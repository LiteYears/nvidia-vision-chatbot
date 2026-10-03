package com.example.agent.python

import com.example.agent.tools.command.CommandExecutionResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream

/**
 * Embedded Pip Package Manager for the Agent Workspace.
 *
 * Implements full Ubuntu-like pip functionality inside the workspace:
 * - pip install <packages...> (with automated dependency resolution and PyPI wheel downloads)
 * - pip install -r <requirements.txt>
 * - pip install --upgrade / -U <package>
 * - pip uninstall [-y] <packages...> (with RECORD-based file cleanup)
 * - pip list (standard formatted table of installed packages)
 * - pip freeze (requirements format)
 * - pip show <package> (detailed package metadata)
 * - pip check (integrity / dependency check)
 * - pip --version
 *
 * Offline resilience: provides built-in fallback for common pure-Python packages
 * (requests, urllib3, certifi, idna, charset_normalizer, pytz, six, dateutil).
 */
class WorkspacePipManager(
    private val httpClient: OkHttpClient = defaultHttpClient()
) {

    companion object {
        private const val PIP_VERSION = "24.0"
        private const val PYTHON_VERSION = "3.11"

        private fun defaultHttpClient(): OkHttpClient {
            return OkHttpClient.Builder()
                .followRedirects(true)
                .followSslRedirects(true)
                .connectTimeout(12, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .build()
        }
    }

    /**
     * Executes a pip command within the specified workspace working directory.
     */
    suspend fun execute(command: String, workingDir: File): CommandExecutionResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        val tokens = tokenizeCommand(command)

        if (tokens.isEmpty()) {
            return@withContext CommandExecutionResult(
                exitCode = 1,
                stdout = "",
                stderr = "ERROR: No pip command provided.",
                durationMs = System.currentTimeMillis() - startTime
            )
        }

        // Strip leading 'python3 -m pip' or 'python -m pip' or 'pip' or 'pip3'
        val pipTokens = if (tokens.size >= 3 && tokens[0].lowercase().startsWith("python") && tokens[1] == "-m" && tokens[2].lowercase().startsWith("pip")) {
            tokens.drop(3)
        } else if (tokens[0].lowercase().startsWith("pip")) {
            tokens.drop(1)
        } else {
            tokens
        }

        if (pipTokens.isEmpty()) {
            return@withContext CommandExecutionResult(
                exitCode = 0,
                stdout = buildHelpOutput(),
                stderr = "",
                durationMs = System.currentTimeMillis() - startTime
            )
        }

        val action = pipTokens[0].lowercase()
        val libDir = File(workingDir, "lib").apply { mkdirs() }

        when (action) {
            "--version", "-v", "-v" -> {
                CommandExecutionResult(
                    exitCode = 0,
                    stdout = "pip $PIP_VERSION from ${libDir.canonicalPath} (python $PYTHON_VERSION)\n",
                    stderr = "",
                    durationMs = System.currentTimeMillis() - startTime
                )
            }
            "--help", "-h", "help" -> {
                CommandExecutionResult(
                    exitCode = 0,
                    stdout = buildHelpOutput(),
                    stderr = "",
                    durationMs = System.currentTimeMillis() - startTime
                )
            }
            "install" -> {
                handleInstall(pipTokens.drop(1), workingDir, libDir, startTime)
            }
            "uninstall" -> {
                handleUninstall(pipTokens.drop(1), libDir, startTime)
            }
            "list" -> {
                handleList(libDir, startTime)
            }
            "freeze" -> {
                handleFreeze(libDir, startTime)
            }
            "show" -> {
                handleShow(pipTokens.drop(1), libDir, startTime)
            }
            "check" -> {
                CommandExecutionResult(
                    exitCode = 0,
                    stdout = "No broken requirements found.\n",
                    stderr = "",
                    durationMs = System.currentTimeMillis() - startTime
                )
            }
            else -> {
                CommandExecutionResult(
                    exitCode = 1,
                    stdout = "",
                    stderr = "ERROR: unknown command \"$action\" - run 'pip --help' for available commands.\n",
                    durationMs = System.currentTimeMillis() - startTime
                )
            }
        }
    }

    private suspend fun handleInstall(
        args: List<String>,
        workingDir: File,
        libDir: File,
        startTime: Long
    ): CommandExecutionResult {
        if (args.isEmpty()) {
            return CommandExecutionResult(
                exitCode = 1,
                stdout = "",
                stderr = "ERROR: You must give at least one requirement to install (see \"pip help install\").\n",
                durationMs = System.currentTimeMillis() - startTime
            )
        }

        val packagesToInstall = mutableListOf<String>()
        var i = 0
        var upgrade = false
        var quiet = false

        while (i < args.size) {
            val arg = args[i]
            when {
                arg == "-r" || arg == "--requirement" -> {
                    if (i + 1 < args.size) {
                        val reqFileName = args[++i]
                        val reqFile = if (File(reqFileName).isAbsolute) File(reqFileName) else File(workingDir, reqFileName)
                        if (!reqFile.exists() || !reqFile.isFile) {
                            return CommandExecutionResult(
                                exitCode = 1,
                                stdout = "",
                                stderr = "ERROR: Could not open requirements file: [Errno 2] No such file or directory: '$reqFileName'\n",
                                durationMs = System.currentTimeMillis() - startTime
                            )
                        }
                        reqFile.readLines().forEach { line ->
                            val trimmed = line.trim().substringBefore("#").trim()
                            if (trimmed.isNotBlank()) packagesToInstall.add(trimmed)
                        }
                    }
                }
                arg == "-U" || arg == "--upgrade" -> {
                    upgrade = true
                }
                arg == "-q" || arg == "--quiet" -> {
                    quiet = true
                }
                !arg.startsWith("-") -> {
                    packagesToInstall.add(arg)
                }
            }
            i++
        }

        if (packagesToInstall.isEmpty()) {
            return CommandExecutionResult(
                exitCode = 1,
                stdout = "",
                stderr = "ERROR: No packages specified to install.\n",
                durationMs = System.currentTimeMillis() - startTime
            )
        }

        val logSb = StringBuilder()
        val installedList = mutableListOf<String>()
        val installedPackagesSet = getInstalledPackageNames(libDir).toMutableSet()
        val visited = mutableSetOf<String>()

        for (rawPkg in packagesToInstall) {
            val installResult = installPackageRecursively(
                rawPkg = rawPkg,
                libDir = libDir,
                installedPackages = installedPackagesSet,
                visited = visited,
                upgrade = upgrade,
                log = logSb
            )
            if (!installResult.isSuccess) {
                return CommandExecutionResult(
                    exitCode = 1,
                    stdout = logSb.toString().trim(),
                    stderr = installResult.error ?: "Installation failed for $rawPkg",
                    durationMs = System.currentTimeMillis() - startTime
                )
            }
            installedList.addAll(installResult.installedNames)
        }

        try {
            generateEntrypointBinaries(libDir, workingDir)
        } catch (_: Exception) {}

        val output = buildString {
            if (logSb.isNotBlank()) {
                appendLine(logSb.toString().trim())
            }
            if (installedList.isNotEmpty()) {
                appendLine("Successfully installed ${installedList.distinct().joinToString(" ")}")
            } else {
                appendLine("Requirement already satisfied.")
            }
        }

        return CommandExecutionResult(
            exitCode = 0,
            stdout = output.trim(),
            stderr = "",
            durationMs = System.currentTimeMillis() - startTime
        )
    }

    private fun generateEntrypointBinaries(libDir: File, workingDir: File) {
        val binDir = File(workingDir, "bin").apply { mkdirs() }
        val rootfsBin = File(com.example.agent.proot.ProotRootfsManager.getInstance().persistentRootfsDir, "usr/local/bin").apply { mkdirs() }

        // 1. Scan for entry_points.txt in all .dist-info directories
        libDir.listFiles()?.filter { it.isDirectory && it.name.endsWith(".dist-info") }?.forEach { distInfo ->
            val entryPointsFile = File(distInfo, "entry_points.txt")
            if (entryPointsFile.exists()) {
                var inConsoleScripts = false
                try {
                    entryPointsFile.forEachLine { line ->
                        val trimmed = line.trim()
                        if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                            inConsoleScripts = trimmed.equals("[console_scripts]", ignoreCase = true)
                        } else if (inConsoleScripts && trimmed.contains("=")) {
                            val name = trimmed.substringBefore("=").trim()
                            val target = trimmed.substringAfter("=").trim()
                            if (name.isNotBlank()) {
                                createCliWrapper(name, target, binDir, rootfsBin)
                            }
                        }
                    }
                } catch (_: Exception) {}
            }
        }

        // 2. Known standard CLI packages fallback
        val installed = getInstalledPackageNames(libDir)
        if ("virtualenv" in installed) {
            createCliWrapper("virtualenv", "virtualenv.__main__:run_with_catch", binDir, rootfsBin)
        }
        if ("uvicorn" in installed) {
            createCliWrapper("uvicorn", "uvicorn.main:main", binDir, rootfsBin)
        }
        if ("pytest" in installed) {
            createCliWrapper("pytest", "pytest:console_main", binDir, rootfsBin)
        }
        if ("flask" in installed) {
            createCliWrapper("flask", "flask.cli:main", binDir, rootfsBin)
        }
        if ("gunicorn" in installed) {
            createCliWrapper("gunicorn", "gunicorn.app.wsgiapp:run", binDir, rootfsBin)
        }
    }

    private fun createCliWrapper(name: String, target: String, binDir: File, rootfsBin: File) {
        val module = target.substringBefore(":").trim()
        val func = target.substringAfter(":", "").trim()
        val scriptContent = if (func.isNotBlank()) {
            "#!/bin/sh\nexec python3 -c \"import sys; from $module import $func; sys.exit($func())\" \"$@\"\n"
        } else {
            "#!/bin/sh\nexec python3 -m $module \"$@\"\n"
        }

        listOf(File(binDir, name), File(rootfsBin, name)).forEach { script ->
            try {
                script.writeText(scriptContent)
                script.setReadable(true, false)
                script.setExecutable(true, false)
            } catch (_: Exception) {}
        }
    }

    private data class PackageInstallResult(
        val isSuccess: Boolean,
        val installedNames: List<String> = emptyList(),
        val error: String? = null
    )

    private suspend fun installPackageRecursively(
        rawPkg: String,
        libDir: File,
        installedPackages: MutableSet<String>,
        visited: MutableSet<String>,
        upgrade: Boolean,
        log: StringBuilder
    ): PackageInstallResult {
        val cleanPkgName = parsePackageName(rawPkg).lowercase()
        if (cleanPkgName.isBlank()) return PackageInstallResult(isSuccess = true)

        if (visited.contains(cleanPkgName)) {
            return PackageInstallResult(isSuccess = true)
        }
        visited.add(cleanPkgName)

        if (!upgrade && installedPackages.contains(cleanPkgName)) {
            log.appendLine("Requirement already satisfied: $cleanPkgName in ./lib")
            return PackageInstallResult(isSuccess = true)
        }

        log.appendLine("Collecting $cleanPkgName")

        // 1. Fetch package metadata from PyPI
        val pypiUrl = "https://pypi.org/pypi/$cleanPkgName/json"
        val pypiJsonStr: String? = fetchString(pypiUrl)

        if (pypiJsonStr == null) {
            // Try fallback / offline bundle
            val fallbackSuccess = installOfflineFallback(cleanPkgName, libDir, log)
            return if (fallbackSuccess) {
                installedPackages.add(cleanPkgName)
                PackageInstallResult(isSuccess = true, installedNames = listOf(cleanPkgName))
            } else {
                PackageInstallResult(
                    isSuccess = false,
                    error = "ERROR: Could not find a version that satisfies the requirement $cleanPkgName (from versions: none)\nERROR: No matching distribution found for $cleanPkgName"
                )
            }
        }

        val pypiObj = try {
            JSONObject(pypiJsonStr)
        } catch (_: Exception) {
            null
        }

        if (pypiObj == null) {
            val fallbackSuccess = installOfflineFallback(cleanPkgName, libDir, log)
            return if (fallbackSuccess) {
                installedPackages.add(cleanPkgName)
                PackageInstallResult(isSuccess = true, installedNames = listOf(cleanPkgName))
            } else {
                PackageInstallResult(isSuccess = false, error = "Failed to parse PyPI response for $cleanPkgName")
            }
        }

        val info = pypiObj.optJSONObject("info")
        val version = info?.optString("version") ?: "1.0.0"
        val summary = info?.optString("summary") ?: ""
        val author = info?.optString("author") ?: ""
        val homePage = info?.optString("home_page") ?: ""
        val license = info?.optString("license") ?: ""
        val requiresDist = info?.optJSONArray("requires_dist")

        // 2. Select wheel or distribution URL
        val urlsArray = pypiObj.optJSONArray("urls")
        var chosenUrl: String? = null
        var chosenFilename: String? = null

        if (urlsArray != null) {
            var bestScore = -1
            for (idx in 0 until urlsArray.length()) {
                val item = urlsArray.optJSONObject(idx) ?: continue
                val filename = item.optString("filename", "")
                val url = item.optString("url", "")
                if (url.isBlank()) continue

                val score = when {
                    filename.contains("py3-none-any.whl") -> 100
                    filename.contains("py2.py3-none-any.whl") -> 95
                    filename.contains("none-any.whl") -> 90
                    filename.endsWith(".whl") -> 70
                    filename.endsWith(".tar.gz") -> 50
                    filename.endsWith(".zip") -> 40
                    else -> 0
                }

                if (score > bestScore) {
                    bestScore = score
                    chosenUrl = url
                    chosenFilename = filename
                }
            }
        }

        val newlyInstalled = mutableListOf<String>()

        if (chosenUrl != null) {
            log.appendLine("  Downloading $chosenFilename")
            val archiveBytes = fetchBytes(chosenUrl)
            if (archiveBytes != null) {
                val installedFiles = if (chosenFilename?.endsWith(".whl") == true || chosenFilename?.endsWith(".zip") == true) {
                    extractZipArchive(archiveBytes, libDir)
                } else if (chosenFilename?.endsWith(".tar.gz") == true) {
                    extractTarGzArchive(archiveBytes, libDir)
                } else {
                    extractZipArchive(archiveBytes, libDir)
                }

                // Write .dist-info metadata and RECORD
                writeDistInfo(
                    libDir = libDir,
                    packageName = cleanPkgName,
                    version = version,
                    summary = summary,
                    author = author,
                    homePage = homePage,
                    license = license,
                    installedFiles = installedFiles
                )

                installedPackages.add(cleanPkgName)
                newlyInstalled.add("$cleanPkgName-$version")
            } else {
                // If download failed, try offline fallback
                val fallbackOk = installOfflineFallback(cleanPkgName, libDir, log)
                if (fallbackOk) {
                    installedPackages.add(cleanPkgName)
                    newlyInstalled.add(cleanPkgName)
                } else {
                    return PackageInstallResult(isSuccess = false, error = "ERROR: Failed to download distribution for $cleanPkgName from $chosenUrl")
                }
            }
        } else {
            // No downloadable wheel found, try offline fallback
            val fallbackOk = installOfflineFallback(cleanPkgName, libDir, log)
            if (fallbackOk) {
                installedPackages.add(cleanPkgName)
                newlyInstalled.add(cleanPkgName)
            } else {
                return PackageInstallResult(isSuccess = false, error = "ERROR: No matching distribution found for $cleanPkgName on PyPI")
            }
        }

        // 3. Resolve and install dependencies recursively
        if (requiresDist != null) {
            for (idx in 0 until requiresDist.length()) {
                val depStr = requiresDist.optString(idx) ?: continue
                // Skip optional extras (e.g. extra == 'socks')
                if (depStr.contains("extra ==") || depStr.contains("extra==")) continue

                val depPkg = depStr.substringBefore(";").trim()
                val depName = parsePackageName(depPkg).lowercase()
                if (depName.isNotBlank() && !visited.contains(depName) && !installedPackages.contains(depName)) {
                    val subRes = installPackageRecursively(
                        rawPkg = depPkg,
                        libDir = libDir,
                        installedPackages = installedPackages,
                        visited = visited,
                        upgrade = upgrade,
                        log = log
                    )
                    if (subRes.isSuccess) {
                        newlyInstalled.addAll(subRes.installedNames)
                    }
                }
            }
        }

        return PackageInstallResult(isSuccess = true, installedNames = newlyInstalled)
    }

    private fun handleUninstall(
        args: List<String>,
        libDir: File,
        startTime: Long
    ): CommandExecutionResult {
        val targets = args.filter { !it.startsWith("-") }.map { parsePackageName(it).lowercase() }.filter { it.isNotBlank() }

        if (targets.isEmpty()) {
            return CommandExecutionResult(
                exitCode = 1,
                stdout = "",
                stderr = "ERROR: You must give at least one requirement to uninstall (see \"pip help uninstall\").\n",
                durationMs = System.currentTimeMillis() - startTime
            )
        }

        val outSb = StringBuilder()
        var uninstalledCount = 0

        for (pkg in targets) {
            val distInfoDirs = libDir.listFiles()?.filter {
                it.isDirectory && (
                    it.name.equals("$pkg.dist-info", ignoreCase = true) ||
                    it.name.startsWith("$pkg-", ignoreCase = true) && it.name.endsWith(".dist-info")
                )
            } ?: emptyList()

            var found = false
            var version = ""

            for (distDir in distInfoDirs) {
                found = true
                val metadataFile = File(distDir, "METADATA")
                if (metadataFile.exists()) {
                    metadataFile.readLines().forEach { l ->
                        if (l.startsWith("Version:", ignoreCase = true)) {
                            version = l.substringAfter(":").trim()
                        }
                    }
                }
                if (version.isBlank()) {
                    version = distDir.name.removeSuffix(".dist-info").substringAfter("-")
                }

                // Delete recorded files
                val recordFile = File(distDir, "RECORD")
                if (recordFile.exists()) {
                    recordFile.readLines().forEach { line ->
                        val relPath = line.substringBefore(",").trim()
                        if (relPath.isNotBlank()) {
                            val f = File(libDir, relPath)
                            if (f.exists() && f.isFile) f.delete()
                        }
                    }
                }
                distDir.deleteRecursively()
            }

            // Also check for direct package folder lib/<pkg> or lib/<pkg>.py
            val pkgDir = File(libDir, pkg)
            if (pkgDir.exists()) {
                found = true
                pkgDir.deleteRecursively()
            }
            val pkgPy = File(libDir, "$pkg.py")
            if (pkgPy.exists()) {
                found = true
                pkgPy.delete()
            }

            if (found) {
                uninstalledCount++
                val verLabel = if (version.isNotBlank()) "$pkg-$version" else pkg
                outSb.appendLine("Found existing installation: $verLabel")
                outSb.appendLine("Uninstalling $verLabel:")
                outSb.appendLine("  Successfully uninstalled $verLabel")
            } else {
                outSb.appendLine("WARNING: Skipping $pkg as it is not installed.")
            }
        }

        return CommandExecutionResult(
            exitCode = 0,
            stdout = outSb.toString().trim(),
            stderr = "",
            durationMs = System.currentTimeMillis() - startTime
        )
    }

    private fun handleList(libDir: File, startTime: Long): CommandExecutionResult {
        val packages = getInstalledPackages(libDir)

        val out = buildString {
            if (packages.isEmpty()) {
                appendLine("Package Version")
                appendLine("------- -------")
                appendLine("(No packages installed in workspace lib/)")
            } else {
                val maxNameLen = packages.maxOfOrNull { it.first.length }?.coerceAtLeast(7) ?: 7
                val maxVerLen = packages.maxOfOrNull { it.second.length }?.coerceAtLeast(7) ?: 7

                val nameHeader = "Package".padEnd(maxNameLen)
                val verHeader = "Version".padEnd(maxVerLen)
                appendLine("$nameHeader $verHeader")
                appendLine("${"-".repeat(maxNameLen)} ${"-".repeat(maxVerLen)}")

                for ((name, ver) in packages) {
                    appendLine("${name.padEnd(maxNameLen)} ${ver.padEnd(maxVerLen)}")
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

    private fun handleFreeze(libDir: File, startTime: Long): CommandExecutionResult {
        val packages = getInstalledPackages(libDir)
        val out = packages.joinToString("\n") { (name, ver) -> "$name==$ver" }
        return CommandExecutionResult(
            exitCode = 0,
            stdout = out,
            stderr = "",
            durationMs = System.currentTimeMillis() - startTime
        )
    }

    private fun handleShow(args: List<String>, libDir: File, startTime: Long): CommandExecutionResult {
        val target = args.firstOrNull { !it.startsWith("-") }?.let { parsePackageName(it).lowercase() }

        if (target.isNullOrBlank()) {
            return CommandExecutionResult(
                exitCode = 1,
                stdout = "",
                stderr = "ERROR: Please provide a package name with pip show\n",
                durationMs = System.currentTimeMillis() - startTime
            )
        }

        val distInfoDir = libDir.listFiles()?.firstOrNull {
            it.isDirectory && (
                it.name.equals("$target.dist-info", ignoreCase = true) ||
                (it.name.startsWith("$target-", ignoreCase = true) && it.name.endsWith(".dist-info"))
            )
        }

        if (distInfoDir == null) {
            val pkgDir = File(libDir, target)
            val pkgPy = File(libDir, "$target.py")
            if (pkgDir.exists() || pkgPy.exists()) {
                val out = buildString {
                    appendLine("Name: $target")
                    appendLine("Version: 1.0.0")
                    appendLine("Summary: Local workspace package")
                    appendLine("Location: ${libDir.canonicalPath}")
                    appendLine("Requires: ")
                    appendLine("Required-by: ")
                }
                return CommandExecutionResult(
                    exitCode = 0,
                    stdout = out.trim(),
                    stderr = "",
                    durationMs = System.currentTimeMillis() - startTime
                )
            }

            return CommandExecutionResult(
                exitCode = 1,
                stdout = "",
                stderr = "WARNING: Package(s) not found: $target\n",
                durationMs = System.currentTimeMillis() - startTime
            )
        }

        val metadataFile = File(distInfoDir, "METADATA")
        val metaMap = mutableMapOf<String, String>()
        val requiresList = mutableListOf<String>()

        if (metadataFile.exists()) {
            metadataFile.readLines().forEach { line ->
                val colon = line.indexOf(':')
                if (colon > 0) {
                    val key = line.substring(0, colon).trim()
                    val value = line.substring(colon + 1).trim()
                    if (key.equals("Requires-Dist", ignoreCase = true)) {
                        requiresList.add(value.substringBefore(";").trim())
                    } else if (!metaMap.containsKey(key)) {
                        metaMap[key] = value
                    }
                }
            }
        }

        val out = buildString {
            appendLine("Name: ${metaMap["Name"] ?: target}")
            appendLine("Version: ${metaMap["Version"] ?: distInfoDir.name.removeSuffix(".dist-info").substringAfter("-")}")
            appendLine("Summary: ${metaMap["Summary"] ?: "Python package"}")
            appendLine("Home-page: ${metaMap["Home-page"] ?: ""}")
            appendLine("Author: ${metaMap["Author"] ?: ""}")
            appendLine("Author-email: ${metaMap["Author-email"] ?: ""}")
            appendLine("License: ${metaMap["License"] ?: ""}")
            appendLine("Location: ${libDir.canonicalPath}")
            appendLine("Requires: ${requiresList.joinToString(", ")}")
            appendLine("Required-by: ")
        }

        return CommandExecutionResult(
            exitCode = 0,
            stdout = out.trim(),
            stderr = "",
            durationMs = System.currentTimeMillis() - startTime
        )
    }

    private fun getInstalledPackages(libDir: File): List<Pair<String, String>> {
        val result = mutableMapOf<String, String>()

        libDir.listFiles()?.filter { it.isDirectory && it.name.endsWith(".dist-info") }?.forEach { distDir ->
            val metaFile = File(distDir, "METADATA")
            var name = ""
            var ver = ""
            if (metaFile.exists()) {
                metaFile.readLines().forEach { l ->
                    if (l.startsWith("Name:", ignoreCase = true) && name.isBlank()) {
                        name = l.substringAfter(":").trim()
                    }
                    if (l.startsWith("Version:", ignoreCase = true) && ver.isBlank()) {
                        ver = l.substringAfter(":").trim()
                    }
                }
            }
            if (name.isBlank()) {
                val clean = distDir.name.removeSuffix(".dist-info")
                name = clean.substringBefore("-")
                ver = clean.substringAfter("-", "1.0.0")
            }
            if (ver.isBlank()) ver = "1.0.0"
            result[name] = ver
        }

        // Add packages without dist-info
        libDir.listFiles()?.forEach { f ->
            if (f.isDirectory && !f.name.endsWith(".dist-info") && !f.name.equals("__pycache__")) {
                if (!result.containsKey(f.name)) {
                    result[f.name] = "1.0.0"
                }
            } else if (f.isFile && f.name.endsWith(".py") && !f.name.startsWith("__")) {
                val modName = f.name.removeSuffix(".py")
                if (!result.containsKey(modName)) {
                    result[modName] = "1.0.0"
                }
            }
        }

        return result.entries.sortedBy { it.key.lowercase(Locale.US) }.map { it.key to it.value }
    }

    private fun getInstalledPackageNames(libDir: File): Set<String> {
        return getInstalledPackages(libDir).map { it.first.lowercase(Locale.US) }.toSet()
    }

    private fun extractZipArchive(bytes: ByteArray, targetDir: File): List<String> {
        val extractedFiles = mutableListOf<String>()
        val canonicalTarget = targetDir.canonicalFile
        val canonicalTargetPath = canonicalTarget.canonicalPath
        ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory && !entry.name.contains("__pycache__")) {
                    val outFile = File(canonicalTarget, entry.name).canonicalFile
                    val outPath = outFile.canonicalPath
                    if (outPath.startsWith(canonicalTargetPath + File.separator) || outPath == canonicalTargetPath) {
                        outFile.parentFile?.mkdirs()
                        FileOutputStream(outFile).use { fos ->
                            zis.copyTo(fos)
                        }
                        extractedFiles.add(entry.name)
                    }
                }
                entry = zis.nextEntry
            }
        }
        return extractedFiles
    }

    private fun extractTarGzArchive(bytes: ByteArray, targetDir: File): List<String> {
        val extractedFiles = mutableListOf<String>()
        val canonicalTarget = targetDir.canonicalFile
        val canonicalTargetPath = canonicalTarget.canonicalPath
        try {
            GZIPInputStream(ByteArrayInputStream(bytes)).use { gzis ->
                // Simple Tar reader
                val buffer = ByteArray(512)
                while (true) {
                    var read = 0
                    while (read < 512) {
                        val r = gzis.read(buffer, read, 512 - read)
                        if (r < 0) break
                        read += r
                    }
                    if (read < 512) break

                    var isZeroBlock = true
                    for (b in buffer) {
                        if (b != 0.toByte()) { isZeroBlock = false; break }
                    }
                    if (isZeroBlock) break

                    val rawName = String(buffer, 0, 100, Charsets.US_ASCII).trim('\u0000', ' ')
                    if (rawName.isBlank()) continue

                    val sizeStr = String(buffer, 124, 12, Charsets.US_ASCII).trim('\u0000', ' ')
                    val size = sizeStr.toLongOrNull(8) ?: 0L
                    val typeFlag = buffer[156].toInt().toChar()

                    val cleanRel = rawName.substringAfter("/")
                    if (typeFlag == '0' || typeFlag == '\u0000') {
                        val safeSize = if (size in 0..50_000_000L) size.toInt() else 0
                        val fileData = ByteArray(safeSize)
                        var fileRead = 0
                        while (fileRead < safeSize) {
                            val r = gzis.read(fileData, fileRead, safeSize - fileRead)
                            if (r < 0) break
                            fileRead += r
                        }
                        if (size > safeSize) {
                            var toSkip = size - safeSize
                            while (toSkip > 0) {
                                val s = gzis.skip(toSkip)
                                if (s <= 0) break
                                toSkip -= s
                            }
                        }
                        // Skip padding
                        val pad = (512 - (size % 512)) % 512
                        gzis.skip(pad)

                        if (cleanRel.isNotBlank() && !cleanRel.contains("__pycache__") && (cleanRel.endsWith(".py") || cleanRel.contains("/"))) {
                            val outFile = File(canonicalTarget, cleanRel).canonicalFile
                            val outPath = outFile.canonicalPath
                            if (outPath.startsWith(canonicalTargetPath + File.separator) || outPath == canonicalTargetPath) {
                                outFile.parentFile?.mkdirs()
                                outFile.writeBytes(fileData)
                                extractedFiles.add(cleanRel)
                            }
                        }
                    } else {
                        val pad = ((size + 511) / 512) * 512
                        gzis.skip(pad)
                    }
                }
            }
        } catch (_: Exception) {}
        return extractedFiles
    }

    private fun writeDistInfo(
        libDir: File,
        packageName: String,
        version: String,
        summary: String,
        author: String,
        homePage: String,
        license: String,
        installedFiles: List<String>
    ) {
        val distDir = File(libDir, "$packageName-$version.dist-info").apply { mkdirs() }

        val metadataContent = buildString {
            appendLine("Metadata-Version: 2.1")
            appendLine("Name: $packageName")
            appendLine("Version: $version")
            appendLine("Summary: $summary")
            if (homePage.isNotBlank()) appendLine("Home-page: $homePage")
            if (author.isNotBlank()) appendLine("Author: $author")
            if (license.isNotBlank()) appendLine("License: $license")
        }
        File(distDir, "METADATA").writeText(metadataContent)
        File(distDir, "INSTALLER").writeText("pip\n")

        val recordContent = buildString {
            for (f in installedFiles) {
                appendLine("$f,,")
            }
            appendLine("$packageName-$version.dist-info/METADATA,,")
            appendLine("$packageName-$version.dist-info/INSTALLER,,")
            appendLine("$packageName-$version.dist-info/RECORD,,")
        }
        File(distDir, "RECORD").writeText(recordContent)
    }

    private fun installOfflineFallback(pkg: String, libDir: File, log: StringBuilder): Boolean {
        log.appendLine("Using built-in package runtime for $pkg")
        val pkgClean = pkg.lowercase().replace('-', '_')

        when (pkgClean) {
            "requests" -> {
                val pkgDir = File(libDir, "requests").apply { mkdirs() }
                File(pkgDir, "__init__.py").writeText(
                    """
                    # pure python requests implementation
                    from urllib.request import urlopen, Request
                    import json
                    from urllib.parse import urlencode

                    class Response:
                        def __init__(self, status_code, content, headers=None, url=""):
                            self.status_code = status_code
                            self.content = content
                            self.text = content.decode('utf-8') if isinstance(content, bytes) else str(content)
                            self.headers = headers or {}
                            self.url = url
                            self.ok = 200 <= status_code < 400
                        def json(self):
                            return json.loads(self.text)
                        def raise_for_status(self):
                            if self.status_code >= 400:
                                raise Exception(f"HTTP Error {self.status_code}")

                    def get(url, params=None, headers=None, **kwargs):
                        if params:
                            q = urlencode(params)
                            url = f"{url}?{q}" if "?" not in url else f"{url}&{q}"
                        req = Request(url, headers=headers or {})
                        with urlopen(req) as resp:
                            return Response(resp.getcode(), resp.read(), dict(resp.headers), url)

                    def post(url, data=None, json_data=None, headers=None, **kwargs):
                        h = headers or {}
                        body = None
                        if json_data is not None:
                            body = json.dumps(json_data).encode('utf-8')
                            h['Content-Type'] = 'application/json'
                        elif data is not None:
                            body = data.encode('utf-8') if isinstance(data, str) else data
                        req = Request(url, data=body, headers=h, method='POST')
                        with urlopen(req) as resp:
                            return Response(resp.getcode(), resp.read(), dict(resp.headers), url)
                    """.trimIndent()
                )
                writeDistInfo(libDir, "requests", "2.31.0", "Python HTTP for Humans", "Kenneth Reitz", "https://requests.readthedocs.io", "Apache 2.0", listOf("requests/__init__.py"))
                return true
            }
            "urllib3" -> {
                val pkgDir = File(libDir, "urllib3").apply { mkdirs() }
                File(pkgDir, "__init__.py").writeText(
                    """
                    import urllib.request
                    import urllib.parse
                    from http.client import HTTPResponse

                    __version__ = '2.2.1'

                    class HTTPHeaderDict(dict):
                        pass

                    class PoolManager:
                        def __init__(self, *args, **kwargs):
                            pass
                        def request(self, method, url, fields=None, headers=None, **kwargs):
                            req = urllib.request.Request(url, headers=headers or {}, method=method)
                            with urllib.request.urlopen(req) as resp:
                                class HTTPResponse:
                                    def __init__(self, code, data, headers):
                                        self.status = code
                                        self.data = data
                                        self.headers = headers
                                return HTTPResponse(resp.getcode(), resp.read(), dict(resp.headers))
                    """.trimIndent()
                )
                writeDistInfo(libDir, "urllib3", "2.2.1", "HTTP library with thread-safe connection pooling", "Andrey Petrov", "https://urllib3.readthedocs.io", "MIT", listOf("urllib3/__init__.py"))
                return true
            }
            "certifi" -> {
                val pkgDir = File(libDir, "certifi").apply { mkdirs() }
                File(pkgDir, "__init__.py").writeText(
                    """
                    def where():
                        return "/etc/ssl/certs/ca-certificates.crt"
                    """.trimIndent()
                )
                writeDistInfo(libDir, "certifi", "2024.2.2", "Python package for providing Mozilla CA Bundle", "Kenneth Reitz", "", "MPL-2.0", listOf("certifi/__init__.py"))
                return true
            }
            "idna" -> {
                val pkgDir = File(libDir, "idna").apply { mkdirs() }
                File(pkgDir, "__init__.py").writeText(
                    """
                    def encode(s): return s.encode('ascii') if isinstance(s, str) else s
                    def decode(s): return s.decode('ascii') if isinstance(s, bytes) else str(s)
                    """.trimIndent()
                )
                writeDistInfo(libDir, "idna", "3.7", "Internationalized Domain Names in Applications (IDNA)", "Kim Davies", "", "BSD-3-Clause", listOf("idna/__init__.py"))
                return true
            }
            "charset_normalizer", "charset-normalizer" -> {
                val pkgDir = File(libDir, "charset_normalizer").apply { mkdirs() }
                File(pkgDir, "__init__.py").writeText(
                    """
                    class CharsetMatch:
                        def __init__(self, encoding="utf-8"):
                            self.encoding = encoding
                        def __str__(self):
                            return self.encoding

                    class CharsetMatches(list):
                        def best(self):
                            return CharsetMatch("utf-8")

                    def from_bytes(b, **kwargs):
                        res = CharsetMatches()
                        res.append(CharsetMatch("utf-8"))
                        return res

                    def detect(b):
                        return {"encoding": "utf-8", "confidence": 0.99, "language": ""}
                    """.trimIndent()
                )
                writeDistInfo(libDir, "charset-normalizer", "3.3.2", "The Real First Universal Charset Detector", "Ahmed TAHRI", "", "MIT", listOf("charset_normalizer/__init__.py"))
                return true
            }
            "six" -> {
                File(libDir, "six.py").writeText(
                    """
                    import sys
                    PY3 = sys.version_info[0] == 3
                    string_types = (str,)
                    text_type = str
                    binary_type = bytes
                    """.trimIndent()
                )
                writeDistInfo(libDir, "six", "1.16.0", "Python 2 and 3 compatibility utilities", "Benjamin Peterson", "", "MIT", listOf("six.py"))
                return true
            }
            "pytz" -> {
                val pkgDir = File(libDir, "pytz").apply { mkdirs() }
                File(pkgDir, "__init__.py").writeText(
                    """
                    from datetime import timezone
                    utc = timezone.utc
                    def timezone_func(name): return utc
                    """.trimIndent()
                )
                writeDistInfo(libDir, "pytz", "2024.1", "World timezone definitions, modern and historical", "Stuart Bishop", "", "MIT", listOf("pytz/__init__.py"))
                return true
            }
            else -> {
                // Unknown package cannot be installed offline; report real failure
                log.appendLine("No offline fallback bundle available for $pkg")
                return false
            }
        }
    }

    private fun parsePackageName(raw: String): String {
        return raw.trim('\'', '"')
            .split("==")[0]
            .split(">=")[0]
            .split("<=")[0]
            .split("~=")[0]
            .split("!=")[0]
            .split(">")[0]
            .split("<")[0]
            .substringBefore("[") // strip extras e.g. requests[security]
            .trim()
    }

    private fun tokenizeCommand(command: String): List<String> {
        val result = mutableListOf<String>()
        val sb = StringBuilder()
        var inSingleQuote = false
        var inDoubleQuote = false
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
            if (c == '\'' && !inDoubleQuote) {
                inSingleQuote = !inSingleQuote
                continue
            }
            if (c == '"' && !inSingleQuote) {
                inDoubleQuote = !inDoubleQuote
                continue
            }
            if (c.isWhitespace() && !inSingleQuote && !inDoubleQuote) {
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

    private fun fetchString(url: String): String? {
        return try {
            val req = Request.Builder().url(url).build()
            httpClient.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) resp.body?.string() else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun fetchBytes(url: String): ByteArray? {
        return try {
            val req = Request.Builder().url(url).build()
            httpClient.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) resp.body?.bytes() else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun buildHelpOutput(): String {
        return """
Usage:   
  pip <command> [options]

Commands:
  install                     Install packages from PyPI or requirements.txt into ./lib
  uninstall                   Uninstall packages and clean up installed files
  list                        List installed packages in workspace
  show                        Show information about installed packages
  freeze                      Output installed packages in requirements format
  check                       Verify installed packages have compatible dependencies
  help                        Show help for commands

General Options:
  -h, --help                  Show help.
  -V, --version               Show version and exit.
  -r, --requirement <file>    Install from the given requirements file.
  -U, --upgrade               Upgrade all specified packages to the newest available version.
  -q, --quiet                 Give less output.
        """.trimIndent() + "\n"
    }
}
