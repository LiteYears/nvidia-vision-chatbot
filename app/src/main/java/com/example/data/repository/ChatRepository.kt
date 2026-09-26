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

    private fun getSmartSimulatedResponse(prompt: String): String {
        val p = prompt.lowercase()
        return when {
            "marketing campaign" in p || "productivity app" in p || "remote workers" in p ->
                "Great target audience. A campaign centered around \"Work Smarter, Not Longer\" could resonate well. The goal would be to help remote professionals reclaim time and reduce burnout through smarter workflows."

            "channel" in p || "channels" in p ->
                "I recommend a multi-channel approach:\n\n• LinkedIn for professional audiences\n• Instagram Reels for productivity tips\n• YouTube Shorts for quick tutorials\n• Email marketing for retention\n• Influencer partnerships with remote-work creators"

            "slogan" in p || "slogans" in p ->
                "Here are 3 compelling campaign slogans:\n\n1. \"Work Smarter, Not Longer\"\n2. \"Reclaim Your Day, Every Day\"\n3. \"Focus on What Matters, Automate the Rest\"\n\nWhich direction resonates best with your brand identity?"

            "describe" in p && "image" in p ->
                "The image displays a serene winding stone path cutting through vibrant green hills and lush misty foliage. Gentle rays of morning sunlight cast soft golden reflections across the landscape."

            else ->
                "I can certainly help with that. Here are the key considerations and recommended next steps:\n\n• Clarify your primary objective and success metrics\n• Identify your core audience and their immediate friction points\n• Build a focused, iterative prototype\n• Gather feedback early to refine the approach\n\nWould you like me to dive deeper into any of these areas?"
        }
    }
}
