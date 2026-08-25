package com.wachaai.phoneassistant

import android.app.Application
import com.wachaai.phoneassistant.data.AutoReplyRegistry
import com.wachaai.phoneassistant.data.EncryptedFileStore
import com.wachaai.phoneassistant.data.MessageRepository
import com.wachaai.phoneassistant.data.SecretStore
import com.wachaai.phoneassistant.data.SettingsStore
import com.wachaai.phoneassistant.report.DailyReportWorker

class WachaPhoneAssistantApp : Application() {
    lateinit var messageRepository: MessageRepository
        private set

    lateinit var secretStore: SecretStore
        private set

    lateinit var settingsStore: SettingsStore
        private set

    lateinit var autoReplyRegistry: AutoReplyRegistry
        private set

    override fun onCreate() {
        super.onCreate()
        val encryptedStore = EncryptedFileStore(this)
        messageRepository = MessageRepository(encryptedStore)
        secretStore = SecretStore(encryptedStore)
        settingsStore = SettingsStore(encryptedStore)
        autoReplyRegistry = AutoReplyRegistry(encryptedStore)

        val current = settingsStore.settings.value
        if (current.reportEmail.isBlank() || current.reportEndpoint.isBlank()) {
            settingsStore.saveReportConfig(
                email = current.reportEmail.ifBlank { DEFAULT_REPORT_EMAIL },
                endpoint = current.reportEndpoint.ifBlank { DEFAULT_REPORT_ENDPOINT },
                apiToken = current.reportApiToken,
                hour = current.reportHour,
            )
        }

        DailyReportWorker.schedule(this, settingsStore.settings.value.reportHour)
    }

    companion object {
        private const val DEFAULT_REPORT_EMAIL = "wachaexperience@gmail.com"
        private const val DEFAULT_REPORT_ENDPOINT = "https://wacha-report-relay.netlify.app/api/report"
    }
}
