package com.example.data.model

import com.example.agent.plan.TaskPlan
import java.util.UUID

enum class AppMode {
    CHAT,
    AGENT,
    TERMINAL
}

enum class AgentTaskStatus(val displayName: String) {
    INITIALIZING("Initializing"),
    THINKING("Thinking"),
    USING_TOOL("Using Tool"),
    OBSERVING("Observing"),
    IN_PROGRESS("In Progress"),
    COMPLETED("Completed"),
    PAUSED("Paused"),
    FAILED("Failed")
}

data class AgentStep(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val description: String = "",
    val status: AgentTaskStatus = AgentTaskStatus.INITIALIZING,
    val timestamp: Long = System.currentTimeMillis()
)

data class AgentReflection(
    val id: String = UUID.randomUUID().toString(),
    val thought: String,
    val timestamp: Long = System.currentTimeMillis()
)

data class ToolExecutionRecord(
    val callId: String = UUID.randomUUID().toString(),
    val messageId: String? = null,
    val toolName: String,
    val arguments: Map<String, Any?> = emptyMap(),
    val isSuccess: Boolean,
    val result: String? = null,
    val error: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)

data class AgentSession(
    val id: String = UUID.randomUUID().toString(),
    val goal: String,
    val status: AgentTaskStatus = AgentTaskStatus.INITIALIZING,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val modelUsed: String = "deepseek-ai/deepseek-v4.1-flash",
    val steps: List<AgentStep> = emptyList(),
    val plan: TaskPlan? = null,
    val messages: List<ChatMessage> = emptyList(),
    val toolExecutions: List<ToolExecutionRecord> = emptyList(),
    val reflections: List<AgentReflection> = emptyList()
)

sealed interface AgentFeedItem {
    val id: String
    val timestamp: Long

    data class Message(val message: ChatMessage) : AgentFeedItem {
        override val id: String get() = message.id
        override val timestamp: Long get() = message.timestamp
    }

    data class Reflection(val reflection: AgentReflection) : AgentFeedItem {
        override val id: String get() = reflection.id
        override val timestamp: Long get() = reflection.timestamp
    }

    data class Tool(val record: ToolExecutionRecord) : AgentFeedItem {
        override val id: String get() = record.callId
        override val timestamp: Long get() = record.timestamp
    }
}

