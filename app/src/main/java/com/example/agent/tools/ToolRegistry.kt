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
     * Finds a registered tool by name, supporting aliases like 'bash', 'terminal', 'sh'.
     */
    fun getTool(name: String): AgentTool? {
        val key = name.lowercase().trim()
        return tools[key] ?: when (key) {
            "bash", "terminal", "sh", "shell", "exec", "cmd" -> tools["run_command"]
            "python", "py" -> tools["python_execute"] ?: tools["run_command"]
            "replace_file_content", "edit_file", "edit", "patch", "patch_file", "apply_diff", "code_edit", "diff", "modify_file", "str_replace" -> tools["file_patch"]
            "write_to_file", "create_file", "overwrite_file" -> tools["file_write"]
            "read_file", "view_file", "cat" -> tools["file_read"]
            "list_dir", "dir_list", "ls" -> tools["file_list"]
            "delete_file", "remove_file", "rm" -> tools["file_delete"]
            else -> null
        }
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
     * The agent operates directly inside its live Ubuntu 22.04 LTS Terminal.
     */
    fun formatToolsForPrompt(): String {
        val sb = StringBuilder()
        sb.append("### DEDICATED AGENT ENVIRONMENT: UBUNTU 22.04 LTS LINUX TERMINAL\n")
        sb.append("You are an autonomous engineering agent with direct access to a live rootless Ubuntu 22.04 LTS (Termux-PRoot) bash terminal running on the device.\n")
        sb.append("The terminal was built specifically for you as your execution environment. You can execute commands directly in your Ubuntu terminal or invoke registered tools.\n\n")
        sb.append("HOW TO EXECUTE COMMANDS IN YOUR UBUNTU TERMINAL:\n")
        sb.append("Output your bash commands in a standard ```bash code block, or a ```tool_call block with tool 'bash':\n")
        sb.append("```bash\n")
        sb.append("# Create script with bash heredoc\n")
        sb.append("cat << 'EOF' > solution.py\n")
        sb.append("print('Executed in Ubuntu Terminal')\n")
        sb.append("EOF\n")
        sb.append("python3 solution.py\n")
        sb.append("```\n\n")
        sb.append("IMPORTANT TERMINAL RULES:\n")
        sb.append("- In ```bash blocks, write real Linux shell commands only (e.g. `mkdir -p dir`, `cat << 'EOF' > file ... EOF`, `ls -la`, `cd dir`).\n")
        sb.append("- NEVER emit pseudo-code or abstract commands like `file_write path=...`, `run_command command=...`, or `directory_create` into bash blocks.\n")
        sb.append("- To invoke structured tools like web_search or calculator, use standard ```tool_call JSON blocks.\n\n")
        sb.append("STANDARD LINUX WORKFLOW IN YOUR TERMINAL:\n")
        sb.append("1. Discover & Inspect: Run `ls -la`, `cat <file>`, `head -n 50 <file>`, `grep -rn 'pattern' .`, `find .`\n")
        sb.append("2. Create & Edit Files: Use bash heredocs (`cat << 'EOF' > filename ... EOF`) or python scripts to write code.\n")
        sb.append("3. Package Management: Run `apt update`, `apt install -y <pkg>`, `pip install <pkg>` to install packages.\n")
        sb.append("4. Execution & Testing: Run `python3 script.py`, `pytest`, `node`, `bash script.sh` and inspect real stdout/stderr.\n")
        sb.append("5. System Tools: `neofetch`, `uname -a`, `whoami`, `df -h`, `free -m`, `ps aux`\n\n")
        sb.append("CODE EDITING, PATCHING & DIFF INSTRUCTIONS:\n")
        sb.append("When modifying or fixing existing code, you have three flexible options:\n")
        sb.append("1. `file_patch` (or `replace_file_content`): Precise search-and-replace, SEARCH/REPLACE blocks, or line range replacements.\n")
        sb.append("   - Exact/Fuzzy replacement: Provide 'path', 'target_content', and 'replacement_content'.\n")
        sb.append("   - Line range replacement: Provide 'path', 'start_line', 'end_line', and 'replacement_content'.\n")
        sb.append("   - SEARCH/REPLACE block: Provide 'path' and 'diff' with <<<<<<< SEARCH ... ======= ... >>>>>>> REPLACE.\n")
        sb.append("2. Terminal `patch` or `git apply`: In ```bash, you can apply unified diffs using `patch -p1 < file.diff` or `git apply file.diff`.\n")
        sb.append("3. Terminal `sed`: For quick substitutions, use `sed -i 's/old/new/g' file`.\n\n")
        sb.append("AUTONOMOUS WEB BROWSING & NEWS EXTRACTION FLOW:\n")
        sb.append("1. Search: Use `web_search` with targeted queries.\n")
        sb.append("2. Open: Use `web_open` with target URLs to extract clean markdown, metadata, and outlines.\n")
        sb.append("3. Paginate/Navigate: Follow relevant links, use `offset` or `section` for deep content.\n\n")
        sb.append("### AVAILABLE TOOLS\n")
        for (tool in tools.values) {
            val def = tool.definition
            sb.append("- **${def.name}**: ${def.description}\n")
            if (def.parameters.isNotEmpty()) {
                val paramsStr = def.parameters.joinToString(", ") { param ->
                    "${param.name}: ${param.type}${if (param.required) " (required)" else ""}"
                }
                sb.append("  Parameters: $paramsStr\n")
            }
        }
        sb.append("\n### TOOL CALL PROTOCOL\n")
        sb.append("To invoke tools or execute commands, you may either:\n")
        sb.append("1. Write standard bash commands directly in a ```bash code block.\n")
        sb.append("2. Output a structured ```tool_call JSON block specifying 'tool' (or 'name') and 'arguments':\n")
        sb.append("```tool_call\n{\n  \"tool\": \"run_command\",\n  \"arguments\": {\n    \"command\": \"ls -la\"\n  }\n}\n```\n")
        sb.append("\n### WORKFLOW MANDATE:\n")
        sb.append("State your intent briefly (1-2 sentences), execute your terminal command or tool, examine stdout/stderr, and conclude with your final solution when verified.")
        return sb.toString()
    }

    /**
     * Executes a tool request and returns a structured ToolResult.
     */
    suspend fun execute(toolCall: ToolCall): ToolResult = withContext(Dispatchers.IO) {
        val toolNameLower = toolCall.toolName.lowercase().trim()
        val tool = getTool(toolNameLower)
            ?: return@withContext ToolResult.failure(
                callId = toolCall.callId,
                toolName = toolCall.toolName,
                error = "Tool '${toolCall.toolName}' is not registered in the tool registry. Available tools: ${tools.keys.joinToString(", ")}"
            )

        try {
            tool.execute(toolCall.arguments)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
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
