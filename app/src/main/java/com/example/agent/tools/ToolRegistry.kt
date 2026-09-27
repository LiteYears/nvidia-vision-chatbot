package com.example.agent.tools

import com.example.agent.capability.RuntimeCapabilityDetector
import com.example.agent.tools.builtin.CalculatorTool
import com.example.agent.tools.builtin.WebSearchTool
import com.example.agent.tools.command.RunCommandTool
import com.example.agent.tools.python.PythonExecuteTool
import com.example.agent.tools.workspace.AgentWorkspaceManager
import com.example.agent.tools.workspace.FileDeleteTool
import com.example.agent.tools.workspace.FileListTool
import com.example.agent.tools.workspace.FileReadTool
import com.example.agent.tools.workspace.FileWriteTool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Thread-safe, modular registry for Agent Mode tools.
 *
 * Allows the agent to:
 * - Discover available tools and their schemas
 * - Request a tool with structured arguments
 * - Execute the tool safely
 * - Receive structured results
 * - Easily extend with new tools without touching the agent core
 */
class ToolRegistry {

    private val tools = ConcurrentHashMap<String, AgentTool>()

    /**
     * Registers a tool. Replaces any existing tool with the same name.
     */
    fun register(tool: AgentTool) {
        tools[tool.definition.name.lowercase()] = tool
    }

    /**
     * Unregisters a tool by name.
     */
    fun unregister(name: String) {
        tools.remove(name.lowercase())
    }

    /**
     * Finds a registered tool by name.
     */
    fun getTool(name: String): AgentTool? {
        return tools[name.lowercase()]
    }

    /**
     * Returns all registered tools.
     */
    fun getAllTools(): List<AgentTool> {
        return tools.values.toList()
    }

    /**
     * Returns tool definitions for all registered tools.
     */
    fun getToolDefinitions(): List<ToolDefinition> {
        return tools.values.map { it.definition }
    }

    /**
     * Formats available tools into a structured prompt instruction block for the agent.
     */
    fun formatToolsForPrompt(): String {
        if (tools.isEmpty()) return ""

        val sb = StringBuilder()
        sb.append("### AVAILABLE TOOLS\n")
        sb.append("You have access to the following tools to assist in achieving the goal:\n\n")

        for (tool in tools.values) {
            val def = tool.definition
            sb.append("- **${def.name}**:\n")
            sb.append("  Description: ${def.description}\n")
            if (def.parameters.isNotEmpty()) {
                sb.append("  Parameters:\n")
                for (param in def.parameters) {
                    val req = if (param.required) "required" else "optional"
                    sb.append("    * `${param.name}` (${param.type}, $req): ${param.description}\n")
                }
            }
            sb.append("\n")
        }

        sb.append("### TOOL CALL PROTOCOL\n")
        sb.append("If you need to call a tool, output a single JSON block inside ```tool_call``` markdown tags in this exact format:\n")
        sb.append("```tool_call\n")
        sb.append("{\n")
        sb.append("  \"tool\": \"<tool_name>\",\n")
        sb.append("  \"arguments\": {\n")
        sb.append("    \"<param_name>\": \"<param_value>\"\n")
        sb.append("  }\n")
        sb.append("}\n")
        sb.append("```\n")
        sb.append("When you output a tool call, the tool will be executed and the structured result returned back to you.\n")
        sb.append("If no tool is required, respond directly with your analysis and roadmap without tool_call tags.\n\n")

        sb.append(RuntimeCapabilityDetector().formatCapabilitiesForPrompt())
        sb.append("\n### AUTONOMOUS CODING & DEBUGGING FLOW\n")
        sb.append("When asked to write, run, debug, or fix scripts:\n")
        sb.append("1. Write the code to a file using `file_write` (e.g. `script.py`).\n")
        sb.append("2. Execute it using `python_execute` (with `script_path`: \"script.py\").\n")
        sb.append("3. If an error or traceback occurs, inspect the line number and exception details.\n")
        sb.append("4. Fix the code by overwriting the file with `file_write`.\n")
        sb.append("5. Re-run with `python_execute` to verify the fix.\n")
        sb.append("6. Only conclude when the script runs cleanly and yields the expected results.\n")

        return sb.toString()
    }

    /**
     * Executes a tool request and returns a structured ToolResult.
     */
    suspend fun execute(toolCall: ToolCall): ToolResult = withContext(Dispatchers.Default) {
        val tool = getTool(toolCall.toolName)
            ?: return@withContext ToolResult.failure(
                callId = toolCall.callId,
                toolName = toolCall.toolName,
                error = "Tool '${toolCall.toolName}' is not registered in the tool registry. Available tools: ${tools.keys.joinToString(", ")}"
            )

        try {
            tool.execute(toolCall.arguments)
        } catch (e: Exception) {
            ToolResult.failure(
                callId = toolCall.callId,
                toolName = toolCall.toolName,
                error = "Unexpected exception during tool execution: ${e.message ?: e.javaClass.simpleName}"
            )
        }
    }

    companion object {
        /**
         * Creates a default ToolRegistry pre-configured with built-in tools.
         */
        fun defaultRegistry(
            workspaceManager: AgentWorkspaceManager = AgentWorkspaceManager.getInstance()
        ): ToolRegistry {
            return ToolRegistry().apply {
                register(CalculatorTool())
                register(WebSearchTool())
                register(FileListTool(workspaceManager))
                register(FileReadTool(workspaceManager))
                register(FileWriteTool(workspaceManager))
                register(FileDeleteTool(workspaceManager))
                register(RunCommandTool(workspaceManager))
                register(PythonExecuteTool(workspaceManager))
            }
        }
    }
}
