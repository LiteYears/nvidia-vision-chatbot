package com.example.agent.tools.workspace

import com.example.agent.artifact.Artifact
import com.example.agent.tools.AgentTool
import com.example.agent.tools.ToolDefinition
import com.example.agent.tools.ToolParameter
import com.example.agent.tools.ToolResult
import com.example.agent.verification.TaskVerificationEngine
import java.io.File
import java.io.IOException
import java.util.Base64
import java.util.UUID

/**
 * Tool for safely writing binary files (such as packages, archives, or images)
 * into the agent workspace using Base64 encoding.
 */
class WriteBinaryFileTool(
    private val workspaceManager: AgentWorkspaceManager = AgentWorkspaceManager.getInstance()
) : AgentTool {

    override val definition: ToolDefinition = ToolDefinition(
        name = "write_binary_file",
        description = "Writes a binary file into the workspace from Base64 encoded data. " +
            "Use for binary deliverables, images, compiled objects, or archives.",
        parameters = listOf(
            ToolParameter(
                name = "path",
                type = "string",
                description = "Destination relative path (e.g. 'output/document.docx', 'assets/image.png')",
                required = true
            ),
            ToolParameter(
                name = "base64_data",
                type = "string",
                description = "Base64 encoded string of the binary content",
                required = true
            )
        )
    )

    override suspend fun execute(arguments: Map<String, Any?>): ToolResult {
        val callId = UUID.randomUUID().toString()
        val path = (arguments["path"] ?: arguments["file"])?.toString()?.trim()

        if (path.isNullOrBlank()) {
            return ToolResult.failure(callId, definition.name, "Missing required parameter 'path'.")
        }

        val base64Data = (arguments["base64_data"] ?: arguments["content"] ?: arguments["data"])?.toString() ?: ""
        if (base64Data.isBlank()) {
            return ToolResult.failure(callId, definition.name, "Missing or empty required parameter 'base64_data'.")
        }

        val targetFile: File
        try {
            targetFile = workspaceManager.resolvePath(path)
        } catch (e: SecurityException) {
            return ToolResult.failure(callId, definition.name, e.message ?: "Path security violation.")
        }

        return try {
            targetFile.parentFile?.let { if (!it.exists()) it.mkdirs() }

            val cleaned = base64Data.trim().replace(Regex("\\s+"), "")
            val bytes = try {
                Base64.getDecoder().decode(cleaned)
            } catch (e: IllegalArgumentException) {
                return ToolResult.failure(callId, definition.name, "Invalid Base64 payload: ${e.message}")
            }

            workspaceManager.atomicWrite(targetFile, bytes)
            val relPath = workspaceManager.getRelativePath(targetFile)
            val typeInfo = workspaceManager.getFileTypeInfo(targetFile)
            val verification = TaskVerificationEngine.verifyFile(targetFile)

            val artifact = Artifact(
                id = UUID.randomUUID().toString(),
                taskId = workspaceManager.activeSessionId ?: "default",
                path = relPath,
                filename = targetFile.name,
                mimeType = typeInfo.mimeType,
                size = targetFile.length(),
                createdAt = targetFile.lastModified(),
                modifiedAt = targetFile.lastModified(),
                exists = true,
                valid = verification.isPassed,
                verificationStatus = if (verification.isPassed) "VALID" else "INVALID",
                verificationDetails = verification.details
            )

            ToolResult.filesystemResult(
                callId = callId,
                toolName = definition.name,
                isSuccess = true,
                path = relPath,
                operation = "write_binary",
                fileType = typeInfo.category.name,
                fileSize = targetFile.length(),
                modifiedTime = targetFile.lastModified(),
                summary = "Successfully wrote binary deliverable '$relPath' (${bytes.size} bytes written).",
                artifact = artifact
            )
        } catch (e: IOException) {
            ToolResult.failure(callId, definition.name, "Failed writing binary file '$path': ${e.message}")
        }
    }
}
