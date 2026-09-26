package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Functions
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * Represents structured blocks of parsed markdown.
 */
sealed class MarkdownBlock {
    data class Paragraph(val text: String) : MarkdownBlock()
    data class Heading(val level: Int, val text: String) : MarkdownBlock()
    data class Code(val language: String, val code: String) : MarkdownBlock()
    data class Math(val equation: String, val isBlock: Boolean) : MarkdownBlock()
    data class Blockquote(val text: String) : MarkdownBlock()
    data class BulletItem(val text: String) : MarkdownBlock()
    data class NumberedItem(val number: String, val text: String) : MarkdownBlock()
    object Divider : MarkdownBlock()
}


/**
 * Top-level Markdown and Math content renderer.
 * Supports real-time animated streaming with ChatGPT-style blinking cursor.
 */
@Composable
fun MarkdownRenderer(
    content: String,
    modifier: Modifier = Modifier,
    isStreaming: Boolean = false
) {
    val blocks = remember(content) { parseMarkdownBlocks(content) }

    val infiniteTransition = rememberInfiniteTransition(label = "cursor_blink")
    val cursorAlpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 480, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "cursor_alpha"
    )
    val primaryColor = MaterialTheme.colorScheme.primary

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        for ((index, block) in blocks.withIndex()) {
            val isLastBlock = index == blocks.size - 1
            when (block) {
                is MarkdownBlock.Heading -> {
                    HeadingBlockView(block)
                }
                is MarkdownBlock.Code -> {
                    CodeBlockView(block)
                }
                is MarkdownBlock.Math -> {
                    MathBlockView(block)
                }
                is MarkdownBlock.Blockquote -> {
                    BlockquoteView(block)
                }
                is MarkdownBlock.BulletItem -> {
                    BulletItemView(block)
                }
                is MarkdownBlock.NumberedItem -> {
                    NumberedItemView(block)
                }
                is MarkdownBlock.Divider -> {
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 8.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        thickness = 1.dp
                    )
                }
                is MarkdownBlock.Paragraph -> {
                    if (block.text.isNotBlank()) {
                        Text(
                            text = parseRichInlineMarkdown(
                                text = block.text,
                                appendCursor = isStreaming && isLastBlock,
                                cursorColor = primaryColor.copy(alpha = cursorAlpha)
                            ),
                            style = MaterialTheme.typography.bodyLarge.copy(
                                color = MaterialTheme.colorScheme.onBackground,
                                fontSize = 15.sp,
                                lineHeight = 23.sp
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }
        if (isStreaming && (blocks.isEmpty() || blocks.lastOrNull() !is MarkdownBlock.Paragraph)) {
            StreamingCursor(modifier = Modifier.padding(top = 2.dp))
        }
    }
}

@Composable
fun StreamingCursor(
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "cursor_blink")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 480, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "cursor_alpha"
    )

    Box(
        modifier = modifier
            .size(width = 8.dp, height = 18.dp)
            .graphicsLayer { this.alpha = alpha }
            .background(
                color = MaterialTheme.colorScheme.primary,
                shape = RoundedCornerShape(2.dp)
            )
    )
}


/**
 * Heading block view supporting H1, H2, H3
 */
@Composable
private fun HeadingBlockView(block: MarkdownBlock.Heading) {
    val (fontSize, fontWeight, color) = when (block.level) {
        1 -> Triple(20.sp, FontWeight.Bold, MaterialTheme.colorScheme.primary)
        2 -> Triple(18.sp, FontWeight.SemiBold, MaterialTheme.colorScheme.onBackground)
        else -> Triple(16.sp, FontWeight.Medium, MaterialTheme.colorScheme.onBackground)
    }

    Text(
        text = parseRichInlineMarkdown(block.text),
        style = MaterialTheme.typography.titleMedium.copy(
            fontSize = fontSize,
            fontWeight = fontWeight,
            color = color,
            lineHeight = (fontSize.value + 6).sp
        ),
        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
    )
}

/**
 * Code Block view with dark editor container, language label, syntax styling, and copy button.
 */
@Composable
fun CodeBlockView(block: MarkdownBlock.Code) {
    val clipboardManager = LocalClipboardManager.current
    var isCopied by remember { mutableStateOf(false) }

    LaunchedEffect(isCopied) {
        if (isCopied) {
            delay(2000)
            isCopied = false
        }
    }

    val displayLang = block.language.ifBlank { "code" }.lowercase()

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Color(0xFF18181D),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF2E2E38)),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .testTag("code_block")
    ) {
        Column {
            // Header bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF23232A))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Terminal,
                        contentDescription = "Code",
                        tint = Color(0xFF8B8BA7),
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = displayLang.uppercase(),
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = Color(0xFFB5B5C9),
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp,
                            fontSize = 11.sp
                        )
                    )
                }

                // Copy button with feedback
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    AnimatedVisibility(
                        visible = isCopied,
                        enter = fadeIn(),
                        exit = fadeOut()
                    ) {
                        Text(
                            text = "Copied!",
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = Color(0xFF4CAF50),
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 11.sp
                            )
                        )
                    }

                    IconButton(
                        onClick = {
                            clipboardManager.setText(AnnotatedString(block.code))
                            isCopied = true
                        },
                        modifier = Modifier
                            .size(28.dp)
                            .testTag("copy_code_button")
                    ) {
                        Icon(
                            imageVector = if (isCopied) Icons.Default.Check else Icons.Outlined.ContentCopy,
                            contentDescription = "Copy code",
                            tint = if (isCopied) Color(0xFF4CAF50) else Color(0xFFA0A0B2),
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }

            // Scrollable code contents with monospace styling
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(14.dp)
            ) {
                Text(
                    text = highlightCodeSyntax(block.code, displayLang),
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        lineHeight = 20.sp,
                        color = Color(0xFFECECF1)
                    )
                )
            }
        }
    }
}

/**
 * Math Block view for equations ($$...$$ or \[...\])
 */
@Composable
fun MathBlockView(block: MarkdownBlock.Math) {
    val clipboardManager = LocalClipboardManager.current
    var isCopied by remember { mutableStateOf(false) }

    LaunchedEffect(isCopied) {
        if (isCopied) {
            delay(2000)
            isCopied = false
        }
    }

    val formattedEquation = remember(block.equation) {
        formatMathLatexToReadable(block.equation)
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .testTag("math_block")
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Functions,
                        contentDescription = "Math",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "FORMULA",
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp,
                            fontSize = 11.sp
                        )
                    )
                }

                IconButton(
                    onClick = {
                        clipboardManager.setText(AnnotatedString(block.equation))
                        isCopied = true
                    },
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        imageVector = if (isCopied) Icons.Default.Check else Icons.Outlined.ContentCopy,
                        contentDescription = "Copy formula",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(vertical = 4.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                Text(
                    text = formattedEquation,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontFamily = FontFamily.Serif,
                        fontStyle = FontStyle.Italic,
                        fontWeight = FontWeight.Medium,
                        fontSize = 16.sp,
                        lineHeight = 24.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                )
            }
        }
    }
}

/**
 * Blockquote styling
 */
@Composable
private fun BlockquoteView(block: MarkdownBlock.Blockquote) {
    Surface(
        shape = RoundedCornerShape(topStart = 2.dp, bottomStart = 2.dp, topEnd = 8.dp, bottomEnd = 8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
    ) {
        Row(
            modifier = Modifier.padding(start = 0.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(36.dp)
                    .background(MaterialTheme.colorScheme.primary)
            )
            Text(
                text = parseRichInlineMarkdown(block.text),
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontStyle = FontStyle.Italic,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 14.sp,
                    lineHeight = 21.sp
                ),
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }
    }
}

/**
 * Bulleted list item
 */
@Composable
private fun BulletItemView(block: MarkdownBlock.BulletItem) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .padding(top = 8.dp)
                .size(6.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary)
        )
        Text(
            text = parseRichInlineMarkdown(block.text),
            style = MaterialTheme.typography.bodyLarge.copy(
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 15.sp,
                lineHeight = 22.sp
            ),
            modifier = Modifier.weight(1f)
        )
    }
}

/**
 * Numbered list item
 */
@Composable
private fun NumberedItemView(block: MarkdownBlock.NumberedItem) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = block.number,
            style = MaterialTheme.typography.bodyLarge.copy(
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                fontSize = 14.sp
            ),
            modifier = Modifier.padding(top = 1.dp)
        )
        Text(
            text = parseRichInlineMarkdown(block.text),
            style = MaterialTheme.typography.bodyLarge.copy(
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 15.sp,
                lineHeight = 22.sp
            ),
            modifier = Modifier.weight(1f)
        )
    }
}

/**
 * Parses markdown string into a list of structured blocks.
 */
fun parseMarkdownBlocks(text: String): List<MarkdownBlock> {
    val blocks = mutableListOf<MarkdownBlock>()
    val lines = text.split("\n")
    var index = 0

    while (index < lines.size) {
        val line = lines[index]
        val trimmed = line.trim()

        // 1. Code block fence: ```[language]
        if (trimmed.startsWith("```")) {
            val language = trimmed.removePrefix("```").trim()
            val codeLines = mutableListOf<String>()
            index++
            while (index < lines.size && !lines[index].trim().startsWith("```")) {
                codeLines.add(lines[index])
                index++
            }
            if (index < lines.size && lines[index].trim().startsWith("```")) {
                index++
            }
            blocks.add(MarkdownBlock.Code(language, codeLines.joinToString("\n")))
            continue
        }

        // 2. Math display block: $$ ... $$ or \[ ... \]
        if (trimmed.startsWith("$$") || trimmed.startsWith("\\[")) {
            val isDollar = trimmed.startsWith("$$")
            val endMarker = if (isDollar) "$$" else "\\]"
            val mathLines = mutableListOf<String>()

            if (trimmed.length > 2 && trimmed.endsWith(endMarker) && trimmed != endMarker) {
                val singleLineMath = if (isDollar) {
                    trimmed.removePrefix("$$").removeSuffix("$$").trim()
                } else {
                    trimmed.removePrefix("\\[").removeSuffix("\\]").trim()
                }
                blocks.add(MarkdownBlock.Math(singleLineMath, true))
                index++
                continue
            }

            index++
            while (index < lines.size && !lines[index].trim().endsWith(endMarker)) {
                mathLines.add(lines[index])
                index++
            }
            if (index < lines.size) {
                val last = lines[index].trim().removeSuffix(endMarker).trim()
                if (last.isNotBlank()) mathLines.add(last)
                index++
            }
            blocks.add(MarkdownBlock.Math(mathLines.joinToString("\n"), true))
            continue
        }

        // 3. Headings
        if (trimmed.startsWith("### ")) {
            blocks.add(MarkdownBlock.Heading(3, trimmed.removePrefix("### ").trim()))
            index++
            continue
        }
        if (trimmed.startsWith("## ")) {
            blocks.add(MarkdownBlock.Heading(2, trimmed.removePrefix("## ").trim()))
            index++
            continue
        }
        if (trimmed.startsWith("# ")) {
            blocks.add(MarkdownBlock.Heading(1, trimmed.removePrefix("# ").trim()))
            index++
            continue
        }

        // 4. Horizontal rule
        if (trimmed == "---" || trimmed == "***" || trimmed == "___") {
            blocks.add(MarkdownBlock.Divider)
            index++
            continue
        }

        // 5. Blockquote
        if (trimmed.startsWith("> ")) {
            val quoteLines = mutableListOf<String>()
            while (index < lines.size && lines[index].trim().startsWith("> ")) {
                quoteLines.add(lines[index].trim().removePrefix("> ").trim())
                index++
            }
            blocks.add(MarkdownBlock.Blockquote(quoteLines.joinToString(" ")))
            continue
        }

        // 6. Bullet lists: *, -, •
        if (trimmed.startsWith("• ") || trimmed.startsWith("- ") || trimmed.startsWith("* ")) {
            val itemText = trimmed.replaceFirst(Regex("^[•\\-\\*]\\s+"), "")
            blocks.add(MarkdownBlock.BulletItem(itemText))
            index++
            continue
        }

        // 7. Numbered list: 1. , 2. 
        val numberedRegex = Regex("^(\\d+[.)])\\s+(.*)")
        val numMatch = numberedRegex.find(trimmed)
        if (numMatch != null) {
            val num = numMatch.groupValues[1]
            val itemText = numMatch.groupValues[2]
            blocks.add(MarkdownBlock.NumberedItem(num, itemText))
            index++
            continue
        }

        // 8. Empty line
        if (trimmed.isEmpty()) {
            index++
            continue
        }

        // 9. Check if standard line contains isolated block equation
        if (trimmed.startsWith("\$") && trimmed.endsWith("\$") && trimmed.length > 2 && !trimmed.substring(1, trimmed.length - 1).contains("\$")) {
            blocks.add(MarkdownBlock.Math(trimmed.substring(1, trimmed.length - 1), false))
            index++
            continue
        }

        // 10. Default paragraph
        blocks.add(MarkdownBlock.Paragraph(line))
        index++
    }

    return blocks
}

/**
 * Rich inline formatting parser:
 * - Bold: **text**
 * - Italic: *text* or _text_
 * - Inline code: `code`
 * - Strikethrough: ~~text~~
 * - Inline Math: $formula$
 * - Mathematical exponents and superscripts
 */
fun parseRichInlineMarkdown(
    text: String,
    appendCursor: Boolean = false,
    cursorColor: Color = Color.Unspecified
): AnnotatedString {
    return buildAnnotatedString {
        var cursor = 0
        val length = text.length

        while (cursor < length) {
            // 1. Inline code: `code`
            if (text[cursor] == '`') {
                val nextBacktick = text.indexOf('`', cursor + 1)
                if (nextBacktick != -1) {
                    val codeContent = text.substring(cursor + 1, nextBacktick)
                    withStyle(
                        SpanStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.5.sp,
                            background = Color(0x28808080),
                            color = Color(0xFF4FC3F7),
                            fontWeight = FontWeight.Medium
                        )
                    ) {
                        append(" $codeContent ")
                    }
                    cursor = nextBacktick + 1
                    continue
                }
            }

            // 2. Inline math: $formula$
            if (text[cursor] == '$') {
                val nextDollar = text.indexOf('$', cursor + 1)
                if (nextDollar != -1 && nextDollar > cursor + 1) {
                    val mathContent = text.substring(cursor + 1, nextDollar)
                    val readableMath = formatMathLatexToReadable(mathContent)
                    withStyle(
                        SpanStyle(
                            fontFamily = FontFamily.Serif,
                            fontStyle = FontStyle.Italic,
                            fontWeight = FontWeight.Medium,
                            color = Color(0xFF64B5F6)
                        )
                    ) {
                        append(readableMath)
                    }
                    cursor = nextDollar + 1
                    continue
                }
            }

            // 3. Bold: **text**
            if (cursor + 1 < length && text.substring(cursor, cursor + 2) == "**") {
                val nextDoubleAsterisk = text.indexOf("**", cursor + 2)
                if (nextDoubleAsterisk != -1) {
                    val boldContent = text.substring(cursor + 2, nextDoubleAsterisk)
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(boldContent)
                    }
                    cursor = nextDoubleAsterisk + 2
                    continue
                }
            }

            // 4. Strikethrough: ~~text~~
            if (cursor + 1 < length && text.substring(cursor, cursor + 2) == "~~") {
                val nextTilde = text.indexOf("~~", cursor + 2)
                if (nextTilde != -1) {
                    val strikeContent = text.substring(cursor + 2, nextTilde)
                    withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                        append(strikeContent)
                    }
                    cursor = nextTilde + 2
                    continue
                }
            }

            // 5. Italic: *text* (when not **)
            if (text[cursor] == '*' && (cursor + 1 >= length || text[cursor + 1] != '*')) {
                val nextAsterisk = text.indexOf('*', cursor + 1)
                if (nextAsterisk != -1 && (nextAsterisk + 1 >= length || text[nextAsterisk + 1] != '*')) {
                    val italicContent = text.substring(cursor + 1, nextAsterisk)
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                        append(italicContent)
                    }
                    cursor = nextAsterisk + 1
                    continue
                }
            }

            // Regular character
            append(text[cursor])
            cursor++
        }

        if (appendCursor) {
            withStyle(
                SpanStyle(
                    color = cursorColor,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
            ) {
                append(" ▍")
            }
        }
    }
}

/**
 * Transforms LaTeX math notation into clean Unicode mathematical symbols.
 */
fun formatMathLatexToReadable(latex: String): String {
    var s = latex
        .replace("\\times", "×")
        .replace("\\div", "÷")
        .replace("\\pm", "±")
        .replace("\\mp", "∓")
        .replace("\\cdot", "·")
        .replace("\\neq", "≠")
        .replace("\\le", "≤")
        .replace("\\leq", "≤")
        .replace("\\ge", "≥")
        .replace("\\geq", "≥")
        .replace("\\approx", "≈")
        .replace("\\equiv", "≡")
        .replace("\\infty", "∞")
        .replace("\\pi", "π")
        .replace("\\alpha", "α")
        .replace("\\beta", "β")
        .replace("\\gamma", "γ")
        .replace("\\delta", "δ")
        .replace("\\Delta", "Δ")
        .replace("\\theta", "θ")
        .replace("\\lambda", "λ")
        .replace("\\sigma", "σ")
        .replace("\\sum", "∑")
        .replace("\\prod", "∏")
        .replace("\\int", "∫")
        .replace("\\partial", "∂")
        .replace("\\nabla", "∇")
        .replace("\\sqrt", "√")
        .replace("\\left(", "(")
        .replace("\\right)", ")")
        .replace("\\left[", "[")
        .replace("\\right]", "]")
        .replace("\\left\\{", "{")
        .replace("\\right\\}", "}")
        .replace(Regex("\\\\frac\\{([^}]+)\\}\\{([^}]+)\\}"), "($1)/($2)")
        .replace(Regex("\\\\sqrt\\{([^}]+)\\}"), "√($1)")
        .replace(Regex("\\^\\{([0-9a-zA-Z+-]+)\\}")) { match ->
            toSuperscript(match.groupValues[1])
        }
        .replace(Regex("\\^([0-9nxi+-])")) { match ->
            toSuperscript(match.groupValues[1])
        }
        .replace(Regex("_\\{([0-9a-zA-Z+-]+)\\}")) { match ->
            toSubscript(match.groupValues[1])
        }
        .replace(Regex("_([0-9a-zA-Z])")) { match ->
            toSubscript(match.groupValues[1])
        }

    return s
}

private fun toSuperscript(text: String): String {
    return text.map { ch ->
        when (ch) {
            '0' -> '⁰'
            '1' -> '¹'
            '2' -> '²'
            '3' -> '³'
            '4' -> '⁴'
            '5' -> '⁵'
            '6' -> '⁶'
            '7' -> '⁷'
            '8' -> '⁸'
            '9' -> '⁹'
            '+' -> '⁺'
            '-' -> '⁻'
            '=' -> '⁼'
            '(' -> '⁽'
            ')' -> '⁾'
            'n' -> 'ⁿ'
            'i' -> 'ⁱ'
            'x' -> 'ˣ'
            else -> ch
        }
    }.joinToString("")
}

private fun toSubscript(text: String): String {
    return text.map { ch ->
        when (ch) {
            '0' -> '₀'
            '1' -> '₁'
            '2' -> '₂'
            '3' -> '₃'
            '4' -> '₄'
            '5' -> '₅'
            '6' -> '₆'
            '7' -> '₇'
            '8' -> '₈'
            '9' -> '₉'
            '+' -> '₊'
            '-' -> '₋'
            '=' -> '₌'
            '(' -> '₍'
            ')' -> '₎'
            'a' -> 'ₐ'
            'e' -> 'ₑ'
            'i' -> 'ᵢ'
            'o' -> 'ₒ'
            'u' -> 'ᵤ'
            'x' -> 'ₓ'
            else -> ch
        }
    }.joinToString("")
}

/**
 * Highlights code syntax keywords, strings, comments and types with contrasting colors.
 */
fun highlightCodeSyntax(code: String, language: String): AnnotatedString {
    val keywords = setOf(
        "val", "var", "fun", "class", "interface", "object", "return", "if", "else", "when",
        "for", "while", "do", "import", "package", "public", "private", "protected", "override",
        "abstract", "sealed", "data", "const", "suspend", "def", "lambda", "from", "as",
        "async", "await", "function", "let", "const", "export", "default", "try", "catch",
        "finally", "throw", "new", "this", "super", "select", "from", "where", "insert",
        "update", "delete", "table", "int", "float", "double", "string", "bool", "boolean",
        "true", "false", "null", "nil", "none"
    )

    return buildAnnotatedString {
        val lines = code.split("\n")
        for ((lineIdx, line) in lines.withIndex()) {
            val trimmed = line.trimStart()

            // Full line comment
            if (trimmed.startsWith("//") || trimmed.startsWith("#")) {
                withStyle(SpanStyle(color = Color(0xFF6A9955), fontStyle = FontStyle.Italic)) {
                    append(line)
                }
            } else {
                // Word by word scanning
                var i = 0
                val len = line.length
                while (i < len) {
                    // String literal "..." or '...'
                    if (line[i] == '"' || line[i] == '\'') {
                        val quote = line[i]
                        val nextQuote = line.indexOf(quote, i + 1)
                        if (nextQuote != -1) {
                            withStyle(SpanStyle(color = Color(0xFFCE9178))) {
                                append(line.substring(i, nextQuote + 1))
                            }
                            i = nextQuote + 1
                            continue
                        }
                    }

                    // Word / Token
                    if (line[i].isLetter() || line[i] == '_') {
                        val start = i
                        while (i < len && (line[i].isLetterOrDigit() || line[i] == '_')) {
                            i++
                        }
                        val word = line.substring(start, i)
                        if (word.lowercase() in keywords) {
                            withStyle(SpanStyle(color = Color(0xFF569CD6), fontWeight = FontWeight.SemiBold)) {
                                append(word)
                            }
                        } else if (word.firstOrNull()?.isUpperCase() == true) {
                            // Type / Class
                            withStyle(SpanStyle(color = Color(0xFF4EC9B0))) {
                                append(word)
                            }
                        } else {
                            withStyle(SpanStyle(color = Color(0xFFD4D4D4))) {
                                append(word)
                            }
                        }
                        continue
                    }

                    // Number
                    if (line[i].isDigit()) {
                        val start = i
                        while (i < len && (line[i].isDigit() || line[i] == '.' || line[i] == 'f' || line[i] == 'L')) {
                            i++
                        }
                        val num = line.substring(start, i)
                        withStyle(SpanStyle(color = Color(0xFFB5CEA8))) {
                            append(num)
                        }
                        continue
                    }

                    // Punctuation / Operator
                    append(line[i])
                    i++
                }
            }

            if (lineIdx < lines.size - 1) {
                append("\n")
            }
        }
    }
}
