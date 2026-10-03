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
    private val commandRunner: CommandRunner = ProcessCommandRunner(workspaceManager = workspaceManager),
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
            ),
            ToolParameter(
                name = "environment",
                type = "string",
                description = "Target execution environment: 'ubuntu' (default, Ubuntu 22.04 LTS userspace with bash, coreutils, apt, dpkg, compilers, and python3), 'termux' (Android Termux environment with pkg and system utilities), or 'auto' (automatic detection based on command).",
                required = false,
                default = "ubuntu"
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

        val environmentArg = (arguments["environment"] ?: arguments["env"])?.toString()?.trim()?.lowercase() ?: "ubuntu"
        val targetEnvironment = when (environmentArg) {
            "termux" -> "termux"
            "auto" -> "auto"
            else -> "ubuntu"
        }

        val workspaceRoot = workspaceManager.getWorkspaceDir()
        val activeWorkingDir = workspaceManager.getCurrentWorkingDir()

        // 1. Resolve and validate working directory (supporting active working dir, /workspace, ~/workspace, and relative paths)
        val resolvedWorkingDir: File = if (workingDirArg.isBlank() || workingDirArg == "." || workingDirArg == "./") {
            activeWorkingDir
        } else if (workingDirArg == "/workspace" || workingDirArg == "~/workspace" || workingDirArg == "~") {
            workspaceRoot
        } else if (workingDirArg.startsWith("/workspace/")) {
            val sub = workingDirArg.removePrefix("/workspace/").trimStart('/')
            File(workspaceRoot, sub).canonicalFile
        } else if (workingDirArg.startsWith("~/workspace/")) {
            val sub = workingDirArg.removePrefix("~/workspace/").trimStart('/')
            File(workspaceRoot, sub).canonicalFile
        } else {
            val candidate = File(workingDirArg)
            if (candidate.isAbsolute && candidate.exists()) {
                candidate.canonicalFile
            } else {
                File(activeWorkingDir, workingDirArg.trimStart('/')).canonicalFile
            }
        }

        if (!resolvedWorkingDir.exists() || !resolvedWorkingDir.isDirectory) {
            resolvedWorkingDir.mkdirs()
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

        // Sanitize command input (strip prompt prefixes, comments, terminal transcripts, unescape newlines)
        val sanitizedCommand = sanitizeCommand(rawCommand)
        if (sanitizedCommand.isBlank() || isOnlyComments(sanitizedCommand)) {
            return ToolResult.success(
                callId = callId,
                toolName = definition.name,
                result = "Exit Code: 0 (Duration: 0ms)\nCommand: $rawCommand\nWorking Directory: $workingDirArg\n\n(Command executed successfully with no output)"
            )
        }

        // 3. SEPARATION GATE: Prevent natural language explanations, markdown, or raw code from reaching shell
        if (isNaturalLanguageOrRawCode(sanitizedCommand)) {
            return ToolResult.failure(
                callId = callId,
                toolName = definition.name,
                error = "Invalid command payload: Natural language reasoning, explanation, or raw code was passed to 'run_command' instead of an executable shell command.\nAttempted payload: $rawCommand\n\nFix: Put reasoning and explanations in your message narrative. To create files, use 'file_write' or bash heredocs ('cat << 'EOF' > filename ... EOF'). To execute code, run 'python3 script.py' or use 'python_execute'."
            )
        }

        // 4. Pre-execution script path validation (e.g. 'python3 src/prayer_times.py')
        val scriptValidationError = validateScriptTargetBeforeExecution(sanitizedCommand, resolvedWorkingDir, workspaceRoot)
        if (scriptValidationError != null) {
            return scriptValidationError
        }

        val nonCommentLines = sanitizedCommand.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith("#") }
        val isMultiCommand = nonCommentLines.size > 1 ||
            sanitizedCommand.contains("&&") ||
            sanitizedCommand.contains("||") ||
            (sanitizedCommand.contains(";") && !sanitizedCommand.contains("<<"))

        val cleanExec = if (isMultiCommand) {
            "bash"
        } else {
            val singleLine = nonCommentLines.firstOrNull() ?: sanitizedCommand.trim()
            val tokens = singleLine.split(Regex("\\s+")).map { it.trim('\'', '"') }.filter { it.isNotBlank() }
            val effectiveExecutable = if (tokens.firstOrNull() == "sudo" && tokens.size > 1) {
                tokens[1]
            } else {
                tokens.firstOrNull() ?: ""
            }
            val rawExec = if (effectiveExecutable.contains('/')) File(effectiveExecutable).name else effectiveExecutable
            if (rawExec.isBlank() || rawExec == "#" || rawExec.contains('@') || rawExec.endsWith('$')) "bash" else rawExec
        }

        // 5. Execute via modular CommandRunner
        val execResult = commandRunner.run(
            command = sanitizedCommand,
            workingDir = resolvedWorkingDir,
            timeoutMs = timeoutMs,
            maxOutputBytes = maxOutputBytes,
            environment = targetEnvironment
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
            val isUnavailable = (
                execResult.exitCode == 127 ||
                execResult.stderr.contains("inaccessible or not found", ignoreCase = true) ||
                execResult.stderr.contains("not found", ignoreCase = true) ||
                execResult.stderr.contains("command not found", ignoreCase = true)
            )

            if (isUnavailable) {
                val aptPkg = com.example.agent.proot.ProotAptManager.findPackageProviding(cleanExec)
                if (aptPkg != null) {
                    val errorMsg = buildString {
                        appendLine("bash: $cleanExec: command not found")
                        appendLine("Command '$cleanExec' can be installed in Ubuntu userspace with:")
                        appendLine("  apt update && apt install -y ${aptPkg.name}")
                    }
                    return ToolResult.failure(callId = callId, toolName = definition.name, error = errorMsg.trim())
                }

                val errorMsg = buildString {
                    appendLine("bash: $cleanExec: command not found")
                    appendLine("Command: $rawCommand")
                    appendLine("Working Directory: $relativeDir")
                    if (execResult.stderr.isNotBlank() && !execResult.stderr.contains("inaccessible or not found", ignoreCase = true)) {
                        appendLine("\n[stderr]")
                        append(execResult.stderr)
                    }
                }
                return ToolResult.failure(callId = callId, toolName = definition.name, error = errorMsg.trim())
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
            // Track working directory changes on successful cd
            val trimmedCmd = sanitizedCommand.trim()
            if (trimmedCmd == "cd" || trimmedCmd.startsWith("cd ")) {
                val targetArg = trimmedCmd.removePrefix("cd").trim().trim('\'', '"')
                val candidateDir = when {
                    targetArg.isEmpty() || targetArg == "~" -> workspaceRoot
                    targetArg.startsWith("/") -> File(workspaceRoot, targetArg.trimStart('/')).canonicalFile
                    else -> File(resolvedWorkingDir, targetArg).canonicalFile
                }
                if (candidateDir.exists() && candidateDir.isDirectory) {
                    workspaceManager.setCurrentWorkingDir(candidateDir)
                }
            }

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

        // If s has no actual newlines but contains literal \n (common in JSON), unescape it
        if (!s.contains('\n') && s.contains("\\n")) {
            s = s.replace("\\n", "\n").replace("\\r", "\r")
        }

        return stripPromptsSafely(s)
    }

    private fun stripPromptsSafely(command: String): String {
        val lines = command.lines()
        val processed = mutableListOf<String>()
        var activeHeredocDelim: String? = null

        // Matches terminal prompt prefixes strictly:
        // 1. user@host:path$ or user@host:path# followed by space
        // 2. Standalone $ followed by space
        // 3. Standalone > followed by space
        // NEVER match # at start of line because # is a valid shell/python comment
        val promptRegex = Regex("""^[ \t]*(?:[a-zA-Z0-9._-]+@[a-zA-Z0-9._-]+:[^$#\r\n]*[\$#]|\$|>)[ \t]+""")

        for (line in lines) {
            if (activeHeredocDelim != null) {
                // Inside heredoc body: keep 100% VERBATIM without any prompt stripping
                processed.add(line)
                val trimmedLine = line.trim().trim('\'', '"')
                if (trimmedLine == activeHeredocDelim) {
                    activeHeredocDelim = null
                }
                continue
            }

            val heredocMatch = Regex("""<<-?\s*['"]?([A-Za-z0-9_.-]+)['"]?""").find(line)
            if (heredocMatch != null) {
                activeHeredocDelim = heredocMatch.groupValues[1]
            }

            var cleanLine = line
            if (promptRegex.containsMatchIn(cleanLine)) {
                cleanLine = cleanLine.replace(promptRegex, "")
            } else if (cleanLine.trimStart().startsWith("$ ")) {
                cleanLine = cleanLine.trimStart().removePrefix("$ ")
            }

            processed.add(cleanLine)
        }

        return processed.joinToString("\n").trim()
    }

    private fun isNaturalLanguageOrRawCode(cmd: String): Boolean {
        val nonComment = cmd.lines()
            .map { it.trim() }
            .firstOrNull { it.isNotBlank() && !it.startsWith("#") } ?: return false

        // Check if inside a heredoc or echo/cat/printf
        if (nonComment.startsWith("cat <<") || nonComment.startsWith("echo ") || nonComment.startsWith("printf ")) {
            return false
        }

        val tokens = nonComment.split(Regex("\\s+")).filter { it.isNotBlank() }
        val firstToken = tokens.firstOrNull()?.lowercase()?.trim('\'', '"', '`', ':', '.') ?: ""

        val rawCodeKeywords = setOf("def", "class", "return", "import", "from", "function", "const", "let", "var", "public", "private")
        if (firstToken in rawCodeKeywords) {
            return true
        }

        val proseLeadingWords = setOf(
            "the", "this", "here", "in", "we", "i", "note", "please", "to", "first", "next", "finally",
            "you", "as", "for", "let's", "lets", "it"
        )
        if (firstToken in proseLeadingWords) {
            if (tokens.size >= 2) {
                val secondToken = tokens.getOrNull(1)?.lowercase() ?: ""
                val isCommandAfterWord = secondToken in setOf("python", "python3", "cat", "ls", "cd", "mkdir")
                if (!isCommandAfterWord) {
                    return true
                }
            } else if (nonComment.endsWith(".") || nonComment.endsWith(":")) {
                return true
            }
        }

        return false
    }

    private fun validateScriptTargetBeforeExecution(
        command: String,
        workingDir: File,
        workspaceRoot: File
    ): ToolResult? {
        val trimmed = command.trim()
        val scriptCmdMatch = Regex("""^(?:sudo\s+)?(?:python3|python|py|bash|sh)\s+([^\s;&|<>'"]+|'[^']+'|"[^"]+")(?:\s+.*)?$""").find(trimmed)
            ?: return null

        val rawTarget = scriptCmdMatch.groupValues[1].trim().trim('\'', '"')
        if (rawTarget.startsWith("-")) return null

        val resolved = try {
            val cand = File(rawTarget)
            if (cand.isAbsolute && cand.exists()) {
                cand
            } else if (rawTarget.startsWith("/workspace/")) {
                File(workspaceRoot, rawTarget.removePrefix("/workspace/").trimStart('/')).canonicalFile
            } else if (workingDir != workspaceRoot && File(workingDir, rawTarget).exists()) {
                File(workingDir, rawTarget).canonicalFile
            } else {
                workspaceManager.resolvePath(rawTarget)
            }
        } catch (_: Exception) {
            null
        }

        if (resolved == null || !resolved.exists() || !resolved.isFile) {
            val availableFiles = workspaceManager.listWorkspaceFiles()
                .filter { !it.isDirectory }
                .map { it.relativePath }
            val baseName = File(rawTarget).name
            val matching = availableFiles.filter { it.endsWith(baseName) || it.contains(baseName) }

            val execTokens = trimmed.split(Regex("\\s+")).filter { it.isNotBlank() }
            val execName = if (execTokens.firstOrNull() == "sudo" && execTokens.size > 1) execTokens[1] else execTokens.firstOrNull() ?: "python3"
            val relativeDir = try {
                workspaceManager.getRelativePath(workingDir).ifBlank { "." }
            } catch (_: Exception) {
                workingDir.path
            }

            val errorMsg = buildString {
                appendLine("$execName: can't open file '$rawTarget': [Errno 2] No such file or directory")
                appendLine("Working Directory: $relativeDir")
                if (matching.isNotEmpty()) {
                    appendLine("\nDid you mean:")
                    for (m in matching) {
                        appendLine("  $execName $m")
                    }
                }
                appendLine("\nWorkspace files present:")
                if (availableFiles.isNotEmpty()) {
                    for (f in availableFiles.take(15)) {
                        appendLine("  - $f")
                    }
                    if (availableFiles.size > 15) {
                        appendLine("  ... (${availableFiles.size - 15} more files)")
                    }
                } else {
                    appendLine("  (Workspace is currently empty)")
                }
                appendLine("\nRecovery advice:")
                appendLine("Create the script file first using 'file_write' or a bash heredoc ('cat << 'EOF' > $rawTarget ... EOF') before executing.")
            }

            return ToolResult.failure(
                callId = UUID.randomUUID().toString(),
                toolName = definition.name,
                error = errorMsg.trim()
            )
        }

        return null
    }

    private fun isOnlyComments(cmd: String): Boolean {
        val nonComment = cmd.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith("#") }
        return nonComment.isEmpty()
    }
}
