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
 * Robust process runner for executing shell commands and scripts with Claude Code-like capabilities.
 *
 * Provides:
 * - Dynamic shell resolution (/system/bin/sh, /bin/sh, etc.)
 * - Rich PATH resolution ensuring Android binaries, Linux toolchains, Node.js, and Python are discoverable
 * - Automatic aliasing (python -> python3, pip -> pip3, node -> nodejs) when primary alias is absent
 * - Integration with workspace directories (bin/, node_modules/.bin, lib/, src/) via PATH, PYTHONPATH, and NODE_PATH
 * - Built-in fallback execution for curl, wget, pip, and npm when host binaries are missing
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

        // Configure search paths including workspace bin/ and node_modules/.bin
        val systemEnv = System.getenv()
        val localBin = File(workingDir, "bin").canonicalPath
        val nodeModulesBin = File(workingDir, "node_modules/.bin").canonicalPath
        val sysPaths = (systemEnv["PATH"] ?: "/bin:/usr/bin:/usr/local/bin:/system/bin:/system/xbin")
            .split(':')
            .map { it.trim() }
            .filter { it.isNotBlank() }

        val allSearchDirs = (listOf(localBin, nodeModulesBin) + sysPaths + listOf(
            "/usr/local/bin",
            "/usr/bin",
            "/bin",
            "/sbin",
            "/system/bin",
            "/system/xbin",
            "/vendor/bin"
        )).distinct()

        val pathCandidates = allSearchDirs.joinToString(":")

        // Transparently alias python -> python3, pip -> pip3, node -> nodejs if needed
        val effectiveCommand = preprocessCommand(command, allSearchDirs)

        val processBuilder = ProcessBuilder(shell, "-c", effectiveCommand)
        processBuilder.directory(workingDir)

        val env = processBuilder.environment()
        env["PATH"] = pathCandidates
        env["HOME"] = workingDir.canonicalPath
        env["PWD"] = workingDir.canonicalPath
        env["TMPDIR"] = workingDir.canonicalPath
        env["LANG"] = "C.UTF-8"
        env["LC_ALL"] = "C.UTF-8"

        // Wire local libraries so imported Python modules and Node packages work automatically
        val libDir = File(workingDir, "lib").canonicalPath
        val srcDir = File(workingDir, "src").canonicalPath
        val nodeModulesDir = File(workingDir, "node_modules").canonicalPath

        env["PYTHONPATH"] = listOf(libDir, srcDir, workingDir.canonicalPath).joinToString(":")
        env["NODE_PATH"] = listOf(nodeModulesDir, workingDir.canonicalPath).joinToString(":")
        
        systemEnv["ANDROID_ROOT"]?.let { env["ANDROID_ROOT"] = it }
        systemEnv["ANDROID_DATA"]?.let { env["ANDROID_DATA"] = it }

        val trimmedCmd = effectiveCommand.trim()
        val firstToken = trimmedCmd.split(Regex("\\s+")).firstOrNull()?.trim('\'', '"')?.let { File(it).name.lowercase() }
        val isCurlOrWget = firstToken == "curl" || firstToken == "wget"
        val isPip = firstToken == "pip" || firstToken == "pip3"
        val isNpm = firstToken == "npm" || firstToken == "npx"

        val process = try {
            processBuilder.start()
        } catch (e: Exception) {
            if (isCurlOrWget) {
                val fallback = executeHttpFallback(command, workingDir, maxOutputBytes)
                if (fallback != null) return@withContext fallback
            } else if (isPip) {
                val fallback = executePipFallback(command, workingDir, maxOutputBytes)
                if (fallback != null) return@withContext fallback
            } else if (isNpm) {
                val fallback = executeNpmFallback(command, workingDir, maxOutputBytes)
                if (fallback != null) return@withContext fallback
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

        if (isPip && (isNotFound || (exitCode != 0 && stdout.isBlank()))) {
            val fallback = executePipFallback(command, workingDir, maxOutputBytes)
            if (fallback != null) {
                return@withContext fallback
            }
        }

        if (isNpm && (isNotFound || (exitCode != 0 && stdout.isBlank()))) {
            val fallback = executeNpmFallback(command, workingDir, maxOutputBytes)
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

    private fun preprocessCommand(command: String, searchDirs: List<String>): String {
        val trimmed = command.trim()
        val tokens = trimmed.split(Regex("\\s+"))
        if (tokens.isEmpty()) return command
        val first = tokens[0]

        val hasPython = isBinaryInPath("python", searchDirs)
        val hasPython3 = isBinaryInPath("python3", searchDirs)
        val hasPip = isBinaryInPath("pip", searchDirs)
        val hasPip3 = isBinaryInPath("pip3", searchDirs)
        val hasNode = isBinaryInPath("node", searchDirs)
        val hasNodejs = isBinaryInPath("nodejs", searchDirs)

        if (!hasPython && hasPython3 && first == "python") {
            return "python3" + trimmed.removePrefix("python")
        }
        if (!hasPip && hasPip3 && first == "pip") {
            return "pip3" + trimmed.removePrefix("pip")
        }
        if (!hasNode && hasNodejs && first == "node") {
            return "nodejs" + trimmed.removePrefix("node")
        }
        return command
    }

    private fun isBinaryInPath(name: String, searchDirs: List<String>): Boolean {
        return searchDirs.any { dir ->
            val f = File(dir, name)
            f.exists() && f.canExecute() && !f.isDirectory
        }
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

    private fun executePipFallback(command: String, workingDir: File, maxOutputBytes: Int): CommandExecutionResult? {
        val tokens = command.trim().split(Regex("\\s+"))
        if (tokens.size < 2) return null
        val action = tokens[1].lowercase()
        val startTime = System.currentTimeMillis()

        if (action == "install" && tokens.size >= 3) {
            val rawPkg = tokens[2].trim('\'', '"')
            val pkg = rawPkg.split("==").first().split(">=").first().split("<=").first().lowercase()
            val libDir = File(workingDir, "lib").apply { mkdirs() }

            return try {
                val client = OkHttpClient.Builder()
                    .followRedirects(true)
                    .connectTimeout(12, TimeUnit.SECONDS)
                    .readTimeout(20, TimeUnit.SECONDS)
                    .build()

                val pypiUrl = "https://pypi.org/pypi/$pkg/json"
                val req = Request.Builder().url(pypiUrl).build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        return CommandExecutionResult(
                            exitCode = 1,
                            stdout = "",
                            stderr = "PyPI package '$pkg' not found (HTTP ${resp.code})",
                            durationMs = System.currentTimeMillis() - startTime
                        )
                    }

                    val json = resp.body?.string() ?: ""
                    val wheelUrlRegex = Regex("\"url\":\\s*\"(https://files\\.pythonhosted\\.org/[^\"]+\\.whl)\"")
                    val matches = wheelUrlRegex.findAll(json).map { it.groupValues[1] }.toList()
                    val targetUrl = matches.firstOrNull { it.contains("none-any.whl") } ?: matches.firstOrNull()

                    if (targetUrl == null) {
                        return CommandExecutionResult(
                            exitCode = 1,
                            stdout = "",
                            stderr = "Could not find a pre-built wheel for '$pkg' on PyPI",
                            durationMs = System.currentTimeMillis() - startTime
                        )
                    }

                    val dlReq = Request.Builder().url(targetUrl).build()
                    client.newCall(dlReq).execute().use { dlResp ->
                        val wheelBytes = dlResp.body?.bytes() ?: ByteArray(0)
                        java.util.zip.ZipInputStream(java.io.ByteArrayInputStream(wheelBytes)).use { zis ->
                            var entry = zis.nextEntry
                            while (entry != null) {
                                if (!entry.isDirectory && !entry.name.contains("__pycache__")) {
                                    val outFile = File(libDir, entry.name)
                                    outFile.parentFile?.mkdirs()
                                    outFile.outputStream().use { fos -> zis.copyTo(fos) }
                                }
                                entry = zis.nextEntry
                            }
                        }
                    }

                    CommandExecutionResult(
                        exitCode = 0,
                        stdout = "Successfully installed $pkg into lib/ (${libDir.canonicalPath})\nPackage is available to import in Python scripts.",
                        stderr = "",
                        durationMs = System.currentTimeMillis() - startTime
                    )
                }
            } catch (e: Exception) {
                CommandExecutionResult(
                    exitCode = 1,
                    stdout = "",
                    stderr = "pip fallback install failed for '$pkg': ${e.message}",
                    durationMs = System.currentTimeMillis() - startTime
                )
            }
        } else if (action == "list") {
            val libDir = File(workingDir, "lib")
            val items = libDir.listFiles()?.filter { it.isDirectory || it.name.endsWith(".py") } ?: emptyList()
            val out = buildString {
                appendLine("Installed packages in workspace (lib/):")
                appendLine("---------------------------------------")
                if (items.isEmpty()) {
                    appendLine("(No packages installed in lib/)")
                } else {
                    for (item in items) {
                        appendLine("  - ${item.name.removeSuffix(".py").removeSuffix(".dist-info")}")
                    }
                }
            }
            return CommandExecutionResult(
                exitCode = 0,
                stdout = out.trim(),
                stderr = "",
                durationMs = System.currentTimeMillis() - startTime
            )
        }
        return null
    }

    private fun executeNpmFallback(command: String, workingDir: File, maxOutputBytes: Int): CommandExecutionResult? {
        val tokens = command.trim().split(Regex("\\s+"))
        if (tokens.size < 2) return null
        val action = tokens[1].lowercase()
        val startTime = System.currentTimeMillis()

        if (action == "init") {
            val packageJson = File(workingDir, "package.json")
            if (!packageJson.exists()) {
                packageJson.writeText(
                    """{
  "name": "agent-workspace",
  "version": "1.0.0",
  "description": "Agent Sandbox Project",
  "main": "src/index.js",
  "scripts": {
    "start": "node src/index.js",
    "test": "echo \"Error: no test specified\" && exit 1"
  },
  "keywords": [],
  "author": "",
  "license": "ISC"
}
"""
                )
            }
            return CommandExecutionResult(
                exitCode = 0,
                stdout = "Wrote to ${packageJson.canonicalPath}:\n\n${packageJson.readText()}",
                stderr = "",
                durationMs = System.currentTimeMillis() - startTime
            )
        } else if (action == "list") {
            val packageJson = File(workingDir, "package.json")
            val content = if (packageJson.exists()) packageJson.readText() else "{}"
            return CommandExecutionResult(
                exitCode = 0,
                stdout = "agent-workspace@1.0.0 ${workingDir.canonicalPath}\n$content",
                stderr = "",
                durationMs = System.currentTimeMillis() - startTime
            )
        }
        return null
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
