package com.example.data.repository

import android.content.Context
import com.example.data.local.ChatDao
import com.example.data.local.ChatMessageEntity
import com.example.data.local.ConversationEntity
import com.example.data.model.ChatMessage
import com.example.data.model.Conversation
import com.example.data.model.MessageRole
import com.example.data.preferences.SettingsManager
import com.example.data.remote.NvidiaApiClient
import com.example.data.remote.NvidiaApiException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

class ChatRepository(
    private val chatDao: ChatDao,
    private val settingsManager: SettingsManager,
    private val apiClient: NvidiaApiClient
) {
    val allConversations: Flow<List<Conversation>> =
        chatDao.getAllConversations().map { entities ->
            entities.map { it.toDomain() }
        }

    fun getMessagesForConversation(conversationId: String): Flow<List<ChatMessage>> =
        chatDao.getMessagesForConversation(conversationId).map { entities ->
            entities.map { it.toDomain() }
        }

    suspend fun createNewConversation(
        title: String = "New Chat",
        modelName: String = settingsManager.getSelectedModel(),
        isIncognito: Boolean = settingsManager.isIncognito()
    ): Conversation {
        val conv = Conversation(
            id = UUID.randomUUID().toString(),
            title = title,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
            modelName = modelName,
            isIncognito = isIncognito
        )
        if (!isIncognito) {
            chatDao.insertConversation(ConversationEntity.fromDomain(conv))
        }
        return conv
    }

    suspend fun getConversationById(id: String): Conversation? {
        return chatDao.getConversationById(id)?.toDomain()
    }

    suspend fun updateConversationTitle(id: String, title: String) {
        val entity = chatDao.getConversationById(id)
        if (entity != null) {
            chatDao.updateConversation(entity.copy(title = title, updatedAt = System.currentTimeMillis()))
        }
    }

    suspend fun saveMessage(message: ChatMessage, isIncognito: Boolean) {
        if (!isIncognito) {
            // Ensure conversation exists
            val existing = chatDao.getConversationById(message.conversationId)
            if (existing == null) {
                val title = if (message.role == MessageRole.USER) {
                    message.content.take(30).ifBlank { "Chat ${System.currentTimeMillis() % 1000}" }
                } else "Chat"
                chatDao.insertConversation(
                    ConversationEntity(
                        id = message.conversationId,
                        title = title,
                        createdAt = System.currentTimeMillis(),
                        updatedAt = System.currentTimeMillis(),
                        modelName = message.modelUsed,
                        isIncognito = false
                    )
                )
            } else {
                // Update timestamp & title if first message
                val newTitle = if (existing.title == "New Chat" && message.role == MessageRole.USER) {
                    message.content.take(32).ifBlank { "New Chat" }
                } else existing.title
                chatDao.updateConversation(existing.copy(title = newTitle, updatedAt = System.currentTimeMillis()))
            }
            chatDao.insertMessage(ChatMessageEntity.fromDomain(message))
        }
    }

    suspend fun deleteConversation(id: String) {
        chatDao.deleteMessagesForConversation(id)
        chatDao.deleteConversation(id)
    }

    suspend fun deleteMessagesForConversation(conversationId: String) {
        chatDao.deleteMessagesForConversation(conversationId)
    }

    suspend fun deleteAllConversations() {
        chatDao.deleteAllConversations()
    }

    suspend fun requestAiCompletion(
        history: List<ChatMessage>,
        userMessage: ChatMessage,
        modelName: String,
        ragContext: String? = null,
        systemPrompt: String? = null,
        temperature: Float = 0.7f,
        topP: Float = 0.95f,
        maxTokens: Int = 4096
    ): Result<String> {
        val messagesToSend = if (!ragContext.isNullOrBlank()) {
            val systemRagMessage = ChatMessage(
                id = UUID.randomUUID().toString(),
                conversationId = userMessage.conversationId,
                role = MessageRole.SYSTEM,
                content = ragContext,
                modelUsed = modelName
            )
            listOf(systemRagMessage) + history + userMessage
        } else {
            history + userMessage
        }
        val apiKey = settingsManager.getEffectiveApiKey()

        if (apiKey.isBlank()) {
            return Result.failure(
                NvidiaApiException(
                    code = 401,
                    message = "NVIDIA API Key not configured. Please enter your API key in Developer Settings."
                )
            )
        }

        return apiClient.sendChatCompletion(
            messages = messagesToSend,
            model = modelName,
            systemPrompt = systemPrompt,
            temperature = temperature.toDouble(),
            topP = topP.toDouble(),
            maxTokens = maxTokens
        )
    }
}

