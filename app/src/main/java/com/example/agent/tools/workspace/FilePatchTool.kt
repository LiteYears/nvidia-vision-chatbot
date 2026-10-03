package com.example.agent.tools.workspace

import com.example.agent.tools.AgentTool
import com.example.agent.tools.ToolDefinition
import com.example.agent.tools.ToolParameter
import com.example.agent.tools.ToolResult
import com.example.agent.python.PythonLexer
import com.example.agent.python.PythonParser
import com.example.agent.python.PythonSyntaxException
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlin.math.max
import kotlin.math.min

/**
 * Advanced code patching and replacement tool for the autonomous agent.
 *
 * Supports:
 * - Direct target-content to replacement-content substitution
 * - Line-range replacement via start_line and end_line
 * - Search / Replace blocks (<<<<<<< SEARCH ... ======= ... >>>>>>> REPLACE, ORIGINAL/UPDATED, HEAD)
 * - Standard unified diff format (@@ -start,count +newStart,newCount @@)
 * - 6-stage code-aware fuzzy matching (line numbers, indentation, whitespace, similarity)
 * - Automatic relative indentation preservation for replacements
 * - Post-patch syntax verification for Python files
 */
class FilePatchTool(
    private val workspaceManager: AgentWorkspaceManager = AgentWorkspaceManager.getInstance()
) : AgentTool {

    override val definition: ToolDefinition = ToolDefinition(
        name = "file_patch",
        description = "Updates, patches, and replaces specific code sections within files using targeted search/replace blocks, line ranges, or unified diffs. " +
            "Supports line-range replacements (start_line, end_line), SEARCH/REPLACE blocks, unified diffs, and exact substitutions with automatic indentation preservation.",
        parameters = listOf(
            ToolParameter(
                name = "path",
                type = "string",
                description = "Relative path of the file to patch (e.g. 'src/main.py', 'package.json'). Also accepts 'TargetFile' or 'file'.",
                required = true
            ),
            ToolParameter(
                name = "target_content",
                type = "string",
                description = "The exact existing code section/lines to find and replace. Also accepts 'TargetContent', 'search', or 'old_str'.",
                required = false
            ),
            ToolParameter(
                name = "replacement_content",
                type = "string",
                description = "The new code section/lines to insert in place of target_content. Also accepts 'ReplacementContent', 'replace', or 'new_str'.",
                required = false
            ),
            ToolParameter(
                name = "diff",
                type = "string",
                description = "Unified diff or '<<<<<<< SEARCH\\n...\\n=======\\n...\\n>>>>>>> REPLACE' block. Also accepts 'patch' or 'hunk'.",
                required = false
            ),
            ToolParameter(
                name = "start_line",
                type = "number",
                description = "Optional 1-indexed starting line number for targeted replacement. Also accepts 'StartLine'.",
                required = false
            ),
            ToolParameter(
                name = "end_line",
                type = "number",
                description = "Optional 1-indexed ending line number for targeted replacement. Also accepts 'EndLine'.",
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

        var requestedPath = (
            arguments["path"] ?: arguments["file"] ?: arguments["file_path"] ?: arguments["filePath"] ?:
            arguments["filename"] ?: arguments["TargetFile"] ?: arguments["target_file"] ?: arguments["targetFile"] ?:
            arguments["target"]
        )?.toString()?.trim()

        val targetContent = (
            arguments["target_content"] ?: arguments["target"] ?: arguments["search"] ?: arguments["old_str"] ?:
            arguments["old_content"] ?: arguments["find"] ?: arguments["original"] ?: arguments["TargetContent"] ?:
            arguments["targetContent"] ?: arguments["oldContent"]
        )?.toString()

        val replacementContent = (
            arguments["replacement_content"] ?: arguments["replacement"] ?: arguments["replace"] ?:
            arguments["new_str"] ?: arguments["new_content"] ?: arguments["substitute"] ?:
            arguments["ReplacementContent"] ?: arguments["replacementContent"] ?: arguments["newContent"]
        )?.toString()

        val diffBlock = (
            arguments["diff"] ?: arguments["patch"] ?: arguments["hunk"] ?: arguments["diff_content"] ?:
            arguments["patch_content"] ?: arguments["diff_text"] ?: arguments["unified_diff"]
        )?.toString()

        val startLine = (
            arguments["start_line"] ?: arguments["StartLine"] ?: arguments["startLine"] ?:
            arguments["start"] ?: arguments["from_line"]
        )?.toString()?.toIntOrNull()

        val endLine = (
            arguments["end_line"] ?: arguments["EndLine"] ?: arguments["endLine"] ?:
            arguments["end"] ?: arguments["to_line"]
        )?.toString()?.toIntOrNull()

        val allowMultiple = when (val a = arguments["allow_multiple"] ?: arguments["AllowMultiple"] ?: arguments["all"]) {
            is Boolean -> a
            is String -> a.equals("true", ignoreCase = true)
            else -> false
        }

        // Auto-extract path from diff header if path was not explicitly provided
        if (requestedPath.isNullOrBlank() && diffBlock != null) {
            requestedPath = extractPathFromDiff(diffBlock)
        }

        if (requestedPath.isNullOrBlank()) {
            return ToolResult.failure(
                callId = callId,
                toolName = definition.name,
                error = "Missing required parameter 'path' (or 'TargetFile')."
            )
        }

        if (targetContent == null && diffBlock == null && (startLine == null || endLine == null)) {
            return ToolResult.failure(
                callId = callId,
                toolName = definition.name,
                error = "Must provide either ('target_content' and 'replacement_content'), 'diff', or ('start_line', 'end_line' and 'replacement_content')."
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
                }
                appendLine("\nTip: Create the file first using 'file_write' before attempting to patch it.")
            }

            return ToolResult.failure(
                callId = callId,
                toolName = definition.name,
                error = errorMsg.trim()
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
            // Line range replacement mode
            startLine != null && endLine != null && (targetContent == null || targetContent.isBlank()) -> {
                applyLineRangeReplacement(originalContent, startLine, endLine, replacementContent ?: "")
            }
            // Diff or SEARCH/REPLACE blocks
            diffBlock != null -> {
                applyDiffOrHunk(originalContent, diffBlock, allowMultiple, requestedPath)
            }
            // Direct target replacement (with optional start/end line hint)
            targetContent != null -> {
                applyDirectReplacement(
                    originalContent = originalContent,
                    target = targetContent,
                    replacement = replacementContent ?: "",
                    allowMultiple = allowMultiple,
                    requestedPath = requestedPath,
                    startLineHint = startLine,
                    endLineHint = endLine
                )
            }
            else -> PatchApplicationResult(false, "No patch instructions provided", originalContent, 0, 0)
        }

        if (!patchResult.isSuccess) {
            return ToolResult.failure(
                callId = callId,
                toolName = definition.name,
                error = "Failed to patch '$requestedPath': ${patchResult.errorMessage}"
            )
        }

        // Post-patch Python syntax verification
        var syntaxWarning: String? = null
        if (targetFile.name.endsWith(".py")) {
            try {
                val tokens = PythonLexer(patchResult.newContent, targetFile.name).tokenize()
                PythonParser(tokens, targetFile.name).parse()
            } catch (e: PythonSyntaxException) {
                syntaxWarning = "Warning: Patched Python code contains a syntax error at line ${e.line}: ${e.message}"
            } catch (_: Exception) {}
        }

        return try {
            workspaceManager.atomicWrite(targetFile, patchResult.newContent.toByteArray(Charsets.UTF_8))

            val relPath = workspaceManager.getRelativePath(targetFile)
            val summary = buildString {
                appendLine("Successfully patched '$relPath'.")
                appendLine("Modifications: -${patchResult.linesRemoved} lines, +${patchResult.linesAdded} lines (Total size: ${targetFile.length()} bytes).")
                if (syntaxWarning != null) {
                    appendLine("\n[SYNTAX ALERT]")
                    appendLine(syntaxWarning)
                }
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

    /**
     * Replaces a specific 1-indexed line range [startLine..endLine] directly.
     */
    private fun applyLineRangeReplacement(
        originalContent: String,
        startLine: Int,
        endLine: Int,
        replacement: String
    ): PatchApplicationResult {
        val origLines = originalContent.replace("\r\n", "\n").lines()
        if (origLines.isEmpty()) {
            return PatchApplicationResult(
                isSuccess = true,
                errorMessage = null,
                newContent = replacement,
                linesRemoved = 0,
                linesAdded = replacement.lines().size,
                changePreview = formatDiffPreview("", replacement)
            )
        }

        val clampedStart = startLine.coerceIn(1, origLines.size)
        val clampedEnd = endLine.coerceIn(clampedStart, origLines.size)
        val sIdx = clampedStart - 1
        val eIdx = clampedEnd

        val targetSlice = origLines.subList(sIdx, eIdx).joinToString("\n")
        val repLines = replacement.replace("\r\n", "\n").lines()
        val newLines = origLines.subList(0, sIdx) + repLines + origLines.subList(eIdx, origLines.size)
        val newContent = newLines.joinToString("\n")

        val removedCount = eIdx - sIdx
        val addedCount = repLines.size

        return PatchApplicationResult(
            isSuccess = true,
            errorMessage = null,
            newContent = newContent,
            linesRemoved = removedCount,
            linesAdded = addedCount,
            changePreview = formatDiffPreview(targetSlice, replacement)
        )
    }

    /**
     * Direct replacement with 6-stage code-aware matching and automatic indentation preservation.
     */
    private fun applyDirectReplacement(
        originalContent: String,
        target: String,
        replacement: String,
        allowMultiple: Boolean,
        requestedPath: String = "",
        startLineHint: Int? = null,
        endLineHint: Int? = null
    ): PatchApplicationResult {
        if (target.isEmpty()) {
            return PatchApplicationResult(false, "target_content cannot be empty.", originalContent, 0, 0)
        }

        val normalizedOriginal = originalContent.replace("\r\n", "\n")
        val normalizedTarget = target.replace("\r\n", "\n")
        val normalizedReplacement = replacement.replace("\r\n", "\n")

        // 1. Try exact match within line-hint window if provided
        var matchIndex = -1
        val origLines = normalizedOriginal.lines()

        if (startLineHint != null && endLineHint != null && startLineHint in 1..origLines.size) {
            val winStart = (startLineHint - 5).coerceAtLeast(1)
            val winEnd = (endLineHint + 5).coerceAtMost(origLines.size)
            val winCharStart = origLines.take(winStart - 1).sumOf { it.length + 1 }
            val winSlice = origLines.subList(winStart - 1, winEnd).joinToString("\n")
            val localIdx = winSlice.indexOf(normalizedTarget)
            if (localIdx >= 0) {
                matchIndex = winCharStart + localIdx
            }
        }

        // 2. Global exact match
        if (matchIndex < 0) {
            matchIndex = normalizedOriginal.indexOf(normalizedTarget)
        }

        // 3. Line-number prefix stripping (e.g. "12: def foo():")
        if (matchIndex < 0) {
            val targetNoLineNumbers = normalizedTarget.lines().joinToString("\n") { line ->
                line.replaceFirst(Regex("""^\s*\d+[:|]\s*"""), "")
            }
            if (targetNoLineNumbers != normalizedTarget) {
                val idx = normalizedOriginal.indexOf(targetNoLineNumbers)
                if (idx >= 0) {
                    matchIndex = idx
                }
            }
        }

        // 4. Multi-strategy Code-Aware Fuzzy Matching
        if (matchIndex < 0) {
            val fuzzyMatch = findCodeAwareMatch(normalizedOriginal, normalizedTarget, startLineHint)
            if (fuzzyMatch != null) {
                val adjustedReplacement = adjustIndentation(
                    originalMatched = fuzzyMatch.matchedText,
                    targetSpecified = normalizedTarget,
                    replacement = normalizedReplacement
                )

                val replaced = normalizedOriginal.substring(0, fuzzyMatch.startIndex) +
                    adjustedReplacement +
                    normalizedOriginal.substring(fuzzyMatch.endIndex)

                val linesRemoved = fuzzyMatch.matchedText.lines().size
                val linesAdded = adjustedReplacement.lines().size
                return PatchApplicationResult(
                    isSuccess = true,
                    errorMessage = null,
                    newContent = replaced,
                    linesRemoved = linesRemoved,
                    linesAdded = linesAdded,
                    changePreview = formatDiffPreview(fuzzyMatch.matchedText, adjustedReplacement)
                )
            }

            // Diagnostic reporting: extract top closest line matches for the agent
            val rawTargetLines = normalizedTarget.lines().map { it.replaceFirst(Regex("""^\s*\d+[:|]\s*"""), "").trim() }
            val firstTargetLine = rawTargetLines.firstOrNull { it.isNotBlank() } ?: ""
            val candidateLines = if (firstTargetLine.isNotBlank()) {
                origLines.mapIndexedNotNull { idx, line ->
                    val cleanLine = line.trim()
                    if (cleanLine.isNotBlank() && (cleanLine.contains(firstTargetLine) || firstTargetLine.contains(cleanLine))) {
                        "  • Line ${idx + 1}: ${cleanLine.take(90)}"
                    } else null
                }.take(3)
            } else emptyList()

            val errorMsg = buildString {
                appendLine("Could not find target_content in '$requestedPath' (${origLines.size} total lines).")
                if (candidateLines.isNotEmpty()) {
                    appendLine("Closest matches in file:")
                    candidateLines.forEach { appendLine(it) }
                }
                appendLine("Tip: Use 'start_line' and 'end_line' with 'replacement_content' to replace specific lines directly.")
            }.trimEnd()

            return PatchApplicationResult(
                isSuccess = false,
                errorMessage = errorMsg,
                newContent = originalContent,
                linesRemoved = 0,
                linesAdded = 0
            )
        }

        // Multiple occurrences verification
        val occurrences = countOccurrences(normalizedOriginal, normalizedTarget)
        if (occurrences > 1 && !allowMultiple && startLineHint == null) {
            return PatchApplicationResult(
                isSuccess = false,
                errorMessage = "Found $occurrences occurrences of target_content in '$requestedPath'. " +
                    "Specify 'start_line' and 'end_line' or provide more surrounding context lines to uniquely identify the section, or set allow_multiple: true.",
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

    /**
     * Applies a unified diff or SEARCH/REPLACE blocks.
     */
    private fun applyDiffOrHunk(
        originalContent: String,
        diffText: String,
        allowMultiple: Boolean,
        requestedPath: String
    ): PatchApplicationResult {
        // Strip markdown code fences if wrapped
        var normalizedDiff = diffText.replace("\r\n", "\n").trim()
        if (normalizedDiff.startsWith("```")) {
            normalizedDiff = normalizedDiff.lines().drop(1).takeWhile { !it.startsWith("```") }.joinToString("\n").trim()
        }

        // 1. Search / Replace blocks: <<<<<<< SEARCH ... ======= ... >>>>>>> REPLACE
        val hunks = parseSearchReplaceHunks(normalizedDiff)
        if (hunks.isNotEmpty()) {
            var currentText = originalContent
            var totalRemoved = 0
            var totalAdded = 0
            val previews = mutableListOf<String>()

            for ((searchBlock, replaceBlock) in hunks) {
                val res = applyDirectReplacement(currentText, searchBlock, replaceBlock, allowMultiple, requestedPath)
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

        // 2. Standard Unified Diff format (@@ ... @@)
        val unifiedHunks = parseUnifiedDiff(normalizedDiff)
        if (unifiedHunks.isNotEmpty()) {
            var currentText = originalContent
            var totalRemoved = 0
            var totalAdded = 0
            val previews = mutableListOf<String>()
            var currentOffset = 0

            for (hunk in unifiedHunks) {
                val effectiveStart = (hunk.startLine + currentOffset).coerceAtLeast(1)
                val res = applyDirectReplacement(
                    originalContent = currentText,
                    target = hunk.targetText,
                    replacement = hunk.replacementText,
                    allowMultiple = allowMultiple,
                    requestedPath = requestedPath,
                    startLineHint = effectiveStart,
                    endLineHint = effectiveStart + hunk.lineCount
                )
                if (!res.isSuccess) {
                    return res
                }
                currentText = res.newContent
                totalRemoved += res.linesRemoved
                totalAdded += res.linesAdded
                currentOffset += (res.linesAdded - res.linesRemoved)
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

        // Fallback: treat diffText as direct target content
        return applyDirectReplacement(originalContent, diffText, "", allowMultiple, requestedPath)
    }

    /**
     * Extracts relative target file path from unified diff headers.
     */
    private fun extractPathFromDiff(diff: String): String? {
        val lines = diff.lines()
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.startsWith("+++") || trimmed.startsWith("---")) {
                val p = trimmed.substring(3).trim().removePrefix("b/").removePrefix("a/").trim()
                if (p.isNotBlank() && p != "/dev/null" && !p.startsWith("---") && !p.startsWith("+++")) {
                    return p
                }
            } else if (trimmed.startsWith("diff --git")) {
                val parts = trimmed.split(Regex("\\s+"))
                if (parts.size >= 4) {
                    val p = parts[3].removePrefix("b/").removePrefix("a/").trim()
                    if (p.isNotBlank()) return p
                }
            } else if (trimmed.startsWith("###")) {
                val p = trimmed.removePrefix("###").trim()
                if (p.isNotBlank() && (p.contains("/") || p.contains("."))) return p
            }
        }
        return null
    }

    /**
     * Parses SEARCH/REPLACE blocks supporting all common variations:
     * - <<<<<<< SEARCH ... ======= ... >>>>>>> REPLACE
     * - <<<<<<< ORIGINAL ... ======= ... >>>>>>> UPDATED
     * - <<<<<<< HEAD ... ======= ... >>>>>>>
     */
    private fun parseSearchReplaceHunks(text: String): List<Pair<String, String>> {
        val hunks = mutableListOf<Pair<String, String>>()
        val pattern = Regex(
            """<{3,10}\s*(?:SEARCH|ORIGINAL|HEAD)[^\r\n]*\r?\n([\s\S]*?)\r?\n={3,10}[^\r\n]*\r?\n([\s\S]*?)\r?\n>{3,10}(?:\s*(?:REPLACE|UPDATED|[a-zA-Z0-9_.-]+))?""",
            RegexOption.IGNORE_CASE
        )
        for (match in pattern.findAll(text)) {
            val search = match.groupValues[1]
            val replace = match.groupValues[2]
            hunks.add(search to replace)
        }
        return hunks
    }

    private data class UnifiedHunk(
        val targetText: String,
        val replacementText: String,
        val startLine: Int = 1,
        val lineCount: Int = 0
    )

    /**
     * Parses unified diff hunks (@@ -start,count +newStart,newCount @@).
     */
    private fun parseUnifiedDiff(diff: String): List<UnifiedHunk> {
        val hunks = mutableListOf<UnifiedHunk>()
        val lines = diff.lines()
        var inHunk = false
        var hunkStartLine = 1
        var hunkLineCount = 0
        val targetBuilder = StringBuilder()
        val replaceBuilder = StringBuilder()

        fun flushHunk() {
            if (targetBuilder.isNotEmpty() || replaceBuilder.isNotEmpty()) {
                hunks.add(
                    UnifiedHunk(
                        targetText = targetBuilder.toString().trimEnd('\n'),
                        replacementText = replaceBuilder.toString().trimEnd('\n'),
                        startLine = hunkStartLine,
                        lineCount = hunkLineCount
                    )
                )
                targetBuilder.clear()
                replaceBuilder.clear()
            }
        }

        val headerRegex = Regex("""@@\s*-(\d+)(?:,(\d+))?\s+\+(\d+)(?:,(\d+))?\s*@@""")

        for (line in lines) {
            val headerMatch = headerRegex.find(line)
            if (headerMatch != null) {
                flushHunk()
                inHunk = true
                hunkStartLine = headerMatch.groupValues[1].toIntOrNull() ?: 1
                hunkLineCount = headerMatch.groupValues[2].toIntOrNull() ?: 1
                continue
            }
            if (!inHunk) continue

            if (line.startsWith("---") || line.startsWith("+++") || line.startsWith("\\ No newline")) continue

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
                else -> {
                    // Line without prefix: treat as context line
                    targetBuilder.append(line).append("\n")
                    replaceBuilder.append(line).append("\n")
                }
            }
        }
        flushHunk()
        return hunks
    }

    private data class FuzzyMatch(val startIndex: Int, val endIndex: Int, val matchedText: String)

    /**
     * 6-stage Code-Aware Matcher:
     * Handles line-number prefixes, trailing whitespace, indentation differences,
     * blank lines, and windowed similarity.
     */
    private fun findCodeAwareMatch(original: String, target: String, startLineHint: Int? = null): FuzzyMatch? {
        val origLines = original.lines()
        val rawTargetLines = target.lines().map { it.replaceFirst(Regex("""^\s*\d+[:|]\s*"""), "") }
        val targetLines = rawTargetLines.filter { it.isNotEmpty() }
        if (targetLines.isEmpty()) return null

        // Line search ordering: if startLineHint is given, search near it first
        val searchIndices = if (startLineHint != null && startLineHint in 1..origLines.size) {
            val center = startLineHint - 1
            val maxRadius = origLines.size
            val list = mutableListOf<Int>()
            for (r in 0..maxRadius) {
                val down = center + r
                val up = center - r
                if (down in 0..origLines.size - targetLines.size && !list.contains(down)) list.add(down)
                if (up in 0..origLines.size - targetLines.size && !list.contains(up)) list.add(up)
            }
            list
        } else {
            (0..origLines.size - targetLines.size).toList()
        }

        // Strategy A: Trailing whitespace trimmed (exact indentation)
        val tTrimEnd = targetLines.map { it.trimEnd() }
        for (i in searchIndices) {
            if (i > origLines.size - targetLines.size) continue
            var match = true
            for (j in tTrimEnd.indices) {
                if (origLines[i + j].trimEnd() != tTrimEnd[j]) {
                    match = false
                    break
                }
            }
            if (match) {
                val charStart = origLines.take(i).sumOf { it.length + 1 }
                val matchedSlice = origLines.subList(i, i + targetLines.size).joinToString("\n")
                return FuzzyMatch(charStart, charStart + matchedSlice.length, matchedSlice)
            }
        }

        // Strategy B: Indentation tolerance (leading & trailing whitespace trimmed)
        val tTrimBoth = targetLines.map { it.trim() }
        for (i in searchIndices) {
            if (i > origLines.size - targetLines.size) continue
            var match = true
            for (j in tTrimBoth.indices) {
                if (origLines[i + j].trim() != tTrimBoth[j]) {
                    match = false
                    break
                }
            }
            if (match) {
                val charStart = origLines.take(i).sumOf { it.length + 1 }
                val matchedSlice = origLines.subList(i, i + targetLines.size).joinToString("\n")
                return FuzzyMatch(charStart, charStart + matchedSlice.length, matchedSlice)
            }
        }

        // Strategy C: Sliding Window Similarity (>= 80% line similarity)
        if (targetLines.size >= 2) {
            var bestScore = 0.0
            var bestIndex = -1
            val windowSize = targetLines.size

            for (i in 0..origLines.size - windowSize) {
                var totalScore = 0.0
                for (j in 0 until windowSize) {
                    val s1 = origLines[i + j].trim()
                    val s2 = tTrimBoth[j]
                    totalScore += computeLineSimilarity(s1, s2)
                }
                val avgScore = totalScore / windowSize
                if (avgScore > bestScore) {
                    bestScore = avgScore
                    bestIndex = i
                }
            }

            if (bestScore >= 0.82 && bestIndex >= 0) {
                val charStart = origLines.take(bestIndex).sumOf { it.length + 1 }
                val matchedSlice = origLines.subList(bestIndex, bestIndex + windowSize).joinToString("\n")
                return FuzzyMatch(charStart, charStart + matchedSlice.length, matchedSlice)
            }
        }

        return null
    }

    /**
     * Preserves relative indentation of replacement lines when matched lines have different base indentation.
     */
    private fun adjustIndentation(
        originalMatched: String,
        targetSpecified: String,
        replacement: String
    ): String {
        val matchedIndent = originalMatched.lines().firstOrNull { it.isNotBlank() }?.takeWhile { it == ' ' || it == '\t' } ?: ""
        val targetIndent = targetSpecified.lines().firstOrNull { it.isNotBlank() }?.takeWhile { it == ' ' || it == '\t' } ?: ""

        if (matchedIndent == targetIndent) {
            return replacement
        }

        val repLines = replacement.lines()
        // If replacement was not indented at all, apply matchedIndent directly
        if (repLines.all { it.isBlank() || (!it.startsWith(" ") && !it.startsWith("\t")) }) {
            return repLines.joinToString("\n") { if (it.isBlank()) it else matchedIndent + it }
        }

        // Relative indentation shift
        val deltaSpaces = matchedIndent.length - targetIndent.length
        return repLines.joinToString("\n") { line ->
            if (line.isBlank()) line
            else if (deltaSpaces > 0) " ".repeat(deltaSpaces) + line
            else if (deltaSpaces < 0 && line.startsWith(" ".repeat(-deltaSpaces))) line.removePrefix(" ".repeat(-deltaSpaces))
            else line
        }
    }

    private fun computeLineSimilarity(s1: String, s2: String): Double {
        if (s1 == s2) return 1.0
        if (s1.isEmpty() || s2.isEmpty()) return 0.0
        val l1 = s1.length
        val l2 = s2.length
        val maxLen = max(l1, l2)
        val common = s1.filter { it in s2 }.length
        return common.toDouble() / maxLen.toDouble()
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
        val changePreview: String = "",
        val warning: String? = null
    )
}
