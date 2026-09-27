package com.example.agent.tools.builtin

import com.example.agent.tools.AgentTool
import com.example.agent.tools.ToolDefinition
import com.example.agent.tools.ToolParameter
import com.example.agent.tools.ToolResult
import com.example.agent.tools.web.DefaultWebPageReader
import com.example.agent.tools.web.WebPageReader
import java.util.UUID

/**
 * Built-in Web Open Tool for Agent Mode.
 *
 * Allows the autonomous agent to:
 * - Accept a URL (e.g. from web_search results or user prompts)
 * - Fetch the page securely with HTTPS, redirects, timeouts, and error handling
 * - Extract clean, readable content without scripts, styles, navigation, or ads
 * - Return structured page title, source URL, final URL, metadata, and cleaned text
 * - Protect the model context window with character limits
 * - Preserve source URL information so the agent can cite where its findings came from
 */
class WebOpenTool(
    private val reader: WebPageReader = DefaultWebPageReader()
) : AgentTool {

    override val definition: ToolDefinition = ToolDefinition(
        name = "web_open",
        description = "Opens and extracts readable text and metadata from a web page (URL). " +
            "Supports HTTPS, follows redirects, and removes scripts, styles, navigation, and ads. " +
            "Returns title, final URL, metadata, and cleaned readable text for analysis and citations.",
        parameters = listOf(
            ToolParameter(
                name = "url",
                type = "string",
                description = "The full web page URL to open (e.g. 'https://en.wikipedia.org/wiki/NVIDIA_Jetson' or a URL obtained from web_search)",
                required = true
            ),
            ToolParameter(
                name = "max_chars",
                type = "number",
                description = "Maximum character limit for extracted readable text (clamped between 500 and 12,000, default: 4000) to protect model context",
                required = false,
                default = 4000
            )
        )
    )

    override suspend fun execute(arguments: Map<String, Any?>): ToolResult {
        val callId = UUID.randomUUID().toString()

        // 1. Input Validation: Extract and validate URL parameter
        val rawUrl = arguments["url"]?.toString()
            ?: arguments["uri"]?.toString()
            ?: arguments["link"]?.toString()
            ?: arguments["target"]?.toString()

        if (rawUrl.isNullOrBlank()) {
            return ToolResult.failure(
                callId = callId,
                toolName = definition.name,
                error = "Missing or empty required parameter 'url'. Please provide a valid HTTP/HTTPS web page URL."
            )
        }

        val trimmedUrl = rawUrl.trim()

        // 2. Parse and clamp max_chars limit (default: 4000, clamped between 500 and 12000)
        val maxChars = when (val rawMax = arguments["max_chars"] ?: arguments["limit"] ?: arguments["max_length"]) {
            is Number -> rawMax.toInt()
            is String -> rawMax.toIntOrNull() ?: 4000
            else -> 4000
        }.coerceIn(500, 12000)

        // 3. Delegate to pluggable WebPageReader
        val readOutcome = reader.open(trimmedUrl, maxChars)

        return readOutcome.fold(
            onSuccess = { page ->
                val formattedResult = buildString {
                    appendLine("TITLE: ${page.title.ifBlank { "(No Title)" }}")
                    appendLine("SOURCE URL: ${page.sourceUrl}")
                    if (page.finalUrl != page.sourceUrl) {
                        appendLine("FINAL URL (after redirect): ${page.finalUrl}")
                    } else {
                        appendLine("FINAL URL: ${page.finalUrl}")
                    }

                    if (page.metadata.isNotEmpty()) {
                        appendLine("METADATA:")
                        page.metadata.forEach { (key, value) ->
                            appendLine("  ${key.replaceFirstChar { it.uppercase() }}: $value")
                        }
                    }

                    appendLine()
                    appendLine("READABLE CONTENT:")
                    appendLine(page.content)
                }.trimEnd()

                ToolResult.success(
                    callId = callId,
                    toolName = definition.name,
                    result = formattedResult
                )
            },
            onFailure = { error ->
                ToolResult.failure(
                    callId = callId,
                    toolName = definition.name,
                    error = "Failed to open web page '$trimmedUrl': ${error.message ?: "Unknown error"}"
                )
            }
        )
    }
}
