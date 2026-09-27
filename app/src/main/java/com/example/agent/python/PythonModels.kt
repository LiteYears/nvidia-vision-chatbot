package com.example.agent.python

/**
 * Result of executing Python code in the agent workspace.
 */
data class PythonExecutionResult(
    val stdout: String,
    val stderr: String,
    val exitCode: Int,
    val durationMs: Long,
    val isTimedOut: Boolean = false,
    val isTruncated: Boolean = false,
    val errorType: String? = null
) {
    val isSuccess: Boolean
        get() = exitCode == 0 && !isTimedOut && errorType == null
}
