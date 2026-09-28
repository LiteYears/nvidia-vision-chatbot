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

    private val VALID_TOOL_IDENTIFIER_REGEX = Regex("""^[a-zA-Z_][a-zA-Z0-9_-]*$""")

    private val KNOWN_TOOL_NAMES = setOf(
        "run_command", "bash", "terminal", "sh", "shell", "exec", "cmd",
        "python_execute", "python", "py",
        "calculator",
        "web_search",
        "web_open",
        "file_list",
        "file_tree",
        "file_search",
        "file_read",
        "file_write",
        "file_patch",
        "file_delete",
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
            val nonJsonCodes = bashMatches.map { it.groupValues[1].trim() }
                .filter { it.isNotBlank() && !it.contains("\"tool\"", ignoreCase = true) }
            if (nonJsonCodes.isNotEmpty()) {
                val combined = nonJsonCodes.joinToString("\n")
                return ToolCall(
                    callId = UUID.randomUUID().toString(),
                    toolName = "bash",
                    arguments = mapOf("command" to combined)
                )
            }
        }

        // 4. Fourth priority: Explicit raw JSON tool call outside of code blocks
        val rawJson = extractBalancedJsonObject(text)
        if (rawJson != null) {
            val toolCall = parseJsonToToolCall(rawJson, isExplicitToolCallBlock = false)
            if (toolCall != null) return toolCall
        }

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
        try {
            val map = mapAdapter.fromJson(jsonString)
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

                    return ToolCall(
                        callId = UUID.randomUUID().toString(),
                        toolName = candidateName!!,
                        arguments = arguments
                    )
                }
            }
        } catch (_: Exception) {
            // Fall back to org.json.JSONObject
        }

        // Fallback using org.json.JSONObject
        return try {
            val json = JSONObject(jsonString)
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

            ToolCall(
                callId = UUID.randomUUID().toString(),
                toolName = candidateName,
                arguments = arguments
            )
        } catch (_: Exception) {
            null
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
        return hasArgs && name.lowercase() in KNOWN_TOOL_NAMES
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
        clean = clean.replace(BASH_BLOCK_REGEX) { "" }
        return clean.trim()
    }
}
