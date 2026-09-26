package com.example.agent.tools

import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import org.json.JSONObject
import java.util.UUID

/**
 * Robust parser to extract structured tool calls from Agent LLM output.
 */
object ToolCallParser {

    private val TOOL_CALL_BLOCK_REGEX = Regex(
        """```(?:tool_call|json)?\s*([\s\S]*?)\s*```""",
        RegexOption.IGNORE_CASE
    )

    private val moshi = Moshi.Builder().build()
    private val mapType = Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java)
    private val mapAdapter = moshi.adapter<Map<String, Any?>>(mapType)

    /**
     * Attempts to parse a structured ToolCall from the provided text.
     */
    fun parse(text: String): ToolCall? {
        if (text.isBlank()) return null

        val jsonString = extractJsonCandidate(text) ?: return null

        return parseJsonToToolCall(jsonString)
    }

    private fun extractJsonCandidate(text: String): String? {
        // 1. Try to find inside code blocks first
        val blockMatches = TOOL_CALL_BLOCK_REGEX.findAll(text)
        for (match in blockMatches) {
            val content = match.groupValues[1].trim()
            if (content.contains("\"tool\"", ignoreCase = true) || content.contains("\"name\"", ignoreCase = true)) {
                val json = extractBalancedJsonObject(content)
                if (json != null) return json
            }
        }

        // 2. Try outside code blocks (raw JSON in text)
        return extractBalancedJsonObject(text)
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
                            if (candidate.contains("\"tool\"", ignoreCase = true) || candidate.contains("\"name\"", ignoreCase = true)) {
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
    private fun parseJsonToToolCall(jsonString: String): ToolCall? {
        // Try Moshi first (works on any JVM without requiring Android SDK runtime)
        try {
            val map = mapAdapter.fromJson(jsonString)
            if (map != null) {
                val toolName = ((map["tool"] as? String) ?: (map["name"] as? String))?.trim()
                if (!toolName.isNullOrBlank()) {
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
                        toolName = toolName,
                        arguments = arguments
                    )
                }
            }
        } catch (_: Exception) {
            // Fall back to org.json.JSONObject if Moshi fails
        }

        // Fallback using org.json.JSONObject
        return try {
            val json = JSONObject(jsonString)
            val toolName = json.optString("tool").ifBlank {
                json.optString("name")
            }
            if (toolName.isBlank()) return null

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
                toolName = toolName.trim(),
                arguments = arguments
            )
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Checks if the text contains a structured tool call.
     */
    fun hasToolCall(text: String): Boolean = parse(text) != null

    /**
     * Removes tool call blocks from text for clean narrative rendering.
     */
    fun stripToolCalls(text: String): String {
        return text.replace(TOOL_CALL_BLOCK_REGEX) { matchResult ->
            val content = matchResult.groupValues[1]
            if (content.contains("\"tool\"", ignoreCase = true) || content.contains("\"name\"", ignoreCase = true)) {
                ""
            } else {
                matchResult.value
            }
        }.trim()
    }
}
