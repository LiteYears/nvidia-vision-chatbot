package com.example.agent.tools.command

import com.example.agent.termux.TermuxCommandRunner
import com.example.agent.termux.TermuxEnvironmentManager
import com.example.agent.tools.workspace.AgentWorkspaceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Real Termux and Ubuntu Linux command execution engine for Agent Workspace.
 *
 * Spawns real native processes via ProcessBuilder against the Android Linux kernel (/system/bin/sh)
 * with the full Termux hierarchy ($PREFIX, $HOME, $UBUNTU_ROOT), package management (apt, pkg, dpkg),
 * and native Linux tools.
 */
class ProcessCommandRunner(
    private val workspaceManager: AgentWorkspaceManager = AgentWorkspaceManager.getInstance(),
    private val termuxRunner: TermuxCommandRunner = TermuxCommandRunner(
        envManager = TermuxEnvironmentManager.getInstance(),
        workspaceManager = workspaceManager
    )
) : CommandRunner {

    override suspend fun run(
        command: String,
        workingDir: File,
        timeoutMs: Long,
        maxOutputBytes: Int
    ): CommandExecutionResult = run(command, workingDir, timeoutMs, maxOutputBytes, "ubuntu")

    override suspend fun run(
        command: String,
        workingDir: File,
        timeoutMs: Long,
        maxOutputBytes: Int,
        environment: String
    ): CommandExecutionResult = withContext(Dispatchers.IO) {
        val trimmed = command.trim()
        if (trimmed.isBlank()) {
            return@withContext CommandExecutionResult(
                exitCode = 0,
                stdout = "",
                stderr = "",
                durationMs = 0
            )
        }

        if (!workingDir.exists()) {
            workingDir.mkdirs()
        }

        termuxRunner.run(
            command = trimmed,
            workingDir = workingDir,
            timeoutMs = timeoutMs,
            maxOutputBytes = maxOutputBytes,
            environment = environment
        )
    }
}

