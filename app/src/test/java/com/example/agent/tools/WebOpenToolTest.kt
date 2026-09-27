package com.example.agent.tools

import com.example.agent.tools.builtin.WebOpenTool
import com.example.agent.tools.web.DefaultWebPageReader
import com.example.agent.tools.web.HtmlContentExtractor
import com.example.agent.tools.web.UrlSecurityValidator
import com.example.agent.tools.web.WebPageContent
import com.example.agent.tools.web.WebPageReader
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

class WebOpenToolTest {

    @Test
    fun testSuccessfulPageRead() = runBlocking {
        val fakeReader = object : WebPageReader {
            override suspend fun open(url: String, maxChars: Int): Result<WebPageContent> {
                return Result.success(
                    WebPageContent(
                        title = "NVIDIA Jetson AGX Orin Documentation",
                        sourceUrl = url,
                        finalUrl = url,
                        content = "# Overview\nThe NVIDIA Jetson AGX Orin delivers up to 275 TOPS of AI performance.\n\n## Key Features\n• 2048-core NVIDIA Ampere architecture GPU\n• 64-core Tensor Cores",
                        metadata = mapOf("description" to "Official documentation for Jetson AGX Orin developer kit.")
                    )
                )
            }
        }

        val tool = WebOpenTool(fakeReader)
        val result = tool.execute(mapOf("url" to "https://developer.nvidia.com/embedded/jetson-orin"))

        assertTrue("Execution should be successful", result.isSuccess)
        assertNull("Error should be null", result.error)
        assertNotNull("Result should not be null", result.result)

        val output = result.result!!
        assertTrue(output.contains("TITLE: NVIDIA Jetson AGX Orin Documentation"))
        assertTrue(output.contains("SOURCE URL: https://developer.nvidia.com/embedded/jetson-orin"))
        assertTrue(output.contains("FINAL URL: https://developer.nvidia.com/embedded/jetson-orin"))
        assertTrue(output.contains("Description: Official documentation for Jetson AGX Orin"))
        assertTrue(output.contains("275 TOPS of AI performance"))
    }

    @Test
    fun testRedirectPreservesSourceAndFinalUrl() = runBlocking {
        val source = "https://nvidia.com/orin"
        val redirected = "https://www.nvidia.com/en-us/autonomous-machines/embedded-systems/jetson-orin/"

        val fakeReader = object : WebPageReader {
            override suspend fun open(url: String, maxChars: Int): Result<WebPageContent> {
                return Result.success(
                    WebPageContent(
                        title = "Jetson Orin - NVIDIA",
                        sourceUrl = url,
                        finalUrl = redirected,
                        content = "Next-generation autonomous machines powered by NVIDIA Jetson."
                    )
                )
            }
        }

        val tool = WebOpenTool(fakeReader)
        val result = tool.execute(mapOf("url" to source))

        assertTrue(result.isSuccess)
        val output = result.result!!
        assertTrue("Output should show original source URL", output.contains("SOURCE URL: $source"))
        assertTrue("Output should show final redirected URL", output.contains("FINAL URL (after redirect): $redirected"))
    }

    @Test
    fun testInvalidUrlMissingOrBlank() = runBlocking {
        val tool = WebOpenTool()

        val emptyResult = tool.execute(emptyMap())
        assertFalse(emptyResult.isSuccess)
        assertTrue(emptyResult.error!!.contains("Missing or empty required parameter 'url'"))

        val blankResult = tool.execute(mapOf("url" to "   "))
        assertFalse(blankResult.isSuccess)
        assertTrue(blankResult.error!!.contains("Missing or empty required parameter 'url'"))
    }

    @Test
    fun testSecurityRejectsLocalFilesAndSystemResources() = runBlocking {
        val reader = DefaultWebPageReader()
        val tool = WebOpenTool(reader)

        // Block file:// access to local system files
        val fileResult = tool.execute(mapOf("url" to "file:///etc/passwd"))
        assertFalse("Local file:// URLs must be blocked", fileResult.isSuccess)
        assertTrue(fileResult.error!!.contains("Access denied", ignoreCase = true) || fileResult.error!!.contains("not permitted", ignoreCase = true))

        // Block content:// provider access
        val contentResult = tool.execute(mapOf("url" to "content://media/external/images/media"))
        assertFalse("Android content:// URLs must be blocked", contentResult.isSuccess)

        // Block localhost and loopback
        val localhostResult = tool.execute(mapOf("url" to "http://localhost:8080/secret"))
        assertFalse("localhost URLs must be blocked", localhostResult.isSuccess)

        val loopbackResult = tool.execute(mapOf("url" to "http://127.0.0.1:5000/api"))
        assertFalse("127.0.0.1 loopback URLs must be blocked", loopbackResult.isSuccess)

        // Block private internal IP ranges
        val privateIpResult = tool.execute(mapOf("url" to "http://192.168.1.1/admin"))
        assertFalse("192.168.x.x private IP must be blocked", privateIpResult.isSuccess)

        val privateIpA = tool.execute(mapOf("url" to "http://10.0.0.1/status"))
        assertFalse("10.x.x.x private IP must be blocked", privateIpA.isSuccess)

        val emulatorHost = tool.execute(mapOf("url" to "http://10.0.2.2:8000/"))
        assertFalse("10.0.2.2 emulator host must be blocked", emulatorHost.isSuccess)
    }

    @Test
    fun testTimeoutHandling() = runBlocking {
        val timeoutReader = object : WebPageReader {
            override suspend fun open(url: String, maxChars: Int): Result<WebPageContent> {
                return Result.failure(IOException("Web page request timed out while opening URL: '$url'"))
            }
        }

        val tool = WebOpenTool(timeoutReader)
        val result = tool.execute(mapOf("url" to "https://slow-website.example.com"))

        assertFalse("Timed out call must be marked as failure", result.isSuccess)
        assertNotNull("Error message must describe the timeout", result.error)
        assertTrue(result.error!!.contains("timed out", ignoreCase = true))
    }

    @Test
    fun testEmptyPageContentHandling() = runBlocking {
        val emptyReader = object : WebPageReader {
            override suspend fun open(url: String, maxChars: Int): Result<WebPageContent> {
                return Result.failure(
                    IOException("Web page at '$url' was loaded successfully (HTTP 200), but contains no readable text content.")
                )
            }
        }

        val tool = WebOpenTool(emptyReader)
        val result = tool.execute(mapOf("url" to "https://blank-page.example.com"))

        assertFalse(result.isSuccess)
        assertTrue(result.error!!.contains("contains no readable text content"))
    }

    @Test
    fun testOversizedPageTruncation() = runBlocking {
        val longText = buildString {
            for (i in 1..500) {
                append("Sentence number $i explaining detailed CUDA programming mechanics. ")
            }
        }

        val fakeReader = object : WebPageReader {
            override suspend fun open(url: String, maxChars: Int): Result<WebPageContent> {
                val effectiveLimit = maxChars.coerceIn(500, 12000)
                val isTruncated = longText.length > effectiveLimit
                val content = if (isTruncated) {
                    longText.take(effectiveLimit) + "\n\n[Content truncated at $effectiveLimit characters to preserve model context. (Total readable text: ${longText.length} characters)]"
                } else longText

                return Result.success(
                    WebPageContent(
                        title = "CUDA Guide",
                        sourceUrl = url,
                        finalUrl = url,
                        content = content,
                        isTruncated = isTruncated,
                        totalExtractedChars = longText.length
                    )
                )
            }
        }

        val tool = WebOpenTool(fakeReader)
        val result = tool.execute(mapOf("url" to "https://docs.nvidia.com/cuda", "max_chars" to 800))

        assertTrue(result.isSuccess)
        val output = result.result!!
        assertTrue("Output should indicate truncation notice", output.contains("Content truncated at"))
    }

    @Test
    fun testHtmlCleanerStripsScriptsStylesAndExtractsMetadata() {
        val rawHtml = """
            <!DOCTYPE html>
            <html>
            <head>
                <title>NVIDIA Isaac Sim &amp; Robotics</title>
                <meta name="description" content="Simulate, validate, and optimize robotic applications.">
                <meta name="author" content="NVIDIA Robotics Team">
                <style>
                    body { font-family: sans-serif; }
                    .ad-banner { display: block; }
                </style>
                <script>
                    console.log("analytics tracking code");
                </script>
            </head>
            <body>
                <header>
                    <nav><a href="/home">Home</a> | <a href="/login">Login</a></nav>
                </header>
                <div class="cookie-banner">Please accept all tracking cookies.</div>
                <main>
                    <h1>Autonomous Robotics with Isaac</h1>
                    <p>NVIDIA Isaac Sim is an extensible robotics simulation application.</p>
                    <h2>Key Capabilities</h2>
                    <ul>
                        <li>Photorealistic physics simulation</li>
                        <li>ROS 2 integration</li>
                    </ul>
                </main>
                <footer>
                    <p>Copyright 2026 NVIDIA Corporation. All rights reserved.</p>
                </footer>
            </body>
            </html>
        """.trimIndent()

        val extracted = HtmlContentExtractor.extract(
            rawHtml = rawHtml,
            sourceUrl = "https://developer.nvidia.com/isaac-sim",
            finalUrl = "https://developer.nvidia.com/isaac-sim",
            maxChars = 4000
        )

        assertEquals("NVIDIA Isaac Sim & Robotics", extracted.title)
        assertEquals("Simulate, validate, and optimize robotic applications.", extracted.metadata["description"])
        assertEquals("NVIDIA Robotics Team", extracted.metadata["author"])

        // Ensure scripts, styles, cookie banner, and nav are removed
        assertFalse("Scripts must be stripped", extracted.content.contains("analytics tracking code"))
        assertFalse("Styles must be stripped", extracted.content.contains("font-family"))
        assertFalse("Cookie banners must be stripped", extracted.content.contains("Please accept all tracking cookies"))
        assertFalse("Nav links must be stripped", extracted.content.contains("Login"))

        // Ensure main content and structure were preserved
        assertTrue(extracted.content.contains("Autonomous Robotics with Isaac"))
        assertTrue(extracted.content.contains("Photorealistic physics simulation"))
        assertTrue(extracted.content.contains("ROS 2 integration"))
    }

    @Test
    fun testDefaultWebPageReaderWithMockClient() = runBlocking {
        // Test HTTP redirect and 200 response with custom OkHttpClient interceptor
        val mockClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val req = chain.request()
                val responseBody = """
                    <html>
                    <head><title>Test Doc Page</title></head>
                    <body>
                        <article>
                            <h1>Documentation Guide</h1>
                            <p>Here is verified technical documentation content.</p>
                        </article>
                    </body>
                    </html>
                """.trimIndent()

                Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .header("Content-Type", "text/html; charset=utf-8")
                    .body(responseBody.toResponseBody("text/html".toMediaType()))
                    .build()
            }
            .build()

        val reader = DefaultWebPageReader(mockClient)
        val outcome = reader.open("https://example.com/docs")

        assertTrue("Mock client read should succeed", outcome.isSuccess)
        val content = outcome.getOrThrow()
        assertEquals("Test Doc Page", content.title)
        assertTrue(content.content.contains("Documentation Guide"))
    }

    @Test
    fun testDefaultWebPageReaderHttpError404() = runBlocking {
        val mockClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(404)
                    .message("Not Found")
                    .header("Content-Type", "text/html")
                    .body("<html><body>404 Not Found</body></html>".toResponseBody("text/html".toMediaType()))
                    .build()
            }
            .build()

        val reader = DefaultWebPageReader(mockClient)
        val outcome = reader.open("https://example.com/nonexistent")

        assertFalse("404 HTTP response should result in failure", outcome.isSuccess)
        assertTrue(outcome.exceptionOrNull()!!.message!!.contains("404"))
    }

    @Test
    fun testDefaultWebPageReaderUnsupportedBinaryContentType() = runBlocking {
        val mockClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .header("Content-Type", "application/octet-stream")
                    .body("binary-bytes-data".toResponseBody("application/octet-stream".toMediaType()))
                    .build()
            }
            .build()

        val reader = DefaultWebPageReader(mockClient)
        val outcome = reader.open("https://example.com/firmware.bin")

        assertFalse("Binary content must be rejected", outcome.isSuccess)
        assertTrue(outcome.exceptionOrNull()!!.message!!.contains("Unsupported content type"))
    }

    @Test
    fun testToolRegistryIntegration() {
        val registry = com.example.agent.tools.ToolRegistry.defaultRegistry()
        val webOpen = registry.getTool("web_open")

        assertNotNull("web_open must be registered in default registry", webOpen)
        assertEquals("web_open", webOpen!!.definition.name)

        val prompt = registry.formatToolsForPrompt()
        assertTrue("Prompt should document web_open", prompt.contains("web_open"))
        assertTrue("Prompt should include autonomous web browsing flow", prompt.contains("AUTONOMOUS WEB BROWSING & NEWS EXTRACTION FLOW"))
    }

    @Test
    fun testUrlSecurityValidatorNormalizesSchemelessUrls() {
        val result1 = UrlSecurityValidator.validate("bbc.com")
        assertTrue("bbc.com should be valid: ${result1.exceptionOrNull()?.message}", result1.isSuccess)
        assertEquals("https://bbc.com", result1.getOrThrow())

        val result2 = UrlSecurityValidator.validate("news.ycombinator.com/news")
        assertTrue(result2.isSuccess)
        assertEquals("https://news.ycombinator.com/news", result2.getOrThrow())

        val result3 = UrlSecurityValidator.validate("https://example.com")
        assertTrue(result3.isSuccess)
        assertEquals("https://example.com", result3.getOrThrow())

        val result4 = UrlSecurityValidator.validate("http://example.org/test")
        assertTrue(result4.isSuccess)
        assertEquals("http://example.org/test", result4.getOrThrow())
    }
}
