package com.example.agent.tools

import com.example.agent.tools.builtin.WebSearchTool
import com.example.agent.tools.search.SearchProvider
import com.example.agent.tools.search.SearchResponse
import com.example.agent.tools.search.SearchResultItem
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class WebSearchToolTest {

    @Test
    fun testSuccessfulSearch() = runBlocking {
        val fakeProvider = object : SearchProvider {
            override suspend fun search(query: String, maxResults: Int): Result<SearchResponse> {
                return Result.success(
                    SearchResponse(
                        query = query,
                        results = listOf(
                            SearchResultItem(
                                title = "NVIDIA Blackwell Architecture",
                                url = "https://www.nvidia.com/blackwell",
                                snippet = "The NVIDIA Blackwell platform enables organizations to build and run real-time generative AI."
                            ),
                            SearchResultItem(
                                title = "NVIDIA Jetson Orin Nano",
                                url = "https://developer.nvidia.com/jetson-orin",
                                snippet = "Up to 40 TOPS of AI performance in the smallest form factor."
                            )
                        )
                    )
                )
            }
        }

        val tool = WebSearchTool(fakeProvider)
        val result = tool.execute(mapOf("query" to "NVIDIA Blackwell"))

        assertTrue("Execution should succeed", result.isSuccess)
        assertNull("Error should be null", result.error)
        assertNotNull("Result should not be null", result.result)

        val output = result.result!!
        assertTrue(output.contains("Search results for \"NVIDIA Blackwell\""))
        assertTrue(output.contains("NVIDIA Blackwell Architecture"))
        assertTrue(output.contains("https://www.nvidia.com/blackwell"))
        assertTrue(output.contains("The NVIDIA Blackwell platform enables"))
        assertTrue(output.contains("NVIDIA Jetson Orin Nano"))
    }

    @Test
    fun testEmptyResults() = runBlocking {
        val emptyProvider = object : SearchProvider {
            override suspend fun search(query: String, maxResults: Int): Result<SearchResponse> {
                return Result.success(SearchResponse(query = query, results = emptyList()))
            }
        }

        val tool = WebSearchTool(emptyProvider)
        val result = tool.execute(mapOf("query" to "nonexistent_term_987654"))

        assertTrue("Execution should return successful ToolResult indicating empty findings", result.isSuccess)
        assertNotNull(result.result)
        assertTrue(result.result!!.contains("No search results found"))
    }

    @Test
    fun testTimeoutHandling() = runBlocking {
        val timeoutProvider = object : SearchProvider {
            override suspend fun search(query: String, maxResults: Int): Result<SearchResponse> {
                return Result.failure(IOException("Web search request timed out for query: '$query'"))
            }
        }

        val tool = WebSearchTool(timeoutProvider)
        val result = tool.execute(mapOf("query" to "timeout_query"))

        assertFalse("Execution should report failure on timeout", result.isSuccess)
        assertNull("Result should be null on failure", result.result)
        assertNotNull("Error message should be populated", result.error)
        assertTrue("Error should mention timeout", result.error!!.contains("timed out", ignoreCase = true))
    }

    @Test
    fun testNetworkErrorHandling() = runBlocking {
        val errorProvider = object : SearchProvider {
            override suspend fun search(query: String, maxResults: Int): Result<SearchResponse> {
                return Result.failure(IOException("Network error during web search: Unable to resolve host 'api.duckduckgo.com'"))
            }
        }

        val tool = WebSearchTool(errorProvider)
        val result = tool.execute(mapOf("query" to "offline_query"))

        assertFalse("Execution should report failure on network error", result.isSuccess)
        assertNotNull("Error message must describe failure", result.error)
        assertTrue(result.error!!.contains("Unable to resolve host", ignoreCase = true))
    }

    @Test
    fun testMalformedInputMissingQuery() = runBlocking {
        val tool = WebSearchTool()
        val result = tool.execute(emptyMap())

        assertFalse("Missing query must fail", result.isSuccess)
        assertNotNull(result.error)
        assertTrue(result.error!!.contains("Missing or empty required parameter 'query'"))
    }

    @Test
    fun testMalformedInputBlankQuery() = runBlocking {
        val tool = WebSearchTool()
        val result = tool.execute(mapOf("query" to "   "))

        assertFalse("Blank whitespace query must fail", result.isSuccess)
        assertNotNull(result.error)
        assertTrue(result.error!!.contains("Missing or empty required parameter 'query'"))
    }

    @Test
    fun testMaxResultsLimitAndClamping() = runBlocking {
        var requestedLimit = 0
        val trackingProvider = object : SearchProvider {
            override suspend fun search(query: String, maxResults: Int): Result<SearchResponse> {
                requestedLimit = maxResults
                return Result.success(SearchResponse(query, emptyList()))
            }
        }

        val tool = WebSearchTool(trackingProvider)

        // Upper limit clamped to 5
        tool.execute(mapOf("query" to "test", "max_results" to 25))
        assertEquals("max_results should be clamped to at most 5", 5, requestedLimit)

        // Lower limit clamped to 1
        tool.execute(mapOf("query" to "test", "max_results" to -3))
        assertEquals("max_results should be clamped to at least 1", 1, requestedLimit)

        // Default is 4
        tool.execute(mapOf("query" to "test"))
        assertEquals("default max_results should be 4", 4, requestedLimit)
    }

    @Test
    fun testToolRegistryDiscoveryOfWebSearch() {
        val registry = ToolRegistry.defaultRegistry()

        val webTool = registry.getTool("web_search")
        assertNotNull("WebSearchTool should be discoverable in defaultRegistry()", webTool)
        assertEquals("web_search", webTool?.definition?.name)

        val queryParam = webTool?.definition?.parameters?.find { it.name == "query" }
        assertNotNull("Query parameter should be defined", queryParam)
        assertTrue("Query parameter must be required", queryParam!!.required)

        val promptBlock = registry.formatToolsForPrompt()
        assertTrue("Prompt block must contain web_search", promptBlock.contains("web_search"))
        assertTrue("Prompt block must describe query parameter", promptBlock.contains("query"))
    }
}
