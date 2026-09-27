package com.example.agent.tools.search

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.URLEncoder
import java.net.UnknownHostException
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

/**
 * Multi-Engine Web Search Provider with cascading fallbacks.
 *
 * Execution Hierarchy:
 * 1. PRIMARY: Bing Search (Free RSS & HTML endpoints, returning real web pages with decoded URLs)
 * 2. FALLBACK 1: DuckDuckGo Instant Answer API (topic abstracts and related entities)
 * 3. FALLBACK 2: Wikipedia Search API (technical specifications, documentation, encyclopedic facts)
 * 4. FALLBACK 3: HackerNews / Tech Web Search API (developer articles, hardware benchmarks, guides)
 *
 * Aggregation & Resilience:
 * - Collects and de-duplicates results across providers until the requested limit is satisfied.
 * - If Bing is rate-limited or fails, seamlessly cascades to DuckDuckGo, Wikipedia, and Tech Web.
 * - Always provides the agent with structured, rich titles, snippets, and valid HTTP(S) source URLs.
 */
class DefaultSearchProvider(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .build()
) : SearchProvider {

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36 NvidiaVisionBot/1.0"

        private val HTML_TAG_REGEX = Pattern.compile("<[^>]*>")
        private val BING_ITEM_REGEX = Pattern.compile("<item\\b[^>]*>(.*?)</item>", Pattern.DOTALL or Pattern.CASE_INSENSITIVE)
        private val TITLE_TAG_REGEX = Pattern.compile("<title\\b[^>]*>(.*?)</title>", Pattern.DOTALL or Pattern.CASE_INSENSITIVE)
        private val LINK_TAG_REGEX = Pattern.compile("<link\\b[^>]*>(.*?)</link>", Pattern.DOTALL or Pattern.CASE_INSENSITIVE)
        private val DESC_TAG_REGEX = Pattern.compile("<description\\b[^>]*>(.*?)</description>", Pattern.DOTALL or Pattern.CASE_INSENSITIVE)
        private val BING_ALGO_REGEX = Pattern.compile("<li class=\"b_algo\"[^>]*>(.*?)</li>", Pattern.DOTALL)
        private val BING_H2_LINK_REGEX = Pattern.compile("<h2[^>]*><a[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a></h2>", Pattern.DOTALL)
        private val BING_P_REGEX = Pattern.compile("<p[^>]*>(.*?)</p>", Pattern.DOTALL)
        private val IPV4_REGEX = Pattern.compile("\\b(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\b")
    }

    fun extractIpAddress(query: String): String? {
        val m = IPV4_REGEX.matcher(query)
        return if (m.find()) m.group(0) else null
    }

    fun isIpOrNetworkQuery(query: String): Boolean {
        if (extractIpAddress(query) != null) return true
        val q = query.lowercase()
        return q.contains("whois") || q.contains("rdap") || q.contains("ip address") ||
            q.contains("origin of ip") || q.contains("owner of ip") || q.contains("location of ip") ||
            q.contains("asn lookup") || q.contains("netrange") || q.contains("cidr")
    }

    fun isWikipediaAppropriate(query: String): Boolean {
        if (isIpOrNetworkQuery(query)) return false
        val q = query.lowercase()
        if (q.contains("cve-") || q.contains("vulnerability") || q.contains("exploit") || q.contains("advisory")) return false
        if (q.contains("version") || q.contains("changelog") || q.contains("release notes") || q.contains("download")) return false
        if (q.contains("github.com") || q.contains("npm") || q.contains("pypi") || q.contains("crates.io")) return false
        if (q.contains("pricing") || q.contains("weather") || q.contains("stock price") || q.contains("exchange rate")) return false
        return true
    }

    fun isTechOrDevQuery(query: String): Boolean {
        val q = query.lowercase()
        return q.contains("code") || q.contains("python") || q.contains("cuda") || q.contains("gpu") ||
            q.contains("nvidia") || q.contains("library") || q.contains("api") || q.contains("framework") ||
            q.contains("architecture") || q.contains("risc-v") || q.contains("arm") || q.contains("benchmark") ||
            q.contains("compiler") || q.contains("linux") || q.contains("android") || q.contains("specs") ||
            q.contains("github") || q.contains("package") || q.contains("onnx") || q.contains("tensorrt")
    }

    override suspend fun search(query: String, maxResults: Int): Result<SearchResponse> =
        withContext(Dispatchers.IO) {
            val trimmedQuery = query.trim()
            if (trimmedQuery.isBlank()) {
                return@withContext Result.failure(
                    IllegalArgumentException("Search query cannot be blank.")
                )
            }

            val limit = maxResults.coerceIn(1, 5)
            val collected = mutableListOf<SearchResultItem>()
            val seenUrls = mutableSetOf<String>()
            var wikiCount = 0
            var lastNetworkException: Exception? = null

            fun addDistinctResults(items: List<SearchResultItem>) {
                for (item in items) {
                    val host = try { java.net.URI(item.url).host?.lowercase() ?: "" } catch (_: Exception) { "" }
                    val isWiki = host.contains("wikipedia.org")
                    if (isWiki && (!isWikipediaAppropriate(trimmedQuery) || wikiCount >= 1)) {
                        continue
                    }

                    val normalizedUrl = item.url.trim().lowercase().removeSuffix("/")
                    if (normalizedUrl.isNotBlank() && seenUrls.add(normalizedUrl) && item.title.isNotBlank()) {
                        if (isWiki) wikiCount++
                        collected.add(item)
                        if (collected.size >= limit) break
                    }
                }
            }

            // ==========================================
            // 1. DOMAIN SPECIFIC: IP & WHOIS Registries
            // ==========================================
            val ipMatch = extractIpAddress(trimmedQuery)
            if (ipMatch != null || isIpOrNetworkQuery(trimmedQuery)) {
                try {
                    val targetIp = ipMatch ?: "8.8.8.8"
                    val ipResults = queryIpRegistries(targetIp, limit)
                    addDistinctResults(ipResults)
                } catch (e: Exception) {
                    if (lastNetworkException == null) lastNetworkException = e
                }
            }

            // ==========================================
            // 2. PRIMARY: Multi-Source Web Search (Bing RSS/Web)
            // ==========================================
            if (collected.size < limit) {
                try {
                    val bingResults = queryBing(trimmedQuery, limit - collected.size)
                    addDistinctResults(bingResults)
                } catch (e: SocketTimeoutException) {
                    lastNetworkException = e
                } catch (e: UnknownHostException) {
                    lastNetworkException = e
                } catch (e: Exception) {
                    lastNetworkException = e
                }
            }

            // ==========================================
            // 3. SPECIALIZED: Tech / Developer Search (HackerNews / Algolia)
            // ==========================================
            if (collected.size < limit && isTechOrDevQuery(trimmedQuery)) {
                try {
                    val hnResults = queryHackerNews(trimmedQuery, limit - collected.size)
                    addDistinctResults(hnResults)
                } catch (e: Exception) {
                    if (lastNetworkException == null) lastNetworkException = e
                }
            }

            // ==========================================
            // 4. FALLBACK 1: DuckDuckGo Instant Answer
            // ==========================================
            if (collected.size < limit) {
                try {
                    val ddgResults = queryDuckDuckGo(trimmedQuery, limit - collected.size)
                    addDistinctResults(ddgResults)
                } catch (e: Exception) {
                    if (lastNetworkException == null) lastNetworkException = e
                }
            }

            // ==========================================
            // 5. FALLBACK 2: Wikipedia (ONLY if appropriate, max 1 result)
            // ==========================================
            if (collected.size < limit && isWikipediaAppropriate(trimmedQuery) && wikiCount == 0) {
                try {
                    val wikiResults = queryWikipedia(trimmedQuery, 1)
                    addDistinctResults(wikiResults)
                } catch (e: Exception) {
                    if (lastNetworkException == null) lastNetworkException = e
                }
            }

            // ==========================================
            // 6. FALLBACK 3: HackerNews Tech Web Search for any remaining
            // ==========================================
            if (collected.size < limit) {
                try {
                    val hnResults = queryHackerNews(trimmedQuery, limit - collected.size)
                    addDistinctResults(hnResults)
                } catch (e: Exception) {
                    if (lastNetworkException == null) lastNetworkException = e
                }
            }

            // If any valid results were gathered across primary and fallbacks, succeed!
            if (collected.isNotEmpty()) {
                return@withContext Result.success(SearchResponse(trimmedQuery, collected.take(limit)))
            }

            // If network timed out or disconnected entirely
            if (lastNetworkException is SocketTimeoutException) {
                return@withContext Result.failure(
                    IOException("Web search request timed out for query: '$trimmedQuery'")
                )
            } else if (lastNetworkException is UnknownHostException) {
                return@withContext Result.failure(
                    IOException("Network error during web search: Unable to resolve search host (${lastNetworkException.message})")
                )
            }

            // If all queries executed without network errors but no content was matched
            Result.success(SearchResponse(trimmedQuery, emptyList()))
        }

    /**
     * Domain Specific: Queries authoritative IP, ASN, and WHOIS / RDAP registries.
     * Excludes Wikipedia and general search noise for IP address and network queries.
     */
    fun queryIpRegistries(ip: String, limit: Int): List<SearchResultItem> {
        val results = mutableListOf<SearchResultItem>()

        // 1. IP Geolocation, ISP, and ASN registry via IP-API
        try {
            val url = "http://ip-api.com/json/$ip"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string()
                    if (!body.isNullOrBlank()) {
                        val json = JSONObject(body)
                        if (json.optString("status") == "success") {
                            val country = json.optString("country")
                            val region = json.optString("regionName")
                            val city = json.optString("city")
                            val isp = json.optString("isp")
                            val org = json.optString("org")
                            val asn = json.optString("as")
                            val lat = json.optDouble("lat", 0.0)
                            val lon = json.optDouble("lon", 0.0)

                            val snippet = "IP: $ip | ISP: $isp | Org: $org | ASN: $asn | Location: $city, $region, $country ($lat, $lon). Authoritative GeoIP & Autonomous System registry record."
                            results.add(
                                SearchResultItem(
                                    title = "IP Geolocation & ASN Registry: $ip ($isp)",
                                    url = "https://ipinfo.io/$ip",
                                    snippet = cleanSnippet(snippet)
                                )
                            )
                        }
                    }
                }
            }
        } catch (_: Exception) {}

        // 2. ARIN / RIR RDAP allocation registry
        try {
            val url = "https://rdap.arin.net/registry/ip/$ip"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/rdap+json,application/json")
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string()
                    if (!body.isNullOrBlank()) {
                        val json = JSONObject(body)
                        val handle = json.optString("handle")
                        val name = json.optString("name")
                        val start = json.optString("startAddress")
                        val end = json.optString("endAddress")
                        val cidrs = json.optJSONArray("cidr0_cidrs")
                        val cidrPrefix = if (cidrs != null && cidrs.length() > 0) {
                            val c = cidrs.getJSONObject(0)
                            "${c.optString("v4prefix")}/${c.optInt("length")}"
                        } else if (start.isNotBlank() && end.isNotBlank()) {
                            "$start - $end"
                        } else ""

                        val snippet = "Authoritative RIR Registry allocation for $ip. NetRange: $start - $end (CIDR: $cidrPrefix), Handle: $handle, Cust/Org Name: $name. Network delegation record."
                        results.add(
                            SearchResultItem(
                                title = "ARIN / RIR RDAP IP Registry: $handle ($name)",
                                url = "https://rdap.arin.net/registry/ip/$ip",
                                snippet = cleanSnippet(snippet)
                            )
                        )
                    }
                }
            }
        } catch (_: Exception) {}

        // 3. Fallback ARIN WHOIS REST endpoint if RDAP was empty
        if (results.none { it.url.contains("arin.net") }) {
            results.add(
                SearchResultItem(
                    title = "ARIN WHOIS IP Registry: $ip",
                    url = "https://whois.arin.net/rest/ip/$ip",
                    snippet = "Official American Registry for Internet Numbers (ARIN) WHOIS registry record for IP network block containing $ip."
                )
            )
        }

        // 4. Hurricane Electric BGP Routing & Peering Toolkit
        results.add(
            SearchResultItem(
                title = "Hurricane Electric BGP Routing Toolkit: $ip",
                url = "https://bgp.he.net/ip/$ip",
                snippet = "Autonomous system number (ASN), BGP routing prefixes, reverse DNS PTR records, and upstream peering transit information for host $ip."
            )
        )

        // 5. AbuseIPDB Network Threat & Abuse Registry
        results.add(
            SearchResultItem(
                title = "AbuseIPDB IP Intelligence & Abuse Report: $ip",
                url = "https://www.abuseipdb.com/check/$ip",
                snippet = "Abuse reports, blacklist verification, spam confidence score, and network security history for host IP $ip."
            )
        )

        return results.take(limit)
    }

    /**
     * Primary: Queries Bing Search using the free RSS endpoint with an HTML fallback.
     */
    private fun queryBing(query: String, limit: Int): List<SearchResultItem> {
        // Attempt 1: Bing RSS
        val rssResults = queryBingRss(query, limit)
        if (rssResults.isNotEmpty()) return rssResults

        // Attempt 2: Bing HTML
        return queryBingHtml(query, limit)
    }

    private fun isSpamOrIrrelevant(title: String, url: String, query: String): Boolean {
        val u = url.lowercase()
        if (u.contains("xhamster") || u.contains("xvideos") || u.contains("xnxx") ||
            u.contains("porn") || u.contains("poki.com") || u.contains("playhop.com") ||
            u.contains("y8.com") || u.contains("crazygames.com") || u.contains("friv.com") ||
            u.contains("lidl.com")
        ) {
            return true
        }
        val hasArabic = title.any { it in '\u0600'..'\u06FF' }
        val queryHasArabic = query.any { it in '\u0600'..'\u06FF' }
        if (hasArabic && !queryHasArabic) return true
        return false
    }

    private fun queryBingRss(query: String, limit: Int): List<SearchResultItem> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val url = "https://www.bing.com/search?q=$encodedQuery&format=rss&setlang=en-US&cc=US&adlt=strict"

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/rss+xml,application/xml,text/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.9")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            val xml = response.body?.string() ?: return emptyList()
            return parseBingRssXml(xml, query, limit)
        }
    }

    private fun parseBingRssXml(xml: String, query: String, limit: Int): List<SearchResultItem> {
        val items = mutableListOf<SearchResultItem>()
        val itemMatcher = BING_ITEM_REGEX.matcher(xml)

        while (itemMatcher.find() && items.size < limit) {
            val itemXml = itemMatcher.group(1) ?: continue

            val tm = TITLE_TAG_REGEX.matcher(itemXml)
            val lm = LINK_TAG_REGEX.matcher(itemXml)
            val dm = DESC_TAG_REGEX.matcher(itemXml)

            val rawTitle = if (tm.find()) tm.group(1) ?: "" else ""
            val rawLink = if (lm.find()) lm.group(1) ?: "" else ""
            val rawDesc = if (dm.find()) dm.group(1) ?: "" else ""

            val title = cleanSnippet(stripHtmlTags(decodeHtml(rawTitle)))
            val link = decodeBingClickUrl(decodeHtml(rawLink).trim())
            val snippet = cleanSnippet(stripHtmlTags(decodeHtml(rawDesc)))

            if (title.isNotBlank() && link.startsWith("http") && !isSpamOrIrrelevant(title, link, query)) {
                items.add(
                    SearchResultItem(
                        title = title,
                        url = link,
                        snippet = snippet.ifBlank { "Documentation and web result for $title" }
                    )
                )
            }
        }
        return items
    }

    private fun queryBingHtml(query: String, limit: Int): List<SearchResultItem> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val url = "https://www.bing.com/search?q=$encodedQuery&setlang=en-US&cc=US&adlt=strict"

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.9")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            val html = response.body?.string() ?: return emptyList()
            return parseBingHtml(html, query, limit)
        }
    }

    private fun parseBingHtml(html: String, query: String, limit: Int): List<SearchResultItem> {
        val results = mutableListOf<SearchResultItem>()
        val algoMatcher = BING_ALGO_REGEX.matcher(html)

        while (algoMatcher.find() && results.size < limit) {
            val block = algoMatcher.group(1) ?: continue
            val h2m = BING_H2_LINK_REGEX.matcher(block)
            if (h2m.find()) {
                val rawLink = h2m.group(1) ?: ""
                val rawTitle = h2m.group(2) ?: ""
                val pm = BING_P_REGEX.matcher(block)
                val rawSnippet = if (pm.find()) pm.group(1) ?: "" else ""

                val title = cleanSnippet(stripHtmlTags(decodeHtml(rawTitle)))
                val link = decodeBingClickUrl(decodeHtml(rawLink).trim())
                val snippet = cleanSnippet(stripHtmlTags(decodeHtml(rawSnippet)))

                if (title.isNotBlank() && link.startsWith("http") && !isSpamOrIrrelevant(title, link, query)) {
                    results.add(SearchResultItem(title, link, snippet.ifBlank { title }))
                }
            }
        }
        return results
    }

    /**
     * Fallback 1: DuckDuckGo Instant Answer API.
     */
    private fun queryDuckDuckGo(query: String, limit: Int): List<SearchResultItem> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val url = "https://api.duckduckgo.com/?q=$encodedQuery&format=json&no_html=1&skip_disambig=1"

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
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

                    val firstUrl = item.optString("FirstURL").trim()
                    val text = item.optString("Text").trim()

                    if (firstUrl.isNotBlank() && text.isNotBlank() && results.none { it.url == firstUrl }) {
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

    /**
     * Fallback 2: Wikipedia Search API for technical documentation, concepts, and facts.
     */
    private fun queryWikipedia(query: String, limit: Int): List<SearchResultItem> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val url = "https://en.wikipedia.org/w/api.php?action=query&list=search&srsearch=$encodedQuery&utf8=&format=json&srlimit=$limit"

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            val body = response.body?.string() ?: return emptyList()
            val json = JSONObject(body)
            val queryObj = json.optJSONObject("query") ?: return emptyList()
            val searchArr = queryObj.optJSONArray("search") ?: return emptyList()

            val results = mutableListOf<SearchResultItem>()

            for (i in 0 until searchArr.length()) {
                if (results.size >= limit) break
                val item = searchArr.optJSONObject(i) ?: continue
                val title = item.optString("title").trim()
                val rawSnippet = item.optString("snippet")
                val cleanSnippetText = cleanSnippet(stripHtmlTags(decodeHtml(rawSnippet)))
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

    /**
     * Fallback 3: HackerNews Search API (Algolia) for developer tutorials, engineering blogs, and tech releases.
     */
    private fun queryHackerNews(query: String, limit: Int): List<SearchResultItem> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val url = "https://hn.algolia.com/api/v1/search?query=$encodedQuery&tags=story&hitsPerPage=$limit"

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            val body = response.body?.string() ?: return emptyList()
            val json = JSONObject(body)
            val hits = json.optJSONArray("hits") ?: return emptyList()

            val results = mutableListOf<SearchResultItem>()
            for (i in 0 until hits.length()) {
                if (results.size >= limit) break
                val item = hits.optJSONObject(i) ?: continue
                val title = item.optString("title").trim()
                var itemUrl = item.optString("url").trim()
                val objectId = item.optString("objectID").trim()

                if (itemUrl.isBlank() && objectId.isNotBlank()) {
                    itemUrl = "https://news.ycombinator.com/item?id=$objectId"
                }

                val points = item.optInt("points", 0)
                val numComments = item.optInt("num_comments", 0)
                val author = item.optString("author")
                val snippet = "Technical article: $title (Score: $points, Comments: $numComments, author: $author)."

                if (title.isNotBlank() && itemUrl.startsWith("http")) {
                    results.add(
                        SearchResultItem(
                            title = title,
                            url = itemUrl,
                            snippet = cleanSnippet(snippet)
                        )
                    )
                }
            }
            return results
        }
    }

    /**
     * Decodes Bing click tracking redirects to direct web URLs when applicable.
     */
    fun decodeBingClickUrl(url: String): String {
        if (!url.contains("bing.com/ck/a")) return url
        val matcher = Pattern.compile("[?&]u=a1([a-zA-Z0-9_-]+)").matcher(url)
        if (!matcher.find()) return url
        val base64Str = matcher.group(1) ?: return url
        return try {
            val pad = (4 - (base64Str.length % 4)) % 4
            val padded = base64Str + "=".repeat(pad)
            val normalized = padded.replace('-', '+').replace('_', '/')
            val decodedBytes = Base64.getDecoder().decode(normalized)
            String(decodedBytes, Charsets.UTF_8)
        } catch (_: Exception) {
            url
        }
    }

    private fun stripHtmlTags(input: String): String {
        return HTML_TAG_REGEX.matcher(input).replaceAll("")
    }

    private fun decodeHtml(input: String): String {
        var str = input
        str = str.replace("&amp;", "&")
        str = str.replace("&lt;", "<")
        str = str.replace("&gt;", ">")
        str = str.replace("&quot;", "\"")
        str = str.replace("&apos;", "'")
        str = str.replace("&#39;", "'")
        str = str.replace("&nbsp;", " ")
        str = str.replace("&#8217;", "’")
        str = str.replace("&#8216;", "‘")
        str = str.replace("&#8220;", "“")
        str = str.replace("&#8221;", "”")
        return str
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
