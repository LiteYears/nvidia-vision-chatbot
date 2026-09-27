package com.example.agent.tools.workspace

import com.example.agent.tools.AgentTool
import com.example.agent.tools.ToolDefinition
import com.example.agent.tools.ToolParameter
import com.example.agent.tools.ToolResult
import java.io.File
import java.util.UUID

/**
 * Agent tool for extracting ZIP archives containing source code or project files
 * inside the agent workspace.
 */
class ArchiveExtractTool(
    private val workspaceManager: AgentWorkspaceManager = AgentWorkspaceManager.getInstance()
) : AgentTool {

    override val definition: ToolDefinition = ToolDefinition(
        name = "archive_extract",
        description = "Extracts a ZIP archive into the agent workspace. " +
            "Safely unpacks folders and source code files, allowing the agent to inspect, modify, and fix codebases.",
        parameters = listOf(
            ToolParameter(
                name = "path",
                type = "string",
                description = "Relative path of the ZIP archive inside the workspace (e.g. 'project.zip', 'data/code.zip')",
                required = true
            ),
            ToolParameter(
                name = "destination",
                type = "string",
                description = "Directory where the archive should be extracted (default: '.' for workspace root)",
                required = false,
                default = "."
            ),
            ToolParameter(
                name = "overwrite",
                type = "boolean",
                description = "Whether to overwrite existing files (default: true)",
                required = false,
                default = true
            )
        )
    )

    override suspend fun execute(arguments: Map<String, Any?>): ToolResult {
        val callId = UUID.randomUUID().toString()
        val requestedPath = (arguments["path"] ?: arguments["file"] ?: arguments["archive"])?.toString()?.trim()

        if (requestedPath.isNullOrBlank()) {
            return ToolResult.failure(
                callId = callId,
                toolName = definition.name,
                error = "Missing or empty required parameter 'path'."
            )
        }

        val destination = arguments["destination"]?.toString()?.trim() ?: "."
        val overwrite = when (val o = arguments["overwrite"]) {
            is Boolean -> o
            is String -> o.equals("true", ignoreCase = true)
            else -> true
        }

        val zipFile: File
        val targetDir: File
        try {
            zipFile = workspaceManager.resolvePath(requestedPath)
            targetDir = workspaceManager.resolvePath(destination)
        } catch (e: SecurityException) {
            return ToolResult.failure(
                callId = callId,
                toolName = definition.name,
                error = e.message ?: "Path traversal security error."
            )
        }

        if (!zipFile.exists() || !zipFile.isFile) {
            return ToolResult.failure(
                callId = callId,
                toolName = definition.name,
                error = "Archive file not found: '$requestedPath'"
            )
        }

        val extractResult = workspaceManager.extractZipFile(
            zipFile = zipFile,
            destinationDir = targetDir,
            overwrite = overwrite
        )

        return if (extractResult.isSuccess) {
            val preview = buildString {
                appendLine("Successfully extracted ${extractResult.totalFiles} files from '${zipFile.name}' into '$destination' (${extractResult.totalBytes} bytes total).")
                appendLine("Extracted files:")
                val displayList = extractResult.extractedPaths.take(25)
                for (p in displayList) {
                    appendLine("  - $p")
                }
                if (extractResult.extractedPaths.size > 25) {
                    appendLine("  ... and ${extractResult.extractedPaths.size - 25} more files.")
                }
                appendLine("\nCodebase is unpacked and ready for inspection (`file_tree`, `file_search`), modification (`file_patch`), and testing (`run_command`).")
            }.trim()

            ToolResult.success(
                callId = callId,
                toolName = definition.name,
                result = preview
            )
        } else {
            ToolResult.failure(
                callId = callId,
                toolName = definition.name,
                error = "Failed to extract archive '${zipFile.name}': ${extractResult.errorMessage}"
            )
        }
    }
}
