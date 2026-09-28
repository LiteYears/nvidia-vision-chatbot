package com.example.agent.tools.workspace

import com.example.agent.tools.AgentTool
import com.example.agent.tools.ToolDefinition
import com.example.agent.tools.ToolParameter
import com.example.agent.tools.ToolResult
import java.io.File
import java.util.UUID

/**
 * Tool for displaying a structured visual directory tree of the agent workspace.
 * Helps the autonomous agent quickly understand multi-directory project layouts,
 * while automatically filtering out internal noise directories (.git, node_modules, build)
 * and preventing symlink recursion loops.
 */
class FileTreeTool(
    private val workspaceManager: AgentWorkspaceManager = AgentWorkspaceManager.getInstance()
) : AgentTool {

    companion object {
        const val MAX_ENTRIES_DEFAULT = 150

        val DEFAULT_IGNORED_DIRS = setOf(
            ".git", ".rootfs", "ubuntu_rootfs", "node_modules", "__pycache__", ".gradle",
            "build", ".idea", ".vscode", "venv", ".venv", ".pytest_cache",
            ".mypy_cache", "target", "dist", ".cache"
        )
    }

    override val definition: ToolDefinition = ToolDefinition(
        name = "file_tree",
        description = "Displays a structured visual directory tree of the agent workspace, showing all directories, files, and sizes. " +
            "Automatically filters noise directories (.git, node_modules) and guards against cyclic symlinks.",
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
            ),
            ToolParameter(
                name = "show_hidden",
                type = "boolean",
                description = "Whether to include hidden and build directories like .git or node_modules (default: false)",
                required = false,
                default = false
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

        val showHidden = when (val sh = arguments["show_hidden"] ?: arguments["include_ignored"]) {
            is Boolean -> sh
            is String -> sh.equals("true", ignoreCase = true)
            else -> false
        }

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
        var totalEntries = 0
        var wasTruncated = false
        val visitedCanonicals = mutableSetOf<String>()

        val sb = StringBuilder()
        val rootRel = try {
            workspaceManager.getRelativePath(targetDir).ifBlank { "." }
        } catch (_: Exception) {
            "."
        }

        sb.appendLine("WORKSPACE TREE ($rootRel):")

        fun renderBranch(dir: File, prefix: String, currentDepth: Int) {
            if (currentDepth > maxDepth || totalEntries >= MAX_ENTRIES_DEFAULT) return

            val canonical = try { dir.canonicalPath } catch (_: Exception) { dir.absolutePath }
            if (!visitedCanonicals.add(canonical)) {
                sb.appendLine("$prefix└── [Cyclic symlink/directory cycle detected]")
                return
            }

            val rawEntries = dir.listFiles() ?: return
            val filteredEntries = rawEntries.filter { file ->
                if (showHidden || file == targetDir) {
                    true
                } else {
                    val name = file.name
                    !name.startsWith(".") && !DEFAULT_IGNORED_DIRS.contains(name.lowercase())
                }
            }.sortedWith(
                compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase() }
            )

            filteredEntries.forEachIndexed { index, file ->
                if (totalEntries >= MAX_ENTRIES_DEFAULT) {
                    wasTruncated = true
                    return
                }

                totalEntries++
                val isLast = index == filteredEntries.size - 1
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

        sb.appendLine()
        if (wasTruncated) {
            sb.appendLine("[Tree output capped at $MAX_ENTRIES_DEFAULT entries. Pass a specific directory in 'path' to inspect deeper subtrees.]")
        }
        sb.appendLine("Total: $fileCount file(s), $dirCount directory(ies)")

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
