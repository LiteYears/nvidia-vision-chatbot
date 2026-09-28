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
 * Automatically filters internal build/noise directories (.git, node_modules)
 * and supports custom limits and recursive traversal with cycle detection.
 */
class FileListTool(
    private val workspaceManager: AgentWorkspaceManager = AgentWorkspaceManager.getInstance()
) : AgentTool {

    companion object {
        val DEFAULT_IGNORED_DIRS = setOf(
            ".git", ".rootfs", "ubuntu_rootfs", "node_modules", "__pycache__", ".gradle",
            "build", ".idea", ".vscode", "venv", ".venv", ".pytest_cache",
            ".mypy_cache", "target", "dist", ".cache"
        )
    }

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
            ),
            ToolParameter(
                name = "show_hidden",
                type = "boolean",
                description = "Whether to include hidden and build directories like .git or node_modules (default: false)",
                required = false,
                default = false
            ),
            ToolParameter(
                name = "limit",
                type = "number",
                description = "Maximum entries to return (default: 100, maximum: 300)",
                required = false,
                default = 100
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
        val showHidden = when (val sh = arguments["show_hidden"] ?: arguments["include_ignored"]) {
            is Boolean -> sh
            is String -> sh.equals("true", ignoreCase = true)
            else -> false
        }
        val limit = when (val l = arguments["limit"] ?: arguments["max_results"]) {
            is Number -> l.toInt()
            is String -> l.toIntOrNull() ?: 100
            else -> 100
        }.coerceIn(10, 300)

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

        val targetCanonical = try { targetDir.canonicalPath } catch (_: Exception) { targetDir.absolutePath }
        val visitedCanonicals = mutableSetOf<String>()
        var wasTruncated = false

        val filesList: List<File> = if (isRecursive) {
            val collected = mutableListOf<File>()
            targetDir.walkTopDown()
                .maxDepth(6)
                .onEnter { dir ->
                    val canonical = try { dir.canonicalPath } catch (_: Exception) { dir.absolutePath }
                    if (!visitedCanonicals.add(canonical)) {
                        return@onEnter false
                    }
                    if (!showHidden && dir != targetDir) {
                        val name = dir.name
                        if (name.startsWith(".") || DEFAULT_IGNORED_DIRS.contains(name.lowercase())) {
                            return@onEnter false
                        }
                    }
                    true
                }
                .filter { file ->
                    val c = try { file.canonicalPath } catch (_: Exception) { file.absolutePath }
                    c != targetCanonical && (showHidden || (!file.name.startsWith(".") && !DEFAULT_IGNORED_DIRS.contains(file.name.lowercase())))
                }
                .forEach { file ->
                    if (collected.size < limit) {
                        collected.add(file)
                    } else {
                        wasTruncated = true
                    }
                }
            collected
        } else {
            val raw = targetDir.listFiles()?.toList() ?: emptyList()
            raw.filter { file ->
                showHidden || (!file.name.startsWith(".") && !DEFAULT_IGNORED_DIRS.contains(file.name.lowercase()))
            }.take(limit).also {
                if (raw.size > limit) wasTruncated = true
            }
        }

        if (filesList.isEmpty()) {
            val cleanRel = if (isRootPath) "workspace root" else "'$requestedPath'"
            return ToolResult.success(
                callId = callId,
                toolName = definition.name,
                result = "Directory $cleanRel is empty."
            )
        }

        val sortedFiles = filesList.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
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
            if (wasTruncated) {
                appendLine("\n[Output capped at $limit entries. Pass a specific subdirectory in 'path' to inspect deeper contents.]")
            }
        }.trimEnd()

        return ToolResult.success(
            callId = callId,
            toolName = definition.name,
            result = formatted
        )
    }
}
