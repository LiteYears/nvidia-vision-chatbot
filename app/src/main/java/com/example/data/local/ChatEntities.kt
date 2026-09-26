package com.example.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.data.model.ChatMessage
import com.example.data.model.Conversation
import com.example.data.model.MessageRole

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val modelName: String,
    val isIncognito: Boolean
) {
    fun toDomain(): Conversation = Conversation(
        id = id,
        title = title,
        createdAt = createdAt,
        updatedAt = updatedAt,
        modelName = modelName,
        isIncognito = isIncognito
    )

    companion object {
        fun fromDomain(conv: Conversation): ConversationEntity = ConversationEntity(
            id = conv.id,
            title = conv.title,
            createdAt = conv.createdAt,
            updatedAt = conv.updatedAt,
            modelName = conv.modelName,
            isIncognito = conv.isIncognito
        )
    }
}

@Entity(
    tableName = "chat_messages",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["conversationId"])]
)
data class ChatMessageEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val role: String,
    val content: String,
    val imageBase64: String?,
    val imageUri: String?,
    val timestamp: Long,
    val modelUsed: String
) {
    fun toDomain(): ChatMessage = ChatMessage(
        id = id,
        conversationId = conversationId,
        role = MessageRole.fromValue(role),
        content = content,
        imageBase64 = imageBase64,
        imageUri = imageUri,
        timestamp = timestamp,
        modelUsed = modelUsed,
        isStreaming = false,
        isError = false
    )

    companion object {
        fun fromDomain(msg: ChatMessage): ChatMessageEntity = ChatMessageEntity(
            id = msg.id,
            conversationId = msg.conversationId,
            role = msg.role.value,
            content = msg.content,
            // Guard against SQLite CursorWindowAllocationException (max 2MB per cursor window)
            // imageUri handles rendering; omit large base64 strings from persistent SQLite storage
            imageBase64 = if (!msg.imageUri.isNullOrBlank()) null else msg.imageBase64?.takeIf { it.length < 80_000 },
            imageUri = msg.imageUri,
            timestamp = msg.timestamp,
            modelUsed = msg.modelUsed
        )
    }
}
