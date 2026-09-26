package com.example.agent.tools.workspace

import com.example.agent.tools.AgentTool
import com.example.agent.tools.ToolDefinition
import com.example.agent.tools.ToolParameter
import com.example.agent.tools.ToolResult
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.util.UUID

/**
 * Tool for reading file contents safely within the agent workspace.
 */
class FileReadTool(
    private val workspaceManager: AgentWorkspaceManager = AgentWorkspaceManager.getInstance()
) : AgentTool {

    override val definition: ToolDefinition = ToolDefinition(
        name = "file_read",
        description = "Reads the text content of a file located within the agent workspace. " +
            "Supports size limits to prevent excessive context consumption.",
        parameters = listOf(
            ToolParameter(
                name = "path",
                type = "string",
                description = "Relative path of the file to read (e.g. 'notes.txt', 'src/pipeline.py')",
                required = true
            ),
            ToolParameter(
                name = "max_bytes",
                type = "number",
                description = "Maximum bytes to read (default: 65536 / 64 KB, maximum: 262144 / 256 KB)",
                required = false,
                default = 65536
            )
        )
    )

    override suspend fun execute(arguments: Map<String, Any?>): ToolResult {
        val callId = UUID.randomUUID().toString()
        val requestedPath = arguments["path"]?.toString()?.trim()

        if (requestedPath.isNullOrBlank()) {
            return ToolResult.failure(
                callId = callId,
                toolName = definition.name,
                error = "Missing or empty required parameter 'path'."
            )
        }

        val targetFile: File
        try {
            targetFile = workspaceManager.resolvePath(requestedPath)
        } catch (e: SecurityException) {
            return ToolResult.failure(
                callId = callId,
                toolName = definition.name,
                error = e.message ?: "Path traversal security error."
            )
        }

        if (!targetFile.exists()) {
            return ToolResult.failure(
                callId = callId,
                toolName = definition.name,
                error = "File not found: '$requestedPath'"
            )
        }

        if (targetFile.isDirectory) {
            return ToolResult.failure(
                callId = callId,
                toolName = definition.name,
                error = "Cannot read '$requestedPath': path is a directory, not a file. Use 'file_list' to view contents."
            )
        }

        val maxBytes = when (val m = arguments["max_bytes"]) {
            is Number -> m.toInt()
            is String -> m.toIntOrNull() ?: 65536
            else -> 65536
        }.coerceIn(512, 262144)

        return try {
            val totalSize = targetFile.length()
            if (totalSize == 0L) {
                return ToolResult.success(
                    callId = callId,
                    toolName = definition.name,
                    result = "File '$requestedPath' is empty (0 bytes)."
                )
            }

            val bytesToRead = minOf(totalSize, maxBytes.toLong()).toInt()
            val buffer = ByteArray(bytesToRead)
            var readTotal = 0
            FileInputStream(targetFile).use { fis ->
                while (readTotal < bytesToRead) {
                    val count = fis.read(buffer, readTotal, bytesToRead - readTotal)
                    if (count == -1) break
                    readTotal += count
                }
            }

            val text = String(buffer, 0, readTotal, Charsets.UTF_8)
            val resultText = if (totalSize > maxBytes) {
                "$text\n\n[Warning: Output truncated at $maxBytes bytes. Total file size is $totalSize bytes.]"
            } else {
                text
            }

            ToolResult.success(
                callId = callId,
                toolName = definition.name,
                result = resultText
            )
        } catch (e: IOException) {
            ToolResult.failure(
                callId = callId,
                toolName = definition.name,
                error = "Failed to read file '$requestedPath': ${e.message}"
            )
        }
    }
}
