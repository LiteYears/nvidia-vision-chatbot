package com.example.agent.tools

import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import org.json.JSONObject
import java.util.UUID

/**
 * Robust parser to extract structured tool calls from Agent LLM output.
 * Prevents false positives where data models, schemas, or JSON responses
 * (e.g. {"name": "Item 1"}) might otherwise be misidentified as tool calls.
 */
object ToolCallParser {

    private val EXPLICIT_TOOL_CALL_REGEX = Regex(
        """```(?:tool_call|tool-call|tool)\s*([\s\S]*?)\s*```""",
        RegexOption.IGNORE_CASE
    )

    private val XML_TOOL_CALL_REGEX = Regex(
        """<(?:tool_call|function_call|invoke)[^>]*>([\s\S]*?)</(?:tool_call|function_call|invoke)>""",
        RegexOption.IGNORE_CASE
    )

    private val BASH_BLOCK_REGEX = Regex(
        """```(?:bash|sh|shell|terminal|ubuntu|cmd)\s*([\s\S]*?)\s*```""",
        RegexOption.IGNORE_CASE
    )

    private val PYTHON_BLOCK_REGEX = Regex(
        """```(?:python3?|py)\s*([\s\S]*?)\s*```""",
        RegexOption.IGNORE_CASE
    )

    private val VALID_TOOL_IDENTIFIER_REGEX = Regex("""^[a-zA-Z_][a-zA-Z0-9_-]*$""")

    private val KNOWN_TOOL_NAMES = setOf(
        "run_command", "bash", "terminal", "sh", "shell", "exec", "cmd",
        "python_execute", "python", "py",
        "calculator",
        "web_search",
        "web_open",
        "file_list", "list_files", "dir_list",
        "file_tree",
        "file_search",
        "file_read", "read_file", "view_file",
        "file_write", "write_file", "create_file",
        "file_patch", "replace_file_content", "edit_file", "edit", "patch", "patch_file", "apply_diff", "code_edit", "diff", "modify_file", "str_replace",
        "file_delete", "delete_file", "remove_file",
        "directory_create",
        "archive_extract"
    )

    private val moshi = Moshi.Builder().build()
    private val mapType = Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java)
    private val mapAdapter = moshi.adapter<Map<String, Any?>>(mapType)

    /**
     * Attempts to parse a structured ToolCall from the provided text.
     */
    fun parse(text: String): ToolCall? {
        if (text.isBlank()) return null

        // 1. First priority: Check for explicit ```tool_call blocks
        val explicitMatches = EXPLICIT_TOOL_CALL_REGEX.findAll(text)
        for (match in explicitMatches) {
            val content = match.groupValues[1].trim()
            val jsonCandidate = extractBalancedJsonObject(content) ?: content
            val toolCall = parseJsonToToolCall(jsonCandidate, isExplicitToolCallBlock = true)
            if (toolCall != null) return toolCall
        }

        // 2. Second priority: Check for XML <tool_call> or <function_call> blocks
        val xmlMatches = XML_TOOL_CALL_REGEX.findAll(text)
        for (match in xmlMatches) {
            val content = match.groupValues[1].trim()
            val jsonCandidate = extractBalancedJsonObject(content) ?: content
            val toolCall = parseJsonToToolCall(jsonCandidate, isExplicitToolCallBlock = true)
            if (toolCall != null) return toolCall
        }

        // 3. Third priority: Support direct ```bash or ```sh execution blocks emitted by coding models
        val bashMatches = BASH_BLOCK_REGEX.findAll(text).toList()
        if (bashMatches.isNotEmpty()) {
            for (match in bashMatches) {
                val content = match.groupValues[1].trim()
                // Check if the bash block actually contains a structured JSON tool call
                val jsonInBash = extractBalancedJsonObject(content)
                if (jsonInBash != null) {
                    val toolCall = parseJsonToToolCall(jsonInBash, isExplicitToolCallBlock = true)
                    if (toolCall != null) return toolCall
                }
                // Check if the bash block contains command-style tool calls (e.g. file_list path='.')
                val cmdTool = parseCommandStyleToolCall(content)
                if (cmdTool != null) return cmdTool
            }

            val nonJsonCodes = bashMatches.map { it.groupValues[1].trim() }
                .filter { it.isNotBlank() && !it.contains("\"tool\"", ignoreCase = true) }
            val executableBlocks = nonJsonCodes.mapNotNull { extractExecutableCommandsFromBlock(it) }
            if (executableBlocks.isNotEmpty()) {
                val combined = executableBlocks.joinToString("\n")
                return ToolCall(
                    callId = UUID.randomUUID().toString(),
                    toolName = "bash",
                    arguments = mapOf("command" to combined)
                )
            }
        }

        // 4. Fourth priority: Support direct ```python or ```py code blocks
        val pythonMatches = PYTHON_BLOCK_REGEX.findAll(text).toList()
        if (pythonMatches.isNotEmpty()) {
            val nonJsonCodes = pythonMatches.map { it.groupValues[1].trim() }
                .filter { it.isNotBlank() && !it.contains("\"tool\"", ignoreCase = true) }
            if (nonJsonCodes.isNotEmpty()) {
                val combinedCode = nonJsonCodes.joinToString("\n\n")
                return ToolCall(
                    callId = UUID.randomUUID().toString(),
                    toolName = "python_execute",
                    arguments = mapOf("code" to combinedCode)
                )
            }
        }

        // 5. Fifth priority: Explicit raw JSON tool call outside of code blocks
        val rawJson = extractBalancedJsonObject(text)
        if (rawJson != null) {
            val toolCall = parseJsonToToolCall(rawJson, isExplicitToolCallBlock = false)
            if (toolCall != null) return toolCall
        }

        // 6. Sixth priority: Command-style tool call in raw text (e.g. file_list path='.' recursive=true)
        val cmdStyleCall = parseCommandStyleToolCall(text)
        if (cmdStyleCall != null) return cmdStyleCall

        return null
    }

    private fun extractBalancedJsonObject(text: String): String? {
        var startIdx = -1
        var depth = 0
        var inString = false
        var escape = false

        for (i in text.indices) {
            val c = text[i]
            if (escape) {
                escape = false
                continue
            }
            if (c == '\\') {
                if (inString) escape = true
                continue
            }
            if (c == '"') {
                inString = !inString
                continue
            }
            if (!inString) {
                if (c == '{') {
                    if (depth == 0) {
                        startIdx = i
                    }
                    depth++
                } else if (c == '}') {
                    if (depth > 0) {
                        depth--
                        if (depth == 0 && startIdx != -1) {
                            val candidate = text.substring(startIdx, i + 1)
                            if (candidate.contains("\"tool\"", ignoreCase = true) ||
                                (candidate.contains("\"name\"", ignoreCase = true) &&
                                 (candidate.contains("\"arguments\"", ignoreCase = true) || candidate.contains("\"parameters\"", ignoreCase = true)))) {
                                return candidate
                            }
                            startIdx = -1
                        }
                    }
                }
            }
        }
        return null
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseJsonToToolCall(jsonString: String, isExplicitToolCallBlock: Boolean): ToolCall? {
        val normalizedJson = normalizeJsonStringEscapes(jsonString)
        try {
            val map = mapAdapter.fromJson(normalizedJson)
            if (map != null) {
                val hasToolKey = map.containsKey("tool")
                val candidateName = ((map["tool"] as? String) ?: (map["name"] as? String))?.trim()

                if (isValidCandidate(candidateName, hasToolKey, map, isExplicitToolCallBlock)) {
                    val arguments = mutableMapOf<String, Any?>()
                    val argsObj = (map["arguments"] as? Map<String, Any?>)
                        ?: (map["parameters"] as? Map<String, Any?>)
                        ?: (map["params"] as? Map<String, Any?>)

                    if (argsObj != null) {
                        arguments.putAll(argsObj)
                    } else {
                        for ((key, value) in map) {
                            if (key != "tool" && key != "name") {
                                arguments[key] = value
                            }
                        }
                    }

                    normalizeToolArguments(candidateName!!, arguments)
                    val canonicalName = normalizeToolCallName(candidateName)

                    return ToolCall(
                        callId = UUID.randomUUID().toString(),
                        toolName = canonicalName,
                        arguments = arguments
                    )
                }
            }
        } catch (_: Exception) {
            // Fall back to org.json.JSONObject
        }

        // Fallback using org.json.JSONObject
        return try {
            val json = JSONObject(normalizedJson)
            val hasToolKey = json.has("tool")
            val candidateName = (json.optString("tool").ifBlank { json.optString("name") }).trim()

            val hasArgs = json.has("arguments") || json.has("parameters") || json.has("params")
            val isIdentifier = VALID_TOOL_IDENTIFIER_REGEX.matches(candidateName)

            val isValid = when {
                candidateName.isBlank() || !isIdentifier -> false
                isExplicitToolCallBlock -> true
                hasToolKey -> true
                hasArgs && candidateName.lowercase() in KNOWN_TOOL_NAMES -> true
                else -> false
            }

            if (!isValid) return null

            val arguments = mutableMapOf<String, Any?>()
            val argsObj = json.optJSONObject("arguments")
                ?: json.optJSONObject("parameters")
                ?: json.optJSONObject("params")

            if (argsObj != null) {
                val keys = argsObj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    arguments[key] = argsObj.get(key)
                }
            } else {
                val keys = json.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    if (key != "tool" && key != "name") {
                        arguments[key] = json.get(key)
                    }
                }
            }

            normalizeToolArguments(candidateName, arguments)
            val canonicalName = normalizeToolCallName(candidateName)

            ToolCall(
                callId = UUID.randomUUID().toString(),
                toolName = canonicalName,
                arguments = arguments
            )
        } catch (_: Exception) {
            null
        }
    }

    fun normalizeToolCallName(raw: String): String {
        return when (raw.trim().lowercase()) {
            "bash", "terminal", "sh", "shell", "exec", "cmd" -> "run_command"
            "python", "py" -> "python_execute"
            "replace_file_content", "edit_file", "edit", "patch", "patch_file", "apply_diff", "code_edit", "diff", "modify_file", "str_replace" -> "file_patch"
            "read_file", "view_file" -> "file_read"
            "write_file", "create_file" -> "file_write"
            "list_files", "dir_list" -> "file_list"
            "delete_file", "remove_file" -> "file_delete"
            else -> raw.trim()
        }
    }

    fun normalizeToolArguments(toolName: String, args: MutableMap<String, Any?>) {
        val normTool = normalizeToolCallName(toolName)
        // Command line normalization
        val cmd = (args["command"] ?: args["cmd"]) as? String
        if (cmd != null && !cmd.contains('\n') && cmd.contains("\\n")) {
            args["command"] = cmd.replace("\\n", "\n").replace("\\r", "\r")
        }

        // File path normalization across all file tools
        if (!args.containsKey("path")) {
            val pathVal = args["TargetFile"] ?: args["target_file"] ?: args["targetFile"] ?: args["filePath"] ?: args["file"] ?: args["AbsolutePath"] ?: args["absolute_path"]
            if (pathVal != null) {
                args["path"] = pathVal
            }
        }

        // Code content normalization for file_write
        if (normTool == "file_write") {
            if (!args.containsKey("content")) {
                val contentVal = args["CodeContent"] ?: args["codeContent"] ?: args["file_content"] ?: args["body"] ?: args["text"]
                if (contentVal != null) {
                    args["content"] = contentVal
                }
            }
        }

        // Patch / Diff / Replacement normalization for file_patch
        if (normTool == "file_patch") {
            if (!args.containsKey("target_content")) {
                val targetVal = args["TargetContent"] ?: args["targetContent"] ?: args["old_str"] ?: args["search"] ?: args["original_content"] ?: args["target"]
                if (targetVal != null) {
                    args["target_content"] = targetVal
                }
            }
            if (!args.containsKey("replacement_content")) {
                val replaceVal = args["ReplacementContent"] ?: args["replacementContent"] ?: args["new_str"] ?: args["replace"] ?: args["new_content"]
                if (replaceVal != null) {
                    args["replacement_content"] = replaceVal
                }
            }
            if (!args.containsKey("diff_content")) {
                val diffVal = args["diff"] ?: args["patch"] ?: args["diff_content"] ?: args["patch_content"]
                if (diffVal != null) {
                    args["diff_content"] = diffVal
                }
            }
            if (!args.containsKey("start_line")) {
                val sl = args["StartLine"] ?: args["startLine"]
                if (sl != null) {
                    args["start_line"] = sl
                }
            }
            if (!args.containsKey("end_line")) {
                val el = args["EndLine"] ?: args["endLine"]
                if (el != null) {
                    args["end_line"] = el
                }
            }
        }
    }

    private fun isValidCandidate(
        name: String?,
        hasToolKey: Boolean,
        map: Map<String, Any?>,
        isExplicitToolCallBlock: Boolean
    ): Boolean {
        if (name.isNullOrBlank()) return false
        // Must be a valid programming identifier (no spaces like "Item 1")
        if (!VALID_TOOL_IDENTIFIER_REGEX.matches(name)) return false

        // In explicit ```tool_call blocks, any valid identifier name is accepted
        if (isExplicitToolCallBlock) return true

        // If explicit "tool" key was specified (e.g. {"tool": "bash", ...}), it's an intended tool call
        if (hasToolKey) return true

        // If only "name" was present, it MUST have arguments/parameters AND match a known tool name
        val hasArgs = map.containsKey("arguments") || map.containsKey("parameters") || map.containsKey("params")
        val lower = name.lowercase()
        return hasArgs && (lower in KNOWN_TOOL_NAMES || normalizeToolCallName(lower) in KNOWN_TOOL_NAMES)
    }

    /**
     * Extracts valid shell command lines from a code block, removing leading/trailing
     * natural language sentences, explanations, or commentary while preserving comments (#),
     * heredocs (cat << EOF), and valid commands.
     * Returns null if the block is purely natural language or raw non-shell programming code.
     */
    fun extractExecutableCommandsFromBlock(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return null

        val lines = trimmed.lines()
        val cleanedLines = mutableListOf<String>()
        var inHeredoc = false
        var heredocDelim: String? = null

        val knownCommands = setOf(
            "cat", "echo", "python", "python3", "py", "pip", "pip3", "ls", "cd", "mkdir", "pwd",
            "grep", "find", "touch", "rm", "cp", "mv", "sed", "awk", "head", "tail", "wc", "sort",
            "uniq", "cut", "tr", "tar", "zip", "unzip", "apt", "apt-get", "dpkg", "pkg", "proot",
            "proot-distro", "node", "npm", "npx", "sh", "bash", "curl", "wget", "git", "pytest",
            "unittest", "which", "whoami", "uname", "chmod", "export", "source", "test", "sleep", "printf",
            "patch", "diff"
        )

        val proseLeadingWords = setOf(
            "the", "this", "here", "in", "we", "i", "note", "please", "to", "first", "next", "finally",
            "you", "as", "for", "let's", "lets", "it"
        )

        val rawCodeKeywords = setOf(
            "def", "class", "return", "import", "from", "function", "const", "let", "var", "public", "private"
        )

        for (line in lines) {
            val lineTrim = line.trim()
            if (inHeredoc) {
                cleanedLines.add(line)
                if (heredocDelim != null && lineTrim == heredocDelim) {
                    inHeredoc = false
                    heredocDelim = null
                }
                continue
            }

            if (lineTrim.isBlank()) continue

            // Check start of heredoc: cat << EOF, cat <<- 'EOF', patch << EOF
            if (lineTrim.contains("<<")) {
                val isHeredocCommand = lineTrim.startsWith("cat ") || lineTrim.startsWith("tee ") ||
                    lineTrim.contains("<<-") || lineTrim.contains("<< '") || lineTrim.contains("<< \"") ||
                    Regex("""\b(?:cat|tee|patch|sh|bash|python|python3)\b.*<<""").containsMatchIn(lineTrim)
                val delimMatch = Regex("""<<-?\s*['"]?([A-Za-z_][A-Za-z0-9_.-]*)['"]?""").find(lineTrim)
                if (isHeredocCommand && delimMatch != null) {
                    val candidateDelim = delimMatch.groupValues[1].trim('\'', '"')
                    if (candidateDelim.length >= 2 || candidateDelim == "_" || candidateDelim.all { it.isUpperCase() }) {
                        inHeredoc = true
                        heredocDelim = candidateDelim
                    }
                }
                cleanedLines.add(line)
                continue
            }

            // Shell comments are fine
            if (lineTrim.startsWith("#")) {
                cleanedLines.add(line)
                continue
            }

            val firstToken = lineTrim.split(Regex("\\s+")).firstOrNull()?.lowercase()?.trim('\'', '"', '`') ?: ""

            // Handle abstract tool invocations mistakenly emitted into bash blocks
            val abstractTools = setOf(
                "file_list", "list_files", "dir_list",
                "file_tree", "file_search", "file_read", "read_file", "view_file",
                "file_write", "write_file", "create_file",
                "file_patch", "replace_file_content", "edit_file", "edit", "patch_file", "apply_diff", "code_edit", "str_replace", "modify_file",
                "file_delete", "delete_file", "remove_file",
                "directory_create", "archive_extract",
                "web_open", "web_search", "calculator", "run_command"
            )
            if (firstToken in abstractTools && lineTrim.contains("=")) {
                if (firstToken == "run_command") {
                    val cmdMatch = Regex("""command=['"](.*?)['"](?:\s|$)""").find(lineTrim)
                    if (cmdMatch != null) {
                        cleanedLines.add(cmdMatch.groupValues[1])
                        continue
                    }
                } else if (firstToken == "directory_create") {
                    val pathMatch = Regex("""path=['"](.*?)['"]""").find(lineTrim)
                    if (pathMatch != null) {
                        cleanedLines.add("mkdir -p ${pathMatch.groupValues[1]}")
                        continue
                    }
                } else if (firstToken == "file_list" || firstToken == "file_tree" || firstToken == "list_files") {
                    val pathMatch = Regex("""path=['"](.*?)['"]""").find(lineTrim)
                    val p = pathMatch?.groupValues?.get(1) ?: "."
                    cleanedLines.add("ls -la $p")
                    continue
                } else if (firstToken == "file_read" || firstToken == "read_file" || firstToken == "view_file") {
                    val pathMatch = Regex("""path=['"](.*?)['"]""").find(lineTrim)
                    if (pathMatch != null) {
                        cleanedLines.add("cat ${pathMatch.groupValues[1]}")
                        continue
                    }
                }
                // Skip other abstract tools from raw shell execution
                continue
            }

            // Check if line looks like raw programming code (e.g. def foo(): or return bar)
            if (firstToken in rawCodeKeywords && !lineTrim.startsWith("echo ") && !lineTrim.startsWith("cat ")) {
                continue
            }

            // Check if line looks like conversational English prose
            if (firstToken in proseLeadingWords && (lineTrim.endsWith(".") || lineTrim.endsWith(":") || (!lineTrim.contains("=") && !lineTrim.contains("/") && !lineTrim.contains("-")))) {
                continue
            }

            cleanedLines.add(line)
        }

        // Must have at least one actual shell command or heredoc
        val hasActualCommand = cleanedLines.any { line ->
            val l = line.trim()
            if (l.isBlank() || l.startsWith("#")) return@any false
            val tok = l.split(Regex("\\s+")).firstOrNull()?.lowercase()?.trim('\'', '"', '`') ?: ""
            tok in knownCommands || l.contains("<<") || l.contains("=") || l.startsWith("./") || l.startsWith("/") || l.startsWith("sh ") || l.startsWith("bash ")
        }

        return if (hasActualCommand && cleanedLines.isNotEmpty()) {
            cleanedLines.joinToString("\n").trim()
        } else {
            null
        }
    }

    /**
     * Parses command-style pseudo-tool calls (e.g. file_list path='.' recursive=true).
     */
    fun parseCommandStyleToolCall(text: String): ToolCall? {
        val lines = text.lines().map { it.trim() }.filter { it.isNotBlank() && !it.startsWith("#") }
        for (line in lines) {
            val cleanLine = line.removePrefix("tool_call:").trim()
            val tokens = cleanLine.split(Regex("\\s+"), limit = 2)
            if (tokens.isEmpty()) continue
            val rawName = tokens[0].trim().lowercase()
            val canonicalName = normalizeToolCallName(rawName)
            if ((canonicalName in KNOWN_TOOL_NAMES || rawName in KNOWN_TOOL_NAMES) && tokens.size > 1 && tokens[1].contains("=")) {
                val argsMap = mutableMapOf<String, Any?>()
                val paramRegex = Regex("""([a-zA-Z_][a-zA-Z0-9_]*)=(?:'([^']*)'|"([^"]*)"|(\S+))""")
                val matches = paramRegex.findAll(tokens[1])
                for (m in matches) {
                    val k = m.groupValues[1]
                    val rawV = when {
                        m.groupValues[2].isNotEmpty() -> m.groupValues[2]
                        m.groupValues[3].isNotEmpty() -> m.groupValues[3]
                        else -> m.groupValues[4]
                    }
                    val v: Any = when (rawV.lowercase()) {
                        "true" -> true
                        "false" -> false
                        else -> rawV.toLongOrNull() ?: rawV
                    }
                    argsMap[k] = v
                }
                if (argsMap.isNotEmpty()) {
                    normalizeToolArguments(canonicalName, argsMap)
                    return ToolCall(
                        callId = UUID.randomUUID().toString(),
                        toolName = canonicalName,
                        arguments = argsMap
                    )
                }
            }
        }
        return null
    }

    /**
     * Escapes raw control characters inside JSON string literals so LLM-generated JSON
     * with unescaped newlines does not cause syntax errors.
     */
    fun normalizeJsonStringEscapes(json: String): String {
        val sb = StringBuilder(json.length + 32)
        var inString = false
        var escape = false
        for (i in json.indices) {
            val c = json[i]
            if (escape) {
                sb.append(c)
                escape = false
                continue
            }
            if (c == '\\') {
                escape = true
                sb.append(c)
                continue
            }
            if (c == '"') {
                inString = !inString
                sb.append(c)
                continue
            }
            if (inString && c == '\n') {
                sb.append("\\n")
                continue
            }
            if (inString && c == '\r') {
                sb.append("\\r")
                continue
            }
            if (inString && c == '\t') {
                sb.append("\\t")
                continue
            }
            sb.append(c)
        }
        return sb.toString()
    }

    /**
     * Checks if the text contains a structured tool call.
     */
    fun hasToolCall(text: String): Boolean = parse(text) != null

    /**
     * Removes tool call blocks and executed terminal blocks from text for clean narrative rendering,
     * while safely preserving ordinary markdown JSON examples and schemas.
     */
    fun stripToolCalls(text: String): String {
        var clean = text.replace(EXPLICIT_TOOL_CALL_REGEX) { "" }
        clean = clean.replace(XML_TOOL_CALL_REGEX) { "" }
        clean = clean.replace(BASH_BLOCK_REGEX) { match ->
            val content = match.groupValues[1].trim()
            if (extractExecutableCommandsFromBlock(content) != null) {
                ""
            } else {
                match.value
            }
        }
        clean = clean.replace(PYTHON_BLOCK_REGEX) { match ->
            val content = match.groupValues[1].trim()
            if (content.isNotBlank() && !content.contains("\"tool\"", ignoreCase = true)) {
                ""
            } else {
                match.value
            }
        }
        return clean.trim()
    }
}
