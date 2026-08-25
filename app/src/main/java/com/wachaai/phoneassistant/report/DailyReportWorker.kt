package com.wachaai.phoneassistant.report

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.wachaai.phoneassistant.WachaPhoneAssistantApp
import com.wachaai.phoneassistant.finance.DailyReportGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Duration
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class DailyReportWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as WachaPhoneAssistantApp
        val settings = app.settingsStore.settings.value
        if (settings.reportEmail.isBlank() || settings.reportEndpoint.isBlank() || settings.reportApiToken.isBlank()) {
            return Result.success()
        }

        val report = DailyReportGenerator.generate(
            messages = app.messageRepository.messages.value,
            trustedSenderKeys = settings.trustedFinanceSenders,
        )
        val body = DailyReportGenerator.renderPlainText(report)
        val outcome = ReportDeliveryClient.send(
            endpoint = settings.reportEndpoint,
            apiToken = settings.reportApiToken,
            recipient = settings.reportEmail,
            subject = "Wacha Daily Money Report — ${report.date}",
            reportText = body,
        )

        return when (outcome) {
            DeliveryOutcome.Success -> Result.success()
            is DeliveryOutcome.RetryableFailure -> if (runAttemptCount < MAX_RETRIES) Result.retry() else Result.failure()
            is DeliveryOutcome.PermanentFailure -> Result.failure()
        }
    }

    companion object {
        private const val WORK_NAME = "wacha-daily-finance-report"
        private const val MAX_RETRIES = 3
        private val KAMPALA_ZONE = ZoneId.of("Africa/Kampala")

        fun schedule(context: Context, hour: Int) {
            val safeHour = hour.coerceIn(0, 23)
            val now = ZonedDateTime.now(KAMPALA_ZONE)
            var firstRun = now.toLocalDate().atTime(safeHour, 0).atZone(KAMPALA_ZONE)
            if (!firstRun.isAfter(now)) firstRun = firstRun.plusDays(1)
            val initialDelay = Duration.between(now, firstRun).toMillis().coerceAtLeast(0)

            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<DailyReportWorker>(24, TimeUnit.HOURS)
                .setInitialDelay(initialDelay, TimeUnit.MILLISECONDS)
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        }
    }
}

sealed interface DeliveryOutcome {
    data object Success : DeliveryOutcome
    data class RetryableFailure(val reason: String) : DeliveryOutcome
    data class PermanentFailure(val reason: String) : DeliveryOutcome
}

object ReportDeliveryClient {
    suspend fun send(
        endpoint: String,
        apiToken: String,
        recipient: String,
        subject: String,
        reportText: String,
    ): DeliveryOutcome = withContext(Dispatchers.IO) {
        val cleanedEndpoint = endpoint.trim()
        val cleanedToken = apiToken.trim()
        if (!cleanedEndpoint.startsWith("https://")) {
            return@withContext DeliveryOutcome.PermanentFailure("Report endpoint must use HTTPS.")
        }
        if (!recipient.contains('@')) {
            return@withContext DeliveryOutcome.PermanentFailure("Report email is invalid.")
        }
        if (cleanedToken.length < 32) {
            return@withContext DeliveryOutcome.PermanentFailure("Report relay token is invalid.")
        }

        val payload = JSONObject()
            .put("to", recipient.trim())
            .put("subject", subject.replace(Regex("[\\r\\n]+"), " ").take(160))
            .put("text", reportText)
            .toString()

        val timestamp = (System.currentTimeMillis() / 1000L).toString()
        val signature = hmacSha256Hex(cleanedToken, "$timestamp.$payload")

        val connection = (URL(cleanedEndpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 25_000
            doOutput = true
            setRequestProperty("X-Wacha-Timestamp", timestamp)
            setRequestProperty("X-Wacha-Signature", signature)
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
        }

        try {
            connection.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            when {
                status in 200..299 -> DeliveryOutcome.Success
                status in RETRYABLE_STATUS -> DeliveryOutcome.RetryableFailure("Email relay returned HTTP $status.")
                else -> DeliveryOutcome.PermanentFailure("Email relay returned HTTP $status.")
            }
        } catch (error: Throwable) {
            DeliveryOutcome.RetryableFailure(error.message ?: "Report delivery failed.")
        } finally {
            connection.disconnect()
        }
    }

    internal fun hmacSha256Hex(secret: String, value: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(value.toByteArray(Charsets.UTF_8)).joinToString("") { byte -> "%02x".format(byte) }
    }

    private val RETRYABLE_STATUS = setOf(408, 425, 429, 500, 502, 503, 504)
}
