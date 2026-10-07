package com.example.agent.tools

import com.example.agent.artifact.Artifact
import java.util.UUID

/**
 * Metadata definition for a parameter required by an AgentTool.
 */
data class ToolParameter(
    val name: String,
    val type: String, // "string", "number", "boolean", "array", "object"
    val description: String,
    val required: Boolean = true,
    val default: Any? = null
)

/**
 * Specification and discovery schema for an AgentTool.
 */
data class ToolDefinition(
    val name: String,
    val description: String,
    val parameters: List<ToolParameter> = emptyList()
)

/**
 * Structured request from an Agent to invoke a tool.
 */
data class ToolCall(
    val callId: String = UUID.randomUUID().toString(),
    val toolName: String,
    val arguments: Map<String, Any?> = emptyMap(),
    val taskId: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Structured execution result returned from an AgentTool.
 *
 * Exposes stdout and stderr distinctly, exit codes, execution duration,
 * working directory, filesystem operations, and artifact metadata.
 */
data class ToolResult(
    val callId: String,
    val toolName: String,
    val isSuccess: Boolean,
    val result: String? = null,
    val error: String? = null,
    val executedAt: Long = System.currentTimeMillis(),
    val exitCode: Int? = null,
    val stdout: String? = null,
    val stderr: String? = null,
    val durationMs: Long = 0L,
    val workingDirectory: String? = null,
    val command: String? = null,
    val timedOut: Boolean = false,
    val cancelled: Boolean = false,
    val terminationReason: String? = null,
    val path: String? = null,
    val operation: String? = null,
    val fileType: String? = null,
    val fileSize: Long? = null,
    val modifiedTime: Long? = null,
    val artifact: Artifact? = null
) {
    companion object {
        fun success(callId: String, toolName: String, result: String): ToolResult =
            ToolResult(
                callId = callId,
                toolName = toolName,
                isSuccess = true,
                result = result,
                error = null
            )

        fun failure(callId: String, toolName: String, error: String): ToolResult =
            ToolResult(
                callId = callId,
                toolName = toolName,
                isSuccess = false,
                result = null,
                error = error
            )

        fun commandResult(
            callId: String,
            toolName: String,
            isSuccess: Boolean,
            exitCode: Int,
            stdout: String,
            stderr: String,
            durationMs: Long,
            workingDirectory: String,
            command: String,
            timedOut: Boolean = false,
            cancelled: Boolean = false,
            terminationReason: String? = null,
            isTruncated: Boolean = false
        ): ToolResult {
            val formatted = buildString {
                if (isSuccess) {
                    appendLine("Exit Code: $exitCode (Duration: ${durationMs}ms)")
                    appendLine("Command: $command")
                    appendLine("Working Directory: $workingDirectory")
                    if (isTruncated) {
                        appendLine("(Output truncated to maximum allowed size)")
                    }
                    if (stdout.isNotBlank()) {
                        appendLine()
                        append(stdout)
                    } else {
                        appendLine("\n(Command executed successfully with no output)")
                    }
                    if (stderr.isNotBlank()) {
                        appendLine()
                        append(stderr)
                    }
                } else {
                    if (timedOut) {
                        appendLine("Command execution timed out after ${durationMs}ms and was killed.")
                    } else {
                        appendLine("Command exited with code $exitCode")
                    }
                    appendLine("Command: $command")
                    appendLine("Working Directory: $workingDirectory")
                    if (isTruncated) {
                        appendLine("(Output truncated to maximum allowed size)")
                    }
                    if (stderr.isNotBlank()) {
                        appendLine()
                        append(stderr)
                    }
                    if (stdout.isNotBlank()) {
                        appendLine()
                        append(stdout)
                    }
                }
            }.trim()

            return ToolResult(
                callId = callId,
                toolName = toolName,
                isSuccess = isSuccess,
                result = if (isSuccess) formatted else null,
                error = if (!isSuccess) formatted else null,
                exitCode = exitCode,
                stdout = stdout,
                stderr = stderr,
                durationMs = durationMs,
                workingDirectory = workingDirectory,
                command = command,
                timedOut = timedOut,
                cancelled = cancelled,
                terminationReason = terminationReason
            )
        }

        fun filesystemResult(
            callId: String,
            toolName: String,
            isSuccess: Boolean,
            path: String,
            operation: String,
            fileType: String,
            fileSize: Long,
            modifiedTime: Long,
            summary: String,
            error: String? = null,
            artifact: Artifact? = null
        ): ToolResult = ToolResult(
            callId = callId,
            toolName = toolName,
            isSuccess = isSuccess,
            result = if (isSuccess) summary else null,
            error = error,
            path = path,
            operation = operation,
            fileType = fileType,
            fileSize = fileSize,
            modifiedTime = modifiedTime,
            artifact = artifact
        )
    }
}
