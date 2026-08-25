package com.wachaai.phoneassistant.ai

import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

sealed interface AiResult {
    data class Success(val reply: String) : AiResult
    data class Failure(val message: String) : AiResult
}

class OpenAiClient {
    suspend fun suggestReply(
        apiKey: String,
        sender: String,
        message: String,
    ): AiResult = withContext(Dispatchers.IO) {
        val payload = JSONObject()
            .put("model", MODEL)
            .put("store", false)
            .put("max_output_tokens", 160)
            .put(
                "instructions",
                "You draft concise, natural WhatsApp replies for the phone owner. " +
                    "Return only the proposed reply text. Do not invent facts. " +
                    "Never provide or repeat OTPs, PINs, passwords, passcodes, security codes, " +
                    "bank credentials, or authorize money transfers. For payment, banking, legal, " +
                    "medical, or account-security matters, draft a cautious response that says the " +
                    "owner will review the matter personally rather than making a commitment.",
            )
            .put(
                "input",
                "Sender: ${sender.trim()}\nIncoming WhatsApp message: ${message.trim()}\nDraft the best short reply.",
            )

        var lastFailure = "OpenAI request failed."
        repeat(MAX_ATTEMPTS) { attempt ->
            val outcome = executeRequest(apiKey.trim(), payload.toString())
            when (outcome) {
                is HttpOutcome.Ok -> {
                    val reply = parseOutputText(outcome.body)
                    return@withContext if (reply.isNullOrBlank()) {
                        AiResult.Failure("OpenAI returned no reply text.")
                    } else {
                        AiResult.Success(reply.trim())
                    }
                }
                is HttpOutcome.Error -> {
                    lastFailure = outcome.message
                    if (outcome.status !in RETRYABLE_STATUS_CODES || attempt == MAX_ATTEMPTS - 1) {
                        return@withContext AiResult.Failure(lastFailure)
                    }
                    delay(300L * (attempt + 1))
                }
            }
        }

        AiResult.Failure(lastFailure)
    }

    private fun executeRequest(apiKey: String, jsonBody: String): HttpOutcome {
        if (!apiKey.startsWith("sk-") || apiKey.length < 20) {
            return HttpOutcome.Error(0, "A valid OpenAI API key is required.")
        }

        val connection = (URL(RESPONSES_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            doOutput = true
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
        }

        return try {
            connection.outputStream.use { output ->
                output.write(jsonBody.toByteArray(Charsets.UTF_8))
                output.flush()
            }

            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()

            if (status in 200..299) {
                HttpOutcome.Ok(body)
            } else {
                val apiMessage = runCatching {
                    JSONObject(body).optJSONObject("error")?.optString("message")
                }.getOrNull()
                HttpOutcome.Error(
                    status,
                    apiMessage?.takeIf { it.isNotBlank() } ?: "OpenAI returned HTTP $status.",
                )
            }
        } catch (error: Throwable) {
            HttpOutcome.Error(0, error.message ?: "Network request failed.")
        } finally {
            connection.disconnect()
        }
    }

    private fun parseOutputText(body: String): String? {
        return runCatching {
            val root = JSONObject(body)
            root.optString("output_text").takeIf { it.isNotBlank() }?.let { return@runCatching it }

            val output = root.optJSONArray("output") ?: return@runCatching null
            for (outputIndex in 0 until output.length()) {
                val item = output.optJSONObject(outputIndex) ?: continue
                val content = item.optJSONArray("content") ?: continue
                for (contentIndex in 0 until content.length()) {
                    val part = content.optJSONObject(contentIndex) ?: continue
                    if (part.optString("type") == "output_text") {
                        val text = part.optString("text")
                        if (text.isNotBlank()) return@runCatching text
                    }
                }
            }
            null
        }.getOrNull()
    }

    private sealed interface HttpOutcome {
        data class Ok(val body: String) : HttpOutcome
        data class Error(val status: Int, val message: String) : HttpOutcome
    }

    companion object {
        private const val RESPONSES_URL = "https://api.openai.com/v1/responses"
        private const val MODEL = "gpt-5.6-luna"
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 30_000
        private const val MAX_ATTEMPTS = 2
        private val RETRYABLE_STATUS_CODES = setOf(408, 429, 500, 502, 503, 504)
    }
}
