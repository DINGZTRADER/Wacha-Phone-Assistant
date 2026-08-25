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


data class CapturedMessage(
    val id: String,
    val notificationKey: String,
    val packageName: String,
    val sender: String,
    val text: String,
    val postedAt: Long,
    val capturedAt: Long,
    val hasReplyAction: Boolean,
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
                if (existingIndex >= 0) {
                    current[existingIndex] = message
                } else {
                    current.add(message)
                }

                val normalized = current
                    .sortedByDescending { it.postedAt }
                    .take(MAX_MESSAGES)

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
                add(
                    CapturedMessage(
                        id = item.getString("id"),
                        notificationKey = item.getString("notificationKey"),
                        packageName = item.getString("packageName"),
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
                    .put("sender", message.sender)
                    .put("text", message.text)
                    .put("postedAt", message.postedAt)
                    .put("capturedAt", message.capturedAt)
                    .put("hasReplyAction", message.hasReplyAction),
            )
        }
        encryptedStore.write(MESSAGES_FILE, array.toString())
    }

    companion object {
        private const val MESSAGES_FILE = "messages.enc"
        private const val MAX_MESSAGES = 250
    }
}
