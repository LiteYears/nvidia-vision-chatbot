package com.example.agent.tools.command

import java.io.File

/**
 * Enforces strict security boundaries on commands executed by the agent:
 * - Prevents access to arbitrary Android/system files (/system, /data, /etc, /proc, /sys, /sdcard, etc.)
 * - Prevents path traversal (e.g. ../) that resolves outside the workspace
 * - Prevents privilege escalation (su, sudo, chroot, mount, etc.)
 * - Prevents network scanning and outbound socket tools (curl, wget, nmap, ping, nc, etc.)
 * - Prevents package installation / system alteration (apt, pm, am, pkg, etc.)
 * - Restricts execution to workspace-safe tools for directory listing, file inspection,
 *   text processing, and workspace scripts.
 */
class CommandSecurityValidator {

    companion object {
        // Safe commands permitted for workspace agent tasks
        val ALLOWED_COMMANDS = setOf(
            // Directory listing
            "ls", "dir", "tree", "pwd",
            // File inspection
            "cat", "head", "tail", "wc", "stat", "file", "find",
            // Text processing
            "grep", "egrep", "fgrep", "sed", "awk", "sort", "uniq", "cut", "tr", "echo", "printf", "diff", "cmp", "tee",
            // Safe basic workspace file operations
            "touch", "mkdir", "rmdir", "cp", "mv", "rm", "basename", "dirname", "true", "false", "sleep", "seq",
            // Shell loop/structure keywords
            "for", "do", "done", "while",
            // Script runners
            "sh", "bash", "python", "python3", "node"
        )

        // Explicitly forbidden commands that pose security, network, or escalation risks
        val FORBIDDEN_COMMANDS = setOf(
            "su", "sudo", "doas", "chroot", "mount", "umount", "insmod", "rmmod", "modprobe",
            "curl", "wget", "nc", "netcat", "ncat", "socat", "nmap", "ping", "ping6", "ssh", "scp", "sftp",
            "telnet", "ftp", "tcpdump", "traceroute", "route", "ifconfig", "ip",
            "apt", "apt-get", "dpkg", "apk", "rpm", "yum", "pacman", "pkg",
            "pm", "am", "cmd", "service", "dumpsys", "setprop", "getprop", "logcat", "dmesg",
            "reboot", "shutdown", "poweroff", "killall",
            "dd", "fdisk", "mkfs", "iptables", "nft"
        )

        // Sensitive system directory roots that must never be accessed
        val FORBIDDEN_SYSTEM_PATHS = listOf(
            "/system", "/data", "/etc", "/proc", "/sys", "/dev",
            "/storage", "/sdcard", "/root", "/var", "/private",
            "/vendor", "/apex", "/mnt", "/product", "/system_ext",
            "/init", "/sbin", "/lost+found"
        )
    }

    /**
     * Validates that the command and its arguments do not escape the workspace
     * or attempt forbidden operations.
     *
     * @throws SecurityException if the command violates security boundaries.
     * @throws IllegalArgumentException if the command syntax or executable is invalid.
     */
    fun validateCommand(
        command: String,
        workingDir: File,
        workspaceRoot: File
    ) {
        val trimmed = command.trim()
        if (trimmed.isEmpty()) {
            throw IllegalArgumentException("Command cannot be empty.")
        }

        if (trimmed.contains('\u0000')) {
            throw SecurityException("Null byte detected in command string.")
        }

        val canonicalRoot = workspaceRoot.canonicalPath
        val canonicalWorkDir = workingDir.canonicalPath

        // 1. Verify working directory is within workspace boundary
        val isWorkDirValid = canonicalWorkDir == canonicalRoot ||
                canonicalWorkDir.startsWith(canonicalRoot + File.separator)
        if (!isWorkDirValid) {
            throw SecurityException("Working directory '${workingDir.path}' escapes the workspace boundary.")
        }

        // 2. Split command by shell operators (;, &&, ||, |, \n) to validate every segment
        val segments = splitCommandSegments(trimmed)
        if (segments.isEmpty()) {
            throw IllegalArgumentException("No executable command found.")
        }

        for (segment in segments) {
            val tokens = tokenizeSegment(segment)
            if (tokens.isEmpty()) continue

            validateSegment(tokens, workingDir, workspaceRoot)
        }
    }

    private fun validateSegment(
        tokens: List<String>,
        workingDir: File,
        workspaceRoot: File
    ) {
        val rawExecutable = tokens[0]
        val cleanExecutable = cleanExecutableName(rawExecutable)

        // Check if executable is explicitly forbidden
        if (FORBIDDEN_COMMANDS.contains(cleanExecutable.lowercase())) {
            throw SecurityException("Command '$cleanExecutable' is forbidden for security reasons.")
        }

        // Check if it's a workspace script execution (e.g. ./script.sh or script.sh in workspace)
        val isScript = isWorkspaceScript(rawExecutable, workingDir, workspaceRoot)

        if (!isScript && !ALLOWED_COMMANDS.contains(cleanExecutable.lowercase())) {
            throw SecurityException(
                "Command '$cleanExecutable' is not permitted in the agent workspace. " +
                "Only workspace-safe utilities (directory listing, file inspection, text processing, and workspace scripts) are supported."
            )
        }

        // Check arguments for forbidden paths, system paths, or path traversal escapes
        for (i in 1 until tokens.size) {
            val token = tokens[i]
            validateArgument(token, workingDir, workspaceRoot)
        }
    }

    private fun validateArgument(
        token: String,
        workingDir: File,
        workspaceRoot: File
    ) {
        // Strip quotes or redirection operators if attached
        val cleanToken = token.trim('\'', '"')
            .removePrefix(">>").removePrefix(">").removePrefix("<")
            .trim()

        if (cleanToken.isEmpty()) return

        val canonicalRoot = workspaceRoot.canonicalPath

        // Check for forbidden system path prefixes
        for (forbiddenPath in FORBIDDEN_SYSTEM_PATHS) {
            if (cleanToken == forbiddenPath || cleanToken.startsWith("$forbiddenPath/")) {
                throw SecurityException(
                    "Access to system path '$cleanToken' is prohibited. Commands may only access files inside the workspace."
                )
            }
        }

        // Check absolute paths: if token starts with '/', it must resolve strictly inside workspace
        if (cleanToken.startsWith("/")) {
            val target = File(cleanToken).canonicalFile
            val targetPath = target.canonicalPath
            val isInside = targetPath == canonicalRoot || targetPath.startsWith(canonicalRoot + File.separator)
            if (!isInside) {
                throw SecurityException("Absolute path '$cleanToken' resolves outside the agent workspace.")
            }
        }

        // Check path traversal with '..'
        if (cleanToken.contains("..")) {
            val resolved = File(workingDir, cleanToken).canonicalFile
            val targetPath = resolved.canonicalPath
            val isInside = targetPath == canonicalRoot || targetPath.startsWith(canonicalRoot + File.separator)
            if (!isInside) {
                throw SecurityException("Path traversal '$cleanToken' attempts to escape the agent workspace.")
            }
        }
    }

    private fun cleanExecutableName(raw: String): String {
        val trimmed = raw.trim('\'', '"')
        val lastSlash = trimmed.lastIndexOfAny(charArrayOf('/', '\\'))
        return if (lastSlash >= 0) trimmed.substring(lastSlash + 1) else trimmed
    }

    private fun isWorkspaceScript(rawExecutable: String, workingDir: File, workspaceRoot: File): Boolean {
        val trimmed = rawExecutable.trim('\'', '"')
        if (trimmed.startsWith("./") || trimmed.endsWith(".sh") || trimmed.contains("/")) {
            val scriptFile = if (trimmed.startsWith("/")) {
                File(trimmed).canonicalFile
            } else {
                File(workingDir, trimmed).canonicalFile
            }
            val canonicalRoot = workspaceRoot.canonicalPath
            val scriptPath = scriptFile.canonicalPath
            val isInside = scriptPath == canonicalRoot || scriptPath.startsWith(canonicalRoot + File.separator)
            if (!isInside) {
                throw SecurityException("Script path '$rawExecutable' escapes the agent workspace.")
            }
            return true
        }
        return false
    }

    private fun splitCommandSegments(command: String): List<String> {
        val segments = mutableListOf<String>()
        val current = StringBuilder()
        var inSingleQuote = false
        var inDoubleQuote = false
        var escape = false

        var i = 0
        while (i < command.length) {
            val c = command[i]
            if (escape) {
                current.append(c)
                escape = false
                i++
                continue
            }
            if (c == '\\') {
                escape = true
                current.append(c)
                i++
                continue
            }
            if (c == '\'' && !inDoubleQuote) {
                inSingleQuote = !inSingleQuote
                current.append(c)
                i++
                continue
            }
            if (c == '"' && !inSingleQuote) {
                inDoubleQuote = !inDoubleQuote
                current.append(c)
                i++
                continue
            }

            if (!inSingleQuote && !inDoubleQuote) {
                if (c == ';' || c == '\n') {
                    if (current.isNotBlank()) segments.add(current.toString().trim())
                    current.clear()
                    i++
                    continue
                }
                if (c == '|' && i + 1 < command.length && command[i + 1] == '|') {
                    if (current.isNotBlank()) segments.add(current.toString().trim())
                    current.clear()
                    i += 2
                    continue
                }
                if (c == '&' && i + 1 < command.length && command[i + 1] == '&') {
                    if (current.isNotBlank()) segments.add(current.toString().trim())
                    current.clear()
                    i += 2
                    continue
                }
                if (c == '|') {
                    if (current.isNotBlank()) segments.add(current.toString().trim())
                    current.clear()
                    i++
                    continue
                }
            }

            current.append(c)
            i++
        }
        if (current.isNotBlank()) {
            segments.add(current.toString().trim())
        }
        return segments
    }

    private fun tokenizeSegment(segment: String): List<String> {
        val tokens = mutableListOf<String>()
        val current = StringBuilder()
        var inSingleQuote = false
        var inDoubleQuote = false
        var escape = false

        for (c in segment) {
            if (escape) {
                current.append(c)
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
                if (current.isNotEmpty()) {
                    tokens.add(current.toString())
                    current.clear()
                }
                continue
            }
            current.append(c)
        }
        if (current.isNotEmpty()) {
            tokens.add(current.toString())
        }
        return tokens
    }
}
