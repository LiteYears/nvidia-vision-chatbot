package com.example.agent.tools

import com.example.agent.capability.RuntimeCapabilityDetector
import com.example.agent.tools.builtin.CalculatorTool
import com.example.agent.tools.builtin.WebOpenTool
import com.example.agent.tools.builtin.WebSearchTool
import com.example.agent.tools.command.RunCommandTool
import com.example.agent.tools.python.PythonExecuteTool
import com.example.agent.tools.workspace.AgentWorkspaceManager
import com.example.agent.tools.workspace.ArchiveExtractTool
import com.example.agent.tools.workspace.DirectoryCreateTool
import com.example.agent.tools.workspace.FileDeleteTool
import com.example.agent.tools.workspace.FileListTool
import com.example.agent.tools.workspace.FilePatchTool
import com.example.agent.tools.workspace.FileReadTool
import com.example.agent.tools.workspace.FileSearchTool
import com.example.agent.tools.workspace.FileTreeTool
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
        sb.append("1. Inspect directory structure using `file_tree` or search codebase with `file_search`.\n")
        sb.append("2. Write or modify modular scripts using `file_write` (e.g. `main.py` and `utils.py`).\n")
        sb.append("3. Execute scripts using `python_execute` (with `script_path`: \"main.py\").\n")
        sb.append("4. If an error occurs, inspect line numbers and diagnostics, refine code with `file_write`, and re-run.\n")
        sb.append("5. Conclude only when verified and complete.\n")

        sb.append("\n### AUTONOMOUS WEB BROWSING & NEWS EXTRACTION FLOW\n")
        sb.append("When asked to enter a website, fetch news, read articles, or query documentation:\n")
        sb.append("1. ENTER WEBSITE / READ URL:\n")
        sb.append("   - If a URL or domain is provided (e.g. \"bbc.com\", \"news.ycombinator.com\", \"https://...\"):\n")
        sb.append("     Immediately invoke `web_open` with parameter `url: \"<url>\"`.\n")
        sb.append("     `web_open` loads the website, strips clutter/ads, and returns the full readable content, headlines, and metadata.\n")
        sb.append("2. DISCOVERY & SEARCH:\n")
        sb.append("   - If a specific website is not specified or you need to find relevant sources:\n")
        sb.append("     Query the web with `web_search` using precise keywords.\n")
        sb.append("     Review result snippets and URLs, then invoke `web_open` to read full articles.\n")
        sb.append("3. MULTI-PAGE EXPLORATION:\n")
        sb.append("   - Open additional pages with `web_open` to gather missing context or cross-verify facts.\n")
        sb.append("4. SYNTHESIZE & CITE:\n")
        sb.append("   - Provide a comprehensive, factual answer citing the source URLs.\n")
        sb.append("\n### CLAUDE CODE-LEVEL WORKSPACE & EXECUTION ARCHITECTURE\n")
        sb.append("- Standard folders are pre-created: `src/` (code), `scripts/` (runners), `data/` (inputs), `output/` (artifacts), `docs/` (documentation), `lib/` (packages), `tests/` (testing), `bin/` (executables).\n")
        sb.append("- All file types are supported: text, source code, JSON/CSV/YAML data, and binary files/images/archives (using `encoding: 'base64'` in `file_read` / `file_write`).\n")
        sb.append("- Targeted code edits & diffs: ALWAYS use `file_patch` to modify, fix, or improve specific functions and code sections without rewriting entire files.\n")
        sb.append("- Project ZIP Archives: unpack project codebases using `archive_extract` (e.g. `path: 'project.zip'`). Once extracted, inspect with `file_tree` and patch with `file_patch`.\n")
        sb.append("- Package management:\n")
        sb.append("  * Python: run `pip install <package>` (or `pip list`) via `run_command`. Packages install to `lib/` and are automatically discoverable.\n")
        sb.append("  * Node.js: run `npm init -y`, `npm install <package>`, and `npm run <cmd>` via `run_command`.\n")
        sb.append("  * Script execution: run with `python3 <file.py>`, `node <file.js>`, or `python_execute`.\n")

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
                register(WebOpenTool())
                register(FileListTool(workspaceManager))
                register(FileTreeTool(workspaceManager))
                register(FileSearchTool(workspaceManager))
                register(FileReadTool(workspaceManager))
                register(FileWriteTool(workspaceManager))
                register(FilePatchTool(workspaceManager))
                register(FileDeleteTool(workspaceManager))
                register(DirectoryCreateTool(workspaceManager))
                register(ArchiveExtractTool(workspaceManager))
                register(RunCommandTool(workspaceManager))
                register(PythonExecuteTool(workspaceManager))
            }
        }
    }
}
