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
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Lightbulb
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
 * Animated pulsating status indicator in Claude Code style.
 * Displays smooth breathing pulses during execution and crisp status rings upon completion.
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
            initialValue = 0.85f,
            targetValue = 1.25f,
            animationSpec = infiniteRepeatable(
                animation = tween(650, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "pulse_scale"
        )
        val pulseAlpha by infiniteTransition.animateFloat(
            initialValue = 0.35f,
            targetValue = 0.95f,
            animationSpec = infiniteRepeatable(
                animation = tween(650, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "pulse_alpha"
        )
        val outerWaveScale by infiniteTransition.animateFloat(
            initialValue = 1.0f,
            targetValue = 1.9f,
            animationSpec = infiniteRepeatable(
                animation = tween(1200, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "wave_scale"
        )
        val outerWaveAlpha by infiniteTransition.animateFloat(
            initialValue = 0.5f,
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
                    .size(8.dp)
                    .background(color, CircleShape)
            )
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .border(1.dp, color.copy(alpha = 0.35f), CircleShape)
            )
        }
    }
}

/**
 * Modern Claude Code-style Tool Execution Card.
 * Compact, elegant, and directly reflects the Android app's Material 3 theme.
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
 * Unified Claude Code-style interactive tool card for commands, scripts, and workspace operations.
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

    val toolType = when (record.toolName.lowercase().trim()) {
        "run_command", "bash", "sh" -> "bash"
        "python_execute", "python", "py" -> "python"
        "file_write" -> "write"
        "file_read" -> "read"
        "file_patch" -> "patch"
        "file_search", "grep" -> "search"
        "file_list", "file_tree" -> "files"
        "web_search" -> "search"
        "web_open" -> "web"
        "archive_extract" -> "extract"
        else -> record.toolName.removeSuffix("Tool").lowercase()
    }

    val commandText = (record.arguments["command"] ?: record.arguments["cmd"] ?: record.arguments["code"] ?: record.arguments["script_path"])?.toString()
        ?: if (record.arguments["path"] != null) record.arguments["path"].toString()
        else if (record.arguments["query"] != null) record.arguments["query"].toString()
        else record.toolName

    val singleLineCommand = remember(commandText) {
        commandText.lines()
            .map { it.trim() }
            .firstOrNull { it.isNotBlank() && !it.startsWith("#") }
            ?: commandText.trim()
    }

    val output = (if (record.isSuccess) record.result else record.error) ?: ""
    val isSuccess = record.isSuccess
    val statusColor = if (isSuccess) Color(0xFF10B981) else Color(0xFFEF4444)

    val badgeBg = when (toolType) {
        "bash" -> Color(0xFF38BDF8).copy(alpha = 0.12f)
        "python" -> Color(0xFFFBBF24).copy(alpha = 0.12f)
        "write", "patch" -> Color(0xFFA78BFA).copy(alpha = 0.12f)
        "read", "files" -> Color(0xFF60A5FA).copy(alpha = 0.12f)
        "search", "web" -> Color(0xFF34D399).copy(alpha = 0.12f)
        else -> MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
    }

    val badgeTextColor = when (toolType) {
        "bash" -> Color(0xFF0284C7)
        "python" -> Color(0xFFD97706)
        "write", "patch" -> Color(0xFF7C3AED)
        "read", "files" -> Color(0xFF2563EB)
        "search", "web" -> Color(0xFF059669)
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
            .padding(horizontal = 14.dp, vertical = 3.dp)
            .testTag("agent_terminal_card"),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
        )
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Header Row (Claude Code style tool line)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { isExpanded = !isExpanded }
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Animated Status Dot
                ClaudePulseIndicator(
                    color = statusColor,
                    isRunning = false
                )

                // Tool Type Pill (e.g. bash, python, write)
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = badgeBg,
                    border = BorderStroke(0.5.dp, badgeTextColor.copy(alpha = 0.35f))
                ) {
                    Text(
                        text = toolType,
                        style = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.sp,
                            color = badgeTextColor
                        ),
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }

                // Command / Action Text in clean Monospace
                Text(
                    text = singleLineCommand,
                    style = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Normal,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                // Trailing Info: Duration badge + Copy + Expand Chevron
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    val durationMatch = remember(output) {
                        Regex("""(?i)(?:duration:?\s*(\d+)ms|in\s*([0-9.]+s))""").find(output)?.value
                    }
                    if (durationMatch != null) {
                        Text(
                            text = durationMatch,
                            style = TextStyle(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.5.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                            )
                        )
                    }

                    IconButton(
                        onClick = {
                            clipboardManager.setText(AnnotatedString("$commandText\n$output"))
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

                    Icon(
                        imageVector = Icons.Outlined.KeyboardArrowDown,
                        contentDescription = if (isExpanded) "Collapse" else "Expand",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        modifier = Modifier
                            .size(18.dp)
                            .rotate(chevronRotation)
                    )
                }
            }

            // Expandable Output Body
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
                    // Divider line
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(0.5.dp)
                            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // If multi-line command, show full command block
                    if (commandText.lines().size > 1) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
                            border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 6.dp)
                        ) {
                            Text(
                                text = commandText,
                                style = TextStyle(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.5.sp,
                                    lineHeight = 16.sp,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f)
                                ),
                                modifier = Modifier
                                    .padding(8.dp)
                                    .horizontalScroll(rememberScrollState())
                            )
                        }
                    }

                    // Output Monospace container
                    val outputLinesCount = output.lines().filter { it.isNotBlank() }.size
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = if (isSuccess) "Output (${outputLinesCount} lines)" else "Error",
                                    style = TextStyle(
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 10.5.sp,
                                        color = if (isSuccess) MaterialTheme.colorScheme.onSurfaceVariant else Color(0xFFEF4444)
                                    )
                                )
                            }

                            if (output.isNotBlank()) {
                                Text(
                                    text = output.trimEnd(),
                                    style = TextStyle(
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp,
                                        lineHeight = 16.sp,
                                        color = if (isSuccess) MaterialTheme.colorScheme.onSurface else Color(0xFFF87171)
                                    ),
                                    modifier = Modifier.horizontalScroll(rememberScrollState())
                                )
                            } else {
                                Text(
                                    text = "(Command completed with no output)",
                                    style = TextStyle(
                                        fontFamily = FontFamily.Monospace,
                                        fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
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
 * Sleek Claude Code-style Thinking & Reflection Card.
 * Replaces basic text with a subtle, beautiful reasoning card with an animated breathing indicator.
 */
@Composable
fun AgentReflectionCard(
    reflection: AgentReflection,
    modifier: Modifier = Modifier
) {
    val reflectionColor = Color(0xFF818CF8) // Indigo accent
    var isExpanded by remember { mutableStateOf(false) }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 3.dp)
            .testTag("agent_reflection_card"),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.32f),
        border = BorderStroke(
            width = 1.dp,
            color = reflectionColor.copy(alpha = 0.22f)
        )
    ) {
        Column(
            modifier = Modifier
                .clickable { isExpanded = !isExpanded }
                .padding(horizontal = 11.dp, vertical = 8.dp)
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
                    text = "Thinking",
                    style = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 11.sp,
                        color = reflectionColor
                    )
                )

                Spacer(modifier = Modifier.weight(1f))

                Icon(
                    imageVector = if (isExpanded) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown,
                    contentDescription = null,
                    tint = reflectionColor.copy(alpha = 0.7f),
                    modifier = Modifier.size(15.dp)
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = reflection.thought,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Default,
                    fontWeight = FontWeight.Normal,
                    fontSize = 12.5.sp,
                    lineHeight = 18.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.88f)
                ),
                maxLines = if (isExpanded) Int.MAX_VALUE else 3,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
