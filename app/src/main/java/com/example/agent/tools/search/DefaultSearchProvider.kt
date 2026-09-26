package com.example.agent.tools.search

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Default search provider implementation.
 * Queries reliable search endpoints with timeout and error handling.
 *
 * Implements the SearchProvider interface so it can be swapped out easily
 * for other search backends (e.g. Google Search API, Bing, Brave, SerpApi).
 */
class DefaultSearchProvider(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .build()
) : SearchProvider {

    override suspend fun search(query: String, maxResults: Int): Result<SearchResponse> =
        withContext(Dispatchers.IO) {
            val trimmedQuery = query.trim()
            if (trimmedQuery.isBlank()) {
                return@withContext Result.failure(
                    IllegalArgumentException("Search query cannot be blank.")
                )
            }

            val limit = maxResults.coerceIn(1, 5)

            try {
                // Strategy 1: DuckDuckGo Instant Answer API
                val ddgResults = queryDuckDuckGo(trimmedQuery, limit)
                if (ddgResults.isNotEmpty()) {
                    return@withContext Result.success(SearchResponse(trimmedQuery, ddgResults.take(limit)))
                }

                // Strategy 2: Wikipedia Search API fallback for topical/technical facts
                val wikiResults = queryWikipedia(trimmedQuery, limit)
                return@withContext Result.success(SearchResponse(trimmedQuery, wikiResults.take(limit)))
            } catch (e: SocketTimeoutException) {
                Result.failure(IOException("Web search request timed out for query: '$trimmedQuery'"))
            } catch (e: IOException) {
                Result.failure(IOException("Network error during web search: ${e.message ?: "Connection failed"}"))
            } catch (e: Exception) {
                Result.failure(IOException("Unexpected error during search: ${e.message ?: e.javaClass.simpleName}"))
            }
        }

    private fun queryDuckDuckGo(query: String, limit: Int): List<SearchResultItem> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val url = "https://api.duckduckgo.com/?q=$encodedQuery&format=json&no_html=1&skip_disambig=1"

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile) NvidiaVisionBot/1.0")
            .header("Accept", "application/json")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            val body = response.body?.string() ?: return emptyList()
            val json = JSONObject(body)

            val results = mutableListOf<SearchResultItem>()

            // 1. Direct Abstract
            val abstractText = json.optString("Abstract").trim()
            val abstractUrl = json.optString("AbstractURL").trim()
            val heading = json.optString("Heading").trim().ifBlank { query }

            if (abstractText.isNotBlank() && abstractUrl.isNotBlank()) {
                results.add(
                    SearchResultItem(
                        title = heading,
                        url = abstractUrl,
                        snippet = cleanSnippet(abstractText)
                    )
                )
            }

            // 2. Related Topics
            val relatedTopics = json.optJSONArray("RelatedTopics")
            if (relatedTopics != null) {
                for (i in 0 until relatedTopics.length()) {
                    if (results.size >= limit) break
                    val item = relatedTopics.optJSONObject(i) ?: continue

                    // Ignore nested sub-topics or topics without URL
                    val firstUrl = item.optString("FirstURL").trim()
                    val text = item.optString("Text").trim()

                    if (firstUrl.isNotBlank() && text.isNotBlank() && results.none { it.url == firstUrl }) {
                        // Title is typically the first clause before " - " or the query
                        val title = if (text.contains(" - ")) {
                            text.substringBefore(" - ").trim()
                        } else {
                            text.take(60).trim()
                        }
                        results.add(
                            SearchResultItem(
                                title = title,
                                url = firstUrl,
                                snippet = cleanSnippet(text)
                            )
                        )
                    }
                }
            }

            return results
        }
    }

    private fun queryWikipedia(query: String, limit: Int): List<SearchResultItem> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val url = "https://en.wikipedia.org/w/api.php?action=query&list=search&srsearch=$encodedQuery&utf8=&format=json&srlimit=$limit"

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile) NvidiaVisionBot/1.0")
            .header("Accept", "application/json")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            val body = response.body?.string() ?: return emptyList()
            val json = JSONObject(body)
            val queryObj = json.optJSONObject("query") ?: return emptyList()
            val searchArr = queryObj.optJSONArray("search") ?: return emptyList()

            val results = mutableListOf<SearchResultItem>()
            val htmlTagRegex = Regex("<[^>]*>")

            for (i in 0 until searchArr.length()) {
                if (results.size >= limit) break
                val item = searchArr.optJSONObject(i) ?: continue
                val title = item.optString("title").trim()
                val rawSnippet = item.optString("snippet")
                val cleanSnippetText = cleanSnippet(rawSnippet.replace(htmlTagRegex, "").replace("&quot;", "\""))
                val articleUrl = "https://en.wikipedia.org/wiki/" + URLEncoder.encode(title.replace(" ", "_"), "UTF-8")

                if (title.isNotBlank()) {
                    results.add(
                        SearchResultItem(
                            title = title,
                            url = articleUrl,
                            snippet = cleanSnippetText
                        )
                    )
                }
            }

            return results
        }
    }

    private fun cleanSnippet(text: String): String {
        val cleaned = text.replace(Regex("\\s+"), " ").trim()
        return if (cleaned.length > 280) {
            cleaned.take(277) + "..."
        } else {
            cleaned
        }
    }
}
