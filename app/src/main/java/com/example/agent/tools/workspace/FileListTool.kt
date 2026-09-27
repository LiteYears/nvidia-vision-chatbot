package com.example.agent.tools.workspace

import com.example.agent.tools.AgentTool
import com.example.agent.tools.ToolDefinition
import com.example.agent.tools.ToolParameter
import com.example.agent.tools.ToolResult
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Tool for listing files and directories inside the agent workspace.
 */
class FileListTool(
    private val workspaceManager: AgentWorkspaceManager = AgentWorkspaceManager.getInstance()
) : AgentTool {

    override val definition: ToolDefinition = ToolDefinition(
        name = "file_list",
        description = "Lists files and directories located inside the agent workspace. " +
            "Returns relative paths, types (file/dir), sizes in bytes, and last modified timestamps.",
        parameters = listOf(
            ToolParameter(
                name = "path",
                type = "string",
                description = "Relative path of the directory to list (e.g. '.' for workspace root, or 'src')",
                required = false,
                default = "."
            ),
            ToolParameter(
                name = "recursive",
                type = "boolean",
                description = "Whether to list contents recursively inside subdirectories (default: false)",
                required = false,
                default = false
            )
        )
    )

    override suspend fun execute(arguments: Map<String, Any?>): ToolResult {
        val callId = UUID.randomUUID().toString()
        val rawPath = arguments["path"]?.toString()?.trim() ?: "."
        val isRootPath = rawPath.isEmpty() || rawPath == "." || rawPath == "./" || rawPath == "/"
        val requestedPath = if (isRootPath) "." else rawPath
        val isRecursive = when (val r = arguments["recursive"]) {
            is Boolean -> r
            is String -> r.equals("true", ignoreCase = true)
            else -> false
        }

        val targetDir: File
        try {
            targetDir = workspaceManager.resolvePath(requestedPath)
        } catch (e: SecurityException) {
            return ToolResult.failure(
                callId = callId,
                toolName = definition.name,
                error = e.message ?: "Path traversal security error."
            )
        }

        if (!targetDir.exists()) {
            return ToolResult.failure(
                callId = callId,
                toolName = definition.name,
                error = "Target path does not exist: '$requestedPath'"
            )
        }

        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

        if (targetDir.isFile) {
            val rel = workspaceManager.getRelativePath(targetDir)
            return ToolResult.success(
                callId = callId,
                toolName = definition.name,
                result = "File: $rel (${targetDir.length()} bytes, modified ${dateFormat.format(Date(targetDir.lastModified()))})"
            )
        }

        val filesList = if (isRecursive) {
            targetDir.walkTopDown()
                .maxDepth(6)
                .take(150)
                .filter { it.canonicalPath != targetDir.canonicalPath }
                .toList()
        } else {
            targetDir.listFiles()?.toList() ?: emptyList()
        }

        if (filesList.isEmpty()) {
            val cleanRel = if (isRootPath) "workspace root" else "'$requestedPath'"
            return ToolResult.success(
                callId = callId,
                toolName = definition.name,
                result = "Directory $cleanRel is empty."
            )
        }

        val sortedFiles = filesList.sortedWith(compareBy({ !it.isDirectory }, { it.name }))
        val formatted = buildString {
            val headerRel = if (isRootPath) "." else requestedPath
            appendLine("Workspace files in \"$headerRel\" (${sortedFiles.size} items):")
            for (file in sortedFiles) {
                val rel = workspaceManager.getRelativePath(file)
                val typeTag = if (file.isDirectory) "[DIR ]" else "[FILE]"
                val sizeInfo = if (file.isDirectory) "" else " (${file.length()} bytes)"
                val dateInfo = dateFormat.format(Date(file.lastModified()))
                appendLine("  $typeTag $rel$sizeInfo - $dateInfo")
            }
        }.trimEnd()

        return ToolResult.success(
            callId = callId,
            toolName = definition.name,
            result = formatted
        )
    }
}
