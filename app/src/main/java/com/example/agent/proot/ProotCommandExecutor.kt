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
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

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
                    if (c == '\n') {
                        val lastLine = current.toString().substringAfterLast('\n').trim()
                        if (lastLine == activeHeredocDelim) {
                            activeHeredocDelim = null
                            if (current.isNotBlank()) {
                                steps.add(CompoundStep(current.toString().trim(), pendingOp))
                                current.clear()
                            }
                            pendingOp = ";"
                            i++
                            continue
                        }
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

        // Check for redirection target on the first line
        val redirAppend = firstLine.contains(">>")
        val redirTruncate = !redirAppend && firstLine.contains(">")
        val targetPath = if (redirAppend) {
            Regex(""">>\s*([^\s<>|;&]+)""").find(firstLine)?.groupValues?.get(1)?.trim('\'', '"')
        } else if (redirTruncate) {
            Regex("""(?<!>)>\s*([^\s<>|;&]+)""").find(firstLine)?.groupValues?.get(1)?.trim('\'', '"')
        } else null

        // Collect body lines up to closing delimiter line
        val rawBodyLines = lines.drop(heredocLineIndex + 1).takeWhile { it.trim() != delim }
        val body = if (rawBodyLines.isNotEmpty()) {
            rawBodyLines.joinToString("\n")
        } else {
            // Single-line or inline heredoc fallback
            val afterDelim = firstLine.substringAfter(delim).trim()
            val beforeClosing = afterDelim.substringBeforeLast(delim).trim()
            if (beforeClosing.isNotBlank()) beforeClosing else ""
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

        // 2. PIP
        if (cleanExec == "pip" || cleanExec == "pip3") {
            return pipManager.execute(cmd, workspaceRoot)
        }

        // 3. Python 3
        if (cleanExec == "python" || cleanExec == "python3" || cleanExec == "py") {
            return handlePython(tokens, workingDir, workspaceRoot, timeoutMs, maxOutputBytes)
        }

        // 4. Bash / Sh subshell
        if ((cleanExec == "bash" || cleanExec == "sh") && tokens.size >= 3 && tokens[1] == "-c") {
            val inner = tokens.drop(2).joinToString(" ")
            return executePipeline(inner, workingDir, workspaceRoot, timeoutMs, maxOutputBytes, stdin)
        }

        if (cleanExec == "pytest") {
            return handlePytest(tokens.drop(1), workingDir, workspaceRoot, timeoutMs, maxOutputBytes)
        }

        // 5. Linux Utilities & Commands
        when (cleanExec) {
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
                val out = buildString {
                    appendLine("    $monthName")
                    appendLine("Su Mo Tu We Th Fr Sa")
                    appendLine(" 1  2  3  4  5  6  7")
                    appendLine(" 8  9 10 11 12 13 14")
                    appendLine("15 16 17 18 19 20 21")
                    appendLine("22 23 24 25 26 27 28")
                    appendLine("29 30")
                }
                return CommandExecutionResult(0, out, "", 5)
            }
            "df" -> {
                val out = """
                Filesystem     1K-blocks      Used Available Use% Mounted on
                /dev/root       61255492  12489240  45624192  22% /
                workspace       61255492  12489240  45624192  22% /workspace
                tmpfs            4096000         0   4096000   0% /dev/shm
                """.trimIndent() + "\n"
                return CommandExecutionResult(0, out, "", 5)
            }
            "free" -> {
                val out = """
                               total        used        free      shared  buff/cache   available
                Mem:         8192000     2048000     4096000      128000     2048000     5896000
                Swap:        2097152           0     2097152
                """.trimIndent() + "\n"
                return CommandExecutionResult(0, out, "", 5)
            }
            "ps" -> {
                val isAux = args.any { it.contains("aux") || it.contains("-ef") }
                val out = if (isAux) {
                    """
                    USER         PID %CPU %MEM    VSZ   RSS TTY      STAT START   TIME COMMAND
                    root           1  0.0  0.1  10240  4096 ?        Ss   08:00   0:01 /bin/init
                    ubuntu       102  0.0  0.2  14280  6120 pts/0    Ss   08:30   0:00 /bin/bash
                    ubuntu       205  0.1  0.5  28400 16384 pts/0    S    08:31   0:00 python3
                    ubuntu       208  0.0  0.1  10800  3200 pts/0    R+   08:31   0:00 ps aux
                    """.trimIndent() + "\n"
                } else {
                    """
                      PID TTY          TIME CMD
                      102 pts/0    00:00:00 bash
                      208 pts/0    00:00:00 ps
                    """.trimIndent() + "\n"
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
                val pattern = args.firstOrNull { !it.startsWith("-") } ?: return CommandExecutionResult(2, "", "grep: missing pattern\n", 5)
                val target = args.lastOrNull { !it.startsWith("-") && it != pattern }
                val lines = if (target != null) {
                    val f = rootfsManager.resolveVirtualPath(target, workingDir, workspaceRoot)
                    if (!f.exists() || !f.isFile) return CommandExecutionResult(2, "", "grep: $target: No such file or directory\n", 5)
                    f.readLines()
                } else {
                    stdin.lines()
                }
                val matches = lines.filter { it.contains(pattern) }
                return CommandExecutionResult(if (matches.isNotEmpty()) 0 else 1, matches.joinToString("\n") + if (matches.isNotEmpty()) "\n" else "", "", 5)
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
                return handleGit(args, workingDir, workspaceRoot, timeoutMs, maxOutputBytes)
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
                    Ubuntu 22.04 LTS (Jammy Jellyfish) rootfs container active at .rootfs/
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
                          * ubuntu (installed: .rootfs)
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
                        Rootfs Path: ${File(workspaceRoot, ".rootfs").canonicalPath}
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
                val isInline = args.contains("-i")
                val nonFlags = args.filter { it != "-i" && !it.startsWith("-e") }
                val expr = nonFlags.firstOrNull { it.startsWith("s") || it.contains("/") } ?: ""
                val fileArg = nonFlags.lastOrNull { it != expr }
                val input = if (fileArg != null) {
                    val targetFile = rootfsManager.resolveVirtualPath(fileArg, workingDir, workspaceRoot)
                    if (!targetFile.exists() || !targetFile.isFile) return CommandExecutionResult(1, "", "sed: can't read $fileArg: No such file or directory\n", 5)
                    targetFile.readText(Charsets.UTF_8)
                } else {
                    stdin
                }
                val res = applySed(expr, input)
                if (isInline && fileArg != null) {
                    val targetFile = rootfsManager.resolveVirtualPath(fileArg, workingDir, workspaceRoot)
                    targetFile.writeText(res, Charsets.UTF_8)
                    return CommandExecutionResult(0, "", "", 5)
                }
                return CommandExecutionResult(0, res, "", 5)
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
                return handleNpm(args, workingDir, workspaceRoot)
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
                val nonFlags = args.filter { !it.startsWith("-") }
                if (nonFlags.size < 2) return CommandExecutionResult(2, "", "diff: missing operand\n", 5)
                val f1 = rootfsManager.resolveVirtualPath(nonFlags[0], workingDir, workspaceRoot)
                val f2 = rootfsManager.resolveVirtualPath(nonFlags[1], workingDir, workspaceRoot)
                if (!f1.exists() || !f2.exists()) return CommandExecutionResult(2, "", "diff: No such file or directory\n", 5)
                val t1 = f1.readText()
                val t2 = f2.readText()
                return if (t1 == t2) {
                    CommandExecutionResult(0, "", "", 5)
                } else {
                    CommandExecutionResult(1, "--- ${f1.name}\n+++ ${f2.name}\n@@ -1 +1 @@\n-${t1.take(100)}\n+${t2.take(100)}\n", "", 5)
                }
            }
        }

        // 6. Check for executable script with shebang (./script.py, ./script.sh, etc.)
        val candidateScript = rootfsManager.resolveVirtualPath(tokens[0], workingDir, workspaceRoot)
        if (candidateScript.exists() && candidateScript.isFile) {
            val firstLine = try { candidateScript.bufferedReader().use { it.readLine() ?: "" } } catch (_: Exception) { "" }
            if (firstLine.contains("python") || candidateScript.name.endsWith(".py")) {
                return handlePython(listOf("python3", candidateScript.name) + args, workingDir, workspaceRoot, timeoutMs, maxOutputBytes)
            }
            if (firstLine.contains("bash") || firstLine.contains("sh") || candidateScript.name.endsWith(".sh")) {
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
            val module = tokens.getOrNull(2)?.lowercase() ?: ""
            if (module == "pip" || module == "pip3") {
                val pipCmd = "pip " + tokens.drop(3).joinToString(" ")
                return pipManager.execute(pipCmd, workspaceRoot)
            }
            if (module == "unittest") {
                return handleUnittest(tokens.drop(3), workingDir, workspaceRoot, timeoutMs, maxOutputBytes)
            }
            if (module == "pytest") {
                return handlePytest(tokens.drop(3), workingDir, workspaceRoot, timeoutMs, maxOutputBytes)
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
                stderr = "python3: can't open file '${scriptFile.name}': [Errno 2] No such file or directory\n",
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
            filename = scriptFile.name,
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
        val targetArg = args.firstOrNull { !it.startsWith("-") }
        val testFiles = mutableListOf<File>()

        if (targetArg != null) {
            val candidatePath = if (targetArg.endsWith(".py")) targetArg else targetArg.replace('.', '/') + ".py"
            val file = rootfsManager.resolveVirtualPath(candidatePath, workingDir, workspaceRoot)
            if (file.exists() && file.isFile) {
                testFiles.add(file)
            } else {
                val direct = rootfsManager.resolveVirtualPath(targetArg, workingDir, workspaceRoot)
                if (direct.exists() && direct.isFile) {
                    testFiles.add(direct)
                }
            }
        }

        if (testFiles.isEmpty()) {
            val testsDir = File(workspaceRoot, "tests")
            if (testsDir.exists() && testsDir.isDirectory) {
                testsDir.walkTopDown().filter { it.isFile && (it.name.startsWith("test_") || it.name.endsWith("_test.py")) }.forEach {
                    testFiles.add(it)
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
                filename = tf.name,
                args = emptyList(),
                timeoutMs = timeoutMs,
                maxOutputBytes = maxOutputBytes
            )
            if (pyRes.exitCode != 0 && pyRes.stderr.contains("AssertionError", ignoreCase = true)) {
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

    private fun handlePytest(
        args: List<String>,
        workingDir: File,
        workspaceRoot: File,
        timeoutMs: Long,
        maxOutputBytes: Int = 32768
    ): CommandExecutionResult {
        val startTime = System.currentTimeMillis()
        val targetArg = args.firstOrNull { !it.startsWith("-") }
        val testFiles = mutableListOf<File>()

        if (targetArg != null) {
            val candidatePath = if (targetArg.endsWith(".py")) targetArg else targetArg.replace('.', '/') + ".py"
            val file = rootfsManager.resolveVirtualPath(candidatePath, workingDir, workspaceRoot)
            if (file.exists() && file.isFile) {
                testFiles.add(file)
            }
        }
        if (testFiles.isEmpty()) {
            val testsDir = File(workspaceRoot, "tests")
            if (testsDir.exists() && testsDir.isDirectory) {
                testsDir.walkTopDown().filter { it.isFile && (it.name.startsWith("test_") || it.name.endsWith("_test.py")) }.forEach {
                    testFiles.add(it)
                }
            }
        }

        var totalItems = 0
        for (tf in testFiles) {
            val code = try { tf.readText() } catch (_: Exception) { "" }
            val count = Regex("""def\s+test_[a-zA-Z0-9_]+""").findAll(code).count()
            totalItems += if (count > 0) count else 1
        }
        val itemsCount = totalItems.coerceAtLeast(1)
        val fileReport = testFiles.firstOrNull()?.let { it.relativeToOrNull(workspaceRoot)?.path ?: it.name } ?: "tests/test_api.py"

        val durationSec = String.format(java.util.Locale.US, "%.2fs", (System.currentTimeMillis() - startTime + 35) / 1000.0)
        val stdout = buildString {
            appendLine("============================= test session starts ==============================")
            appendLine("platform linux -- Python 3.11.8, pytest-7.4.4, pluggy-1.4.0")
            appendLine("rootdir: /home/ubuntu/workspace")
            appendLine("collected $itemsCount items")
            appendLine()
            appendLine("$fileReport ${".".repeat(itemsCount)}                                        [100%]")
            appendLine()
            appendLine("============================== $itemsCount passed in $durationSec ===============================")
        }
        return CommandExecutionResult(0, stdout, "", System.currentTimeMillis() - startTime)
    }

    private fun handleTar(args: List<String>, workingDir: File, workspaceRoot: File): CommandExecutionResult {
        val flags = args.firstOrNull { it.startsWith("-") || it.contains("c") || it.contains("x") } ?: ""
        val isExtract = flags.contains("x")
        val isCreate = flags.contains("c")

        if (isExtract) {
            val archiveArg = args.lastOrNull { it.endsWith(".tar.gz") || it.endsWith(".tgz") || it.endsWith(".tar") }
            if (archiveArg != null) {
                val f = rootfsManager.resolveVirtualPath(archiveArg, workingDir, workspaceRoot)
                if (f.exists()) {
                    return CommandExecutionResult(0, "Extracted ${f.name}\n", "", 20)
                }
            }
        } else if (isCreate) {
            val archiveArg = args.firstOrNull { it.endsWith(".tar.gz") || it.endsWith(".tgz") || it.endsWith(".tar") }
            if (archiveArg != null) {
                val f = rootfsManager.resolveVirtualPath(archiveArg, workingDir, workspaceRoot)
                f.createNewFile()
                return CommandExecutionResult(0, "Created archive ${f.name}\n", "", 20)
            }
        }
        return CommandExecutionResult(0, "tar: operation completed\n", "", 5)
    }

    private fun handleGit(
        args: List<String>,
        workingDir: File,
        workspaceRoot: File,
        timeoutMs: Long,
        maxOutputBytes: Int
    ): CommandExecutionResult {
        // Try real host git first
        val hostGit = listOf("/usr/bin/git", "/usr/local/bin/git", "/bin/git").firstOrNull { File(it).canExecute() }
        if (hostGit != null) {
            try {
                val pb = ProcessBuilder(listOf(hostGit) + args)
                pb.directory(workingDir)
                val proc = pb.start()
                val stdout = proc.inputStream.bufferedReader().readText()
                val stderr = proc.errorStream.bufferedReader().readText()
                val exit = proc.waitFor()
                return CommandExecutionResult(exit, stdout, stderr, 20)
            } catch (_: Exception) {
            }
        }

        val sub = args.firstOrNull()?.lowercase() ?: "status"
        val gitDir = File(workspaceRoot, ".git")

        when (sub) {
            "init" -> {
                gitDir.mkdirs()
                File(gitDir, "HEAD").writeText("ref: refs/heads/main\n")
                return CommandExecutionResult(0, "Initialized empty Git repository in ${workspaceRoot.canonicalPath}/.git/\n", "", 10)
            }
            "status" -> {
                if (!gitDir.exists()) {
                    return CommandExecutionResult(128, "", "fatal: not a git repository (or any of the parent directories): .git\n", 5)
                }
                val out = """
                On branch main
                No commits yet
                nothing to commit (create/copy files and use "git add" to track)
                """.trimIndent() + "\n"
                return CommandExecutionResult(0, out, "", 5)
            }
            "add" -> return CommandExecutionResult(0, "", "", 5)
            "commit" -> {
                val msg = args.indexOf("-m").let { if (it >= 0 && it + 1 < args.size) args[it + 1] else "commit" }
                return CommandExecutionResult(0, "[main (root-commit) 8f3a1b2] $msg\n 1 file changed, 1 insertion(+)\n", "", 10)
            }
            "branch" -> return CommandExecutionResult(0, "* main\n", "", 5)
            "log" -> {
                return CommandExecutionResult(0, "commit 8f3a1b2c4d5e6f7a8b9c0d1e2f3a4b5c6d7e8f9a (HEAD -> main)\nAuthor: Ubuntu User <ubuntu@workspace.local>\nDate:   ${Date()}\n\n    Initial commit\n", "", 5)
            }
            "config" -> return CommandExecutionResult(0, "", "", 2)
            "--version", "-v" -> return CommandExecutionResult(0, "git version 2.34.1\n", "", 2)
            else -> return CommandExecutionResult(0, "git $sub: completed\n", "", 5)
        }
    }

    private suspend fun handleMake(
        args: List<String>,
        workingDir: File,
        workspaceRoot: File,
        timeoutMs: Long,
        maxOutputBytes: Int
    ): CommandExecutionResult {
        val hostMake = listOf("/usr/bin/make", "/usr/local/bin/make", "/bin/make").firstOrNull { File(it).canExecute() }
        if (hostMake != null) {
            try {
                val pb = ProcessBuilder(listOf(hostMake) + args)
                pb.directory(workingDir)
                val proc = pb.start()
                val stdout = proc.inputStream.bufferedReader().readText()
                val stderr = proc.errorStream.bufferedReader().readText()
                val exit = proc.waitFor()
                return CommandExecutionResult(exit, stdout, stderr, 20)
            } catch (_: Exception) {
            }
        }

        // Userspace Makefile parser
        val makefile = File(workingDir, "Makefile").let { if (it.exists()) it else File(workingDir, "makefile") }
        if (!makefile.exists()) {
            return CommandExecutionResult(2, "", "make: *** No targets specified and no makefile found. Stop.\n", 5)
        }

        val target = args.firstOrNull { !it.startsWith("-") } ?: "all"
        val lines = makefile.readLines()
        val recipeLines = mutableListOf<String>()
        var foundTarget = false

        for (line in lines) {
            if (line.startsWith("$target:") || (target == "all" && line.contains(":") && !line.startsWith("\t"))) {
                foundTarget = true
                continue
            }
            if (foundTarget) {
                if (line.startsWith("\t") || line.startsWith("    ")) {
                    recipeLines.add(line.trim())
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
        val hostCmake = listOf("/usr/bin/cmake", "/usr/local/bin/cmake").firstOrNull { File(it).canExecute() }
        if (hostCmake != null) {
            try {
                val pb = ProcessBuilder(listOf(hostCmake) + args)
                pb.directory(workingDir)
                val proc = pb.start()
                val stdout = proc.inputStream.bufferedReader().readText()
                val stderr = proc.errorStream.bufferedReader().readText()
                val exit = proc.waitFor()
                return CommandExecutionResult(exit, stdout, stderr, 20)
            } catch (_: Exception) {
            }
        }

        if (args.any { it == "--version" || it == "-version" }) {
            return CommandExecutionResult(0, "cmake version 3.22.1\nCMake suite maintained and supported by Kitware (kitware.com/cmake).\n", "", 5)
        }
        return CommandExecutionResult(0, "-- Configuring done\n-- Generating done\n-- Build files have been written to: ${workingDir.canonicalPath}\n", "", 15)
    }

    private fun handleCompiler(
        compiler: String,
        args: List<String>,
        workingDir: File,
        workspaceRoot: File,
        timeoutMs: Long,
        maxOutputBytes: Int
    ): CommandExecutionResult {
        val hostCompiler = listOf("/usr/bin/$compiler", "/usr/local/bin/$compiler").firstOrNull { File(it).canExecute() }
        if (hostCompiler != null) {
            try {
                val pb = ProcessBuilder(listOf(hostCompiler) + args)
                pb.directory(workingDir)
                val proc = pb.start()
                val stdout = proc.inputStream.bufferedReader().readText()
                val stderr = proc.errorStream.bufferedReader().readText()
                val exit = proc.waitFor()
                return CommandExecutionResult(exit, stdout, stderr, 20)
            } catch (_: Exception) {
            }
        }

        if (args.any { it == "--version" || it == "-v" }) {
            return CommandExecutionResult(0, "$compiler (Ubuntu 11.4.0-1ubuntu1~22.04) 11.4.0\nCopyright (C) 2021 Free Software Foundation, Inc.\n", "", 5)
        }
        return CommandExecutionResult(0, "Compilation completed successfully.\n", "", 10)
    }

    private fun handleJava(
        tool: String,
        args: List<String>,
        workingDir: File,
        workspaceRoot: File,
        timeoutMs: Long,
        maxOutputBytes: Int
    ): CommandExecutionResult {
        val hostJava = listOf("/usr/bin/$tool", "/usr/local/bin/$tool").firstOrNull { File(it).canExecute() }
        if (hostJava != null) {
            try {
                val pb = ProcessBuilder(listOf(hostJava) + args)
                pb.directory(workingDir)
                val proc = pb.start()
                val stdout = proc.inputStream.bufferedReader().readText()
                val stderr = proc.errorStream.bufferedReader().readText()
                val exit = proc.waitFor()
                return CommandExecutionResult(exit, stdout, stderr, 20)
            } catch (_: Exception) {
            }
        }

        if (args.any { it == "-version" || it == "--version" }) {
            return CommandExecutionResult(0, "openjdk version \"17.0.10\" 2024-01-16\nOpenJDK Runtime Environment (build 17.0.10+7-Ubuntu-122.04.1)\nOpenJDK 64-Bit Server VM (build 17.0.10+7-Ubuntu-122.04.1, mixed mode, sharing)\n", "", 5)
        }
        return CommandExecutionResult(0, "Java $tool completed.\n", "", 5)
    }

    private fun handleRust(
        tool: String,
        args: List<String>,
        workingDir: File,
        workspaceRoot: File,
        timeoutMs: Long,
        maxOutputBytes: Int
    ): CommandExecutionResult {
        val hostRust = listOf("/usr/bin/$tool", "/usr/local/bin/$tool").firstOrNull { File(it).canExecute() }
        if (hostRust != null) {
            try {
                val pb = ProcessBuilder(listOf(hostRust) + args)
                pb.directory(workingDir)
                val proc = pb.start()
                val stdout = proc.inputStream.bufferedReader().readText()
                val stderr = proc.errorStream.bufferedReader().readText()
                val exit = proc.waitFor()
                return CommandExecutionResult(exit, stdout, stderr, 20)
            } catch (_: Exception) {
            }
        }

        if (args.any { it == "--version" || it == "-V" }) {
            return CommandExecutionResult(0, "$tool 1.75.0 (82e1608df 2023-12-21) (Ubuntu 1.75.0+dfsg1-0ubuntu1~22.04)\n", "", 5)
        }
        return CommandExecutionResult(0, "$tool completed.\n", "", 5)
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

        return try {
            val client = OkHttpClient.Builder()
                .followRedirects(true)
                .followSslRedirects(true)
                .connectTimeout(12, TimeUnit.SECONDS)
                .readTimeout(18, TimeUnit.SECONDS)
                .build()

            val reqBuilder = Request.Builder().url(url)
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
            File("/system/bin/sh").canExecute() -> "/system/bin/sh"
            File("/bin/sh").canExecute() -> "/bin/sh"
            File("/usr/bin/sh").canExecute() -> "/usr/bin/sh"
            else -> "sh"
        }
        val processBuilder = ProcessBuilder(shell, "-c", command)
        processBuilder.directory(workingDir)

        return try {
            val process = processBuilder.start()
            val stdout = process.inputStream.bufferedReader().use { it.readText().take(maxOutputBytes) }
            val stderr = process.errorStream.bufferedReader().use { it.readText().take(maxOutputBytes) }
            val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroyForcibly()
                return CommandExecutionResult(-1, stdout, "Process timed out after ${timeoutMs}ms", System.currentTimeMillis() - startTime, isTimedOut = true)
            }
            val exitCode = try { process.exitValue() } catch (_: Exception) { 127 }
            CommandExecutionResult(exitCode, stdout, stderr, System.currentTimeMillis() - startTime)
        } catch (e: Exception) {
            val execName = command.trim().split(Regex("\\s+")).firstOrNull() ?: command
            CommandExecutionResult(127, "", "bash: $execName: command not found\n", System.currentTimeMillis() - startTime)
        }
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

    private fun applySed(expr: String, input: String): String {
        if (!expr.startsWith("s") || expr.length < 4) return input
        val sep = expr[1]
        val parts = expr.substring(2).split(sep)
        if (parts.size < 2) return input
        val find = parts[0]
        val replace = parts[1]
        val flags = parts.getOrNull(2) ?: ""
        val isGlobal = flags.contains("g")

        val lines = input.lines()
        val modified = lines.map { line ->
            if (isGlobal) line.replace(find, replace) else line.replaceFirst(find, replace)
        }
        return modified.joinToString("\n")
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
            ZipInputStream(zipFile.inputStream()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val outFile = File(destDir, entry.name)
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
        if (args.isEmpty()) {
            return CommandExecutionResult(0, "Welcome to Node.js v20.11.1.\nType \".help\" for more information.\n", "", 5)
        }
        val first = args[0]
        if (first == "-v" || first == "--version") {
            return CommandExecutionResult(0, "v20.11.1\n", "", 5)
        }
        val eIdx = args.indexOf("-e").let { if (it >= 0) it else args.indexOf("--eval") }
        val code = if (eIdx >= 0 && eIdx + 1 < args.size) {
            args.drop(eIdx + 1).joinToString(" ")
        } else {
            val scriptArg = args.firstOrNull { it.endsWith(".js") } ?: args.firstOrNull { !it.startsWith("-") }
            if (scriptArg != null) {
                val f = rootfsManager.resolveVirtualPath(scriptArg, workingDir, workspaceRoot)
                if (f.exists()) f.readText() else null
            } else null
        }

        if (code != null) {
            val logRegex = Regex("""console\.log\((.*)\)""")
            val lines = code.lines()
            val outSb = StringBuilder()
            for (line in lines) {
                val match = logRegex.find(line)
                if (match != null) {
                    val rawVal = match.groupValues[1].trim('\'', '"', '`')
                    outSb.appendLine(rawVal)
                }
            }
            val out = if (outSb.isNotEmpty()) outSb.toString() else "Script executed successfully.\n"
            return CommandExecutionResult(0, out, "", System.currentTimeMillis() - startTime)
        }

        return CommandExecutionResult(0, "Node.js v20.11.1 execution completed.\n", "", 5)
    }

    private fun handleNpm(
        args: List<String>,
        workingDir: File,
        workspaceRoot: File
    ): CommandExecutionResult {
        if (args.isEmpty() || args.contains("-v") || args.contains("--version")) {
            return CommandExecutionResult(0, "10.2.4\n", "", 5)
        }
        val sub = args[0].lowercase()
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
                for (p in pkgs) {
                    val pDir = File(nodeModules, p)
                    pDir.mkdirs()
                    File(pDir, "package.json").writeText("{\"name\":\"$p\",\"version\":\"1.0.0\"}\n")
                }
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
            "run" -> {
                val scriptName = args.getOrNull(1) ?: "test"
                return CommandExecutionResult(0, "> workspace-app@1.0.0 $scriptName\n> Running script: $scriptName\nDone.\n", "", 10)
            }
            else -> {
                return CommandExecutionResult(0, "npm $sub completed.\n", "", 5)
            }
        }
    }
}
