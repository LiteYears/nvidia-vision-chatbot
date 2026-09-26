package com.example.agent.tools

import org.json.JSONObject
import java.util.UUID

/**
 * Robust parser to extract structured tool calls from Agent LLM output.
 */
object ToolCallParser {

    private val TOOL_CALL_BLOCK_REGEX = Regex(
        """```(?:tool_call|json)?\s*(\{[\s\S]*?"tool"[\s\S]*?\})\s*```""",
        RegexOption.IGNORE_CASE
    )

    private val RAW_JSON_REGEX = Regex(
        """\{[\s\r\n]*"tool"[\s\S]*?"arguments"[\s\S]*?\}""",
        RegexOption.IGNORE_CASE
    )

    /**
     * Attempts to parse a structured ToolCall from the provided text.
     */
    fun parse(text: String): ToolCall? {
        if (text.isBlank()) return null

        // 1. Check for fenced code block (```tool_call or ```json)
        val blockMatch = TOOL_CALL_BLOCK_REGEX.find(text)
        val jsonString = if (blockMatch != null) {
            blockMatch.groupValues[1]
        } else {
            // 2. Fallback to raw JSON object
            val rawMatch = RAW_JSON_REGEX.find(text)
            rawMatch?.value
        } ?: return null

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
                // If arguments were specified directly on root
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
        return text.replace(TOOL_CALL_BLOCK_REGEX, "").trim()
    }
}
