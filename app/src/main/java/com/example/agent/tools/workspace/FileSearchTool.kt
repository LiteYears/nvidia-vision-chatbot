package com.example.agent.tools.workspace

import com.example.agent.tools.AgentTool
import com.example.agent.tools.ToolDefinition
import com.example.agent.tools.ToolParameter
import com.example.agent.tools.ToolResult
import java.io.File
import java.util.UUID
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

/**
 * Tool for searching keywords, phrases, or regular expressions across workspace files.
 * Enables the autonomous agent to locate function definitions, class structures, imports,
 * and references across large multi-file projects with optional regex and context lines.
 */
class FileSearchTool(
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
        name = "file_search",
        description = "Searches for text or regex patterns across files in the agent workspace. " +
            "Returns matching files, line numbers, matching text snippets, and optional surrounding context lines.",
        parameters = listOf(
            ToolParameter(
                name = "query",
                type = "string",
                description = "The keyword, phrase, or regular expression to search for across files",
                required = true
            ),
            ToolParameter(
                name = "path",
                type = "string",
                description = "Relative directory path to search within (default: '.' for entire workspace)",
                required = false,
                default = "."
            ),
            ToolParameter(
                name = "is_regex",
                type = "boolean",
                description = "Whether 'query' should be treated as a regular expression (default: false)",
                required = false,
                default = false
            ),
            ToolParameter(
                name = "extension",
                type = "string",
                description = "Optional file extension filter without dot (e.g. 'py', 'kt', 'json', 'txt', 'md')",
                required = false,
                default = ""
            ),
            ToolParameter(
                name = "file_pattern",
                type = "string",
                description = "Optional glob-like pattern to filter file names (e.g. '*test*', '*.py', 'build*')",
                required = false,
                default = ""
            ),
            ToolParameter(
                name = "case_sensitive",
                type = "boolean",
                description = "Whether the search should be case-sensitive (default: false)",
                required = false,
                default = false
            ),
            ToolParameter(
                name = "context_lines",
                type = "number",
                description = "Number of context lines to display before and after each match (0 to 3, default: 0)",
                required = false,
                default = 0
            ),
            ToolParameter(
                name = "show_hidden",
                type = "boolean",
                description = "Whether to include hidden or build directories like .git, node_modules (default: false)",
                required = false,
                default = false
            ),
            ToolParameter(
                name = "max_results",
                type = "number",
                description = "Maximum matching lines to return (default: 30, maximum: 100)",
                required = false,
                default = 30
            )
        )
    )

    override suspend fun execute(arguments: Map<String, Any?>): ToolResult {
        val callId = UUID.randomUUID().toString()
        val query = (arguments["query"] ?: arguments["search"] ?: arguments["pattern"])?.toString()?.trim()

        if (query.isNullOrBlank()) {
            return ToolResult.failure(
                callId = callId,
                toolName = definition.name,
                error = "Missing or empty required parameter 'query'."
            )
        }

        val rawPath = arguments["path"]?.toString()?.trim() ?: "."
        val extFilter = arguments["extension"]?.toString()?.trim()?.removePrefix(".")?.lowercase() ?: ""
        val filePattern = arguments["file_pattern"]?.toString()?.trim() ?: ""
        val isRegex = when (val ir = arguments["is_regex"] ?: arguments["regex"]) {
            is Boolean -> ir
            is String -> ir.equals("true", ignoreCase = true)
            else -> false
        }
        val caseSensitive = when (val cs = arguments["case_sensitive"]) {
            is Boolean -> cs
            is String -> cs.equals("true", ignoreCase = true)
            else -> false
        }
        val showHidden = when (val sh = arguments["show_hidden"] ?: arguments["include_ignored"]) {
            is Boolean -> sh
            is String -> sh.equals("true", ignoreCase = true)
            else -> false
        }
        val contextLines = when (val cl = arguments["context_lines"] ?: arguments["context"]) {
            is Number -> cl.toInt()
            is String -> cl.toIntOrNull() ?: 0
            else -> 0
        }.coerceIn(0, 3)

        val maxResults = when (val mr = arguments["max_results"] ?: arguments["limit"]) {
            is Number -> mr.toInt()
            is String -> mr.toIntOrNull() ?: 30
            else -> 30
        }.coerceIn(1, 100)

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

        val flags = if (caseSensitive) 0 else Pattern.CASE_INSENSITIVE
        val pattern: Pattern = try {
            if (isRegex) {
                Pattern.compile(query, flags)
            } else {
                Pattern.compile(Pattern.quote(query), flags)
            }
        } catch (e: PatternSyntaxException) {
            return ToolResult.failure(
                callId = callId,
                toolName = definition.name,
                error = "Invalid regular expression pattern '$query': ${e.description} at index ${e.index}"
            )
        } catch (e: Exception) {
            return ToolResult.failure(
                callId = callId,
                toolName = definition.name,
                error = "Pattern compilation error: ${e.message}"
            )
        }

        val namePattern: Pattern? = if (filePattern.isNotBlank()) {
            val regexStr = filePattern
                .replace(".", "\\.")
                .replace("*", ".*")
                .replace("?", ".")
            try {
                Pattern.compile(regexStr, Pattern.CASE_INSENSITIVE)
            } catch (_: Exception) {
                null
            }
        } else null

        val matches = mutableListOf<String>()
        val visitedCanonicals = mutableSetOf<String>()

        val filesToSearch = if (targetDir.isFile) {
            listOf(targetDir)
        } else {
            val collected = mutableListOf<File>()
            targetDir.walkTopDown()
                .maxDepth(8)
                .onEnter { dir ->
                    val canonical = try { dir.canonicalPath } catch (_: Exception) { dir.absolutePath }
                    if (!visitedCanonicals.add(canonical)) {
                        return@onEnter false // Circular symlink loop prevention
                    }
                    if (!showHidden && dir != targetDir) {
                        val name = dir.name
                        if (name.startsWith(".") || DEFAULT_IGNORED_DIRS.contains(name.lowercase())) {
                            return@onEnter false
                        }
                    }
                    true
                }
                .filter { it.isFile && it.length() <= 2 * 1024 * 1024 } // Skip files > 2MB
                .forEach { f ->
                    if (collected.size < 500) {
                        collected.add(f)
                    }
                }
            collected
        }

        for (file in filesToSearch) {
            if (matches.size >= maxResults) break

            if (extFilter.isNotBlank() && !file.extension.equals(extFilter, ignoreCase = true)) {
                continue
            }
            if (namePattern != null && !namePattern.matcher(file.name).find()) {
                continue
            }

            val relPath = workspaceManager.getRelativePath(file)
            try {
                val lines = file.readLines(Charsets.UTF_8)
                for (idx in lines.indices) {
                    val line = lines[idx]
                    if (pattern.matcher(line).find()) {
                        val lineNum = idx + 1

                        if (contextLines > 0) {
                            val startCtx = (idx - contextLines).coerceAtLeast(0)
                            val endCtx = (idx + contextLines).coerceAtMost(lines.size - 1)
                            for (c in startCtx until idx) {
                                val ctxLine = lines[c].trimEnd()
                                val display = if (ctxLine.length > 120) ctxLine.take(117) + "..." else ctxLine
                                matches.add("$relPath-${c + 1}- $display")
                            }
                        }

                        val trimmedLine = line.trim()
                        val displayLine = if (trimmedLine.length > 120) trimmedLine.take(117) + "..." else trimmedLine
                        matches.add("$relPath:$lineNum: $displayLine")

                        if (contextLines > 0) {
                            val endCtx = (idx + contextLines).coerceAtMost(lines.size - 1)
                            for (c in (idx + 1)..endCtx) {
                                val ctxLine = lines[c].trimEnd()
                                val display = if (ctxLine.length > 120) ctxLine.take(117) + "..." else ctxLine
                                matches.add("$relPath-${c + 1}- $display")
                            }
                        }

                        if (matches.size >= maxResults) break
                    }
                }
            } catch (_: Exception) {
                // Ignore binary, non-UTF8, or unreadable files
            }
        }

        return if (matches.isEmpty()) {
            ToolResult.success(
                callId = callId,
                toolName = definition.name,
                result = "No matches found for \"$query\" in workspace path '$rawPath'."
            )
        } else {
            val formatted = buildString {
                appendLine("Found ${matches.size} match result(s) for \"$query\":")
                matches.forEach { appendLine("  • $it") }
                if (matches.size >= maxResults) {
                    appendLine("\n[Result limit reached. Showing first $maxResults matches.]")
                }
            }.trimEnd()

            ToolResult.success(
                callId = callId,
                toolName = definition.name,
                result = formatted
            )
        }
    }
}
