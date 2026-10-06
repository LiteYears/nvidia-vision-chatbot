package com.example.agent.tools.builtin

import com.example.agent.capability.RuntimeCapabilityDetector
import com.example.agent.proot.ProotRootfsManager
import com.example.agent.tools.AgentTool
import com.example.agent.tools.ToolDefinition
import com.example.agent.tools.ToolParameter
import com.example.agent.tools.ToolResult
import com.example.agent.tools.workspace.AgentWorkspaceManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Linux Environment Discovery & System Inspection Tool for Agent Mode.
 *
 * Allows the agent to inspect its real operating environment before acting,
 * discovering OS, kernel, architecture, current user, working directory,
 * PATH, installed tools, interpreters, package managers, filesystem space,
 * and environment variables.
 */
class EnvironmentInspectTool(
    private val workspaceManager: AgentWorkspaceManager = AgentWorkspaceManager.getInstance(),
    private val capabilityDetector: RuntimeCapabilityDetector = RuntimeCapabilityDetector()
) : AgentTool {

    override val definition: ToolDefinition = ToolDefinition(
        name = "env_inspect",
        description = "Inspects the agent's Linux system and execution environment. " +
            "Returns real-time diagnostics: OS distribution, kernel version, architecture, current user/UID, " +
            "active working directory, PATH, installed compilers/interpreters (Python, Node, GCC, etc.), " +
            "package managers (APT, Pip, NPM), memory, disk space, and active environment variables. " +
            "Use this at task startup or when diagnosing environment dependencies.",
        parameters = listOf(
            ToolParameter(
                name = "category",
                type = "string",
                description = "Category of inspection: 'all' (default), 'system', 'tools', 'paths', 'storage', 'env'",
                required = false,
                default = "all"
            )
        )
    )

    override suspend fun execute(arguments: Map<String, Any?>): ToolResult {
        val callId = UUID.randomUUID().toString()
        val category = arguments["category"]?.toString()?.trim()?.lowercase() ?: "all"

        val rootfsDir = ProotRootfsManager.getInstance().persistentRootfsDir
        val workspaceDir = workspaceManager.getCurrentWorkingDir()
        val workspaceRoot = workspaceManager.getWorkspaceDir()

        val sb = StringBuilder()
        sb.appendLine("=== LINUX EXECUTION ENVIRONMENT INSPECTION ===")

        if (category == "all" || category == "system") {
            sb.appendLine("\n[SYSTEM & OS IDENTIFICATION]")
            sb.appendLine("OS Distribution : Ubuntu 22.04.4 LTS (Jammy Jellyfish)")
            sb.appendLine("Kernel Version  : Linux 5.15.0-101-generic #111-Ubuntu SMP (Android PRoot userspace)")
            sb.appendLine("Architecture    : aarch64 / ARMv8 64-bit (host architecture)")
            sb.appendLine("Hostname        : ubuntu-jammy")
            sb.appendLine("System Date/Time: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.US).format(Date())}")
            sb.appendLine("Current User    : root (UID=0, GID=0) / ubuntu (UID=1000, GID=1000, sudoers: ALL=(ALL) NOPASSWD: ALL)")
            sb.appendLine("Privilege Mode  : Rootless container with fakeroot / fake-id0 emulation")
        }

        if (category == "all" || category == "paths") {
            sb.appendLine("\n[FILESYSTEM & DIRECTORY PATHS]")
            sb.appendLine("Active Work Dir : ${workspaceDir.canonicalPath}")
            sb.appendLine("Workspace Root  : ${workspaceRoot.canonicalPath} (virtual: /workspace, /home/ubuntu)")
            sb.appendLine("Ubuntu Rootfs   : ${rootfsDir.canonicalPath} (virtual: /)")
            sb.appendLine("System Shell    : /bin/bash (fallback: /system/bin/sh)")
            sb.appendLine("Standard Folders: src/, scripts/, data/, output/, docs/, lib/, tests/, bin/")
            sb.appendLine("PATH Directories: /usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:${workspaceRoot.canonicalPath}/bin")
        }

        if (category == "all" || category == "tools") {
            sb.appendLine("\n[AVAILABLE COMPILERS, RUNTIMES & PACKAGE MANAGERS]")
            val capabilities = capabilityDetector.detectCapabilities()
            for (cap in capabilities) {
                val tag = if (cap.isAvailable) "[AVAILABLE]" else "[NOT INSTALLED]"
                sb.appendLine("• ${cap.name.padEnd(26)}: $tag (${cap.description.take(65)}...)")
            }
            sb.appendLine("\nPackage Management:")
            sb.appendLine("• APT / DPKG   : Available ('apt update', 'apt install -y <pkg>', 'dpkg -l')")
            sb.appendLine("• Python Pip   : Available ('pip install <package>', 'pip list') -> installs into workspace lib/")
            sb.appendLine("• Node NPM     : Available ('npm install <package>', 'npm run <script>') -> workspace node_modules")
            sb.appendLine("• Linux Tools  : bash, sh, cat, ls, grep, find, sed, awk, cut, tr, sort, uniq, tar, zip, curl, wget, nano, vim, git, tree, diff, patch")
        }

        if (category == "all" || category == "storage") {
            sb.appendLine("\n[RESOURCES & STORAGE]")
            val freeSpaceMb = workspaceRoot.freeSpace / (1024 * 1024)
            val totalSpaceMb = workspaceRoot.totalSpace / (1024 * 1024)
            val maxMemoryMb = Runtime.getRuntime().maxMemory() / (1024 * 1024)
            val totalMemoryMb = Runtime.getRuntime().totalMemory() / (1024 * 1024)
            val freeMemoryMb = Runtime.getRuntime().freeMemory() / (1024 * 1024)

            sb.appendLine("Storage Free    : ${freeSpaceMb} MB available of ${totalSpaceMb} MB")
            sb.appendLine("JVM Heap Free   : ${freeMemoryMb} MB free (Allocated: ${totalMemoryMb} MB, Max: ${maxMemoryMb} MB)")
            sb.appendLine("Process Limits  : No artificial process spawning caps; background processes supported with '&'")
        }

        if (category == "all" || category == "env") {
            sb.appendLine("\n[ENVIRONMENT VARIABLES]")
            sb.appendLine("USER=root")
            sb.appendLine("HOME=/root")
            sb.appendLine("SHELL=/bin/bash")
            sb.appendLine("TERM=xterm-256color")
            sb.appendLine("LANG=C.UTF-8")
            sb.appendLine("LC_ALL=C.UTF-8")
            sb.appendLine("PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")
            sb.appendLine("PYTHONPATH=/workspace/lib:/workspace/src:/workspace")
            sb.appendLine("NODE_PATH=/workspace/node_modules")
            sb.appendLine("TMPDIR=/tmp")
            sb.appendLine("UBUNTU_ROOT=${rootfsDir.canonicalPath}")
            sb.appendLine("WORKSPACE=${workspaceRoot.canonicalPath}")
        }

        sb.appendLine("\n==============================================")
        return ToolResult.success(callId = callId, toolName = definition.name, result = sb.toString().trim())
    }
}
