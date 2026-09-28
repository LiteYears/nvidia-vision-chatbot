package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.AgentReflection
import com.example.data.model.ToolExecutionRecord

/**
 * Minimal, clean tool execution indicator card inspired by top-tier coding agent workflows (Claude Code, OpenCode).
 * Compact single-line summary with the signature ●○● indicator by default, expandable on tap to inspect raw arguments/output.
 */
@Composable
fun ToolExecutionCard(
    record: ToolExecutionRecord,
    modifier: Modifier = Modifier
) {
    var isExpanded by remember { mutableStateOf(false) }
    val clipboardManager = LocalClipboardManager.current
    var hasCopied by remember { mutableStateOf(false) }

    val statusColor = if (record.isSuccess) Color(0xFF4ADE80) else Color(0xFFF87171)
    val actionSummary = remember(record.toolName, record.arguments) {
        formatToolActionSummary(record.toolName, record.arguments)
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 2.5.dp)
            .testTag("tool_execution_card"),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        border = BorderStroke(
            width = 1.dp,
            color = statusColor.copy(alpha = 0.28f)
        )
    ) {
        Column(
            modifier = Modifier
                .clickable { isExpanded = !isExpanded }
                .padding(horizontal = 10.dp, vertical = 7.dp)
        ) {
            // Minimal Header Row: ●○● Indicator + Action description + Status Badge + Chevron
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Left: ●○● dot pattern + Action Label
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // Stylized ●○● indicator
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        modifier = Modifier.padding(end = 2.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(5.dp)
                                .background(statusColor, CircleShape)
                        )
                        Box(
                            modifier = Modifier
                                .size(5.dp)
                                .border(1.dp, statusColor, CircleShape)
                        )
                        Box(
                            modifier = Modifier
                                .size(5.dp)
                                .background(statusColor, CircleShape)
                        )
                    }

                    Text(
                        text = actionSummary,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Medium,
                            fontSize = 11.5.sp,
                            letterSpacing = 0.2.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.width(6.dp))

                // Right: Status badge [ success ] / [ Failed × ] + Expand Chevron
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = statusColor.copy(alpha = 0.12f),
                        border = BorderStroke(1.dp, statusColor.copy(alpha = 0.3f))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Icon(
                                imageVector = if (record.isSuccess) Icons.Default.Check else Icons.Default.Close,
                                contentDescription = null,
                                tint = statusColor,
                                modifier = Modifier.size(9.5.dp)
                            )
                            Text(
                                text = if (record.isSuccess) "success" else "Failed ×",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = statusColor,
                                    fontSize = 9.5.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            )
                        }
                    }

                    Icon(
                        imageVector = if (isExpanded) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown,
                        contentDescription = if (isExpanded) "Collapse tool details" else "Expand tool details",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        modifier = Modifier.size(15.dp)
                    )
                }
            }

            // Expandable details (Arguments & Structured Result/Error)
            AnimatedVisibility(
                visible = isExpanded,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Column(modifier = Modifier.padding(top = 8.dp)) {
                    // Tool Arguments (if non-empty)
                    if (record.arguments.isNotEmpty()) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.15f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(8.dp)) {
                                Text(
                                    text = "ARGUMENTS",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 9.sp,
                                        letterSpacing = 0.5.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                    )
                                )
                                Spacer(modifier = Modifier.height(3.dp))
                                record.arguments.forEach { (key, value) ->
                                    val valStr = value?.toString() ?: "null"
                                    Text(
                                        text = "$key: $valStr",
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurface
                                        ),
                                        maxLines = 4,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                    }

                    // Result / Error Output
                    val outputText = (if (record.isSuccess) record.result else record.error) ?: "No output"
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (record.isSuccess) Color(0xFF4ADE80).copy(alpha = 0.08f) else Color(0xFFF87171).copy(alpha = 0.08f),
                        border = BorderStroke(1.dp, statusColor.copy(alpha = 0.2f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(8.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = if (record.isSuccess) "OUTPUT" else "ERROR",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 9.sp,
                                        letterSpacing = 0.5.sp,
                                        color = statusColor
                                    )
                                )

                                IconButton(
                                    onClick = {
                                        clipboardManager.setText(AnnotatedString(outputText))
                                        hasCopied = true
                                    },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(
                                        imageVector = if (hasCopied) Icons.Default.Check else Icons.Outlined.ContentCopy,
                                        contentDescription = "Copy output",
                                        tint = if (hasCopied) Color(0xFF4ADE80) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                        modifier = Modifier.size(12.dp)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(2.dp))

                            Text(
                                text = outputText,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    lineHeight = 15.sp,
                                    color = MaterialTheme.colorScheme.onSurface
                                ),
                                maxLines = 15,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Produces clean, minimal action descriptions for tools like Claude Code / OpenCode.
 */
fun formatToolActionSummary(toolName: String, arguments: Map<String, Any?>): String {
    val cleanName = toolName.lowercase().trim()
    return when (cleanName) {
        "file_read" -> {
            val file = (arguments["path"] ?: arguments["file"])?.toString()?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
                ?: arguments["path"]?.toString() ?: "file"
            "reading file ( $file )"
        }
        "file_write" -> {
            val file = (arguments["path"] ?: arguments["file"])?.toString()?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
                ?: arguments["path"]?.toString() ?: "file"
            "Write [ $file ]"
        }
        "file_patch" -> {
            val file = (arguments["path"] ?: arguments["file"])?.toString()?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
                ?: arguments["path"]?.toString() ?: "file"
            "Edit [ $file ]"
        }
        "file_delete" -> {
            val file = (arguments["path"] ?: arguments["file"])?.toString()?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
                ?: arguments["path"]?.toString() ?: "file"
            "Delete [ $file ]"
        }
        "file_list" -> {
            val path = arguments["path"]?.toString()?.takeIf { it.isNotBlank() && it != "." } ?: "workspace"
            "List files ( $path )"
        }
        "file_tree" -> {
            val path = arguments["path"]?.toString()?.takeIf { it.isNotBlank() && it != "." } ?: "workspace"
            "Explore tree ( $path )"
        }
        "file_search" -> {
            val query = (arguments["query"] ?: arguments["pattern"])?.toString() ?: ""
            "Search files ( \"$query\" )"
        }
        "run_command" -> {
            val cmd = arguments["command"]?.toString()?.take(40) ?: ""
            "Command ( $cmd )"
        }
        "python_execute" -> {
            val script = arguments["script_path"]?.toString()?.substringAfterLast('/')
                ?: arguments["code"]?.toString()?.lines()?.firstOrNull()?.trim()?.take(30)
                ?: "python"
            "Executing Python ( $script )"
        }
        "web_search" -> {
            val query = arguments["query"]?.toString()?.take(35) ?: ""
            "Search web ( \"$query\" )"
        }
        "web_open" -> {
            val url = arguments["url"]?.toString()
                ?.removePrefix("https://")?.removePrefix("http://")?.take(35) ?: ""
            "Open ( $url )"
        }
        "archive_extract" -> {
            val file = arguments["archive_path"]?.toString()?.substringAfterLast('/') ?: "archive"
            "Extract ( $file )"
        }
        "calculator" -> {
            val expr = arguments["expression"]?.toString() ?: ""
            "Calculate ( $expr )"
        }
        else -> {
            val target = (arguments["path"] ?: arguments["command"] ?: arguments["query"])?.toString()?.take(30)
            if (target != null) "$cleanName ( $target )" else cleanName
        }
    }
}

/**
 * Minimal reflection card inspired by top-tier coding agent workflows (Claude Code, OpenCode).
 * Shows the agent's continuous reflection, analysis, or next intent prefixed with the signature ●○● indicator.
 */
@Composable
fun AgentReflectionCard(
    reflection: AgentReflection,
    modifier: Modifier = Modifier
) {
    val reflectionColor = Color(0xFF818CF8) // Indigo/Violet accent
    var isExpanded by remember { mutableStateOf(false) }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 2.5.dp)
            .testTag("agent_reflection_card"),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        border = BorderStroke(
            width = 1.dp,
            color = reflectionColor.copy(alpha = 0.28f)
        )
    ) {
        Row(
            modifier = Modifier
                .clickable { isExpanded = !isExpanded }
                .padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Stylized ●○● indicator matching ToolExecutionCard
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.padding(top = 4.dp, end = 2.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(5.dp)
                        .background(reflectionColor, CircleShape)
                )
                Box(
                    modifier = Modifier
                        .size(5.dp)
                        .border(1.dp, reflectionColor, CircleShape)
                )
                Box(
                    modifier = Modifier
                        .size(5.dp)
                        .background(reflectionColor, CircleShape)
                )
            }

            Text(
                text = reflection.thought,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Normal,
                    fontSize = 11.5.sp,
                    lineHeight = 16.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.95f)
                ),
                maxLines = if (isExpanded) Int.MAX_VALUE else 4,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

