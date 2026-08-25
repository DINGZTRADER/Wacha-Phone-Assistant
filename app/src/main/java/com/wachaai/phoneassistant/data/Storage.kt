package com.wachaai.phoneassistant.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

enum class MessageSource {
    WHATSAPP,
    WHATSAPP_BUSINESS,
    SMS,
}

data class CapturedMessage(
    val id: String,
    val notificationKey: String,
    val packageName: String,
    val source: MessageSource,
    val accountFingerprint: String,
    val accountHint: String?,
    val sender: String,
    val text: String,
    val postedAt: Long,
    val capturedAt: Long,
    val hasReplyAction: Boolean,
) {
    fun conversationKey(): String = "$accountFingerprint|${sender.trim().lowercase()}"
}

data class AssistantSettings(
    val reportEmail: String = "",
    val reportEndpoint: String = "",
    val reportApiToken: String = "",
    val reportHour: Int = 19,
    val trustedFinanceSenders: Set<String> = emptySet(),
    val accountLabels: Map<String, String> = emptyMap(),
    val autoReplyConversationKeys: Set<String> = emptySet(),
)

class EncryptedFileStore(
    context: Context,
    private val keyAlias: String = "wacha_phone_assistant_aes_v1",
) {
    private val directory = File(context.filesDir, "secure").apply { mkdirs() }
    private val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    @Synchronized
    fun write(fileName: String, plaintext: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val payload = ByteArray(cipher.iv.size + ciphertext.size)
        System.arraycopy(cipher.iv, 0, payload, 0, cipher.iv.size)
        System.arraycopy(ciphertext, 0, payload, cipher.iv.size, ciphertext.size)
        val encoded = Base64.encode(payload, Base64.NO_WRAP)

        val atomicFile = AtomicFile(File(directory, fileName))
        val stream = atomicFile.startWrite()
        try {
            stream.write(encoded)
            stream.fd.sync()
            atomicFile.finishWrite(stream)
        } catch (error: Throwable) {
            atomicFile.failWrite(stream)
            throw error
        }
    }

    @Synchronized
    fun read(fileName: String): String? {
        val file = File(directory, fileName)
        if (!file.exists()) return null

        val encoded = AtomicFile(file).readFully()
        val payload = Base64.decode(encoded, Base64.NO_WRAP)
        require(payload.size > IV_LENGTH_BYTES) { "Encrypted payload is invalid." }

        val iv = payload.copyOfRange(0, IV_LENGTH_BYTES)
        val ciphertext = payload.copyOfRange(IV_LENGTH_BYTES, payload.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
        return cipher.doFinal(ciphertext).toString(Charsets.UTF_8)
    }

    @Synchronized
    fun delete(fileName: String) {
        AtomicFile(File(directory, fileName)).delete()
    }

    private fun getOrCreateKey(): SecretKey {
        (keyStore.getKey(keyAlias, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            "AndroidKeyStore",
        )
        val spec = KeyGenParameterSpec.Builder(
            keyAlias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(false)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }

    companion object {
        private const val IV_LENGTH_BYTES = 12
    }
}

class SecretStore(private val encryptedStore: EncryptedFileStore) {
    fun saveOpenAiApiKey(apiKey: String) {
        val cleaned = apiKey.trim()
        require(cleaned.startsWith("sk-") && cleaned.length >= 20) {
            "That does not look like an OpenAI API key."
        }
        encryptedStore.write(API_KEY_FILE, cleaned)
    }

    fun getOpenAiApiKey(): String? = encryptedStore.read(API_KEY_FILE)?.trim()?.takeIf { it.isNotEmpty() }

    fun hasOpenAiApiKey(): Boolean = runCatching { getOpenAiApiKey() != null }.getOrDefault(false)

    fun clearOpenAiApiKey() = encryptedStore.delete(API_KEY_FILE)

    companion object {
        private const val API_KEY_FILE = "openai_api_key.enc"
    }
}

class SettingsStore(private val encryptedStore: EncryptedFileStore) {
    private val lock = Any()
    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<AssistantSettings> = _settings.asStateFlow()

    fun saveReportConfig(email: String, endpoint: String, apiToken: String, hour: Int) {
        require(hour in 0..23) { "Report hour must be between 0 and 23." }
        update {
            it.copy(
                reportEmail = email.trim(),
                reportEndpoint = endpoint.trim(),
                reportApiToken = apiToken.trim(),
                reportHour = hour,
            )
        }
    }

    fun assignAccountLabel(fingerprint: String, label: String) {
        if (fingerprint.isBlank()) return
        update { current ->
            current.copy(accountLabels = current.accountLabels + (fingerprint to label.trim()))
        }
    }

    fun accountLabel(fingerprint: String): String? = settings.value.accountLabels[fingerprint]

    fun setTrustedFinanceSender(sender: String, trusted: Boolean) {
        val key = sender.trim().lowercase()
        if (key.isBlank()) return
        update { current ->
            val next = current.trustedFinanceSenders.toMutableSet()
            if (trusted) next += key else next -= key
            current.copy(trustedFinanceSenders = next)
        }
    }

    fun isTrustedFinanceSender(sender: String): Boolean =
        sender.trim().lowercase() in settings.value.trustedFinanceSenders

    fun setAutoReply(conversationKey: String, enabled: Boolean) {
        update { current ->
            val next = current.autoReplyConversationKeys.toMutableSet()
            if (enabled) next += conversationKey else next -= conversationKey
            current.copy(autoReplyConversationKeys = next)
        }
    }

    fun isAutoReplyEnabled(conversationKey: String): Boolean =
        conversationKey in settings.value.autoReplyConversationKeys

    private fun update(transform: (AssistantSettings) -> AssistantSettings) {
        synchronized(lock) {
            val next = transform(_settings.value)
            _settings.value = next
            persist(next)
        }
    }

    private fun load(): AssistantSettings = runCatching {
        val raw = encryptedStore.read(SETTINGS_FILE) ?: return AssistantSettings()
        val json = JSONObject(raw)
        val trusted = json.optJSONArray("trustedFinanceSenders").toStringSet()
        val autoReply = json.optJSONArray("autoReplyConversationKeys").toStringSet()
        val labelsJson = json.optJSONObject("accountLabels")
        val labels = buildMap {
            if (labelsJson != null) {
                val keys = labelsJson.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    put(key, labelsJson.optString(key))
                }
            }
        }
        AssistantSettings(
            reportEmail = json.optString("reportEmail"),
            reportEndpoint = json.optString("reportEndpoint"),
            reportApiToken = json.optString("reportApiToken"),
            reportHour = json.optInt("reportHour", 19).coerceIn(0, 23),
            trustedFinanceSenders = trusted,
            accountLabels = labels,
            autoReplyConversationKeys = autoReply,
        )
    }.getOrElse { AssistantSettings() }

    private fun persist(settings: AssistantSettings) {
        val labels = JSONObject()
        settings.accountLabels.forEach { (key, value) -> labels.put(key, value) }
        val json = JSONObject()
            .put("reportEmail", settings.reportEmail)
            .put("reportEndpoint", settings.reportEndpoint)
            .put("reportApiToken", settings.reportApiToken)
            .put("reportHour", settings.reportHour)
            .put("trustedFinanceSenders", JSONArray(settings.trustedFinanceSenders.toList()))
            .put("accountLabels", labels)
            .put("autoReplyConversationKeys", JSONArray(settings.autoReplyConversationKeys.toList()))
        encryptedStore.write(SETTINGS_FILE, json.toString())
    }

    private fun JSONArray?.toStringSet(): Set<String> {
        if (this == null) return emptySet()
        return buildSet {
            for (index in 0 until length()) {
                optString(index).takeIf { it.isNotBlank() }?.let(::add)
            }
        }
    }

    companion object {
        private const val SETTINGS_FILE = "assistant_settings.enc"
    }
}

class AutoReplyRegistry(private val encryptedStore: EncryptedFileStore) {
    private val lock = Any()

    fun claim(messageId: String): Boolean = synchronized(lock) {
        val current = load().toMutableList()
        if (messageId in current) return@synchronized false
        current.add(messageId)
        encryptedStore.write(FILE, JSONArray(current.takeLast(MAX_IDS)).toString())
        true
    }

    private fun load(): List<String> = runCatching {
        val raw = encryptedStore.read(FILE) ?: return emptyList()
        val array = JSONArray(raw)
        buildList {
            for (index in 0 until array.length()) {
                array.optString(index).takeIf { it.isNotBlank() }?.let(::add)
            }
        }
    }.getOrElse { emptyList() }

    companion object {
        private const val FILE = "auto_reply_registry.enc"
        private const val MAX_IDS = 500
    }
}

class MessageRepository(private val encryptedStore: EncryptedFileStore) {
    private val lock = Any()
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _messages = MutableStateFlow(loadMessages())
    val messages: StateFlow<List<CapturedMessage>> = _messages.asStateFlow()

    fun capture(message: CapturedMessage) {
        ioScope.launch {
            synchronized(lock) {
                val current = _messages.value.toMutableList()
                val existingIndex = current.indexOfFirst { it.id == message.id }
                if (existingIndex >= 0) current[existingIndex] = message else current.add(message)

                val normalized = current.sortedByDescending { it.postedAt }.take(MAX_MESSAGES)
                _messages.value = normalized
                persist(normalized)
            }
        }
    }

    fun clear() {
        ioScope.launch {
            synchronized(lock) {
                _messages.value = emptyList()
                encryptedStore.delete(MESSAGES_FILE)
            }
        }
    }

    private fun loadMessages(): List<CapturedMessage> = runCatching {
        val raw = encryptedStore.read(MESSAGES_FILE) ?: return emptyList()
        val array = JSONArray(raw)
        buildList {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                val packageName = item.getString("packageName")
                val source = item.optString("source").takeIf { it.isNotBlank() }
                    ?.let { runCatching { MessageSource.valueOf(it) }.getOrNull() }
                    ?: inferSource(packageName)
                add(
                    CapturedMessage(
                        id = item.getString("id"),
                        notificationKey = item.getString("notificationKey"),
                        packageName = packageName,
                        source = source,
                        accountFingerprint = item.optString("accountFingerprint").ifBlank { packageName },
                        accountHint = item.optString("accountHint").takeIf { it.isNotBlank() },
                        sender = item.getString("sender"),
                        text = item.getString("text"),
                        postedAt = item.getLong("postedAt"),
                        capturedAt = item.getLong("capturedAt"),
                        hasReplyAction = item.getBoolean("hasReplyAction"),
                    ),
                )
            }
        }.sortedByDescending { it.postedAt }
    }.getOrElse { emptyList() }

    private fun persist(messages: List<CapturedMessage>) {
        val array = JSONArray()
        messages.forEach { message ->
            array.put(
                JSONObject()
                    .put("id", message.id)
                    .put("notificationKey", message.notificationKey)
                    .put("packageName", message.packageName)
                    .put("source", message.source.name)
                    .put("accountFingerprint", message.accountFingerprint)
                    .put("accountHint", message.accountHint ?: "")
                    .put("sender", message.sender)
                    .put("text", message.text)
                    .put("postedAt", message.postedAt)
                    .put("capturedAt", message.capturedAt)
                    .put("hasReplyAction", message.hasReplyAction),
            )
        }
        encryptedStore.write(MESSAGES_FILE, array.toString())
    }

    private fun inferSource(packageName: String): MessageSource = when (packageName) {
        "com.whatsapp" -> MessageSource.WHATSAPP
        "com.whatsapp.w4b" -> MessageSource.WHATSAPP_BUSINESS
        else -> MessageSource.SMS
    }

    companion object {
        private const val MESSAGES_FILE = "messages.enc"
        private const val MAX_MESSAGES = 1000
    }
}
