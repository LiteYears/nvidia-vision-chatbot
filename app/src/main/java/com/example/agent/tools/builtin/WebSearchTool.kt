package com.example.agent.tools.builtin

import com.example.agent.tools.AgentTool
import com.example.agent.tools.ToolDefinition
import com.example.agent.tools.ToolParameter
import com.example.agent.tools.ToolResult
import com.example.agent.tools.search.DefaultSearchProvider
import com.example.agent.tools.search.SearchProvider
import java.util.UUID

/**
 * Built-in Web Search Tool for Agent Mode.
 *
 * Allows the agent to query the web for up-to-date facts, specifications, and news.
 * Returns structured search results containing title, URL, and relevant snippet.
 * Keeps the provider isolated behind SearchProvider for easy replacement.
 */
class WebSearchTool(
    private val provider: SearchProvider = DefaultSearchProvider()
) : AgentTool {

    override val definition: ToolDefinition = ToolDefinition(
        name = "web_search",
        description = "Searches the web for up-to-date facts, technical specifications, documentation, and news. " +
            "Returns structured search results with title, URL, and snippet.",
        parameters = listOf(
            ToolParameter(
                name = "query",
                type = "string",
                description = "The search query string to look up on the web (e.g. 'NVIDIA Jetson Orin Nano specs', 'CUDA 12.8 release notes')",
                required = true
            ),
            ToolParameter(
                name = "max_results",
                type = "number",
                description = "Maximum number of search results to return (default: 4, clamped between 1 and 5)",
                required = false,
                default = 4
            )
        )
    )

    override suspend fun execute(arguments: Map<String, Any?>): ToolResult {
        val callId = UUID.randomUUID().toString()

        // 1. Input Validation: Extract and validate query parameter
        val rawQuery = arguments["query"]?.toString()
            ?: arguments["q"]?.toString()
            ?: arguments["search"]?.toString()

        if (rawQuery.isNullOrBlank()) {
            return ToolResult.failure(
                callId = callId,
                toolName = definition.name,
                error = "Missing or empty required parameter 'query'. Please provide a valid search query string."
            )
        }

        val trimmedQuery = rawQuery.trim()

        // 2. Parse and clamp max_results (default: 4, max limit: 5 to prevent excessive context usage)
        val maxResults = when (val rawMax = arguments["max_results"] ?: arguments["limit"]) {
            is Number -> rawMax.toInt()
            is String -> rawMax.toIntOrNull() ?: 4
            else -> 4
        }.coerceIn(1, 5)

        // 3. Delegate to pluggable SearchProvider
        val searchOutcome = provider.search(trimmedQuery, maxResults)

        return searchOutcome.fold(
            onSuccess = { response ->
                val results = response.results
                if (results.isEmpty()) {
                    ToolResult.success(
                        callId = callId,
                        toolName = definition.name,
                        result = "No search results found on the web for query: \"$trimmedQuery\". Try refining the search terms."
                    )
                } else {
                    val formatted = buildString {
                        appendLine("Search results for \"$trimmedQuery\" (${results.size} found):")
                        results.forEachIndexed { index, item ->
                            appendLine()
                            appendLine("${index + 1}. ${item.title}")
                            appendLine("   URL: ${item.url}")
                            appendLine("   Snippet: ${item.snippet}")
                        }
                    }.trimEnd()

                    ToolResult.success(
                        callId = callId,
                        toolName = definition.name,
                        result = formatted
                    )
                }
            },
            onFailure = { error ->
                ToolResult.failure(
                    callId = callId,
                    toolName = definition.name,
                    error = "Web search failed for query '$trimmedQuery': ${error.message ?: "Unknown error"}"
                )
            }
        )
    }
}
