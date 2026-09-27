package com.example.agent.tools.workspace

import com.example.agent.tools.AgentTool
import com.example.agent.tools.ToolDefinition
import com.example.agent.tools.ToolParameter
import com.example.agent.tools.ToolResult
import java.io.File
import java.util.UUID

/**
 * Tool for creating directories inside the agent workspace.
 * Automatically creates any missing parent directories.
 */
class DirectoryCreateTool(
    private val workspaceManager: AgentWorkspaceManager = AgentWorkspaceManager.getInstance()
) : AgentTool {

    override val definition: ToolDefinition = ToolDefinition(
        name = "directory_create",
        description = "Creates a directory (and any necessary parent directories) inside the agent workspace.",
        parameters = listOf(
            ToolParameter(
                name = "path",
                type = "string",
                description = "Relative path of the directory to create (e.g. 'src/components', 'data/processed', 'tests/unit')",
                required = true
            )
        )
    )

    override suspend fun execute(arguments: Map<String, Any?>): ToolResult {
        val callId = UUID.randomUUID().toString()
        val requestedPath = (arguments["path"] ?: arguments["dir"])?.toString()?.trim()

        if (requestedPath.isNullOrBlank()) {
            return ToolResult.failure(
                callId = callId,
                toolName = definition.name,
                error = "Missing or empty required parameter 'path'."
            )
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

        if (targetDir.isFile) {
            return ToolResult.failure(
                callId = callId,
                toolName = definition.name,
                error = "Cannot create directory '$requestedPath': a regular file already exists at this path."
            )
        }

        val existed = targetDir.exists()
        if (!existed) {
            val created = targetDir.mkdirs()
            if (!created && !targetDir.exists()) {
                return ToolResult.failure(
                    callId = callId,
                    toolName = definition.name,
                    error = "Failed to create directory '$requestedPath'."
                )
            }
        }

        val relPath = workspaceManager.getRelativePath(targetDir)
        val msg = if (existed) {
            "Directory '$relPath' already exists."
        } else {
            "Successfully created directory '$relPath'."
        }

        return ToolResult.success(
            callId = callId,
            toolName = definition.name,
            result = msg
        )
    }
}
