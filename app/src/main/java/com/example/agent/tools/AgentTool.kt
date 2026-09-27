package com.example.agent.tools

/**
 * Interface that all Agent tools must implement.
 *
 * This clean, extensible abstraction allows the agent to:
 * - Discover tool definition and parameter requirements
 * - Execute the tool with structured arguments
 * - Receive a structured ToolResult
 * - Report success or failure back to the agent
 */
interface AgentTool {
    val definition: ToolDefinition
    val name: String get() = definition.name

    /**
     * Executes the tool with the provided arguments.
     *
     * @param arguments Map of parameter names to values passed by the agent.
     * @return Structured ToolResult indicating success or failure.
     */
    suspend fun execute(arguments: Map<String, Any?>): ToolResult
}
