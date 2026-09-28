package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Terminal
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.AgentReflection
import com.example.data.model.ToolExecutionRecord
import kotlinx.coroutines.delay

/**
 * Animated pulsating status indicator with smooth breathing transitions.
 * Features a glowing core, expanding ambient wave when running, and crisp ring on completion.
 */
@Composable
fun ClaudePulseIndicator(
    color: Color,
    isRunning: Boolean = false,
    modifier: Modifier = Modifier
) {
    if (isRunning) {
        val infiniteTransition = rememberInfiniteTransition(label = "pulse_transition")
        val pulseScale by infiniteTransition.animateFloat(
            initialValue = 0.82f,
            targetValue = 1.25f,
            animationSpec = infiniteRepeatable(
                animation = tween(650, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "pulse_scale"
        )
        val pulseAlpha by infiniteTransition.animateFloat(
            initialValue = 0.45f,
            targetValue = 0.95f,
            animationSpec = infiniteRepeatable(
                animation = tween(650, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "pulse_alpha"
        )
        val outerWaveScale by infiniteTransition.animateFloat(
            initialValue = 1.0f,
            targetValue = 1.85f,
            animationSpec = infiniteRepeatable(
                animation = tween(1200, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "wave_scale"
        )
        val outerWaveAlpha by infiniteTransition.animateFloat(
            initialValue = 0.45f,
            targetValue = 0.0f,
            animationSpec = infiniteRepeatable(
                animation = tween(1200, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "wave_alpha"
        )

        Box(
            modifier = modifier.size(16.dp),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .scale(outerWaveScale)
                    .alpha(outerWaveAlpha)
                    .background(color, CircleShape)
            )
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .scale(pulseScale)
                    .alpha(pulseAlpha)
                    .background(color, CircleShape)
            )
        }
    } else {
        Box(
            modifier = modifier.size(16.dp),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .background(color, CircleShape)
            )
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .border(1.dp, color.copy(alpha = 0.4f), CircleShape)
            )
        }
    }
}

/**
 * Three subtle Linux / terminal header window controls (close, minimize, zoom).
 */
@Composable
private fun TerminalWindowDots(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .background(Color(0xFFEF4444).copy(alpha = 0.75f), CircleShape)
        )
        Box(
            modifier = Modifier
                .size(6.dp)
                .background(Color(0xFFF59E0B).copy(alpha = 0.75f), CircleShape)
        )
        Box(
            modifier = Modifier
                .size(6.dp)
                .background(Color(0xFF10B981).copy(alpha = 0.75f), CircleShape)
        )
    }
}

/**
 * Parsed and sanitized tool execution presentation model.
 * Removes technical "exit 0" / "exit 1" jargon in favor of user-friendly Success / Failed states.
 */
private data class ToolCardModel(
    val toolType: String,
    val promptSymbol: String,
    val commandPreview: String,
    val fullCommandOrInput: String,
    val cleanStdout: String,
    val cleanStderr: String?,
    val durationText: String?,
    val userStatusLabel: String,
    val isSuccess: Boolean,
    val workingDir: String?
)

private fun sanitizeToolRecord(record: ToolExecutionRecord): ToolCardModel {
    val rawName = record.toolName.lowercase().trim()
    val toolType = when (rawName) {
        "run_command", "bash", "sh" -> "bash"
        "python_execute", "python", "py" -> "python"
        "file_write" -> "write"
        "file_read" -> "read"
        "file_patch" -> "patch"
        "file_search", "grep" -> "grep"
        "file_list", "file_tree" -> "files"
        "web_search" -> "search"
        "web_open" -> "web"
        "archive_extract" -> "extract"
        "calculator" -> "calc"
        else -> record.toolName.removeSuffix("Tool").lowercase()
    }

    val promptSymbol = when (toolType) {
        "bash" -> "$"
        "python" -> ">>>"
        "read" -> "cat"
        "write" -> "write"
        "patch" -> "patch"
        "grep" -> "grep"
        "files" -> "ls"
        "calc" -> "="
        "search", "web" -> "🌐"
        else -> "❯"
    }

    // Extract command / input text
    val cmdCandidate = (record.arguments["command"]
        ?: record.arguments["cmd"]
        ?: record.arguments["code"]
        ?: record.arguments["script_path"]
        ?: record.arguments["expression"]
        ?: record.arguments["path"]
        ?: record.arguments["query"])?.toString() ?: record.toolName

    val singleLineCommand = cmdCandidate.lines()
        .map { it.trim() }
        .firstOrNull { it.isNotBlank() && !it.startsWith("#") }
        ?: cmdCandidate.trim()

    val rawOutput = (if (record.isSuccess) record.result else record.error) ?: ""

    // Extract duration from text (e.g. "Duration: 45ms", "in 0.12s")
    val durationRegex = Regex("""(?i)(?:duration:?\s*(\d+)ms|in\s*([0-9.]+s)|\((\d+)ms\))""")
    val durationMatch = durationRegex.find(rawOutput)?.let { match ->
        match.groups[1]?.value?.let { "${it}ms" }
            ?: match.groups[2]?.value
            ?: match.groups[3]?.value?.let { "${it}ms" }
    }

    // Extract working directory if present
    val workingDir = (record.arguments["working_dir"] ?: record.arguments["dir"])?.toString()

    // Sanitize output by removing technical "Exit Code: 0", "exit 1", "[stdout]", "[stderr]" boilerplate
    var stdoutText = ""
    var stderrText: String? = null

    if (rawOutput.contains("[stdout]") || rawOutput.contains("[stderr]")) {
        val stdoutPart = rawOutput.substringAfter("[stdout]", "").substringBefore("[stderr]").trim()
        val stderrPart = if (rawOutput.contains("[stderr]")) rawOutput.substringAfter("[stderr]").trim() else null

        stdoutText = stdoutPart
        stderrText = stderrPart?.takeIf { it.isNotBlank() }
    } else {
        // Strip lines like "Exit Code: 0", "Command exited with code 1", etc.
        val cleanedLines = rawOutput.lines().filter { line ->
            val trimmed = line.trim()
            !trimmed.startsWith("Exit Code:", ignoreCase = true) &&
            !trimmed.startsWith("Exit code:", ignoreCase = true) &&
            !trimmed.startsWith("Command exited with code", ignoreCase = true) &&
            !trimmed.matches(Regex("""(?i)^exit\s+\d+.*""")) &&
            !trimmed.startsWith("Working Directory:", ignoreCase = true) &&
            !trimmed.startsWith("Command:", ignoreCase = true) &&
            !trimmed.startsWith("Script:", ignoreCase = true)
        }
        stdoutText = cleanedLines.joinToString("\n").trim()
    }

    // If clean stdout is blank and this was a failure, use error string
    if (stdoutText.isBlank() && !record.isSuccess && !record.error.isNullOrBlank()) {
        stderrText = record.error.lines().filter { line ->
            val trimmed = line.trim()
            !trimmed.startsWith("Command exited with code", ignoreCase = true) &&
            !trimmed.startsWith("Exit Code:", ignoreCase = true)
        }.joinToString("\n").trim()
    }

    val userStatusLabel = if (record.isSuccess) "Success" else "Failed"

    return ToolCardModel(
        toolType = toolType,
        promptSymbol = promptSymbol,
        commandPreview = singleLineCommand,
        fullCommandOrInput = cmdCandidate,
        cleanStdout = stdoutText,
        cleanStderr = stderrText,
        durationText = durationMatch,
        userStatusLabel = userStatusLabel,
        isSuccess = record.isSuccess,
        workingDir = workingDir
    )
}

/**
 * Modern Tool Execution Card with a clean Linux / terminal aesthetic.
 * Collapsible / expandable with a small arrow, animated status indicators,
 * and user-facing Success / Failed states.
 */
@Composable
fun ToolExecutionCard(
    record: ToolExecutionRecord,
    modifier: Modifier = Modifier
) {
    ClaudeToolCard(record = record, modifier = modifier)
}

/**
 * Backward compatibility wrapper matching the test tag requirement.
 */
@Composable
fun AgentTerminalCard(
    record: ToolExecutionRecord,
    modifier: Modifier = Modifier
) {
    ClaudeToolCard(record = record, modifier = modifier)
}

/**
 * Unified interactive tool card for commands, scripts, and workspace operations.
 */
@Composable
fun ClaudeToolCard(
    record: ToolExecutionRecord,
    modifier: Modifier = Modifier
) {
    val clipboardManager = LocalClipboardManager.current
    var hasCopied by remember { mutableStateOf(false) }
    var isExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(hasCopied) {
        if (hasCopied) {
            delay(1800)
            hasCopied = false
        }
    }

    val model = remember(record) { sanitizeToolRecord(record) }
    val statusColor = if (model.isSuccess) Color(0xFF10B981) else Color(0xFFEF4444)

    val badgeBg = when (model.toolType) {
        "bash" -> Color(0xFF38BDF8).copy(alpha = 0.12f)
        "python" -> Color(0xFFFBBF24).copy(alpha = 0.12f)
        "write", "patch" -> Color(0xFFA78BFA).copy(alpha = 0.12f)
        "read", "files" -> Color(0xFF60A5FA).copy(alpha = 0.12f)
        "grep", "search", "web" -> Color(0xFF34D399).copy(alpha = 0.12f)
        "calc" -> Color(0xFFF472B6).copy(alpha = 0.12f)
        else -> MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
    }

    val badgeTextColor = when (model.toolType) {
        "bash" -> Color(0xFF0284C7)
        "python" -> Color(0xFFD97706)
        "write", "patch" -> Color(0xFF7C3AED)
        "read", "files" -> Color(0xFF2563EB)
        "grep", "search", "web" -> Color(0xFF059669)
        "calc" -> Color(0xFFDB2777)
        else -> MaterialTheme.colorScheme.primary
    }

    val chevronRotation by animateFloatAsState(
        targetValue = if (isExpanded) 180f else 0f,
        animationSpec = tween(220),
        label = "chevron_rotate"
    )

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 3.5.dp)
            .testTag("agent_terminal_card"),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        border = BorderStroke(
            width = 1.dp,
            color = if (model.isSuccess) {
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
            } else {
                Color(0xFFEF4444).copy(alpha = 0.35f)
            }
        )
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Header Bar (Terminal Header with window dots + pulse + tool badge + command preview + status + small arrow)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp, bottomStart = if (isExpanded) 0.dp else 12.dp, bottomEnd = if (isExpanded) 0.dp else 12.dp))
                    .clickable { isExpanded = !isExpanded }
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Subtle 3 terminal window dots
                TerminalWindowDots()

                // Animated status dot
                ClaudePulseIndicator(
                    color = statusColor,
                    isRunning = false
                )

                // Tool Type Pill
                Surface(
                    shape = RoundedCornerShape(5.dp),
                    color = badgeBg,
                    border = BorderStroke(0.5.dp, badgeTextColor.copy(alpha = 0.35f))
                ) {
                    Text(
                        text = model.toolType.uppercase(),
                        style = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 9.5.sp,
                            color = badgeTextColor,
                            letterSpacing = 0.5.sp
                        ),
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                    )
                }

                // Command Preview with subtle terminal prompt prefix
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = model.promptSymbol,
                        style = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.5.sp,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.75f)
                        )
                    )
                    Text(
                        text = model.commandPreview,
                        style = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Normal,
                            fontSize = 11.5.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Trailing Info: Duration + User Status Pill (Success / Failed) + Copy + Expand Arrow
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    // Duration if available
                    if (model.durationText != null) {
                        Text(
                            text = model.durationText,
                            style = TextStyle(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                            )
                        )
                    }

                    // User-facing Success / Failed Pill (Replacing raw exit 0 / exit 1)
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = if (model.isSuccess) Color(0xFF10B981).copy(alpha = 0.14f) else Color(0xFFEF4444).copy(alpha = 0.14f),
                        border = BorderStroke(
                            0.5.dp,
                            if (model.isSuccess) Color(0xFF10B981).copy(alpha = 0.35f) else Color(0xFFEF4444).copy(alpha = 0.35f)
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(2.5.dp)
                        ) {
                            Icon(
                                imageVector = if (model.isSuccess) Icons.Filled.Check else Icons.Filled.Close,
                                contentDescription = null,
                                tint = if (model.isSuccess) Color(0xFF10B981) else Color(0xFFEF4444),
                                modifier = Modifier.size(9.dp)
                            )
                            Text(
                                text = model.userStatusLabel,
                                style = TextStyle(
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 9.sp,
                                    color = if (model.isSuccess) Color(0xFF10B981) else Color(0xFFEF4444)
                                )
                            )
                        }
                    }

                    // Copy Button
                    IconButton(
                        onClick = {
                            val copyText = buildString {
                                appendLine("${model.promptSymbol} ${model.fullCommandOrInput}")
                                if (model.cleanStdout.isNotBlank()) {
                                    appendLine(model.cleanStdout)
                                }
                                if (!model.cleanStderr.isNullOrBlank()) {
                                    appendLine(model.cleanStderr)
                                }
                            }.trim()
                            clipboardManager.setText(AnnotatedString(copyText))
                            hasCopied = true
                        },
                        modifier = Modifier
                            .size(28.dp)
                            .testTag("copy_button")
                    ) {
                        Icon(
                            imageVector = if (hasCopied) Icons.Default.Check else Icons.Outlined.ContentCopy,
                            contentDescription = "Copy command and output",
                            tint = if (hasCopied) Color(0xFF10B981) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                            modifier = Modifier.size(13.dp)
                        )
                    }

                    // Small Arrow (Rotates smoothly on expand/collapse)
                    Icon(
                        imageVector = Icons.Outlined.KeyboardArrowDown,
                        contentDescription = if (isExpanded) "Collapse" else "Expand",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                        modifier = Modifier
                            .size(16.dp)
                            .rotate(chevronRotation)
                    )
                }
            }

            // Expandable Content Body (Organized into Command, Stderr, and Console Output)
            AnimatedVisibility(
                visible = isExpanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, end = 12.dp, bottom = 10.dp)
                ) {
                    // Fine divider line
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(0.5.dp)
                            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // 1. COMMAND / INPUT SECTION (Subtle Linux terminal console look)
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xFF0F172A), // Dark slate terminal surface
                        border = BorderStroke(0.5.dp, Color(0xFF1E293B)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp)
                    ) {
                        Column(modifier = Modifier.padding(9.dp)) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 5.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.Terminal,
                                        contentDescription = null,
                                        tint = Color(0xFF38BDF8),
                                        modifier = Modifier.size(12.dp)
                                    )
                                    Text(
                                        text = "COMMAND / SCRIPT",
                                        style = TextStyle(
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 9.sp,
                                            letterSpacing = 0.6.sp,
                                            color = Color(0xFF38BDF8)
                                        )
                                    )
                                }

                                if (!model.workingDir.isNullOrBlank() && model.workingDir != ".") {
                                    Text(
                                        text = "cwd: ${model.workingDir}",
                                        style = TextStyle(
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 9.5.sp,
                                            color = Color(0xFF94A3B8)
                                        )
                                    )
                                }
                            }

                            Row(
                                modifier = Modifier.horizontalScroll(rememberScrollState()),
                                verticalAlignment = Alignment.Top
                            ) {
                                Text(
                                    text = "${model.promptSymbol} ",
                                    style = TextStyle(
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 11.5.sp,
                                        lineHeight = 17.sp,
                                        color = Color(0xFF38BDF8)
                                    )
                                )
                                Text(
                                    text = model.fullCommandOrInput,
                                    style = TextStyle(
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.5.sp,
                                        lineHeight = 17.sp,
                                        color = Color(0xFFE2E8F0)
                                    )
                                )
                            }
                        }
                    }

                    // 2. ERROR / STDERR SECTION (If execution produced error output)
                    if (!model.cleanStderr.isNullOrBlank()) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = Color(0xFF450A0A).copy(alpha = 0.5f),
                            border = BorderStroke(0.5.dp, Color(0xFFEF4444).copy(alpha = 0.4f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 8.dp)
                        ) {
                            Column(modifier = Modifier.padding(9.dp)) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(bottom = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.ErrorOutline,
                                        contentDescription = null,
                                        tint = Color(0xFFF87171),
                                        modifier = Modifier.size(11.dp)
                                    )
                                    Text(
                                        text = "STANDARD ERROR",
                                        style = TextStyle(
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 9.sp,
                                            letterSpacing = 0.5.sp,
                                            color = Color(0xFFF87171)
                                        )
                                    )
                                }
                                Text(
                                    text = model.cleanStderr,
                                    style = TextStyle(
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp,
                                        lineHeight = 16.sp,
                                        color = Color(0xFFFCA5A5)
                                    ),
                                    modifier = Modifier.horizontalScroll(rememberScrollState())
                                )
                            }
                        }
                    }

                    // 3. CONSOLE OUTPUT / RESULT SECTION
                    val outputLines = model.cleanStdout.lines().filter { it.isNotBlank() }
                    val lineCountText = if (outputLines.isNotEmpty()) "(${outputLines.size} lines)" else ""

                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xFF0F172A),
                        border = BorderStroke(0.5.dp, Color(0xFF1E293B)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(9.dp)) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 5.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                                ) {
                                    Text(
                                        text = "CONSOLE OUTPUT",
                                        style = TextStyle(
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 9.sp,
                                            letterSpacing = 0.6.sp,
                                            color = if (model.isSuccess) Color(0xFF34D399) else Color(0xFFF87171)
                                        )
                                    )
                                    if (lineCountText.isNotBlank()) {
                                        Text(
                                            text = lineCountText,
                                            style = TextStyle(
                                                fontFamily = FontFamily.Monospace,
                                                fontSize = 9.sp,
                                                color = Color(0xFF64748B)
                                            )
                                        )
                                    }
                                }

                                Text(
                                    text = if (model.isSuccess) "✓ Completed" else "✗ Failed",
                                    style = TextStyle(
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 9.sp,
                                        color = if (model.isSuccess) Color(0xFF34D399) else Color(0xFFF87171)
                                    )
                                )
                            }

                            if (model.cleanStdout.isNotBlank()) {
                                Text(
                                    text = model.cleanStdout,
                                    style = TextStyle(
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp,
                                        lineHeight = 16.sp,
                                        color = Color(0xFFE2E8F0)
                                    ),
                                    modifier = Modifier.horizontalScroll(rememberScrollState())
                                )
                            } else {
                                Text(
                                    text = if (model.isSuccess) "(Process completed successfully with no console output)" else "(No output produced)",
                                    style = TextStyle(
                                        fontFamily = FontFamily.Monospace,
                                        fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                                        fontSize = 10.5.sp,
                                        color = Color(0xFF64748B)
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Polished Thinking & Reflection Card.
 * Displays the COMPLETE reflection output without truncation or hiding,
 * featuring smooth collapsible/expandable small arrow animation and pulsating reasoning indicator.
 */
@Composable
fun AgentReflectionCard(
    reflection: AgentReflection,
    modifier: Modifier = Modifier
) {
    val clipboardManager = LocalClipboardManager.current
    var hasCopied by remember { mutableStateOf(false) }
    var isExpanded by remember { mutableStateOf(true) } // Expanded by default to show complete thought

    LaunchedEffect(hasCopied) {
        if (hasCopied) {
            delay(1800)
            hasCopied = false
        }
    }

    val reflectionColor = Color(0xFF818CF8) // Indigo accent
    val chevronRotation by animateFloatAsState(
        targetValue = if (isExpanded) 180f else 0f,
        animationSpec = tween(220),
        label = "reflection_chevron"
    )

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 3.5.dp)
            .testTag("agent_reflection_card"),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        border = BorderStroke(
            width = 1.dp,
            color = reflectionColor.copy(alpha = 0.25f)
        )
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Header: Breathing pulse + Title + Copy + Small Arrow
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp, bottomStart = if (isExpanded) 0.dp else 12.dp, bottomEnd = if (isExpanded) 0.dp else 12.dp))
                    .clickable { isExpanded = !isExpanded }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    // Animated breathing thought dot
                    ClaudePulseIndicator(
                        color = reflectionColor,
                        isRunning = true
                    )

                    Text(
                        text = "Reasoning & Reflection",
                        style = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                            letterSpacing = 0.4.sp,
                            color = reflectionColor
                        )
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    IconButton(
                        onClick = {
                            clipboardManager.setText(AnnotatedString(reflection.thought))
                            hasCopied = true
                        },
                        modifier = Modifier.size(26.dp)
                    ) {
                        Icon(
                            imageVector = if (hasCopied) Icons.Default.Check else Icons.Outlined.ContentCopy,
                            contentDescription = "Copy reasoning",
                            tint = if (hasCopied) Color(0xFF10B981) else reflectionColor.copy(alpha = 0.7f),
                            modifier = Modifier.size(13.dp)
                        )
                    }

                    // Small arrow indicating collapsible state
                    Icon(
                        imageVector = Icons.Outlined.KeyboardArrowDown,
                        contentDescription = if (isExpanded) "Collapse" else "Expand",
                        tint = reflectionColor.copy(alpha = 0.8f),
                        modifier = Modifier
                            .size(16.dp)
                            .rotate(chevronRotation)
                    )
                }
            }

            // Expandable Reflection Body (Displays the COMPLETE output, never truncated)
            AnimatedVisibility(
                visible = isExpanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, end = 12.dp, bottom = 10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(0.5.dp)
                            .background(reflectionColor.copy(alpha = 0.18f))
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // Complete reflection output with rich typography
                    Text(
                        text = reflection.thought.trim(),
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = FontWeight.Normal,
                            fontSize = 13.sp,
                            lineHeight = 19.5.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.92f)
                        ),
                        // Explicitly showing complete reflection text without truncation
                        maxLines = Int.MAX_VALUE,
                        overflow = TextOverflow.Clip
                    )
                }
            }
        }
    }
}
