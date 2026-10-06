package com.example.agent.tools.workspace

import com.example.agent.artifact.Artifact
import com.example.agent.tools.AgentTool
import com.example.agent.tools.ToolDefinition
import com.example.agent.tools.ToolParameter
import com.example.agent.tools.ToolResult
import com.example.agent.verification.TaskVerificationEngine
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Base64
import java.util.UUID

/**
 * Tool for creating and writing UTF-8 text files inside the agent workspace.
 *
 * Rejects attempts to fabricate binary package formats (e.g. .docx, .pdf, .apk, .zip)
 * as plain text, ensuring valid document construction via dedicated tools or python.
 */
class FileWriteTool(
    private val workspaceManager: AgentWorkspaceManager = AgentWorkspaceManager.getInstance()
) : AgentTool {

    companion object {
        private val BINARY_EXTENSIONS = setOf(
            "docx", "doc", "pdf", "zip", "apk", "tar", "gz", "bz2", "7z", "bin",
            "png", "jpg", "jpeg", "gif", "webp", "exe", "so", "dex", "jar"
        )
    }

    override val definition: ToolDefinition = ToolDefinition(
        name = "file_write",
        description = "Creates or replaces a UTF-8 text or code file inside the agent workspace. " +
            "Do NOT use for binary formats such as DOCX, PDF, APK, ZIP, or images (generate those via Python scripts using python-docx or write_binary_file). " +
            "Returns path, byte size, and format metadata.",
        parameters = listOf(
            ToolParameter(
                name = "path",
                type = "string",
                description = "Relative path of the text file to write (e.g. 'script.py', 'summary.md', 'src/config.json')",
                required = true
            ),
            ToolParameter(
                name = "content",
                type = "string",
                description = "The text content to write into the file",
                required = true
            ),
            ToolParameter(
                name = "append",
                type = "boolean",
                description = "Whether to append to the existing file rather than overwriting (default: false)",
                required = false,
                default = false
            ),
            ToolParameter(
                name = "encoding",
                type = "string",
                description = "Content encoding: 'utf-8' (default) or 'base64'",
                required = false,
                default = "utf-8"
            )
        )
    )

    override suspend fun execute(arguments: Map<String, Any?>): ToolResult {
        val callId = UUID.randomUUID().toString()
        val requestedPath = (arguments["path"] ?: arguments["file"] ?: arguments["file_path"] ?: arguments["filePath"] ?: arguments["filename"] ?: arguments["TargetFile"] ?: arguments["target_file"] ?: arguments["targetFile"] ?: arguments["AbsolutePath"])?.toString()?.trim()

        if (requestedPath.isNullOrBlank()) {
            return ToolResult.failure(
                callId = callId,
                toolName = definition.name,
                error = "Missing or empty required parameter 'path'."
            )
        }

        val content = (arguments["content"] ?: arguments["text"] ?: arguments["code"] ?: arguments["data"] ?: arguments["CodeContent"] ?: arguments["codeContent"] ?: arguments["file_content"] ?: arguments["body"])?.toString() ?: ""
        val isAppend = when (val a = arguments["append"]) {
            is Boolean -> a
            is String -> a.equals("true", ignoreCase = true)
            else -> false
        }
        val isBase64Arg = when (val b = arguments["is_base64"]) {
            is Boolean -> b
            is String -> b.equals("true", ignoreCase = true)
            else -> false
        }
        val encoding = if (isBase64Arg) "base64" else (arguments["encoding"]?.toString()?.trim()?.lowercase() ?: "utf-8")

        val ext = requestedPath.substringAfterLast('.', "").lowercase()
        if (BINARY_EXTENSIONS.contains(ext) && encoding != "base64") {
            return ToolResult.failure(
                callId = callId,
                toolName = definition.name,
                error = "Cannot write binary package format '.$ext' using plain text writing tool. " +
                    "To generate a valid $ext file, write a Python script using 'python-docx' or Python packaging libraries, " +
                    "or write binary Base64 content via write_binary_file."
            )
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

        if (targetFile.canonicalPath == workspaceManager.getWorkspaceDir().canonicalPath) {
            return ToolResult.failure(
                callId = callId,
                toolName = definition.name,
                error = "Cannot write content to workspace root directory."
            )
        }

        if (targetFile.exists() && targetFile.isDirectory) {
            return ToolResult.failure(
                callId = callId,
                toolName = definition.name,
                error = "Cannot write to '$requestedPath': path is an existing directory."
            )
        }

        return try {
            targetFile.parentFile?.let { parent ->
                if (!parent.exists()) {
                    parent.mkdirs()
                }
            }

            val bytes: ByteArray = if (encoding == "base64") {
                val cleaned = content.trim().replace(Regex("\\s+"), "")
                try {
                    Base64.getDecoder().decode(cleaned)
                } catch (e: IllegalArgumentException) {
                    return ToolResult.failure(
                        callId = callId,
                        toolName = definition.name,
                        error = "Failed to decode Base64 content: ${e.message}"
                    )
                }
            } else {
                content.toByteArray(Charsets.UTF_8)
            }

            if (isAppend) {
                FileOutputStream(targetFile, true).use { fos ->
                    fos.write(bytes)
                    fos.flush()
                }
            } else {
                workspaceManager.atomicWrite(targetFile, bytes)
            }

            val relPath = workspaceManager.getRelativePath(targetFile)
            val actionWord = if (isAppend) "appended to" else "written to"
            val totalSize = targetFile.length()
            val typeInfo = workspaceManager.getFileTypeInfo(targetFile)

            val verification = TaskVerificationEngine.verifyFile(targetFile)
            val artifact = Artifact(
                id = UUID.randomUUID().toString(),
                taskId = workspaceManager.activeSessionId ?: "default",
                path = relPath,
                filename = targetFile.name,
                mimeType = typeInfo.mimeType,
                size = totalSize,
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
                operation = if (isAppend) "append" else "write",
                fileType = typeInfo.category.name,
                fileSize = totalSize,
                modifiedTime = targetFile.lastModified(),
                summary = "Successfully $actionWord '$relPath' (${bytes.size} bytes written, format: ${typeInfo.category}, total size: $totalSize bytes).",
                artifact = artifact
            )
        } catch (e: IOException) {
            ToolResult.failure(
                callId = callId,
                toolName = definition.name,
                error = "Failed to write file '$requestedPath': ${e.message}"
            )
        }
    }
}
