package com.example.agent.tools.command

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Controlled process runner for workspace command execution.
 *
 * Enforces:
 * - Isolated working directory execution
 * - Sanitized environment (no API keys, system tokens, or secrets leaked)
 * - Strict execution timeouts with forced process termination
 * - Output buffer size limits to prevent out-of-memory or excessive token consumption
 * - Non-blocking stream consumption
 */
class ProcessCommandRunner : CommandRunner {

    override suspend fun run(
        command: String,
        workingDir: File,
        timeoutMs: Long,
        maxOutputBytes: Int
    ): CommandExecutionResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        val shell = resolveShell()

        val processBuilder = ProcessBuilder(shell, "-c", command)
        processBuilder.directory(workingDir)

        // Sanitize environment: clear all inherited variables to prevent secret leakage
        val env = processBuilder.environment()
        env.clear()
        env["PATH"] = "/system/bin:/system/xbin:/bin:/usr/bin:/usr/local/bin"
        env["HOME"] = workingDir.canonicalPath
        env["PWD"] = workingDir.canonicalPath
        env["TMPDIR"] = workingDir.canonicalPath
        env["LANG"] = "C.UTF-8"
        env["LC_ALL"] = "C.UTF-8"

        val process = try {
            processBuilder.start()
        } catch (e: Exception) {
            val duration = System.currentTimeMillis() - startTime
            return@withContext CommandExecutionResult(
                exitCode = -1,
                stdout = "",
                stderr = "Failed to launch process: ${e.message ?: e.javaClass.simpleName}",
                durationMs = duration,
                isTimedOut = false,
                isTruncated = false
            )
        }

        // Asynchronously stream stdout and stderr up to byte limit
        val stdoutReader = StreamCollector(process.inputStream, maxOutputBytes)
        val stderrReader = StreamCollector(process.errorStream, maxOutputBytes)
        val stdoutThread = Thread(stdoutReader, "cmd-stdout-collector")
        val stderrThread = Thread(stderrReader, "cmd-stderr-collector")
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

        // Wait briefly for stream threads to finish collecting remaining bytes
        try {
            stdoutThread.join(250)
            stderrThread.join(250)
        } catch (_: InterruptedException) {}

        val duration = System.currentTimeMillis() - startTime
        val exitCode = if (isTimedOut.get()) -1 else {
            try {
                process.exitValue()
            } catch (_: Exception) {
                -1
            }
        }

        val stdout = stdoutReader.getOutput()
        val stderr = if (isTimedOut.get()) {
            val baseErr = stderrReader.getOutput()
            if (baseErr.isNotBlank()) "$baseErr\nCommand timed out after ${timeoutMs}ms and was killed."
            else "Command timed out after ${timeoutMs}ms and was killed."
        } else {
            stderrReader.getOutput()
        }

        CommandExecutionResult(
            exitCode = exitCode,
            stdout = stdout,
            stderr = stderr,
            durationMs = duration,
            isTimedOut = isTimedOut.get(),
            isTruncated = stdoutReader.isTruncated || stderrReader.isTruncated
        )
    }

    private fun terminateProcess(process: Process) {
        try {
            process.destroyForcibly()
        } catch (_: Throwable) {
            try {
                process.destroy()
            } catch (_: Throwable) {}
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
                        }
                        isTruncated = true
                        break
                    }
                }
            } catch (_: Exception) {
                // Stream closed or process terminated
            } finally {
                try {
                    inputStream.close()
                } catch (_: Exception) {}
            }
        }

        fun getOutput(): String {
            return outputStream.toString("UTF-8")
        }
    }
}
