package com.example.agent.termux

import com.example.agent.proot.ProotCommandExecutor
import com.example.agent.tools.command.CommandExecutionResult
import com.example.agent.tools.command.CommandRunner
import com.example.agent.tools.workspace.AgentWorkspaceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream

/**
 * Real Linux process execution engine for Termux and Ubuntu environment.
 *
 * Runs commands via ProcessBuilder against the Android Linux kernel (/system/bin/sh),
 * passing the real Termux prefix, PATH, HOME, and Ubuntu environment variables.
 * Provides fallback to ProotCommandExecutor if process execution is restricted.
 */
class TermuxCommandRunner(
    private val envManager: TermuxEnvironmentManager = TermuxEnvironmentManager.getInstance(),
    private val workspaceManager: AgentWorkspaceManager = AgentWorkspaceManager.getInstance(),
    private val fallbackExecutor: ProotCommandExecutor = ProotCommandExecutor()
) : CommandRunner {

    override suspend fun run(
        command: String,
        workingDir: File,
        timeoutMs: Long,
        maxOutputBytes: Int
    ): CommandExecutionResult = withContext(Dispatchers.IO) {
        val trimmed = command.trim()
        if (trimmed.isBlank()) {
            return@withContext CommandExecutionResult(0, "", "", 0)
        }

        // Ensure real Termux environment is prepared
        if (!envManager.isInitialized) {
            envManager.ensureInitialized(force = false)
        }

        val effectiveDir = if (workingDir.exists() && workingDir.isDirectory) {
            workingDir
        } else {
            envManager.homeDir
        }

        val workspaceRoot = workspaceManager.getWorkspaceDir()

        // 1. Direct handling for Ubuntu PRoot and package management commands:
        // Tools like apt, dpkg, pkg, proot, proot-distro, pip, and python are user-space emulated
        // within the rootless Ubuntu PRoot environment to avoid Android SELinux W^X execution restrictions.
        if (isEmbeddedUbuntuCommand(trimmed)) {
            return@withContext fallbackExecutor.execute(
                commandLine = trimmed,
                workingDir = effectiveDir,
                workspaceRoot = workspaceRoot,
                timeoutMs = timeoutMs,
                maxOutputBytes = maxOutputBytes
            )
        }

        val startTime = System.currentTimeMillis()
        val shell = envManager.findSystemShell()

        try {
            val pb = ProcessBuilder(shell, "-c", trimmed)
            pb.directory(effectiveDir)
            val envVars = envManager.getEnvironmentVariables(workspaceRoot)
            pb.environment().putAll(envVars)

            val process = pb.start()

            // Asynchronously collect stdout and stderr
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
                } else StreamReadResult("", false)
                @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
                val partialStderr = if (stderrDeferred.isCompleted) {
                    try { stderrDeferred.getCompleted() } catch (_: Exception) { StreamReadResult("", false) }
                } else StreamReadResult("", false)
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

            // 2. Fallback check:
            // If the native process failed because of Permission denied (code 126, e.g. SELinux W^X blocking
            // execution of user binaries/scripts in data dir) or command not found (code 127),
            // automatically route to the PRoot Ubuntu virtual executor!
            val isRestrictedOrUnavailable = exitCode == 126 || exitCode == 127 ||
                stderrResult.text.contains("Permission denied", ignoreCase = true) ||
                stderrResult.text.contains("inaccessible or not found", ignoreCase = true)

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
        } catch (e: Exception) {
            // If process spawning fails due to OS security restriction, invoke fallback executor
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

    /**
     * Determines whether a command involves Ubuntu PRoot user-space tools that should be executed
     * via ProotCommandExecutor rather than host shell process spawning.
     */
    private fun isEmbeddedUbuntuCommand(command: String): Boolean {
        val trimmed = command.trim()
        if (trimmed.contains("<<")) {
            return true
        }

        val embeddedTools = setOf(
            "apt", "apt-get", "dpkg", "pkg",
            "proot", "proot-distro",
            "pip", "pip3", "neofetch"
        )

        val subCommands = trimmed.split(Regex("[;&|\n]+"))
        for (sub in subCommands) {
            val words = sub.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
            if (words.isEmpty()) continue
            var execWord = words[0].trim('\'', '"').lowercase()
            if (execWord == "sudo" && words.size > 1) {
                execWord = words[1].trim('\'', '"').lowercase()
            }
            val execName = if (execWord.contains('/')) File(execWord).name else execWord
            if (execName in embeddedTools) {
                return true
            }
            if (execName == "python" || execName == "python3" || execName == "py") {
                val hasSystemPython = File("/system/bin/python3").exists() ||
                    File("/system/bin/python").exists() ||
                    File("/system/xbin/python3").exists()
                if (!hasSystemPython) {
                    return true
                }
            }
        }
        return false
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
}
