package com.example.agent.tools.workspace

import com.example.agent.tools.AgentTool
import com.example.agent.tools.ToolDefinition
import com.example.agent.tools.ToolParameter
import com.example.agent.tools.ToolResult
import java.io.File
import java.util.UUID
import java.util.regex.Pattern

/**
 * Tool for searching keywords or regular expressions across workspace files.
 * Enables the agent to locate function definitions, variables, and references across a multi-file project.
 */
class FileSearchTool(
    private val workspaceManager: AgentWorkspaceManager = AgentWorkspaceManager.getInstance()
) : AgentTool {

    override val definition: ToolDefinition = ToolDefinition(
        name = "file_search",
        description = "Searches for text or regex patterns across files in the agent workspace. " +
            "Returns matching files, line numbers, and matching text snippets.",
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
                name = "extension",
                type = "string",
                description = "Optional file extension filter without dot (e.g. 'py', 'json', 'txt', 'md')",
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
        val caseSensitive = when (val cs = arguments["case_sensitive"]) {
            is Boolean -> cs
            is String -> cs.equals("true", ignoreCase = true)
            else -> false
        }
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

        val pattern = try {
            val flags = if (caseSensitive) 0 else Pattern.CASE_INSENSITIVE
            Pattern.compile(Pattern.quote(query), flags)
        } catch (_: Exception) {
            Pattern.compile(query, if (caseSensitive) 0 else Pattern.CASE_INSENSITIVE)
        }

        val matches = mutableListOf<String>()
        val filesToSearch = if (targetDir.isFile) {
            listOf(targetDir)
        } else {
            targetDir.walkTopDown()
                .maxDepth(6)
                .filter { it.isFile && it.length() <= 1024 * 1024 } // Skip files > 1MB
                .toList()
        }

        for (file in filesToSearch) {
            if (matches.size >= maxResults) break
            if (extFilter.isNotBlank() && !file.extension.equals(extFilter, ignoreCase = true)) {
                continue
            }

            val relPath = workspaceManager.getRelativePath(file)
            try {
                var lineNum = 0
                file.useLines { lines ->
                    for (line in lines) {
                        lineNum++
                        if (pattern.matcher(line).find()) {
                            val trimmedLine = line.trim()
                            val displayLine = if (trimmedLine.length > 120) trimmedLine.take(117) + "..." else trimmedLine
                            matches.add("$relPath:$lineNum: $displayLine")
                            if (matches.size >= maxResults) break
                        }
                    }
                }
            } catch (_: Exception) {
                // Ignore binary or unreadable files
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
                appendLine("Found ${matches.size} match(es) for \"$query\":")
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
