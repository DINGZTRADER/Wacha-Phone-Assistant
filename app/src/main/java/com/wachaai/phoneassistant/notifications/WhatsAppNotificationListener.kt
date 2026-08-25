package com.wachaai.phoneassistant.notifications

import android.app.Notification
import android.app.RemoteInput
import android.content.Intent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.wachaai.phoneassistant.WachaPhoneAssistantApp
import com.wachaai.phoneassistant.data.CapturedMessage

sealed interface ReplyResult {
    data object Success : ReplyResult
    data object ListenerUnavailable : ReplyResult
    data object NotificationNotFound : ReplyResult
    data object NoReplyAction : ReplyResult
    data class Failure(val reason: String) : ReplyResult
}

class WhatsAppNotificationListener : NotificationListenerService() {
    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        activeNotifications?.forEach(::captureIfWhatsApp)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn?.let(::captureIfWhatsApp)
    }

    private fun captureIfWhatsApp(sbn: StatusBarNotification) {
        if (sbn.packageName !in WHATSAPP_PACKAGES) return
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

        val app = application as WachaPhoneAssistantApp
        app.messageRepository.capture(
            CapturedMessage(
                id = "${sbn.key}:${sbn.postTime}",
                notificationKey = sbn.key,
                packageName = sbn.packageName,
                sender = sender,
                text = text,
                postedAt = sbn.postTime,
                capturedAt = System.currentTimeMillis(),
                hasReplyAction = findReplyTarget(notification) != null,
            ),
        )
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
        private val WHATSAPP_PACKAGES = setOf(
            "com.whatsapp",
            "com.whatsapp.w4b",
        )

        @Volatile
        private var instance: WhatsAppNotificationListener? = null

        fun sendReply(notificationKey: String, text: String): ReplyResult {
            return instance?.sendReplyInternal(notificationKey, text)
                ?: ReplyResult.ListenerUnavailable
        }
    }
}
