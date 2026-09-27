package com.example.agent.tools.web

import java.util.regex.Pattern

/**
 * High-performance, pure-Kotlin cleaner and content extractor for HTML web pages.
 *
 * Removes scripts, styles, navigation, headers, footers, sidebars, cookie banners,
 * and ads. Extracts the page title, metadata, and main readable body text, converting
 * structure into clean Markdown-style text while enforcing maximum character limits
 * to protect model context.
 */
object HtmlContentExtractor {

    // Non-content blocks to strip entirely
    private val SCRIPT_REGEX = Pattern.compile("<script\\b[^>]*>.*?</script>", Pattern.CASE_INSENSITIVE or Pattern.DOTALL)
    private val STYLE_REGEX = Pattern.compile("<style\\b[^>]*>.*?</style>", Pattern.CASE_INSENSITIVE or Pattern.DOTALL)
    private val NOSCRIPT_REGEX = Pattern.compile("<noscript\\b[^>]*>.*?</noscript>", Pattern.CASE_INSENSITIVE or Pattern.DOTALL)
    private val SVG_REGEX = Pattern.compile("<svg\\b[^>]*>.*?</svg>", Pattern.CASE_INSENSITIVE or Pattern.DOTALL)
    private val IFRAME_REGEX = Pattern.compile("<iframe\\b[^>]*>.*?</iframe>", Pattern.CASE_INSENSITIVE or Pattern.DOTALL)
    private val COMMENT_REGEX = Pattern.compile("<!--.*?-->", Pattern.DOTALL)

    // Boilerplate navigation, footer, header, and aside blocks
    private val NAV_REGEX = Pattern.compile("<nav\\b[^>]*>.*?</nav>", Pattern.CASE_INSENSITIVE or Pattern.DOTALL)
    private val HEADER_REGEX = Pattern.compile("<header\\b[^>]*>.*?</header>", Pattern.CASE_INSENSITIVE or Pattern.DOTALL)
    private val FOOTER_REGEX = Pattern.compile("<footer\\b[^>]*>.*?</footer>", Pattern.CASE_INSENSITIVE or Pattern.DOTALL)
    private val ASIDE_REGEX = Pattern.compile("<aside\\b[^>]*>.*?</aside>", Pattern.CASE_INSENSITIVE or Pattern.DOTALL)

    // Common ad, cookie, and clutter containers
    private val CLUTTER_CONTAINER_REGEX = Pattern.compile(
        "<div\\b[^>]*(?:class|id)=[\"'][^\"']*(?:cookie-banner|cookie-notice|cookie-consent|ad-banner|advertisement|sidebar-menu|social-share)[^\"']*[\"'][^>]*>.*?</div>",
        Pattern.CASE_INSENSITIVE or Pattern.DOTALL
    )

    // Metadata & Title extractors
    private val TITLE_TAG_REGEX = Pattern.compile("<title\\b[^>]*>(.*?)</title>", Pattern.CASE_INSENSITIVE or Pattern.DOTALL)
    private val META_DESCRIPTION_REGEX = Pattern.compile(
        "<meta\\b[^>]*?(?:name=[\"']description[\"'][^>]*?content=[\"']([^\"']*)[\"']|content=[\"']([^\"']*)[\"'][^>]*?name=[\"']description[\"'])",
        Pattern.CASE_INSENSITIVE
    )
    private val META_OG_DESCRIPTION_REGEX = Pattern.compile(
        "<meta\\b[^>]*?property=[\"']og:description[\"'][^>]*?content=[\"']([^\"']*)[\"']",
        Pattern.CASE_INSENSITIVE
    )
    private val META_AUTHOR_REGEX = Pattern.compile(
        "<meta\\b[^>]*?(?:name=[\"']author[\"'][^>]*?content=[\"']([^\"']*)[\"']|content=[\"']([^\"']*)[\"'][^>]*?name=[\"']author[\"'])",
        Pattern.CASE_INSENSITIVE
    )
    private val META_OG_TITLE_REGEX = Pattern.compile(
        "<meta\\b[^>]*?property=[\"']og:title[\"'][^>]*?content=[\"']([^\"']*)[\"']",
        Pattern.CASE_INSENSITIVE
    )

    // Main article/content targets
    private val ARTICLE_REGEX = Pattern.compile("<article\\b[^>]*>(.*?)</article>", Pattern.CASE_INSENSITIVE or Pattern.DOTALL)
    private val MAIN_REGEX = Pattern.compile("<main\\b[^>]*>(.*?)</main>", Pattern.CASE_INSENSITIVE or Pattern.DOTALL)

    // Structural elements to markdown
    private val H1_REGEX = Pattern.compile("<h1\\b[^>]*>(.*?)</h1>", Pattern.CASE_INSENSITIVE or Pattern.DOTALL)
    private val H2_REGEX = Pattern.compile("<h2\\b[^>]*>(.*?)</h2>", Pattern.CASE_INSENSITIVE or Pattern.DOTALL)
    private val H3_REGEX = Pattern.compile("<h3\\b[^>]*>(.*?)</h3>", Pattern.CASE_INSENSITIVE or Pattern.DOTALL)
    private val H4_H6_REGEX = Pattern.compile("<h[4-6]\\b[^>]*>(.*?)</h[4-6]>", Pattern.CASE_INSENSITIVE or Pattern.DOTALL)
    private val P_TAG_REGEX = Pattern.compile("</p>|<p\\b[^>]*>", Pattern.CASE_INSENSITIVE)
    private val BR_TAG_REGEX = Pattern.compile("<br\\s*/?>", Pattern.CASE_INSENSITIVE)
    private val LI_TAG_REGEX = Pattern.compile("<li\\b[^>]*>", Pattern.CASE_INSENSITIVE)
    private val TR_TAG_REGEX = Pattern.compile("</tr>", Pattern.CASE_INSENSITIVE)
    private val TD_TAG_REGEX = Pattern.compile("</td>|</th>", Pattern.CASE_INSENSITIVE)
    private val BLOCKQUOTE_REGEX = Pattern.compile("<blockquote\\b[^>]*>(.*?)</blockquote>", Pattern.CASE_INSENSITIVE or Pattern.DOTALL)
    private val CODE_PRE_REGEX = Pattern.compile("<pre\\b[^>]*><code\\b[^>]*>(.*?)</code></pre>", Pattern.CASE_INSENSITIVE or Pattern.DOTALL)
    private val TAG_STRIP_REGEX = Pattern.compile("<[^>]+>")

    /**
     * Extracts title, metadata, and cleaned readable text from raw HTML.
     */
    fun extract(
        rawHtml: String,
        sourceUrl: String,
        finalUrl: String,
        maxChars: Int = 4000
    ): WebPageContent {
        if (rawHtml.isBlank()) {
            return WebPageContent(
                title = "",
                sourceUrl = sourceUrl,
                finalUrl = finalUrl,
                content = "",
                metadata = emptyMap(),
                isTruncated = false,
                totalExtractedChars = 0
            )
        }

        // 1. Extract title
        val title = extractTitle(rawHtml)

        // 2. Extract metadata
        val metadata = extractMetadata(rawHtml)

        // 3. Remove script, style, comments, and boilerplate navigation
        var cleaned = rawHtml
        cleaned = SCRIPT_REGEX.matcher(cleaned).replaceAll("")
        cleaned = STYLE_REGEX.matcher(cleaned).replaceAll("")
        cleaned = NOSCRIPT_REGEX.matcher(cleaned).replaceAll("")
        cleaned = SVG_REGEX.matcher(cleaned).replaceAll("")
        cleaned = IFRAME_REGEX.matcher(cleaned).replaceAll("")
        cleaned = COMMENT_REGEX.matcher(cleaned).replaceAll("")
        cleaned = NAV_REGEX.matcher(cleaned).replaceAll("")
        cleaned = HEADER_REGEX.matcher(cleaned).replaceAll("")
        cleaned = FOOTER_REGEX.matcher(cleaned).replaceAll("")
        cleaned = ASIDE_REGEX.matcher(cleaned).replaceAll("")
        cleaned = CLUTTER_CONTAINER_REGEX.matcher(cleaned).replaceAll("")

        // 4. Try extracting from primary content tags (<article>, <main>) if present and substantial
        val articleMatch = ARTICLE_REGEX.matcher(cleaned)
        val mainMatch = MAIN_REGEX.matcher(cleaned)

        val articleGroup = if (articleMatch.find()) articleMatch.group(1) else null
        val mainGroup = if (mainMatch.find()) mainMatch.group(1) else null

        val targetedContent = when {
            articleGroup != null && articleGroup.length > 200 -> articleGroup
            mainGroup != null && mainGroup.length > 200 -> mainGroup
            else -> cleaned
        }

        // 5. Transform structured HTML to readable Markdown text
        var text = targetedContent
        text = CODE_PRE_REGEX.matcher(text).replaceAll("\n```\n$1\n```\n")
        text = BLOCKQUOTE_REGEX.matcher(text).replaceAll("\n> $1\n")
        text = H1_REGEX.matcher(text).replaceAll("\n\n# $1\n\n")
        text = H2_REGEX.matcher(text).replaceAll("\n\n## $1\n\n")
        text = H3_REGEX.matcher(text).replaceAll("\n\n### $1\n\n")
        text = H4_H6_REGEX.matcher(text).replaceAll("\n\n#### $1\n\n")
        text = P_TAG_REGEX.matcher(text).replaceAll("\n\n")
        text = BR_TAG_REGEX.matcher(text).replaceAll("\n")
        text = LI_TAG_REGEX.matcher(text).replaceAll("\n• ")
        text = TD_TAG_REGEX.matcher(text).replaceAll(" | ")
        text = TR_TAG_REGEX.matcher(text).replaceAll("\n")

        // 6. Strip any leftover tags
        text = TAG_STRIP_REGEX.matcher(text).replaceAll("")

        // 7. Decode HTML entities
        text = decodeHtmlEntities(text)

        // 8. Normalize whitespace and empty lines
        val normalized = normalizeWhitespace(text)
        val totalChars = normalized.length

        // 9. Enforce max character limit to preserve context window
        val effectiveLimit = maxChars.coerceIn(500, 12000)
        val (finalContent, isTruncated) = if (totalChars > effectiveLimit) {
            val truncated = truncateCleanly(normalized, effectiveLimit)
            val withNotice = truncated + "\n\n[Content truncated at $effectiveLimit characters to preserve model context. (Total readable text: $totalChars characters)]"
            Pair(withNotice, true)
        } else {
            Pair(normalized, false)
        }

        return WebPageContent(
            title = title,
            sourceUrl = sourceUrl,
            finalUrl = finalUrl,
            content = finalContent,
            metadata = metadata,
            isTruncated = isTruncated,
            totalExtractedChars = totalChars
        )
    }

    private fun extractTitle(html: String): String {
        val matcher = TITLE_TAG_REGEX.matcher(html)
        if (matcher.find()) {
            val rawTitle = matcher.group(1)?.trim() ?: ""
            return decodeHtmlEntities(TAG_STRIP_REGEX.matcher(rawTitle).replaceAll("")).trim()
        }

        val ogTitleMatcher = META_OG_TITLE_REGEX.matcher(html)
        if (ogTitleMatcher.find()) {
            val rawOgTitle = ogTitleMatcher.group(1)?.trim() ?: ""
            return decodeHtmlEntities(rawOgTitle).trim()
        }

        return ""
    }

    private fun extractMetadata(html: String): Map<String, String> {
        val metadata = mutableMapOf<String, String>()

        // Description
        val descMatcher = META_DESCRIPTION_REGEX.matcher(html)
        if (descMatcher.find()) {
            val desc = descMatcher.group(1) ?: descMatcher.group(2)
            if (!desc.isNullOrBlank()) {
                metadata["description"] = decodeHtmlEntities(desc.trim())
            }
        } else {
            val ogDescMatcher = META_OG_DESCRIPTION_REGEX.matcher(html)
            if (ogDescMatcher.find()) {
                val ogDesc = ogDescMatcher.group(1)
                if (!ogDesc.isNullOrBlank()) {
                    metadata["description"] = decodeHtmlEntities(ogDesc.trim())
                }
            }
        }

        // Author
        val authorMatcher = META_AUTHOR_REGEX.matcher(html)
        if (authorMatcher.find()) {
            val author = authorMatcher.group(1) ?: authorMatcher.group(2)
            if (!author.isNullOrBlank()) {
                metadata["author"] = decodeHtmlEntities(author.trim())
            }
        }

        return metadata
    }

    private fun truncateCleanly(text: String, limit: Int): String {
        if (text.length <= limit) return text
        val slice = text.substring(0, limit)
        // Find last newline, period, or space to avoid cutting mid-word
        val lastNewline = slice.lastIndexOf('\n')
        val lastPeriod = slice.lastIndexOf(". ")
        val lastSpace = slice.lastIndexOf(' ')

        val cutIndex = when {
            lastNewline > limit - 200 -> lastNewline
            lastPeriod > limit - 150 -> lastPeriod + 1
            lastSpace > limit - 60 -> lastSpace
            else -> limit
        }
        return slice.substring(0, cutIndex).trimEnd()
    }

    private fun normalizeWhitespace(text: String): String {
        val lines = text.lines()
        val result = StringBuilder()
        var consecutiveEmptyLines = 0

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) {
                if (consecutiveEmptyLines < 2) {
                    result.append("\n")
                    consecutiveEmptyLines++
                }
            } else {
                consecutiveEmptyLines = 0
                // Collapse multi-spaces within a line
                val cleanLine = trimmed.replace(Regex("[ \\t]+"), " ")
                result.append(cleanLine).append("\n")
            }
        }
        return result.toString().trim()
    }

    fun decodeHtmlEntities(input: String): String {
        var str = input
        str = str.replace("&nbsp;", " ")
        str = str.replace("&amp;", "&")
        str = str.replace("&lt;", "<")
        str = str.replace("&gt;", ">")
        str = str.replace("&quot;", "\"")
        str = str.replace("&apos;", "'")
        str = str.replace("&#39;", "'")
        str = str.replace("&mdash;", "—")
        str = str.replace("&ndash;", "–")
        str = str.replace("&bull;", "•")
        str = str.replace("&hellip;", "…")

        // Decimal numeric entities &#123;
        str = Regex("&#(\\d+);").replace(str) { match ->
            try {
                val code = match.groupValues[1].toInt()
                code.toChar().toString()
            } catch (_: Exception) {
                match.value
            }
        }

        // Hex numeric entities &#x1F600;
        str = Regex("&#x([0-9a-fA-F]+);").replace(str) { match ->
            try {
                val code = match.groupValues[1].toInt(16)
                code.toChar().toString()
            } catch (_: Exception) {
                match.value
            }
        }

        return str
    }
}
