package com.example.agent.tools.workspace

import com.example.agent.tools.AgentTool
import com.example.agent.tools.ToolDefinition
import com.example.agent.tools.ToolParameter
import com.example.agent.tools.ToolResult
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID

/**
 * Tool for writing text files inside the agent workspace.
 * Automatically creates any missing parent directories.
 */
class FileWriteTool(
    private val workspaceManager: AgentWorkspaceManager = AgentWorkspaceManager.getInstance()
) : AgentTool {

    override val definition: ToolDefinition = ToolDefinition(
        name = "file_write",
        description = "Writes text content to a file inside the agent workspace. " +
            "Automatically creates missing parent directories when needed.",
        parameters = listOf(
            ToolParameter(
                name = "path",
                type = "string",
                description = "Relative path of the file to create or write (e.g. 'summary.md', 'src/config.json')",
                required = true
            ),
            ToolParameter(
                name = "content",
                type = "string",
                description = "The text content to write into the file",
                required = true
            ),
            ToolParameter(
                name = "append",
                type = "boolean",
                description = "Whether to append to the existing file rather than overwriting it (default: false)",
                required = false,
                default = false
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

        val content = arguments["content"]?.toString() ?: ""
        val isAppend = when (val a = arguments["append"]) {
            is Boolean -> a
            is String -> a.equals("true", ignoreCase = true)
            else -> false
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

        // Prevent treating workspace root itself as a writable file
        if (targetFile.canonicalPath == workspaceManager.getWorkspaceDir().canonicalPath) {
            return ToolResult.failure(
                callId = callId,
                toolName = definition.name,
                error = "Cannot write content to workspace root directory."
            )
        }

        if (targetFile.exists() && targetFile.isDirectory) {
            return ToolResult.failure(
                callId = callId,
                toolName = definition.name,
                error = "Cannot write to '$requestedPath': path is an existing directory."
            )
        }

        return try {
            // Automatically create missing parent directories
            targetFile.parentFile?.let { parent ->
                if (!parent.exists()) {
                    parent.mkdirs()
                }
            }

            val bytes = content.toByteArray(Charsets.UTF_8)
            FileOutputStream(targetFile, isAppend).use { fos ->
                fos.write(bytes)
                fos.flush()
            }

            val relPath = workspaceManager.getRelativePath(targetFile)
            val actionWord = if (isAppend) "appended to" else "written to"
            val totalSize = targetFile.length()

            ToolResult.success(
                callId = callId,
                toolName = definition.name,
                result = "Successfully $actionWord '$relPath' (${bytes.size} bytes written, total file size: $totalSize bytes)."
            )
        } catch (e: IOException) {
            ToolResult.failure(
                callId = callId,
                toolName = definition.name,
                error = "Failed to write file '$requestedPath': ${e.message}"
            )
        }
    }
}
