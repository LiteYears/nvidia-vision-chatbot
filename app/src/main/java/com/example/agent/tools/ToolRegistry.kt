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
        sb.append("The terminal was built specifically for you as your execution environment. You do not call abstract tools on the chat interface; you execute commands directly in your Ubuntu terminal.\n\n")
        sb.append("HOW TO EXECUTE COMMANDS IN YOUR UBUNTU TERMINAL:\n")
        sb.append("Output your bash commands in a standard ```bash code block, or a ```tool_call block with tool 'bash':\n")
        sb.append("```bash\n")
        sb.append("# Create script with bash heredoc\n")
        sb.append("cat << 'EOF' > solution.py\n")
        sb.append("print('Executed in Ubuntu Terminal')\n")
        sb.append("EOF\n")
        sb.append("python3 solution.py\n")
        sb.append("```\n\n")
        sb.append("STANDARD LINUX WORKFLOW IN YOUR TERMINAL:\n")
        sb.append("1. Discover & Inspect: Run `ls -la`, `cat <file>`, `head -n 50 <file>`, `grep -rn 'pattern' .`, `find .`\n")
        sb.append("2. Create & Edit Files: Use bash heredocs (`cat << 'EOF' > filename ... EOF`) or python scripts to write code.\n")
        sb.append("3. Package Management: Run `apt update`, `apt install -y <pkg>`, `pip install <pkg>` to install packages.\n")
        sb.append("4. Execution & Testing: Run `python3 script.py`, `pytest`, `node`, `bash script.sh` and inspect real stdout/stderr.\n")
        sb.append("5. System Tools: `neofetch`, `uname -a`, `whoami`, `df -h`, `free -m`, `ps aux`\n\n")
        sb.append("### SUPPLEMENTARY TOOLS\n")
        sb.append("- **web_search**: Search the web for up-to-date documentation, APIs, and news (`query`: string).\n")
        sb.append("- **web_open**: Open and read documentation or web page content (`url`: string).\n\n")
        sb.append("### WORKFLOW MANDATE:\n")
        sb.append("State your intent briefly (1-2 sentences), execute your terminal command, examine the terminal stdout/stderr, and conclude with your final solution when verified.")
        return sb.toString()
    }

    /**
     * Executes a tool request and returns a structured ToolResult.
     * Legacy file operations are automatically translated into real Ubuntu terminal bash commands.
     */
    suspend fun execute(toolCall: ToolCall): ToolResult = withContext(Dispatchers.Default) {
        val toolNameLower = toolCall.toolName.lowercase().trim()

        // Transparently convert legacy tool calls into genuine Ubuntu terminal bash commands
        val effectiveToolCall = when (toolNameLower) {
            "file_write" -> {
                val path = toolCall.arguments["path"]?.toString() ?: "file.txt"
                val content = toolCall.arguments["content"]?.toString() ?: ""
                val bashCmd = "mkdir -p \$(dirname '$path') && cat << 'EOF' > '$path'\n$content\nEOF"
                ToolCall(
                    callId = toolCall.callId,
                    toolName = "bash",
                    arguments = mapOf("command" to bashCmd)
                )
            }
            "file_read" -> {
                val path = toolCall.arguments["path"]?.toString() ?: ""
                val bashCmd = if (path.isNotBlank()) "cat '$path'" else "ls -la"
                ToolCall(
                    callId = toolCall.callId,
                    toolName = "bash",
                    arguments = mapOf("command" to bashCmd)
                )
            }
            "file_list", "file_tree" -> {
                val path = toolCall.arguments["path"]?.toString() ?: "."
                ToolCall(
                    callId = toolCall.callId,
                    toolName = "bash",
                    arguments = mapOf("command" to "ls -la '$path'")
                )
            }
            "file_delete" -> {
                val path = toolCall.arguments["path"]?.toString() ?: ""
                ToolCall(
                    callId = toolCall.callId,
                    toolName = "bash",
                    arguments = mapOf("command" to "rm -rf '$path'")
                )
            }
            "directory_create" -> {
                val path = toolCall.arguments["path"]?.toString() ?: ""
                ToolCall(
                    callId = toolCall.callId,
                    toolName = "bash",
                    arguments = mapOf("command" to "mkdir -p '$path'")
                )
            }
            "python_execute" -> {
                val scriptPath = toolCall.arguments["script_path"]?.toString() ?: "main.py"
                ToolCall(
                    callId = toolCall.callId,
                    toolName = "bash",
                    arguments = mapOf("command" to "python3 '$scriptPath'")
                )
            }
            else -> toolCall
        }

        val tool = getTool(effectiveToolCall.toolName)
            ?: return@withContext ToolResult.failure(
                callId = effectiveToolCall.callId,
                toolName = effectiveToolCall.toolName,
                error = "Tool '${effectiveToolCall.toolName}' is not registered in the tool registry. Available tools: ${tools.keys.joinToString(", ")}"
            )

        try {
            tool.execute(effectiveToolCall.arguments)
        } catch (e: Exception) {
            ToolResult.failure(
                callId = effectiveToolCall.callId,
                toolName = effectiveToolCall.toolName,
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
