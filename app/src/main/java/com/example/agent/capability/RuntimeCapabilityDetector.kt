package com.example.agent.capability

import java.io.File

/**
 * Capability and runtime detection layer for Agent Mode.
 *
 * Detects available and unavailable runtimes in the Android runtime environment.
 * Ensures the agent is explicitly aware of available tools and execution capabilities,
 * preventing LLM guesswork and providing actionable recovery alternatives.
 */
class RuntimeCapabilityDetector(
    private val pathDirs: List<File> = defaultSearchPaths()
) {

    /**
     * Inspects the environment and returns all registered capabilities with their status.
     */
    fun detectCapabilities(): List<RuntimeCapability> {
        val hasShellPython = isExecutableAvailable("python") || isExecutableAvailable("python3")
        val hasShellNode = isExecutableAvailable("node") || isExecutableAvailable("nodejs")
        val hasShellJava = isExecutableAvailable("java") || isExecutableAvailable("javac")
        val hasShellSh = isExecutableAvailable("sh")

        return listOf(
            RuntimeCapability(
                id = "embedded_python",
                name = "Embedded Python 3",
                type = CapabilityType.EMBEDDED_RUNTIME,
                isAvailable = true,
                description = "Dedicated in-app embedded Python 3 execution runtime with workspace isolation, capturing stdout/stderr/exit codes.",
                primaryTool = "python_execute",
                notes = "Always use 'python_execute' for Python scripts and coding tasks. Do not use shell 'python'.",
                alternatives = listOf("python_execute")
            ),
            RuntimeCapability(
                id = "workspace_filesystem",
                name = "Workspace File Management",
                type = CapabilityType.WORKSPACE_TOOL,
                isAvailable = true,
                description = "Isolated file operations (create, read, write, list, delete) constrained strictly to the agent session workspace.",
                primaryTool = "file_write",
                notes = "Tools: file_read, file_write, file_list, file_delete.",
                alternatives = listOf("file_write", "file_read", "file_list")
            ),
            RuntimeCapability(
                id = "shell_utilities",
                name = "Shell Workspace Utilities",
                type = CapabilityType.SHELL_EXECUTABLE,
                isAvailable = hasShellSh,
                description = "Safe shell utilities for workspace inspection and text processing (ls, cat, grep, find, wc, echo, sort).",
                primaryTool = "run_command",
                notes = "Useful for directory inspection and piping text data within the workspace.",
                alternatives = listOf("run_command")
            ),
            RuntimeCapability(
                id = "shell_python",
                name = "Shell Python Executable",
                type = CapabilityType.SHELL_EXECUTABLE,
                isAvailable = hasShellPython,
                description = "System shell 'python' binary in PATH (/system/bin/sh).",
                primaryTool = if (hasShellPython) "run_command" else null,
                notes = if (hasShellPython) "Available in host shell." else "Android system shell does not have 'python' installed.",
                alternatives = listOf("python_execute")
            ),
            RuntimeCapability(
                id = "node",
                name = "Node.js Runtime",
                type = CapabilityType.SHELL_EXECUTABLE,
                isAvailable = hasShellNode,
                description = "Node.js JavaScript runtime environment.",
                primaryTool = if (hasShellNode) "run_command" else null,
                notes = "Node.js is not present in standard Android environments.",
                alternatives = emptyList()
            ),
            RuntimeCapability(
                id = "java",
                name = "Java / JVM Compiler",
                type = CapabilityType.SHELL_EXECUTABLE,
                isAvailable = hasShellJava,
                description = "JDK command-line tools (java, javac).",
                primaryTool = if (hasShellJava) "run_command" else null,
                notes = "Command-line javac/java is not present in standard Android environments.",
                alternatives = emptyList()
            )
        )
    }

    /**
     * Checks if an executable is physically available in the search PATH.
     */
    fun isExecutableAvailable(executableName: String): Boolean {
        val clean = executableName.trim().removeSurrounding("\"").removeSurrounding("'")
        if (clean.isBlank()) return false

        // If absolute path provided
        if (clean.startsWith("/")) {
            val f = File(clean)
            return f.exists() && f.canExecute() && !f.isDirectory
        }

        return pathDirs.any { dir ->
            val candidate = File(dir, clean)
            candidate.exists() && candidate.canExecute() && !candidate.isDirectory
        }
    }

    /**
     * Finds alternatives and returns recovery guidance when an executable is requested.
     */
    fun findAlternative(executableName: String): String? {
        val normalized = executableName.lowercase().trim()
        return when {
            normalized in setOf("python", "python3", "py", "python.exe") -> {
                "The system shell does not have '$executableName'. Use the dedicated 'python_execute' tool, which provides an embedded Python 3 runtime inside your agent workspace."
            }
            normalized in setOf("node", "nodejs", "npm", "npx") -> {
                "Node.js runtime is unavailable in this Android environment."
            }
            normalized in setOf("java", "javac", "jar", "gradle", "mvn") -> {
                "Java development kit command line tools are unavailable in this Android environment."
            }
            normalized in setOf("pip", "pip3") -> {
                "Package manager 'pip' is unavailable. Standard Python libraries (math, json, random, sys, os, time, re, etc.) are built into 'python_execute'."
            }
            normalized in setOf("curl", "wget") -> {
                "For entering websites, downloading web pages, or fetching news, use the dedicated 'web_open' tool with parameter 'url'. For web search, use 'web_search'."
            }
            else -> null
        }
    }

    /**
     * Builds a structured CAPABILITY_UNAVAILABLE error message for the agent.
     */
    fun buildCapabilityUnavailableError(executable: String, command: String, rawError: String? = null): String {
        val alternativeAdvice = findAlternative(executable)
        val caps = detectCapabilities()
        val availableList = caps.filter { it.isAvailable }.joinToString(", ") { "${it.name} [${it.primaryTool ?: "available"}]" }
        val unavailableList = caps.filter { !it.isAvailable }.joinToString(", ") { it.name }

        return buildString {
            appendLine("[CAPABILITY_UNAVAILABLE]")
            appendLine("Executable '$executable' is not available in the execution environment.")
            appendLine("Attempted command: $command")
            if (!rawError.isNullOrBlank()) {
                appendLine("System error: ${rawError.trim()}")
            }
            if (alternativeAdvice != null) {
                appendLine("\nRecommended Alternative:")
                appendLine("-> $alternativeAdvice")
            }
            appendLine("\nAvailable Capabilities:")
            appendLine("- $availableList")
            appendLine("Unavailable in this environment:")
            appendLine("- $unavailableList")
            appendLine("\nNext Steps:")
            appendLine("Do NOT retry running '$executable' via run_command.")
            if (executable.startsWith("python")) {
                appendLine("-> Use 'python_execute' with 'script_path' or 'code' to execute Python tasks.")
            } else if (executable == "curl" || executable == "wget") {
                appendLine("-> Use 'web_open' with parameter 'url' to fetch and read web pages.")
            } else {
                appendLine("-> Switch to a supported tool (web_open, file_write, python_execute, run_command for safe utilities) to complete the goal.")
            }
        }.trim()
    }

    /**
     * Formats detected capabilities into an informative section for the Agent system prompt.
     */
    fun formatCapabilitiesForPrompt(): String {
        val caps = detectCapabilities()
        val sb = StringBuilder()
        sb.append("### ENVIRONMENT CAPABILITIES & RUNTIMES\n")
        sb.append("The agent execution environment has the following verified capabilities:\n\n")

        for (cap in caps) {
            val statusTag = if (cap.isAvailable) "[AVAILABLE]" else "[UNAVAILABLE]"
            sb.append("- **${cap.name}** $statusTag:\n")
            sb.append("  ${cap.description}\n")
            if (cap.primaryTool != null && cap.isAvailable) {
                sb.append("  * Primary tool: `${cap.primaryTool}`\n")
            }
            if (cap.notes != null) {
                sb.append("  * Note: ${cap.notes}\n")
            }
            if (!cap.isAvailable && cap.alternatives.isNotEmpty()) {
                sb.append("  * Alternative: Use ${cap.alternatives.joinToString(", ") { "`$it`" }}\n")
            }
        }
        sb.append("\n**EXECUTION NOTES**:\n")
        sb.append("Both `python_execute` and `run_command` are available for code and command execution without restrictions.\n")
        sb.append("All files and scripts can be managed in the agent workspace root (use `file_write`, `file_read`, `file_list`).\n")

        return sb.toString()
    }

    companion object {
        private fun defaultSearchPaths(): List<File> {
            val pathEnv = System.getenv("PATH") ?: "/system/bin:/system/xbin:/bin:/usr/bin:/usr/local/bin"
            return pathEnv.split(':')
                .map { File(it.trim()) }
                .filter { it.isDirectory }
        }
    }
}
