package com.example.agent.tools.search

/**
 * Individual structured search result item.
 */
data class SearchResultItem(
    val title: String,
    val url: String,
    val snippet: String
)

/**
 * Structured search response holding the query and list of results.
 */
data class SearchResponse(
    val query: String,
    val results: List<SearchResultItem>
)

/**
 * Pluggable search provider interface.
 * Isolates the underlying search API/provider so it can be replaced or mocked easily.
 */
interface SearchProvider {
    suspend fun search(query: String, maxResults: Int = 4): Result<SearchResponse>
}
