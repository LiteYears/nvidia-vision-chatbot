package com.example.agent.tools.workspace

import com.example.agent.tools.AgentTool
import com.example.agent.tools.ToolDefinition
import com.example.agent.tools.ToolParameter
import com.example.agent.tools.ToolResult
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Base64
import java.util.UUID

/**
 * Tool for creating and writing files of any type (text, code, data, or binary)
 * inside the agent workspace.
 * Automatically creates any missing parent directories.
 */
class FileWriteTool(
    private val workspaceManager: AgentWorkspaceManager = AgentWorkspaceManager.getInstance()
) : AgentTool {

    override val definition: ToolDefinition = ToolDefinition(
        name = "file_write",
        description = "Writes content to a file inside the agent workspace. " +
            "Supports all file types (text, source code, data, or binary via Base64). " +
            "Automatically creates missing parent directories when needed.",
        parameters = listOf(
            ToolParameter(
                name = "path",
                type = "string",
                description = "Relative path of the file to create or write (e.g. 'summary.md', 'src/config.json', 'data/weights.bin')",
                required = true
            ),
            ToolParameter(
                name = "content",
                type = "string",
                description = "The content to write into the file (text or Base64 string for binary)",
                required = true
            ),
            ToolParameter(
                name = "append",
                type = "boolean",
                description = "Whether to append to the existing file rather than overwriting it (default: false)",
                required = false,
                default = false
            ),
            ToolParameter(
                name = "encoding",
                type = "string",
                description = "Content encoding: 'utf-8' (default for text/code) or 'base64' (for binary files, images, archives)",
                required = false,
                default = "utf-8"
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
        val encoding = arguments["encoding"]?.toString()?.trim()?.lowercase() ?: "utf-8"

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

            val bytes: ByteArray = if (encoding == "base64") {
                val cleaned = content.trim().replace(Regex("\\s+"), "")
                try {
                    Base64.getDecoder().decode(cleaned)
                } catch (e: IllegalArgumentException) {
                    return ToolResult.failure(
                        callId = callId,
                        toolName = definition.name,
                        error = "Failed to decode Base64 content: ${e.message}"
                    )
                }
            } else {
                content.toByteArray(Charsets.UTF_8)
            }

            FileOutputStream(targetFile, isAppend).use { fos ->
                fos.write(bytes)
                fos.flush()
            }

            val relPath = workspaceManager.getRelativePath(targetFile)
            val actionWord = if (isAppend) "appended to" else "written to"
            val totalSize = targetFile.length()
            val typeInfo = workspaceManager.getFileTypeInfo(targetFile)

            ToolResult.success(
                callId = callId,
                toolName = definition.name,
                result = "Successfully $actionWord '$relPath' (${bytes.size} bytes written, format: ${typeInfo.category}, total size: $totalSize bytes)."
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
