package com.example.agent.tools.workspace

import com.example.agent.tools.AgentTool
import com.example.agent.tools.ToolDefinition
import com.example.agent.tools.ToolParameter
import com.example.agent.tools.ToolResult
import java.io.File
import java.util.UUID

/**
 * Tool for safely deleting files or directories within the agent workspace.
 */
class FileDeleteTool(
    private val workspaceManager: AgentWorkspaceManager = AgentWorkspaceManager.getInstance()
) : AgentTool {

    override val definition: ToolDefinition = ToolDefinition(
        name = "file_delete",
        description = "Deletes a file or directory located inside the agent workspace.",
        parameters = listOf(
            ToolParameter(
                name = "path",
                type = "string",
                description = "Relative path of the file or directory to delete (e.g. 'temp.txt', 'cache')",
                required = true
            ),
            ToolParameter(
                name = "recursive",
                type = "boolean",
                description = "Whether to recursively delete a directory and all its contents (default: false)",
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

        val isRecursive = when (val r = arguments["recursive"]) {
            is Boolean -> r
            is String -> r.equals("true", ignoreCase = true)
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

        val workspaceRoot = workspaceManager.getWorkspaceDir()
        val targetPath = targetFile.canonicalPath
        if (targetPath == workspaceRoot.canonicalPath) {
            return ToolResult.failure(
                callId = callId,
                toolName = definition.name,
                error = "Cannot delete the workspace root directory."
            )
        }

        val rootfsDir = com.example.agent.proot.ProotRootfsManager.getInstance().ensureRootfs(workspaceRoot).canonicalPath
        if (targetPath == rootfsDir || targetPath.startsWith(rootfsDir + File.separator)) {
            return ToolResult.failure(
                callId = callId,
                toolName = definition.name,
                error = "Access denied: Deleting core system rootfs directories and files is prohibited."
            )
        }

        if (!targetFile.exists()) {
            return ToolResult.failure(
                callId = callId,
                toolName = definition.name,
                error = "File or directory does not exist: '$requestedPath'"
            )
        }

        val relPath = workspaceManager.getRelativePath(targetFile)

        if (targetFile.isDirectory) {
            val children = targetFile.listFiles()
            if (!children.isNullOrEmpty() && !isRecursive) {
                return ToolResult.failure(
                    callId = callId,
                    toolName = definition.name,
                    error = "Cannot delete directory '$relPath' because it is not empty (${children.size} items). Set 'recursive' to true to delete non-empty directories."
                )
            }

            val deleted = targetFile.deleteRecursively()
            return if (deleted) {
                ToolResult.success(
                    callId = callId,
                    toolName = definition.name,
                    result = "Successfully deleted directory '$relPath'."
                )
            } else {
                ToolResult.failure(
                    callId = callId,
                    toolName = definition.name,
                    error = "Failed to delete directory '$relPath'."
                )
            }
        } else {
            val deleted = targetFile.delete()
            return if (deleted) {
                ToolResult.success(
                    callId = callId,
                    toolName = definition.name,
                    result = "Successfully deleted file '$relPath'."
                )
            } else {
                ToolResult.failure(
                    callId = callId,
                    toolName = definition.name,
                    error = "Failed to delete file '$relPath'."
                )
            }
        }
    }
}
