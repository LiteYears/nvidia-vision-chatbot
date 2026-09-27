package com.example.agent.tools.web

import java.net.URI
import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * High-performance, pure-Kotlin reader-mode cleaner and content extractor for HTML web pages.
 *
 * Capabilities:
 * - Aggressive Clutter Removal: Removes scripts, styles, noscript, svg, iframes, navigation,
 *   headers, footers, sidebars, cookie banners, newsletter overlays, and advertisements.
 * - Prompt Injection Quarantine: Strips invisible/hidden DOM elements (CSS display:none, visibility:hidden,
 *   opacity:0, font-size:0, hidden, aria-hidden) and sanitizes adversarial delimiter collisions.
 * - Semantic Readability: Prioritizes `<article>`, `<main>`, `<div role="main">`, and major content containers.
 * - Structured Markdown: Converts headings, tables, code blocks, lists, blockquotes, and text links.
 * - Hyperlink Preservation: Extracts meaningful in-content Markdown links `[text](url)` resolved against the base URL.
 * - Rich Metadata: Extracts title, site name, published date, author, description, and canonical URL.
 * - Document Outline: Builds a Table of Contents from headings for quick overview and section navigation.
 * - Smart Pagination & Section Targeting: Supports `offset` pagination and `section` targeting.
 */
object HtmlContentExtractor {

    // Non-content blocks to strip entirely
    private val SCRIPT_REGEX = Pattern.compile("<script\\b[^>]*>.*?</script>", Pattern.CASE_INSENSITIVE or Pattern.DOTALL)
    private val STYLE_REGEX = Pattern.compile("<style\\b[^>]*>.*?</style>", Pattern.CASE_INSENSITIVE or Pattern.DOTALL)
    private val NOSCRIPT_REGEX = Pattern.compile("<noscript\\b[^>]*>.*?</noscript>", Pattern.CASE_INSENSITIVE or Pattern.DOTALL)
    private val SVG_REGEX = Pattern.compile("<svg\\b[^>]*>.*?</svg>", Pattern.CASE_INSENSITIVE or Pattern.DOTALL)
    private val IFRAME_REGEX = Pattern.compile("<iframe\\b[^>]*>.*?</iframe>", Pattern.CASE_INSENSITIVE or Pattern.DOTALL)
    private val OBJECT_EMBED_REGEX = Pattern.compile("<(object|embed|applet|canvas|audio|video)\\b[^>]*>.*?</\\1>", Pattern.CASE_INSENSITIVE or Pattern.DOTALL)
    private val COMMENT_REGEX = Pattern.compile("<!--.*?-->", Pattern.DOTALL)

    // Boilerplate navigation, footer, header, and aside blocks
    private val NAV_REGEX = Pattern.compile("<nav\\b[^>]*>.*?</nav>", Pattern.CASE_INSENSITIVE or Pattern.DOTALL)
    private val HEADER_REGEX = Pattern.compile("<header\\b[^>]*>.*?</header>", Pattern.CASE_INSENSITIVE or Pattern.DOTALL)
    private val FOOTER_REGEX = Pattern.compile("<footer\\b[^>]*>.*?</footer>", Pattern.CASE_INSENSITIVE or Pattern.DOTALL)
    private val ASIDE_REGEX = Pattern.compile("<aside\\b[^>]*>.*?</aside>", Pattern.CASE_INSENSITIVE or Pattern.DOTALL)
    private val FORM_REGEX = Pattern.compile("<form\\b[^>]*>.*?</form>", Pattern.CASE_INSENSITIVE or Pattern.DOTALL)
    private val DIALOG_REGEX = Pattern.compile("<dialog\\b[^>]*>.*?</dialog>", Pattern.CASE_INSENSITIVE or Pattern.DOTALL)

    // Strips invisible / hidden elements used for prompt injections or tracking
    private val HIDDEN_ELEMENT_REGEX = Pattern.compile(
        "<[a-zA-Z0-9_-]+\\b[^>]*(?:style=[\"'][^\"']*(?:display\\s*:\\s*none|visibility\\s*:\\s*hidden|opacity\\s*:\\s*0|font-size\\s*:\\s*0|text-indent\\s*:\\s*-[0-9]{3,})[^\"']*[\"']|\\bhidden\\b|aria-hidden=[\"']true[\"'])[^>]*>.*?</[a-zA-Z0-9_-]+>",
        Pattern.CASE_INSENSITIVE or Pattern.DOTALL
    )

    // Common ad, cookie, newsletter, sidebar, and comment clutter containers
    private val CLUTTER_CONTAINER_REGEX = Pattern.compile(
        "<(?:div|section|aside)\\b[^>]*(?:class|id)=[\"'][^\"']*(?:cookie-banner|cookie-notice|cookie-consent|gdpr-banner|ad-banner|advertisement|sponsored|sidebar-menu|sidebar-nav|social-share|share-buttons|newsletter|subscribe-box|disqus|comment-list|comments-area|related-posts|popular-posts|breadcrumb)[^\"']*[\"'][^>]*>.*?</(?:div|section|aside)>",
        Pattern.CASE_INSENSITIVE or Pattern.DOTALL
    )

    // Metadata & Title extractors
    private val TITLE_TAG_REGEX = Pattern.compile("<title\\b[^>]*>(.*?)</title>", Pattern.CASE_INSENSITIVE or Pattern.DOTALL)
    private val META_OG_TITLE_REGEX = Pattern.compile("<meta\\b[^>]*?property=[\"']og:title[\"'][^>]*?content=[\"']([^\"']*)[\"']", Pattern.CASE_INSENSITIVE)
    private val META_TWITTER_TITLE_REGEX = Pattern.compile("<meta\\b[^>]*?name=[\"']twitter:title[\"'][^>]*?content=[\"']([^\"']*)[\"']", Pattern.CASE_INSENSITIVE)

    private val META_DESCRIPTION_REGEX = Pattern.compile(
        "<meta\\b[^>]*?(?:name=[\"']description[\"'][^>]*?content=[\"']([^\"']*)[\"']|content=[\"']([^\"']*)[\"'][^>]*?name=[\"']description[\"'])",
        Pattern.CASE_INSENSITIVE
    )
    private val META_OG_DESCRIPTION_REGEX = Pattern.compile("<meta\\b[^>]*?property=[\"']og:description[\"'][^>]*?content=[\"']([^\"']*)[\"']", Pattern.CASE_INSENSITIVE)
    private val META_OG_SITENAME_REGEX = Pattern.compile("<meta\\b[^>]*?property=[\"']og:site_name[\"'][^>]*?content=[\"']([^\"']*)[\"']", Pattern.CASE_INSENSITIVE)

    private val META_AUTHOR_REGEX = Pattern.compile(
        "<meta\\b[^>]*?(?:name=[\"']author[\"'][^>]*?content=[\"']([^\"']*)[\"']|content=[\"']([^\"']*)[\"'][^>]*?name=[\"']author[\"'])",
        Pattern.CASE_INSENSITIVE
    )
    private val META_ARTICLE_AUTHOR_REGEX = Pattern.compile("<meta\\b[^>]*?property=[\"']article:author[\"'][^>]*?content=[\"']([^\"']*)[\"']", Pattern.CASE_INSENSITIVE)

    private val META_PUBLISHED_TIME_REGEX = Pattern.compile("<meta\\b[^>]*?property=[\"']article:published_time[\"'][^>]*?content=[\"']([^\"']*)[\"']", Pattern.CASE_INSENSITIVE)
    private val TIME_TAG_REGEX = Pattern.compile("<time\\b[^>]*datetime=[\"']([^\"']*)[\"'][^>]*>(.*?)</time>", Pattern.CASE_INSENSITIVE or Pattern.DOTALL)

    // Main article/content targets
    private val ARTICLE_REGEX = Pattern.compile("<article\\b[^>]*>(.*?)</article>", Pattern.CASE_INSENSITIVE or Pattern.DOTALL)
    private val MAIN_REGEX = Pattern.compile("<main\\b[^>]*>(.*?)</main>", Pattern.CASE_INSENSITIVE or Pattern.DOTALL)
    private val ROLE_MAIN_REGEX = Pattern.compile("<div\\b[^>]*role=[\"']main[\"'][^>]*>(.*?)</div>", Pattern.CASE_INSENSITIVE or Pattern.DOTALL)
    private val CONTENT_CONTAINER_REGEX = Pattern.compile(
        "<(?:div|section)\\b[^>]*(?:class|id)=[\"'][^\"']*(?:post-content|article-body|entry-content|main-content|article__content|story-body|article-text|c-entry-content)[^\"']*[\"'][^>]*>(.*?)</(?:div|section)>",
        Pattern.CASE_INSENSITIVE or Pattern.DOTALL
    )

    // Structural elements to markdown
    private val TABLE_REGEX = Pattern.compile("<table\\b[^>]*>(.*?)</table>", Pattern.CASE_INSENSITIVE or Pattern.DOTALL)
    private val CODE_PRE_REGEX = Pattern.compile(
        "<pre\\b[^>]*>(?:<code\\b[^>]*(?:class=[\"'][^\"']*language-([a-zA-Z0-9_-]+)[^\"']*[\"'])?[^>]*>)?(.*?)(?:</code>)?</pre>",
        Pattern.CASE_INSENSITIVE or Pattern.DOTALL
    )
    private val INLINE_CODE_REGEX = Pattern.compile("<code\\b[^>]*>(.*?)</code>", Pattern.CASE_INSENSITIVE or Pattern.DOTALL)
    private val H1_REGEX = Pattern.compile("<h1\\b[^>]*>(.*?)</h1>", Pattern.CASE_INSENSITIVE or Pattern.DOTALL)
    private val H2_REGEX = Pattern.compile("<h2\\b[^>]*>(.*?)</h2>", Pattern.CASE_INSENSITIVE or Pattern.DOTALL)
    private val H3_REGEX = Pattern.compile("<h3\\b[^>]*>(.*?)</h3>", Pattern.CASE_INSENSITIVE or Pattern.DOTALL)
    private val H4_H6_REGEX = Pattern.compile("<h([4-6])\\b[^>]*>(.*?)</h\\1>", Pattern.CASE_INSENSITIVE or Pattern.DOTALL)
    private val BLOCKQUOTE_REGEX = Pattern.compile("<blockquote\\b[^>]*>(.*?)</blockquote>", Pattern.CASE_INSENSITIVE or Pattern.DOTALL)
    private val P_TAG_REGEX = Pattern.compile("</p>|<p\\b[^>]*>", Pattern.CASE_INSENSITIVE)
    private val BR_TAG_REGEX = Pattern.compile("<br\\s*/?>", Pattern.CASE_INSENSITIVE)
    private val LI_TAG_REGEX = Pattern.compile("<li\\b[^>]*>", Pattern.CASE_INSENSITIVE)
    private val BOLD_TAG_REGEX = Pattern.compile("<(?:b|strong)\\b[^>]*>(.*?)</(?:b|strong)>", Pattern.CASE_INSENSITIVE or Pattern.DOTALL)
    private val ITALIC_TAG_REGEX = Pattern.compile("<(?:i|em)\\b[^>]*>(.*?)</(?:i|em)>", Pattern.CASE_INSENSITIVE or Pattern.DOTALL)
    private val TAG_STRIP_REGEX = Pattern.compile("<[^>]+>")

    /**
     * Extracts title, rich metadata, document outline, key links, and cleaned readable text from raw HTML.
     *
     * @param rawHtml The HTML content fetched from the remote web server.
     * @param sourceUrl The URL requested.
     * @param finalUrl The final URL after any HTTP redirects.
     * @param maxChars Maximum character limit for this read window (500..12000).
     * @param offset Character offset into the article content for pagination.
     * @param section Optional section heading to jump directly to.
     */
    fun extract(
        rawHtml: String,
        sourceUrl: String,
        finalUrl: String,
        maxChars: Int = 4000,
        offset: Int = 0,
        section: String? = null
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

        // 1. Extract metadata before stripping tags
        val title = extractTitle(rawHtml)
        val metadata = extractMetadata(rawHtml)
        val siteName = extractSiteName(rawHtml, finalUrl)
        val publishedDate = extractPublishedDate(rawHtml)
        val author = extractAuthor(rawHtml)

        // 2. Remove script, style, comments, and boilerplate navigation
        var cleaned = rawHtml
        cleaned = SCRIPT_REGEX.matcher(cleaned).replaceAll("")
        cleaned = STYLE_REGEX.matcher(cleaned).replaceAll("")
        cleaned = NOSCRIPT_REGEX.matcher(cleaned).replaceAll("")
        cleaned = SVG_REGEX.matcher(cleaned).replaceAll("")
        cleaned = IFRAME_REGEX.matcher(cleaned).replaceAll("")
        cleaned = OBJECT_EMBED_REGEX.matcher(cleaned).replaceAll("")
        cleaned = COMMENT_REGEX.matcher(cleaned).replaceAll("")

        // 3. Strip hidden / invisible elements to prevent indirect prompt injection
        cleaned = HIDDEN_ELEMENT_REGEX.matcher(cleaned).replaceAll("")

        // 4. Strip boilerplate headers, footers, aside, nav, dialogs, forms, and ad/cookie clutter
        cleaned = NAV_REGEX.matcher(cleaned).replaceAll("")
        cleaned = HEADER_REGEX.matcher(cleaned).replaceAll("")
        cleaned = FOOTER_REGEX.matcher(cleaned).replaceAll("")
        cleaned = ASIDE_REGEX.matcher(cleaned).replaceAll("")
        cleaned = FORM_REGEX.matcher(cleaned).replaceAll("")
        cleaned = DIALOG_REGEX.matcher(cleaned).replaceAll("")
        cleaned = CLUTTER_CONTAINER_REGEX.matcher(cleaned).replaceAll("")

        // 5. Semantic container detection: find main article content
        val targetedContent = findPrimaryContentRegion(cleaned)

        // 6. Transform structured HTML to readable Markdown text
        var text = targetedContent

        // 6a. Convert tables to clean Markdown tables
        text = convertTablesToMarkdown(text)

        // 6b. Convert code blocks
        text = convertCodeBlocksToMarkdown(text)

        // 6c. Convert inline code
        text = INLINE_CODE_REGEX.matcher(text).replaceAll("`$1`")

        // 6d. Convert blockquotes
        val bqMatcher = BLOCKQUOTE_REGEX.matcher(text)
        val bqSb = StringBuffer()
        while (bqMatcher.find()) {
            val inner = bqMatcher.group(1)?.let { TAG_STRIP_REGEX.matcher(it).replaceAll("") } ?: ""
            val quoted = inner.lines().filter { it.isNotBlank() }.joinToString("\n") { "> ${it.trim()}" }
            bqMatcher.appendReplacement(bqSb, Matcher.quoteReplacement("\n\n$quoted\n\n"))
        }
        bqMatcher.appendTail(bqSb)
        text = bqSb.toString()

        // 6e. Convert headings (collect for Table of Contents)
        text = H1_REGEX.matcher(text).replaceAll("\n\n# $1\n\n")
        text = H2_REGEX.matcher(text).replaceAll("\n\n## $1\n\n")
        text = H3_REGEX.matcher(text).replaceAll("\n\n### $1\n\n")
        text = H4_H6_REGEX.matcher(text).replaceAll("\n\n#### $2\n\n")

        // 6f. Convert formatting (bold, italic)
        text = BOLD_TAG_REGEX.matcher(text).replaceAll("**$1**")
        text = ITALIC_TAG_REGEX.matcher(text).replaceAll("*$1*")

        // 6g. Convert hyperlinks: preserve meaningful links and collect key references
        val keyLinks = mutableListOf<Pair<String, String>>()
        text = convertLinksToMarkdown(text, finalUrl, keyLinks)

        // 6h. Paragraphs, breaks, and lists
        text = P_TAG_REGEX.matcher(text).replaceAll("\n\n")
        text = BR_TAG_REGEX.matcher(text).replaceAll("\n")
        text = LI_TAG_REGEX.matcher(text).replaceAll("\n• ")

        // 7. Strip leftover HTML tags
        text = TAG_STRIP_REGEX.matcher(text).replaceAll("")

        // 8. Decode HTML entities
        text = decodeHtmlEntities(text)

        // 9. Sanitize adversarial prompt injection delimiters from external web text
        text = sanitizePromptInjectionDelimiters(text)

        // 10. Normalize whitespace
        val normalized = normalizeWhitespace(text)
        val totalChars = normalized.length

        // 11. Extract Table of Contents from headings
        val tableOfContents = extractTableOfContents(normalized)

        // 12. Handle Section Targeting if requested
        val contentForWindow = if (!section.isNullOrBlank()) {
            extractTargetSection(normalized, section.trim())
        } else {
            normalized
        }

        // 13. Windowing & Pagination
        val effectiveLimit = maxChars.coerceIn(500, 12000)
        val safeOffset = offset.coerceIn(0, totalChars)

        val windowText = if (safeOffset > 0 && safeOffset < contentForWindow.length) {
            val sliceStart = findSafeStartOffset(contentForWindow, safeOffset)
            contentForWindow.substring(sliceStart)
        } else {
            contentForWindow
        }

        val isTruncated = windowText.length > effectiveLimit
        val displayedContent = if (isTruncated) {
            truncateCleanly(windowText, effectiveLimit)
        } else {
            windowText
        }

        val nextOffset = if (isTruncated) {
            safeOffset + displayedContent.length
        } else {
            null
        }

        return WebPageContent(
            title = title,
            sourceUrl = sourceUrl,
            finalUrl = finalUrl,
            content = displayedContent,
            metadata = metadata,
            isTruncated = isTruncated,
            totalExtractedChars = totalChars,
            siteName = siteName,
            publishedDate = publishedDate,
            author = author,
            tableOfContents = tableOfContents,
            keyLinks = keyLinks,
            offset = safeOffset,
            nextOffset = nextOffset,
            activeSection = section
        )
    }

    private fun findPrimaryContentRegion(html: String): String {
        // Priority 1: <article>
        val articleMatch = ARTICLE_REGEX.matcher(html)
        if (articleMatch.find()) {
            val group = articleMatch.group(1)
            if (group != null && group.length > 250) return group
        }

        // Priority 2: <main>
        val mainMatch = MAIN_REGEX.matcher(html)
        if (mainMatch.find()) {
            val group = mainMatch.group(1)
            if (group != null && group.length > 250) return group
        }

        // Priority 3: <div role="main">
        val roleMatch = ROLE_MAIN_REGEX.matcher(html)
        if (roleMatch.find()) {
            val group = roleMatch.group(1)
            if (group != null && group.length > 250) return group
        }

        // Priority 4: Typical semantic article classes
        val containerMatch = CONTENT_CONTAINER_REGEX.matcher(html)
        if (containerMatch.find()) {
            val group = containerMatch.group(1)
            if (group != null && group.length > 250) return group
        }

        return html
    }

    private fun convertTablesToMarkdown(html: String): String {
        val matcher = TABLE_REGEX.matcher(html)
        val sb = StringBuffer()

        val rowRegex = Pattern.compile("<tr\\b[^>]*>(.*?)</tr>", Pattern.CASE_INSENSITIVE or Pattern.DOTALL)
        val cellRegex = Pattern.compile("<(?:th|td)\\b[^>]*>(.*?)</(?:th|td)>", Pattern.CASE_INSENSITIVE or Pattern.DOTALL)

        while (matcher.find()) {
            val tableBody = matcher.group(1) ?: ""
            val rowMatcher = rowRegex.matcher(tableBody)
            val rows = mutableListOf<List<String>>()

            while (rowMatcher.find()) {
                val rowHtml = rowMatcher.group(1) ?: ""
                val cellMatcher = cellRegex.matcher(rowHtml)
                val cells = mutableListOf<String>()

                while (cellMatcher.find()) {
                    val rawCell = cellMatcher.group(1) ?: ""
                    val cleanCell = decodeHtmlEntities(TAG_STRIP_REGEX.matcher(rawCell).replaceAll(""))
                        .replace("\n", " ")
                        .replace("|", "\\|")
                        .trim()
                    cells.add(cleanCell)
                }
                if (cells.isNotEmpty()) {
                    rows.add(cells)
                }
            }

            if (rows.isNotEmpty() && rows[0].size > 1) {
                val maxCols = rows.maxOf { it.size }
                val tableMd = buildString {
                    append("\n\n")
                    // Header row
                    val header = rows[0].map { it.ifBlank { " " } }
                    val paddedHeader = header + List(maxCols - header.size) { " " }
                    append("| ").append(paddedHeader.joinToString(" | ")).append(" |\n")

                    // Separator
                    append("| ").append(List(maxCols) { "---" }.joinToString(" | ")).append(" |\n")

                    // Data rows
                    for (i in 1 until rows.size) {
                        val row = rows[i].map { it.ifBlank { " " } }
                        val paddedRow = row + List(maxCols - row.size) { " " }
                        append("| ").append(paddedRow.joinToString(" | ")).append(" |\n")
                    }
                    append("\n")
                }
                matcher.appendReplacement(sb, Matcher.quoteReplacement(tableMd))
            } else {
                matcher.appendReplacement(sb, Matcher.quoteReplacement(tableBody))
            }
        }
        matcher.appendTail(sb)
        return sb.toString()
    }

    private fun convertCodeBlocksToMarkdown(html: String): String {
        val matcher = CODE_PRE_REGEX.matcher(html)
        val sb = StringBuffer()
        while (matcher.find()) {
            val lang = matcher.group(1)?.trim() ?: ""
            val rawCode = matcher.group(2) ?: ""
            val cleanCode = decodeHtmlEntities(TAG_STRIP_REGEX.matcher(rawCode).replaceAll(""))
            val formatted = "\n```$lang\n$cleanCode\n```\n"
            matcher.appendReplacement(sb, Matcher.quoteReplacement(formatted))
        }
        matcher.appendTail(sb)
        return sb.toString()
    }

    private fun convertLinksToMarkdown(
        html: String,
        finalUrl: String,
        collectedLinks: MutableList<Pair<String, String>>
    ): String {
        val linkPattern = Pattern.compile("<a\\b[^>]*href=[\"']([^\"']*)[\"'][^>]*>(.*?)</a>", Pattern.CASE_INSENSITIVE or Pattern.DOTALL)
        val matcher = linkPattern.matcher(html)
        val sb = StringBuffer()

        while (matcher.find()) {
            val rawHref = matcher.group(1)?.trim() ?: ""
            val rawText = matcher.group(2)?.trim() ?: ""
            val cleanText = decodeHtmlEntities(TAG_STRIP_REGEX.matcher(rawText).replaceAll("")).trim()

            if (cleanText.isBlank() || rawHref.isBlank() || rawHref.startsWith("#") ||
                rawHref.startsWith("javascript:", ignoreCase = true) ||
                rawHref.startsWith("mailto:", ignoreCase = true) ||
                rawHref.startsWith("tel:", ignoreCase = true)
            ) {
                matcher.appendReplacement(sb, Matcher.quoteReplacement(cleanText))
            } else {
                val resolvedUrl = resolveRelativeUrl(rawHref, finalUrl)
                if (resolvedUrl.startsWith("http://", ignoreCase = true) || resolvedUrl.startsWith("https://", ignoreCase = true)) {
                    if (cleanText.length in 3..75 && collectedLinks.size < 10) {
                        if (collectedLinks.none { it.second == resolvedUrl }) {
                            collectedLinks.add(Pair(cleanText, resolvedUrl))
                        }
                    }
                    matcher.appendReplacement(sb, Matcher.quoteReplacement("[$cleanText]($resolvedUrl)"))
                } else {
                    matcher.appendReplacement(sb, Matcher.quoteReplacement(cleanText))
                }
            }
        }
        matcher.appendTail(sb)
        return sb.toString()
    }

    private fun resolveRelativeUrl(href: String, baseUrl: String): String {
        return try {
            val baseUri = URI(baseUrl)
            baseUri.resolve(href).toString()
        } catch (_: Exception) {
            href
        }
    }

    private fun sanitizePromptInjectionDelimiters(input: String): String {
        var text = input
        text = text.replace("[TOOL_RESULT:", "[tool_result_ref:")
        text = text.replace("[SUBTASK STATUS:", "[subtask_status_ref:")
        text = text.replace("```tool_call", "```tool_call_text")
        text = text.replace("```tool_result", "```tool_result_text")
        text = text.replace("<|im_start|>", "&lt;|im_start|&gt;")
        text = text.replace("<|im_end|>", "&lt;|im_end|&gt;")
        text = text.replace("<|system|>", "&lt;|system|&gt;")
        text = text.replace("<system>", "&lt;system&gt;")
        text = text.replace("</system>", "&lt;/system&gt;")
        text = text.replace("<prompt>", "&lt;prompt&gt;")
        text = text.replace("</prompt>", "&lt;/prompt&gt;")
        return text
    }

    private fun extractTitle(html: String): String {
        val matcher = TITLE_TAG_REGEX.matcher(html)
        if (matcher.find()) {
            val rawTitle = matcher.group(1)?.trim() ?: ""
            val clean = decodeHtmlEntities(TAG_STRIP_REGEX.matcher(rawTitle).replaceAll("")).trim()
            if (clean.isNotBlank()) return clean
        }

        val ogTitleMatcher = META_OG_TITLE_REGEX.matcher(html)
        if (ogTitleMatcher.find()) {
            val rawOgTitle = ogTitleMatcher.group(1)?.trim() ?: ""
            val clean = decodeHtmlEntities(rawOgTitle).trim()
            if (clean.isNotBlank()) return clean
        }

        val twitterTitleMatcher = META_TWITTER_TITLE_REGEX.matcher(html)
        if (twitterTitleMatcher.find()) {
            val rawTwTitle = twitterTitleMatcher.group(1)?.trim() ?: ""
            val clean = decodeHtmlEntities(rawTwTitle).trim()
            if (clean.isNotBlank()) return clean
        }

        return ""
    }

    private fun extractSiteName(html: String, finalUrl: String): String {
        val ogSiteMatcher = META_OG_SITENAME_REGEX.matcher(html)
        if (ogSiteMatcher.find()) {
            val site = ogSiteMatcher.group(1)?.trim() ?: ""
            if (site.isNotBlank()) return decodeHtmlEntities(site)
        }

        return try {
            val uri = URI(finalUrl)
            uri.host?.removePrefix("www.") ?: ""
        } catch (_: Exception) {
            ""
        }
    }

    private fun extractPublishedDate(html: String): String {
        val pubMatcher = META_PUBLISHED_TIME_REGEX.matcher(html)
        if (pubMatcher.find()) {
            val date = pubMatcher.group(1)?.trim() ?: ""
            if (date.isNotBlank()) return date
        }

        val timeMatcher = TIME_TAG_REGEX.matcher(html)
        if (timeMatcher.find()) {
            val datetime = timeMatcher.group(1)?.trim() ?: ""
            if (datetime.isNotBlank()) return datetime
            val text = timeMatcher.group(2)?.let { TAG_STRIP_REGEX.matcher(it).replaceAll("").trim() } ?: ""
            if (text.isNotBlank()) return text
        }

        return ""
    }

    private fun extractAuthor(html: String): String {
        val authorMatcher = META_AUTHOR_REGEX.matcher(html)
        if (authorMatcher.find()) {
            val author = authorMatcher.group(1) ?: authorMatcher.group(2)
            if (!author.isNullOrBlank()) {
                return decodeHtmlEntities(author.trim())
            }
        }

        val articleAuthorMatcher = META_ARTICLE_AUTHOR_REGEX.matcher(html)
        if (articleAuthorMatcher.find()) {
            val author = articleAuthorMatcher.group(1)
            if (!author.isNullOrBlank()) {
                return decodeHtmlEntities(author.trim())
            }
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
        val author = extractAuthor(html)
        if (author.isNotBlank()) {
            metadata["author"] = author
        }

        // Published Date
        val published = extractPublishedDate(html)
        if (published.isNotBlank()) {
            metadata["published"] = published
        }

        // Site Name
        val ogSite = META_OG_SITENAME_REGEX.matcher(html)
        if (ogSite.find()) {
            val site = ogSite.group(1)
            if (!site.isNullOrBlank()) {
                metadata["site"] = decodeHtmlEntities(site.trim())
            }
        }

        return metadata
    }

    private fun extractTableOfContents(markdown: String): List<String> {
        val headings = mutableListOf<String>()
        val headingPattern = Pattern.compile("^(#{1,4})\\s+(.+)$", Pattern.MULTILINE)
        val matcher = headingPattern.matcher(markdown)

        while (matcher.find()) {
            val hashes = matcher.group(1) ?: "#"
            val text = matcher.group(2)?.trim() ?: ""
            if (text.isNotBlank() && text.length < 80) {
                val indent = "  ".repeat((hashes.length - 1).coerceAtLeast(0))
                headings.add("$indent- $hashes $text")
            }
        }
        return headings
    }

    private fun extractTargetSection(text: String, sectionQuery: String): String {
        val headingPattern = Pattern.compile("^(#{1,4})\\s+(.+)$", Pattern.MULTILINE)
        val matcher = headingPattern.matcher(text)

        var foundStart = -1
        var matchedLevel = 1
        var foundEnd = text.length

        while (matcher.find()) {
            val level = matcher.group(1)?.length ?: 1
            val headingTitle = matcher.group(2)?.trim() ?: ""

            if (foundStart == -1) {
                if (headingTitle.contains(sectionQuery, ignoreCase = true)) {
                    foundStart = matcher.start()
                    matchedLevel = level
                }
            } else {
                // Next heading of equal or higher rank concludes the section
                if (level <= matchedLevel) {
                    foundEnd = matcher.start()
                    break
                }
            }
        }

        return if (foundStart != -1) {
            text.substring(foundStart, foundEnd).trim()
        } else {
            text
        }
    }

    private fun findSafeStartOffset(text: String, offset: Int): Int {
        if (offset <= 0) return 0
        if (offset >= text.length) return text.length

        // Look back up to 80 chars for a newline or period to avoid cutting mid-word
        val lookbackRange = (offset - 80).coerceAtLeast(0)
        val searchSlice = text.substring(lookbackRange, offset)

        val lastNewline = searchSlice.lastIndexOf('\n')
        if (lastNewline != -1) return lookbackRange + lastNewline + 1

        val lastPeriod = searchSlice.lastIndexOf(". ")
        if (lastPeriod != -1) return lookbackRange + lastPeriod + 2

        return offset
    }

    private fun truncateCleanly(text: String, limit: Int): String {
        if (text.length <= limit) return text
        val slice = text.substring(0, limit)
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
        str = str.replace("&copy;", "©")
        str = str.replace("&reg;", "®")
        str = str.replace("&trade;", "™")
        str = str.replace("&deg;", "°")
        str = str.replace("&plusmn;", "±")
        str = str.replace("&times;", "×")
        str = str.replace("&divide;", "÷")
        str = str.replace("&lsquo;", "‘")
        str = str.replace("&rsquo;", "’")
        str = str.replace("&ldquo;", "“")
        str = str.replace("&rdquo;", "”")
        str = str.replace("&pound;", "£")
        str = str.replace("&euro;", "€")
        str = str.replace("&yen;", "¥")
        str = str.replace("&sect;", "§")
        str = str.replace("&middot;", "·")

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
