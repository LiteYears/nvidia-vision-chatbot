package com.example.agent.tools.command

import com.example.agent.tools.AgentTool
import com.example.agent.tools.ToolDefinition
import com.example.agent.tools.ToolParameter
import com.example.agent.tools.ToolResult
import com.example.agent.tools.workspace.AgentWorkspaceManager
import com.example.data.preferences.SettingsManager
import java.io.File
import java.util.UUID

/**
 * Agent Mode tool for executing shell commands and scripts with real-time user permission control.
 * Supports all commands (including network, package manager, and shell scripts).
 * When a command requires authorization, it invokes the CommandAuthorizer to present
 * the user with a Permit Question dialog (Allow Once, Always Allow, or Always Allow All).
 */
class RunCommandTool(
    private val workspaceManager: AgentWorkspaceManager = AgentWorkspaceManager.getInstance(),
    private val commandRunner: CommandRunner = ProcessCommandRunner(),
    private val securityValidator: CommandSecurityValidator = CommandSecurityValidator(),
    private val settingsManager: SettingsManager? = null,
    private val authorizer: CommandAuthorizer? = null
) : AgentTool {

    override val definition: ToolDefinition = ToolDefinition(
        name = "run_command",
        description = "Executes shell commands, scripts, system tools, pipes, utilities, and packages. " +
            "Supports directory inspection (ls, tree, pwd), file tools (cat, head, tail, grep, sed, awk), " +
            "network operations (curl, ping, wget), package management queries (pm, am, getprop), and script execution (sh, bash, python). " +
            "If a command requires authorization, the app automatically prompts the user with an authorization permit question " +
            "(Allow Once, Always Allow Command, or Always Allow All).",
        parameters = listOf(
            ToolParameter(
                name = "command",
                type = "string",
                description = "The shell command or script to execute (e.g. 'ls -la', 'curl -I https://api.github.com', 'pm list packages', 'cat file.txt | grep search')",
                required = true
            ),
            ToolParameter(
                name = "working_dir",
                type = "string",
                description = "Working directory path for execution (relative to workspace or absolute). Defaults to active workspace root.",
                required = false,
                default = "."
            ),
            ToolParameter(
                name = "timeout_ms",
                type = "number",
                description = "Maximum execution time in milliseconds before terminating the process (default: 10000 ms, maximum: 60000 ms)",
                required = false,
                default = 10000
            ),
            ToolParameter(
                name = "max_output_bytes",
                type = "number",
                description = "Maximum output bytes to capture from stdout/stderr (default: 65536 / 64 KB, maximum: 262144 / 256 KB)",
                required = false,
                default = 65536
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

        val timeoutMs = parseLong(arguments["timeout_ms"] ?: arguments["timeout"], default = 10000L)
            .coerceIn(500L, 60000L)

        val maxOutputBytes = parseInt(arguments["max_output_bytes"] ?: arguments["max_bytes"], default = 65536)
            .coerceIn(1024, 262144)

        val workspaceRoot = workspaceManager.getWorkspaceDir()

        // 1. Resolve and ensure working directory
        val resolvedWorkingDir: File = if (workingDirArg.startsWith("/")) {
            File(workingDirArg)
        } else {
            try {
                workspaceManager.resolvePath(workingDirArg)
            } catch (_: Exception) {
                File(workspaceRoot, workingDirArg)
            }
        }

        if (!resolvedWorkingDir.exists()) {
            try {
                resolvedWorkingDir.mkdirs()
            } catch (_: Exception) {
                // If can't create (e.g. system read-only), fallback to workspace root
            }
        }

        val effectiveWorkDir = if (resolvedWorkingDir.exists() && resolvedWorkingDir.isDirectory) {
            resolvedWorkingDir
        } else {
            workspaceRoot
        }

        // 2. Classify command and evaluate risk level
        val classification = securityValidator.classifyCommand(
            command = rawCommand,
            workingDir = effectiveWorkDir,
            workspaceRoot = workspaceRoot
        )

        // 3. Check permission policy and authorization
        val isAlwaysAllowAll = settingsManager?.isAlwaysAllowAllCommands() == true ||
            settingsManager?.getCommandPermissionPolicy() == CommandPermissionPolicy.ALWAYS_ALLOW_ALL.id

        val isCommandWhitelisted = settingsManager?.isCommandAlwaysAllowed(classification.executable) == true

        val policy = settingsManager?.getCommandPermissionPolicy() ?: CommandPermissionPolicy.ASK_FOR_SENSITIVE.id

        val needsPrompt = when {
            isAlwaysAllowAll -> false
            isCommandWhitelisted -> false
            policy == CommandPermissionPolicy.ALWAYS_ASK.id -> true
            classification.isPrivilegedOrSensitive -> true
            else -> false
        }

        if (needsPrompt && authorizer != null) {
            val decision = authorizer.requestPermission(
                command = rawCommand,
                workingDir = effectiveWorkDir.path,
                classification = classification
            )

            when (decision) {
                CommandPermissionDecision.DENY -> {
                    return ToolResult.failure(
                        callId = callId,
                        toolName = definition.name,
                        error = "Command execution was denied by the user. Command was blocked from running: '$rawCommand'"
                    )
                }
                CommandPermissionDecision.ALWAYS_ALLOW_COMMAND -> {
                    settingsManager?.addAlwaysAllowedCommand(classification.executable)
                }
                CommandPermissionDecision.ALWAYS_ALLOW_ALL -> {
                    settingsManager?.setAlwaysAllowAllCommands(true)
                }
                CommandPermissionDecision.ALLOW_ONCE -> {
                    // Proceed with one-time execution
                }
            }
        }

        // 4. Execute via modular CommandRunner
        val execResult = commandRunner.run(
            command = rawCommand,
            workingDir = effectiveWorkDir,
            timeoutMs = timeoutMs,
            maxOutputBytes = maxOutputBytes
        )

        val displayDir = try {
            val rel = workspaceManager.getRelativePath(effectiveWorkDir)
            if (rel.isBlank()) effectiveWorkDir.path else rel
        } catch (_: Exception) {
            effectiveWorkDir.path
        }

        // 5. Return structured ToolResult
        return if (execResult.isTimedOut) {
            val errorMsg = buildString {
                appendLine("Command execution timed out after ${timeoutMs}ms and was killed.")
                appendLine("Command: $rawCommand")
                appendLine("Category: ${classification.riskLevel.displayName}")
                appendLine("Working Directory: $displayDir")
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
            val errorMsg = buildString {
                appendLine("Command exited with code ${execResult.exitCode} (Duration: ${execResult.durationMs}ms)")
                appendLine("Command: $rawCommand")
                appendLine("Category: ${classification.riskLevel.displayName}")
                appendLine("Working Directory: $displayDir")
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
                appendLine("Category: ${classification.riskLevel.displayName}")
                appendLine("Working Directory: $displayDir")
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
}
