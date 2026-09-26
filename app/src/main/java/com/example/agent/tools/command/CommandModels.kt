package com.example.agent.tools.command

/**
 * Result of executing a command in the agent workspace or system shell.
 *
 * @property exitCode Process exit code (0 for success, non-zero for failure, -1 for timeout/crash).
 * @property stdout Standard output captured during execution.
 * @property stderr Standard error captured during execution.
 * @property durationMs Execution time in milliseconds.
 * @property isTimedOut True if the process exceeded its timeout and was killed.
 * @property isTruncated True if standard output or standard error exceeded the byte limit.
 */
data class CommandExecutionResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val durationMs: Long,
    val isTimedOut: Boolean = false,
    val isTruncated: Boolean = false
)

/**
 * Categorization of commands based on system risk and scope.
 */
enum class CommandRiskLevel(val displayName: String) {
    STANDARD("Standard Workspace"),
    NETWORK("Network Operation"),
    SYSTEM_PACKAGE("System & Package Tool"),
    PRIVILEGED("Privileged / Root"),
    SYSTEM_PATH("System Path Access"),
    DESTRUCTIVE("Potentially Destructive"),
    CUSTOM_SCRIPT("Custom Script / Shell")
}

/**
 * Decision returned when the user is prompted to permit command execution.
 */
enum class CommandPermissionDecision {
    ALLOW_ONCE,
    ALWAYS_ALLOW_COMMAND,
    ALWAYS_ALLOW_ALL,
    DENY
}

/**
 * Configurable command permission policies for the application.
 */
enum class CommandPermissionPolicy(
    val id: String,
    val title: String,
    val description: String
) {
    ALWAYS_ALLOW_ALL(
        id = "always_allow_all",
        title = "Always Allow All (Unrestricted)",
        description = "Gives the agent full autonomy to execute any shell command, script, network tool, or package utility without prompting."
    ),
    ASK_FOR_SENSITIVE(
        id = "ask_sensitive",
        title = "Ask for Sensitive Commands (Recommended)",
        description = "Runs safe workspace commands automatically, but asks for permission before running network, package, or system-altering commands."
    ),
    ALWAYS_ASK(
        id = "always_ask",
        title = "Always Ask for Every Command",
        description = "Prompts a permission question for every single command the agent attempts to run."
    );

    companion object {
        fun fromId(id: String): CommandPermissionPolicy {
            return entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: ASK_FOR_SENSITIVE
        }
    }
}

/**
 * Security analysis result providing classification details for a command.
 */
data class CommandClassification(
    val executable: String,
    val fullCommand: String,
    val riskLevel: CommandRiskLevel,
    val description: String,
    val isPrivilegedOrSensitive: Boolean
)

/**
 * Active pending permission request awaiting user confirmation in the UI.
 */
data class PendingCommandPermission(
    val id: String,
    val command: String,
    val workingDir: String,
    val riskLevel: CommandRiskLevel,
    val details: String,
    val executable: String,
    val onDecision: (CommandPermissionDecision) -> Unit
)
