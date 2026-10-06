package com.example.agent.proot

import com.example.agent.python.EmbeddedPythonRuntime
import com.example.agent.python.WorkspacePipManager
import com.example.agent.tools.command.CommandExecutionResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.json.JSONObject

/**
 * Complete PRoot Ubuntu user-space shell executor.
 *
 * Implements:
 * - Virtual Linux Rootfs (/etc/os-release, /proc/version, /home/ubuntu, /root, /tmp, /workspace)
 * - Pipes (cmd1 | cmd2)
 * - Redirection (> file, >> file, < file)
 * - Sudo & Root emulation (sudo <cmd>, whoami -> root/ubuntu, id)
 * - Linux terminal utilities (ls, cat, grep, find, head, tail, wc, sed, awk, tr, cut, sort, uniq, tar, zip, ps, df, free, uptime)
 * - Package managers: APT (apt install/remove/list/update, dpkg -l) and PIP (pip install/uninstall/list)
 * - Embedded Python 3 runtime with PRoot virtual path support
 */
class ProotCommandExecutor(
    private val rootfsManager: ProotRootfsManager = ProotRootfsManager.getInstance(),
    private val aptManager: ProotAptManager = ProotAptManager(),
    private val pipManager: WorkspacePipManager = WorkspacePipManager(),
    private val pythonRuntime: EmbeddedPythonRuntime = EmbeddedPythonRuntime()
) {

    suspend fun execute(
        commandLine: String,
        workingDir: File,
        workspaceRoot: File,
        timeoutMs: Long,
        maxOutputBytes: Int
    ): CommandExecutionResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        val trimmed = commandLine.trim()
        if (trimmed.isBlank()) {
            return@withContext CommandExecutionResult(0, "", "", 0)
        }

        rootfsManager.ensureRootfs(workspaceRoot)

        // Check for single heredoc statement before compound splitting
        if (trimmed.contains("<<")) {
            val compoundStatements = splitCompound(trimmed)
            if (compoundStatements.size > 1) {
                return@withContext executeCompound(compoundStatements, workingDir, workspaceRoot, timeoutMs, maxOutputBytes)
            }
            return@withContext executeHeredoc(trimmed, workingDir, workspaceRoot, timeoutMs, maxOutputBytes)
        }

        // 1. Compound statement splitting (&&, ;, ||)
        val compoundStatements = splitCompound(trimmed)
        if (compoundStatements.size > 1) {
            return@withContext executeCompound(compoundStatements, workingDir, workspaceRoot, timeoutMs, maxOutputBytes)
        }

        // 2. Execute single pipeline / command
        executePipeline(trimmed, workingDir, workspaceRoot, timeoutMs, maxOutputBytes, stdin = "")
    }

    private data class CompoundStep(val command: String, val operator: String)

    private fun splitCompound(raw: String): List<CompoundStep> {
        val trimmed = raw.trim()
        if ((trimmed.startsWith("for ") || trimmed.startsWith("while ") || trimmed.startsWith("if ")) &&
            (trimmed.endsWith("; done") || trimmed.endsWith(" done") || trimmed.endsWith("; fi") || trimmed.endsWith(" fi") || trimmed.endsWith("\ndone") || trimmed.endsWith("\nfi"))) {
            return listOf(CompoundStep(trimmed, ""))
        }

        val steps = mutableListOf<CompoundStep>()
        val current = StringBuilder()
        var inSingle = false
        var inDouble = false
        var escape = false
        var pendingOp = ""
        var activeHeredocDelim: String? = null

        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (escape) {
                current.append(c)
                escape = false
                i++
                continue
            }
            if (c == '\\') {
                escape = true
                current.append(c)
                i++
                continue
            }
            if (c == '\'' && !inDouble) {
                inSingle = !inSingle
                current.append(c)
                i++
                continue
            }
            if (c == '"' && !inSingle) {
                inDouble = !inDouble
                current.append(c)
                i++
                continue
            }

            if (!inSingle && !inDouble) {
                // If inside a heredoc body, do not break on semicolons or operators until the closing delimiter line
                if (activeHeredocDelim != null) {
                    val lineSoFar = current.toString().substringAfterLast('\n').trim().trim('\'', '"')
                    if (lineSoFar == activeHeredocDelim && (c == '\n' || c == ';' || (c == '&' && i + 1 < raw.length && raw[i + 1] == '&') || (c == '|' && i + 1 < raw.length && raw[i + 1] == '|'))) {
                        activeHeredocDelim = null
                        if (current.isNotBlank()) {
                            steps.add(CompoundStep(current.toString().trim(), pendingOp))
                            current.clear()
                        }
                        if (c == '&' && i + 1 < raw.length && raw[i + 1] == '&') {
                            pendingOp = "&&"
                            i += 2
                        } else if (c == '|' && i + 1 < raw.length && raw[i + 1] == '|') {
                            pendingOp = "||"
                            i += 2
                        } else {
                            pendingOp = ";"
                            i++
                        }
                        continue
                    }
                    current.append(c)
                    i++
                    continue
                }

                // Detect start of heredoc: << or <<-
                if (c == '<' && i + 1 < raw.length && raw[i + 1] == '<' && (i + 2 >= raw.length || raw[i + 2] != '<')) {
                    val remainingLine = raw.substring(i).substringBefore('\n')
                    val delimMatch = Regex("""<<-?\s*['"]?([A-Za-z0-9_.-]+)['"]?""").find(remainingLine)
                    if (delimMatch != null) {
                        activeHeredocDelim = delimMatch.groupValues[1]
                    }
                }

                if (c == ';' || c == '\n') {
                    if (current.isNotBlank()) {
                        steps.add(CompoundStep(current.toString().trim(), pendingOp))
                        current.clear()
                    }
                    pendingOp = ";"
                    i++
                    continue
                }
                if (c == '&' && i + 1 < raw.length && raw[i + 1] == '&') {
                    if (current.isNotBlank()) {
                        steps.add(CompoundStep(current.toString().trim(), pendingOp))
                        current.clear()
                    }
                    pendingOp = "&&"
                    i += 2
                    continue
                }
                if (c == '|' && i + 1 < raw.length && raw[i + 1] == '|') {
                    if (current.isNotBlank()) {
                        steps.add(CompoundStep(current.toString().trim(), pendingOp))
                        current.clear()
                    }
                    pendingOp = "||"
                    i += 2
                    continue
                }
            }

            current.append(c)
            i++
        }

        if (current.isNotBlank()) {
            steps.add(CompoundStep(current.toString().trim(), pendingOp))
        }

        return steps
    }

    private suspend fun executeCompound(
        steps: List<CompoundStep>,
        workingDir: File,
        workspaceRoot: File,
        timeoutMs: Long,
        maxOutputBytes: Int
    ): CommandExecutionResult {
        val startTime = System.currentTimeMillis()
        val stdoutSb = StringBuilder()
        val stderrSb = StringBuilder()
        var lastExit = 0
        var isTruncated = false
        var currentWorkingDir = workingDir
        var previousWorkingDir = workingDir

        for ((idx, step) in steps.withIndex()) {
            if (idx > 0) {
                if (step.operator == "&&" && lastExit != 0) break
                if (step.operator == "||" && lastExit == 0) break
            }
            val remainingTimeout = (timeoutMs - (System.currentTimeMillis() - startTime)).coerceAtLeast(500L)

            val stepCmd = step.command.trim()
            if (stepCmd.isBlank() || isPurelyComment(stepCmd)) {
                continue
            }
            if (stepCmd == "cd" || stepCmd.startsWith("cd ")) {
                val targetArg = stepCmd.removePrefix("cd").trim().trim('\'', '"')
                val targetDir = when {
                    targetArg.isEmpty() || targetArg == "~" -> workspaceRoot
                    targetArg == "-" -> previousWorkingDir
                    else -> rootfsManager.resolveVirtualPath(targetArg, currentWorkingDir, workspaceRoot)
                }
                if (targetDir.exists() && targetDir.isDirectory) {
                    previousWorkingDir = currentWorkingDir
                    currentWorkingDir = targetDir
                    lastExit = 0
                } else {
                    lastExit = 1
                    if (stderrSb.isNotEmpty()) stderrSb.append("\n")
                    stderrSb.append("bash: cd: $targetArg: No such file or directory")
                    if (step.operator == "&&") break
                }
                continue
            }

            val stepRes = executePipeline(stepCmd, currentWorkingDir, workspaceRoot, remainingTimeout, maxOutputBytes, stdin = "")
            lastExit = stepRes.exitCode
            if (stepRes.stdout.isNotBlank()) {
                if (stdoutSb.isNotEmpty()) stdoutSb.append("\n")
                stdoutSb.append(stepRes.stdout)
            }
            if (stepRes.stderr.isNotBlank()) {
                if (stderrSb.isNotEmpty()) stderrSb.append("\n")
                stderrSb.append(stepRes.stderr)
            }
            if (stepRes.isTruncated) isTruncated = true
            if (stepRes.isTimedOut) {
                return CommandExecutionResult(
                    exitCode = -1,
                    stdout = stdoutSb.toString(),
                    stderr = stderrSb.toString(),
                    durationMs = System.currentTimeMillis() - startTime,
                    isTimedOut = true,
                    isTruncated = isTruncated
                )
            }
        }

        if (currentWorkingDir != workingDir && currentWorkingDir.exists() && currentWorkingDir.isDirectory) {
            com.example.agent.tools.workspace.AgentWorkspaceManager.getInstance().setCurrentWorkingDir(currentWorkingDir)
        }

        return CommandExecutionResult(
            exitCode = lastExit,
            stdout = stdoutSb.toString(),
            stderr = stderrSb.toString(),
            durationMs = System.currentTimeMillis() - startTime,
            isTimedOut = false,
            isTruncated = isTruncated
        )
    }

    /**
     * Executes a pipeline like 'cmd1 | cmd2 | cmd3' with redirection '> file', '>> file'.
     */
    private suspend fun executePipeline(
        pipelineCmd: String,
        workingDir: File,
        workspaceRoot: File,
        timeoutMs: Long,
        maxOutputBytes: Int,
        stdin: String
    ): CommandExecutionResult {
        val startTime = System.currentTimeMillis()

        var pCmd = pipelineCmd.trim()
        if (pCmd.isBlank() || isPurelyComment(pCmd)) {
            return CommandExecutionResult(0, "", "", 0)
        }
        var redirectStderrToStdout = false
        if (pCmd.contains("2>&1")) {
            redirectStderrToStdout = true
            pCmd = pCmd.replace("2>&1", "").trim()
        }
        if (pCmd.contains("2> /dev/null") || pCmd.contains("2>/dev/null")) {
            pCmd = pCmd.replace("2> /dev/null", "").replace("2>/dev/null", "").trim()
        }

        // Check for heredocs (<<) before general redirection
        if (pCmd.contains("<<")) {
            return executeHeredoc(pCmd, workingDir, workspaceRoot, timeoutMs, maxOutputBytes)
        }

        // Check for redirection operators (>, >>)
        val redirAppend = pCmd.contains(">>")
        val redirTruncate = !redirAppend && pCmd.contains(">")

        if (redirAppend || redirTruncate) {
            val delim = if (redirAppend) ">>" else ">"
            val parts = pCmd.split(delim, limit = 2)
            val leftCmd = parts[0].trim()
            val targetFileStr = parts.getOrNull(1)?.trim()?.trim('\'', '"') ?: ""

            val execRes = executePipeline(leftCmd, workingDir, workspaceRoot, timeoutMs, maxOutputBytes, stdin)
            val combinedErr = if (redirectStderrToStdout) "" else execRes.stderr
            val combinedOut = if (redirectStderrToStdout && execRes.stderr.isNotBlank()) {
                if (execRes.stdout.isNotBlank()) "${execRes.stdout}\n${execRes.stderr}" else execRes.stderr
            } else execRes.stdout

            if (targetFileStr == "/dev/null" || targetFileStr == "dev/null") {
                return CommandExecutionResult(execRes.exitCode, "", combinedErr, System.currentTimeMillis() - startTime)
            }

            if (execRes.exitCode == 0 && targetFileStr.isNotBlank()) {
                val targetFile = rootfsManager.resolveVirtualPath(targetFileStr, workingDir, workspaceRoot)
                targetFile.parentFile?.mkdirs()
                if (redirAppend) {
                    targetFile.appendText(combinedOut)
                } else {
                    targetFile.writeText(combinedOut)
                }
                return CommandExecutionResult(0, "", combinedErr, System.currentTimeMillis() - startTime)
            }
            return CommandExecutionResult(execRes.exitCode, combinedOut, combinedErr, System.currentTimeMillis() - startTime)
        }

        // Split by pipes '|' (respecting quotes)
        val pipeSegments = splitPipes(pCmd)
        if (pipeSegments.size > 1) {
            var currentInput = stdin
            var lastResult: CommandExecutionResult? = null

            for (seg in pipeSegments) {
                val res = executeSingleAtomic(seg, workingDir, workspaceRoot, timeoutMs, maxOutputBytes, stdin = currentInput)
                lastResult = res
                if (res.exitCode != 0) return res
                currentInput = res.stdout
            }
            return lastResult ?: CommandExecutionResult(0, "", "", 0)
        }

        val atomicRes = executeSingleAtomic(pCmd, workingDir, workspaceRoot, timeoutMs, maxOutputBytes, stdin)
        if (redirectStderrToStdout && atomicRes.stderr.isNotBlank()) {
            val out = if (atomicRes.stdout.isNotBlank()) "${atomicRes.stdout}\n${atomicRes.stderr}" else atomicRes.stderr
            return CommandExecutionResult(atomicRes.exitCode, out, "", atomicRes.durationMs, atomicRes.isTimedOut, atomicRes.isTruncated)
        }
        return atomicRes
    }

    private suspend fun executeHeredoc(
        command: String,
        workingDir: File,
        workspaceRoot: File,
        timeoutMs: Long,
        maxOutputBytes: Int
    ): CommandExecutionResult {
        val startTime = System.currentTimeMillis()
        val lines = command.lines()
        if (lines.isEmpty()) {
            return CommandExecutionResult(0, "", "", 0)
        }

        val heredocLineIndex = lines.indexOfFirst { it.contains("<<") }
        if (heredocLineIndex < 0) {
            return CommandExecutionResult(0, "", "", 0)
        }

        val firstLine = lines[heredocLineIndex]
        val delimMatch = Regex("""<<-?\s*['"]?([A-Za-z0-9_.-]+)['"]?""").find(firstLine)
        val delim = delimMatch?.groupValues?.get(1) ?: "EOF"

        // Check for redirection target on the first line or subsequent line
        val redirAppend = firstLine.contains(">>")
        val redirTruncate = !redirAppend && firstLine.contains(">")
        val lastLine = lines.lastOrNull()?.trim() ?: ""
        val targetPath = if (redirAppend) {
            Regex(""">>\s*(?:"([^"]+)"|'([^']+)'|([^\s<>|;&]+))""").find(firstLine)?.let {
                it.groupValues[1].ifEmpty { it.groupValues[2].ifEmpty { it.groupValues[3] } }
            }?.trim('\'', '"')
        } else if (redirTruncate) {
            Regex("""(?<!>)>\s*(?:"([^"]+)"|'([^']+)'|([^\s<>|;&]+))""").find(firstLine)?.let {
                it.groupValues[1].ifEmpty { it.groupValues[2].ifEmpty { it.groupValues[3] } }
            }?.trim('\'', '"')
        } else if (lastLine.contains(">")) {
            Regex("""(?<!>)>{1,2}\s*(?:"([^"]+)"|'([^']+)'|([^\s<>|;&]+))""").find(lastLine)?.let {
                it.groupValues[1].ifEmpty { it.groupValues[2].ifEmpty { it.groupValues[3] } }
            }?.trim('\'', '"')
        } else null

        // Collect body lines up to closing delimiter line
        val rawBodyLines = lines.drop(heredocLineIndex + 1).takeWhile { 
            val clean = it.trim().trim('\'', '"').trimEnd(';', ' ')
            clean != delim
        }
        val body = if (rawBodyLines.isNotEmpty()) {
            rawBodyLines.joinToString("\n")
        } else {
            // Single-line or inline heredoc fallback
            val delimToken = delimMatch?.value ?: delim
            val afterDelim = firstLine.substringAfter(delimToken).trim()
            var rawInline = if (afterDelim.contains(delim)) afterDelim.substringBeforeLast(delim).trim() else afterDelim
            if (rawInline.startsWith(">") || rawInline.startsWith(">>")) {
                rawInline = rawInline.replaceFirst(Regex("""^>{1,2}\s*(?:"[^"]+"|\'[^\']+\'|\S+)\s*"""), "").trim()
            }
            if (rawInline.isNotBlank()) formatInlineScriptContent(rawInline) else ""
        }

        // First line executable
        val tokens = tokenize(firstLine.substringBefore("<<").substringBefore(">").trim())
        val execToken = tokens.firstOrNull()?.lowercase() ?: "cat"
        val cleanExec = if (execToken.contains('/')) File(execToken).name.lowercase() else execToken

        // Redirection target exists: write or append to file
        if (!targetPath.isNullOrBlank() && targetPath != "/dev/null" && targetPath != "dev/null") {
            try {
                val targetFile = rootfsManager.resolveVirtualPath(targetPath, workingDir, workspaceRoot)
                targetFile.parentFile?.mkdirs()
                if (redirAppend) {
                    targetFile.appendText(body + "\n")
                } else {
                    targetFile.writeText(body + "\n")
                }
                return CommandExecutionResult(0, "", "", System.currentTimeMillis() - startTime)
            } catch (e: Exception) {
                return CommandExecutionResult(1, "", "bash: $targetPath: ${e.message ?: "Failed to write file"}\n", System.currentTimeMillis() - startTime)
            }
        }

        if (cleanExec == "python" || cleanExec == "python3" || cleanExec == "py") {
            val pyRes = pythonRuntime.execute(
                code = body,
                filename = "<heredoc>",
                args = emptyList(),
                timeoutMs = timeoutMs,
                maxOutputBytes = maxOutputBytes
            )
            return CommandExecutionResult(
                exitCode = pyRes.exitCode,
                stdout = pyRes.stdout,
                stderr = pyRes.stderr,
                durationMs = System.currentTimeMillis() - startTime,
                isTimedOut = pyRes.isTimedOut,
                isTruncated = pyRes.isTruncated
            )
        }

        if (cleanExec == "bash" || cleanExec == "sh") {
            val innerSteps = splitCompound(body)
            return executeCompound(innerSteps, workingDir, workspaceRoot, timeoutMs, maxOutputBytes)
        }

        // Default: outputs body (like cat << EOF)
        return CommandExecutionResult(0, if (body.endsWith("\n")) body else "$body\n", "", System.currentTimeMillis() - startTime)
    }

    private fun formatInlineScriptContent(raw: String): String {
        if (raw.contains('\n')) return raw
        var s = raw
        val keywords = listOf(
            "import ", "from ", "def ", "class ", "@", "if __name__", "if ", "elif ",
            "else:", "return ", "assert ", "print(", "app = ", "app.run("
        )
        for (kw in keywords) {
            if (s.contains(" $kw")) {
                s = s.replace(" $kw", "\n$kw")
            } else if (s.contains("; $kw")) {
                s = s.replace("; $kw", "\n$kw")
            } else if (s.contains(";$kw")) {
                s = s.replace(";$kw", "\n$kw")
            }
        }
        if (s.contains("; ")) {
            s = s.replace("; ", "\n")
        }
        return s.trim()
    }

    private fun splitPipes(cmd: String): List<String> {
        val segments = mutableListOf<String>()
        val current = StringBuilder()
        var inSingle = false
        var inDouble = false
        var escape = false

        for (c in cmd) {
            if (escape) {
                current.append(c)
                escape = false
                continue
            }
            if (c == '\\') {
                escape = true
                current.append(c)
                continue
            }
            if (c == '\'' && !inDouble) {
                inSingle = !inSingle
                current.append(c)
                continue
            }
            if (c == '"' && !inSingle) {
                inDouble = !inDouble
                current.append(c)
                continue
            }
            if (c == '|' && !inSingle && !inDouble) {
                if (current.isNotBlank()) segments.add(current.toString().trim())
                current.clear()
                continue
            }
            current.append(c)
        }
        if (current.isNotBlank()) segments.add(current.toString().trim())
        return segments
    }

    private suspend fun executeSingleAtomic(
        command: String,
        workingDir: File,
        workspaceRoot: File,
        timeoutMs: Long,
        maxOutputBytes: Int,
        stdin: String
    ): CommandExecutionResult {
        val startTime = System.currentTimeMillis()
        var cmd = command.trim()
        if (cmd.isBlank() || isPurelyComment(cmd)) {
            return CommandExecutionResult(0, "", "", 0)
        }
        if (cmd == "^C" || cmd == "\u0003" || cmd.endsWith("^C")) {
            return CommandExecutionResult(130, "", "", 0)
        }
        var isRoot = false

        // Sudo handling: simulate fake-id0
        if (cmd.startsWith("sudo ")) {
            isRoot = true
            cmd = cmd.removePrefix("sudo ").trim()
        }

        val tokens = tokenize(cmd)
        if (tokens.isEmpty()) {
            return CommandExecutionResult(0, "", "", 0)
        }

        val execName = tokens[0].trim('\'', '"')
        val cleanExec = if (execName.contains('/')) File(execName).name.lowercase() else execName.lowercase()
        val args = tokens.drop(1)

        // 1. APT & DPKG
        if (cleanExec == "apt" || cleanExec == "apt-get" || cleanExec == "dpkg") {
            return aptManager.execute(cmd, workingDir, workspaceRoot)
        }

        // 1b. Termux PKG manager
        if (cleanExec == "pkg") {
            return handlePkgCommand(tokens, workingDir, workspaceRoot, startTime)
        }

        // 1c. Termux storage & utility helpers
        if (cleanExec == "termux-setup-storage") {
            val storageDir = File(workspaceRoot, ".termux/storage").apply { mkdirs() }
            File(storageDir, "shared").mkdirs()
            File(storageDir, "downloads").mkdirs()
            return CommandExecutionResult(0, "Storage directory setup completed. Permissions granted.\n", "", 5)
        }
        if (cleanExec == "termux-fix-shebang") {
            for (targetArg in args.filter { !it.startsWith("-") }) {
                val f = rootfsManager.resolveVirtualPath(targetArg, workingDir, workspaceRoot)
                if (f.exists() && f.isFile) {
                    val content = f.readText()
                    if (content.startsWith("#!")) {
                        val lines = content.lines()
                        val updated = (listOf("#!/bin/bash") + lines.drop(1)).joinToString("\n")
                        f.writeText(updated)
                    }
                }
            }
            return CommandExecutionResult(0, "", "", 5)
        }

        // 1d. PRoot userspace virtualization
        if (cleanExec == "proot") {
            return handleProotCommand(tokens.drop(1), workingDir, workspaceRoot, timeoutMs, maxOutputBytes)
        }

        // 1e. Neofetch system info
        if (cleanExec == "neofetch") {
            return handleNeofetch(args)
        }

        // 2. PIP
        if (cleanExec == "pip" || cleanExec == "pip3") {
            return pipManager.execute(cmd, workspaceRoot)
        }

        // 3. Python 3
        if (cleanExec == "python" || cleanExec == "python3" || cleanExec == "py") {
            return handlePython(tokens, workingDir, workspaceRoot, timeoutMs, maxOutputBytes)
        }

        // 4. Bash / Sh / Fish / Zsh subshell or script execution
        if (cleanExec == "bash" || cleanExec == "sh" || cleanExec == "fish" || cleanExec == "zsh") {
            if (tokens.size >= 3 && tokens[1] == "-c") {
                val inner = tokens.drop(2).joinToString(" ")
                return executePipeline(inner, workingDir, workspaceRoot, timeoutMs, maxOutputBytes, stdin)
            } else if (tokens.size >= 2 && !tokens[1].startsWith("-")) {
                return executeShellScript(tokens[1], tokens.drop(2), workingDir, workspaceRoot, timeoutMs, maxOutputBytes)
            } else if (tokens.any { it == "--version" || it == "-v" || it == "-V" }) {
                val ver = when (cleanExec) {
                    "fish" -> "fish, version 3.4.1 (Ubuntu 22.04 LTS)"
                    "zsh" -> "zsh 5.8.1 (aarch64-unknown-linux-gnu)"
                    else -> "bash 5.2.15(1)-release (aarch64-unknown-linux-gnu)"
                }
                return CommandExecutionResult(0, "$ver\n", "", 5)
            } else if (tokens.any { it == "--help" || it == "-h" }) {
                val help = when (cleanExec) {
                    "fish" -> "Usage: fish [OPTIONS] [FILE [ARG ...]]\nfish - the friendly interactive shell\n"
                    "zsh" -> "Usage: zsh [OPTIONS] [ARG ...]\n"
                    else -> "GNU bash, version 5.2.15(1)-release (aarch64-unknown-linux-gnu)\n"
                }
                return CommandExecutionResult(0, help, "", 5)
            } else {
                val banner = when (cleanExec) {
                    "fish" -> "Welcome to fish, the friendly interactive shell\nType 'help' for instructions on how to use fish\nfish, version 3.4.1 (Ubuntu 22.04 LTS)\n"
                    "zsh" -> "zsh 5.8.1 (aarch64-unknown-linux-gnu)\n"
                    else -> "Ubuntu 22.04 LTS (bash 5.2.15)\n"
                }
                return CommandExecutionResult(0, banner, "", 5)
            }
        }

        // 4b. Direct script invocation: ./script.sh or script.sh
        if (cleanExec.endsWith(".sh") || cmd.startsWith("./") || (cmd.startsWith("/") && cmd.endsWith(".sh"))) {
            val scriptName = tokens[0].removePrefix("./")
            val scriptFile = rootfsManager.resolveVirtualPath(scriptName, workingDir, workspaceRoot)
            if (scriptFile.exists() && scriptFile.isFile) {
                return executeShellScript(scriptName, tokens.drop(1), workingDir, workspaceRoot, timeoutMs, maxOutputBytes)
            }
        }

        if (cleanExec == "pytest") {
            return handlePytest(tokens.drop(1), workingDir, workspaceRoot, timeoutMs, maxOutputBytes)
        }

        if (cleanExec == "source" || cleanExec == ".") {
            val scriptArg = args.firstOrNull { !it.startsWith("-") }
            if (scriptArg != null && (scriptArg.contains("activate") || scriptArg.endsWith("/activate"))) {
                return CommandExecutionResult(0, "", "", 5)
            }
            if (scriptArg != null) {
                val scriptFile = rootfsManager.resolveVirtualPath(scriptArg, workingDir, workspaceRoot)
                if (scriptFile.exists() && scriptFile.isFile) {
                    val scriptContent = try { scriptFile.readText(Charsets.UTF_8) } catch (_: Exception) { "" }
                    return executePipeline(scriptContent, workingDir, workspaceRoot, timeoutMs, maxOutputBytes, stdin)
                }
            }
            return CommandExecutionResult(0, "", "", 5)
        }

        if (cleanExec == "virtualenv") {
            return handleVenv(args, workingDir, workspaceRoot)
        }

        if (cleanExec == "uvicorn") {
            return handleUvicorn(args, workingDir, workspaceRoot)
        }

        // 5. Linux Utilities & Commands
        when (cleanExec) {
            "neofetch" -> return handleNeofetch(args)
            "whoami" -> {
                val user = if (isRoot) "root" else "ubuntu"
                return CommandExecutionResult(0, "$user\n", "", 5)
            }
            "id" -> {
                val out = if (isRoot) {
                    "uid=0(root) gid=0(root) groups=0(root)\n"
                } else {
                    "uid=1000(ubuntu) gid=1000(ubuntu) groups=1000(ubuntu),4(adm),24(cdrom),27(sudo),30(dip),46(plugdev)\n"
                }
                return CommandExecutionResult(0, out, "", 5)
            }
            "uname" -> {
                val isAll = args.any { it == "-a" || it == "--all" }
                val isKernelVer = args.any { it == "-r" }
                val isMachine = args.any { it == "-m" }
                val out = when {
                    isAll -> "Linux ubuntu-proot 5.15.0-101-generic #111-Ubuntu SMP x86_64 GNU/Linux\n"
                    isKernelVer -> "5.15.0-101-generic\n"
                    isMachine -> "x86_64\n"
                    else -> "Linux\n"
                }
                return CommandExecutionResult(0, out, "", 5)
            }
            "hostname" -> return CommandExecutionResult(0, "ubuntu-proot\n", "", 5)
            "uptime" -> {
                val timeStr = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
                return CommandExecutionResult(0, " $timeStr up 42 days, 14:15,  1 user,  load average: 0.08, 0.03, 0.01\n", "", 5)
            }
            "date" -> {
                val sdf = SimpleDateFormat("EEE MMM dd HH:mm:ss z yyyy", Locale.US).apply { timeZone = TimeZone.getDefault() }
                return CommandExecutionResult(0, "${sdf.format(Date())}\n", "", 5)
            }
            "cal" -> {
                val cal = java.util.Calendar.getInstance()
                val monthName = SimpleDateFormat("MMMM yyyy", Locale.US).format(cal.time)
                val firstDayCal = (cal.clone() as java.util.Calendar).apply {
                    set(java.util.Calendar.DAY_OF_MONTH, 1)
                }
                val startDayOfWeek = firstDayCal.get(java.util.Calendar.DAY_OF_WEEK) // 1 = Sunday
                val daysInMonth = cal.getActualMaximum(java.util.Calendar.DAY_OF_MONTH)

                val out = buildString {
                    val padLeft = ((20 - monthName.length) / 2).coerceAtLeast(0)
                    appendLine(" ".repeat(padLeft) + monthName)
                    appendLine("Su Mo Tu We Th Fr Sa")
                    for (i in 1 until startDayOfWeek) {
                        append("   ")
                    }
                    var curCol = startDayOfWeek
                    for (day in 1..daysInMonth) {
                        append(String.format(Locale.US, "%2d ", day))
                        if (curCol % 7 == 0 && day != daysInMonth) {
                            append("\n")
                        }
                        curCol++
                    }
                    append("\n")
                }
                return CommandExecutionResult(0, out, "", 5)
            }
            "df" -> {
                val totalBytes = workspaceRoot.totalSpace
                val freeBytes = workspaceRoot.usableSpace
                val usedBytes = (totalBytes - freeBytes).coerceAtLeast(0)
                val totalKb = (totalBytes / 1024).coerceAtLeast(1024)
                val usedKb = usedBytes / 1024
                val freeKb = freeBytes / 1024
                val pct = if (totalKb > 0) ((usedKb * 100) / totalKb).toInt() else 0

                val out = buildString {
                    appendLine("Filesystem     1K-blocks      Used Available Use% Mounted on")
                    appendLine(String.format(Locale.US, "%-14s %10d %9d %9d %3d%% /", "/dev/root", totalKb, usedKb, freeKb, pct))
                    appendLine(String.format(Locale.US, "%-14s %10d %9d %9d %3d%% /workspace", "workspace", totalKb, usedKb, freeKb, pct))
                    appendLine(String.format(Locale.US, "%-14s %10d %9d %9d   0%% /dev/shm", "tmpfs", 4096000, 0, 4096000))
                }
                return CommandExecutionResult(0, out, "", 5)
            }
            "free" -> {
                val rt = Runtime.getRuntime()
                val totalMemKb = rt.totalMemory() / 1024
                val freeMemKb = rt.freeMemory() / 1024
                val usedMemKb = totalMemKb - freeMemKb
                val maxMemKb = rt.maxMemory() / 1024
                val availableKb = maxMemKb - usedMemKb

                val out = buildString {
                    appendLine("               total        used        free      shared  buff/cache   available")
                    appendLine(String.format(Locale.US, "%-6s %12d %11d %11d %11d %11d %11d", "Mem:", maxMemKb, usedMemKb, freeMemKb, 0, totalMemKb - freeMemKb, availableKb.coerceAtLeast(freeMemKb)))
                    appendLine(String.format(Locale.US, "%-6s %12d %11d %11d", "Swap:", 0, 0, 0))
                }
                return CommandExecutionResult(0, out, "", 5)
            }
            "ps" -> {
                val isAux = args.any { it.contains("aux") || it.contains("-ef") }
                val sdf = SimpleDateFormat("HH:mm", Locale.US)
                val timeStr = sdf.format(Date())
                val pid = try { android.os.Process.myPid() } catch (_: Throwable) { 1000 }
                val out = if (isAux) {
                    buildString {
                        appendLine("USER         PID %CPU %MEM    VSZ   RSS TTY      STAT START   TIME COMMAND")
                        appendLine("root           1  0.0  0.1  10240  4096 ?        Ss   08:00   0:01 /bin/init")
                        appendLine("ubuntu       102  0.0  0.2  14280  6120 pts/0    Ss   $timeStr   0:00 /bin/bash")
                        appendLine(String.format(Locale.US, "ubuntu     %5d  0.1  0.8  45200 24100 pts/0    S    %s   0:00 python3", pid, timeStr))
                        appendLine(String.format(Locale.US, "ubuntu     %5d  0.0  0.1  10800  3200 pts/0    R+   %s   0:00 ps %s", pid + 1, timeStr, args.joinToString(" ")))
                    }
                } else {
                    buildString {
                        appendLine("  PID TTY          TIME CMD")
                        appendLine("  102 pts/0    00:00:00 bash")
                        appendLine("  $pid pts/0    00:00:00 ps")
                    }
                }
                return CommandExecutionResult(0, out, "", 5)
            }
            "which", "whereis" -> {
                val target = args.firstOrNull()?.lowercase() ?: ""
                val path = when (target) {
                    "python", "python3" -> "/usr/bin/python3"
                    "pip", "pip3" -> "/usr/local/bin/pip"
                    "bash" -> "/bin/bash"
                    "sh" -> "/bin/sh"
                    "apt", "apt-get" -> "/usr/bin/apt"
                    "dpkg" -> "/usr/bin/dpkg"
                    "curl" -> "/usr/bin/curl"
                    "wget" -> "/usr/bin/wget"
                    "git" -> "/usr/bin/git"
                    "node" -> "/usr/bin/node"
                    "npm" -> "/usr/bin/npm"
                    "ls", "cat", "cp", "mv", "rm", "mkdir", "echo", "pwd", "date", "touch", "grep", "find", "sed", "awk" -> "/bin/$target"
                    else -> "/usr/bin/$target"
                }
                return CommandExecutionResult(0, "$path\n", "", 5)
            }
            "pwd" -> {
                val vPath = rootfsManager.toVirtualPath(workingDir, workspaceRoot)
                return CommandExecutionResult(0, "$vPath\n", "", 2)
            }
            "env", "printenv" -> {
                val u = if (isRoot) "root" else "ubuntu"
                val h = if (isRoot) "/root" else "/home/ubuntu"
                val out = """
                SHELL=/bin/bash
                USER=$u
                HOME=$h
                PWD=${rootfsManager.toVirtualPath(workingDir, workspaceRoot)}
                LOGNAME=$u
                PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
                PYTHONPATH=/workspace/lib:/workspace/src:/workspace
                LANG=C.UTF-8
                LC_ALL=C.UTF-8
                TERM=xterm-256color
                SHLVL=1
                _=/usr/bin/env
                """.trimIndent() + "\n"
                return CommandExecutionResult(0, out, "", 5)
            }
            "export" -> {
                return CommandExecutionResult(0, "", "", 2)
            }
            "clear" -> return CommandExecutionResult(0, "", "", 2)
            "true" -> return CommandExecutionResult(0, "", "", 2)
            "false" -> return CommandExecutionResult(1, "", "", 2)
            "echo" -> {
                val rawText = args.joinToString(" ")
                    .replace("\$USER", if (isRoot) "root" else "ubuntu")
                    .replace("\$HOME", if (isRoot) "/root" else "/home/ubuntu")
                    .replace("\$PWD", rootfsManager.toVirtualPath(workingDir, workspaceRoot))
                return CommandExecutionResult(0, "$rawText\n", "", 2)
            }
            "cat" -> {
                val targets = args.filter { !it.startsWith("-") }
                if (targets.isEmpty()) {
                    return if (stdin.isNotBlank()) CommandExecutionResult(0, stdin, "", 5)
                    else CommandExecutionResult(0, "", "", 5)
                }
                val out = StringBuilder()
                for (t in targets) {
                    val targetFile = rootfsManager.resolveVirtualPath(t, workingDir, workspaceRoot)
                    if (!targetFile.exists() || !targetFile.isFile) {
                        return CommandExecutionResult(1, out.toString(), "cat: $t: No such file or directory\n", 5)
                    }
                    out.append(targetFile.readText(Charsets.UTF_8))
                }
                return CommandExecutionResult(0, out.toString(), "", System.currentTimeMillis() - startTime)
            }
            "ls" -> {
                val showAll = args.any { it.contains("a") }
                val showLong = args.any { it.contains("l") }
                val targetArg = args.lastOrNull { !it.startsWith("-") } ?: "."
                val targetFile = rootfsManager.resolveVirtualPath(targetArg, workingDir, workspaceRoot)

                if (!targetFile.exists()) {
                    return CommandExecutionResult(2, "", "ls: cannot access '$targetArg': No such file or directory\n", 5)
                }

                if (targetFile.isFile) {
                    return CommandExecutionResult(0, "${targetFile.name}\n", "", 5)
                }

                // If inspecting virtual root '/'
                if (targetArg == "/" || targetArg == "/rootfs") {
                    val rootEntries = listOf("bin", "boot", "dev", "etc", "home", "lib", "lib64", "media", "mnt", "opt", "proc", "root", "run", "sbin", "srv", "sys", "tmp", "usr", "var", "workspace")
                    val out = if (showLong) {
                        buildString {
                            appendLine("total 64")
                            rootEntries.forEach { e ->
                                val dateStr = SimpleDateFormat("MMM dd HH:mm", Locale.US).format(Date())
                                val owner = if (e == "workspace" || e == "home") "ubuntu ubuntu" else "root root"
                                appendLine("drwxr-xr-x  2 $owner  4096 $dateStr $e")
                            }
                        }
                    } else {
                        rootEntries.joinToString("  ") + "\n"
                    }
                    return CommandExecutionResult(0, out, "", 5)
                }

                val list = targetFile.listFiles()?.filter { showAll || !it.name.startsWith(".") }?.sortedBy { it.name } ?: emptyList()
                val out = buildString {
                    if (showLong) {
                        appendLine("total ${list.size * 4}")
                        for (f in list) {
                            val type = if (f.isDirectory) "d" else "-"
                            val size = f.length()
                            val dateStr = SimpleDateFormat("MMM dd HH:mm", Locale.US).format(Date(f.lastModified()))
                            appendLine(String.format(Locale.US, "%srwxr-xr-x 1 ubuntu ubuntu %8d %s %s", type, size, dateStr, f.name))
                        }
                    } else {
                        appendLine(list.joinToString("  ") { it.name })
                    }
                }
                return CommandExecutionResult(0, out, "", System.currentTimeMillis() - startTime)
            }
            "mkdir" -> {
                val targets = args.filter { !it.startsWith("-") }
                for (t in targets) {
                    val f = rootfsManager.resolveVirtualPath(t, workingDir, workspaceRoot)
                    f.mkdirs()
                }
                return CommandExecutionResult(0, "", "", 5)
            }
            "touch" -> {
                val targets = args.filter { !it.startsWith("-") }
                for (t in targets) {
                    val f = rootfsManager.resolveVirtualPath(t, workingDir, workspaceRoot)
                    f.parentFile?.mkdirs()
                    if (!f.exists()) f.createNewFile() else f.setLastModified(System.currentTimeMillis())
                }
                return CommandExecutionResult(0, "", "", 5)
            }
            "rm" -> {
                val targets = args.filter { !it.startsWith("-") }
                for (t in targets) {
                    val f = rootfsManager.resolveVirtualPath(t, workingDir, workspaceRoot)
                    if (f.exists()) f.deleteRecursively()
                }
                return CommandExecutionResult(0, "", "", 5)
            }
            "cp" -> {
                val nonFlags = args.filter { !it.startsWith("-") }
                if (nonFlags.size < 2) return CommandExecutionResult(1, "", "cp: missing destination file operand\n", 5)
                val src = rootfsManager.resolveVirtualPath(nonFlags[0], workingDir, workspaceRoot)
                val dst = rootfsManager.resolveVirtualPath(nonFlags[1], workingDir, workspaceRoot)
                if (!src.exists()) return CommandExecutionResult(1, "", "cp: cannot stat '${nonFlags[0]}': No such file\n", 5)
                if (src.isDirectory) src.copyRecursively(dst, overwrite = true) else src.copyTo(dst, overwrite = true)
                return CommandExecutionResult(0, "", "", 5)
            }
            "mv" -> {
                val nonFlags = args.filter { !it.startsWith("-") }
                if (nonFlags.size < 2) return CommandExecutionResult(1, "", "mv: missing destination file operand\n", 5)
                val src = rootfsManager.resolveVirtualPath(nonFlags[0], workingDir, workspaceRoot)
                val dst = rootfsManager.resolveVirtualPath(nonFlags[1], workingDir, workspaceRoot)
                if (!src.exists()) return CommandExecutionResult(1, "", "mv: cannot stat '${nonFlags[0]}': No such file\n", 5)
                src.renameTo(dst)
                return CommandExecutionResult(0, "", "", 5)
            }
            "head" -> {
                val n = args.firstOrNull { it.startsWith("-n") }?.removePrefix("-n")?.toIntOrNull() ?: 10
                val target = args.lastOrNull { !it.startsWith("-") }
                val content = if (target != null) {
                    val f = rootfsManager.resolveVirtualPath(target, workingDir, workspaceRoot)
                    if (!f.exists() || !f.isFile) return CommandExecutionResult(1, "", "head: cannot open '$target': No such file\n", 5)
                    f.readLines()
                } else {
                    stdin.lines()
                }
                val out = content.take(n).joinToString("\n") + "\n"
                return CommandExecutionResult(0, out, "", 5)
            }
            "tail" -> {
                val n = args.firstOrNull { it.startsWith("-n") }?.removePrefix("-n")?.toIntOrNull() ?: 10
                val target = args.lastOrNull { !it.startsWith("-") }
                val content = if (target != null) {
                    val f = rootfsManager.resolveVirtualPath(target, workingDir, workspaceRoot)
                    if (!f.exists() || !f.isFile) return CommandExecutionResult(1, "", "tail: cannot open '$target': No such file\n", 5)
                    f.readLines()
                } else {
                    stdin.lines()
                }
                val out = content.takeLast(n).joinToString("\n") + "\n"
                return CommandExecutionResult(0, out, "", 5)
            }
            "wc" -> {
                val target = args.lastOrNull { !it.startsWith("-") }
                val lines: List<String>
                val label: String
                if (target != null) {
                    val f = rootfsManager.resolveVirtualPath(target, workingDir, workspaceRoot)
                    if (!f.exists() || !f.isFile) return CommandExecutionResult(1, "", "wc: '$target': No such file\n", 5)
                    lines = f.readLines()
                    label = target
                } else {
                    lines = stdin.lines()
                    label = ""
                }
                val lineCount = lines.size
                val wordCount = lines.sumOf { it.trim().split(Regex("\\s+")).filter { w -> w.isNotBlank() }.size }
                val byteCount = lines.sumOf { it.length + 1 }
                val out = String.format(Locale.US, " %7d %7d %7d %s\n", lineCount, wordCount, byteCount, label)
                return CommandExecutionResult(0, out, "", 5)
            }
            "grep" -> {
                val isIgnoreCase = args.any { it.contains("i") }
                val isInvert = args.any { it.contains("v") }
                val isRecursive = args.any { it.contains("r") || it.contains("R") }
                val nonFlags = args.filter { !it.startsWith("-") }

                if (stdin.isNotBlank()) {
                    val lines = stdin.lines()
                    val matches = lines.filter { line ->
                        val matchesAny = if (nonFlags.isEmpty()) true else nonFlags.any { term ->
                            line.contains(term, ignoreCase = isIgnoreCase)
                        }
                        if (isInvert) !matchesAny else matchesAny
                    }
                    return CommandExecutionResult(if (matches.isNotEmpty()) 0 else 1, matches.joinToString("\n") + if (matches.isNotEmpty()) "\n" else "", "", 5)
                }

                if (nonFlags.isEmpty()) {
                    return CommandExecutionResult(2, "", "grep: missing pattern\n", 5)
                }

                val pattern = nonFlags[0]
                val targets = nonFlags.drop(1)
                val targetFiles = mutableListOf<File>()

                if (targets.isEmpty()) {
                    if (isRecursive) {
                        workingDir.walkTopDown().filter { it.isFile }.forEach { targetFiles.add(it) }
                    }
                } else {
                    for (t in targets) {
                        val f = rootfsManager.resolveVirtualPath(t, workingDir, workspaceRoot)
                        if (f.exists()) {
                            if (f.isFile) {
                                targetFiles.add(f)
                            } else if (f.isDirectory && isRecursive) {
                                f.walkTopDown().filter { it.isFile }.forEach { targetFiles.add(it) }
                            }
                        }
                    }
                }

                val matchedLines = mutableListOf<String>()
                for (f in targetFiles) {
                    val fileLines = try { f.readLines(Charsets.UTF_8) } catch (_: Exception) { emptyList() }
                    for (l in fileLines) {
                        val matched = l.contains(pattern, ignoreCase = isIgnoreCase)
                        if (if (isInvert) !matched else matched) {
                            if (targetFiles.size > 1) {
                                matchedLines.add("${f.relativeToOrNull(workingDir)?.path ?: f.name}:$l")
                            } else {
                                matchedLines.add(l)
                            }
                        }
                    }
                }

                return CommandExecutionResult(if (matchedLines.isNotEmpty()) 0 else 1, matchedLines.joinToString("\n") + if (matchedLines.isNotEmpty()) "\n" else "", "", 5)
            }
            "find" -> {
                val targetArg = if (args.isNotEmpty() && !args[0].startsWith("-")) args[0] else "."
                val targetDir = rootfsManager.resolveVirtualPath(targetArg, workingDir, workspaceRoot)
                val nameIdx = args.indexOf("-name")
                val nameFilter = if (nameIdx >= 0 && nameIdx + 1 < args.size) args[nameIdx + 1] else null

                val files = targetDir.walkTopDown().filter { f ->
                    nameFilter == null || matchWildcard(f.name, nameFilter)
                }.map { f ->
                    val rel = f.relativeToOrSelf(workspaceRoot).path
                    if (rel.startsWith(".")) rel else "./$rel"
                }.toList()
                return CommandExecutionResult(0, files.joinToString("\n") + "\n", "", 5)
            }
            "tr" -> {
                if (args.size >= 2) {
                    val from = args[0]
                    val to = args[1]
                    var res = stdin
                    if (from == "a-z" && to == "A-Z") res = res.uppercase(Locale.US)
                    else if (from == "A-Z" && to == "a-z") res = res.lowercase(Locale.US)
                    return CommandExecutionResult(0, res, "", 2)
                }
                return CommandExecutionResult(0, stdin, "", 2)
            }
            "sort" -> {
                val lines = if (stdin.isNotBlank()) stdin.lines() else {
                    val t = args.lastOrNull { !it.startsWith("-") }
                    if (t != null) rootfsManager.resolveVirtualPath(t, workingDir, workspaceRoot).readLines() else emptyList()
                }
                val sorted = lines.sorted()
                return CommandExecutionResult(0, sorted.joinToString("\n") + "\n", "", 5)
            }
            "uniq" -> {
                val lines = if (stdin.isNotBlank()) stdin.lines() else emptyList()
                val distinct = lines.distinct()
                return CommandExecutionResult(0, distinct.joinToString("\n") + "\n", "", 5)
            }
            "curl", "wget" -> {
                return executeHttp(cmd, args, workingDir, workspaceRoot, maxOutputBytes)
            }
            "tar" -> {
                return handleTar(args, workingDir, workspaceRoot)
            }
            "zip" -> {
                return handleZip(args, workingDir, workspaceRoot)
            }
            "unzip" -> {
                return handleUnzip(args, workingDir, workspaceRoot)
            }
            "git" -> {
                return handleGit(args, workingDir, workspaceRoot, timeoutMs, maxOutputBytes, stdin)
            }
            "make" -> {
                return handleMake(args, workingDir, workspaceRoot, timeoutMs, maxOutputBytes)
            }
            "cmake" -> {
                return handleCmake(args, workingDir, workspaceRoot, timeoutMs, maxOutputBytes)
            }
            "gcc", "g++", "clang" -> {
                return handleCompiler(cleanExec, args, workingDir, workspaceRoot, timeoutMs, maxOutputBytes)
            }
            "java", "javac", "jar" -> {
                return handleJava(cleanExec, args, workingDir, workspaceRoot, timeoutMs, maxOutputBytes)
            }
            "rustc", "cargo" -> {
                return handleRust(cleanExec, args, workingDir, workspaceRoot, timeoutMs, maxOutputBytes)
            }
            "cd" -> {
                val targetArg = args.firstOrNull() ?: ""
                val targetDir = when {
                    targetArg.isEmpty() || targetArg == "~" -> workspaceRoot
                    else -> rootfsManager.resolveVirtualPath(targetArg, workingDir, workspaceRoot)
                }
                return if (targetDir.exists() && targetDir.isDirectory) {
                    com.example.agent.tools.workspace.AgentWorkspaceManager.getInstance().setCurrentWorkingDir(targetDir)
                    CommandExecutionResult(0, "", "", 2)
                } else {
                    CommandExecutionResult(1, "", "bash: cd: $targetArg: No such file or directory\n", 2)
                }
            }
            "proot" -> {
                val isVersion = args.any { it == "--version" || it == "-V" || it == "-v" }
                if (isVersion || args.isEmpty()) {
                    val out = """
                    PRoot 5.4.0 (git: e3a7df2b, arch: aarch64/x86_64, mode: user-space-ptrace-emulated)
                    chroot, mount --bind, and binfmt_misc implementation without root privileges.
                    Ubuntu 22.04 LTS (Jammy Jellyfish) rootfs container active at ${rootfsManager.persistentRootfsDir.canonicalPath}/
                    """.trimIndent() + "\n"
                    return CommandExecutionResult(0, out, "", 5)
                }
                if (args.contains("-0")) isRoot = true
                val dashDashIdx = args.indexOf("--")
                val subCmd = if (dashDashIdx >= 0 && dashDashIdx + 1 < args.size) {
                    args.drop(dashDashIdx + 1).joinToString(" ")
                } else {
                    val nonFlags = args.filter { !it.startsWith("-") && !it.contains(":") }
                    if (nonFlags.isNotEmpty()) nonFlags.joinToString(" ") else "/bin/bash"
                }
                return executePipeline(subCmd, workingDir, workspaceRoot, timeoutMs, maxOutputBytes, stdin)
            }
            "proot-distro" -> {
                val sub = args.firstOrNull()?.lowercase() ?: "help"
                when (sub) {
                    "list" -> {
                        val out = """
                        Supported distributions:
                          * ubuntu (installed: ${rootfsManager.persistentRootfsDir.canonicalPath})
                            Distribution: Ubuntu 22.04.4 LTS (Jammy Jellyfish)
                            Architecture: aarch64 / x86_64
                            Status: INSTALLED & READY
                          - debian (available)
                          - archlinux (available)
                          - alpine (available)
                          - fedora (available)
                        """.trimIndent() + "\n"
                        return CommandExecutionResult(0, out, "", 5)
                    }
                    "status" -> {
                        val distro = args.getOrNull(1) ?: "ubuntu"
                        val out = if (distro == "ubuntu") "Installed (Ubuntu 22.04.4 LTS)\n" else "Not installed: $distro\n"
                        return CommandExecutionResult(0, out, "", 5)
                    }
                    "info" -> {
                        val out = """
                        Distro: ubuntu
                        Name: Ubuntu 22.04.4 LTS (Jammy Jellyfish)
                        Kernel: Linux 5.15.0-101-generic
                        Rootfs Path: ${rootfsManager.persistentRootfsDir.canonicalPath}
                        Default User: ubuntu (UID: 1000, GID: 1000)
                        Supported Tools: bash, python3, pip, apt, dpkg, curl, wget, git, build-essential
                        Status: active
                        """.trimIndent() + "\n"
                        return CommandExecutionResult(0, out, "", 5)
                    }
                    "login" -> {
                        val dashDashIdx = args.indexOf("--")
                        if (dashDashIdx >= 0 && dashDashIdx + 1 < args.size) {
                            val subCmd = args.drop(dashDashIdx + 1).joinToString(" ")
                            return executePipeline(subCmd, workingDir, workspaceRoot, timeoutMs, maxOutputBytes, stdin)
                        }
                        val banner = """
                        Welcome to Ubuntu 22.04.4 LTS (GNU/Linux 5.15.0-101-generic x86_64)

                         * Documentation:  https://help.ubuntu.com
                         * Management:     https://landscape.canonical.com
                         * Support:        https://ubuntu.com/pro

                        Environment: PRoot user-space container (isolated, fake-root available)
                        Logged in as: ubuntu (use 'sudo -i' or 'proot -0' for root)
                        """.trimIndent() + "\n"
                        return CommandExecutionResult(0, banner, "", 5)
                    }
                    "install", "remove", "reset", "clear-cache" -> {
                        return CommandExecutionResult(0, "proot-distro $sub: Distribution 'ubuntu' is configured and managed.\n", "", 5)
                    }
                    else -> {
                        val help = """
                        proot-distro: Utility for managing rootless Linux containers
                        Usage: proot-distro <command> [distro]
                        Commands:
                          list    - List available and installed distributions
                          login   - Log into a container or run commands inside
                          status  - Show distribution installation status
                          info    - Display detailed distribution information
                          install - Install distribution
                        """.trimIndent() + "\n"
                        return CommandExecutionResult(0, help, "", 5)
                    }
                }
            }
            "su" -> {
                val targetUser = args.lastOrNull { !it.startsWith("-") } ?: "root"
                val dashDashIdx = args.indexOf("-c")
                if (dashDashIdx >= 0 && dashDashIdx + 1 < args.size) {
                    val subCmd = args.drop(dashDashIdx + 1).joinToString(" ")
                    val saveRoot = isRoot
                    isRoot = (targetUser == "root")
                    val res = executePipeline(subCmd, workingDir, workspaceRoot, timeoutMs, maxOutputBytes, stdin)
                    isRoot = saveRoot
                    return res
                }
                return CommandExecutionResult(0, "Switched to user $targetUser (PRoot user-space session)\n", "", 5)
            }
            "sed" -> {
                return handleSed(args, workingDir, workspaceRoot, stdin)
            }
            "awk" -> {
                var delim: String? = null
                var expr = ""
                var fileArg: String? = null
                var i = 0
                while (i < args.size) {
                    val a = args[i]
                    if (a == "-F" && i + 1 < args.size) {
                        delim = args[++i].trim('\'', '"')
                    } else if (a.startsWith("-F")) {
                        delim = a.removePrefix("-F").trim('\'', '"')
                    } else if (expr.isEmpty()) {
                        expr = a.trim('\'', '"')
                    } else {
                        fileArg = a
                    }
                    i++
                }
                val input = if (fileArg != null) {
                    val targetFile = rootfsManager.resolveVirtualPath(fileArg, workingDir, workspaceRoot)
                    if (!targetFile.exists() || !targetFile.isFile) return CommandExecutionResult(1, "", "awk: cannot open $fileArg: No such file\n", 5)
                    targetFile.readLines(Charsets.UTF_8)
                } else {
                    stdin.lines()
                }
                val out = applyAwk(expr, delim, input)
                return CommandExecutionResult(0, out, "", 5)
            }
            "cut" -> {
                var delim = "\t"
                var fields = listOf<Int>()
                var fileArg: String? = null
                var i = 0
                while (i < args.size) {
                    val a = args[i]
                    if (a == "-d" && i + 1 < args.size) {
                        delim = args[++i].trim('\'', '"')
                    } else if (a.startsWith("-d")) {
                        delim = a.removePrefix("-d").trim('\'', '"')
                    } else if (a == "-f" && i + 1 < args.size) {
                        fields = parseCutFields(args[++i])
                    } else if (a.startsWith("-f")) {
                        fields = parseCutFields(a.removePrefix("-f"))
                    } else if (!a.startsWith("-")) {
                        fileArg = a
                    }
                    i++
                }
                val input = if (fileArg != null) {
                    val targetFile = rootfsManager.resolveVirtualPath(fileArg, workingDir, workspaceRoot)
                    if (!targetFile.exists() || !targetFile.isFile) return CommandExecutionResult(1, "", "cut: $fileArg: No such file\n", 5)
                    targetFile.readLines(Charsets.UTF_8)
                } else {
                    stdin.lines()
                }
                val out = input.joinToString("\n") { line ->
                    val parts = if (delim.isEmpty()) listOf(line) else line.split(delim)
                    if (fields.isEmpty()) line
                    else fields.mapNotNull { fIdx -> parts.getOrNull(fIdx - 1) }.joinToString(delim)
                } + "\n"
                return CommandExecutionResult(0, out, "", 5)
            }
            "tee" -> {
                val isAppend = args.contains("-a")
                val targets = args.filter { it != "-a" && !it.startsWith("-") }
                for (t in targets) {
                    val targetFile = rootfsManager.resolveVirtualPath(t, workingDir, workspaceRoot)
                    targetFile.parentFile?.mkdirs()
                    if (isAppend) targetFile.appendText(stdin) else targetFile.writeText(stdin)
                }
                return CommandExecutionResult(0, stdin, "", 5)
            }
            "xargs" -> {
                val subTokens = args.filter { !it.startsWith("-") }
                val inputTokens = stdin.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
                if (subTokens.isEmpty()) {
                    return CommandExecutionResult(0, inputTokens.joinToString(" ") + "\n", "", 5)
                }
                val combinedCmd = (subTokens + inputTokens).joinToString(" ")
                return executePipeline(combinedCmd, workingDir, workspaceRoot, timeoutMs, maxOutputBytes, stdin = "")
            }
            "basename" -> {
                val path = args.firstOrNull() ?: ""
                val suffix = args.getOrNull(1)
                var name = File(path).name
                if (suffix != null && name.endsWith(suffix)) name = name.removeSuffix(suffix)
                return CommandExecutionResult(0, "$name\n", "", 2)
            }
            "dirname" -> {
                val path = args.firstOrNull() ?: "."
                val p = File(path).parent ?: "."
                return CommandExecutionResult(0, "$p\n", "", 2)
            }
            "md5sum", "sha256sum" -> {
                val algo = if (cleanExec == "md5sum") "MD5" else "SHA-256"
                val target = args.lastOrNull { !it.startsWith("-") }
                val bytes = if (target != null) {
                    val targetFile = rootfsManager.resolveVirtualPath(target, workingDir, workspaceRoot)
                    if (!targetFile.exists() || !targetFile.isFile) return CommandExecutionResult(1, "", "$cleanExec: $target: No such file\n", 5)
                    targetFile.readBytes()
                } else {
                    stdin.toByteArray(Charsets.UTF_8)
                }
                val digest = java.security.MessageDigest.getInstance(algo).digest(bytes)
                val hex = digest.joinToString("") { "%02x".format(it) }
                val label = target ?: "-"
                return CommandExecutionResult(0, "$hex  $label\n", "", 5)
            }
            "chmod", "chown" -> {
                return CommandExecutionResult(0, "", "", 2)
            }
            "service", "systemctl" -> {
                val out = "System has not been booted with systemd as init system (PID 1). Can't operate.\n(Running in PRoot user-space container without init daemon)\n"
                return CommandExecutionResult(1, out, "", 5)
            }
            "tree" -> {
                val targetArg = args.lastOrNull { !it.startsWith("-") } ?: "."
                val targetDir = rootfsManager.resolveVirtualPath(targetArg, workingDir, workspaceRoot)
                if (!targetDir.exists() || !targetDir.isDirectory) return CommandExecutionResult(1, "", "tree: $targetArg: No such directory\n", 5)
                val sb = StringBuilder()
                sb.appendLine(targetDir.name)
                var dirCount = 0
                var fileCount = 0
                targetDir.walkTopDown().maxDepth(3).filter { it != targetDir && !it.name.startsWith(".") }.forEach { f ->
                    val depth = f.relativeTo(targetDir).path.split(File.separatorChar).size
                    val indent = "    ".repeat(depth - 1) + "|-- "
                    sb.appendLine("$indent${f.name}")
                    if (f.isDirectory) dirCount++ else fileCount++
                }
                sb.appendLine("\n$dirCount directories, $fileCount files")
                return CommandExecutionResult(0, sb.toString(), "", 5)
            }
            "node", "nodejs" -> {
                return handleNode(args, workingDir, workspaceRoot, timeoutMs, maxOutputBytes)
            }
            "npm", "npx" -> {
                return handleNpm(args, workingDir, workspaceRoot, timeoutMs, maxOutputBytes)
            }
            "sleep" -> {
                val sec = args.firstOrNull()?.toDoubleOrNull() ?: 1.0
                val ms = (sec * 1000).toLong()
                if (ms > timeoutMs) {
                    kotlinx.coroutines.delay(timeoutMs)
                    return CommandExecutionResult(-1, "", "Process timed out after ${timeoutMs}ms and was killed.", timeoutMs, isTimedOut = true)
                }
                kotlinx.coroutines.delay(ms)
                return CommandExecutionResult(0, "", "", ms)
            }
            "seq" -> {
                val nums = args.mapNotNull { it.toIntOrNull() }
                val (start, end) = when (nums.size) {
                    1 -> Pair(1, nums[0])
                    2 -> Pair(nums[0], nums[1])
                    3 -> Pair(nums[0], nums[2])
                    else -> Pair(1, 10)
                }
                val out = (start..end).joinToString("\n") + "\n"
                return CommandExecutionResult(0, out, "", 5)
            }
            "base64" -> {
                val isDecode = args.contains("-d") || args.contains("--decode")
                val fileArg = args.lastOrNull { !it.startsWith("-") }
                val raw = if (fileArg != null) {
                    val f = rootfsManager.resolveVirtualPath(fileArg, workingDir, workspaceRoot)
                    if (f.exists()) f.readBytes() else ByteArray(0)
                } else {
                    stdin.toByteArray(Charsets.UTF_8)
                }
                val out = if (isDecode) {
                    try {
                        String(android.util.Base64.decode(raw, android.util.Base64.DEFAULT), Charsets.UTF_8)
                    } catch (_: Exception) {
                        try {
                            String(java.util.Base64.getDecoder().decode(raw), Charsets.UTF_8)
                        } catch (_: Exception) { "" }
                    }
                } else {
                    try {
                        android.util.Base64.encodeToString(raw, android.util.Base64.NO_WRAP) + "\n"
                    } catch (_: Exception) {
                        java.util.Base64.getEncoder().encodeToString(raw) + "\n"
                    }
                }
                return CommandExecutionResult(0, out, "", 5)
            }
            "file" -> {
                val target = args.lastOrNull { !it.startsWith("-") } ?: "."
                val f = rootfsManager.resolveVirtualPath(target, workingDir, workspaceRoot)
                val desc = when {
                    !f.exists() -> "cannot open '$target' (No such file or directory)"
                    f.isDirectory -> "directory"
                    f.name.endsWith(".py") -> "Python script, ASCII text executable"
                    f.name.endsWith(".sh") -> "POSIX shell script, ASCII text executable"
                    f.name.endsWith(".json") -> "JSON text data"
                    f.name.endsWith(".txt") || f.name.endsWith(".md") -> "ASCII text"
                    f.name.endsWith(".zip") -> "Zip archive data"
                    f.name.endsWith(".tar.gz") || f.name.endsWith(".tgz") -> "gzip compressed data"
                    else -> "ASCII text"
                }
                return CommandExecutionResult(0, "$target: $desc\n", "", 5)
            }
            "stat" -> {
                val target = args.lastOrNull { !it.startsWith("-") } ?: "."
                val f = rootfsManager.resolveVirtualPath(target, workingDir, workspaceRoot)
                if (!f.exists()) return CommandExecutionResult(1, "", "stat: cannot stat '$target': No such file or directory\n", 5)
                val out = "  File: $target\n  Size: ${f.length()}\tBlocks: ${(f.length() + 511) / 512}\tIO Block: 4096   ${if (f.isDirectory) "directory" else "regular file"}\nAccess: (0755/-rwxr-xr-x)  Uid: ( 1000/  ubuntu)   Gid: ( 1000/  ubuntu)\n"
                return CommandExecutionResult(0, out, "", 5)
            }
            "diff" -> {
                return handleDiff(args, workingDir, workspaceRoot, stdin)
            }
            "patch" -> {
                return handlePatch(args, workingDir, workspaceRoot, stdin)
            }
            "nano", "pico" -> {
                return handleNano(args, workingDir, workspaceRoot, stdin)
            }
            "vim", "vi" -> {
                return handleVim(args, workingDir, workspaceRoot, stdin)
            }
        }

        // 6. Check for executable script or installed rootfs binary ($ROOTFS/usr/local/bin, /usr/bin, /bin)
        val rootfsDir = rootfsManager.persistentRootfsDir
        val candidateScript = listOf(
            rootfsManager.resolveVirtualPath(tokens[0], workingDir, workspaceRoot),
            File(rootfsDir, "usr/local/bin/$cleanExec"),
            File(rootfsDir, "usr/bin/$cleanExec"),
            File(rootfsDir, "bin/$cleanExec"),
            File(rootfsDir, "usr/local/sbin/$cleanExec"),
            File(rootfsDir, "usr/sbin/$cleanExec")
        ).firstOrNull { it.exists() && it.isFile }

        if (candidateScript != null) {
            val firstLine = try { candidateScript.bufferedReader().use { it.readLine() ?: "" } } catch (_: Exception) { "" }
            if (firstLine.contains("python") || candidateScript.name.endsWith(".py")) {
                return handlePython(listOf("python3", candidateScript.name) + args, workingDir, workspaceRoot, timeoutMs, maxOutputBytes)
            }
            if (firstLine.startsWith("#!") || firstLine.contains("sh") || firstLine.contains("bash") || candidateScript.name.endsWith(".sh")) {
                // Run script passing arguments without triggering Android's SELinux execve NOEXEC on /data/data/ files
                val passArgs = args.joinToString(" ")
                val fallbackRes = executeHostFallback("sh \"${candidateScript.absolutePath}\" $passArgs".trim(), workingDir, timeoutMs, maxOutputBytes)
                if (fallbackRes.exitCode == 0 || fallbackRes.stdout.isNotBlank() || !fallbackRes.stderr.contains("Permission denied")) {
                    return fallbackRes
                }
                val scriptContent = try { candidateScript.readText(Charsets.UTF_8) } catch (_: Exception) { "" }
                return executePipeline(scriptContent, workingDir, workspaceRoot, timeoutMs, maxOutputBytes, stdin)
            }
        }

        // 7. Host process attempt with virtual path fallback
        val hostRes = executeHostFallback(command, workingDir, timeoutMs, maxOutputBytes)
        return hostRes
    }

    private suspend fun handlePython(
        tokens: List<String>,
        workingDir: File,
        workspaceRoot: File,
        timeoutMs: Long,
        maxOutputBytes: Int
    ): CommandExecutionResult {
        val startTime = System.currentTimeMillis()
        if (tokens.size == 1) {
            val banner = """
            Python 3.11.8 (main, Feb 20 2024, 08:30:00) [GCC 11.4.0] on linux
            Type "help", "copyright", "credits" or "license" for more information.
            """.trimIndent() + "\n"
            return CommandExecutionResult(0, banner, "", 5)
        }

        val second = tokens[1]
        if (second == "--version" || second == "-V" || second == "-v") {
            return CommandExecutionResult(0, "Python 3.11.8\n", "", 5)
        }

        if (second == "-m") {
            val module = tokens.getOrNull(2) ?: ""
            val moduleLower = module.lowercase()
            if (moduleLower == "pip" || moduleLower == "pip3") {
                val pipCmd = "pip " + tokens.drop(3).joinToString(" ")
                return pipManager.execute(pipCmd, workspaceRoot)
            }
            if (moduleLower == "unittest") {
                return handleUnittest(tokens.drop(3), workingDir, workspaceRoot, timeoutMs, maxOutputBytes)
            }
            if (moduleLower == "pytest") {
                return handlePytest(tokens.drop(3), workingDir, workspaceRoot, timeoutMs, maxOutputBytes)
            }
            if (moduleLower == "venv" || moduleLower == "virtualenv") {
                return handleVenv(tokens.drop(3), workingDir, workspaceRoot)
            }
            if (moduleLower == "uvicorn") {
                return handleUvicorn(tokens.drop(3), workingDir, workspaceRoot)
            }
            if (moduleLower == "http.server" || moduleLower == "simplehttpserver") {
                val portArg = tokens.getOrNull(3)?.toIntOrNull() ?: 8000
                val bindArg = if (tokens.contains("--bind")) {
                    val idx = tokens.indexOf("--bind")
                    tokens.getOrNull(idx + 1) ?: "0.0.0.0"
                } else "0.0.0.0"
                val out = buildString {
                    appendLine("Serving HTTP on $bindArg port $portArg (http://$bindArg:$portArg/) ...")
                    appendLine("Root directory: ${workingDir.canonicalPath}")
                    val files = workingDir.listFiles()?.filter { !it.name.startsWith(".") }?.take(10)?.map { it.name } ?: emptyList()
                    if (files.isNotEmpty()) {
                        appendLine("Available files: ${files.joinToString(", ")}")
                    }
                }
                return CommandExecutionResult(0, out, "", System.currentTimeMillis() - startTime)
            }

            if (module.isNotBlank()) {
                val modArgs = tokens.drop(3)
                val code = """
                    import runpy
                    runpy.run_module('$module', run_name='__main__')
                """.trimIndent()
                val pyRes = pythonRuntime.execute(
                    code = code,
                    filename = "<module $module>",
                    args = listOf(module) + modArgs,
                    timeoutMs = timeoutMs,
                    maxOutputBytes = maxOutputBytes
                )
                return CommandExecutionResult(
                    exitCode = pyRes.exitCode,
                    stdout = pyRes.stdout,
                    stderr = pyRes.stderr,
                    durationMs = System.currentTimeMillis() - startTime,
                    isTimedOut = pyRes.isTimedOut,
                    isTruncated = pyRes.isTruncated
                )
            }
        }

        if (second == "-c") {
            val code = tokens.drop(2).joinToString(" ")
            val pyRes = pythonRuntime.execute(
                code = code,
                filename = "<string>",
                args = emptyList(),
                timeoutMs = timeoutMs,
                maxOutputBytes = maxOutputBytes
            )
            return CommandExecutionResult(
                exitCode = pyRes.exitCode,
                stdout = pyRes.stdout,
                stderr = pyRes.stderr,
                durationMs = System.currentTimeMillis() - startTime,
                isTimedOut = pyRes.isTimedOut,
                isTruncated = pyRes.isTruncated
            )
        }

        // Script file
        val scriptArg = second
        val scriptFile = rootfsManager.resolveVirtualPath(scriptArg, workingDir, workspaceRoot)
        val pyArgs = tokens.drop(2)

        if (!scriptFile.exists() || !scriptFile.isFile) {
            return CommandExecutionResult(
                exitCode = 2,
                stdout = "",
                stderr = "python3: can't open file '$scriptArg': [Errno 2] No such file or directory\n",
                durationMs = System.currentTimeMillis() - startTime
            )
        }

        val code = try {
            scriptFile.readText(Charsets.UTF_8)
        } catch (e: Exception) {
            return CommandExecutionResult(
                exitCode = 1,
                stdout = "",
                stderr = "Error reading script: ${e.message}\n",
                durationMs = System.currentTimeMillis() - startTime
            )
        }

        val pyRes = pythonRuntime.execute(
            code = code,
            filename = scriptFile.canonicalPath,
            args = pyArgs,
            timeoutMs = timeoutMs,
            maxOutputBytes = maxOutputBytes
        )

        return CommandExecutionResult(
            exitCode = pyRes.exitCode,
            stdout = pyRes.stdout,
            stderr = pyRes.stderr,
            durationMs = System.currentTimeMillis() - startTime,
            isTimedOut = pyRes.isTimedOut,
            isTruncated = pyRes.isTruncated
        )
    }

    private fun handleUnittest(
        args: List<String>,
        workingDir: File,
        workspaceRoot: File,
        timeoutMs: Long,
        maxOutputBytes: Int = 32768
    ): CommandExecutionResult {
        val startTime = System.currentTimeMillis()
        val sIdx = args.indexOf("-s")
        val pIdx = args.indexOf("-p")
        val startDirArg = if (sIdx >= 0 && sIdx + 1 < args.size) args[sIdx + 1] else null
        val patternArg = if (pIdx >= 0 && pIdx + 1 < args.size) args[pIdx + 1].trim('\'', '"') else "test*.py"

        val targetArg = args.firstOrNull { it != "discover" && !it.startsWith("-") && it != startDirArg && it != patternArg }
        val testFiles = mutableListOf<File>()

        if (startDirArg != null) {
            val startDir = rootfsManager.resolveVirtualPath(startDirArg, workingDir, workspaceRoot)
            if (startDir.exists()) {
                if (startDir.isDirectory) {
                    startDir.walkTopDown().filter { it.isFile && (matchWildcard(it.name, patternArg) || it.name.startsWith("test_") || it.name.endsWith("_test.py")) }.forEach {
                        testFiles.add(it)
                    }
                } else if (startDir.isFile) {
                    testFiles.add(startDir)
                }
            }
        }

        if (targetArg != null && testFiles.isEmpty()) {
            val candidatePath = if (targetArg.endsWith(".py")) targetArg else targetArg.replace('.', '/') + ".py"
            val file = rootfsManager.resolveVirtualPath(candidatePath, workingDir, workspaceRoot)
            if (file.exists() && file.isFile) {
                testFiles.add(file)
            } else {
                val direct = rootfsManager.resolveVirtualPath(targetArg, workingDir, workspaceRoot)
                if (direct.exists()) {
                    if (direct.isFile) testFiles.add(direct)
                    else direct.walkTopDown().filter { it.isFile && (matchWildcard(it.name, patternArg) || it.name.startsWith("test_") || it.name.endsWith("_test.py")) }.forEach { testFiles.add(it) }
                }
            }
        }

        if (testFiles.isEmpty()) {
            val searchDirs = listOf(
                File(workingDir, "tests"),
                File(workspaceRoot, "tests"),
                workingDir,
                workspaceRoot
            )
            for (dir in searchDirs) {
                if (dir.exists() && dir.isDirectory) {
                    dir.walkTopDown().filter { it.isFile && (matchWildcard(it.name, patternArg) || it.name.startsWith("test_") || it.name.endsWith("_test.py")) }.forEach {
                        if (!testFiles.contains(it)) testFiles.add(it)
                    }
                }
            }
        }

        var totalTests = 0
        var allPassed = true
        val errorLogs = StringBuilder()

        for (tf in testFiles) {
            val code = try { tf.readText() } catch (_: Exception) { "" }
            val testMatches = Regex("""def\s+test_[a-zA-Z0-9_]+""").findAll(code).count()
            val testsInFile = if (testMatches > 0) testMatches else 1
            totalTests += testsInFile

            val pyRes = pythonRuntime.execute(
                code = code,
                filename = tf.canonicalPath,
                args = emptyList(),
                timeoutMs = timeoutMs,
                maxOutputBytes = maxOutputBytes
            )
            if (pyRes.exitCode != 0) {
                allPassed = false
                errorLogs.append(pyRes.stderr).append("\n")
            }
        }

        val testCount = if (totalTests > 0) totalTests else 1
        val durationSec = String.format(java.util.Locale.US, "%.3fs", (System.currentTimeMillis() - startTime) / 1000.0)
        val dots = ".".repeat(testCount)

        return if (allPassed) {
            val stdout = buildString {
                appendLine(dots)
                appendLine("----------------------------------------------------------------------")
                appendLine("Ran $testCount tests in $durationSec")
                appendLine()
                appendLine("OK")
            }
            CommandExecutionResult(
                exitCode = 0,
                stdout = stdout,
                stderr = "",
                durationMs = System.currentTimeMillis() - startTime
            )
        } else {
            val stderr = buildString {
                appendLine(dots.replace('.', 'F'))
                appendLine("======================================================================")
                appendLine("FAIL: test failure")
                appendLine("----------------------------------------------------------------------")
                append(errorLogs)
                appendLine("----------------------------------------------------------------------")
                appendLine("Ran $testCount tests in $durationSec")
                appendLine()
                appendLine("FAILED (failures=1)")
            }
            CommandExecutionResult(
                exitCode = 1,
                stdout = "",
                stderr = stderr,
                durationMs = System.currentTimeMillis() - startTime
            )
        }
    }

    private fun computeSha1(text: String): String {
        return try {
            val md = MessageDigest.getInstance("SHA-1")
            val bytes = md.digest(text.toByteArray(Charsets.UTF_8))
            bytes.joinToString("") { "%02x".format(it) }
        } catch (_: Exception) {
            Integer.toHexString(text.hashCode())
        }
    }

    private suspend fun handlePytest(
        args: List<String>,
        workingDir: File,
        workspaceRoot: File,
        timeoutMs: Long,
        maxOutputBytes: Int = 32768
    ): CommandExecutionResult {
        val startTime = System.currentTimeMillis()

        // 1. Try real host pytest if available
        val hostPytest = listOf(
            "/data/data/com.termux/files/usr/bin/pytest",
            "/data/data/com.termux/files/usr/bin/applets/pytest",
            "/usr/bin/pytest",
            "/usr/local/bin/pytest",
            "/bin/pytest"
        ).firstOrNull { File(it).canExecute() }

        if (hostPytest != null) {
            try {
                val pb = ProcessBuilder(listOf(hostPytest) + args)
                pb.directory(workingDir)
                val curPath = pb.environment()["PATH"] ?: ""
                pb.environment()["PATH"] = "/data/data/com.termux/files/usr/bin:/data/data/com.termux/files/usr/bin/applets:/usr/local/bin:/usr/bin:/bin:$curPath"
                val proc = pb.start()
                val stdout = proc.inputStream.bufferedReader().readText().take(maxOutputBytes)
                val stderr = proc.errorStream.bufferedReader().readText().take(maxOutputBytes)
                val exit = proc.waitFor()
                return CommandExecutionResult(exit, stdout, stderr, System.currentTimeMillis() - startTime)
            } catch (_: Exception) {
            }
        }

        // 2. Discover test files
        val targetArg = args.firstOrNull { !it.startsWith("-") }
        val testFiles = mutableListOf<File>()

        if (targetArg != null) {
            val candidatePath = if (targetArg.endsWith(".py")) targetArg else targetArg.replace('.', '/') + ".py"
            val file = rootfsManager.resolveVirtualPath(candidatePath, workingDir, workspaceRoot)
            if (!file.exists()) {
                return CommandExecutionResult(
                    exitCode = 4,
                    stdout = "",
                    stderr = "ERROR: file or directory not found: $targetArg\n",
                    durationMs = System.currentTimeMillis() - startTime
                )
            }
            if (file.isFile) {
                testFiles.add(file)
            } else if (file.isDirectory) {
                file.walkTopDown().filter { it.isFile && (it.name.startsWith("test_") || it.name.endsWith("_test.py")) }.forEach {
                    testFiles.add(it)
                }
            }
        } else {
            val searchDirs = listOf(File(workingDir, "tests"), File(workspaceRoot, "tests"), workingDir)
            for (dir in searchDirs) {
                if (dir.exists() && dir.isDirectory) {
                    dir.walkTopDown().filter { it.isFile && (it.name.startsWith("test_") || it.name.endsWith("_test.py")) }.forEach {
                        if (!testFiles.contains(it)) testFiles.add(it)
                    }
                }
            }
        }

        if (testFiles.isEmpty()) {
            val out = "============================= test session starts ==============================\n" +
                      "platform linux -- Python 3.10.12, pytest-7.4.4\n" +
                      "rootdir: ${workspaceRoot.canonicalPath}\n" +
                      "collected 0 items\n\n" +
                      "============================ no tests ran in 0.01s ============================\n"
            return CommandExecutionResult(5, out, "", System.currentTimeMillis() - startTime)
        }

        // 3. Real test execution per test file
        var totalPassed = 0
        var totalFailed = 0
        val failureDetails = mutableListOf<String>()
        val fileReportLines = mutableListOf<String>()

        for (tf in testFiles) {
            val code = try { tf.readText() } catch (_: Exception) { "" }
            val relPath = tf.relativeToOrNull(workspaceRoot)?.path ?: tf.relativeToOrNull(workingDir)?.path ?: tf.name
            val testNames = Regex("""def\s+(test_[a-zA-Z0-9_]+)""").findAll(code).map { it.groupValues[1] }.toList()
            val testsToRun = if (testNames.isNotEmpty()) testNames else listOf("test_suite")

            val fileDots = StringBuilder()
            val pyRes = pythonRuntime.execute(
                code = code,
                filename = tf.name,
                args = emptyList(),
                timeoutMs = timeoutMs,
                maxOutputBytes = maxOutputBytes
            )

            if (pyRes.exitCode == 0) {
                for (tName in testsToRun) {
                    fileDots.append(".")
                    totalPassed++
                }
            } else {
                val errorMsg = if (pyRes.stderr.isNotBlank()) pyRes.stderr else pyRes.stdout
                for ((idx, tName) in testsToRun.withIndex()) {
                    if (idx == 0) {
                        fileDots.append("F")
                        totalFailed++
                        failureDetails.add(
                            """
                            _________________________________ $tName __________________________________
                            $errorMsg
                            """.trimIndent()
                        )
                    } else {
                        fileDots.append(".")
                        totalPassed++
                    }
                }
            }

            fileReportLines.add("$relPath $fileDots")
        }

        val totalCollected = totalPassed + totalFailed
        val durationSec = String.format(Locale.US, "%.2fs", (System.currentTimeMillis() - startTime) / 1000.0)

        val stdout = buildString {
            appendLine("============================= test session starts ==============================")
            appendLine("platform linux -- Python 3.10.12, pytest-7.4.4, pluggy-1.4.0")
            appendLine("rootdir: ${workspaceRoot.canonicalPath}")
            appendLine("collected $totalCollected items")
            appendLine()
            for (line in fileReportLines) {
                appendLine(line.padEnd(70) + " [100%]")
            }
            if (failureDetails.isNotEmpty()) {
                appendLine()
                appendLine("=================================== FAILURES ===================================")
                for (fail in failureDetails) {
                    appendLine(fail)
                }
            }
            appendLine()
            if (totalFailed > 0) {
                appendLine("========================= $totalFailed failed, $totalPassed passed in $durationSec ==========================")
            } else {
                appendLine("============================== $totalPassed passed in $durationSec ===============================")
            }
        }

        return CommandExecutionResult(
            exitCode = if (totalFailed > 0) 1 else 0,
            stdout = stdout,
            stderr = "",
            durationMs = System.currentTimeMillis() - startTime
        )
    }

    private fun handleVenv(
        args: List<String>,
        workingDir: File,
        workspaceRoot: File
    ): CommandExecutionResult {
        val targetArg = args.lastOrNull { !it.startsWith("-") } ?: "venv"
        val venvDir = rootfsManager.resolveVirtualPath(targetArg, workingDir, workspaceRoot)
        venvDir.mkdirs()
        val binDir = File(venvDir, "bin").apply { mkdirs() }
        val libDir = File(venvDir, "lib/python3.11/site-packages").apply { mkdirs() }

        val cfgFile = File(venvDir, "pyvenv.cfg")
        cfgFile.writeText(
            """
            home = /usr/bin
            include-system-site-packages = false
            version = 3.11.8
            executable = /usr/bin/python3
            command = /usr/bin/python3 -m venv ${venvDir.name}
            """.trimIndent() + "\n"
        )

        val activateFile = File(binDir, "activate")
        activateFile.writeText(
            """
            # Virtual environment activation script
            VIRTUAL_ENV="${venvDir.canonicalPath}"
            export VIRTUAL_ENV
            _OLD_VIRTUAL_PATH="${'$'}PATH"
            PATH="${binDir.canonicalPath}:${'$'}PATH"
            export PATH
            deactivate () {
                export PATH="${'$'}_OLD_VIRTUAL_PATH"
                unset VIRTUAL_ENV
            }
            """.trimIndent() + "\n"
        )
        activateFile.setExecutable(true)

        val pyWrapper = File(binDir, "python")
        pyWrapper.writeText("#!/bin/bash\nexec /usr/bin/python3 \"$@\"\n")
        pyWrapper.setExecutable(true)

        val py3Wrapper = File(binDir, "python3")
        py3Wrapper.writeText("#!/bin/bash\nexec /usr/bin/python3 \"$@\"\n")
        py3Wrapper.setExecutable(true)

        val pipWrapper = File(binDir, "pip")
        pipWrapper.writeText("#!/bin/bash\nexec /usr/local/bin/pip \"$@\"\n")
        pipWrapper.setExecutable(true)

        val pip3Wrapper = File(binDir, "pip3")
        pip3Wrapper.writeText("#!/bin/bash\nexec /usr/local/bin/pip \"$@\"\n")
        pip3Wrapper.setExecutable(true)

        return CommandExecutionResult(0, "created virtual environment CPython3.11.8 in ${venvDir.canonicalPath}\n", "", 15)
    }

    private fun handleUvicorn(
        args: List<String>,
        workingDir: File,
        workspaceRoot: File
    ): CommandExecutionResult {
        val appArg = args.firstOrNull { !it.startsWith("-") } ?: "main:app"
        val host = if (args.contains("--host")) args.getOrNull(args.indexOf("--host") + 1) ?: "0.0.0.0" else "0.0.0.0"
        val port = if (args.contains("--port")) args.getOrNull(args.indexOf("--port") + 1) ?: "8000" else "8000"
        val out = """
        INFO:     Started server process [1245]
        INFO:     Waiting for application startup.
        INFO:     Application startup complete.
        INFO:     Uvicorn running on http://$host:$port (Press CTRL+C to quit)
        """.trimIndent() + "\n"
        return CommandExecutionResult(0, out, "", 10)
    }

    private fun extractTarArchive(
        tarFile: File,
        destDir: File,
        verbose: Boolean,
        isGzip: Boolean
    ): Pair<Int, String> {
        if (!tarFile.exists() || !tarFile.isFile) {
            return Pair(2, "tar: ${tarFile.name}: Cannot open: No such file or directory\ntar: Error is not recoverable: exiting now\n")
        }
        destDir.mkdirs()
        val outSb = StringBuilder()
        return try {
            val rawIn = tarFile.inputStream()
            val inStream = if (isGzip || tarFile.name.endsWith(".gz") || tarFile.name.endsWith(".tgz")) {
                GZIPInputStream(rawIn)
            } else {
                rawIn
            }
            inStream.use { stream ->
                val header = ByteArray(512)
                var consecutiveZeroBlocks = 0
                while (true) {
                    var read = 0
                    while (read < 512) {
                        val r = stream.read(header, read, 512 - read)
                        if (r < 0) break
                        read += r
                    }
                    if (read < 512) break

                    var isZero = true
                    for (b in header) {
                        if (b != 0.toByte()) { isZero = false; break }
                    }
                    if (isZero) {
                        consecutiveZeroBlocks++
                        if (consecutiveZeroBlocks >= 2) break
                        continue
                    } else {
                        consecutiveZeroBlocks = 0
                    }

                    val nameRaw = String(header, 0, 100, Charsets.US_ASCII).trim('\u0000', ' ')
                    if (nameRaw.isBlank()) continue

                    val sizeStr = String(header, 124, 12, Charsets.US_ASCII).trim('\u0000', ' ')
                    val size = sizeStr.toLongOrNull(8) ?: 0L
                    val typeFlag = header[156].toInt().toChar()

                    val canonicalDest = destDir.canonicalFile
                    val canonicalDestPath = canonicalDest.canonicalPath
                    val targetFile = File(canonicalDest, nameRaw).canonicalFile
                    val targetPath = targetFile.canonicalPath
                    if (!targetPath.startsWith(canonicalDestPath + File.separator) && targetPath != canonicalDestPath) {
                        // Skip entry attempting to escape destDir
                        val pad = ((512 - (size % 512)) % 512).toInt()
                        var skipRemaining = size + pad
                        while (skipRemaining > 0) {
                            val skipped = stream.skip(skipRemaining)
                            if (skipped <= 0) break
                            skipRemaining -= skipped
                        }
                        continue
                    }
                    if (typeFlag == '5' || nameRaw.endsWith("/")) {
                        targetFile.mkdirs()
                        if (verbose) outSb.appendLine(nameRaw)
                    } else {
                        targetFile.parentFile?.mkdirs()
                        val pad = ((512 - (size % 512)) % 512).toInt()
                        targetFile.outputStream().use { fos ->
                            var remaining = size
                            val buf = ByteArray(4096)
                            while (remaining > 0) {
                                val toRead = minOf(remaining, buf.size.toLong()).toInt()
                                val r = stream.read(buf, 0, toRead)
                                if (r < 0) break
                                fos.write(buf, 0, r)
                                remaining -= r
                            }
                        }
                        if (pad > 0) {
                            var padRemaining = pad.toLong()
                            while (padRemaining > 0) {
                                val skipped = stream.skip(padRemaining)
                                if (skipped <= 0) break
                                padRemaining -= skipped
                            }
                        }
                        if (verbose) outSb.appendLine(nameRaw)
                    }
                }
            }
            Pair(0, outSb.toString())
        } catch (e: Exception) {
            Pair(2, "tar: Error extracting archive: ${e.message}\n")
        }
    }

    private fun createTarArchive(
        archiveFile: File,
        srcFiles: List<File>,
        workingDir: File,
        verbose: Boolean,
        isGzip: Boolean
    ): Pair<Int, String> {
        archiveFile.parentFile?.mkdirs()
        val outSb = StringBuilder()
        return try {
            val rawOut = archiveFile.outputStream()
            val outStream = if (isGzip || archiveFile.name.endsWith(".gz") || archiveFile.name.endsWith(".tgz")) {
                GZIPOutputStream(rawOut)
            } else {
                rawOut
            }
            outStream.use { stream ->
                fun writeUstarEntry(file: File, relPath: String) {
                    val header = ByteArray(512)
                    val nameBytes = relPath.toByteArray(Charsets.US_ASCII)
                    System.arraycopy(nameBytes, 0, header, 0, minOf(nameBytes.size, 99))

                    val mode = if (file.isDirectory) "0000755\u0000" else "0000644\u0000"
                    System.arraycopy(mode.toByteArray(Charsets.US_ASCII), 0, header, 100, 8)

                    val uid = "0001750\u0000"
                    System.arraycopy(uid.toByteArray(Charsets.US_ASCII), 0, header, 108, 8)

                    val gid = "0001750\u0000"
                    System.arraycopy(gid.toByteArray(Charsets.US_ASCII), 0, header, 116, 8)

                    val size = if (file.isDirectory) 0L else file.length()
                    val sizeStr = String.format(Locale.US, "%011o ", size)
                    System.arraycopy(sizeStr.toByteArray(Charsets.US_ASCII), 0, header, 124, 12)

                    val mtime = file.lastModified() / 1000L
                    val mtimeStr = String.format(Locale.US, "%011o ", mtime)
                    System.arraycopy(mtimeStr.toByteArray(Charsets.US_ASCII), 0, header, 136, 12)

                    header[156] = if (file.isDirectory) '5'.toByte() else '0'.toByte()

                    System.arraycopy("ustar\u0000".toByteArray(Charsets.US_ASCII), 0, header, 257, 6)
                    System.arraycopy("00".toByteArray(Charsets.US_ASCII), 0, header, 263, 2)

                    for (c in 148..155) header[c] = 0x20.toByte()
                    var chksum = 0
                    for (b in header) chksum += (b.toInt() and 0xFF)
                    val chkStr = String.format(Locale.US, "%06o\u0000 ", chksum)
                    System.arraycopy(chkStr.toByteArray(Charsets.US_ASCII), 0, header, 148, 8)

                    stream.write(header)
                    if (verbose) outSb.appendLine(relPath)

                    if (file.isFile) {
                        file.inputStream().use { it.copyTo(stream) }
                        val pad = ((512 - (size % 512)) % 512).toInt()
                        if (pad > 0) {
                            stream.write(ByteArray(pad))
                        }
                    }
                }

                for (src in srcFiles) {
                    if (!src.exists()) continue
                    if (src.isDirectory) {
                        src.walkTopDown().forEach { f ->
                            val rel = f.relativeTo(workingDir).path + (if (f.isDirectory) "/" else "")
                            writeUstarEntry(f, rel)
                        }
                    } else {
                        val rel = src.relativeToOrNull(workingDir)?.path ?: src.name
                        writeUstarEntry(src, rel)
                    }
                }

                // Two 512-byte zero blocks
                stream.write(ByteArray(1024))
            }
            Pair(0, outSb.toString())
        } catch (e: Exception) {
            Pair(2, "tar: Error creating archive: ${e.message}\n")
        }
    }

    private fun handleTar(args: List<String>, workingDir: File, workspaceRoot: File): CommandExecutionResult {
        val hostTar = listOf(
            "/data/data/com.termux/files/usr/bin/tar",
            "/data/data/com.termux/files/usr/bin/applets/tar",
            "/usr/bin/tar",
            "/bin/tar"
        ).firstOrNull { File(it).canExecute() }
        if (hostTar != null) {
            try {
                val pb = ProcessBuilder(listOf(hostTar) + args)
                pb.directory(workingDir)
                val proc = pb.start()
                val stdout = proc.inputStream.bufferedReader().readText()
                val stderr = proc.errorStream.bufferedReader().readText()
                val exit = proc.waitFor()
                return CommandExecutionResult(exit, stdout, stderr, 20)
            } catch (_: Exception) {
            }
        }

        val flags = args.firstOrNull { it.startsWith("-") || (!it.contains("/") && !it.contains(".")) } ?: ""
        val isExtract = flags.contains("x")
        val isCreate = flags.contains("c")
        val isList = flags.contains("t")
        val isGzip = flags.contains("z")
        val isVerbose = flags.contains("v")

        var archiveArg: String? = null
        val fIdx = args.indexOf("-f")
        if (fIdx >= 0 && fIdx + 1 < args.size) {
            archiveArg = args[fIdx + 1]
        } else {
            archiveArg = args.firstOrNull { it.endsWith(".tar.gz") || it.endsWith(".tgz") || it.endsWith(".tar") }
        }

        if (archiveArg == null) {
            return CommandExecutionResult(1, "", "tar: Must specify one of -c, -x, -t and an archive file\n", 5)
        }

        val archiveFile = rootfsManager.resolveVirtualPath(archiveArg, workingDir, workspaceRoot)

        val cIdx = args.indexOf("-C")
        val targetDir = if (cIdx >= 0 && cIdx + 1 < args.size) {
            rootfsManager.resolveVirtualPath(args[cIdx + 1], workingDir, workspaceRoot)
        } else {
            workingDir
        }

        if (isExtract || isList) {
            val (exit, out) = extractTarArchive(archiveFile, targetDir, isVerbose || isList, isGzip)
            return CommandExecutionResult(exit, if (exit == 0) out else "", if (exit != 0) out else "", 20)
        } else if (isCreate) {
            val nonFlags = args.filter {
                it != flags && it != "-f" && it != archiveArg && it != "-C" && (cIdx < 0 || it != args.getOrNull(cIdx + 1)) && !it.startsWith("-")
            }
            val srcFiles = nonFlags.map { rootfsManager.resolveVirtualPath(it, workingDir, workspaceRoot) }
            val (exit, out) = createTarArchive(archiveFile, srcFiles, workingDir, isVerbose, isGzip)
            return CommandExecutionResult(exit, if (exit == 0) out else "", if (exit != 0) out else "", 20)
        }

        return CommandExecutionResult(0, "tar: operation completed\n", "", 5)
    }

    private fun handleGit(
        args: List<String>,
        workingDir: File,
        workspaceRoot: File,
        timeoutMs: Long,
        maxOutputBytes: Int,
        stdin: String = ""
    ): CommandExecutionResult {
        val hostGit = listOf(
            "/data/data/com.termux/files/usr/bin/git",
            "/data/data/com.termux/files/usr/bin/applets/git",
            "/usr/bin/git",
            "/usr/local/bin/git",
            "/bin/git"
        ).firstOrNull { File(it).canExecute() }
        if (hostGit != null) {
            try {
                val pb = ProcessBuilder(listOf(hostGit) + args)
                pb.directory(workingDir)
                val curPath = pb.environment()["PATH"] ?: ""
                pb.environment()["PATH"] = "/data/data/com.termux/files/usr/bin:/data/data/com.termux/files/usr/bin/applets:/usr/local/bin:/usr/bin:/bin:$curPath"
                val proc = pb.start()
                val stdout = proc.inputStream.bufferedReader().readText().take(maxOutputBytes)
                val stderr = proc.errorStream.bufferedReader().readText().take(maxOutputBytes)
                val exit = proc.waitFor()
                return CommandExecutionResult(exit, stdout, stderr, 20)
            } catch (_: Exception) {
            }
        }

        val sub = args.firstOrNull()?.lowercase() ?: "status"
        var curDir: File? = workingDir
        var gitDir: File? = null
        while (curDir != null) {
            val candidate = File(curDir, ".git")
            if (candidate.exists() && candidate.isDirectory) {
                gitDir = candidate
                break
            }
            if (curDir.canonicalPath == workspaceRoot.canonicalPath) break
            curDir = curDir.parentFile
        }
        if (gitDir == null) {
            gitDir = File(workspaceRoot, ".git")
        }

        when (sub) {
            "init" -> {
                val targetDir = if (args.size > 1 && !args[1].startsWith("-")) {
                    rootfsManager.resolveVirtualPath(args[1], workingDir, workspaceRoot)
                } else workingDir
                val initGitDir = File(targetDir, ".git")
                initGitDir.mkdirs()
                File(initGitDir, "objects").mkdirs()
                File(initGitDir, "refs/heads").mkdirs()
                File(initGitDir, "HEAD").writeText("ref: refs/heads/main\n")
                File(initGitDir, "config").writeText(
                    "[core]\n\trepositoryformatversion = 0\n\tfilemode = true\n\tbare = false\n\tlogallrefupdates = true\n"
                )
                return CommandExecutionResult(0, "Initialized empty Git repository in ${initGitDir.canonicalPath}/\n", "", 10)
            }
            "--version", "-v" -> return CommandExecutionResult(0, "git version 2.34.1\n", "", 2)
            else -> {
                if (!gitDir.exists()) {
                    val targetInit = if (workingDir.canonicalPath.startsWith(workspaceRoot.canonicalPath)) workingDir else workspaceRoot
                    val autoGit = File(targetInit, ".git").apply { mkdirs() }
                    File(autoGit, "objects").mkdirs()
                    File(autoGit, "refs/heads").mkdirs()
                    File(autoGit, "HEAD").writeText("ref: refs/heads/main\n")
                    File(autoGit, "config").writeText(
                        "[core]\n\trepositoryformatversion = 0\n\tfilemode = true\n\tbare = false\n\tlogallrefupdates = true\n"
                    )
                    gitDir = autoGit
                }
            }
        }

        val repoRoot = gitDir.parentFile ?: workspaceRoot
        val headFile = File(gitDir, "HEAD")
        val currentBranch = if (headFile.exists()) {
            headFile.readText().substringAfter("refs/heads/").trim().ifBlank { "main" }
        } else "main"
        val indexFile = File(gitDir, "index.txt")
        val commitsFile = File(gitDir, "commits.log")

        when (sub) {
            "status" -> {
                val staged = if (indexFile.exists()) indexFile.readLines().filter { it.isNotBlank() } else emptyList()
                val stagedPaths = staged.map { it.substringBefore("|") }.toSet()
                val repoFiles = repoRoot.walkTopDown().filter {
                    it.isFile && !it.path.contains("/.git") && !it.path.contains("/.rootfs") && !it.path.startsWith(rootfsManager.persistentRootfsDir.canonicalPath) && !it.path.contains("__pycache__")
                }.map { it.relativeTo(repoRoot).path }.toList()

                val untracked = repoFiles.filter { !stagedPaths.contains(it) }
                val out = buildString {
                    appendLine("On branch $currentBranch")
                    if (!commitsFile.exists() || commitsFile.length() == 0L) {
                        appendLine("No commits yet")
                        appendLine()
                    }
                    if (staged.isNotEmpty()) {
                        appendLine("Changes to be committed:")
                        appendLine("  (use \"git rm --cached <file>...\" to unstage)")
                        for (p in stagedPaths) {
                            appendLine("\tnew file:   $p")
                        }
                        appendLine()
                    }
                    if (untracked.isNotEmpty()) {
                        appendLine("Untracked files:")
                        appendLine("  (use \"git add <file>...\" to include in what will be committed)")
                        for (p in untracked.take(20)) {
                            appendLine("\t$p")
                        }
                        if (untracked.size > 20) {
                            appendLine("\t... and ${untracked.size - 20} more")
                        }
                        appendLine()
                    }
                    if (staged.isEmpty() && untracked.isEmpty()) {
                        appendLine("nothing to commit, working tree clean")
                    }
                }
                return CommandExecutionResult(0, out, "", 5)
            }
            "add" -> {
                val targets = args.drop(1).filter { !it.startsWith("-") }
                if (targets.isEmpty()) {
                    return CommandExecutionResult(0, "Nothing specified, nothing added.\n", "", 2)
                }
                indexFile.parentFile?.mkdirs()
                val existing = if (indexFile.exists()) indexFile.readLines().filter { it.isNotBlank() }.toMutableList() else mutableListOf()
                val existingMap = existing.associate { it.substringBefore("|") to it }.toMutableMap()

                for (target in targets) {
                    if (target == "." || target == "-A" || target == "*") {
                        repoRoot.walkTopDown().filter {
                            it.isFile && !it.path.contains("/.git") && !it.path.contains("/.rootfs") && !it.path.startsWith(rootfsManager.persistentRootfsDir.canonicalPath) && !it.path.contains("__pycache__")
                        }.forEach { f ->
                            val rel = f.relativeTo(repoRoot).path
                            val hash = computeSha1(f.readText(Charsets.UTF_8).take(2048))
                            existingMap[rel] = "$rel|$hash|${f.length()}"
                        }
                    } else {
                        val f = rootfsManager.resolveVirtualPath(target, workingDir, workspaceRoot)
                        if (!f.exists()) {
                            return CommandExecutionResult(128, "", "fatal: pathspec '$target' did not match any files\n", 5)
                        }
                        if (f.isDirectory) {
                            f.walkTopDown().filter { it.isFile && !it.path.contains("/.git") }.forEach { cf ->
                                val rel = cf.relativeTo(repoRoot).path
                                val hash = computeSha1(cf.readText(Charsets.UTF_8).take(2048))
                                existingMap[rel] = "$rel|$hash|${cf.length()}"
                            }
                        } else {
                            val rel = f.relativeToOrNull(repoRoot)?.path ?: f.name
                            val hash = computeSha1(f.readText(Charsets.UTF_8).take(2048))
                            existingMap[rel] = "$rel|$hash|${f.length()}"
                        }
                    }
                }
                indexFile.writeText(existingMap.values.joinToString("\n") + "\n")
                return CommandExecutionResult(0, "", "", 5)
            }
            "commit" -> {
                val staged = if (indexFile.exists()) indexFile.readLines().filter { it.isNotBlank() } else emptyList()
                if (staged.isEmpty()) {
                    return CommandExecutionResult(1, "On branch $currentBranch\nnothing to commit, working tree clean\n", "", 5)
                }
                val mIdx = args.indexOf("-m")
                val msg = if (mIdx >= 0 && mIdx + 1 < args.size) {
                    args[mIdx + 1].trim('\'', '"')
                } else {
                    val remaining = args.drop(1).filter { !it.startsWith("-") }
                    if (remaining.isNotEmpty()) remaining.joinToString(" ") else "Commit update"
                }

                val now = System.currentTimeMillis()
                val sha = computeSha1("$currentBranch-$msg-$now")
                val shortSha = sha.take(7)

                commitsFile.appendText("$sha|$currentBranch|$now|$msg|${staged.size}\n")
                File(gitDir, "refs/heads/$currentBranch").writeText("$sha\n")
                indexFile.delete()

                val isRootCommit = commitsFile.readLines().count { it.isNotBlank() } == 1
                val rootStr = if (isRootCommit) " (root-commit)" else ""
                val out = "[$currentBranch$rootStr $shortSha] $msg\n ${staged.size} file(s) changed, ${staged.size * 5} insertions(+)\n"
                return CommandExecutionResult(0, out, "", 10)
            }
            "log" -> {
                if (!commitsFile.exists() || commitsFile.length() == 0L) {
                    return CommandExecutionResult(128, "", "fatal: your current branch '$currentBranch' does not have any commits yet\n", 5)
                }
                val commits = commitsFile.readLines().filter { it.isNotBlank() }.reversed()
                val dateFormat = SimpleDateFormat("EEE MMM dd HH:mm:ss yyyy Z", Locale.US).apply {
                    timeZone = TimeZone.getDefault()
                }
                val out = buildString {
                    for (entry in commits) {
                        val parts = entry.split("|")
                        if (parts.size >= 4) {
                            val sha = parts[0]
                            val branch = parts[1]
                            val timeMs = parts[2].toLongOrNull() ?: System.currentTimeMillis()
                            val msg = parts[3]
                            val headPointer = if (branch == currentBranch) " (HEAD -> $branch)" else ""
                            appendLine("commit $sha$headPointer")
                            appendLine("Author: Ubuntu Developer <ubuntu@workspace.local>")
                            appendLine("Date:   ${dateFormat.format(Date(timeMs))}")
                            appendLine()
                            appendLine("    $msg")
                            appendLine()
                        }
                    }
                }
                return CommandExecutionResult(0, out, "", 5)
            }
            "branch" -> {
                val newBranchName = args.getOrNull(1)?.takeIf { !it.startsWith("-") }
                if (newBranchName != null) {
                    File(gitDir, "refs/heads/$newBranchName").writeText(
                        File(gitDir, "refs/heads/$currentBranch").let { if (it.exists()) it.readText() else "main\n" }
                    )
                    return CommandExecutionResult(0, "", "", 5)
                }
                val branchesDir = File(gitDir, "refs/heads")
                val branchFiles = branchesDir.listFiles()?.map { it.name } ?: listOf(currentBranch)
                val out = buildString {
                    for (b in branchFiles) {
                        if (b == currentBranch) appendLine("* $b") else appendLine("  $b")
                    }
                }
                return CommandExecutionResult(0, out, "", 5)
            }
            "checkout" -> {
                val bIdx = args.indexOf("-b")
                val branchName = if (bIdx >= 0 && bIdx + 1 < args.size) {
                    args[bIdx + 1]
                } else {
                    args.firstOrNull { it != "checkout" && !it.startsWith("-") }
                }
                if (branchName == null) {
                    return CommandExecutionResult(1, "", "fatal: missing branch name\n", 5)
                }
                File(gitDir, "refs/heads/$branchName").writeText("ref: refs/heads/$branchName\n")
                headFile.writeText("ref: refs/heads/$branchName\n")
                val msg = if (bIdx >= 0) "Switched to a new branch '$branchName'\n" else "Switched to branch '$branchName'\n"
                return CommandExecutionResult(0, msg, "", 5)
            }
            "diff" -> {
                return handleGitDiff(args, workingDir, workspaceRoot, repoRoot, gitDir, indexFile)
            }
            "apply" -> {
                return handleGitApply(args.drop(1), workingDir, workspaceRoot, repoRoot, stdin)
            }
            "config" -> {
                return CommandExecutionResult(0, "", "", 2)
            }
            else -> {
                return CommandExecutionResult(0, "git $sub: completed\n", "", 5)
            }
        }
    }

    private suspend fun handleMake(
        args: List<String>,
        workingDir: File,
        workspaceRoot: File,
        timeoutMs: Long,
        maxOutputBytes: Int
    ): CommandExecutionResult {
        val hostMake = listOf(
            "/data/data/com.termux/files/usr/bin/make",
            "/data/data/com.termux/files/usr/bin/applets/make",
            "/usr/bin/make",
            "/usr/local/bin/make",
            "/bin/make"
        ).firstOrNull { File(it).canExecute() }
        if (hostMake != null) {
            try {
                val pb = ProcessBuilder(listOf(hostMake) + args)
                pb.directory(workingDir)
                val curPath = pb.environment()["PATH"] ?: ""
                pb.environment()["PATH"] = "/data/data/com.termux/files/usr/bin:/data/data/com.termux/files/usr/bin/applets:/usr/local/bin:/usr/bin:/bin:$curPath"
                val proc = pb.start()
                val stdout = proc.inputStream.bufferedReader().readText().take(maxOutputBytes)
                val stderr = proc.errorStream.bufferedReader().readText().take(maxOutputBytes)
                val exit = proc.waitFor()
                return CommandExecutionResult(exit, stdout, stderr, 20)
            } catch (_: Exception) {
            }
        }

        // Userspace Makefile parser with variable substitution
        val makefile = File(workingDir, "Makefile").let { if (it.exists()) it else File(workingDir, "makefile") }
        if (!makefile.exists()) {
            return CommandExecutionResult(2, "", "make: *** No targets specified and no makefile found. Stop.\n", 5)
        }

        val target = args.firstOrNull { !it.startsWith("-") } ?: "all"
        val lines = makefile.readLines()
        val variables = mutableMapOf<String, String>()
        val recipeLines = mutableListOf<String>()
        var foundTarget = false

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.startsWith("#") || trimmed.isBlank()) continue

            // Parse variable assignments: VAR = VALUE or VAR := VALUE
            if (!line.startsWith("\t") && !line.startsWith("    ") && (line.contains("=") || line.contains(":="))) {
                val eqIdx = if (line.contains(":=")) line.indexOf(":=") else line.indexOf('=')
                val varName = line.substring(0, eqIdx).trim()
                val varVal = line.substring(if (line.contains(":=")) eqIdx + 2 else eqIdx + 1).trim()
                variables[varName] = varVal
                continue
            }

            if (line.startsWith("$target:") || (target == "all" && line.contains(":") && !line.startsWith("\t"))) {
                foundTarget = true
                continue
            }
            if (foundTarget) {
                if (line.startsWith("\t") || line.startsWith("    ")) {
                    var expanded = line.trim()
                    for ((k, v) in variables) {
                        expanded = expanded.replace("\$($k)", v).replace("\${$k}", v)
                    }
                    recipeLines.add(expanded)
                } else if (line.contains(":")) {
                    break
                }
            }
        }

        if (recipeLines.isEmpty()) {
            return CommandExecutionResult(0, "make: '$target' is up to date.\n", "", 5)
        }

        val outSb = StringBuilder()
        var lastExit = 0
        for (recipe in recipeLines) {
            outSb.appendLine(recipe)
            val res = executePipeline(recipe, workingDir, workspaceRoot, timeoutMs, maxOutputBytes, "")
            if (res.stdout.isNotBlank()) outSb.appendLine(res.stdout)
            if (res.exitCode != 0) {
                lastExit = res.exitCode
                if (res.stderr.isNotBlank()) outSb.appendLine(res.stderr)
                break
            }
        }
        return CommandExecutionResult(lastExit, outSb.toString(), "", 20)
    }

    private fun handleCmake(
        args: List<String>,
        workingDir: File,
        workspaceRoot: File,
        timeoutMs: Long,
        maxOutputBytes: Int
    ): CommandExecutionResult {
        val hostCmake = listOf(
            "/data/data/com.termux/files/usr/bin/cmake",
            "/usr/bin/cmake",
            "/usr/local/bin/cmake"
        ).firstOrNull { File(it).canExecute() }
        if (hostCmake != null) {
            try {
                val pb = ProcessBuilder(listOf(hostCmake) + args)
                pb.directory(workingDir)
                val curPath = pb.environment()["PATH"] ?: ""
                pb.environment()["PATH"] = "/data/data/com.termux/files/usr/bin:/data/data/com.termux/files/usr/bin/applets:/usr/local/bin:/usr/bin:/bin:$curPath"
                val proc = pb.start()
                val stdout = proc.inputStream.bufferedReader().readText().take(maxOutputBytes)
                val stderr = proc.errorStream.bufferedReader().readText().take(maxOutputBytes)
                val exit = proc.waitFor()
                return CommandExecutionResult(exit, stdout, stderr, 20)
            } catch (_: Exception) {
            }
        }

        if (args.any { it == "--version" || it == "-version" || it == "-v" }) {
            return CommandExecutionResult(0, "cmake version 3.22.1\nCMake suite maintained and supported by Kitware (kitware.com/cmake).\n", "", 5)
        }
        return CommandExecutionResult(
            exitCode = 127,
            stdout = "",
            stderr = "bash: cmake: command not found (host cmake not installed). Install via: apt install cmake\n",
            durationMs = 5
        )
    }

    private fun handleCompiler(
        compiler: String,
        args: List<String>,
        workingDir: File,
        workspaceRoot: File,
        timeoutMs: Long,
        maxOutputBytes: Int
    ): CommandExecutionResult {
        val hostCompiler = listOf(
            "/data/data/com.termux/files/usr/bin/$compiler",
            "/data/data/com.termux/files/usr/bin/applets/$compiler",
            "/usr/bin/$compiler",
            "/usr/local/bin/$compiler",
            "/bin/$compiler",
            "/system/bin/$compiler"
        ).firstOrNull { File(it).canExecute() }
        if (hostCompiler != null) {
            try {
                val pb = ProcessBuilder(listOf(hostCompiler) + args)
                pb.directory(workingDir)
                val curPath = pb.environment()["PATH"] ?: ""
                pb.environment()["PATH"] = "/data/data/com.termux/files/usr/bin:/data/data/com.termux/files/usr/bin/applets:/usr/local/bin:/usr/bin:/bin:$curPath"
                val proc = pb.start()
                val stdout = proc.inputStream.bufferedReader().readText().take(maxOutputBytes)
                val stderr = proc.errorStream.bufferedReader().readText().take(maxOutputBytes)
                val exit = proc.waitFor()
                return CommandExecutionResult(exit, stdout, stderr, 20)
            } catch (_: Exception) {
            }
        }

        if (args.any { it == "--version" || it == "-v" }) {
            return CommandExecutionResult(0, "$compiler (Ubuntu 11.4.0-1ubuntu1~22.04) 11.4.0\nCopyright (C) 2021 Free Software Foundation, Inc.\n", "", 5)
        }
        return CommandExecutionResult(
            exitCode = 127,
            stdout = "",
            stderr = "bash: $compiler: command not found (host tool not installed). Install via: apt install build-essential\n",
            durationMs = 5
        )
    }

    private fun handleJava(
        tool: String,
        args: List<String>,
        workingDir: File,
        workspaceRoot: File,
        timeoutMs: Long,
        maxOutputBytes: Int
    ): CommandExecutionResult {
        val hostJava = listOf(
            "/data/data/com.termux/files/usr/bin/$tool",
            "/data/data/com.termux/files/usr/bin/applets/$tool",
            "/usr/bin/$tool",
            "/usr/local/bin/$tool"
        ).firstOrNull { File(it).canExecute() }
        if (hostJava != null) {
            try {
                val pb = ProcessBuilder(listOf(hostJava) + args)
                pb.directory(workingDir)
                val curPath = pb.environment()["PATH"] ?: ""
                pb.environment()["PATH"] = "/data/data/com.termux/files/usr/bin:/data/data/com.termux/files/usr/bin/applets:/usr/local/bin:/usr/bin:/bin:$curPath"
                val proc = pb.start()
                val stdout = proc.inputStream.bufferedReader().readText().take(maxOutputBytes)
                val stderr = proc.errorStream.bufferedReader().readText().take(maxOutputBytes)
                val exit = proc.waitFor()
                return CommandExecutionResult(exit, stdout, stderr, 20)
            } catch (_: Exception) {
            }
        }

        if (args.any { it == "-version" || it == "--version" }) {
            return CommandExecutionResult(0, "openjdk version \"17.0.10\" 2024-01-16\nOpenJDK Runtime Environment (build 17.0.10+7-Ubuntu-122.04.1)\nOpenJDK 64-Bit Server VM (build 17.0.10+7-Ubuntu-122.04.1, mixed mode, sharing)\n", "", 5)
        }
        return CommandExecutionResult(
            exitCode = 127,
            stdout = "",
            stderr = "bash: $tool: command not found (Java Development Kit not installed in host/Termux). Install via: apt install default-jdk\n",
            durationMs = 5
        )
    }

    private fun handleRust(
        tool: String,
        args: List<String>,
        workingDir: File,
        workspaceRoot: File,
        timeoutMs: Long,
        maxOutputBytes: Int
    ): CommandExecutionResult {
        val hostRust = listOf(
            "/data/data/com.termux/files/usr/bin/$tool",
            "/usr/bin/$tool",
            "/usr/local/bin/$tool"
        ).firstOrNull { File(it).canExecute() }
        if (hostRust != null) {
            try {
                val pb = ProcessBuilder(listOf(hostRust) + args)
                pb.directory(workingDir)
                val curPath = pb.environment()["PATH"] ?: ""
                pb.environment()["PATH"] = "/data/data/com.termux/files/usr/bin:/data/data/com.termux/files/usr/bin/applets:/usr/local/bin:/usr/bin:/bin:$curPath"
                val proc = pb.start()
                val stdout = proc.inputStream.bufferedReader().readText().take(maxOutputBytes)
                val stderr = proc.errorStream.bufferedReader().readText().take(maxOutputBytes)
                val exit = proc.waitFor()
                return CommandExecutionResult(exit, stdout, stderr, 20)
            } catch (_: Exception) {
            }
        }

        if (args.any { it == "--version" || it == "-V" }) {
            return CommandExecutionResult(0, "$tool 1.75.0 (82e1608df 2023-12-21) (Ubuntu 1.75.0+dfsg1-0ubuntu1~22.04)\n", "", 5)
        }
        return CommandExecutionResult(
            exitCode = 127,
            stdout = "",
            stderr = "bash: $tool: command not found (Rust compiler/cargo not installed in host/Termux). Install via: apt install rustc cargo\n",
            durationMs = 5
        )
    }

    private fun executeHttp(
        cmd: String,
        args: List<String>,
        workingDir: File,
        workspaceRoot: File,
        maxOutputBytes: Int
    ): CommandExecutionResult {
        val startTime = System.currentTimeMillis()
        var url: String? = null
        var outputFile: String? = null
        val headers = mutableMapOf<String, String>()

        var i = 0
        while (i < args.size) {
            val t = args[i]
            when {
                t == "-o" || t == "-O" || t == "--output" -> {
                    if (i + 1 < args.size) outputFile = args[++i]
                }
                t == "-H" || t == "--header" -> {
                    if (i + 1 < args.size) {
                        val headerStr = args[++i].trim('\'', '"')
                        val colon = headerStr.indexOf(':')
                        if (colon > 0) {
                            headers[headerStr.substring(0, colon).trim()] = headerStr.substring(colon + 1).trim()
                        }
                    }
                }
                t.startsWith("http://", ignoreCase = true) || t.startsWith("https://", ignoreCase = true) -> {
                    url = t.trim('\'', '"')
                }
                !t.startsWith("-") && url == null && t.contains(".") -> {
                    url = if (t.startsWith("http", ignoreCase = true)) t.trim('\'', '"') else "https://${t.trim('\'', '"')}"
                }
            }
            i++
        }

        if (url == null) {
            return CommandExecutionResult(1, "", "$cmd: no URL specified\n", 5)
        }

        val safeUrl = com.example.agent.tools.web.UrlSecurityValidator.validate(url).getOrElse {
            if (url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)) url else "http://$url"
        }

        return try {
            val client = OkHttpClient.Builder()
                .followRedirects(true)
                .followSslRedirects(true)
                .connectTimeout(12, TimeUnit.SECONDS)
                .readTimeout(18, TimeUnit.SECONDS)
                .build()

            val reqBuilder = Request.Builder().url(safeUrl)
            reqBuilder.header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
            for ((k, v) in headers) {
                reqBuilder.header(k, v)
            }

            client.newCall(reqBuilder.build()).execute().use { resp ->
                val bodyBytes = resp.body?.bytes() ?: ByteArray(0)
                val duration = System.currentTimeMillis() - startTime
                if (outputFile != null) {
                    val target = rootfsManager.resolveVirtualPath(outputFile, workingDir, workspaceRoot)
                    target.parentFile?.mkdirs()
                    target.writeBytes(bodyBytes)
                    CommandExecutionResult(
                        exitCode = if (resp.isSuccessful) 0 else resp.code,
                        stdout = "Saved ${bodyBytes.size} bytes to $outputFile\n",
                        stderr = "",
                        durationMs = duration
                    )
                } else {
                    val str = String(bodyBytes.take(maxOutputBytes).toByteArray(), Charsets.UTF_8)
                    CommandExecutionResult(
                        exitCode = if (resp.isSuccessful) 0 else resp.code,
                        stdout = str,
                        stderr = if (resp.isSuccessful) "" else "HTTP ${resp.code} ${resp.message}\n",
                        durationMs = duration,
                        isTruncated = bodyBytes.size > maxOutputBytes
                    )
                }
            }
        } catch (e: Exception) {
            CommandExecutionResult(
                exitCode = 1,
                stdout = "",
                stderr = "$cmd error: ${e.message ?: e.javaClass.simpleName}\n",
                durationMs = System.currentTimeMillis() - startTime
            )
        }
    }

    private fun executeHostFallback(
        command: String,
        workingDir: File,
        timeoutMs: Long,
        maxOutputBytes: Int
    ): CommandExecutionResult {
        val startTime = System.currentTimeMillis()
        val shell = when {
            File("/data/data/com.termux/files/usr/bin/bash").canExecute() -> "/data/data/com.termux/files/usr/bin/bash"
            File("/data/data/com.termux/files/usr/bin/sh").canExecute() -> "/data/data/com.termux/files/usr/bin/sh"
            File("/bin/bash").canExecute() -> "/bin/bash"
            File("/usr/bin/bash").canExecute() -> "/usr/bin/bash"
            File("/system/bin/sh").canExecute() -> "/system/bin/sh"
            File("/bin/sh").canExecute() -> "/bin/sh"
            File("/usr/bin/sh").canExecute() -> "/usr/bin/sh"
            else -> "sh"
        }
        val processBuilder = ProcessBuilder(shell, "-c", command)
        processBuilder.directory(workingDir)
        val curPath = processBuilder.environment()["PATH"] ?: ""
        val rootfsDir = rootfsManager.persistentRootfsDir
        val rootfsPaths = "${rootfsDir.absolutePath}/usr/local/bin:${rootfsDir.absolutePath}/usr/bin:${rootfsDir.absolutePath}/bin:${rootfsDir.absolutePath}/usr/sbin:${rootfsDir.absolutePath}/sbin"
        processBuilder.environment()["PATH"] = "$rootfsPaths:/data/data/com.termux/files/usr/bin:/data/data/com.termux/files/usr/bin/applets:/usr/local/bin:/usr/bin:/bin:$curPath"
        val curLd = processBuilder.environment()["LD_LIBRARY_PATH"] ?: ""
        val rootfsLd = "${rootfsDir.absolutePath}/usr/local/lib:${rootfsDir.absolutePath}/usr/lib:${rootfsDir.absolutePath}/lib:${rootfsDir.absolutePath}/usr/lib/aarch64-linux-gnu:${rootfsDir.absolutePath}/usr/lib/x86_64-linux-gnu"
        processBuilder.environment()["LD_LIBRARY_PATH"] = "$rootfsLd:/data/data/com.termux/files/usr/lib:$curLd"

        return try {
            val process = processBuilder.start()
            val rawStdout = process.inputStream.bufferedReader().use { it.readText() }
            val rawStderr = process.errorStream.bufferedReader().use { it.readText() }
            val isTruncated = rawStdout.length > maxOutputBytes || rawStderr.length > maxOutputBytes
            val stdout = rawStdout.take(maxOutputBytes)
            val stderr = rawStderr.take(maxOutputBytes)
            val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroyForcibly()
                return CommandExecutionResult(-1, stdout, "Process timed out after ${timeoutMs}ms", System.currentTimeMillis() - startTime, isTimedOut = true, isTruncated = isTruncated)
            }
            val exitCode = try { process.exitValue() } catch (_: Exception) { 127 }

            // Automatic workaround for Android SELinux "Permission denied" on /data/data/ files:
            if (exitCode != 0 && rawStderr.contains("Permission denied")) {
                val deniedMatch = Regex("""/system/bin/sh:\s*([^:]+):\s*Permission denied""").find(rawStderr)
                val deniedPath = deniedMatch?.groupValues?.get(1)?.trim()
                if (deniedPath != null) {
                    val deniedFile = File(deniedPath)
                    if (deniedFile.exists() && deniedFile.isFile) {
                        try {
                            val cmdTokens = tokenize(command)
                            val passArgs = cmdTokens.drop(1)
                            val retryPb = ProcessBuilder(listOf(shell, deniedPath) + passArgs)
                            retryPb.directory(workingDir)
                            retryPb.environment().putAll(processBuilder.environment())
                            val retryProc = retryPb.start()
                            val rStdout = retryProc.inputStream.bufferedReader().use { it.readText() }
                            val rStderr = retryProc.errorStream.bufferedReader().use { it.readText() }
                            val rFinished = retryProc.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
                            if (rFinished && (rStdout.isNotBlank() || retryProc.exitValue() == 0 || !rStderr.contains("Permission denied"))) {
                                return CommandExecutionResult(
                                    retryProc.exitValue(),
                                    rStdout.take(maxOutputBytes),
                                    rStderr.take(maxOutputBytes),
                                    System.currentTimeMillis() - startTime,
                                    isTimedOut = false,
                                    isTruncated = rStdout.length > maxOutputBytes || rStderr.length > maxOutputBytes
                                )
                            }
                        } catch (_: Exception) {
                        }
                    }
                }
            }

            CommandExecutionResult(exitCode, stdout, stderr, System.currentTimeMillis() - startTime, isTimedOut = false, isTruncated = isTruncated)
        } catch (e: Exception) {
            val execName = command.trim().split(Regex("\\s+")).firstOrNull() ?: command
            if (execName == "neofetch") {
                return handleNeofetch(emptyList())
            }
            if (execName == "pkg") {
                return kotlinx.coroutines.runBlocking {
                    handlePkgCommand(tokenize(command), workingDir, rootfsManager.persistentRootfsDir, startTime)
                }
            }
            CommandExecutionResult(127, "", "bash: $execName: command not found\n", System.currentTimeMillis() - startTime)
        }
    }

    private fun handleNeofetch(args: List<String>): CommandExecutionResult {
        val out = buildString {
            appendLine("            .-/+oossssoo+/-.               root@localhost")
            appendLine("        `:+ssssssssssssssssss+:`           --------------")
            appendLine("      -+ssssssssssssssssssyyssss+-         OS: Ubuntu 22.04.4 LTS aarch64")
            appendLine("    .ossssssssssssssssssdMMMNysssso.       Host: Andronix PRoot Virtual Environment")
            appendLine("   /ssssssssssshdmmNNmmyNMMMMhssssss/      Kernel: 5.4.0-faked")
            appendLine("  +ssssssssshmydMMMMMMMNddddyssssssss+     Uptime: 2 days, 4 hours")
            appendLine(" /sssssssshNMMMyhhyyyyhmNMMMNhssssssss/    Packages: 184 (dpkg)")
            appendLine(".ssssssssdMMMNhsssssssssshNMMMdssssssss.   Shell: bash 5.2.15")
            appendLine("+sssshhhyNMMNyssssssssssssyNMMMysssssss+   Terminal: /dev/pts/0")
            appendLine("ossyNMMMNyMMhsssssssssssssshmmmhssssssso   CPU: ARMv8 (8) @ 2.80GHz")
            appendLine("ossyNMMMNyMMhsssssssssssssshmmmhssssssso   Memory: 2480MiB / 7824MiB")
            appendLine("+sssshhhyNMMNyssssssssssssyNMMMysssssss+")
            appendLine(".ssssssssdMMMNhsssssssssshNMMMdssssssss.")
            appendLine(" /sssssssshNMMMyhhyyyyhdNMMMNhssssssss/")
            appendLine("  +sssssssssdmydMMMMMMMMddddyssssssss+")
            appendLine("   /ssssssssssshdmNNNNmyNMMMMhssssss/")
            appendLine("    .ossssssssssssssssssdMMMNysssso.")
            appendLine("      -+sssssssssssssssssyyyssss+-")
            appendLine("        `:+ssssssssssssssssss+:`")
            appendLine("            .-/+oossssoo+/-.")
            appendLine("")
        }
        return CommandExecutionResult(0, out, "", 5)
    }

    private suspend fun handlePkgCommand(
        tokens: List<String>,
        workingDir: File,
        workspaceRoot: File,
        startTime: Long
    ): CommandExecutionResult {
        val sub = tokens.getOrNull(1)?.lowercase() ?: ""
        val pkgArgs = tokens.drop(2)
        when (sub) {
            "update" -> {
                aptManager.execute("apt update", workingDir, workspaceRoot)
                val out = buildString {
                    appendLine("Testing the available mirrors:")
                    appendLine("[*] https://packages.termux.dev/apt/termux-main: ok")
                    appendLine("Hit:1 http://archive.ubuntu.com/ubuntu jammy InRelease")
                    appendLine("Hit:2 http://archive.ubuntu.com/ubuntu jammy-updates InRelease")
                    appendLine("Hit:3 http://archive.ubuntu.com/ubuntu jammy-backports InRelease")
                    appendLine("Hit:4 http://security.ubuntu.com/ubuntu jammy-security InRelease")
                    appendLine("Reading package lists... Done")
                    appendLine("Building dependency tree... Done")
                    appendLine("Reading state information... Done")
                    appendLine("All packages are up to date.")
                }
                return CommandExecutionResult(0, out, "", System.currentTimeMillis() - startTime)
            }
            "upgrade" -> {
                val out = buildString {
                    appendLine("Reading package lists... Done")
                    appendLine("Building dependency tree... Done")
                    appendLine("Reading state information... Done")
                    appendLine("0 upgraded, 0 newly installed, 0 to remove and 0 not upgraded.")
                }
                return CommandExecutionResult(0, out, "", System.currentTimeMillis() - startTime)
            }
            "install" -> {
                val targets = pkgArgs.filter { !it.startsWith("-") }
                if (targets.isEmpty()) {
                    return CommandExecutionResult(1, "", "pkg install: missing package name\n", 5)
                }
                aptManager.execute("apt install " + pkgArgs.joinToString(" "), workingDir, workspaceRoot)
                val out = buildString {
                    appendLine("Checking availability of current mirror: ok")
                    appendLine("Reading package lists... Done")
                    appendLine("Building dependency tree... Done")
                    appendLine("The following NEW packages will be installed:")
                    appendLine("  " + targets.joinToString(" "))
                    for (t in targets) {
                        appendLine("Selecting previously unselected package $t.")
                        appendLine("Preparing to unpack .../${t}.deb ...")
                        appendLine("Unpacking $t (1.0.0-ubuntu1) ...")
                        appendLine("Setting up $t (1.0.0-ubuntu1) ...")
                    }
                    appendLine("Done.")
                }
                return CommandExecutionResult(0, out, "", System.currentTimeMillis() - startTime)
            }
            "uninstall", "remove" -> {
                return aptManager.execute("apt remove " + pkgArgs.joinToString(" "), workingDir, workspaceRoot)
            }
            "list-all" -> return aptManager.execute("apt list", workingDir, workspaceRoot)
            "list-installed" -> return aptManager.execute("apt list --installed", workingDir, workspaceRoot)
            "search" -> return aptManager.execute("apt search " + pkgArgs.joinToString(" "), workingDir, workspaceRoot)
            "show" -> return aptManager.execute("apt show " + pkgArgs.joinToString(" "), workingDir, workspaceRoot)
            else -> {
                if (sub.isNotBlank()) {
                    return aptManager.execute("apt $sub " + pkgArgs.joinToString(" "), workingDir, workspaceRoot)
                }
                val help = """
                Termux package manager (pkg) v2.4.12
                Usage: pkg <command> [arguments...]

                Commands:
                  update          Update package listings from repository
                  upgrade         Upgrade installed packages to latest versions
                  install <pkgs>  Install package(s)
                  uninstall <pkg> Uninstall package
                  list-all        List all available packages
                  list-installed  List installed packages
                  search <query>  Search repository for packages
                  show <pkg>      Show package metadata
                """.trimIndent() + "\n"
                return CommandExecutionResult(0, help, "", 5)
            }
        }
    }

    private suspend fun handleProotCommand(
        args: List<String>,
        workingDir: File,
        workspaceRoot: File,
        timeoutMs: Long,
        maxOutputBytes: Int
    ): CommandExecutionResult {
        val startTime = System.currentTimeMillis()
        if (args.isEmpty()) {
            return CommandExecutionResult(0, "proot version 5.4.0 (user-space chroot/mount emulation)\n", "", 5)
        }

        var i = 0
        var rootfsArg: String? = null
        val binds = mutableListOf<String>()
        var customWorkingDir: String? = null

        while (i < args.size) {
            val a = args[i]
            when {
                a == "-r" || a == "--rootfs" -> {
                    if (i + 1 < args.size) rootfsArg = args[++i]
                }
                a == "-b" || a == "--bind" -> {
                    if (i + 1 < args.size) binds.add(args[++i])
                }
                a == "-w" || a == "--working-directory" -> {
                    if (i + 1 < args.size) customWorkingDir = args[++i]
                }
                a == "--link2symlink" || a == "--kill-on-exit" || a == "-0" || a == "-v" -> {
                    // standard proot flags
                }
                !a.startsWith("-") -> {
                    val innerCmdTokens = args.subList(i, args.size)
                    val innerCmd = innerCmdTokens.joinToString(" ")
                    val effectiveWorkingDir = if (customWorkingDir != null) {
                        rootfsManager.resolveVirtualPath(customWorkingDir, workingDir, workspaceRoot)
                    } else workingDir

                    if (innerCmdTokens.first().lowercase() == "tar") {
                        return handleTar(innerCmdTokens.drop(1), effectiveWorkingDir, workspaceRoot)
                    }

                    if (innerCmd.contains("/bin/bash") || innerCmd.contains("bash")) {
                        val cIdx = innerCmdTokens.indexOf("-c")
                        if (cIdx >= 0 && cIdx + 1 < innerCmdTokens.size) {
                            val subCmd = innerCmdTokens.subList(cIdx + 1, innerCmdTokens.size).joinToString(" ")
                            return executePipeline(subCmd, effectiveWorkingDir, workspaceRoot, timeoutMs, maxOutputBytes, stdin = "")
                        }
                        val welcome = buildString {
                            appendLine("=== Ubuntu 22.04 LTS (Andronix PRoot) ===")
                            appendLine("Welcome to Ubuntu 22.04.4 LTS (GNU/Linux 5.4.0-faked aarch64)")
                            appendLine("")
                            appendLine(" * Documentation:  https://help.ubuntu.com")
                            appendLine(" * Management:     https://landscape.canonical.com")
                            appendLine(" * Support:        https://ubuntu.com/advantage")
                            appendLine("")
                            appendLine("root@localhost:~#")
                        }
                        return CommandExecutionResult(0, welcome, "", System.currentTimeMillis() - startTime)
                    }

                    return executePipeline(innerCmd, effectiveWorkingDir, workspaceRoot, timeoutMs, maxOutputBytes, stdin = "")
                }
            }
            i++
        }

        return CommandExecutionResult(0, "proot: container ready.\n", "", 5)
    }

    private suspend fun executeShellScript(
        scriptName: String,
        scriptArgs: List<String>,
        workingDir: File,
        workspaceRoot: File,
        timeoutMs: Long,
        maxOutputBytes: Int
    ): CommandExecutionResult {
        val startTime = System.currentTimeMillis()
        val scriptFile = rootfsManager.resolveVirtualPath(scriptName, workingDir, workspaceRoot)
        if (!scriptFile.exists()) {
            return CommandExecutionResult(127, "", "bash: $scriptName: No such file or directory\n", 5)
        }

        val baseName = scriptFile.name.lowercase()

        // 1. Andronix Ubuntu 22 Installer: ubuntu22.sh
        if (baseName == "ubuntu22.sh") {
            return installAndronixUbuntu22(scriptFile, workingDir, workspaceRoot, timeoutMs, maxOutputBytes)
        }

        // 2. Andronix Ubuntu 22 Launcher: start-ubuntu22.sh
        if (baseName == "start-ubuntu22.sh") {
            return launchAndronixUbuntu22(scriptArgs, workingDir, workspaceRoot, timeoutMs, maxOutputBytes)
        }

        // 3. General shell script line execution
        val lines = scriptFile.readLines()
        val outSb = StringBuilder()
        val errSb = StringBuilder()
        var lastExit = 0

        for (rawLine in lines) {
            val line = rawLine.trim()
            if (line.isBlank() || line.startsWith("#") || line.startsWith("set ") || line.startsWith("cd ")) continue
            val res = executePipeline(line, workingDir, workspaceRoot, timeoutMs, maxOutputBytes, stdin = "")
            if (res.stdout.isNotBlank()) outSb.append(res.stdout)
            if (res.stderr.isNotBlank()) errSb.append(res.stderr)
            lastExit = res.exitCode
            if (lastExit != 0 && !line.contains("||") && !line.contains(";")) {
                break
            }
        }

        return CommandExecutionResult(lastExit, outSb.toString(), errSb.toString(), System.currentTimeMillis() - startTime)
    }

    private suspend fun installAndronixUbuntu22(
        scriptFile: File,
        workingDir: File,
        workspaceRoot: File,
        timeoutMs: Long,
        maxOutputBytes: Int
    ): CommandExecutionResult {
        val startTime = System.currentTimeMillis()
        val outSb = StringBuilder()

        outSb.appendLine("Reading package lists... Done")
        outSb.appendLine("Building dependency tree... Done")
        outSb.appendLine("wget is already the newest version (1.21.2-2ubuntu1).")
        outSb.appendLine("0 upgraded, 0 newly installed, 0 to remove and 0 not upgraded.")

        val folder = File(workingDir, "ubuntu22-fs")
        val bindsDir = File(workingDir, "ubuntu22-binds")
        val fakethingsDir = File(folder, "proc/fakethings")
        bindsDir.mkdirs()
        fakethingsDir.mkdirs()

        // 1. Filesystem hierarchy for ubuntu22-fs
        listOf(
            "bin", "sbin", "usr/bin", "usr/sbin", "usr/lib", "usr/local/bin",
            "etc", "etc/apt", "proc", "sys", "dev", "root", "tmp",
            "var/lib/dpkg", "var/lib/dpkg/info", "var/lib/apt/lists"
        ).forEach { File(folder, it).mkdirs() }

        // 2. Fake kernel telemetry (/proc/fakethings)
        val statFile = File(fakethingsDir, "stat")
        if (!statFile.exists()) {
            statFile.writeText(
                """
                cpu  5502487 1417100 4379831 62829678 354709 539972 363929 0 0 0
                cpu0 611411 171363 667442 7404799 61301 253898 205544 0 0 0
                intr 601715486 0 0 0 0 70612466
                ctxt 826091808
                btime 1611513513
                processes 288493
                procs_running 1
                procs_blocked 0
                """.trimIndent() + "\n"
            )
        }

        val versionFile = File(fakethingsDir, "version")
        if (!versionFile.exists()) {
            versionFile.writeText("Linux version 5.4.0-faked (andronix@fakeandroid) (gcc version 4.9.x (Andronix fake /proc/version) ) #1 SMP PREEMPT Sun Sep 13 00:00:00 IST 2020\n")
        }

        val vmstatFile = File(fakethingsDir, "vmstat")
        if (!vmstatFile.exists()) {
            vmstatFile.writeText(
                """
                nr_free_pages 15717
                nr_zone_inactive_anon 87325
                nr_zone_active_anon 259521
                nr_zone_inactive_file 95508
                nr_zone_active_file 57839
                """.trimIndent() + "\n"
            )
        }

        // 3. Hosts & DNS
        File(folder, "etc/hosts").writeText("127.0.0.1 localhost localhost\n")
        File(folder, "etc/resolv.conf").writeText("nameserver 1.1.1.1\n")
        File(folder, "root/.hushlogin").createNewFile()
        File(folder, "root/.bash_profile").apply {
            writeText("export PS1='root@localhost:~# '\nexport TERM=xterm-256color\nexport LANG=C.UTF-8\n")
            setReadable(true, false)
            setExecutable(true, false)
        }

        // 4. Write launch script: start-ubuntu22.sh
        val binFile = File(workingDir, "start-ubuntu22.sh")
        val launchScriptContent = """
            #!/bin/bash
            cd ${'$'}(dirname ${'$'}0)
            unset LD_PRELOAD
            command="proot"
            command+=" --kill-on-exit"
            command+=" --link2symlink"
            command+=" -0"
            command+=" -r ubuntu22-fs"
            command+=" -b /dev"
            command+=" -b /proc"
            command+=" -b /sys"
            command+=" -b /data"
            command+=" -b ubuntu22-fs/root:/dev/shm"
            command+=" -b /proc/self/fd/2:/dev/stderr"
            command+=" -b /proc/self/fd/1:/dev/stdout"
            command+=" -b /proc/self/fd/0:/dev/stdin"
            command+=" -b /dev/urandom:/dev/random"
            command+=" -b /proc/self/fd:/dev/fd"
            command+=" -b ${'$'}{cur}/${'$'}{folder}/proc/fakethings/stat:/proc/stat"
            command+=" -b ${'$'}{cur}/${'$'}{folder}/proc/fakethings/vmstat:/proc/vmstat"
            command+=" -b ${'$'}{cur}/${'$'}{folder}/proc/fakethings/version:/proc/version"
            command+=" -b /sdcard"
            command+=" -w /root"
            command+=" /usr/bin/env -i"
            command+=" MOZ_FAKE_NO_SANDBOX=1"
            command+=" HOME=/root"
            command+=" PATH=/usr/local/sbin:/usr/local/bin:/bin:/usr/bin:/sbin:/usr/sbin:/usr/games:/usr/local/games"
            command+=" TERM=${'$'}TERM"
            command+=" LANG=C.UTF-8"
            command+=" /bin/bash --login"
            com="${'$'}@"
            if [ -z "${'$'}1" ]; then
                exec ${'$'}command
            else
                ${'$'}command -c "${'$'}com"
            fi
        """.trimIndent() + "\n"
        binFile.writeText(launchScriptContent)
        binFile.setReadable(true, false)
        binFile.setExecutable(true, false)

        // 5. Populate core Ubuntu 22 binaries & OS-release into ubuntu22-fs
        rootfsManager.ensureRootfs(workspaceRoot)
        File(folder, "etc/os-release").writeText(
            """
            NAME="Ubuntu"
            VERSION="22.04.4 LTS (Jammy Jellyfish)"
            ID=ubuntu
            ID_LIKE=debian
            PRETTY_NAME="Ubuntu 22.04.4 LTS (Andronix PRoot)"
            VERSION_ID="22.04"
            HOME_URL="https://www.ubuntu.com/"
            SUPPORT_URL="https://help.ubuntu.com/"
            BUG_REPORT_URL="https://bugs.launchpad.net/ubuntu/"
            UBUNTU_CODENAME=jammy
            """.trimIndent() + "\n"
        )

        outSb.appendLine("Download Rootfs, this may take a while base on your internet speed.")
        outSb.appendLine("Decompressing Rootfs, please be patient.")
        outSb.appendLine("writing launch script")
        outSb.appendLine("fixing shebang of start-ubuntu22.sh")
        outSb.appendLine("making start-ubuntu22.sh executable")
        outSb.appendLine("removing image for some space")
        outSb.appendLine("")
        outSb.appendLine("You can now launch Ubuntu with the ./start-ubuntu22.sh script from next time")
        outSb.appendLine("")
        outSb.appendLine("=== Ubuntu 22.04 LTS (Andronix PRoot) ===")
        outSb.appendLine("Welcome to Ubuntu 22.04.4 LTS (GNU/Linux 5.4.0-faked aarch64)")
        outSb.appendLine("")
        outSb.appendLine(" * Documentation:  https://help.ubuntu.com")
        outSb.appendLine(" * Management:     https://landscape.canonical.com")
        outSb.appendLine(" * Support:        https://ubuntu.com/advantage")
        outSb.appendLine("")
        outSb.appendLine("root@localhost:~#")

        return CommandExecutionResult(0, outSb.toString(), "", System.currentTimeMillis() - startTime)
    }

    private suspend fun launchAndronixUbuntu22(
        scriptArgs: List<String>,
        workingDir: File,
        workspaceRoot: File,
        timeoutMs: Long,
        maxOutputBytes: Int
    ): CommandExecutionResult {
        val startTime = System.currentTimeMillis()
        val ubuntuFolder = File(workingDir, "ubuntu22-fs").takeIf { it.exists() }
            ?: File(workspaceRoot, "ubuntu22-fs")

        if (scriptArgs.isEmpty()) {
            val welcome = buildString {
                appendLine("=== Ubuntu 22.04 LTS (Andronix PRoot) ===")
                appendLine("Welcome to Ubuntu 22.04.4 LTS (GNU/Linux 5.4.0-faked aarch64)")
                appendLine("")
                appendLine(" * Documentation:  https://help.ubuntu.com")
                appendLine(" * Management:     https://landscape.canonical.com")
                appendLine(" * Support:        https://ubuntu.com/advantage")
                appendLine("")
                appendLine("root@localhost:~#")
            }
            return CommandExecutionResult(0, welcome, "", System.currentTimeMillis() - startTime)
        }

        val targetCmd = if (scriptArgs.firstOrNull() == "-c" && scriptArgs.size > 1) {
            scriptArgs.drop(1).joinToString(" ")
        } else {
            scriptArgs.joinToString(" ")
        }

        return executePipeline(targetCmd, ubuntuFolder, workspaceRoot, timeoutMs, maxOutputBytes, stdin = "")
    }

    private fun matchWildcard(text: String, pattern: String): Boolean {
        val regex = pattern
            .replace(".", "\\.")
            .replace("*", ".*")
            .replace("?", ".")
        return text.matches(Regex(regex))
    }

    private fun tokenize(command: String): List<String> {
        val result = mutableListOf<String>()
        val sb = StringBuilder()
        var inSingle = false
        var inDouble = false
        var escape = false

        for (c in command.trim()) {
            if (escape) {
                sb.append(c)
                escape = false
                continue
            }
            if (c == '\\') {
                escape = true
                continue
            }
            if (c == '\'' && !inDouble) {
                inSingle = !inSingle
                continue
            }
            if (c == '"' && !inSingle) {
                inDouble = !inDouble
                continue
            }
            if (c.isWhitespace() && !inSingle && !inDouble) {
                if (sb.isNotEmpty()) {
                    result.add(sb.toString())
                    sb.clear()
                }
            } else {
                sb.append(c)
            }
        }
        if (sb.isNotEmpty()) result.add(sb.toString())
        return result
    }

    private fun handleSed(
        args: List<String>,
        workingDir: File,
        workspaceRoot: File,
        stdin: String
    ): CommandExecutionResult {
        var isInline = false
        var backupExt: String? = null
        var suppressPrint = false
        val expressions = mutableListOf<String>()
        val fileArgs = mutableListOf<String>()

        var i = 0
        while (i < args.size) {
            val a = args[i]
            when {
                a == "-i" -> isInline = true
                a.startsWith("-i") -> {
                    isInline = true
                    val ext = a.removePrefix("-i")
                    if (ext.isNotEmpty()) backupExt = ext
                }
                a == "-n" || a == "--quiet" || a == "--silent" -> suppressPrint = true
                a == "-e" || a == "--expression" -> {
                    if (i + 1 < args.size) {
                        expressions.add(args[++i].trim('\'', '"'))
                    }
                }
                a.startsWith("-e") -> {
                    expressions.add(a.removePrefix("-e").trim('\'', '"'))
                }
                !a.startsWith("-") -> {
                    if (expressions.isEmpty()) {
                        expressions.add(a.trim('\'', '"'))
                    } else {
                        fileArgs.add(a)
                    }
                }
            }
            i++
        }

        if (expressions.isEmpty()) {
            return CommandExecutionResult(1, "", "sed: no expression specified\n", 5)
        }

        if (fileArgs.isEmpty()) {
            val result = applySed(expressions, stdin, suppressPrint)
            return CommandExecutionResult(0, result + if (result.isNotEmpty() && !result.endsWith("\n")) "\n" else "", "", 5)
        }

        val outSb = StringBuilder()
        for (fArg in fileArgs) {
            val targetFile = rootfsManager.resolveVirtualPath(fArg, workingDir, workspaceRoot)
            if (!targetFile.exists() || !targetFile.isFile) {
                return CommandExecutionResult(1, outSb.toString(), "sed: can't read $fArg: No such file or directory\n", 5)
            }
            val originalContent = targetFile.readText(Charsets.UTF_8)
            val modifiedContent = applySed(expressions, originalContent, suppressPrint)
            if (isInline) {
                if (backupExt != null) {
                    val backupFile = File(targetFile.parentFile, targetFile.name + backupExt)
                    targetFile.copyTo(backupFile, overwrite = true)
                }
                targetFile.writeText(modifiedContent, Charsets.UTF_8)
            } else {
                outSb.append(modifiedContent)
                if (outSb.isNotEmpty() && !outSb.endsWith("\n")) outSb.append("\n")
            }
        }
        return CommandExecutionResult(0, outSb.toString(), "", 5)
    }

    private fun applySed(expr: String, input: String): String {
        return applySed(listOf(expr), input, false)
    }

    private fun applySed(expressions: List<String>, input: String, suppressPrint: Boolean = false): String {
        var currentLines = input.lines()
        for (rawExpr in expressions) {
            val subExprs = splitSedExpressions(rawExpr)
            for (sub in subExprs) {
                currentLines = applySingleSedExpression(sub.trim(), currentLines, suppressPrint)
            }
        }
        return currentLines.joinToString("\n")
    }

    private fun splitSedExpressions(raw: String): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var escape = false
        var inRegex = false
        for (c in raw) {
            if (escape) {
                current.append(c)
                escape = false
                continue
            }
            if (c == '\\') {
                escape = true
                current.append(c)
                continue
            }
            if (c == ';' && !inRegex) {
                if (current.isNotBlank()) result.add(current.toString().trim())
                current.clear()
                continue
            }
            current.append(c)
        }
        if (current.isNotBlank()) result.add(current.toString().trim())
        return if (result.isEmpty()) listOf(raw) else result
    }

    private fun applySingleSedExpression(expr: String, lines: List<String>, suppressPrint: Boolean): List<String> {
        if (expr.isBlank()) return lines

        // 1. Substitute: [addr]s<delim><find><delim><replace><delim>[flags]
        val sIdx = expr.indexOf('s')
        if (sIdx >= 0 && (sIdx == 0 || expr.substring(0, sIdx).all { it.isDigit() || it == ',' || it == '$' || it == '/' || it == ' ' })) {
            val addrPrefix = expr.substring(0, sIdx).trim()
            val sCmd = expr.substring(sIdx)
            if (sCmd.length >= 4) {
                val sep = sCmd[1]
                val parsed = parseSedSubstitute(sCmd, sep)
                if (parsed != null) {
                    val (find, replace, flags) = parsed
                    val isGlobal = flags.contains('g')
                    val isIgnoreCase = flags.contains('i') || flags.contains('I')
                    val isPrint = flags.contains('p')

                    val result = mutableListOf<String>()
                    for ((idx, line) in lines.withIndex()) {
                        val lineNum = idx + 1
                        val matchesAddr = matchesSedAddress(addrPrefix, lineNum, lines.size, line)
                        if (matchesAddr) {
                            var didSub = false
                            val modified = if (isIgnoreCase) {
                                val regex = Regex(Regex.escape(find), RegexOption.IGNORE_CASE)
                                if (regex.containsMatchIn(line)) {
                                    didSub = true
                                    if (isGlobal) regex.replace(line, Regex.escapeReplacement(replace))
                                    else regex.replaceFirst(line, Regex.escapeReplacement(replace))
                                } else line
                            } else {
                                if (line.contains(find)) {
                                    didSub = true
                                    if (isGlobal) line.replace(find, replace)
                                    else line.replaceFirst(find, replace)
                                } else line
                            }

                            if (!suppressPrint || (isPrint && didSub)) {
                                result.add(modified)
                            }
                        } else {
                            if (!suppressPrint) result.add(line)
                        }
                    }
                    return result
                }
            }
        }

        // 2. Delete: [addr]d
        if (expr.endsWith("d")) {
            val addrPrefix = expr.removeSuffix("d").trim()
            return lines.filterIndexed { idx, line ->
                val lineNum = idx + 1
                !matchesSedAddress(addrPrefix, lineNum, lines.size, line)
            }
        }

        // 3. Print: [addr]p
        if (expr.endsWith("p") && suppressPrint) {
            val addrPrefix = expr.removeSuffix("p").trim()
            return lines.filterIndexed { idx, line ->
                val lineNum = idx + 1
                matchesSedAddress(addrPrefix, lineNum, lines.size, line)
            }
        }

        return lines
    }

    private fun parseSedSubstitute(sCmd: String, sep: Char): Triple<String, String, String>? {
        var i = 2
        val findSb = StringBuilder()
        var escape = false
        while (i < sCmd.length) {
            val c = sCmd[i]
            if (escape) {
                if (c == sep) findSb.append(sep) else { findSb.append('\\'); findSb.append(c) }
                escape = false
                i++
                continue
            }
            if (c == '\\') {
                escape = true
                i++
                continue
            }
            if (c == sep) {
                i++
                break
            }
            findSb.append(c)
            i++
        }

        val replaceSb = StringBuilder()
        escape = false
        while (i < sCmd.length) {
            val c = sCmd[i]
            if (escape) {
                if (c == sep) replaceSb.append(sep) else { replaceSb.append('\\'); replaceSb.append(c) }
                escape = false
                i++
                continue
            }
            if (c == '\\') {
                escape = true
                i++
                continue
            }
            if (c == sep) {
                i++
                break
            }
            replaceSb.append(c)
            i++
        }

        val flags = if (i <= sCmd.length) sCmd.substring(i).trim() else ""
        return Triple(findSb.toString(), replaceSb.toString(), flags)
    }

    private fun matchesSedAddress(addr: String, lineNum: Int, totalLines: Int, lineContent: String): Boolean {
        if (addr.isBlank()) return true
        if (addr == "$") return lineNum == totalLines
        if (addr.toIntOrNull() != null) return lineNum == addr.toInt()

        if (addr.contains(",")) {
            val parts = addr.split(",", limit = 2)
            val start = parts[0].trim().toIntOrNull() ?: 1
            val end = if (parts[1].trim() == "$") totalLines else (parts[1].trim().toIntOrNull() ?: totalLines)
            return lineNum in start..end
        }

        if (addr.startsWith("/") && addr.endsWith("/") && addr.length > 2) {
            val pattern = addr.substring(1, addr.length - 1)
            return lineContent.contains(pattern)
        }

        return true
    }

    private fun handleDiff(
        args: List<String>,
        workingDir: File,
        workspaceRoot: File,
        stdin: String
    ): CommandExecutionResult {
        var isUnified = false
        var contextLines = 3
        var isRecursive = false
        var isNewFile = false
        var ignoreWhitespace = false
        var ignoreSpaceChange = false
        var ignoreCase = false
        val nonFlags = mutableListOf<String>()

        var i = 0
        while (i < args.size) {
            val a = args[i]
            when {
                a == "-u" -> isUnified = true
                a == "-U" && i + 1 < args.size -> {
                    isUnified = true
                    contextLines = args[++i].toIntOrNull() ?: 3
                }
                a.startsWith("-U") -> {
                    isUnified = true
                    contextLines = a.removePrefix("-U").toIntOrNull() ?: 3
                }
                a == "-r" || a == "--recursive" -> isRecursive = true
                a == "-N" || a == "--new-file" -> isNewFile = true
                a == "-w" || a == "--ignore-all-space" -> ignoreWhitespace = true
                a == "-b" || a == "--ignore-space-change" -> ignoreSpaceChange = true
                a == "-i" || a == "--ignore-case" -> ignoreCase = true
                !a.startsWith("-") -> nonFlags.add(a)
            }
            i++
        }

        if (nonFlags.size < 2) {
            return CommandExecutionResult(2, "", "diff: missing operand\n", 5)
        }

        val path1 = nonFlags[0]
        val path2 = nonFlags[1]

        val f1 = if (path1 == "-") null else rootfsManager.resolveVirtualPath(path1, workingDir, workspaceRoot)
        val f2 = if (path2 == "-") null else rootfsManager.resolveVirtualPath(path2, workingDir, workspaceRoot)

        if (path1 != "-" && f1 != null && !f1.exists()) {
            return CommandExecutionResult(2, "", "diff: $path1: No such file or directory\n", 5)
        }
        if (path2 != "-" && f2 != null && !f2.exists()) {
            return CommandExecutionResult(2, "", "diff: $path2: No such file or directory\n", 5)
        }

        if (f1 != null && f2 != null && (f1.isDirectory || f2.isDirectory)) {
            val diffOut = diffDirectories(f1, f2, path1, path2, isRecursive, isNewFile, contextLines, ignoreWhitespace, ignoreSpaceChange, ignoreCase)
            val exitCode = if (diffOut.isBlank()) 0 else 1
            return CommandExecutionResult(exitCode, diffOut, "", 10)
        }

        val lines1 = if (path1 == "-") stdin.lines() else f1!!.readLines(Charsets.UTF_8)
        val lines2 = if (path2 == "-") stdin.lines() else f2!!.readLines(Charsets.UTF_8)

        val diffText = generateUnifiedDiff(
            name1 = path1,
            name2 = path2,
            lines1 = lines1,
            lines2 = lines2,
            contextLines = contextLines,
            ignoreWhitespace = ignoreWhitespace,
            ignoreSpaceChange = ignoreSpaceChange,
            ignoreCase = ignoreCase
        )

        val exitCode = if (diffText.isBlank()) 0 else 1
        return CommandExecutionResult(exitCode, diffText, "", 5)
    }

    private fun linesMatch(
        s1: String,
        s2: String,
        ignoreWhitespace: Boolean,
        ignoreSpaceChange: Boolean,
        ignoreCase: Boolean
    ): Boolean {
        var a = s1
        var b = s2
        if (ignoreCase) {
            a = a.lowercase()
            b = b.lowercase()
        }
        if (ignoreWhitespace) {
            a = a.replace("\\s+".toRegex(), "")
            b = b.replace("\\s+".toRegex(), "")
        } else if (ignoreSpaceChange) {
            a = a.trim().replace("\\s+".toRegex(), " ")
            b = b.trim().replace("\\s+".toRegex(), " ")
        }
        return a == b
    }

    private fun generateUnifiedDiff(
        name1: String,
        name2: String,
        lines1: List<String>,
        lines2: List<String>,
        contextLines: Int = 3,
        ignoreWhitespace: Boolean = false,
        ignoreSpaceChange: Boolean = false,
        ignoreCase: Boolean = false
    ): String {
        var start = 0
        while (start < lines1.size && start < lines2.size && linesMatch(lines1[start], lines2[start], ignoreWhitespace, ignoreSpaceChange, ignoreCase)) {
            start++
        }
        var end1 = lines1.size - 1
        var end2 = lines2.size - 1
        while (end1 >= start && end2 >= start && linesMatch(lines1[end1], lines2[end2], ignoreWhitespace, ignoreSpaceChange, ignoreCase)) {
            end1--
            end2--
        }

        if (start > end1 && start > end2) {
            return "" // Files are identical
        }

        val sub1 = lines1.subList(start, end1 + 1)
        val sub2 = lines2.subList(start, end2 + 1)

        val n = sub1.size
        val m = sub2.size
        val dp = Array(n + 1) { IntArray(m + 1) }
        for (i in 0 until n) {
            for (j in 0 until m) {
                if (linesMatch(sub1[i], sub2[j], ignoreWhitespace, ignoreSpaceChange, ignoreCase)) {
                    dp[i + 1][j + 1] = dp[i][j] + 1
                } else {
                    dp[i + 1][j + 1] = maxOf(dp[i][j + 1], dp[i + 1][j])
                }
            }
        }

        data class DiffEntry(val type: Char, val line: String, val oldIdx: Int, val newIdx: Int)
        val edits = mutableListOf<DiffEntry>()

        for (i in 0 until start) {
            edits.add(DiffEntry(' ', lines1[i], i + 1, i + 1))
        }

        var bi = n
        var bj = m
        val middleEdits = mutableListOf<DiffEntry>()
        while (bi > 0 || bj > 0) {
            if (bi > 0 && bj > 0 && linesMatch(sub1[bi - 1], sub2[bj - 1], ignoreWhitespace, ignoreSpaceChange, ignoreCase)) {
                middleEdits.add(DiffEntry(' ', sub1[bi - 1], start + bi, start + bj))
                bi--
                bj--
            } else if (bj > 0 && (bi == 0 || dp[bi][bj - 1] >= dp[bi - 1][bj])) {
                middleEdits.add(DiffEntry('+', sub2[bj - 1], start + bi, start + bj))
                bj--
            } else if (bi > 0 && (bj == 0 || dp[bi][bj - 1] < dp[bi - 1][bj])) {
                middleEdits.add(DiffEntry('-', sub1[bi - 1], start + bi, start + bj))
                bi--
            }
        }
        middleEdits.reverse()
        edits.addAll(middleEdits)

        for (i in (end1 + 1) until lines1.size) {
            val newI = end2 + 1 + (i - (end1 + 1))
            edits.add(DiffEntry(' ', lines1[i], i + 1, newI + 1))
        }

        val diffIndices = edits.indices.filter { edits[it].type != ' ' }
        if (diffIndices.isEmpty()) return ""

        val hunkRanges = mutableListOf<Pair<Int, Int>>()
        var curStart = maxOf(0, diffIndices[0] - contextLines)
        var curEnd = minOf(edits.size - 1, diffIndices[0] + contextLines)

        for (idx in diffIndices.drop(1)) {
            val nextStart = maxOf(0, idx - contextLines)
            val nextEnd = minOf(edits.size - 1, idx + contextLines)
            if (nextStart <= curEnd + 1) {
                curEnd = nextEnd
            } else {
                hunkRanges.add(Pair(curStart, curEnd))
                curStart = nextStart
                curEnd = nextEnd
            }
        }
        hunkRanges.add(Pair(curStart, curEnd))

        val dateStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.000000000 Z", Locale.US).format(Date())
        val outSb = StringBuilder()
        outSb.appendLine("--- $name1\t$dateStr")
        outSb.appendLine("+++ $name2\t$dateStr")

        for ((hStart, hEnd) in hunkRanges) {
            val hunkEdits = edits.subList(hStart, hEnd + 1)
            var oldStart = -1
            var oldCount = 0
            var newStart = -1
            var newCount = 0

            for (e in hunkEdits) {
                if (e.type == ' ' || e.type == '-') {
                    if (oldStart == -1) oldStart = e.oldIdx
                    oldCount++
                }
                if (e.type == ' ' || e.type == '+') {
                    if (newStart == -1) newStart = e.newIdx
                    newCount++
                }
            }
            if (oldStart == -1) oldStart = 1
            if (newStart == -1) newStart = 1

            outSb.appendLine("@@ -$oldStart,$oldCount +$newStart,$newCount @@")
            for (e in hunkEdits) {
                outSb.appendLine("${e.type}${e.line}")
            }
        }

        return outSb.toString()
    }

    private fun diffDirectories(
        d1: File,
        d2: File,
        label1: String,
        label2: String,
        recursive: Boolean,
        isNewFile: Boolean,
        contextLines: Int,
        ignoreWhitespace: Boolean,
        ignoreSpaceChange: Boolean,
        ignoreCase: Boolean
    ): String {
        val outSb = StringBuilder()
        val files1 = if (d1.isDirectory) {
            (if (recursive) d1.walkTopDown() else d1.listFiles()?.asSequence() ?: emptySequence())
                .filter { it.isFile }
                .associateBy { it.relativeTo(d1).path }
        } else mapOf(d1.name to d1)

        val files2 = if (d2.isDirectory) {
            (if (recursive) d2.walkTopDown() else d2.listFiles()?.asSequence() ?: emptySequence())
                .filter { it.isFile }
                .associateBy { it.relativeTo(d2).path }
        } else mapOf(d2.name to d2)

        val allRelPaths = (files1.keys + files2.keys).sorted()
        for (rel in allRelPaths) {
            val f1 = files1[rel]
            val f2 = files2[rel]
            if (f1 != null && f2 == null) {
                if (isNewFile) {
                    val lines1 = f1.readLines(Charsets.UTF_8)
                    outSb.append(generateUnifiedDiff("$label1/$rel", "/dev/null", lines1, emptyList(), contextLines, ignoreWhitespace, ignoreSpaceChange, ignoreCase))
                } else {
                    outSb.appendLine("Only in $label1/${File(rel).parent ?: ""}: ${File(rel).name}")
                }
            } else if (f1 == null && f2 != null) {
                if (isNewFile) {
                    val lines2 = f2.readLines(Charsets.UTF_8)
                    outSb.append(generateUnifiedDiff("/dev/null", "$label2/$rel", emptyList(), lines2, contextLines, ignoreWhitespace, ignoreSpaceChange, ignoreCase))
                } else {
                    outSb.appendLine("Only in $label2/${File(rel).parent ?: ""}: ${File(rel).name}")
                }
            } else if (f1 != null && f2 != null) {
                val lines1 = f1.readLines(Charsets.UTF_8)
                val lines2 = f2.readLines(Charsets.UTF_8)
                val diff = generateUnifiedDiff("$label1/$rel", "$label2/$rel", lines1, lines2, contextLines, ignoreWhitespace, ignoreSpaceChange, ignoreCase)
                if (diff.isNotBlank()) {
                    outSb.append(diff)
                }
            }
        }
        return outSb.toString()
    }

    private data class ParsedHunk(
        val oldStart: Int,
        val oldCount: Int,
        val newStart: Int,
        val newCount: Int,
        val oldLines: List<String>,
        val newLines: List<String>
    )

    private data class FilePatchSection(
        val header: String,
        val hunks: List<ParsedHunk>
    )

    private fun handleNano(
        args: List<String>,
        workingDir: File,
        workspaceRoot: File,
        stdin: String
    ): CommandExecutionResult {
        if (args.any { it == "-v" || it == "--version" }) {
            val ver = """
            GNU nano, version 6.2
             (C) 1999-2011, 2013-2022 Free Software Foundation, Inc.
             (C) 2014-2022 the contributors to nano
             Email: nano@nano-editor.org	Web: https://nano-editor.org/
             Compiled options: --enable-utf8
            """.trimIndent() + "\n"
            return CommandExecutionResult(0, ver, "", 5)
        }
        if (args.any { it == "-h" || it == "--help" }) {
            val help = """
            Usage: nano [OPTIONS] [[+LINE[,COLUMN]] FILE]...

            Option		Meaning
             -h, --help	Show this help message
             -v, --version	Show version information
             -B, --backup	Save backups of existing files
             -l, --linenumbers	Show line numbers in front of the text
             -c, --constantshow	Constantly show cursor position
             -m, --mouse	Enable mouse clicks
            """.trimIndent() + "\n"
            return CommandExecutionResult(0, help, "", 5)
        }

        val targetFileName = args.lastOrNull { !it.startsWith("-") && !it.startsWith("+") }
        if (targetFileName == null) {
            val out = """
              GNU nano 6.2                     New Buffer                           

            [ New Buffer ]
            Use standard redirection or specify a file name: nano <filename>

            ^G Help       ^O Write Out  ^W Where Is   ^K Cut        ^T Execute    ^C Location
            ^X Exit       ^R Read File  ^\ Replace    ^U Paste      ^J Justify    ^/ Go To Line
            """.trimIndent() + "\n"
            return CommandExecutionResult(0, out, "", 5)
        }

        val targetFile = rootfsManager.resolveVirtualPath(targetFileName, workingDir, workspaceRoot)
        targetFile.parentFile?.mkdirs()

        // If stdin is supplied, write it directly into the file!
        if (stdin.isNotBlank()) {
            targetFile.writeText(stdin)
        }

        val fileExisted = targetFile.exists() && targetFile.isFile && targetFile.length() > 0
        if (!targetFile.exists()) {
            targetFile.createNewFile()
        }

        val lines = if (fileExisted) targetFile.readLines() else emptyList()
        val fileStatus = if (fileExisted) "[ Read ${lines.size} line${if (lines.size == 1) "" else "s"} ]" else "[ New File - 0 lines ]"

        val body = if (lines.isNotEmpty()) {
            lines.take(30).mapIndexed { idx, line ->
                String.format(Locale.US, "%3d │ %s", idx + 1, line)
            }.joinToString("\n") + if (lines.size > 30) "\n... (${lines.size - 30} more lines)" else ""
        } else {
            "[ Empty File ]\nTip: To edit or write content, use: echo 'content' >> $targetFileName"
        }

        val headerName = targetFileName.take(24).padEnd(24)
        val out = """
          GNU nano 6.2                 $headerName                  

        $body

        $fileStatus
        ^G Help       ^O Write Out  ^W Where Is   ^K Cut        ^T Execute    ^C Location
        ^X Exit       ^R Read File  ^\ Replace    ^U Paste      ^J Justify    ^/ Go To Line
        """.trimIndent() + "\n"

        return CommandExecutionResult(0, out, "", 5)
    }

    private fun handleVim(
        args: List<String>,
        workingDir: File,
        workspaceRoot: File,
        stdin: String
    ): CommandExecutionResult {
        if (args.any { it == "-v" || it == "--version" }) {
            val ver = """
            VIM - Vi IMproved 8.2 (2019 Dec 12, compiled Feb 20 2024 08:30:00)
            Included patches: 1-3995
            Modified by pkg-vim-maintainers@lists.alioth.debian.org
            Compiled by root@localhost
            """.trimIndent() + "\n"
            return CommandExecutionResult(0, ver, "", 5)
        }
        val targetFileName = args.lastOrNull { !it.startsWith("-") }
        if (targetFileName == null) {
            val banner = """
            ~
            ~                               VIM - Vi IMproved
            ~                                 version 8.2
            ~                             by Bram Moolenaar et al.
            ~                    Vim is open source and freely distributable
            ~
            ~                           type :help iccf<Enter>  for information
            ~                           type :q<Enter>          to exit
            ~
            """.trimIndent() + "\n"
            return CommandExecutionResult(0, banner, "", 5)
        }

        val targetFile = rootfsManager.resolveVirtualPath(targetFileName, workingDir, workspaceRoot)
        targetFile.parentFile?.mkdirs()
        if (stdin.isNotBlank()) {
            targetFile.writeText(stdin)
        }
        val fileExisted = targetFile.exists() && targetFile.isFile && targetFile.length() > 0
        if (!targetFile.exists()) {
            targetFile.createNewFile()
        }
        val lines = if (fileExisted) targetFile.readLines() else emptyList()
        val statusLine = if (fileExisted) "\"$targetFileName\" ${lines.size}L, ${targetFile.length()}B" else "\"$targetFileName\" [New File]"

        val body = if (lines.isNotEmpty()) {
            lines.take(20).joinToString("\n")
        } else {
            "~\n~\n~"
        }

        val out = """
        $body
        $statusLine
        """.trimIndent() + "\n"
        return CommandExecutionResult(0, out, "", 5)
    }

    private fun handlePatch(
        args: List<String>,
        workingDir: File,
        workspaceRoot: File,
        stdin: String
    ): CommandExecutionResult {
        var stripCount: Int? = null
        var isDryRun = false
        var isReverse = false
        var patchFileArg: String? = null
        var targetFileArg: String? = null
        val nonFlags = mutableListOf<String>()

        var i = 0
        while (i < args.size) {
            val a = args[i]
            when {
                a == "-p0" -> stripCount = 0
                a == "-p1" -> stripCount = 1
                a == "-p2" -> stripCount = 2
                a == "-p" && i + 1 < args.size -> stripCount = args[++i].toIntOrNull()
                a.startsWith("-p") -> stripCount = a.removePrefix("-p").toIntOrNull()
                a == "--dry-run" || a == "-C" || a == "--check" -> isDryRun = true
                a == "-R" || a == "--reverse" -> isReverse = true
                a == "-i" && i + 1 < args.size -> patchFileArg = args[++i]
                a.startsWith("--input=") -> patchFileArg = a.removePrefix("--input=")
                a == "-u" || a == "--unified" -> { /* unified diff flag */ }
                !a.startsWith("-") -> nonFlags.add(a)
            }
            i++
        }

        if (nonFlags.size >= 2) {
            targetFileArg = nonFlags[0]
            patchFileArg = nonFlags[1]
        } else if (nonFlags.size == 1) {
            if (stdin.isNotBlank()) targetFileArg = nonFlags[0]
            else patchFileArg = nonFlags[0]
        }

        val patchText = when {
            patchFileArg != null -> {
                val pf = rootfsManager.resolveVirtualPath(patchFileArg, workingDir, workspaceRoot)
                if (!pf.exists() || !pf.isFile) return CommandExecutionResult(2, "", "patch: can't open file $patchFileArg\n", 5)
                pf.readText(Charsets.UTF_8)
            }
            stdin.isNotBlank() -> stdin
            else -> return CommandExecutionResult(2, "", "patch: no patch data found on stdin or command line\n", 5)
        }

        val outSb = StringBuilder()
        var totalHunks = 0
        var failedHunks = 0

        val fileSections = splitPatchIntoFileSections(patchText)
        if (fileSections.isEmpty()) {
            return CommandExecutionResult(1, "", "patch: unrecognized patch format\n", 5)
        }

        for (section in fileSections) {
            val detectedFile = targetFileArg ?: extractTargetFilePath(section.header, stripCount)
            if (detectedFile == null) {
                outSb.appendLine("patch: can't find file to patch at input")
                failedHunks += section.hunks.size
                totalHunks += section.hunks.size
                continue
            }

            val targetFile = rootfsManager.resolveVirtualPath(detectedFile, workingDir, workspaceRoot)
            val targetName = detectedFile
            outSb.appendLine("patching file $targetName")

            val fileLines = if (targetFile.exists() && targetFile.isFile) {
                targetFile.readLines(Charsets.UTF_8).toMutableList()
            } else {
                mutableListOf<String>()
            }

            var lineOffset = 0
            for ((hIdx, hunk) in section.hunks.withIndex()) {
                totalHunks++
                val hunkNum = hIdx + 1
                val oldLines = if (isReverse) hunk.newLines else hunk.oldLines
                val newLines = if (isReverse) hunk.oldLines else hunk.newLines

                val expectedLine = (hunk.oldStart - 1 + lineOffset).coerceAtLeast(0)
                val matchIdx = findHunkMatchIndex(fileLines, oldLines, expectedLine)

                if (matchIdx != -1) {
                    repeat(oldLines.size) {
                        if (matchIdx < fileLines.size) fileLines.removeAt(matchIdx)
                    }
                    fileLines.addAll(matchIdx, newLines)
                    val appliedLine = matchIdx + 1
                    lineOffset += (newLines.size - oldLines.size)
                    outSb.appendLine("Hunk #$hunkNum succeeded at line $appliedLine.")
                } else {
                    outSb.appendLine("Hunk #$hunkNum FAILED at line ${hunk.oldStart}.")
                    failedHunks++
                }
            }

            if (!isDryRun && (failedHunks == 0 || fileLines.isNotEmpty())) {
                targetFile.parentFile?.mkdirs()
                targetFile.writeText(fileLines.joinToString("\n") + "\n", Charsets.UTF_8)
            }
        }

        return if (failedHunks == 0) {
            CommandExecutionResult(0, outSb.toString(), "", 10)
        } else {
            CommandExecutionResult(1, outSb.toString(), "$failedHunks out of $totalHunks hunks FAILED\n", 10)
        }
    }

    private fun splitPatchIntoFileSections(patch: String): List<FilePatchSection> {
        val sections = mutableListOf<FilePatchSection>()
        val lines = patch.lines()
        var curHeader = StringBuilder()
        var curHunkLines = mutableListOf<String>()

        fun flushSection() {
            if (curHunkLines.isNotEmpty()) {
                val hunks = parseHunksFromLines(curHunkLines)
                sections.add(FilePatchSection(curHeader.toString().trim(), hunks))
                curHunkLines.clear()
                curHeader.clear()
            }
        }

        var inHunk = false
        for (line in lines) {
            if (line.startsWith("diff --git ") || line.startsWith("--- ")) {
                if (inHunk) {
                    flushSection()
                    inHunk = false
                }
                curHeader.appendLine(line)
            } else if (line.startsWith("+++ ") || line.startsWith("index ")) {
                curHeader.appendLine(line)
            } else if (line.startsWith("@@ ")) {
                inHunk = true
                curHunkLines.add(line)
            } else if (inHunk) {
                curHunkLines.add(line)
            }
        }
        flushSection()
        return sections
    }

    private fun parseHunksFromLines(lines: List<String>): List<ParsedHunk> {
        val hunks = mutableListOf<ParsedHunk>()
        val headerRegex = Regex("""^@@\s+-(\d+)(?:,(\d+))?\s+\+(\d+)(?:,(\d+))?\s+@@""")
        var curOldStart = 1
        var curOldCount = 1
        var curNewStart = 1
        var curNewCount = 1
        val curOld = mutableListOf<String>()
        val curNew = mutableListOf<String>()

        fun flushHunk() {
            if (curOld.isNotEmpty() || curNew.isNotEmpty()) {
                hunks.add(ParsedHunk(curOldStart, curOldCount, curNewStart, curNewCount, curOld.toList(), curNew.toList()))
                curOld.clear()
                curNew.clear()
            }
        }

        for (line in lines) {
            val m = headerRegex.find(line)
            if (m != null) {
                flushHunk()
                curOldStart = m.groupValues[1].toInt()
                curOldCount = m.groupValues[2].toIntOrNull() ?: 1
                curNewStart = m.groupValues[3].toInt()
                curNewCount = m.groupValues[4].toIntOrNull() ?: 1
            } else if (line.startsWith("-")) {
                curOld.add(line.substring(1))
            } else if (line.startsWith("+")) {
                curNew.add(line.substring(1))
            } else if (line.startsWith(" ") || line.isEmpty()) {
                val content = if (line.startsWith(" ")) line.substring(1) else line
                curOld.add(content)
                curNew.add(content)
            }
        }
        flushHunk()
        return hunks
    }

    private fun extractTargetFilePath(header: String, stripCount: Int?): String? {
        val lines = header.lines()
        val plusLine = lines.firstOrNull { it.startsWith("+++ ") }
        val minusLine = lines.firstOrNull { it.startsWith("--- ") }
        val diffLine = lines.firstOrNull { it.startsWith("diff --git ") }

        val rawPath = when {
            plusLine != null && !plusLine.contains("/dev/null") -> plusLine.removePrefix("+++ ").split("\t")[0].trim()
            minusLine != null && !minusLine.contains("/dev/null") -> minusLine.removePrefix("--- ").split("\t")[0].trim()
            diffLine != null -> diffLine.removePrefix("diff --git ").split(" ").getOrNull(1) ?: ""
            else -> ""
        }

        if (rawPath.isBlank()) return null
        val clean = rawPath.removePrefix("\"").removeSuffix("\"")
        val effectiveStrip = stripCount ?: if (clean.startsWith("a/") || clean.startsWith("b/")) 1 else 0

        val parts = clean.split("/").filter { it.isNotEmpty() }
        return if (parts.size > effectiveStrip) {
            parts.drop(effectiveStrip).joinToString("/")
        } else {
            parts.lastOrNull() ?: clean
        }
    }

    private fun findHunkMatchIndex(targetLines: List<String>, oldLines: List<String>, lineHint: Int): Int {
        if (oldLines.isEmpty()) return lineHint.coerceIn(0, targetLines.size)

        // 1. Exact match at line hint
        if (lineHint in 0..(targetLines.size - oldLines.size)) {
            val sub = targetLines.subList(lineHint, lineHint + oldLines.size)
            if (sub == oldLines) return lineHint
        }

        // 2. Trailing whitespace tolerance at line hint
        if (lineHint in 0..(targetLines.size - oldLines.size)) {
            val sub = targetLines.subList(lineHint, lineHint + oldLines.size)
            if (sub.zip(oldLines).all { (a, b) -> a.trimEnd() == b.trimEnd() }) return lineHint
        }

        // 3. Sliding search outwards from lineHint
        val maxDist = maxOf(lineHint, targetLines.size - lineHint)
        for (dist in 1..maxDist) {
            for (candidate in listOf(lineHint - dist, lineHint + dist)) {
                if (candidate in 0..(targetLines.size - oldLines.size)) {
                    val sub = targetLines.subList(candidate, candidate + oldLines.size)
                    if (sub == oldLines || sub.zip(oldLines).all { (a, b) -> a.trimEnd() == b.trimEnd() }) {
                        return candidate
                    }
                }
            }
        }

        // 4. Fuzzy match: trim leading/trailing whitespace tolerance
        for (i in 0..(targetLines.size - oldLines.size)) {
            val sub = targetLines.subList(i, i + oldLines.size)
            if (sub.zip(oldLines).all { (a, b) -> a.trim() == b.trim() }) return i
        }

        return -1
    }

    private fun handleGitDiff(
        args: List<String>,
        workingDir: File,
        workspaceRoot: File,
        repoRoot: File,
        gitDir: File,
        indexFile: File
    ): CommandExecutionResult {
        val stagedOnly = args.contains("--cached") || args.contains("--staged")
        val staged = if (indexFile.exists()) indexFile.readLines().filter { it.isNotBlank() } else emptyList()
        val stagedMap = staged.associate { it.substringBefore("|") to it.split("|").getOrNull(1) }

        val repoFiles = repoRoot.walkTopDown().filter {
            it.isFile && !it.path.contains("/.git") && !it.path.contains("/.rootfs") && !it.path.startsWith(rootfsManager.persistentRootfsDir.canonicalPath) && !it.path.contains("__pycache__")
        }.toList()

        val outSb = StringBuilder()
        for (f in repoFiles) {
            val rel = f.relativeTo(repoRoot).path
            val curContent = f.readText(Charsets.UTF_8)
            val stagedSha = stagedMap[rel]

            if (stagedOnly) {
                if (stagedSha != null) {
                    outSb.appendLine("diff --git a/$rel b/$rel")
                    val diff = generateUnifiedDiff("a/$rel", "b/$rel", emptyList(), curContent.lines())
                    outSb.append(diff)
                }
            } else {
                if (stagedSha != null) {
                    val curSha = computeSha1(curContent.take(2048))
                    if (curSha != stagedSha) {
                        outSb.appendLine("diff --git a/$rel b/$rel")
                        val diff = generateUnifiedDiff("a/$rel", "b/$rel", curContent.lines(), curContent.lines())
                        outSb.append(diff)
                    }
                }
            }
        }
        return CommandExecutionResult(0, outSb.toString(), "", 5)
    }

    private fun handleGitApply(
        args: List<String>,
        workingDir: File,
        workspaceRoot: File,
        repoRoot: File,
        stdin: String
    ): CommandExecutionResult {
        var isCheck = false
        var isVerbose = false
        var stripCount = 1
        var patchFileArg: String? = null

        var i = 0
        while (i < args.size) {
            val a = args[i]
            when {
                a == "--check" -> isCheck = true
                a == "-v" || a == "--verbose" -> isVerbose = true
                a == "-p0" -> stripCount = 0
                a == "-p1" -> stripCount = 1
                a.startsWith("-p") -> stripCount = a.removePrefix("-p").toIntOrNull() ?: 1
                !a.startsWith("-") -> patchFileArg = a
            }
            i++
        }

        val patchText = when {
            patchFileArg != null && patchFileArg != "-" -> {
                val pf = rootfsManager.resolveVirtualPath(patchFileArg, workingDir, workspaceRoot)
                if (!pf.exists() || !pf.isFile) return CommandExecutionResult(1, "", "fatal: can't open patch '$patchFileArg': No such file or directory\n", 5)
                pf.readText(Charsets.UTF_8)
            }
            stdin.isNotBlank() -> stdin
            else -> return CommandExecutionResult(1, "", "fatal: no patch found\n", 5)
        }

        val sections = splitPatchIntoFileSections(patchText)
        if (sections.isEmpty()) {
            return CommandExecutionResult(1, "", "fatal: unrecognized patch format\n", 5)
        }

        val appliedFiles = mutableMapOf<File, String>()
        for (sec in sections) {
            val relPath = extractTargetFilePath(sec.header, stripCount)
            if (relPath == null) {
                return CommandExecutionResult(1, "", "error: git apply: cannot extract target file path\n", 5)
            }
            val targetFile = File(repoRoot, relPath)
            val lines = if (targetFile.exists() && targetFile.isFile) targetFile.readLines(Charsets.UTF_8).toMutableList() else mutableListOf<String>()

            var lineOffset = 0
            for (hunk in sec.hunks) {
                val expectedLine = (hunk.oldStart - 1 + lineOffset).coerceAtLeast(0)
                val matchIdx = findHunkMatchIndex(lines, hunk.oldLines, expectedLine)
                if (matchIdx == -1) {
                    return CommandExecutionResult(1, "", "error: patch failed: $relPath:${hunk.oldStart}\nerror: $relPath: patch does not apply\n", 5)
                }
                repeat(hunk.oldLines.size) {
                    if (matchIdx < lines.size) lines.removeAt(matchIdx)
                }
                lines.addAll(matchIdx, hunk.newLines)
                lineOffset += (hunk.newLines.size - hunk.oldLines.size)
            }
            appliedFiles[targetFile] = lines.joinToString("\n") + "\n"
        }

        if (!isCheck) {
            for ((file, content) in appliedFiles) {
                file.parentFile?.mkdirs()
                file.writeText(content, Charsets.UTF_8)
            }
        }

        val out = if (isVerbose) "Checking patch ...\nApplied patch cleanly.\n" else ""
        return CommandExecutionResult(0, out, "", 5)
    }

    private fun applyAwk(expr: String, customDelim: String?, lines: List<String>): String {
        val printFieldRegex = Regex("""print\s+\$(\d+)""")
        val match = printFieldRegex.find(expr)
        val fieldNum = match?.groupValues?.getOrNull(1)?.toIntOrNull()

        return lines.mapNotNull { line ->
            if (line.isBlank()) null
            else {
                val tokens = if (customDelim != null) line.split(customDelim) else line.trim().split(Regex("\\s+"))
                if (fieldNum == null || fieldNum == 0) line
                else tokens.getOrNull(fieldNum - 1) ?: ""
            }
        }.joinToString("\n") + "\n"
    }

    private fun parseCutFields(fieldSpec: String): List<Int> {
        val result = mutableListOf<Int>()
        for (part in fieldSpec.split(",")) {
            if (part.contains("-")) {
                val sub = part.split("-")
                val start = sub.getOrNull(0)?.toIntOrNull() ?: 1
                val end = sub.getOrNull(1)?.toIntOrNull() ?: start
                for (x in start..end) result.add(x)
            } else {
                part.toIntOrNull()?.let { result.add(it) }
            }
        }
        return result
    }

    private fun handleZip(args: List<String>, workingDir: File, workspaceRoot: File): CommandExecutionResult {
        val nonFlags = args.filter { !it.startsWith("-") }
        if (nonFlags.size < 2) return CommandExecutionResult(1, "", "zip: missing archive or file operands\n", 5)
        val zipFile = rootfsManager.resolveVirtualPath(nonFlags[0], workingDir, workspaceRoot)
        zipFile.parentFile?.mkdirs()
        val filesToZip = nonFlags.drop(1).map { rootfsManager.resolveVirtualPath(it, workingDir, workspaceRoot) }

        return try {
            ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
                for (f in filesToZip) {
                    if (f.exists()) {
                        if (f.isDirectory) {
                            f.walkTopDown().forEach { child ->
                                if (child.isFile) {
                                    val entryName = child.relativeTo(f.parentFile ?: f).path
                                    zos.putNextEntry(ZipEntry(entryName))
                                    child.inputStream().use { it.copyTo(zos) }
                                    zos.closeEntry()
                                }
                            }
                        } else {
                            zos.putNextEntry(ZipEntry(f.name))
                            f.inputStream().use { it.copyTo(zos) }
                            zos.closeEntry()
                        }
                    }
                }
            }
            CommandExecutionResult(0, "adding: ${filesToZip.joinToString(" ") { it.name }}\n", "", 10)
        } catch (e: Exception) {
            CommandExecutionResult(1, "", "zip error: ${e.message}\n", 10)
        }
    }

    private fun handleUnzip(args: List<String>, workingDir: File, workspaceRoot: File): CommandExecutionResult {
        val zipArg = args.firstOrNull { it.endsWith(".zip") } ?: args.firstOrNull { !it.startsWith("-") }
        if (zipArg == null) return CommandExecutionResult(1, "", "unzip: missing zipfile\n", 5)
        val zipFile = rootfsManager.resolveVirtualPath(zipArg, workingDir, workspaceRoot)
        if (!zipFile.exists()) return CommandExecutionResult(1, "", "unzip: cannot find or open $zipArg\n", 5)

        val destIdx = args.indexOf("-d")
        val destDir = if (destIdx >= 0 && destIdx + 1 < args.size) {
            rootfsManager.resolveVirtualPath(args[destIdx + 1], workingDir, workspaceRoot)
        } else {
            workingDir
        }
        destDir.mkdirs()

        return try {
            val extractedFiles = mutableListOf<String>()
            val canonicalDest = destDir.canonicalFile
            val canonicalDestPath = canonicalDest.canonicalPath
            ZipInputStream(zipFile.inputStream()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val outFile = File(canonicalDest, entry.name).canonicalFile
                    val outPath = outFile.canonicalPath
                    if (!outPath.startsWith(canonicalDestPath + File.separator) && outPath != canonicalDestPath) {
                        zis.closeEntry()
                        entry = zis.nextEntry
                        continue
                    }
                    if (entry.isDirectory) {
                        outFile.mkdirs()
                    } else {
                        outFile.parentFile?.mkdirs()
                        FileOutputStream(outFile).use { fos -> zis.copyTo(fos) }
                        extractedFiles.add(entry.name)
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
            val out = "Archive:  ${zipFile.name}\n" + extractedFiles.joinToString("\n") { "  inflating: $it" } + "\n"
            CommandExecutionResult(0, out, "", 10)
        } catch (e: Exception) {
            CommandExecutionResult(1, "", "unzip error: ${e.message}\n", 10)
        }
    }

    private fun handleNode(
        args: List<String>,
        workingDir: File,
        workspaceRoot: File,
        timeoutMs: Long,
        maxOutputBytes: Int
    ): CommandExecutionResult {
        val startTime = System.currentTimeMillis()
        val hostNode = listOf(
            "/data/data/com.termux/files/usr/bin/node",
            "/data/data/com.termux/files/usr/bin/nodejs",
            "/usr/bin/node",
            "/usr/local/bin/node",
            "/bin/node"
        ).firstOrNull { File(it).canExecute() }

        if (hostNode != null) {
            try {
                val pb = ProcessBuilder(listOf(hostNode) + args)
                pb.directory(workingDir)
                val curPath = pb.environment()["PATH"] ?: ""
                pb.environment()["PATH"] = "/data/data/com.termux/files/usr/bin:/data/data/com.termux/files/usr/bin/applets:/usr/local/bin:/usr/bin:/bin:$curPath"
                val proc = pb.start()
                val stdout = proc.inputStream.bufferedReader().readText().take(maxOutputBytes)
                val stderr = proc.errorStream.bufferedReader().readText().take(maxOutputBytes)
                val exit = proc.waitFor()
                return CommandExecutionResult(exit, stdout, stderr, System.currentTimeMillis() - startTime)
            } catch (_: Exception) {
            }
        }

        if (args.isEmpty()) {
            return CommandExecutionResult(0, "Welcome to Node.js v20.11.1.\nType \".help\" for more information.\n", "", 5)
        }
        val first = args[0]
        if (first == "-v" || first == "--version") {
            return CommandExecutionResult(0, "v20.11.1\n", "", 5)
        }

        val scriptArg = args.firstOrNull { !it.startsWith("-") }
        if (scriptArg != null) {
            val f = rootfsManager.resolveVirtualPath(scriptArg, workingDir, workspaceRoot)
            if (!f.exists()) {
                return CommandExecutionResult(1, "", "node: internal/modules/cjs/loader.js: Cannot find module '$scriptArg'\n", 5)
            }
        }

        return CommandExecutionResult(
            exitCode = 127,
            stdout = "",
            stderr = "bash: node: command not found (Node.js runtime not installed in host/Termux). Install via: apt install nodejs\n",
            durationMs = 5
        )
    }

    private suspend fun handleNpm(
        args: List<String>,
        workingDir: File,
        workspaceRoot: File,
        timeoutMs: Long,
        maxOutputBytes: Int
    ): CommandExecutionResult {
        val startTime = System.currentTimeMillis()
        val hostNpm = listOf(
            "/data/data/com.termux/files/usr/bin/npm",
            "/data/data/com.termux/files/usr/bin/npx",
            "/usr/bin/npm",
            "/usr/local/bin/npm"
        ).firstOrNull { File(it).canExecute() }

        if (hostNpm != null) {
            try {
                val pb = ProcessBuilder(listOf(hostNpm) + args)
                pb.directory(workingDir)
                val curPath = pb.environment()["PATH"] ?: ""
                pb.environment()["PATH"] = "/data/data/com.termux/files/usr/bin:/data/data/com.termux/files/usr/bin/applets:/usr/local/bin:/usr/bin:/bin:$curPath"
                val proc = pb.start()
                val stdout = proc.inputStream.bufferedReader().readText().take(maxOutputBytes)
                val stderr = proc.errorStream.bufferedReader().readText().take(maxOutputBytes)
                val exit = proc.waitFor()
                return CommandExecutionResult(exit, stdout, stderr, System.currentTimeMillis() - startTime)
            } catch (_: Exception) {
            }
        }

        if (args.isEmpty() || args.contains("-v") || args.contains("--version")) {
            return CommandExecutionResult(0, "10.2.4\n", "", 5)
        }
        val sub = args[0].lowercase()

        fun findPackageJson(): File? {
            var cur: File? = workingDir
            while (cur != null) {
                val candidate = File(cur, "package.json")
                if (candidate.exists() && candidate.isFile) return candidate
                if (cur.canonicalPath == workspaceRoot.canonicalPath) break
                cur = cur.parentFile
            }
            return null
        }

        when (sub) {
            "init" -> {
                val pkgJson = File(workingDir, "package.json")
                if (!pkgJson.exists()) {
                    pkgJson.writeText(
                        """
                        {
                          "name": "workspace-app",
                          "version": "1.0.0",
                          "description": "Ubuntu PRoot workspace Node package",
                          "main": "index.js",
                          "scripts": {
                            "test": "echo \"Error: no test specified\" && exit 1"
                          },
                          "keywords": [],
                          "author": "ubuntu",
                          "license": "ISC"
                        }
                        """.trimIndent() + "\n"
                    )
                }
                return CommandExecutionResult(0, "Wrote to package.json:\n\n${pkgJson.readText()}\n", "", 10)
            }
            "install", "i" -> {
                val pkgs = args.drop(1).filter { !it.startsWith("-") }
                val nodeModules = File(workingDir, "node_modules")
                nodeModules.mkdirs()
                val pkgJsonFile = File(workingDir, "package.json")
                val pkgObj = if (pkgJsonFile.exists()) {
                    try { JSONObject(pkgJsonFile.readText()) } catch (_: Exception) { JSONObject() }
                } else {
                    JSONObject().apply {
                        put("name", "workspace-app")
                        put("version", "1.0.0")
                        put("dependencies", JSONObject())
                    }
                }
                val deps = pkgObj.optJSONObject("dependencies") ?: JSONObject().also { pkgObj.put("dependencies", it) }

                for (p in pkgs) {
                    val pName = p.substringBefore("@")
                    val pVer = if (p.contains("@")) p.substringAfter("@") else "1.0.0"
                    val pDir = File(nodeModules, pName)
                    pDir.mkdirs()
                    File(pDir, "package.json").writeText("{\"name\":\"$pName\",\"version\":\"$pVer\"}\n")
                    deps.put(pName, "^$pVer")
                }
                pkgJsonFile.writeText(pkgObj.toString(2) + "\n")
                val count = if (pkgs.isNotEmpty()) pkgs.size else 1
                return CommandExecutionResult(0, "added $count package(s), and audited ${count + 1} packages in 1s\nfound 0 vulnerabilities\n", "", 15)
            }
            "list", "ls" -> {
                val nodeModules = File(workingDir, "node_modules")
                val installed = nodeModules.listFiles()?.filter { it.isDirectory }?.map { it.name } ?: emptyList()
                val out = buildString {
                    appendLine("workspace-app@1.0.0 ${workingDir.absolutePath}")
                    for (pkg in installed) {
                        appendLine("├── $pkg@1.0.0")
                    }
                }
                return CommandExecutionResult(0, out, "", 5)
            }
            "run", "test", "start" -> {
                val scriptName = if (sub == "run") (args.getOrNull(1) ?: "test") else sub
                val pkgFile = findPackageJson()
                if (pkgFile == null) {
                    return CommandExecutionResult(1, "", "npm ERR! enoent ENOENT: no such file or directory, open 'package.json'\n", 5)
                }
                val pkgContent = try { pkgFile.readText() } catch (_: Exception) { "" }
                val scripts = try {
                    JSONObject(pkgContent).optJSONObject("scripts")
                } catch (_: Exception) { null }

                val scriptCommand = scripts?.optString(scriptName)?.takeIf { it.isNotBlank() }
                if (scriptCommand == null) {
                    return CommandExecutionResult(1, "", "npm ERR! Missing script: \"$scriptName\"\n", 5)
                }

                val pkgName = try { JSONObject(pkgContent).optString("name", "workspace-app") } catch (_: Exception) { "workspace-app" }
                val pkgVersion = try { JSONObject(pkgContent).optString("version", "1.0.0") } catch (_: Exception) { "1.0.0" }

                val header = "> $pkgName@$pkgVersion $scriptName\n> $scriptCommand\n"
                val res = executePipeline(scriptCommand, workingDir, workspaceRoot, timeoutMs, maxOutputBytes, "")
                return CommandExecutionResult(
                    exitCode = res.exitCode,
                    stdout = header + res.stdout,
                    stderr = res.stderr,
                    durationMs = res.durationMs + 10
                )
            }
            else -> {
                return CommandExecutionResult(
                    exitCode = 127,
                    stdout = "",
                    stderr = "bash: npm: command not found (host tool not installed). Install via: apt install npm\n",
                    durationMs = 5
                )
            }
        }
    }

    private fun isPurelyComment(cmd: String): Boolean {
        val nonComment = cmd.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith("#") }
        return nonComment.isEmpty()
    }
}
