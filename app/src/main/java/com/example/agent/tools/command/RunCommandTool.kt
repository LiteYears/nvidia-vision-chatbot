package com.example.agent.tools.command

import com.example.agent.capability.RuntimeCapabilityDetector
import com.example.agent.tools.AgentTool
import com.example.agent.tools.ToolDefinition
import com.example.agent.tools.ToolParameter
import com.example.agent.tools.ToolResult
import com.example.agent.tools.workspace.AgentWorkspaceManager
import java.io.File
import java.util.UUID

/**
 * Agent Mode tool for executing workspace-safe commands strictly inside the agent workspace.
 */
class RunCommandTool(
    private val workspaceManager: AgentWorkspaceManager = AgentWorkspaceManager.getInstance(),
    private val commandRunner: CommandRunner = ProcessCommandRunner(),
    val securityValidator: CommandSecurityValidator = CommandSecurityValidator(),
    private val capabilityDetector: RuntimeCapabilityDetector = RuntimeCapabilityDetector()
) : AgentTool {

    override val definition: ToolDefinition = ToolDefinition(
        name = "run_command",
        description = "Executes a workspace-safe shell command or script strictly inside the active agent workspace. " +
            "Runs with a controlled process runner enforcing strict execution timeouts, output size limits, " +
            "and sandboxing. Returns stdout, stderr, exit code, and execution duration. " +
            "Supported commands include directory listing (ls, dir, tree), file inspection (cat, head, tail, wc, stat, find), " +
            "text processing (grep, sed, awk, sort, uniq, cut, tr, echo, diff), and workspace scripts.",
        parameters = listOf(
            ToolParameter(
                name = "command",
                type = "string",
                description = "The workspace command to execute (e.g. 'ls -la', 'cat notes.txt | grep topic', 'wc -l src/*')",
                required = true
            ),
            ToolParameter(
                name = "working_dir",
                type = "string",
                description = "Relative directory inside the workspace to execute within (default: '.' for workspace root)",
                required = false,
                default = "."
            ),
            ToolParameter(
                name = "timeout_ms",
                type = "number",
                description = "Maximum execution time in milliseconds before terminating the process (default: 5000 ms, maximum: 30000 ms)",
                required = false,
                default = 5000
            ),
            ToolParameter(
                name = "max_output_bytes",
                type = "number",
                description = "Maximum output bytes to capture from stdout/stderr (default: 32768 / 32 KB, maximum: 131072 / 128 KB)",
                required = false,
                default = 32768
            )
        )
    )

    override suspend fun execute(arguments: Map<String, Any?>): ToolResult {
        val callId = UUID.randomUUID().toString()
        val rawCommand = (arguments["command"] ?: arguments["cmd"])?.toString()?.trim()

        if (rawCommand.isNullOrBlank()) {
            return ToolResult.failure(
                callId = callId,
                toolName = definition.name,
                error = "Missing or empty required parameter 'command'."
            )
        }

        val workingDirArg = (arguments["working_dir"] ?: arguments["cwd"] ?: arguments["dir"])?.toString()?.trim() ?: "."

        val timeoutMs = parseLong(arguments["timeout_ms"] ?: arguments["timeout"], default = 5000L)
            .coerceIn(100L, 30000L)

        val maxOutputBytes = parseInt(arguments["max_output_bytes"] ?: arguments["max_bytes"], default = 32768)
            .coerceIn(1024, 131072)

        val workspaceRoot = workspaceManager.getWorkspaceDir()

        // 1. Resolve and validate working directory
        val resolvedWorkingDir: File
        try {
            resolvedWorkingDir = workspaceManager.resolvePath(workingDirArg)
        } catch (e: SecurityException) {
            return ToolResult.failure(
                callId = callId,
                toolName = definition.name,
                error = "Working directory security error: ${e.message}"
            )
        }

        if (!resolvedWorkingDir.exists() || !resolvedWorkingDir.isDirectory) {
            return ToolResult.failure(
                callId = callId,
                toolName = definition.name,
                error = "Working directory does not exist or is not a directory: '$workingDirArg'"
            )
        }

        // 2. Validate command and arguments through security policy
        try {
            securityValidator.validateCommand(
                command = rawCommand,
                workingDir = resolvedWorkingDir,
                workspaceRoot = workspaceRoot
            )
        } catch (e: SecurityException) {
            return ToolResult.failure(
                callId = callId,
                toolName = definition.name,
                error = "Security validation failed: ${e.message}"
            )
        } catch (e: IllegalArgumentException) {
            return ToolResult.failure(
                callId = callId,
                toolName = definition.name,
                error = "Invalid command argument: ${e.message}"
            )
        }

        // 3. Check for language runtimes that are unavailable in Android shell
        val primaryExecutable = rawCommand.trim().split(Regex("\\s+")).firstOrNull()?.trim('\'', '"') ?: ""
        val cleanExec = if (primaryExecutable.contains('/')) File(primaryExecutable).name else primaryExecutable

        if (cleanExec.isNotBlank() && isLanguageRuntimeCommand(cleanExec) && !capabilityDetector.isExecutableAvailable(cleanExec)) {
            val capError = capabilityDetector.buildCapabilityUnavailableError(cleanExec, rawCommand)
            return ToolResult.failure(callId = callId, toolName = definition.name, error = capError)
        }

        // 4. Execute via modular CommandRunner
        val execResult = commandRunner.run(
            command = rawCommand,
            workingDir = resolvedWorkingDir,
            timeoutMs = timeoutMs,
            maxOutputBytes = maxOutputBytes
        )

        val relativeDir = workspaceManager.getRelativePath(resolvedWorkingDir).ifBlank { "." }

        // 5. Return structured ToolResult
        return if (execResult.isTimedOut) {
            val errorMsg = buildString {
                appendLine("Command execution timed out after ${timeoutMs}ms and was killed.")
                appendLine("Command: $rawCommand")
                appendLine("Working Directory: $relativeDir")
                appendLine("Execution Duration: ${execResult.durationMs}ms")
                if (execResult.stdout.isNotBlank()) {
                    appendLine("\n[stdout]")
                    append(execResult.stdout)
                }
                if (execResult.stderr.isNotBlank()) {
                    appendLine("\n[stderr]")
                    append(execResult.stderr)
                }
            }
            ToolResult.failure(callId = callId, toolName = definition.name, error = errorMsg.trim())
        } else if (execResult.exitCode != 0) {
            val isUnavailable = execResult.exitCode == 127 ||
                execResult.stderr.contains("inaccessible or not found", ignoreCase = true) ||
                (execResult.stderr.contains("not found", ignoreCase = true) && !capabilityDetector.isExecutableAvailable(cleanExec))

            if (isUnavailable) {
                val capError = capabilityDetector.buildCapabilityUnavailableError(cleanExec, rawCommand, execResult.stderr)
                return ToolResult.failure(callId = callId, toolName = definition.name, error = capError)
            }

            val errorMsg = buildString {
                appendLine("Command exited with code ${execResult.exitCode} (Duration: ${execResult.durationMs}ms)")
                appendLine("Command: $rawCommand")
                appendLine("Working Directory: $relativeDir")
                if (execResult.stdout.isNotBlank()) {
                    appendLine("\n[stdout]")
                    append(execResult.stdout)
                }
                if (execResult.stderr.isNotBlank()) {
                    appendLine("\n[stderr]")
                    append(execResult.stderr)
                }
                if (execResult.stdout.isBlank() && execResult.stderr.isBlank()) {
                    appendLine("\n(No output produced)")
                }
            }
            ToolResult.failure(callId = callId, toolName = definition.name, error = errorMsg.trim())
        } else {
            val resultMsg = buildString {
                appendLine("Exit Code: 0 (Duration: ${execResult.durationMs}ms)")
                appendLine("Command: $rawCommand")
                appendLine("Working Directory: $relativeDir")
                if (execResult.isTruncated) {
                    appendLine("[Output truncated: exceeded $maxOutputBytes bytes limit]")
                }
                if (execResult.stdout.isNotBlank()) {
                    appendLine("\n[stdout]")
                    append(execResult.stdout.trimEnd())
                }
                if (execResult.stderr.isNotBlank()) {
                    appendLine("\n[stderr]")
                    append(execResult.stderr.trimEnd())
                }
                if (execResult.stdout.isBlank() && execResult.stderr.isBlank()) {
                    appendLine("\n(Command executed successfully with no output)")
                }
            }
            ToolResult.success(callId = callId, toolName = definition.name, result = resultMsg.trim())
        }
    }

    private fun parseLong(value: Any?, default: Long): Long {
        return when (value) {
            is Number -> value.toLong()
            is String -> value.toLongOrNull() ?: default
            else -> default
        }
    }

    private fun parseInt(value: Any?, default: Int): Int {
        return when (value) {
            is Number -> value.toInt()
            is String -> value.toIntOrNull() ?: default
            else -> default
        }
    }

    private fun isLanguageRuntimeCommand(executable: String): Boolean {
        val norm = executable.lowercase().trim()
        return norm in setOf(
            "python", "python3", "py",
            "node", "nodejs", "npm", "npx",
            "java", "javac", "gradle", "mvn",
            "pip", "pip3", "ruby", "perl", "php"
        )
    }
}
