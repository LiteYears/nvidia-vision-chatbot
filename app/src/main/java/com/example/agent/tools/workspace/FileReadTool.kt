package com.example.agent.tools.workspace

import com.example.agent.tools.AgentTool
import com.example.agent.tools.ToolDefinition
import com.example.agent.tools.ToolParameter
import com.example.agent.tools.ToolResult
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.util.Base64
import java.util.UUID

/**
 * Tool for reading all file types safely within the agent workspace.
 *
 * Supports:
 * - Text, source code, data, and configurations (UTF-8) with optional line range and line numbers
 * - Binary files, images, archives, datasets, and executables (Base64, Hex dump, or structured metadata summary)
 * - Configurable byte limits to prevent excessive context consumption
 */
class FileReadTool(
    private val workspaceManager: AgentWorkspaceManager = AgentWorkspaceManager.getInstance()
) : AgentTool {

    override val definition: ToolDefinition = ToolDefinition(
        name = "file_read",
        description = "Reads the content of any file (text, source code, data, or binary) located within the agent workspace. " +
            "Supports line-by-line inspection (start_line, end_line), line numbers, and encodings ('auto', 'utf-8', 'base64', 'hex').",
        parameters = listOf(
            ToolParameter(
                name = "path",
                type = "string",
                description = "Relative path of the file to read (e.g. 'notes.txt', 'src/pipeline.py', 'data/image.png')",
                required = true
            ),
            ToolParameter(
                name = "start_line",
                type = "number",
                description = "Optional 1-indexed starting line number for slicing text files",
                required = false
            ),
            ToolParameter(
                name = "end_line",
                type = "number",
                description = "Optional 1-indexed ending line number for slicing text files (inclusive)",
                required = false
            ),
            ToolParameter(
                name = "show_line_numbers",
                type = "boolean",
                description = "Whether to prefix each line with its 1-indexed line number (default: false, auto-enabled if line range specified)",
                required = false,
                default = false
            ),
            ToolParameter(
                name = "max_bytes",
                type = "number",
                description = "Maximum bytes to read (default: 65536 / 64 KB, maximum: 4194304 / 4 MB)",
                required = false,
                default = 65536
            ),
            ToolParameter(
                name = "encoding",
                type = "string",
                description = "Content encoding mode: 'auto' (default: text as UTF-8, binary as structured summary), 'utf-8', 'base64', or 'hex'",
                required = false,
                default = "auto"
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

        if (!targetFile.exists()) {
            val availableFiles = try {
                workspaceManager.listWorkspaceFiles()
                    .filter { !it.isDirectory }
                    .map { it.relativePath }
            } catch (_: Exception) {
                emptyList()
            }
            val baseName = File(requestedPath).name
            val matching = availableFiles.filter { it.endsWith(baseName) || it.contains(baseName) }

            val errorMsg = buildString {
                appendLine("File not found: '$requestedPath'")
                if (matching.isNotEmpty()) {
                    appendLine("\nDid you mean:")
                    for (m in matching) {
                        appendLine("  $m")
                    }
                }
                if (availableFiles.isNotEmpty()) {
                    appendLine("\nAvailable files in workspace:")
                    for (f in availableFiles.take(15)) {
                        appendLine("  - $f")
                    }
                    if (availableFiles.size > 15) {
                        appendLine("  ... (${availableFiles.size - 15} more files)")
                    }
                } else {
                    appendLine("\n(Workspace is currently empty)")
                }
                appendLine("\nRecovery advice:")
                appendLine("Inspect the workspace with 'file_list' or create the file using 'file_write'.")
            }

            return ToolResult.failure(
                callId = callId,
                toolName = definition.name,
                error = errorMsg.trim()
            )
        }

        if (targetFile.isDirectory) {
            return ToolResult.failure(
                callId = callId,
                toolName = definition.name,
                error = "Cannot read '$requestedPath': path is a directory, not a file. Use 'file_list' to view contents."
            )
        }

        val maxBytes = when (val m = arguments["max_bytes"]) {
            is Number -> m.toInt()
            is String -> m.toIntOrNull() ?: 65536
            else -> 65536
        }.coerceIn(512, 4194304)

        val encoding = arguments["encoding"]?.toString()?.trim()?.lowercase() ?: "auto"

        val startLineArg = when (val sl = arguments["start_line"] ?: arguments["StartLine"] ?: arguments["startLine"] ?: arguments["from_line"]) {
            is Number -> sl.toInt()
            is String -> sl.toIntOrNull()
            else -> null
        }
        val endLineArg = when (val el = arguments["end_line"] ?: arguments["EndLine"] ?: arguments["endLine"] ?: arguments["to_line"]) {
            is Number -> el.toInt()
            is String -> el.toIntOrNull()
            else -> null
        }
        val showLineNumbers = when (val sln = arguments["show_line_numbers"] ?: arguments["line_numbers"]) {
            is Boolean -> sln
            is String -> sln.equals("true", ignoreCase = true)
            else -> (startLineArg != null || endLineArg != null)
        }

        return try {
            val totalSize = targetFile.length()
            if (totalSize == 0L) {
                return ToolResult.success(
                    callId = callId,
                    toolName = definition.name,
                    result = "File '$requestedPath' is empty (0 bytes)."
                )
            }

            val isBinary = workspaceManager.isBinaryFile(targetFile)
            val typeInfo = workspaceManager.getFileTypeInfo(targetFile)

            // If line slicing was requested on a text file
            if (!isBinary && (startLineArg != null || endLineArg != null || showLineNumbers)) {
                val allLines = targetFile.readLines(Charsets.UTF_8)
                val totalLines = allLines.size

                val startLine = (startLineArg ?: 1).coerceIn(1, maxOf(1, totalLines))
                val endLine = (endLineArg ?: totalLines).coerceIn(startLine, maxOf(startLine, totalLines))

                val slicedLines = if (totalLines == 0) emptyList() else allLines.subList(startLine - 1, minOf(endLine, totalLines))
                val formattedSlice = buildString {
                    appendLine("[$requestedPath: lines $startLine to $endLine of $totalLines]")
                    slicedLines.forEachIndexed { idx, line ->
                        val currentLineNum = startLine + idx
                        if (showLineNumbers) {
                            appendLine(String.format(java.util.Locale.US, "%4d: %s", currentLineNum, line))
                        } else {
                            appendLine(line)
                        }
                    }
                }.trimEnd()

                return ToolResult.success(
                    callId = callId,
                    toolName = definition.name,
                    result = formattedSlice
                )
            }

            val bytesToRead = minOf(totalSize, maxBytes.toLong()).toInt()
            val buffer = ByteArray(bytesToRead)
            var readTotal = 0
            FileInputStream(targetFile).use { fis ->
                while (readTotal < bytesToRead) {
                    val count = fis.read(buffer, readTotal, bytesToRead - readTotal)
                    if (count == -1) break
                    readTotal += count
                }
            }

            val actualBytes = if (readTotal == bytesToRead) buffer else buffer.copyOf(readTotal)

            val resultText = when (encoding) {
                "base64" -> {
                    val b64 = Base64.getEncoder().encodeToString(actualBytes)
                    if (totalSize > maxBytes) {
                        "$b64\n\n[Warning: Base64 output truncated at $maxBytes bytes. Total file size is $totalSize bytes.]"
                    } else {
                        b64
                    }
                }
                "hex" -> {
                    val hexDump = formatHexDump(actualBytes)
                    if (totalSize > maxBytes) {
                        "$hexDump\n\n[Warning: Hex output truncated at $maxBytes bytes. Total file size is $totalSize bytes.]"
                    } else {
                        hexDump
                    }
                }
                "utf-8" -> {
                    val text = String(actualBytes, Charsets.UTF_8)
                    if (totalSize > maxBytes) {
                        "$text\n\n[Warning: Output truncated at $maxBytes bytes. Total file size is $totalSize bytes.]"
                    } else {
                        text
                    }
                }
                else -> { // "auto"
                    if (isBinary) {
                        buildString {
                            appendLine("[BINARY FILE]")
                            appendLine("Path: $requestedPath")
                            appendLine("Category: ${typeInfo.category}")
                            appendLine("MIME Type: ${typeInfo.mimeType}")
                            appendLine("Description: ${typeInfo.description}")
                            appendLine("Size: $totalSize bytes (read: $readTotal bytes)")
                            appendLine("\nHex Preview (first ${minOf(readTotal, 64)} bytes):")
                            appendLine(formatHexDump(actualBytes.take(64).toByteArray()))
                            appendLine("\n(Note: To retrieve complete raw binary content, invoke file_read with encoding: 'base64')")
                        }.trim()
                    } else {
                        val text = String(actualBytes, Charsets.UTF_8)
                        if (totalSize > maxBytes) {
                            "$text\n\n[Warning: Output truncated at $maxBytes bytes. Total file size is $totalSize bytes.]"
                        } else {
                            text
                        }
                    }
                }
            }

            ToolResult.success(
                callId = callId,
                toolName = definition.name,
                result = resultText
            )
        } catch (e: IOException) {
            ToolResult.failure(
                callId = callId,
                toolName = definition.name,
                error = "Failed to read file '$requestedPath': ${e.message}"
            )
        }
    }

    private fun formatHexDump(bytes: ByteArray): String {
        val sb = StringBuilder()
        for (i in bytes.indices step 16) {
            sb.append(String.format("%08x: ", i))
            val chunkLength = minOf(16, bytes.size - i)
            for (j in 0 until 16) {
                if (j < chunkLength) {
                    sb.append(String.format("%02x ", bytes[i + j]))
                } else {
                    sb.append("   ")
                }
                if (j == 7) sb.append(" ")
            }
            sb.append(" |")
            for (j in 0 until chunkLength) {
                val b = bytes[i + j].toInt().toChar()
                if (b in ' '..'~') {
                    sb.append(b)
                } else {
                    sb.append('.')
                }
            }
            sb.append("|\n")
        }
        return sb.toString().trimEnd()
    }
}
