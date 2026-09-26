package com.example.data.model

import java.util.UUID

enum class MessageRole(val value: String) {
    USER("user"),
    ASSISTANT("assistant"),
    SYSTEM("system");

    companion object {
        fun fromValue(value: String): MessageRole {
            return entries.firstOrNull { it.value.equals(value, ignoreCase = true) } ?: ASSISTANT
        }
    }
}

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val conversationId: String,
    val role: MessageRole,
    val content: String,
    val imageBase64: String? = null,
    val imageUri: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val modelUsed: String = "deepseek-ai/deepseek-v4.1-flash",
    val isStreaming: Boolean = false,
    val isError: Boolean = false
)

data class Conversation(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "New Chat",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val modelName: String = "deepseek-ai/deepseek-v4.1-flash",
    val isIncognito: Boolean = false
)
