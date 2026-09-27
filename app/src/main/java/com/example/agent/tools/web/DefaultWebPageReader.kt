package com.example.agent.tools.web

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

/**
 * Default implementation of WebPageReader using OkHttp.
 *
 * Provides:
 * - HTTPS support
 * - Automatic redirect following
 * - Connect and read timeouts
 * - SSRF and local system resource blocking via UrlSecurityValidator
 * - Content-Type validation (HTML and text only)
 * - Safe response size limits to protect device memory
 * - Preservation of source URL and final redirected URL
 */
class DefaultWebPageReader(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(18, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .build()
) : WebPageReader {

    companion object {
        // Maximum response body length to read in memory (2 MB) to prevent OOM
        private const val MAX_BODY_BYTES = 2L * 1024 * 1024
    }

    override suspend fun open(url: String, maxChars: Int): Result<WebPageContent> =
        withContext(Dispatchers.IO) {
            // 1. Security & syntax validation
            val validation = UrlSecurityValidator.validate(url)
            if (validation.isFailure) {
                return@withContext Result.failure(
                    validation.exceptionOrNull() ?: IllegalArgumentException("Invalid URL: '$url'")
                )
            }
            val validUrl = validation.getOrThrow()

            try {
                // 2. Build HTTP GET request with standard browser headers
                val request = Request.Builder()
                    .url(validUrl)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36 NvidiaVisionBot/1.0")
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,text/plain;q=0.8,*/*;q=0.7")
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .build()

                client.newCall(request).execute().use { response ->
                    val finalUrl = response.request.url.toString()

                    // Check if final redirected URL violates security rules
                    val finalUrlValidation = UrlSecurityValidator.validate(finalUrl)
                    if (finalUrlValidation.isFailure) {
                        return@withContext Result.failure(
                            SecurityException("Redirect target violated security policy: ${finalUrlValidation.exceptionOrNull()?.message}")
                        )
                    }

                    // 3. HTTP status code check
                    if (!response.isSuccessful) {
                        val statusText = when (response.code) {
                            401 -> "Unauthorized"
                            403 -> "Forbidden / Access Denied"
                            404 -> "Not Found"
                            429 -> "Rate Limited / Too Many Requests"
                            500 -> "Internal Server Error"
                            502 -> "Bad Gateway"
                            503 -> "Service Unavailable"
                            504 -> "Gateway Timeout"
                            else -> response.message.ifBlank { "HTTP Error" }
                        }
                        return@withContext Result.failure(
                            IOException("HTTP ${response.code} $statusText while opening URL '$finalUrl'")
                        )
                    }

                    // 4. Content-Type check (reject binary files, images, executables, PDFs)
                    val rawContentType = response.header("Content-Type")?.lowercase() ?: ""
                    val isSupportedContent = rawContentType.isBlank() ||
                        rawContentType.contains("text/") ||
                        rawContentType.contains("html") ||
                        rawContentType.contains("xml") ||
                        rawContentType.contains("json") ||
                        rawContentType.contains("javascript")

                    if (!isSupportedContent) {
                        return@withContext Result.failure(
                            IOException(
                                "Unsupported content type '$rawContentType' for URL '$finalUrl'. " +
                                    "web_open only extracts readable HTML, text, and documentation pages."
                            )
                        )
                    }

                    // 5. Read body with size ceiling to prevent OOM
                    val responseBody = response.body
                    if (responseBody == null) {
                        return@withContext Result.failure(
                            IOException("Web page at '$finalUrl' returned an empty response body.")
                        )
                    }

                    val source = responseBody.source()
                    val rawHtml = source.readUtf8(minOf(responseBody.contentLength().coerceAtLeast(0), MAX_BODY_BYTES))
                        .ifEmpty {
                            // If contentLength was -1 (chunked)
                            source.readUtf8()
                        }

                    if (rawHtml.isBlank()) {
                        return@withContext Result.failure(
                            IOException("Web page at '$finalUrl' was loaded successfully (HTTP 200), but contains no readable content.")
                        )
                    }

                    // 6. Extract structured content, title, metadata, and clean text
                    val pageContent = HtmlContentExtractor.extract(
                        rawHtml = rawHtml,
                        sourceUrl = validUrl,
                        finalUrl = finalUrl,
                        maxChars = maxChars
                    )

                    if (pageContent.content.isBlank()) {
                        return@withContext Result.failure(
                            IOException("Web page at '$finalUrl' contains only empty or non-text elements (no readable text found).")
                        )
                    }

                    Result.success(pageContent)
                }
            } catch (e: SocketTimeoutException) {
                Result.failure(IOException("Web page request timed out while opening URL: '$validUrl'"))
            } catch (e: UnknownHostException) {
                Result.failure(IOException("Could not resolve host for URL '$validUrl'. Check the domain name or network connectivity."))
            } catch (e: SSLException) {
                Result.failure(IOException("SSL/TLS handshake error while connecting to '$validUrl': ${e.message}"))
            } catch (e: IOException) {
                Result.failure(IOException("Network error while opening URL '$validUrl': ${e.message ?: "Connection failed"}"))
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
}
