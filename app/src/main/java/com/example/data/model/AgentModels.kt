package com.example.data.model

import java.util.UUID

enum class AppMode {
    CHAT,
    AGENT
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
    val messages: List<ChatMessage> = emptyList(),
    val toolExecutions: List<ToolExecutionRecord> = emptyList()
)
