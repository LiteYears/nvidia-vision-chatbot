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
        description = "Executes commands inside the Ubuntu 22.04 LTS PRoot workspace container and Python 3 environment. " +
            "Supports running scripts ('python3 script.py', 'python3 -c \"...\"'), PRoot container tools ('proot', 'proot-distro list/login/status'), " +
            "package management ('apt update', 'apt install -y <pkg>', 'pip install <package>', 'pip uninstall -y <package>', 'pip list'), " +
            "compound chaining ('cmd1 && cmd2', 'cd <dir> && pwd'), pipes ('cmd1 | cmd2'), redirection ('> file', '>> file', '> /dev/null', '2>&1'), " +
            "and Linux terminal utilities (bash, sudo, su, ls, cat, grep, find, sed, awk, cut, sort, uniq, tr, tee, xargs, touch, cp, mv, rm, mkdir, tree, curl, wget, uname, whoami, id, date, env, free, df, ps, tar, zip, unzip, md5sum). " +
            "Packages install into workspace lib/ and are immediately discoverable and importable. Returns stdout, stderr, exit code, and duration.",
        parameters = listOf(
            ToolParameter(
                name = "command",
                type = "string",
                description = "The shell command to execute (e.g. 'pip install requests', 'pip uninstall -y requests', 'python3 script.py', 'python3 -c \"import requests; ...\"', 'ls -la', 'uname -a').",
                required = true
            ),
            ToolParameter(
                name = "working_dir",
                type = "string",
                description = "Directory to execute within (default: '.' for workspace root)",
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
        val resolvedWorkingDir: File = if (workingDirArg.isBlank() || workingDirArg == "." || workingDirArg == "./") {
            workspaceRoot
        } else {
            val candidate = File(workingDirArg)
            if (candidate.isAbsolute) {
                candidate.canonicalFile
            } else {
                File(workspaceRoot, workingDirArg).canonicalFile
            }
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

        // Sanitize command input (strip prompt prefixes, comments, terminal transcripts)
        val sanitizedCommand = sanitizeCommand(rawCommand)
        if (sanitizedCommand.isBlank() || isOnlyComments(sanitizedCommand)) {
            return ToolResult.success(
                callId = callId,
                toolName = definition.name,
                result = "Exit Code: 0 (Duration: 0ms)\nCommand: $rawCommand\nWorking Directory: $workingDirArg\n\n(Command executed successfully with no output)"
            )
        }

        val nonCommentLine = sanitizedCommand.lines()
            .map { it.trim() }
            .firstOrNull { it.isNotBlank() && !it.startsWith("#") } ?: sanitizedCommand
        val primaryExecutable = nonCommentLine.split(Regex("\\s+")).firstOrNull()?.trim('\'', '"') ?: ""
        val rawExec = if (primaryExecutable.contains('/')) File(primaryExecutable).name else primaryExecutable
        val cleanExec = if (rawExec.isBlank() || rawExec == "#" || rawExec.contains('@') || rawExec.endsWith('$')) "bash" else rawExec

        // 3. Execute via modular CommandRunner
        val execResult = commandRunner.run(
            command = sanitizedCommand,
            workingDir = resolvedWorkingDir,
            timeoutMs = timeoutMs,
            maxOutputBytes = maxOutputBytes
        )

        val relativeDir = try {
            workspaceManager.getRelativePath(resolvedWorkingDir).ifBlank { "." }
        } catch (_: Exception) {
            resolvedWorkingDir.canonicalPath
        }

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
        } else if (execResult.exitCode != 0 && !(execResult.isTruncated && execResult.exitCode == 141)) {
            val isKnownShellOrBuiltin = cleanExec in setOf("bash", "sh", "zsh", "cat", "echo", "mkdir", "cd", "python", "python3")
            val isUnavailable = !isKnownShellOrBuiltin && !capabilityDetector.isExecutableAvailable(cleanExec) && (
                execResult.exitCode == 127 ||
                execResult.stderr.contains("inaccessible or not found", ignoreCase = true) ||
                execResult.stderr.contains("not found", ignoreCase = true) ||
                execResult.stderr.contains("command not found", ignoreCase = true)
            )

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

    private fun sanitizeCommand(raw: String): String {
        var s = raw.trim()
        val transcriptIndex = s.indexOf("[stdout]")
        if (transcriptIndex >= 0) {
            s = s.substring(0, transcriptIndex).trim()
        }
        val exitCodeIndex = s.indexOf("Exit Code:")
        if (exitCodeIndex >= 0) {
            s = s.substring(0, exitCodeIndex).trim()
        }
        val processExitedIndex = s.indexOf("[Process exited")
        if (processExitedIndex >= 0) {
            s = s.substring(0, processExitedIndex).trim()
        }

        // Strip leading prompt prefixes from lines: ubuntu@termux:~$, user@host:~/dir$, $, #, >
        val promptRegex = Regex("""(?m)^[ \t]*(?:[a-zA-Z0-9._-]+@[a-zA-Z0-9._-]+:[^$#\r\n]*[\$#]|[\$#>])[ \t]*""")
        s = s.replace(promptRegex, "").trim()

        val commandKeywords = listOf("cat <<", "python3 ", "python ", "pip ", "pip3 ", "echo ", "mkdir ", "cd ", "pytest ", "npm ", "node ", "git ", "curl ", "bash ", "sh ")
        val lines = s.lines().toMutableList()
        for (i in lines.indices) {
            val line = lines[i].trim()
            if (line.startsWith("#")) {
                for (kw in commandKeywords) {
                    val kwIdx = line.indexOf(kw)
                    if (kwIdx > 0) {
                        lines[i] = line.substring(kwIdx)
                        break
                    }
                }
            }
        }
        return lines.joinToString("\n").trim()
    }

    private fun isOnlyComments(cmd: String): Boolean {
        val nonComment = cmd.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith("#") }
        return nonComment.isEmpty()
    }
}
