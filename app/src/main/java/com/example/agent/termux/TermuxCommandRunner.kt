package com.example.agent.termux

import com.example.agent.proot.ProotCommandExecutor
import com.example.agent.python.EmbeddedPythonRuntime
import com.example.agent.tools.command.CommandExecutionResult
import com.example.agent.tools.command.CommandRunner
import com.example.agent.tools.workspace.AgentWorkspaceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream

/**
 * Real Linux process execution engine for Termux and Ubuntu environment.
 *
 * Runs commands via ProcessBuilder against the Android Linux kernel (/system/bin/sh),
 * passing the real Termux prefix, PATH, HOME, and Ubuntu environment variables.
 * Provides seamless fallback to ProotCommandExecutor if process execution is restricted
 * or tools are not present on the host Android image.
 */
class TermuxCommandRunner(
    private val envManager: TermuxEnvironmentManager = TermuxEnvironmentManager.getInstance(),
    private val workspaceManager: AgentWorkspaceManager = AgentWorkspaceManager.getInstance(),
    private val fallbackExecutor: ProotCommandExecutor = ProotCommandExecutor(
        pythonRuntime = EmbeddedPythonRuntime(workspaceManager)
    )
) : CommandRunner {

    override suspend fun run(
        command: String,
        workingDir: File,
        timeoutMs: Long,
        maxOutputBytes: Int
    ): CommandExecutionResult = run(command, workingDir, timeoutMs, maxOutputBytes, "auto")

    override suspend fun run(
        command: String,
        workingDir: File,
        timeoutMs: Long,
        maxOutputBytes: Int,
        environment: String
    ): CommandExecutionResult = withContext(Dispatchers.IO) {
        val trimmed = stripPromptsSafely(command)
        if (trimmed.isBlank()) {
            return@withContext CommandExecutionResult(0, "", "", 0)
        }
        if (trimmed == "^C" || trimmed == "\u0003" || trimmed.endsWith("^C")) {
            return@withContext CommandExecutionResult(130, "", "", 0)
        }

        // Ensure real Termux environment is prepared
        if (!envManager.isInitialized) {
            envManager.ensureInitialized(force = false)
        }

        val workspaceRoot = workspaceManager.getWorkspaceDir()
        val effectiveDir = if (workingDir.exists() && workingDir.isDirectory) {
            workingDir
        } else {
            workspaceRoot
        }

        val firstToken = trimmed.split(Regex("\\s+|&&|\\|\\||;")).firstOrNull()?.trim('\'', '"') ?: ""
        val cleanFirst = if (firstToken.contains('/')) File(firstToken).name else firstToken
        val isSpecialUtility = cleanFirst == "pkg" || cleanFirst == "neofetch" || cleanFirst == "proot" ||
            cleanFirst.startsWith("termux-") || trimmed.contains("ubuntu22.sh") || trimmed.contains("start-ubuntu22.sh")

        if (isSpecialUtility) {
            return@withContext fallbackExecutor.execute(
                commandLine = trimmed,
                workingDir = effectiveDir,
                workspaceRoot = workspaceRoot,
                timeoutMs = timeoutMs,
                maxOutputBytes = maxOutputBytes
            )
        }

        val resolvedEnv = when (environment.trim().lowercase()) {
            "termux" -> "termux"
            "auto" -> if (isTermuxSpecificCommand(trimmed)) "termux" else "ubuntu"
            else -> "ubuntu"
        }

        // 1. Ubuntu 22.04 LTS Execution:
        if (resolvedEnv == "ubuntu") {
            // Check if native host PRoot binary is available
            val nativeProot = findNativeProotBinary()
            if (nativeProot != null) {
                val prootRes = runNativeProot(
                    prootBin = nativeProot,
                    command = trimmed,
                    workingDir = effectiveDir,
                    workspaceRoot = workspaceRoot,
                    timeoutMs = timeoutMs,
                    maxOutputBytes = maxOutputBytes
                )
                if (prootRes != null) {
                    return@withContext prootRes
                }
            }

            // Route to Ubuntu virtual userspace executor (handles bash pipelines, apt, dpkg, deb archives, python runtime)
            return@withContext fallbackExecutor.execute(
                commandLine = trimmed,
                workingDir = effectiveDir,
                workspaceRoot = workspaceRoot,
                timeoutMs = timeoutMs,
                maxOutputBytes = maxOutputBytes
            )
        }

        // 2. Termux Native Execution:
        val startTime = System.currentTimeMillis()
        val shell = envManager.findSystemShell()

        try {
            val pb = ProcessBuilder(shell, "-c", trimmed)
            pb.directory(effectiveDir)
            val envVars = envManager.getEnvironmentVariables(workspaceRoot)
            pb.environment().putAll(envVars)

            val process = pb.start()
            try {
                // Close standard input immediately so child commands waiting for EOF on stdin do not hang
                try {
                    process.outputStream.close()
                } catch (_: Exception) {}

                val stdoutDeferred = async(Dispatchers.IO) {
                    readStreamLimited(process.inputStream, maxOutputBytes)
                }
                val stderrDeferred = async(Dispatchers.IO) {
                    readStreamLimited(process.errorStream, maxOutputBytes)
                }

                val finished = process.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
                val durationMs = System.currentTimeMillis() - startTime

                if (!finished) {
                    process.destroyForcibly()
                    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
                    val partialStdout = if (stdoutDeferred.isCompleted) {
                        try { stdoutDeferred.getCompleted() } catch (_: Exception) { StreamReadResult("", false) }
                    } else {
                        stdoutDeferred.cancel()
                        StreamReadResult("", false)
                    }
                    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
                    val partialStderr = if (stderrDeferred.isCompleted) {
                        try { stderrDeferred.getCompleted() } catch (_: Exception) { StreamReadResult("", false) }
                    } else {
                        stderrDeferred.cancel()
                        StreamReadResult("", false)
                    }
                    return@withContext CommandExecutionResult(
                        exitCode = -1,
                        stdout = partialStdout.text,
                        stderr = "${partialStderr.text}\nProcess timed out after ${timeoutMs}ms and was killed.",
                        durationMs = durationMs,
                        isTimedOut = true,
                        isTruncated = partialStdout.isTruncated || partialStderr.isTruncated
                    )
                }

                val exitCode = process.exitValue()
                val stdoutResult = stdoutDeferred.await()
                val stderrResult = stderrDeferred.await()

                // Fallback check:
                // If the native process failed because of Permission denied (code 126, e.g. SELinux W^X blocking
                // execution of user binaries/scripts in data dir), command not found (code 127), syntax errors,
                // or missing host tools, automatically route to the PRoot Ubuntu virtual executor!
                val isRestrictedOrUnavailable = exitCode == 126 || exitCode == 127 ||
                    stderrResult.text.contains("Permission denied", ignoreCase = true) ||
                    stderrResult.text.contains("inaccessible or not found", ignoreCase = true) ||
                    stderrResult.text.contains("not found", ignoreCase = true) ||
                    (exitCode != 0 && (stderrResult.text.contains("syntax error", ignoreCase = true) || trimmed.contains("<<")))

                if (isRestrictedOrUnavailable) {
                    val fallbackRes = fallbackExecutor.execute(
                        commandLine = trimmed,
                        workingDir = effectiveDir,
                        workspaceRoot = workspaceRoot,
                        timeoutMs = timeoutMs,
                        maxOutputBytes = maxOutputBytes
                    )
                    if (fallbackRes.exitCode == 0 || fallbackRes.stdout.isNotBlank() || !fallbackRes.stderr.contains("Unknown package tool")) {
                        return@withContext fallbackRes
                    }
                }

                CommandExecutionResult(
                    exitCode = exitCode,
                    stdout = stdoutResult.text,
                    stderr = stderrResult.text,
                    durationMs = durationMs,
                    isTimedOut = false,
                    isTruncated = stdoutResult.isTruncated || stderrResult.isTruncated
                )
            } finally {
                if (process.isAlive) {
                    try { process.destroyForcibly() } catch (_: Exception) {}
                }
            }
        } catch (e: Exception) {
            val fallbackResult = fallbackExecutor.execute(
                commandLine = trimmed,
                workingDir = effectiveDir,
                workspaceRoot = workspaceRoot,
                timeoutMs = timeoutMs,
                maxOutputBytes = maxOutputBytes
            )
            fallbackResult
        }
    }

    private suspend fun runNativeProot(
        prootBin: File,
        command: String,
        workingDir: File,
        workspaceRoot: File,
        timeoutMs: Long,
        maxOutputBytes: Int
    ): CommandExecutionResult? = withContext(Dispatchers.IO) {
        val rootfsDir = envManager.ubuntuRootDir
        val relWorking = try {
            val rel = workingDir.relativeTo(workspaceRoot).path
            if (rel.isBlank() || rel == ".") "/workspace" else "/workspace/$rel"
        } catch (_: Exception) {
            "/workspace"
        }

        val bashCandidates = listOf(
            File(rootfsDir, "usr/bin/bash"),
            File(rootfsDir, "bin/bash"),
            File("/bin/bash"),
            File("/usr/bin/bash")
        )
        val bashPath = bashCandidates.firstOrNull { it.exists() }?.let {
            if (it.absolutePath.startsWith(rootfsDir.absolutePath)) {
                it.absolutePath.removePrefix(rootfsDir.absolutePath)
            } else {
                it.absolutePath
            }
        } ?: "/bin/sh"

        val prootArgs = listOf(
            prootBin.absolutePath,
            "-r", rootfsDir.absolutePath,
            "-0",
            "-b", "/dev",
            "-b", "/proc",
            "-b", "/sys",
            "-b", "${workspaceRoot.absolutePath}:/workspace",
            "-w", relWorking,
            bashPath, "-c", command
        )

        val startTime = System.currentTimeMillis()
        try {
            val pb = ProcessBuilder(prootArgs)
            pb.directory(workingDir)
            val envVars = envManager.getEnvironmentVariables(workspaceRoot)
            pb.environment().putAll(envVars)
            pb.environment()["HOME"] = "/root"
            pb.environment()["USER"] = "root"
            pb.environment()["PATH"] = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"

            val process = pb.start()
            try {
                try {
                    process.outputStream.close()
                } catch (_: Exception) {}

                val stdoutDeferred = async(Dispatchers.IO) { readStreamLimited(process.inputStream, maxOutputBytes) }
                val stderrDeferred = async(Dispatchers.IO) { readStreamLimited(process.errorStream, maxOutputBytes) }

                val finished = process.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
                val durationMs = System.currentTimeMillis() - startTime

                if (!finished) {
                    process.destroyForcibly()
                    stdoutDeferred.cancel()
                    stderrDeferred.cancel()
                    return@withContext CommandExecutionResult(
                        exitCode = -1,
                        stdout = "",
                        stderr = "Process timed out after ${timeoutMs}ms and was killed.",
                        durationMs = durationMs,
                        isTimedOut = true
                    )
                }

                val exitCode = process.exitValue()
                val stdoutRes = stdoutDeferred.await()
                val stderrRes = stderrDeferred.await()

                // If PRoot failed with ptrace or permission error, return null to fall back to virtual executor
                if (exitCode == 126 || exitCode == 127 ||
                    stderrRes.text.contains("ptrace", ignoreCase = true) ||
                    stderrRes.text.contains("proot info", ignoreCase = true) ||
                    (stderrRes.text.contains("Permission denied", ignoreCase = true) && stderrRes.text.contains("proot", ignoreCase = true))) {
                    return@withContext null
                }

                CommandExecutionResult(
                    exitCode = exitCode,
                    stdout = stdoutRes.text,
                    stderr = stderrRes.text,
                    durationMs = durationMs,
                    isTimedOut = false,
                    isTruncated = stdoutRes.isTruncated || stderrRes.isTruncated
                )
            } finally {
                if (process.isAlive) {
                    try { process.destroyForcibly() } catch (_: Exception) {}
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun findNativeProotBinary(): File? {
        val candidates = listOf(
            File("/data/data/com.termux/files/usr/bin/proot"),
            File("/usr/bin/proot"),
            File("/system/bin/proot"),
            File("/system/xbin/proot")
        )
        return candidates.firstOrNull { it.exists() && it.canExecute() && !it.isDirectory }
    }

    private fun isTermuxSpecificCommand(command: String): Boolean {
        val trimmed = command.trim()
        val tokens = trimmed.split(Regex("\\s+|&&|\\|\\||;")).filter { it.isNotBlank() }
        if (tokens.isEmpty()) return false
        val firstToken = tokens.first().trim('\'', '"')
        val baseName = if (firstToken.contains('/')) File(firstToken).name else firstToken
        return baseName == "pkg" || baseName.startsWith("termux-")
    }

    private fun isEmbeddedUbuntuCommand(command: String): Boolean {
        val trimmed = command.trim()
        if (trimmed.contains("<<")) return true

        val tokens = trimmed.split(Regex("\\s+|&&|\\|\\||;")).filter { it.isNotBlank() }
        if (tokens.isEmpty()) return false
        val ubuntuKeywords = setOf(
            "apt", "apt-get", "dpkg", "pkg", "proot", "proot-distro",
            "python", "python3", "py", "pip", "pip3", "pytest", "unittest"
        )
        return tokens.any { token ->
            val clean = token.trim('\'', '"', ';', '&', '|').lowercase()
            val baseName = if (clean.contains('/')) File(clean).name else clean
            baseName in ubuntuKeywords
        }
    }

    private data class StreamReadResult(val text: String, val isTruncated: Boolean)

    private fun readStreamLimited(input: InputStream, maxBytes: Int): StreamReadResult {
        val buffer = ByteArray(1024)
        val baos = ByteArrayOutputStream()
        var totalRead = 0
        var truncated = false

        try {
            var n: Int
            while (input.read(buffer).also { n = it } != -1) {
                if (totalRead + n > maxBytes) {
                    val remaining = maxBytes - totalRead
                    if (remaining > 0) {
                        baos.write(buffer, 0, remaining)
                        totalRead += remaining
                    }
                    truncated = true
                    break
                } else {
                    baos.write(buffer, 0, n)
                    totalRead += n
                }
            }
        } catch (_: Exception) {
        } finally {
            try {
                input.close()
            } catch (_: Exception) {
            }
        }

        return StreamReadResult(baos.toString(Charsets.UTF_8.name()), truncated)
    }

    private fun stripPromptsSafely(command: String): String {
        val lines = command.lines()
        val processed = mutableListOf<String>()
        var activeHeredocDelim: String? = null

        val promptRegex = Regex("""^[ \t]*(?:[a-zA-Z0-9._-]+@[a-zA-Z0-9._-]+:[^$#\r\n]*[\$#]|\$|>)[ \t]+""")

        for (line in lines) {
            if (activeHeredocDelim != null) {
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
}
