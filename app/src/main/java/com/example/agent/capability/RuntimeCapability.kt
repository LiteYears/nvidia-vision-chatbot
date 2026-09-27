package com.example.agent.capability

/**
 * Type of capability available in the agent execution environment.
 */
enum class CapabilityType {
    EMBEDDED_RUNTIME,
    SHELL_EXECUTABLE,
    WORKSPACE_TOOL
}

/**
 * Metadata and status for an execution capability or runtime environment.
 */
data class RuntimeCapability(
    val id: String,
    val name: String,
    val type: CapabilityType,
    val isAvailable: Boolean,
    val description: String,
    val primaryTool: String? = null,
    val notes: String? = null,
    val alternatives: List<String> = emptyList()
)
