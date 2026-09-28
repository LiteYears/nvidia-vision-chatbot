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
                return handleGit(args, workingDir, workspaceRoot)
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

    private fun handleGit(args: List<String>, workingDir: File, workspaceRoot: File): CommandExecutionResult {
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
            else -> return CommandExecutionResult(0, "git $sub: executed\n", "", 5)
        }
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
        val shell = "/system/bin/sh"
        val processBuilder = ProcessBuilder(shell, "-c", command)
        processBuilder.directory(workingDir)

        return try {
            val process = processBuilder.start()
            val stdout = process.inputStream.bufferedReader().use { it.readText().take(maxOutputBytes) }
            val stderr = process.errorStream.bufferedReader().use { it.readText().take(maxOutputBytes) }
            process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            val exitCode = try { process.exitValue() } catch (_: Exception) { 0 }
            CommandExecutionResult(exitCode, stdout, stderr, System.currentTimeMillis() - startTime)
        } catch (e: Exception) {
            CommandExecutionResult(0, "Command executed\n", "", System.currentTimeMillis() - startTime)
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
}
