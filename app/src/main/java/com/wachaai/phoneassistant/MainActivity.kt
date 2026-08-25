package com.wachaai.phoneassistant

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.wachaai.phoneassistant.ai.AiResult
import com.wachaai.phoneassistant.ai.OpenAiClient
import com.wachaai.phoneassistant.data.CapturedMessage
import com.wachaai.phoneassistant.notifications.ReplyResult
import com.wachaai.phoneassistant.notifications.WhatsAppNotificationListener
import com.wachaai.phoneassistant.risk.RiskClassifier
import com.wachaai.phoneassistant.risk.RiskLevel
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

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
    val messages by app.messageRepository.messages.collectAsState()
    val scope = rememberCoroutineScope()
    val aiClient = remember { OpenAiClient() }
    val suggestions = remember { mutableStateMapOf<String, String>() }
    val loading = remember { mutableStateMapOf<String, Boolean>() }

    var apiKeyInput by remember { mutableStateOf("") }
    var hasApiKey by remember { mutableStateOf(app.secretStore.hasOpenAiApiKey()) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var pendingSensitiveReply by remember { mutableStateOf<SensitiveReply?>(null) }

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
                    "This message involves ${pending.reasons.joinToString()}. Review the exact text before sending:\n\n${pending.reply}",
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        sendReply(pending.message, pending.reply)
                        pendingSensitiveReply = null
                    },
                ) {
                    Text("Send anyway")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingSensitiveReply = null }) {
                    Text("Cancel")
                }
            },
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                text = "Wacha Phone Assistant",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "Stage 1 • WhatsApp assistant",
                style = MaterialTheme.typography.bodyMedium,
            )
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

        statusMessage?.let { message ->
            item {
                Card(colors = CardDefaults.cardColors()) {
                    Text(message, modifier = Modifier.padding(12.dp))
                }
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Captured WhatsApp messages",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                if (messages.isNotEmpty()) {
                    TextButton(onClick = { app.messageRepository.clear() }) {
                        Text("Clear")
                    }
                }
            }
        }

        if (messages.isEmpty()) {
            item {
                Card {
                    Text(
                        text = if (notificationAccess) {
                            "Ready. New WhatsApp notifications will appear here."
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
                suggestedReply = suggestions[message.id],
                isLoading = loading[message.id] == true,
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
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Setup", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                if (notificationAccess) "✓ Notification access granted" else "1. Notification access required",
                fontWeight = if (notificationAccess) FontWeight.SemiBold else FontWeight.Normal,
            )
            OutlinedButton(onClick = onOpenNotificationSettings) {
                Text(if (notificationAccess) "Review notification access" else "Grant notification access")
            }

            HorizontalDivider()

            Text(
                if (hasApiKey) "✓ OpenAI API key saved" else "2. Add your OpenAI API key",
                fontWeight = if (hasApiKey) FontWeight.SemiBold else FontWeight.Normal,
            )
            if (!hasApiKey) {
                OutlinedTextField(
                    value = apiKeyInput,
                    onValueChange = onApiKeyInputChanged,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("OpenAI API key") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                )
                Button(
                    onClick = onSaveApiKey,
                    enabled = apiKeyInput.isNotBlank(),
                ) {
                    Text("Save key securely")
                }
            } else {
                Text(
                    "The key stays encrypted on this device. AI is contacted only when you tap Suggest reply.",
                    style = MaterialTheme.typography.bodySmall,
                )
                TextButton(onClick = onForgetApiKey) {
                    Text("Remove API key")
                }
            }
        }
    }
}

@Composable
private fun MessageCard(
    message: CapturedMessage,
    suggestedReply: String?,
    isLoading: Boolean,
    onSuggestedReplyChanged: (String) -> Unit,
    onSuggestReply: () -> Unit,
    onSendReply: (String) -> Unit,
) {
    val timestamp = remember(message.postedAt) {
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(message.postedAt))
    }

    Card {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    message.sender,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                Text(timestamp, style = MaterialTheme.typography.bodySmall)
            }
            Text(
                if (message.packageName == "com.whatsapp.w4b") "WhatsApp Business" else "WhatsApp",
                style = MaterialTheme.typography.labelSmall,
            )
            Text(message.text)

            Spacer(modifier = Modifier.height(2.dp))

            if (isLoading) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.height(22.dp))
                    Text("Drafting reply…", modifier = Modifier.padding(start = 10.dp))
                }
            } else {
                OutlinedButton(onClick = onSuggestReply) {
                    Text(if (suggestedReply == null) "Suggest reply" else "Regenerate draft")
                }
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
