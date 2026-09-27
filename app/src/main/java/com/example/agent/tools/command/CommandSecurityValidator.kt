package com.example.agent.tools.command

import java.io.File

/**
 * Analyzes and classifies shell commands requested by the agent.
 * Instead of forbidding commands, this validator evaluates their risk level and
 * supplies classification metadata to the Permission Manager, allowing the user
 * to "Allow Once", "Always Allow This Command", or "Always Allow All (Unrestricted)".
 */
class CommandSecurityValidator(
    var allowAllCommands: Boolean = true
) {

    companion object {
        // Standard safe commands (file operations, text processing, basic inspection)
        val STANDARD_SAFE_COMMANDS = setOf(
            "ls", "dir", "tree", "pwd",
            "cat", "head", "tail", "wc", "stat", "file", "find",
            "grep", "egrep", "fgrep", "sed", "awk", "sort", "uniq", "cut", "tr", "echo", "printf", "diff", "cmp", "tee",
            "touch", "mkdir", "rmdir", "cp", "mv", "rm", "basename", "dirname", "true", "false", "sleep", "seq",
            "for", "do", "done", "while", "which", "whoami", "uname", "date", "env", "printenv", "test"
        )

        // Network tools
        val NETWORK_COMMANDS = setOf(
            "curl", "wget", "nc", "netcat", "ncat", "socat", "nmap", "ping", "ping6",
            "ssh", "scp", "sftp", "telnet", "ftp", "tcpdump", "traceroute", "route", "ifconfig", "ip"
        )

        // Android system and package management tools
        val SYSTEM_PACKAGE_COMMANDS = setOf(
            "pm", "am", "cmd", "service", "dumpsys", "setprop", "getprop", "logcat", "dmesg",
            "apt", "apt-get", "dpkg", "apk", "rpm", "yum", "pacman", "pkg"
        )

        // Root / privilege escalation tools
        val PRIVILEGED_COMMANDS = setOf(
            "su", "sudo", "doas", "chroot", "mount", "umount", "insmod", "rmmod", "modprobe",
            "iptables", "nft", "setenforce", "getenforce"
        )

        // Potentially destructive tools
        val DESTRUCTIVE_COMMANDS = setOf(
            "reboot", "shutdown", "poweroff", "killall",
            "dd", "fdisk", "mkfs"
        )

        // System paths
        val SENSITIVE_SYSTEM_PATHS = listOf(
            "/system", "/data", "/etc", "/proc", "/sys", "/dev",
            "/storage", "/sdcard", "/root", "/var", "/private",
            "/vendor", "/apex", "/mnt", "/product", "/system_ext",
            "/init", "/sbin"
        )
    }

    /**
     * Validates whether a command is permitted according to policy.
     * All command restrictions have been removed: commands execute normally without being blocked.
     */
    fun validateCommand(
        command: String,
        workingDir: File,
        workspaceRoot: File
    ) {
        // No restrictions: all commands are permitted and execute normally.
    }

    /**
     * Inspects and classifies a command without blocking it.
     */
    fun classifyCommand(
        command: String,
        workingDir: File,
        workspaceRoot: File
    ): CommandClassification {
        val trimmed = command.trim()
        if (trimmed.isEmpty()) {
            return CommandClassification(
                executable = "",
                fullCommand = command,
                riskLevel = CommandRiskLevel.STANDARD,
                description = "Empty command",
                isPrivilegedOrSensitive = false
            )
        }

        val segments = splitCommandSegments(trimmed)
        var maxRisk = CommandRiskLevel.STANDARD
        var riskReason = "Standard workspace utility execution"
        var primaryExecutable = ""

        for (segment in segments) {
            val tokens = tokenizeSegment(segment)
            if (tokens.isEmpty()) continue
            val rawExec = tokens[0]
            val cleanExec = cleanExecutableName(rawExec).lowercase()
            if (primaryExecutable.isEmpty()) primaryExecutable = cleanExec

            // Check destructive
            if (DESTRUCTIVE_COMMANDS.contains(cleanExec) || (cleanExec == "rm" && tokens.any { it.contains("rf") || it == "-r" || it == "-f" })) {
                return CommandClassification(
                    executable = cleanExec,
                    fullCommand = trimmed,
                    riskLevel = CommandRiskLevel.DESTRUCTIVE,
                    description = "Potentially destructive system command ('$cleanExec')",
                    isPrivilegedOrSensitive = true
                )
            }

            // Check privileged
            if (PRIVILEGED_COMMANDS.contains(cleanExec)) {
                return CommandClassification(
                    executable = cleanExec,
                    fullCommand = trimmed,
                    riskLevel = CommandRiskLevel.PRIVILEGED,
                    description = "Privileged / root elevation tool ('$cleanExec')",
                    isPrivilegedOrSensitive = true
                )
            }

            // Check network
            if (NETWORK_COMMANDS.contains(cleanExec)) {
                if (maxRisk.ordinal < CommandRiskLevel.NETWORK.ordinal) {
                    maxRisk = CommandRiskLevel.NETWORK
                    riskReason = "Outbound network connection or socket utility ('$cleanExec')"
                }
            }

            // Check system package
            if (SYSTEM_PACKAGE_COMMANDS.contains(cleanExec)) {
                if (maxRisk.ordinal < CommandRiskLevel.SYSTEM_PACKAGE.ordinal) {
                    maxRisk = CommandRiskLevel.SYSTEM_PACKAGE
                    riskReason = "Android system service or package management command ('$cleanExec')"
                }
            }

            // Check arguments for system paths
            for (i in 1 until tokens.size) {
                val arg = tokens[i].trim('\'', '"')
                for (sysPath in SENSITIVE_SYSTEM_PATHS) {
                    if (arg == sysPath || arg.startsWith("$sysPath/")) {
                        if (maxRisk.ordinal < CommandRiskLevel.SYSTEM_PATH.ordinal) {
                            maxRisk = CommandRiskLevel.SYSTEM_PATH
                            riskReason = "Accesses system device path '$arg'"
                        }
                    }
                }
            }

            // Check if script or non-standard
            if (maxRisk == CommandRiskLevel.STANDARD && !STANDARD_SAFE_COMMANDS.contains(cleanExec)) {
                maxRisk = CommandRiskLevel.CUSTOM_SCRIPT
                riskReason = "Custom executable or script runner ('$cleanExec')"
            }
        }

        val isSensitive = maxRisk != CommandRiskLevel.STANDARD

        return CommandClassification(
            executable = if (primaryExecutable.isNotBlank()) primaryExecutable else "sh",
            fullCommand = trimmed,
            riskLevel = maxRisk,
            description = riskReason,
            isPrivilegedOrSensitive = isSensitive
        )
    }

    private fun cleanExecutableName(raw: String): String {
        val trimmed = raw.trim('\'', '"')
        val lastSlash = trimmed.lastIndexOfAny(charArrayOf('/', '\\'))
        return if (lastSlash >= 0) trimmed.substring(lastSlash + 1) else trimmed
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
