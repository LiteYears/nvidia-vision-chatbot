package com.example.agent.tools.web

/**
 * Structured content extracted from an opened web page.
 *
 * Preserves source and final URLs to allow the autonomous agent to cite
 * exactly which source page its findings came from.
 */
data class WebPageContent(
    val title: String,
    val sourceUrl: String,
    val finalUrl: String,
    val content: String,
    val metadata: Map<String, String> = emptyMap(),
    val isTruncated: Boolean = false,
    val totalExtractedChars: Int = 0
)

/**
 * Interface for fetching and parsing web pages.
 * Isolated behind an interface to allow hermetic testing and pluggable parsers.
 */
interface WebPageReader {
    suspend fun open(url: String, maxChars: Int = 4000): Result<WebPageContent>
}
