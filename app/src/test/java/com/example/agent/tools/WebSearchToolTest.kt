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

    @Test
    fun testDefaultSearchProviderBingPrimary() = runBlocking {
        val rssResponse = """
            <rss version="2.0">
            <channel>
                <item>
                    <title>NVIDIA Jetson AGX Orin Developer Kit</title>
                    <link>https://developer.nvidia.com/embedded/jetson-orin</link>
                    <description>Delivers 275 TOPS for autonomous AI machines.</description>
                </item>
            </channel>
            </rss>
        """.trimIndent()

        val mockClient = okhttp3.OkHttpClient.Builder()
            .addInterceptor { chain ->
                okhttp3.Response.Builder()
                    .request(chain.request())
                    .protocol(okhttp3.Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .header("Content-Type", "application/rss+xml")
                    .body(okhttp3.ResponseBody.create(okhttp3.MediaType.parse("application/rss+xml"), rssResponse))
                    .build()
            }
            .build()

        val provider = com.example.agent.tools.search.DefaultSearchProvider(mockClient)
        val response = provider.search("NVIDIA Jetson", 4).getOrThrow()

        assertEquals(1, response.results.size)
        assertEquals("NVIDIA Jetson AGX Orin Developer Kit", response.results[0].title)
        assertEquals("https://developer.nvidia.com/embedded/jetson-orin", response.results[0].url)
        assertTrue(response.results[0].snippet.contains("275 TOPS"))
    }

    @Test
    fun testDecodeBingClickUrl() {
        val provider = com.example.agent.tools.search.DefaultSearchProvider()
        // base64 for "https://www.nvidia.com/en-us/" is "aHR0cHM6Ly93d3cubnZpZGlhLmNvbS9lbi11cy8"
        val clickUrl = "https://www.bing.com/ck/a?!&&p=123&u=a1aHR0cHM6Ly93d3cubnZpZGlhLmNvbS9lbi11cy8&ntb=1"
        val decoded = provider.decodeBingClickUrl(clickUrl)
        assertEquals("https://www.nvidia.com/en-us/", decoded)

        // Non-tracking URL unchanged
        val normalUrl = "https://developer.nvidia.com/orin"
        assertEquals(normalUrl, provider.decodeBingClickUrl(normalUrl))
    }

    @Test
    fun testFallbackToDuckDuckGoWhenBingFails() = runBlocking {
        val ddgJson = """
            {
                "Heading": "NVIDIA CUDA",
                "Abstract": "Compute Unified Device Architecture is a parallel computing platform.",
                "AbstractURL": "https://en.wikipedia.org/wiki/CUDA"
            }
        """.trimIndent()

        val mockClient = okhttp3.OkHttpClient.Builder()
            .addInterceptor { chain ->
                val url = chain.request().url().toString()
                if (url.contains("bing.com")) {
                    okhttp3.Response.Builder()
                        .request(chain.request())
                        .protocol(okhttp3.Protocol.HTTP_1_1)
                        .code(503)
                        .message("Service Unavailable")
                        .body(okhttp3.ResponseBody.create(okhttp3.MediaType.parse("text/plain"), "Bing down"))
                        .build()
                } else if (url.contains("duckduckgo.com")) {
                    okhttp3.Response.Builder()
                        .request(chain.request())
                        .protocol(okhttp3.Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .header("Content-Type", "application/json")
                        .body(okhttp3.ResponseBody.create(okhttp3.MediaType.parse("application/json"), ddgJson))
                        .build()
                } else {
                    okhttp3.Response.Builder()
                        .request(chain.request())
                        .protocol(okhttp3.Protocol.HTTP_1_1)
                        .code(404)
                        .message("Not Found")
                        .body(okhttp3.ResponseBody.create(okhttp3.MediaType.parse("text/plain"), "Not Found"))
                        .build()
                }
            }
            .build()

        val provider = com.example.agent.tools.search.DefaultSearchProvider(mockClient)
        val response = provider.search("CUDA", 2).getOrThrow()

        assertTrue("Should contain DuckDuckGo fallback results", response.results.isNotEmpty())
        assertEquals("NVIDIA CUDA", response.results[0].title)
        assertTrue(response.results[0].snippet.contains("parallel computing platform"))
    }

    @Test
    fun testFallbackToWikipediaWhenBingAndDdgEmpty() = runBlocking {
        val wikiJson = """
            {
                "query": {
                    "search": [
                        {
                            "title": "NVIDIA DRIVE",
                            "snippet": "NVIDIA DRIVE is an autonomous vehicle platform."
                        }
                    ]
                }
            }
        """.trimIndent()

        val mockClient = okhttp3.OkHttpClient.Builder()
            .addInterceptor { chain ->
                val url = chain.request().url().toString()
                if (url.contains("wikipedia.org")) {
                    okhttp3.Response.Builder()
                        .request(chain.request())
                        .protocol(okhttp3.Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .header("Content-Type", "application/json")
                        .body(okhttp3.ResponseBody.create(okhttp3.MediaType.parse("application/json"), wikiJson))
                        .build()
                } else {
                    // Bing & DDG empty
                    okhttp3.Response.Builder()
                        .request(chain.request())
                        .protocol(okhttp3.Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(okhttp3.ResponseBody.create(okhttp3.MediaType.parse("text/plain"), ""))
                        .build()
                }
            }
            .build()

        val provider = com.example.agent.tools.search.DefaultSearchProvider(mockClient)
        val response = provider.search("NVIDIA DRIVE", 2).getOrThrow()

        assertEquals(1, response.results.size)
        assertEquals("NVIDIA DRIVE", response.results[0].title)
        assertTrue(response.results[0].snippet.contains("autonomous vehicle platform"))
    }

    @Test
    fun testFallbackToHackerNews() = runBlocking {
        val hnJson = """
            {
                "hits": [
                    {
                        "title": "NVIDIA releases new TensorRT-LLM with enhanced FP4 support",
                        "url": "https://developer.nvidia.com/blog/tensorrt-llm-fp4",
                        "objectID": "98765",
                        "points": 340,
                        "num_comments": 85,
                        "author": "cuda_dev"
                    }
                ]
            }
        """.trimIndent()

        val mockClient = okhttp3.OkHttpClient.Builder()
            .addInterceptor { chain ->
                val url = chain.request().url().toString()
                if (url.contains("algolia.com")) {
                    okhttp3.Response.Builder()
                        .request(chain.request())
                        .protocol(okhttp3.Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .header("Content-Type", "application/json")
                        .body(okhttp3.ResponseBody.create(okhttp3.MediaType.parse("application/json"), hnJson))
                        .build()
                } else {
                    okhttp3.Response.Builder()
                        .request(chain.request())
                        .protocol(okhttp3.Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(okhttp3.ResponseBody.create(okhttp3.MediaType.parse("text/plain"), ""))
                        .build()
                }
            }
            .build()

        val provider = com.example.agent.tools.search.DefaultSearchProvider(mockClient)
        val response = provider.search("TensorRT-LLM FP4", 2).getOrThrow()

        assertEquals(1, response.results.size)
        assertEquals("NVIDIA releases new TensorRT-LLM with enhanced FP4 support", response.results[0].title)
        assertEquals("https://developer.nvidia.com/blog/tensorrt-llm-fp4", response.results[0].url)
        assertTrue(response.results[0].snippet.contains("TensorRT-LLM"))
    }
}
