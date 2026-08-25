package com.wachaai.phoneassistant

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.wachaai.phoneassistant.ai.AiResult
import com.wachaai.phoneassistant.ai.OpenAiClient
import com.wachaai.phoneassistant.data.CapturedMessage
import com.wachaai.phoneassistant.data.MessageSource
import com.wachaai.phoneassistant.finance.DailyReportGenerator
import com.wachaai.phoneassistant.finance.FinanceParser
import com.wachaai.phoneassistant.finance.senderTrustKey
import com.wachaai.phoneassistant.notifications.ReplyResult
import com.wachaai.phoneassistant.notifications.WhatsAppNotificationListener
import com.wachaai.phoneassistant.report.DailyReportWorker
import com.wachaai.phoneassistant.report.DeliveryOutcome
import com.wachaai.phoneassistant.report.ReportDeliveryClient
import com.wachaai.phoneassistant.risk.RiskClassifier
import com.wachaai.phoneassistant.risk.RiskLevel
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    private var notificationAccess by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        notificationAccess = hasNotificationListenerAccess()

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    WachaAssistantScreen(
                        app = application as WachaPhoneAssistantApp,
                        notificationAccess = notificationAccess,
                        openNotificationSettings = {
                            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                        },
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        notificationAccess = hasNotificationListenerAccess()
    }

    private fun hasNotificationListenerAccess(): Boolean {
        val component = ComponentName(this, WhatsAppNotificationListener::class.java)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            getSystemService(NotificationManager::class.java)
                .isNotificationListenerAccessGranted(component)
        } else {
            Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
                ?.split(':')
                ?.any { it == component.flattenToString() }
                ?: false
        }
    }
}

private data class SensitiveReply(
    val message: CapturedMessage,
    val reply: String,
    val reasons: List<String>,
)

@Composable
private fun WachaAssistantScreen(
    app: WachaPhoneAssistantApp,
    notificationAccess: Boolean,
    openNotificationSettings: () -> Unit,
) {
    val context = LocalContext.current
    val messages by app.messageRepository.messages.collectAsState()
    val settings by app.settingsStore.settings.collectAsState()
    val scope = rememberCoroutineScope()
    val aiClient = remember { OpenAiClient() }
    val suggestions = remember { mutableStateMapOf<String, String>() }
    val loading = remember { mutableStateMapOf<String, Boolean>() }

    var ttsReady by remember { mutableStateOf(false) }
    val tts = remember {
        TextToSpeech(context) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
        }
    }
    DisposableEffect(tts) {
        if (ttsReady) tts.language = Locale.UK
        onDispose { tts.shutdown() }
    }

    var apiKeyInput by remember { mutableStateOf("") }
    var hasApiKey by remember { mutableStateOf(app.secretStore.hasOpenAiApiKey()) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var pendingSensitiveReply by remember { mutableStateOf<SensitiveReply?>(null) }
    var showReport by remember { mutableStateOf(false) }

    var reportEmail by remember(settings.reportEmail) { mutableStateOf(settings.reportEmail) }
    var reportEndpoint by remember(settings.reportEndpoint) { mutableStateOf(settings.reportEndpoint) }
    var reportToken by remember(settings.reportApiToken) { mutableStateOf(settings.reportApiToken) }
    var reportHour by remember(settings.reportHour) { mutableStateOf(settings.reportHour.toString()) }

    val report = remember(messages, settings.trustedFinanceSenders) {
        DailyReportGenerator.generate(messages, settings.trustedFinanceSenders)
    }
    val reportText = remember(report) { DailyReportGenerator.renderPlainText(report) }

    fun speak(text: String) {
        if (!ttsReady) {
            statusMessage = "Text-to-speech is not ready yet."
            return
        }
        tts.language = Locale.UK
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "wacha-message")
    }

    val sendReply: (CapturedMessage, String) -> Unit = { message, reply ->
        statusMessage = when (val result = WhatsAppNotificationListener.sendReply(message.notificationKey, reply)) {
            ReplyResult.Success -> "Reply sent to ${message.sender}."
            ReplyResult.ListenerUnavailable -> "Notification access is not active."
            ReplyResult.NotificationNotFound -> "That WhatsApp notification is no longer active. Open WhatsApp to reply manually."
            ReplyResult.NoReplyAction -> "WhatsApp did not expose a quick-reply action for this notification."
            is ReplyResult.Failure -> "Reply failed: ${result.reason}"
        }
    }

    pendingSensitiveReply?.let { pending ->
        AlertDialog(
            onDismissRequest = { pendingSensitiveReply = null },
            title = { Text("Sensitive reply — confirm") },
            text = {
                Text(
                    "This involves ${pending.reasons.joinToString()}. Review the exact reply before sending:\n\n${pending.reply}",
                )
            },
            confirmButton = {
                Button(onClick = {
                    sendReply(pending.message, pending.reply)
                    pendingSensitiveReply = null
                }) { Text("Send anyway") }
            },
            dismissButton = {
                TextButton(onClick = { pendingSensitiveReply = null }) { Text("Cancel") }
            },
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("Wacha Phone Assistant", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("WhatsApp • SMS • Daily Money Report", style = MaterialTheme.typography.bodyMedium)
        }

        item {
            SetupCard(
                notificationAccess = notificationAccess,
                hasApiKey = hasApiKey,
                apiKeyInput = apiKeyInput,
                onApiKeyInputChanged = { apiKeyInput = it },
                onOpenNotificationSettings = openNotificationSettings,
                onSaveApiKey = {
                    runCatching { app.secretStore.saveOpenAiApiKey(apiKeyInput) }
                        .onSuccess {
                            apiKeyInput = ""
                            hasApiKey = true
                            statusMessage = "OpenAI API key saved in Android Keystore-backed encrypted storage."
                        }
                        .onFailure { statusMessage = it.message ?: "Could not save API key." }
                },
                onForgetApiKey = {
                    app.secretStore.clearOpenAiApiKey()
                    hasApiKey = false
                    apiKeyInput = ""
                    statusMessage = "OpenAI API key removed."
                },
            )
        }

        item {
            DailyReportSetupCard(
                email = reportEmail,
                endpoint = reportEndpoint,
                token = reportToken,
                hour = reportHour,
                onEmailChanged = { reportEmail = it },
                onEndpointChanged = { reportEndpoint = it },
                onTokenChanged = { reportToken = it },
                onHourChanged = { reportHour = it.filter(Char::isDigit).take(2) },
                onSave = {
                    val hour = reportHour.toIntOrNull()
                    if (hour == null || hour !in 0..23) {
                        statusMessage = "Choose a report hour from 0 to 23."
                    } else {
                        runCatching {
                            app.settingsStore.saveReportConfig(reportEmail, reportEndpoint, reportToken, hour)
                            DailyReportWorker.schedule(context, hour)
                        }.onSuccess {
                            statusMessage = "Daily report setup saved. The phone will prepare the report each evening and retry when internet is available."
                        }.onFailure {
                            statusMessage = it.message ?: "Could not save report settings."
                        }
                    }
                },
                onSendNow = {
                    scope.launch {
                        if (reportEmail.isBlank() || reportEndpoint.isBlank() || reportToken.isBlank()) {
                            statusMessage = "Save the report email, secure relay address and token first."
                        } else {
                            statusMessage = "Sending today's report…"
                            statusMessage = when (
                                val outcome = ReportDeliveryClient.send(
                                    endpoint = reportEndpoint,
                                    apiToken = reportToken,
                                    recipient = reportEmail,
                                    subject = "Wacha Daily Money Report — ${report.date}",
                                    reportText = reportText,
                                )
                            ) {
                                DeliveryOutcome.Success -> "Today's money report was sent."
                                is DeliveryOutcome.RetryableFailure -> "Report could not be sent yet: ${outcome.reason}"
                                is DeliveryOutcome.PermanentFailure -> "Report setup error: ${outcome.reason}"
                            }
                        }
                    }
                },
            )
        }

        item {
            Card {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Today's money report", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("Liquidity in: UGX ${report.totalLiquidityIn.toPlainString()}")
                    Text("External out: UGX ${report.totalExternalOut.toPlainString()}")
                    Text("Savings moved in: UGX ${report.savingsDeposited.toPlainString()}")
                    Text("Interest/rewards: UGX ${report.interestEarned.toPlainString()}")
                    Text("Unverified finance SMS excluded: ${report.unverifiedFinancialMessages.size}")
                    TextButton(onClick = { showReport = !showReport }) {
                        Text(if (showReport) "Hide full report" else "Show full report")
                    }
                    if (showReport) Text(reportText, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        statusMessage?.let { message ->
            item { Card { Text(message, modifier = Modifier.padding(12.dp)) } }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Messages",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                if (messages.isNotEmpty()) {
                    TextButton(onClick = { app.messageRepository.clear() }) { Text("Clear") }
                }
            }
        }

        if (messages.isEmpty()) {
            item {
                Card {
                    Text(
                        if (notificationAccess) {
                            "Ready. New WhatsApp and SMS notifications will appear here."
                        } else {
                            "Grant notification access first."
                        },
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }

        items(messages, key = { it.id }) { message ->
            MessageCard(
                message = message,
                accountLabel = settings.accountLabels[message.accountFingerprint],
                isFinanceTrusted = message.senderTrustKey() in settings.trustedFinanceSenders,
                autoReplyEnabled = message.conversationKey() in settings.autoReplyConversationKeys,
                suggestedReply = suggestions[message.id],
                isLoading = loading[message.id] == true,
                onReadAloud = { speak("Message from ${message.sender}. ${message.text}") },
                onAssignAirtel = { app.settingsStore.assignAccountLabel(message.accountFingerprint, AIRTEL_WHATSAPP_LABEL) },
                onAssignMtn = { app.settingsStore.assignAccountLabel(message.accountFingerprint, MTN_WHATSAPP_LABEL) },
                onToggleFinanceTrust = { trusted -> app.settingsStore.setTrustedFinanceSender(message.sender, trusted) },
                onToggleAutoReply = { enabled -> app.settingsStore.setAutoReply(message.conversationKey(), enabled) },
                onSuggestedReplyChanged = { suggestions[message.id] = it },
                onSuggestReply = {
                    val apiKey = app.secretStore.getOpenAiApiKey()
                    if (apiKey == null) {
                        statusMessage = "Save an OpenAI API key before requesting an AI draft."
                    } else {
                        scope.launch {
                            loading[message.id] = true
                            when (val result = aiClient.suggestReply(apiKey, message.sender, message.text)) {
                                is AiResult.Success -> {
                                    suggestions[message.id] = result.reply
                                    statusMessage = "Draft ready. Review it before sending."
                                }
                                is AiResult.Failure -> statusMessage = result.message
                            }
                            loading[message.id] = false
                        }
                    }
                },
                onSendReply = { reply ->
                    val assessment = RiskClassifier.assess(message.text, reply)
                    if (assessment.level == RiskLevel.SENSITIVE) {
                        pendingSensitiveReply = SensitiveReply(message, reply, assessment.reasons)
                    } else {
                        sendReply(message, reply)
                    }
                },
            )
        }
    }
}

@Composable
private fun SetupCard(
    notificationAccess: Boolean,
    hasApiKey: Boolean,
    apiKeyInput: String,
    onApiKeyInputChanged: (String) -> Unit,
    onOpenNotificationSettings: () -> Unit,
    onSaveApiKey: () -> Unit,
    onForgetApiKey: () -> Unit,
) {
    Card {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Assistant setup", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(if (notificationAccess) "✓ Message notification access granted" else "1. Message notification access required")
            OutlinedButton(onClick = onOpenNotificationSettings) {
                Text(if (notificationAccess) "Review notification access" else "Grant notification access")
            }
            Text("This permission lets Wacha read new WhatsApp and default-SMS-app notifications. It does not scrape WhatsApp's database.")
            HorizontalDivider()
            Text(if (hasApiKey) "✓ OpenAI API key saved" else "2. Add your OpenAI API key")
            if (!hasApiKey) {
                OutlinedTextField(
                    value = apiKeyInput,
                    onValueChange = onApiKeyInputChanged,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("OpenAI API key") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                )
                Button(onClick = onSaveApiKey, enabled = apiKeyInput.isNotBlank()) { Text("Save key securely") }
            } else {
                Text("The key is encrypted on this device. AI is used for drafts and for chats you explicitly allow to auto-answer.")
                TextButton(onClick = onForgetApiKey) { Text("Remove API key") }
            }
        }
    }
}

@Composable
private fun DailyReportSetupCard(
    email: String,
    endpoint: String,
    token: String,
    hour: String,
    onEmailChanged: (String) -> Unit,
    onEndpointChanged: (String) -> Unit,
    onTokenChanged: (String) -> Unit,
    onHourChanged: (String) -> Unit,
    onSave: () -> Unit,
    onSendNow: () -> Unit,
) {
    Card {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Daily email report", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("Raw WhatsApp/SMS text stays encrypted on your phone. Only the finished daily report is sent to the secure mail relay.")
            OutlinedTextField(email, onEmailChanged, Modifier.fillMaxWidth(), label = { Text("Send report to email") }, singleLine = true)
            OutlinedTextField(hour, onHourChanged, Modifier.fillMaxWidth(), label = { Text("Evening hour (24-hour clock)") }, singleLine = true)
            OutlinedTextField(endpoint, onEndpointChanged, Modifier.fillMaxWidth(), label = { Text("Secure report relay URL") }, singleLine = true)
            OutlinedTextField(
                token,
                onTokenChanged,
                Modifier.fillMaxWidth(),
                label = { Text("Report relay token") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onSave) { Text("Save report setup") }
                OutlinedButton(onClick = onSendNow) { Text("Send today's report now") }
            }
        }
    }
}

@Composable
private fun MessageCard(
    message: CapturedMessage,
    accountLabel: String?,
    isFinanceTrusted: Boolean,
    autoReplyEnabled: Boolean,
    suggestedReply: String?,
    isLoading: Boolean,
    onReadAloud: () -> Unit,
    onAssignAirtel: () -> Unit,
    onAssignMtn: () -> Unit,
    onToggleFinanceTrust: (Boolean) -> Unit,
    onToggleAutoReply: (Boolean) -> Unit,
    onSuggestedReplyChanged: (String) -> Unit,
    onSuggestReply: () -> Unit,
    onSendReply: (String) -> Unit,
) {
    val timestamp = remember(message.postedAt) {
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(message.postedAt))
    }
    val isWhatsApp = message.source != MessageSource.SMS
    val financial = FinanceParser.parse(message)

    Card {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(message.sender, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text(timestamp, style = MaterialTheme.typography.bodySmall)
            }
            Text(
                when (message.source) {
                    MessageSource.WHATSAPP -> accountLabel ?: message.accountHint ?: "WhatsApp • line not assigned"
                    MessageSource.WHATSAPP_BUSINESS -> accountLabel ?: message.accountHint ?: "WhatsApp Business • line not assigned"
                    MessageSource.SMS -> "SMS"
                },
                style = MaterialTheme.typography.labelMedium,
            )
            Text(message.text)
            OutlinedButton(onClick = onReadAloud) { Text("Read aloud") }

            if (isWhatsApp && accountLabel == null) {
                Text("Which of your WhatsApp numbers received this chat? Assign it once so Wacha can label that notification channel.")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onAssignAirtel) { Text("Airtel 0704") }
                    TextButton(onClick = onAssignMtn) { Text("MTN 0774") }
                }
            }

            if (financial != null) {
                HorizontalDivider()
                Text("Finance detected: ${financial.type.name.replace('_', ' ')}")
                financial.amount?.let { Text("Amount: UGX ${it.toPlainString()}") }
                financial.fee?.let { Text("Fee/charge: UGX ${it.toPlainString()}") }
                financial.balance?.let { Text("Balance found: UGX ${it.toPlainString()}") }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (isFinanceTrusted) "Included in reports" else "Excluded until sender is trusted", modifier = Modifier.weight(1f))
                    Switch(checked = isFinanceTrusted, onCheckedChange = onToggleFinanceTrust)
                }
            }

            if (isWhatsApp) {
                HorizontalDivider()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Auto-answer this chat", fontWeight = FontWeight.SemiBold)
                        Text("Only normal low-risk messages. Sensitive messages remain manual.", style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = autoReplyEnabled, onCheckedChange = onToggleAutoReply)
                }

                OutlinedButton(onClick = onSuggestReply, enabled = !isLoading) {
                    Text(if (isLoading) "Drafting…" else if (suggestedReply == null) "Suggest reply" else "Regenerate draft")
                }
                suggestedReply?.let { reply ->
                    OutlinedTextField(
                        value = reply,
                        onValueChange = onSuggestedReplyChanged,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Review/edit reply") },
                        minLines = 2,
                    )
                    Button(
                        onClick = { onSendReply(reply) },
                        enabled = reply.isNotBlank() && message.hasReplyAction,
                    ) {
                        Text(if (message.hasReplyAction) "Send reply" else "Quick reply unavailable")
                    }
                }
            }
        }
    }
}

private const val AIRTEL_WHATSAPP_LABEL = "Airtel WhatsApp • 0704 650 600"
private const val MTN_WHATSAPP_LABEL = "MTN WhatsApp • 0774 178 738"
