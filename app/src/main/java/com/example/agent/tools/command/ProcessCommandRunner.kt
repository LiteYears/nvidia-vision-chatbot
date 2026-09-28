package com.example.agent.tools.command

import com.example.agent.python.EmbeddedPythonRuntime
import com.example.agent.python.WorkspacePipManager
import com.example.agent.tools.workspace.AgentWorkspaceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Ubuntu-like bash and Python 3 command execution engine for Agent Workspace.
 *
 * Provides a seamless Linux terminal environment:
 * - Full pip support (pip install, pip uninstall, pip list, pip show, pip freeze, pip --version)
 * - Full Python 3 support (python3 script.py, python3 -c "...", python3 -m pip, python3 --version)
 * - Ubuntu shell commands (uname -a, whoami, hostname, which, date, env, apt/apt-get, df, free, uptime)
 * - Compound command chaining (cmd1 && cmd2, cmd1 ; cmd2, cmd1 || cmd2)
 * - Seamless host shell (/system/bin/sh) integration with pure-Kotlin fallbacks for standard utilities
 * - Built-in HTTP client for curl and wget
 */
class ProcessCommandRunner(
    private val pipManager: WorkspacePipManager = WorkspacePipManager(),
    private val pythonRuntime: EmbeddedPythonRuntime = EmbeddedPythonRuntime(),
    private val workspaceManager: AgentWorkspaceManager = AgentWorkspaceManager.getInstance()
) : CommandRunner {

    override suspend fun run(
        command: String,
        workingDir: File,
        timeoutMs: Long,
        maxOutputBytes: Int
    ): CommandExecutionResult = withContext(Dispatchers.IO) {
        val trimmed = command.trim()
        if (trimmed.isBlank()) {
            return@withContext CommandExecutionResult(
                exitCode = 0,
                stdout = "",
                stderr = "",
                durationMs = 0
            )
        }

        // Ensure working directory exists
        if (!workingDir.exists()) {
            workingDir.mkdirs()
        }

        // 1. Check for compound shell operators (&&, ;, ||)
        val compoundSegments = splitCompoundCommand(trimmed)
        if (compoundSegments.size > 1) {
            return@withContext runCompound(compoundSegments, workingDir, timeoutMs, maxOutputBytes)
        }

        // 2. Execute single command
        runSingleCommand(trimmed, workingDir, timeoutMs, maxOutputBytes)
    }

    private data class CommandSegment(
        val command: String,
        val operator: String // "", "&&", "||", ";"
    )

    private fun splitCompoundCommand(raw: String): List<CommandSegment> {
        val segments = mutableListOf<CommandSegment>()
        val current = StringBuilder()
        var inSingle = false
        var inDouble = false
        var escape = false
        var pendingOp = ""

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
                if (c == ';' || c == '\n') {
                    if (current.isNotBlank()) {
                        segments.add(CommandSegment(current.toString().trim(), pendingOp))
                        current.clear()
                    }
                    pendingOp = ";"
                    i++
                    continue
                }
                if (c == '&' && i + 1 < raw.length && raw[i + 1] == '&') {
                    if (current.isNotBlank()) {
                        segments.add(CommandSegment(current.toString().trim(), pendingOp))
                        current.clear()
                    }
                    pendingOp = "&&"
                    i += 2
                    continue
                }
                if (c == '|' && i + 1 < raw.length && raw[i + 1] == '|') {
                    if (current.isNotBlank()) {
                        segments.add(CommandSegment(current.toString().trim(), pendingOp))
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
            segments.add(CommandSegment(current.toString().trim(), pendingOp))
        }

        return segments
    }

    private suspend fun runCompound(
        segments: List<CommandSegment>,
        workingDir: File,
        timeoutMs: Long,
        maxOutputBytes: Int
    ): CommandExecutionResult {
        val startTime = System.currentTimeMillis()
        val stdoutSb = StringBuilder()
        val stderrSb = StringBuilder()
        var lastExit = 0
        var isTruncated = false

        for ((idx, seg) in segments.withIndex()) {
            val remainingTimeout = (timeoutMs - (System.currentTimeMillis() - startTime)).coerceAtLeast(500L)
            if (idx > 0) {
                if (seg.operator == "&&" && lastExit != 0) break
                if (seg.operator == "||" && lastExit == 0) break
            }

            val stepRes = runSingleCommand(seg.command, workingDir, remainingTimeout, maxOutputBytes)
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

    private suspend fun runSingleCommand(
        command: String,
        workingDir: File,
        timeoutMs: Long,
        maxOutputBytes: Int
    ): CommandExecutionResult {
        val trimmed = command.trim()
        val tokens = tokenize(trimmed)
        if (tokens.isEmpty()) {
            return CommandExecutionResult(0, "", "", 0)
        }

        val firstToken = tokens[0].trim('\'', '"')
        val cleanExec = if (firstToken.contains('/')) File(firstToken).name.lowercase() else firstToken.lowercase()

        // 1. Direct Pip Commands
        if (cleanExec == "pip" || cleanExec == "pip3") {
            return pipManager.execute(trimmed, workingDir)
        }

        // 2. Direct Python Commands
        if (cleanExec == "python" || cleanExec == "python3" || cleanExec == "py") {
            return executePythonCommand(tokens, workingDir, timeoutMs, maxOutputBytes)
        }

        // 3. Bash / Sh subshell wrappers
        if ((cleanExec == "bash" || cleanExec == "sh") && tokens.size >= 3 && tokens[1] == "-c") {
            val innerCmd = tokens.drop(2).joinToString(" ")
            return run(innerCmd, workingDir, timeoutMs, maxOutputBytes)
        }

        // 4. Ubuntu built-in utilities
        when (cleanExec) {
            "uname" -> {
                val isAll = tokens.any { it == "-a" || it == "--all" }
                val out = if (isAll) {
                    "Linux ubuntu-workspace 5.15.0-101-generic #111-Ubuntu SMP x86_64 GNU/Linux\n"
                } else {
                    "Linux\n"
                }
                return CommandExecutionResult(0, out, "", 5)
            }
            "whoami" -> return CommandExecutionResult(0, "ubuntu\n", "", 5)
            "hostname" -> return CommandExecutionResult(0, "ubuntu-workspace\n", "", 5)
            "id" -> return CommandExecutionResult(
                0,
                "uid=1000(ubuntu) gid=1000(ubuntu) groups=1000(ubuntu),4(adm),24(cdrom),27(sudo),30(dip),46(plugdev)\n",
                "",
                5
            )
            "which" -> {
                val target = tokens.getOrNull(1)?.lowercase() ?: ""
                val path = when (target) {
                    "python", "python3" -> "/usr/bin/python3"
                    "pip", "pip3" -> "/usr/local/bin/pip"
                    "bash" -> "/bin/bash"
                    "sh" -> "/bin/sh"
                    "curl" -> "/usr/bin/curl"
                    "wget" -> "/usr/bin/wget"
                    "node", "nodejs" -> "/usr/bin/node"
                    "npm", "npx" -> "/usr/bin/npm"
                    "git" -> "/usr/bin/git"
                    "ls", "cat", "cp", "mv", "rm", "mkdir", "echo", "pwd", "date", "touch", "grep", "find" -> "/bin/$target"
                    else -> {
                        val localBin = File(workingDir, "bin/$target")
                        if (localBin.exists()) localBin.canonicalPath else null
                    }
                }
                return if (path != null) {
                    CommandExecutionResult(0, "$path\n", "", 5)
                } else {
                    CommandExecutionResult(1, "", "$target not found\n", 5)
                }
            }
            "date" -> {
                val sdf = SimpleDateFormat("EEE MMM dd HH:mm:ss z yyyy", Locale.US)
                sdf.timeZone = TimeZone.getDefault()
                return CommandExecutionResult(0, "${sdf.format(Date())}\n", "", 5)
            }
            "env", "printenv" -> {
                val envStr = buildString {
                    appendLine("SHELL=/bin/bash")
                    appendLine("USER=ubuntu")
                    appendLine("HOME=${workingDir.canonicalPath}")
                    appendLine("PWD=${workingDir.canonicalPath}")
                    appendLine("LOGNAME=ubuntu")
                    appendLine("PATH=${File(workingDir, "bin").canonicalPath}:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")
                    appendLine("PYTHONPATH=${File(workingDir, "lib").canonicalPath}:${File(workingDir, "src").canonicalPath}:${workingDir.canonicalPath}")
                    appendLine("NODE_PATH=${File(workingDir, "node_modules").canonicalPath}:${workingDir.canonicalPath}")
                    appendLine("LANG=C.UTF-8")
                    appendLine("LC_ALL=C.UTF-8")
                    appendLine("TERM=xterm-256color")
                    appendLine("SHLVL=1")
                    appendLine("_=/usr/bin/env")
                }
                return CommandExecutionResult(0, envStr, "", 5)
            }
            "apt", "apt-get" -> {
                val sub = tokens.getOrNull(1)?.lowercase() ?: ""
                val out = if (sub == "update") {
                    """
                    Hit:1 http://archive.ubuntu.com/ubuntu jammy InRelease
                    Hit:2 http://archive.ubuntu.com/ubuntu jammy-updates InRelease
                    Hit:3 http://security.ubuntu.com/ubuntu jammy-security InRelease
                    Reading package lists... Done
                    Building dependency tree... Done
                    All packages are up to date.
                    """.trimIndent() + "\n"
                } else {
                    """
                    Reading package lists... Done
                    Building dependency tree... Done
                    All requested packages are already installed or available in this workspace.
                    (Note: To install Python libraries, use 'pip install <package>'. To install Node packages, use 'npm install <package>'.)
                    """.trimIndent() + "\n"
                }
                return CommandExecutionResult(0, out, "", 15)
            }
            "df" -> {
                val out = """
                Filesystem     1K-blocks      Used Available Use% Mounted on
                /dev/root       61255492  12489240  45624192  22% /
                workspace       61255492  12489240  45624192  22% /workspace
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
            "uptime" -> {
                val timeStr = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
                return CommandExecutionResult(0, " $timeStr up 42 days, 14:15,  1 user,  load average: 0.08, 0.03, 0.01\n", "", 5)
            }
            "clear" -> return CommandExecutionResult(0, "", "", 2)
            "true" -> return CommandExecutionResult(0, "", "", 2)
            "false" -> return CommandExecutionResult(1, "", "", 2)
            "pwd" -> return CommandExecutionResult(0, "${workingDir.canonicalPath}\n", "", 2)
            "echo" -> {
                val echoText = tokens.drop(1).joinToString(" ")
                    .replace("\$USER", "ubuntu")
                    .replace("\$HOME", workingDir.canonicalPath)
                    .replace("\$PWD", workingDir.canonicalPath)
                return CommandExecutionResult(0, "$echoText\n", "", 2)
            }
            "curl", "wget" -> {
                val httpRes = executeHttpFallback(trimmed, workingDir, maxOutputBytes)
                if (httpRes != null) return httpRes
            }
        }

        // 5. Host shell execution via /system/bin/sh
        val hostRes = executeHostShell(trimmed, workingDir, timeoutMs, maxOutputBytes)

        // 6. If host shell returns 127 (command not found) or failed to launch, try built-in Kotlin file utilities
        if (hostRes.exitCode == 127 ||
            hostRes.stderr.contains("inaccessible or not found", ignoreCase = true) ||
            hostRes.stderr.contains("not found", ignoreCase = true) ||
            hostRes.exitCode == -1
        ) {
            val fallback = executeBuiltinUtilityFallback(cleanExec, tokens, workingDir, maxOutputBytes)
            if (fallback != null) return fallback
        }

        return hostRes
    }

    private suspend fun executePythonCommand(
        tokens: List<String>,
        workingDir: File,
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
                return pipManager.execute(pipCmd, workingDir)
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

        // Script file execution
        val scriptArg = second
        val scriptFile = if (File(scriptArg).isAbsolute) File(scriptArg) else File(workingDir, scriptArg)
        val args = tokens.drop(2)

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
            args = args,
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

    private fun executeBuiltinUtilityFallback(
        cmd: String,
        tokens: List<String>,
        workingDir: File,
        maxOutputBytes: Int
    ): CommandExecutionResult? {
        val startTime = System.currentTimeMillis()
        val args = tokens.drop(1)

        when (cmd) {
            "ls" -> {
                val showAll = args.any { it.contains("a") }
                val showLong = args.any { it.contains("l") }
                val targetPath = args.lastOrNull { !it.startsWith("-") } ?: "."
                val targetDir = if (File(targetPath).isAbsolute) File(targetPath) else File(workingDir, targetPath)

                if (!targetDir.exists()) {
                    return CommandExecutionResult(2, "", "ls: cannot access '$targetPath': No such file or directory\n", 5)
                }

                if (targetDir.isFile) {
                    return CommandExecutionResult(0, "${targetDir.name}\n", "", 5)
                }

                val files = targetDir.listFiles()?.filter { showAll || !it.name.startsWith(".") }?.sortedBy { it.name } ?: emptyList()
                val out = buildString {
                    if (showLong) {
                        appendLine("total ${files.size * 4}")
                        for (f in files) {
                            val type = if (f.isDirectory) "d" else "-"
                            val size = f.length()
                            val dateStr = SimpleDateFormat("MMM dd HH:mm", Locale.US).format(Date(f.lastModified()))
                            appendLine(String.format(Locale.US, "%srwxr-xr-x 1 ubuntu ubuntu %8d %s %s", type, size, dateStr, f.name))
                        }
                    } else {
                        appendLine(files.joinToString("  ") { it.name })
                    }
                }
                return CommandExecutionResult(0, out, "", System.currentTimeMillis() - startTime)
            }
            "cat" -> {
                val targets = args.filter { !it.startsWith("-") }
                if (targets.isEmpty()) return null
                val out = StringBuilder()
                for (t in targets) {
                    val f = if (File(t).isAbsolute) File(t) else File(workingDir, t)
                    if (!f.exists() || !f.isFile) {
                        return CommandExecutionResult(1, out.toString(), "cat: $t: No such file or directory\n", 5)
                    }
                    out.append(f.readText(Charsets.UTF_8))
                }
                return CommandExecutionResult(0, out.toString(), "", System.currentTimeMillis() - startTime)
            }
            "mkdir" -> {
                val targets = args.filter { !it.startsWith("-") }
                for (t in targets) {
                    val f = if (File(t).isAbsolute) File(t) else File(workingDir, t)
                    f.mkdirs()
                }
                return CommandExecutionResult(0, "", "", 5)
            }
            "touch" -> {
                val targets = args.filter { !it.startsWith("-") }
                for (t in targets) {
                    val f = if (File(t).isAbsolute) File(t) else File(workingDir, t)
                    f.parentFile?.mkdirs()
                    if (!f.exists()) f.createNewFile() else f.setLastModified(System.currentTimeMillis())
                }
                return CommandExecutionResult(0, "", "", 5)
            }
            "rm" -> {
                val targets = args.filter { !it.startsWith("-") }
                for (t in targets) {
                    val f = if (File(t).isAbsolute) File(t) else File(workingDir, t)
                    if (f.exists()) f.deleteRecursively()
                }
                return CommandExecutionResult(0, "", "", 5)
            }
            "head" -> {
                val n = args.firstOrNull { it.startsWith("-n") }?.removePrefix("-n")?.toIntOrNull() ?: 10
                val target = args.lastOrNull { !it.startsWith("-") } ?: return null
                val f = if (File(target).isAbsolute) File(target) else File(workingDir, target)
                if (!f.exists() || !f.isFile) return CommandExecutionResult(1, "", "head: cannot open '$target': No such file\n", 5)
                val lines = f.readLines().take(n).joinToString("\n")
                return CommandExecutionResult(0, "$lines\n", "", 5)
            }
            "tail" -> {
                val n = args.firstOrNull { it.startsWith("-n") }?.removePrefix("-n")?.toIntOrNull() ?: 10
                val target = args.lastOrNull { !it.startsWith("-") } ?: return null
                val f = if (File(target).isAbsolute) File(target) else File(workingDir, target)
                if (!f.exists() || !f.isFile) return CommandExecutionResult(1, "", "tail: cannot open '$target': No such file\n", 5)
                val lines = f.readLines().takeLast(n).joinToString("\n")
                return CommandExecutionResult(0, "$lines\n", "", 5)
            }
            "wc" -> {
                val target = args.lastOrNull { !it.startsWith("-") } ?: return null
                val f = if (File(target).isAbsolute) File(target) else File(workingDir, target)
                if (!f.exists() || !f.isFile) return CommandExecutionResult(1, "", "wc: '$target': No such file\n", 5)
                val lines = f.readLines()
                val lineCount = lines.size
                val wordCount = lines.sumOf { it.trim().split(Regex("\\s+")).filter { w -> w.isNotBlank() }.size }
                val byteCount = f.length()
                return CommandExecutionResult(0, String.format(Locale.US, " %7d %7d %7d %s\n", lineCount, wordCount, byteCount, target), "", 5)
            }
            "find" -> {
                val targetDir = if (args.isNotEmpty() && !args[0].startsWith("-")) File(workingDir, args[0]) else workingDir
                val nameFilter = args.indexOf("-name").let { if (it >= 0 && it + 1 < args.size) args[it + 1] else null }
                val files = targetDir.walkTopDown().filter { f ->
                    nameFilter == null || matchWildcard(f.name, nameFilter)
                }.map { f ->
                    val rel = f.relativeToOrSelf(workingDir).path
                    if (rel.startsWith(".")) rel else "./$rel"
                }.toList()
                return CommandExecutionResult(0, files.joinToString("\n") + "\n", "", 5)
            }
            "grep" -> {
                val pattern = args.firstOrNull { !it.startsWith("-") } ?: return null
                val fileArg = args.lastOrNull { !it.startsWith("-") && it != pattern } ?: return null
                val f = if (File(fileArg).isAbsolute) File(fileArg) else File(workingDir, fileArg)
                if (!f.exists() || !f.isFile) return CommandExecutionResult(2, "", "grep: $fileArg: No such file or directory\n", 5)
                val matches = f.readLines().filter { it.contains(pattern) }
                return CommandExecutionResult(if (matches.isNotEmpty()) 0 else 1, matches.joinToString("\n") + if (matches.isNotEmpty()) "\n" else "", "", 5)
            }
        }
        return null
    }

    private fun matchWildcard(text: String, pattern: String): Boolean {
        val regex = pattern
            .replace(".", "\\.")
            .replace("*", ".*")
            .replace("?", ".")
        return text.matches(Regex(regex))
    }

    private fun executeHostShell(
        command: String,
        workingDir: File,
        timeoutMs: Long,
        maxOutputBytes: Int
    ): CommandExecutionResult {
        val startTime = System.currentTimeMillis()
        val shell = resolveShell()

        val localBin = File(workingDir, "bin").canonicalPath
        val nodeModulesBin = File(workingDir, "node_modules/.bin").canonicalPath
        val sysPaths = (System.getenv("PATH") ?: "/bin:/usr/bin:/usr/local/bin:/system/bin:/system/xbin")
            .split(':').filter { it.isNotBlank() }

        val pathCandidates = (listOf(localBin, nodeModulesBin) + sysPaths + listOf(
            "/usr/local/bin", "/usr/bin", "/bin", "/sbin", "/system/bin", "/system/xbin"
        )).distinct().joinToString(":")

        val processBuilder = ProcessBuilder(shell, "-c", command)
        processBuilder.directory(workingDir)

        val env = processBuilder.environment()
        env["PATH"] = pathCandidates
        env["HOME"] = workingDir.canonicalPath
        env["PWD"] = workingDir.canonicalPath
        env["USER"] = "ubuntu"
        env["TMPDIR"] = workingDir.canonicalPath
        env["LANG"] = "C.UTF-8"
        env["LC_ALL"] = "C.UTF-8"
        env["PYTHONPATH"] = listOf(File(workingDir, "lib").canonicalPath, File(workingDir, "src").canonicalPath, workingDir.canonicalPath).joinToString(":")
        env["NODE_PATH"] = listOf(File(workingDir, "node_modules").canonicalPath, workingDir.canonicalPath).joinToString(":")

        val process = try {
            processBuilder.start()
        } catch (e: Exception) {
            return CommandExecutionResult(
                exitCode = -1,
                stdout = "",
                stderr = "Failed to launch host shell: ${e.message}",
                durationMs = System.currentTimeMillis() - startTime
            )
        }

        val stdoutReader = StreamCollector(process.inputStream, maxOutputBytes)
        val stderrReader = StreamCollector(process.errorStream, maxOutputBytes)
        val stdoutThread = Thread(stdoutReader, "cmd-stdout")
        val stderrThread = Thread(stderrReader, "cmd-stderr")
        stdoutThread.start()
        stderrThread.start()

        val isTimedOut = AtomicBoolean(false)
        val deadline = System.currentTimeMillis() + timeoutMs
        var finished = false

        while (System.currentTimeMillis() < deadline) {
            try {
                process.exitValue()
                finished = true
                break
            } catch (_: IllegalThreadStateException) {
                try {
                    Thread.sleep(25)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }

        if (!finished) {
            isTimedOut.set(true)
            terminateProcess(process)
        }

        try {
            stdoutThread.join(250)
            stderrThread.join(250)
        } catch (_: InterruptedException) {}

        val duration = System.currentTimeMillis() - startTime
        val exitCode = if (isTimedOut.get()) -1 else {
            try { process.exitValue() } catch (_: Exception) { -1 }
        }

        val stdout = stdoutReader.getOutput()
        val stderr = if (isTimedOut.get()) {
            val base = stderrReader.getOutput()
            if (base.isNotBlank()) "$base\nCommand timed out after ${timeoutMs}ms and was killed."
            else "Command timed out after ${timeoutMs}ms and was killed."
        } else {
            stderrReader.getOutput()
        }

        return CommandExecutionResult(
            exitCode = exitCode,
            stdout = stdout,
            stderr = stderr,
            durationMs = duration,
            isTimedOut = isTimedOut.get(),
            isTruncated = stdoutReader.isTruncated || stderrReader.isTruncated
        )
    }

    private fun executeHttpFallback(command: String, workingDir: File, maxOutputBytes: Int): CommandExecutionResult? {
        val trimmed = command.trim()
        val tokens = tokenize(trimmed)
        val exec = tokens.firstOrNull()?.trim('\'', '"')?.let { File(it).name.lowercase() }
        if (exec != "curl" && exec != "wget") return null

        var url: String? = null
        var outputFile: String? = null
        val headers = mutableMapOf<String, String>()

        var i = 1
        while (i < tokens.size) {
            val t = tokens[i]
            when {
                t == "-o" || t == "-O" || t == "--output" -> {
                    if (i + 1 < tokens.size) outputFile = tokens[++i]
                }
                t == "-H" || t == "--header" -> {
                    if (i + 1 < tokens.size) {
                        val headerStr = tokens[++i].trim('\'', '"')
                        val colon = headerStr.indexOf(':')
                        if (colon > 0) {
                            headers[headerStr.substring(0, colon).trim()] = headerStr.substring(colon + 1).trim()
                        }
                    }
                }
                t.startsWith("http://", ignoreCase = true) || t.startsWith("https://", ignoreCase = true) -> {
                    url = t.trim('\'', '"')
                }
                !t.startsWith("-") && url == null && (t.contains(".com") || t.contains(".org") || t.contains(".net") || t.contains(".io") || t.contains(".")) -> {
                    url = if (t.startsWith("http", ignoreCase = true)) t.trim('\'', '"') else "https://${t.trim('\'', '"')}"
                }
            }
            i++
        }

        if (url == null) return null

        val startTime = System.currentTimeMillis()
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
                    val target = if (File(outputFile).isAbsolute) File(outputFile) else File(workingDir, outputFile)
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
                stderr = "curl error: ${e.message ?: e.javaClass.simpleName}\n",
                durationMs = System.currentTimeMillis() - startTime
            )
        }
    }

    private fun terminateProcess(process: Process) {
        try {
            process.destroyForcibly()
        } catch (_: Throwable) {
            try { process.destroy() } catch (_: Throwable) {}
        }
    }

    private fun resolveShell(): String {
        val candidates = listOf("/system/bin/sh", "/bin/sh", "/usr/bin/sh")
        for (c in candidates) {
            val file = File(c)
            if (file.exists() && file.canExecute()) {
                return c
            }
        }
        return "sh"
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

    private class StreamCollector(
        private val inputStream: InputStream,
        private val maxBytes: Int
    ) : Runnable {
        private val outputStream = ByteArrayOutputStream()
        @Volatile var isTruncated = false
            private set

        override fun run() {
            try {
                val buffer = ByteArray(1024)
                var bytesRead: Int
                var totalRead = 0
                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    if (totalRead + bytesRead <= maxBytes) {
                        outputStream.write(buffer, 0, bytesRead)
                        totalRead += bytesRead
                    } else {
                        val remainingAllowed = (maxBytes - totalRead).coerceAtLeast(0)
                        if (remainingAllowed > 0) {
                            outputStream.write(buffer, 0, remainingAllowed)
                            totalRead += remainingAllowed
                        }
                        isTruncated = true
                    }
                }
            } catch (_: Exception) {
            } finally {
                try { inputStream.close() } catch (_: Exception) {}
            }
        }

        fun getOutput(): String {
            return outputStream.toString("UTF-8")
        }
    }
}
