package com.example.agent.tools

import java.util.UUID

/**
 * Metadata definition for a parameter required by an AgentTool.
 */
data class ToolParameter(
    val name: String,
    val type: String, // "string", "number", "boolean", "array", "object"
    val description: String,
    val required: Boolean = true,
    val default: Any? = null
)

/**
 * Specification and discovery schema for an AgentTool.
 */
data class ToolDefinition(
    val name: String,
    val description: String,
    val parameters: List<ToolParameter> = emptyList()
)

/**
 * Structured request from an Agent to invoke a tool.
 */
data class ToolCall(
    val callId: String = UUID.randomUUID().toString(),
    val toolName: String,
    val arguments: Map<String, Any?> = emptyMap()
)

/**
 * Structured execution result returned from an AgentTool.
 */
data class ToolResult(
    val callId: String,
    val toolName: String,
    val isSuccess: Boolean,
    val result: String? = null,
    val error: String? = null,
    val executedAt: Long = System.currentTimeMillis()
) {
    companion object {
        fun success(callId: String, toolName: String, result: String): ToolResult =
            ToolResult(
                callId = callId,
                toolName = toolName,
                isSuccess = true,
                result = result,
                error = null
            )

        fun failure(callId: String, toolName: String, error: String): ToolResult =
            ToolResult(
                callId = callId,
                toolName = toolName,
                isSuccess = false,
                result = null,
                error = error
            )
    }
}
