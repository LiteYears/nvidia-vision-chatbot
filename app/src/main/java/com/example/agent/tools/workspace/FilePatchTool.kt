package com.example.agent.tools.workspace

import com.example.agent.tools.AgentTool
import com.example.agent.tools.ToolDefinition
import com.example.agent.tools.ToolParameter
import com.example.agent.tools.ToolResult
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID

/**
 * Agent tool for patching and updating specific code sections within files.
 *
 * Supports:
 * - Direct target-content to replacement-content substitution
 * - Search / Replace blocks (<<<<<<< SEARCH ... ======= ... >>>>>>> REPLACE)
 * - Standard unified diff hunks (@@ ... @@)
 * - Whitespace and newline tolerance to prevent fragile matching failures
 */
class FilePatchTool(
    private val workspaceManager: AgentWorkspaceManager = AgentWorkspaceManager.getInstance()
) : AgentTool {

    override val definition: ToolDefinition = ToolDefinition(
        name = "file_patch",
        description = "Updates specific sections of a file using targeted search/replace blocks or diffs. " +
            "Allows modifying, fixing, and improving code sections without rewriting the entire file.",
        parameters = listOf(
            ToolParameter(
                name = "path",
                type = "string",
                description = "Relative path of the file to patch (e.g. 'src/main.py', 'package.json')",
                required = true
            ),
            ToolParameter(
                name = "target_content",
                type = "string",
                description = "The exact existing code section/lines to find and replace",
                required = false
            ),
            ToolParameter(
                name = "replacement_content",
                type = "string",
                description = "The new code section/lines to insert in place of target_content",
                required = false
            ),
            ToolParameter(
                name = "diff",
                type = "string",
                description = "Alternative: Unified diff format or '<<<<<<< SEARCH\\n...\\n=======\\n...\\n>>>>>>> REPLACE' block",
                required = false
            ),
            ToolParameter(
                name = "allow_multiple",
                type = "boolean",
                description = "Whether to replace multiple occurrences if target appears more than once (default: false)",
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

        val targetContent = arguments["target_content"]?.toString()
        val replacementContent = arguments["replacement_content"]?.toString()
        val diffBlock = arguments["diff"]?.toString()
        val allowMultiple = when (val a = arguments["allow_multiple"]) {
            is Boolean -> a
            is String -> a.equals("true", ignoreCase = true)
            else -> false
        }

        if (targetContent == null && diffBlock == null) {
            return ToolResult.failure(
                callId = callId,
                toolName = definition.name,
                error = "Must provide either ('target_content' and 'replacement_content') or 'diff' parameter."
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

        if (!targetFile.exists() || !targetFile.isFile) {
            return ToolResult.failure(
                callId = callId,
                toolName = definition.name,
                error = "File not found: '$requestedPath'"
            )
        }

        val originalContent = try {
            targetFile.readText(Charsets.UTF_8)
        } catch (e: IOException) {
            return ToolResult.failure(
                callId = callId,
                toolName = definition.name,
                error = "Failed to read '$requestedPath': ${e.message}"
            )
        }

        val patchResult: PatchApplicationResult = when {
            diffBlock != null -> applyDiffOrHunk(originalContent, diffBlock, allowMultiple)
            targetContent != null -> applyDirectReplacement(originalContent, targetContent, replacementContent ?: "", allowMultiple)
            else -> PatchApplicationResult(false, "No patch instructions provided", originalContent, 0, 0)
        }

        if (!patchResult.isSuccess) {
            return ToolResult.failure(
                callId = callId,
                toolName = definition.name,
                error = "Failed to patch '$requestedPath': ${patchResult.errorMessage}"
            )
        }

        return try {
            FileOutputStream(targetFile, false).use { fos ->
                fos.write(patchResult.newContent.toByteArray(Charsets.UTF_8))
                fos.flush()
            }

            val relPath = workspaceManager.getRelativePath(targetFile)
            val summary = buildString {
                appendLine("Successfully patched '$relPath'.")
                appendLine("Modifications: -${patchResult.linesRemoved} lines, +${patchResult.linesAdded} lines (Total size: ${targetFile.length()} bytes).")
                if (patchResult.changePreview.isNotBlank()) {
                    appendLine("\nDiff Preview:")
                    appendLine(patchResult.changePreview)
                }
            }.trim()

            ToolResult.success(
                callId = callId,
                toolName = definition.name,
                result = summary
            )
        } catch (e: IOException) {
            ToolResult.failure(
                callId = callId,
                toolName = definition.name,
                error = "Failed to write patched content to '$requestedPath': ${e.message}"
            )
        }
    }

    private fun applyDirectReplacement(
        originalContent: String,
        target: String,
        replacement: String,
        allowMultiple: Boolean
    ): PatchApplicationResult {
        if (target.isEmpty()) {
            return PatchApplicationResult(false, "target_content cannot be empty.", originalContent, 0, 0)
        }

        val normalizedOriginal = originalContent.replace("\r\n", "\n")
        val normalizedTarget = target.replace("\r\n", "\n")
        val normalizedReplacement = replacement.replace("\r\n", "\n")

        // 1. Try exact match
        var matchIndex = normalizedOriginal.indexOf(normalizedTarget)

        // 2. If exact match fails, try whitespace-trimmed line matching
        if (matchIndex < 0) {
            val fuzzyMatch = findFuzzyMatch(normalizedOriginal, normalizedTarget)
            if (fuzzyMatch != null) {
                val replaced = normalizedOriginal.substring(0, fuzzyMatch.startIndex) +
                    normalizedReplacement +
                    normalizedOriginal.substring(fuzzyMatch.endIndex)

                val linesRemoved = fuzzyMatch.matchedText.lines().size
                val linesAdded = normalizedReplacement.lines().size
                return PatchApplicationResult(
                    isSuccess = true,
                    errorMessage = null,
                    newContent = replaced,
                    linesRemoved = linesRemoved,
                    linesAdded = linesAdded,
                    changePreview = formatDiffPreview(fuzzyMatch.matchedText, normalizedReplacement)
                )
            }

            return PatchApplicationResult(
                isSuccess = false,
                errorMessage = "target_content was not found in the file. Ensure you provide exact lines with sufficient surrounding context.",
                newContent = originalContent,
                linesRemoved = 0,
                linesAdded = 0
            )
        }

        // Count occurrences
        val occurrences = countOccurrences(normalizedOriginal, normalizedTarget)
        if (occurrences > 1 && !allowMultiple) {
            return PatchApplicationResult(
                isSuccess = false,
                errorMessage = "Found $occurrences occurrences of target_content. Provide more surrounding context lines to uniquely identify the section, or set allow_multiple: true.",
                newContent = originalContent,
                linesRemoved = 0,
                linesAdded = 0
            )
        }

        val newContent = if (allowMultiple) {
            normalizedOriginal.replace(normalizedTarget, normalizedReplacement)
        } else {
            normalizedOriginal.substring(0, matchIndex) + normalizedReplacement + normalizedOriginal.substring(matchIndex + normalizedTarget.length)
        }

        val linesRemoved = normalizedTarget.lines().size * (if (allowMultiple) occurrences else 1)
        val linesAdded = normalizedReplacement.lines().size * (if (allowMultiple) occurrences else 1)

        return PatchApplicationResult(
            isSuccess = true,
            errorMessage = null,
            newContent = newContent,
            linesRemoved = linesRemoved,
            linesAdded = linesAdded,
            changePreview = formatDiffPreview(normalizedTarget, normalizedReplacement)
        )
    }

    private fun applyDiffOrHunk(
        originalContent: String,
        diffText: String,
        allowMultiple: Boolean
    ): PatchApplicationResult {
        val normalizedDiff = diffText.replace("\r\n", "\n")

        // Format A: <<<<<<< SEARCH ... ======= ... >>>>>>> REPLACE
        if (normalizedDiff.contains("<<<<<<< SEARCH") && normalizedDiff.contains("=======") && normalizedDiff.contains(">>>>>>> REPLACE")) {
            val hunks = parseSearchReplaceHunks(normalizedDiff)
            if (hunks.isEmpty()) {
                return PatchApplicationResult(false, "Could not parse any SEARCH/REPLACE hunks from diff parameter.", originalContent, 0, 0)
            }

            var currentText = originalContent
            var totalRemoved = 0
            var totalAdded = 0
            val previews = mutableListOf<String>()

            for ((searchBlock, replaceBlock) in hunks) {
                val res = applyDirectReplacement(currentText, searchBlock, replaceBlock, allowMultiple)
                if (!res.isSuccess) {
                    return res
                }
                currentText = res.newContent
                totalRemoved += res.linesRemoved
                totalAdded += res.linesAdded
                previews.add(res.changePreview)
            }

            return PatchApplicationResult(
                isSuccess = true,
                errorMessage = null,
                newContent = currentText,
                linesRemoved = totalRemoved,
                linesAdded = totalAdded,
                changePreview = previews.joinToString("\n\n")
            )
        }

        // Format B: Unified Diff format (@@ ... @@)
        val unifiedHunks = parseUnifiedDiff(normalizedDiff)
        if (unifiedHunks.isNotEmpty()) {
            var currentText = originalContent
            var totalRemoved = 0
            var totalAdded = 0
            val previews = mutableListOf<String>()

            for (hunk in unifiedHunks) {
                val res = applyDirectReplacement(currentText, hunk.targetText, hunk.replacementText, allowMultiple)
                if (!res.isSuccess) {
                    return res
                }
                currentText = res.newContent
                totalRemoved += res.linesRemoved
                totalAdded += res.linesAdded
                previews.add(res.changePreview)
            }

            return PatchApplicationResult(
                isSuccess = true,
                errorMessage = null,
                newContent = currentText,
                linesRemoved = totalRemoved,
                linesAdded = totalAdded,
                changePreview = previews.joinToString("\n\n")
            )
        }

        // If neither recognized, treat raw diffText as target_content
        return applyDirectReplacement(originalContent, diffText, "", allowMultiple)
    }

    private fun parseSearchReplaceHunks(text: String): List<Pair<String, String>> {
        val hunks = mutableListOf<Pair<String, String>>()
        val pattern = Regex("<<<<<<< SEARCH\\n([\\s\\S]*?)\\n=======\\n([\\s\\S]*?)\\n>>>>>>> REPLACE")
        for (match in pattern.findAll(text)) {
            val search = match.groupValues[1]
            val replace = match.groupValues[2]
            hunks.add(search to replace)
        }
        return hunks
    }

    private data class UnifiedHunk(val targetText: String, val replacementText: String)

    private fun parseUnifiedDiff(diff: String): List<UnifiedHunk> {
        val hunks = mutableListOf<UnifiedHunk>()
        val lines = diff.lines()
        var inHunk = false
        val targetBuilder = StringBuilder()
        val replaceBuilder = StringBuilder()

        fun flushHunk() {
            if (targetBuilder.isNotEmpty() || replaceBuilder.isNotEmpty()) {
                hunks.add(UnifiedHunk(targetBuilder.toString().trimEnd('\n'), replaceBuilder.toString().trimEnd('\n')))
                targetBuilder.clear()
                replaceBuilder.clear()
            }
        }

        for (line in lines) {
            if (line.startsWith("@@")) {
                flushHunk()
                inHunk = true
                continue
            }
            if (!inHunk) continue

            if (line.startsWith("---") || line.startsWith("+++")) continue

            when {
                line.startsWith("-") -> {
                    targetBuilder.append(line.substring(1)).append("\n")
                }
                line.startsWith("+") -> {
                    replaceBuilder.append(line.substring(1)).append("\n")
                }
                line.startsWith(" ") -> {
                    val context = line.substring(1)
                    targetBuilder.append(context).append("\n")
                    replaceBuilder.append(context).append("\n")
                }
            }
        }
        flushHunk()
        return hunks
    }

    private data class FuzzyMatch(val startIndex: Int, val endIndex: Int, val matchedText: String)

    private fun findFuzzyMatch(original: String, target: String): FuzzyMatch? {
        val origLines = original.lines()
        val targetLines = target.lines().map { it.trimEnd() }.filter { it.isNotEmpty() }
        if (targetLines.isEmpty()) return null

        for (i in 0..origLines.size - targetLines.size) {
            var match = true
            for (j in targetLines.indices) {
                if (origLines[i + j].trimEnd() != targetLines[j]) {
                    match = false
                    break
                }
            }
            if (match) {
                // Calculate character bounds in original
                val startLineIndex = i
                val endLineIndex = i + targetLines.size

                val charStart = origLines.take(startLineIndex).sumOf { it.length + 1 }
                val matchedSlice = origLines.subList(startLineIndex, endLineIndex).joinToString("\n")
                val charEnd = charStart + matchedSlice.length

                return FuzzyMatch(charStart, charEnd, matchedSlice)
            }
        }
        return null
    }

    private fun countOccurrences(src: String, sub: String): Int {
        var count = 0
        var idx = 0
        while (src.indexOf(sub, idx).also { idx = it } != -1) {
            count++
            idx += sub.length
        }
        return count
    }

    private fun formatDiffPreview(target: String, replacement: String): String {
        val sb = StringBuilder()
        for (l in target.lines().take(6)) {
            sb.appendLine("- $l")
        }
        if (target.lines().size > 6) {
            sb.appendLine("- ... (${target.lines().size - 6} more lines removed)")
        }
        for (l in replacement.lines().take(6)) {
            sb.appendLine("+ $l")
        }
        if (replacement.lines().size > 6) {
            sb.appendLine("+ ... (${replacement.lines().size - 6} more lines added)")
        }
        return sb.toString().trimEnd()
    }

    private data class PatchApplicationResult(
        val isSuccess: Boolean,
        val errorMessage: String?,
        val newContent: String,
        val linesRemoved: Int,
        val linesAdded: Int,
        val changePreview: String = ""
    )
}
