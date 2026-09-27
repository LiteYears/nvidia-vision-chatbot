package com.example.agent.tools.workspace

import com.example.agent.tools.AgentTool
import com.example.agent.tools.ToolDefinition
import com.example.agent.tools.ToolParameter
import com.example.agent.tools.ToolResult
import java.io.File
import java.util.UUID

/**
 * Tool for displaying a structured visual directory tree of the agent workspace.
 * Helps the autonomous agent quickly understand multi-directory project layouts.
 */
class FileTreeTool(
    private val workspaceManager: AgentWorkspaceManager = AgentWorkspaceManager.getInstance()
) : AgentTool {

    override val definition: ToolDefinition = ToolDefinition(
        name = "file_tree",
        description = "Displays a structured visual directory tree of the agent workspace, showing all directories, files, and sizes.",
        parameters = listOf(
            ToolParameter(
                name = "path",
                type = "string",
                description = "Relative directory path to render tree from (default: '.' for workspace root)",
                required = false,
                default = "."
            ),
            ToolParameter(
                name = "max_depth",
                type = "number",
                description = "Maximum directory nesting depth to traverse (default: 4, maximum: 8)",
                required = false,
                default = 4
            )
        )
    )

    override suspend fun execute(arguments: Map<String, Any?>): ToolResult {
        val callId = UUID.randomUUID().toString()
        val rawPath = arguments["path"]?.toString()?.trim() ?: "."
        val maxDepth = when (val md = arguments["max_depth"] ?: arguments["depth"]) {
            is Number -> md.toInt()
            is String -> md.toIntOrNull() ?: 4
            else -> 4
        }.coerceIn(1, 8)

        val targetDir: File
        try {
            targetDir = workspaceManager.resolvePath(rawPath)
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
                error = "Target directory does not exist: '$rawPath'"
            )
        }

        if (targetDir.isFile) {
            val rel = workspaceManager.getRelativePath(targetDir)
            return ToolResult.success(
                callId = callId,
                toolName = definition.name,
                result = "$rel (${formatSize(targetDir.length())})"
            )
        }

        var dirCount = 0
        var fileCount = 0
        val sb = StringBuilder()
        val rootRel = try {
            workspaceManager.getRelativePath(targetDir).ifBlank { "." }
        } catch (_: Exception) {
            "."
        }

        sb.appendLine("WORKSPACE TREE ($rootRel):")

        fun renderBranch(dir: File, prefix: String, currentDepth: Int) {
            if (currentDepth > maxDepth) return
            val entries = dir.listFiles()?.sortedWith(
                compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase() }
            ) ?: return

            entries.forEachIndexed { index, file ->
                val isLast = index == entries.size - 1
                val connector = if (isLast) "└── " else "├── "
                val childPrefix = if (isLast) "$prefix    " else "$prefix│   "

                if (file.isDirectory) {
                    dirCount++
                    sb.appendLine("$prefix$connector${file.name}/")
                    renderBranch(file, childPrefix, currentDepth + 1)
                } else {
                    fileCount++
                    sb.appendLine("$prefix$connector${file.name} (${formatSize(file.length())})")
                }
            }
        }

        renderBranch(targetDir, "", 1)

        if (dirCount == 0 && fileCount == 0) {
            return ToolResult.success(
                callId = callId,
                toolName = definition.name,
                result = "Directory '$rootRel' is empty."
            )
        }

        sb.appendLine("\nTotal: $fileCount file(s), $dirCount directory(ies)")

        return ToolResult.success(
            callId = callId,
            toolName = definition.name,
            result = sb.toString().trimEnd()
        )
    }

    private fun formatSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f KB", bytes / 1024.0)
            else -> String.format(java.util.Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
        }
    }
}
