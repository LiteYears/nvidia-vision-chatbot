package com.example.agent.proot

import com.example.agent.python.WorkspacePipManager
import com.example.agent.tools.command.CommandExecutionResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Advanced APT and DPKG Package Manager emulation for Ubuntu PRoot environment.
 *
 * Provides real-feeling Debian/Ubuntu package management:
 * - apt update / apt-get update
 * - apt install [-y] <packages...> / apt-get install
 * - apt remove [-y] <packages...> / apt purge
 * - apt list [--installed]
 * - dpkg -l
 * - dpkg -s <pkg>
 *
 * Intelligently bridges python3-* packages (e.g. python3-requests, python3-bs4, python3-dateutil)
 * directly into WorkspacePipManager so Python scripts can immediately import them!
 */
class ProotAptManager(
    private val pipManager: WorkspacePipManager = WorkspacePipManager(),
    private val rootfsManager: ProotRootfsManager = ProotRootfsManager.getInstance()
) {

    suspend fun execute(command: String, workingDir: File, workspaceRoot: File): CommandExecutionResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        val tokens = command.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (tokens.isEmpty()) {
            return@withContext CommandExecutionResult(0, "", "", 0)
        }

        rootfsManager.ensureRootfs(workspaceRoot)

        val cleanTokens = if (tokens.firstOrNull()?.lowercase() == "sudo") tokens.drop(1) else tokens
        if (cleanTokens.isEmpty()) {
            return@withContext CommandExecutionResult(0, "", "", 0)
        }

        val firstRaw = cleanTokens[0].lowercase().trim('\'', '"')
        val first = if (firstRaw.contains('/')) File(firstRaw).name else firstRaw
        val isApt = first == "apt" || first == "apt-get"
        val isDpkg = first == "dpkg"

        if (isApt) {
            handleApt(cleanTokens.drop(1), workingDir, workspaceRoot, startTime)
        } else if (isDpkg) {
            handleDpkg(cleanTokens.drop(1), workspaceRoot, startTime)
        } else {
            CommandExecutionResult(1, "", "Unknown package tool: $first\n", 5)
        }
    }

    private suspend fun handleApt(
        args: List<String>,
        workingDir: File,
        workspaceRoot: File,
        startTime: Long
    ): CommandExecutionResult {
        if (args.isEmpty()) {
            val help = """
            apt 2.4.12 (amd64)
            Usage: apt [options] command

            commands:
              list - list packages based on names
              search - search in package descriptions
              show - show package details
              install - install packages
              remove - remove packages
              purge - remove packages and their configuration files
              update - update list of available packages
              upgrade - upgrade the system by installing/upgrading packages
            """.trimIndent() + "\n"
            return CommandExecutionResult(0, help, "", 5)
        }

        val nonFlags = args.filter { !it.startsWith("-") }
        val action = nonFlags.firstOrNull()?.lowercase() ?: "update"
        val packages = nonFlags.drop(1)

        when (action) {
            "update" -> {
                val out = """
                Hit:1 http://archive.ubuntu.com/ubuntu jammy InRelease
                Hit:2 http://archive.ubuntu.com/ubuntu jammy-updates InRelease
                Hit:3 http://archive.ubuntu.com/ubuntu jammy-backports InRelease
                Hit:4 http://security.ubuntu.com/ubuntu jammy-security InRelease
                Reading package lists... Done
                Building dependency tree... Done
                Reading state information... Done
                All packages are up to date.
                """.trimIndent() + "\n"
                return CommandExecutionResult(0, out, "", System.currentTimeMillis() - startTime)
            }
            "upgrade" -> {
                val out = """
                Reading package lists... Done
                Building dependency tree... Done
                Reading state information... Done
                Calculating upgrade... Done
                0 upgraded, 0 newly installed, 0 to remove and 0 not upgraded.
                """.trimIndent() + "\n"
                return CommandExecutionResult(0, out, "", System.currentTimeMillis() - startTime)
            }
            "install" -> {
                if (packages.isEmpty()) {
                    return CommandExecutionResult(1, "", "apt install: missing package name\n", 5)
                }

                val outSb = StringBuilder()
                outSb.appendLine("Reading package lists... Done")
                outSb.appendLine("Building dependency tree... Done")
                outSb.appendLine("Reading state information... Done")
                outSb.appendLine("The following NEW packages will be installed:")
                outSb.appendLine("  ${packages.joinToString(" ")}")
                outSb.appendLine("0 upgraded, ${packages.size} newly installed, 0 to remove and 0 not upgraded.")
                outSb.appendLine("Need to get 142 kB of archives.")
                outSb.appendLine("After this operation, 480 kB of additional disk space will be used.")

                for ((idx, pkg) in packages.withIndex()) {
                    outSb.appendLine("Get:${idx + 1} http://archive.ubuntu.com/ubuntu jammy/main amd64 $pkg all [142 kB]")
                }
                outSb.appendLine("Fetched 142 kB in 0s (1,240 kB/s)")

                // Install packages
                for (pkg in packages) {
                    outSb.appendLine("Selecting previously unselected package $pkg.")
                    outSb.appendLine("Preparing to unpack .../$pkg.deb ...")
                    outSb.appendLine("Unpacking $pkg ...")
                    outSb.appendLine("Setting up $pkg ...")

                    // If it's a python package e.g. python3-requests, python3-pip, python3-bs4
                    if (pkg.startsWith("python3-") || pkg.startsWith("python-")) {
                        val pipPkgName = pkg.removePrefix("python3-").removePrefix("python-")
                        // Run pip install under the hood
                        try {
                            pipManager.execute("pip install $pipPkgName", workspaceRoot)
                        } catch (_: Throwable) {
                        }
                    }

                    // Register in dpkg status
                    registerDpkgPackage(pkg, "1.0.0", workspaceRoot)
                }

                return CommandExecutionResult(0, outSb.toString().trim() + "\n", "", System.currentTimeMillis() - startTime)
            }
            "remove", "purge" -> {
                if (packages.isEmpty()) {
                    return CommandExecutionResult(1, "", "apt remove: missing package name\n", 5)
                }
                val outSb = StringBuilder()
                outSb.appendLine("Reading package lists... Done")
                outSb.appendLine("Building dependency tree... Done")
                outSb.appendLine("Reading state information... Done")
                outSb.appendLine("The following packages will be REMOVED:")
                outSb.appendLine("  ${packages.joinToString(" ")}")
                for (pkg in packages) {
                    outSb.appendLine("Removing $pkg ...")
                    if (pkg.startsWith("python3-")) {
                        val pipPkgName = pkg.removePrefix("python3-")
                        pipManager.execute("pip uninstall -y $pipPkgName", workspaceRoot)
                    }
                    unregisterDpkgPackage(pkg, workspaceRoot)
                }
                return CommandExecutionResult(0, outSb.toString().trim() + "\n", "", System.currentTimeMillis() - startTime)
            }
            "list" -> {
                val statusFile = File(workspaceRoot, "${ProotRootfsManager.ROOTFS_DIR}/var/lib/dpkg/status")
                val installed = parseDpkgStatus(statusFile)
                val out = buildString {
                    appendLine("Listing... Done")
                    for ((pkg, ver) in installed) {
                        appendLine("$pkg/jammy,now $ver amd64 [installed]")
                    }
                }
                return CommandExecutionResult(0, out, "", System.currentTimeMillis() - startTime)
            }
            else -> {
                return CommandExecutionResult(0, "apt $action: operation completed.\n", "", 5)
            }
        }
    }

    private fun handleDpkg(args: List<String>, workspaceRoot: File, startTime: Long): CommandExecutionResult {
        val flag = args.firstOrNull()?.lowercase() ?: "-l"
        val statusFile = File(workspaceRoot, "${ProotRootfsManager.ROOTFS_DIR}/var/lib/dpkg/status")
        val installed = parseDpkgStatus(statusFile)

        when (flag) {
            "-l", "--list" -> {
                val out = buildString {
                    appendLine("Desired=Unknown/Install/Remove/Purge/Hold")
                    appendLine("| Status=Not/Inst/Conf-files/Unpacked/halF-conf/Half-inst/trig-aWait/Trig-pend")
                    appendLine("|/ Err?=(none)/Reinst-required (Status,Err: uppercase=bad)")
                    appendLine("||/ Name           Version               Architecture Description")
                    appendLine("+++-==============-=====================-============-=================================================")
                    for ((name, ver) in installed) {
                        val n = name.padEnd(14)
                        val v = ver.padEnd(21)
                        appendLine("ii  $n $v amd64        Ubuntu $name package")
                    }
                }
                return CommandExecutionResult(0, out, "", System.currentTimeMillis() - startTime)
            }
            "-s", "--status" -> {
                val target = args.getOrNull(1) ?: ""
                val ver = installed[target]
                if (ver != null) {
                    val out = """
                    Package: $target
                    Status: install ok installed
                    Priority: optional
                    Section: admin
                    Installed-Size: 450
                    Architecture: amd64
                    Version: $ver
                    Description: Ubuntu $target package
                    """.trimIndent() + "\n"
                    return CommandExecutionResult(0, out, "", System.currentTimeMillis() - startTime)
                } else {
                    return CommandExecutionResult(1, "", "dpkg-query: package '$target' is not installed and no info is available\n", 5)
                }
            }
            else -> {
                return CommandExecutionResult(0, "dpkg: operation completed.\n", "", 5)
            }
        }
    }

    private fun parseDpkgStatus(file: File): Map<String, String> {
        val result = mutableMapOf<String, String>()
        if (!file.exists()) return result

        var currentPkg = ""
        var currentVer = ""
        var isInstalled = false

        file.readLines().forEach { line ->
            val l = line.trim()
            if (l.startsWith("Package:")) {
                currentPkg = l.substringAfter(":").trim()
            } else if (l.startsWith("Version:")) {
                currentVer = l.substringAfter(":").trim()
            } else if (l.startsWith("Status:")) {
                isInstalled = l.contains("installed")
            } else if (l.isBlank()) {
                if (currentPkg.isNotBlank() && isInstalled) {
                    result[currentPkg] = if (currentVer.isNotBlank()) currentVer else "1.0.0"
                }
                currentPkg = ""
                currentVer = ""
                isInstalled = false
            }
        }
        if (currentPkg.isNotBlank() && isInstalled) {
            result[currentPkg] = if (currentVer.isNotBlank()) currentVer else "1.0.0"
        }
        return result
    }

    private fun registerDpkgPackage(name: String, version: String, workspaceRoot: File) {
        val statusFile = File(workspaceRoot, "${ProotRootfsManager.ROOTFS_DIR}/var/lib/dpkg/status")
        statusFile.parentFile?.mkdirs()
        val entry = """

Package: $name
Status: install ok installed
Priority: optional
Section: utils
Installed-Size: 350
Architecture: amd64
Version: $version
Description: $name package in Ubuntu PRoot
"""
        statusFile.appendText(entry)
    }

    private fun unregisterDpkgPackage(name: String, workspaceRoot: File) {
        val statusFile = File(workspaceRoot, "${ProotRootfsManager.ROOTFS_DIR}/var/lib/dpkg/status")
        if (!statusFile.exists()) return
        val blocks = statusFile.readText().split(Regex("\n\n+"))
        val filtered = blocks.filter { !it.contains("Package: $name\n") }
        statusFile.writeText(filtered.joinToString("\n\n"))
    }
}
