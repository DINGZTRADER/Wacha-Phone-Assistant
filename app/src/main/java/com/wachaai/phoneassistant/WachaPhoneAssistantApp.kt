package com.wachaai.phoneassistant

import android.app.Application
import com.wachaai.phoneassistant.data.EncryptedFileStore
import com.wachaai.phoneassistant.data.MessageRepository
import com.wachaai.phoneassistant.data.SecretStore

class WachaPhoneAssistantApp : Application() {
    lateinit var messageRepository: MessageRepository
        private set

    lateinit var secretStore: SecretStore
        private set

    override fun onCreate() {
        super.onCreate()
        val encryptedStore = EncryptedFileStore(this)
        messageRepository = MessageRepository(encryptedStore)
        secretStore = SecretStore(encryptedStore)
    }
}
