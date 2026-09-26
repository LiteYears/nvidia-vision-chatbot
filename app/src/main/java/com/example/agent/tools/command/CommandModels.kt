package com.example.agent.tools.command

/**
 * Result of executing a command in the agent workspace.
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
