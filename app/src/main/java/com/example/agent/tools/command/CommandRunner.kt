package com.example.agent.tools.command

import java.io.File

/**
 * Modular interface for executing commands within an isolated workspace.
 *
 * This abstraction decouples command execution from the underlying mechanism,
 * allowing the default process runner to be substituted with a container,
 * microVM, or stronger sandbox without modifying the agent tools or loop.
 */
interface CommandRunner {

    /**
     * Executes the command inside [workingDir] with the given [timeoutMs] and [maxOutputBytes] limits.
     *
     * @param command The validated shell command string.
     * @param workingDir The directory where the process should be started (must be inside workspace).
     * @param timeoutMs Maximum allowed execution time before terminating the process.
     * @param maxOutputBytes Maximum total bytes to collect from standard output and error.
     * @return Execution result with stdout, stderr, exit code, duration, and status flags.
     */
    suspend fun run(
        command: String,
        workingDir: File,
        timeoutMs: Long,
        maxOutputBytes: Int
    ): CommandExecutionResult

    /**
     * Executes the command targeting a specific environment ('ubuntu', 'termux', or 'auto').
     */
    suspend fun run(
        command: String,
        workingDir: File,
        timeoutMs: Long,
        maxOutputBytes: Int,
        environment: String
    ): CommandExecutionResult = run(command, workingDir, timeoutMs, maxOutputBytes)
}

