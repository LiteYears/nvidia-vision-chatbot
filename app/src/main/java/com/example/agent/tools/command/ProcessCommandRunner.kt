package com.example.agent.tools.command

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Robust process runner for executing shell commands and scripts.
 *
 * Provides:
 * - Dynamic shell resolution (/system/bin/sh, /bin/sh, etc.)
 * - Rich PATH resolution ensuring Android binaries (pm, am, getprop, curl, ping) are discoverable
 * - Strict execution timeouts with process tree cleanup
 * - Stream size limiting to protect memory
 * - Non-blocking asynchronous stdout/stderr collection
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

        // Ensure working directory exists
        if (!workingDir.exists()) {
            workingDir.mkdirs()
        }

        val processBuilder = ProcessBuilder(shell, "-c", command)
        processBuilder.directory(workingDir)

        val env = processBuilder.environment()
        
        // Pass necessary system variables for Android shell tools
        val systemEnv = System.getenv()
        val pathCandidates = listOf(
            systemEnv["PATH"],
            "/system/bin",
            "/system/xbin",
            "/sbin",
            "/vendor/bin",
            "/bin",
            "/usr/bin",
            "/usr/local/bin"
        ).filterNotNull().joinToString(":")

        env["PATH"] = pathCandidates
        env["HOME"] = workingDir.canonicalPath
        env["PWD"] = workingDir.canonicalPath
        env["TMPDIR"] = workingDir.canonicalPath
        env["LANG"] = "C.UTF-8"
        env["LC_ALL"] = "C.UTF-8"
        
        systemEnv["ANDROID_ROOT"]?.let { env["ANDROID_ROOT"] = it }
        systemEnv["ANDROID_DATA"]?.let { env["ANDROID_DATA"] = it }

        val trimmedCmd = command.trim()
        val firstToken = trimmedCmd.split(Regex("\\s+")).firstOrNull()?.trim('\'', '"')?.let { File(it).name.lowercase() }
        val isCurlOrWget = firstToken == "curl" || firstToken == "wget"

        val process = try {
            processBuilder.start()
        } catch (e: Exception) {
            if (isCurlOrWget) {
                val fallback = executeHttpFallback(command, workingDir, maxOutputBytes)
                if (fallback != null) {
                    return@withContext fallback
                }
            }
            val duration = System.currentTimeMillis() - startTime
            return@withContext CommandExecutionResult(
                exitCode = -1,
                stdout = "",
                stderr = "Failed to launch command process: ${e.message ?: e.javaClass.simpleName}",
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

        val isNotFound = exitCode == 127 ||
            stderr.contains("inaccessible or not found", ignoreCase = true) ||
            stderr.contains("not found", ignoreCase = true)

        if (isCurlOrWget && (isNotFound || (exitCode != 0 && stdout.isBlank()))) {
            val fallback = executeHttpFallback(command, workingDir, maxOutputBytes)
            if (fallback != null) {
                return@withContext fallback
            }
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

    private fun executeHttpFallback(command: String, workingDir: File, maxOutputBytes: Int): CommandExecutionResult? {
        val trimmed = command.trim()
        val tokens = trimmed.split(Regex("\\s+"))
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
                        stdout = "Saved ${bodyBytes.size} bytes to $outputFile",
                        stderr = "",
                        durationMs = duration
                    )
                } else {
                    val str = String(bodyBytes.take(maxOutputBytes).toByteArray(), Charsets.UTF_8)
                    CommandExecutionResult(
                        exitCode = if (resp.isSuccessful) 0 else resp.code,
                        stdout = str,
                        stderr = if (resp.isSuccessful) "" else "HTTP ${resp.code} ${resp.message}",
                        durationMs = duration,
                        isTruncated = bodyBytes.size > maxOutputBytes
                    )
                }
            }
        } catch (e: Exception) {
            CommandExecutionResult(
                exitCode = 1,
                stdout = "",
                stderr = "curl error: ${e.message ?: e.javaClass.simpleName}",
                durationMs = System.currentTimeMillis() - startTime
            )
        }
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
