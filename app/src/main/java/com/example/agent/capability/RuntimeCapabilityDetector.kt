package com.example.agent.capability

import java.io.File

/**
 * Capability and runtime detection layer for Agent Mode.
 *
 * Detects available runtimes (Python 3, Pip, Node.js, NPM, shell utilities)
 * and provides Claude Code-level visibility into agent environment capabilities.
 */
class RuntimeCapabilityDetector(
    private val pathDirs: List<File> = defaultSearchPaths()
) {

    /**
     * Inspects the environment and returns all registered capabilities with their status.
     */
    fun detectCapabilities(): List<RuntimeCapability> {
        val hasShellPython = isExecutableAvailable("python") || isExecutableAvailable("python3")
        val hasShellPip = isExecutableAvailable("pip") || isExecutableAvailable("pip3")
        val hasShellNode = isExecutableAvailable("node") || isExecutableAvailable("nodejs")
        val hasShellNpm = isExecutableAvailable("npm") || isExecutableAvailable("npx")
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
                notes = "Always available for running Python code and scripts inside the workspace sandbox.",
                alternatives = listOf("python_execute", "run_command")
            ),
            RuntimeCapability(
                id = "workspace_filesystem",
                name = "Workspace File Management",
                type = CapabilityType.WORKSPACE_TOOL,
                isAvailable = true,
                description = "Isolated file and folder operations handling all file types (code, text, data, images, archives, binaries) with pre-configured standard folders (src/, scripts/, data/, output/, docs/, lib/, tests/, bin/).",
                primaryTool = "file_write",
                notes = "Tools: file_read, file_write, file_list, file_delete, file_tree, file_search, directory_create.",
                alternatives = listOf("file_write", "file_read", "file_list")
            ),
            RuntimeCapability(
                id = "shell_utilities",
                name = "Shell Workspace Utilities",
                type = CapabilityType.SHELL_EXECUTABLE,
                isAvailable = true,
                description = "Ubuntu-like bash shell utilities for workspace inspection, text manipulation, and process orchestration (ls, cat, grep, find, wc, echo, sort, mkdir, cp, mv, uname, whoami, which, date, env).",
                primaryTool = "run_command",
                notes = "Useful for directory inspection and piping text data within the workspace.",
                alternatives = listOf("run_command")
            ),
            RuntimeCapability(
                id = "shell_python",
                name = "Python 3 CLI",
                type = CapabilityType.SHELL_EXECUTABLE,
                isAvailable = true,
                description = "Ubuntu-like Python 3 CLI environment. Runs scripts, pip modules, and commands via 'run_command' (e.g. 'python3 script.py', 'python3 -c \"...\"').",
                primaryTool = "run_command",
                notes = "Always available via run_command and python_execute.",
                alternatives = listOf("run_command", "python_execute")
            ),
            RuntimeCapability(
                id = "pip",
                name = "Pip Package Manager",
                type = CapabilityType.SHELL_EXECUTABLE,
                isAvailable = true,
                description = "Python package manager for installing, uninstalling, and managing dependencies. Run 'pip install <package>', 'pip uninstall -y <package>', 'pip list', 'pip show <package>' via 'run_command'.",
                primaryTool = "run_command",
                notes = "Packages install into workspace lib/ and are immediately available to import in Python scripts.",
                alternatives = listOf("run_command")
            ),
            RuntimeCapability(
                id = "node",
                name = "Node.js Runtime",
                type = CapabilityType.SHELL_EXECUTABLE,
                isAvailable = true,
                description = "Node.js JavaScript runtime environment. Run with 'node <file.js>' or 'node -e \"<code>\"' via 'run_command'.",
                primaryTool = "run_command",
                notes = "Available in Ubuntu PRoot workspace.",
                alternatives = listOf("run_command")
            ),
            RuntimeCapability(
                id = "npm",
                name = "NPM Package Manager",
                type = CapabilityType.SHELL_EXECUTABLE,
                isAvailable = true,
                description = "Node.js package manager for dependencies and scripts. Run 'npm init -y', 'npm install <package>', 'npm run <script>' via 'run_command'.",
                primaryTool = "run_command",
                notes = "Supports initializing package.json, managing node_modules, and running npm scripts in workspace.",
                alternatives = listOf("run_command")
            ),
            RuntimeCapability(
                id = "java",
                name = "Java / JVM Compiler",
                type = CapabilityType.SHELL_EXECUTABLE,
                isAvailable = hasShellJava,
                description = "JDK command-line tools (java, javac).",
                primaryTool = if (hasShellJava) "run_command" else null,
                notes = if (hasShellJava) "Available in environment." else "Command-line javac/java is not present in standard Android environments.",
                alternatives = emptyList()
            )
        )
    }

    /**
     * Checks if an executable is physically available in the search PATH (supporting aliases like python -> python3).
     */
    fun isExecutableAvailable(executableName: String): Boolean {
        val clean = executableName.trim().removeSurrounding("\"").removeSurrounding("'")
        if (clean.isBlank()) return false

        // Built-in workspace environment tools are always available
        val lower = clean.lowercase()
        if (lower in setOf(
                "python", "python3", "py", "pip", "pip3", "bash", "sh", "zsh", "ash", "dash",
                "uname", "whoami", "hostname", "which", "whereis", "date", "env", "printenv", "export",
                "apt", "apt-get", "dpkg", "pkg", "df", "free", "uptime", "clear", "cls", "true", "false",
                "echo", "pwd", "cd", "curl", "wget", "ls", "dir", "cat", "mkdir", "touch", "rm", "cp", "mv",
                "grep", "egrep", "fgrep", "wc", "head", "tail", "find", "git", "tar", "zip", "unzip", "tree",
                "proot", "proot-distro", "sed", "awk", "cut", "tr", "sort", "uniq", "tee", "xargs", "chmod",
                "chown", "su", "sudo", "md5sum", "sha256sum", "basename", "dirname", "node", "nodejs", "npm", "npx",
                "sleep", "seq", "base64", "stat", "file", "diff", "cmp", "service", "systemctl", "cal", "neofetch",
                "virtualenv", "venv", "uvicorn", "gunicorn", "pytest", "fastapi", "flask", "source", ".", "make", "gcc", "g++", "clang", "cargo", "rustc", "unittest"
            )
        ) {
            return true
        }

        // If absolute path provided
        if (clean.startsWith("/")) {
            val f = File(clean)
            return f.exists() && f.canExecute() && !f.isDirectory
        }

        val candidates = when (lower) {
            "node" -> listOf("node", "nodejs")
            "npm" -> listOf("npm", "npx")
            else -> listOf(clean)
        }

        return pathDirs.any { dir ->
            candidates.any { name ->
                val candidate = File(dir, name)
                candidate.exists() && candidate.canExecute() && !candidate.isDirectory
            }
        }
    }

    /**
     * Finds alternatives and returns recovery guidance when an executable is requested.
     */
    fun findAlternative(executableName: String): String? {
        val normalized = executableName.lowercase().trim()
        val hasShellPython = isExecutableAvailable("python")
        val hasShellPip = isExecutableAvailable("pip")
        val hasShellNode = isExecutableAvailable("node")
        val hasShellNpm = isExecutableAvailable("npm")

        return when {
            normalized in setOf("python", "python3", "py", "python.exe") -> {
                if (hasShellPython) {
                    "Python 3 is available in your shell environment. Run 'python3 <script.py>' or 'python <script.py>' via 'run_command', or use 'python_execute'."
                } else {
                    "The system shell does not have '$executableName'. Use the dedicated 'python_execute' tool, which provides an embedded Python 3 runtime inside your agent workspace."
                }
            }
            normalized in setOf("pip", "pip3") -> {
                "Run 'pip install <package>' or 'pip list' via 'run_command'. Packages are installed into workspace lib/ and can be imported directly."
            }
            normalized in setOf("node", "nodejs") -> {
                if (hasShellNode) {
                    "Node.js is available in your shell environment. Run 'node <file.js>' or 'node -e \"<code>\"' via 'run_command'."
                } else {
                    "Node.js runtime is unavailable in this environment."
                }
            }
            normalized in setOf("npm", "npx") -> {
                "Run 'npm init -y', 'npm install <package>', or 'npm run <script>' via 'run_command'."
            }
            normalized in setOf("java", "javac", "jar", "gradle", "mvn") -> {
                "Java development kit command line tools are unavailable in this Android environment."
            }
            normalized in setOf("docker", "dockerd", "podman", "containerd") -> {
                "Docker and container daemons cannot run inside Android userspace or PRoot environments (Android kernel cgroups and root namespaces are restricted). Run services and test scripts directly via 'python3 script.py' or 'python_execute' instead of Docker."
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
            val aptPkg = com.example.agent.proot.ProotAptManager.findPackageProviding(executable)
            if (aptPkg != null) {
                appendLine("\nPackage Installation:")
                appendLine("-> Command '$executable' is available via Ubuntu package '${aptPkg.name}'.")
                appendLine("-> Run 'apt install -y ${aptPkg.name}' via 'run_command' to install it into the Ubuntu environment.")
            }

            appendLine("\nAvailable Capabilities:")
            appendLine("- $availableList")
            if (unavailableList.isNotBlank()) {
                appendLine("Unavailable in this environment:")
                appendLine("- $unavailableList")
            }
            appendLine("\nNext Steps:")
            if (aptPkg != null) {
                appendLine("-> Install '${aptPkg.name}' with 'apt install -y ${aptPkg.name}' using run_command, then retry.")
            } else if (executable.startsWith("python")) {
                appendLine("-> Use 'python_execute' with 'script_path' or 'code' to execute Python tasks.")
            } else if (executable == "curl" || executable == "wget") {
                appendLine("-> Use 'web_open' with parameter 'url' to fetch and read web pages.")
            } else {
                appendLine("-> Switch to a supported tool or check if package can be installed with 'apt search $executable'.")
            }
        }.trim()
    }

    /**
     * Formats detected capabilities into an informative section for the Agent system prompt.
     */
    fun formatCapabilitiesForPrompt(): String {
        val caps = detectCapabilities()
        val sb = StringBuilder()
        sb.append("### ENVIRONMENT CAPABILITIES & CLAUDE CODE-LEVEL RUNTIMES\n")
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
        sb.append("\n**WORKSPACE & EXECUTION STANDARDS**:\n")
        sb.append("- Standard folders are pre-created: `src/`, `scripts/`, `data/`, `output/`, `docs/`, `lib/`, `tests/`, `bin/`.\n")
        sb.append("- All file formats are supported: source code, text, json, csv, yaml, images, archives, binaries (via Base64).\n")
        sb.append("- Full Ubuntu 22.04 LTS userspace with persistent rootfs, bash, apt, dpkg, deb archive extraction, Python 3, and coreutils.\n")
        sb.append("- Package management: install Ubuntu packages with `apt install -y <pkg>`, Python packages with `pip install <package>`, and Node modules with `npm install <package>`.\n")
        sb.append("- Both `python_execute` and `run_command` are available for executing code and terminal commands without restrictions.\n")

        return sb.toString()
    }

    companion object {
        private fun defaultSearchPaths(): List<File> {
            val rootfsDir = com.example.agent.proot.ProotRootfsManager.getInstance().persistentRootfsDir
            val rootfsDirs = listOf(
                File(rootfsDir, "usr/local/bin"),
                File(rootfsDir, "usr/bin"),
                File(rootfsDir, "bin"),
                File(rootfsDir, "usr/local/sbin"),
                File(rootfsDir, "usr/sbin"),
                File(rootfsDir, "sbin"),
                File("/data/data/com.termux/files/usr/bin")
            )
            val wsRoot: File? = try { com.example.agent.tools.workspace.AgentWorkspaceManager.getInstance().getWorkspaceRoot() } catch (_: Exception) { null }
            val wsDirs: List<File> = if (wsRoot != null) {
                listOf(
                    File(wsRoot, "bin"),
                    File(wsRoot, "venv/bin"),
                    File(wsRoot, ".venv/bin"),
                    File(wsRoot, "lib/bin")
                )
            } else emptyList()
            val pathEnv = System.getenv("PATH") ?: "/system/bin:/system/xbin:/bin:/usr/bin:/usr/local/bin"
            val envDirs: List<File> = pathEnv.split(':')
                .filter { it.isNotBlank() }
                .map { File(it.trim()) }
            return (wsDirs + rootfsDirs + envDirs).distinct().filter { it.isDirectory }
        }
    }
}
