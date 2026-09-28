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

        val startTime = System.currentTimeMillis()
        val shell = envManager.findSystemShell()

        try {
            val pb = ProcessBuilder(shell, "-c", trimmed)
            pb.directory(effectiveDir)
            val envVars = envManager.getEnvironmentVariables(workspaceManager.getWorkspaceDir())
            pb.environment().putAll(envVars)

            val process = pb.start()

            // Asynchronously collect stdout and stderr
            val stdoutDeferred = async(Dispatchers.IO) {
                readStreamLimited(process.inputStream, maxOutputBytes)
            }
            val stderrDeferred = async(Dispatchers.IO) {
                readStreamLimited(process.errorStream, maxOutputBytes)
            }

            val completedInTime = withTimeoutOrNull(timeoutMs) {
                val exitCode = process.waitFor()
                exitCode
            }

            val durationMs = System.currentTimeMillis() - startTime

            if (completedInTime == null) {
                process.destroyForcibly()
                val partialStdout = stdoutDeferred.getCompleted()
                val partialStderr = stderrDeferred.getCompleted()
                return@withContext CommandExecutionResult(
                    exitCode = -1,
                    stdout = partialStdout.text,
                    stderr = "${partialStderr.text}\nProcess timed out after ${timeoutMs}ms and was killed.",
                    durationMs = durationMs,
                    isTimedOut = true,
                    isTruncated = partialStdout.isTruncated || partialStderr.isTruncated
                )
            }

            val stdoutResult = stdoutDeferred.await()
            val stderrResult = stderrDeferred.await()

            CommandExecutionResult(
                exitCode = completedInTime,
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
                workspaceRoot = workspaceManager.getWorkspaceDir(),
                timeoutMs = timeoutMs,
                maxOutputBytes = maxOutputBytes
            )
            fallbackResult
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
}
