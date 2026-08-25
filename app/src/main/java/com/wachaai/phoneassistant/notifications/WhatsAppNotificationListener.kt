package com.wachaai.phoneassistant.notifications

import android.app.Notification
import android.app.NotificationManager
import android.app.RemoteInput
import android.content.Intent
import android.os.Bundle
import android.provider.Telephony
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.wachaai.phoneassistant.WachaPhoneAssistantApp
import com.wachaai.phoneassistant.ai.AiResult
import com.wachaai.phoneassistant.ai.OpenAiClient
import com.wachaai.phoneassistant.data.CapturedMessage
import com.wachaai.phoneassistant.data.MessageSource
import com.wachaai.phoneassistant.intelligence.ReplyStyle
import com.wachaai.phoneassistant.risk.RiskClassifier
import com.wachaai.phoneassistant.risk.RiskLevel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

sealed interface ReplyResult {
    data object Success : ReplyResult
    data object ListenerUnavailable : ReplyResult
    data object NotificationNotFound : ReplyResult
    data object NoReplyAction : ReplyResult
    data class Failure(val reason: String) : ReplyResult
}

class WhatsAppNotificationListener : NotificationListenerService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val aiClient = OpenAiClient()

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        activeNotifications?.forEach { captureSupportedMessage(it, allowAutoReply = false) }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn?.let { captureSupportedMessage(it, allowAutoReply = true) }
    }

    private fun captureSupportedMessage(sbn: StatusBarNotification, allowAutoReply: Boolean) {
        val source = sourceForPackage(sbn.packageName) ?: return
        val notification = sbn.notification
        if ((notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return

        val extras = notification.extras
        val sender = extras.getCharSequence(Notification.EXTRA_TITLE)
            ?.toString()
            ?.trim()
            .orEmpty()
        val text = (
            extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
                ?: extras.getCharSequence(Notification.EXTRA_TEXT)
            )
            ?.toString()
            ?.trim()
            .orEmpty()

        if (sender.isBlank() || text.isBlank()) return

        val accountHint = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)
            ?.toString()
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        val fingerprint = buildAccountFingerprint(sbn.packageName, notification, accountHint, source)
        val replyTarget = if (source == MessageSource.SMS) null else findReplyTarget(notification)

        val message = CapturedMessage(
            id = "${sbn.key}:${sbn.postTime}",
            notificationKey = sbn.key,
            packageName = sbn.packageName,
            source = source,
            accountFingerprint = fingerprint,
            accountHint = accountHint,
            sender = sender,
            text = text,
            postedAt = sbn.postTime,
            capturedAt = System.currentTimeMillis(),
            hasReplyAction = replyTarget != null,
        )

        val app = application as WachaPhoneAssistantApp
        app.messageRepository.capture(message)

        if (!allowAutoReply || source == MessageSource.SMS || replyTarget == null) return
        val conversationKey = message.conversationKey()
        if (!app.settingsStore.isAutoReplyEnabled(conversationKey)) return
        val apiKey = app.secretStore.getOpenAiApiKey() ?: return
        if (!app.autoReplyRegistry.claim(message.id)) return

        val style = ReplyStyle.fromStored(app.settingsStore.replyStyleName(conversationKey))
        val guidance = app.settingsStore.replyGuidance(conversationKey)

        serviceScope.launch {
            when (
                val draft = aiClient.suggestReply(
                    apiKey = apiKey,
                    sender = message.sender,
                    message = message.text,
                    style = style,
                    guidance = guidance,
                )
            ) {
                is AiResult.Success -> {
                    val assessment = RiskClassifier.assess(message.text, draft.reply)
                    if (assessment.level == RiskLevel.NORMAL) {
                        sendReplyInternal(message.notificationKey, draft.reply)
                    }
                }
                is AiResult.Failure -> Unit
            }
        }
    }

    private fun sourceForPackage(packageName: String): MessageSource? {
        return when (packageName) {
            "com.whatsapp" -> MessageSource.WHATSAPP
            "com.whatsapp.w4b" -> MessageSource.WHATSAPP_BUSINESS
            Telephony.Sms.getDefaultSmsPackage(this) -> MessageSource.SMS
            else -> null
        }
    }

    private fun buildAccountFingerprint(
        packageName: String,
        notification: Notification,
        accountHint: String?,
        source: MessageSource,
    ): String {
        if (source == MessageSource.SMS) return "sms|$packageName"

        val channelId = notification.channelId.orEmpty()
        val channelGroup = runCatching {
            getSystemService(NotificationManager::class.java)
                .getNotificationChannel(channelId)
                ?.group
                .orEmpty()
        }.getOrDefault("")

        return listOf(packageName, channelGroup, accountHint.orEmpty(), channelId)
            .joinToString("|")
    }

    private fun sendReplyInternal(notificationKey: String, text: String): ReplyResult {
        val cleaned = text.trim()
        if (cleaned.isBlank()) return ReplyResult.Failure("Reply is empty.")

        val sbn = activeNotifications?.firstOrNull { it.key == notificationKey }
            ?: return ReplyResult.NotificationNotFound
        val target = findReplyTarget(sbn.notification) ?: return ReplyResult.NoReplyAction

        return try {
            val fillInIntent = Intent()
            val results = Bundle()
            target.remoteInputs.forEach { input ->
                results.putCharSequence(input.resultKey, cleaned)
            }
            RemoteInput.addResultsToIntent(target.remoteInputs.toTypedArray(), fillInIntent, results)
            target.action.actionIntent.send(this, 0, fillInIntent)
            ReplyResult.Success
        } catch (error: Throwable) {
            ReplyResult.Failure(error.message ?: error.javaClass.simpleName)
        }
    }

    private fun findReplyTarget(notification: Notification): ReplyTarget? {
        return notification.actions
            ?.asSequence()
            ?.mapNotNull { action ->
                val inputs = action.remoteInputs
                    ?.filter { it.allowFreeFormInput }
                    .orEmpty()
                if (inputs.isEmpty()) null else ReplyTarget(action, inputs)
            }
            ?.firstOrNull()
    }

    private data class ReplyTarget(
        val action: Notification.Action,
        val remoteInputs: List<RemoteInput>,
    )

    companion object {
        @Volatile
        private var instance: WhatsAppNotificationListener? = null

        fun sendReply(notificationKey: String, text: String): ReplyResult {
            return instance?.sendReplyInternal(notificationKey, text)
                ?: ReplyResult.ListenerUnavailable
        }
    }
}
