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
import androidx.compose.material.icons.outlined.Terminal
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.AgentReflection
import com.example.data.model.ToolExecutionRecord

/**
 * Renders tool execution records.
 * For bash, terminal, and command execution, renders an authentic Ubuntu 22.04 LTS Terminal Window Card.
 * For supplementary tools, renders a compact, clean expandable tool card.
 */
@Composable
fun ToolExecutionCard(
    record: ToolExecutionRecord,
    modifier: Modifier = Modifier
) {
    val isWebTool = when (record.toolName.lowercase().trim()) {
        "web_search", "web_open" -> true
        else -> false
    }

    if (!isWebTool) {
        AgentTerminalCard(record = record, modifier = modifier)
    } else {
        StandardToolExecutionCard(record = record, modifier = modifier)
    }
}

/**
 * Authentic Ubuntu 22.04 LTS Terminal Window Card for commands executed by the agent on the Ubuntu system.
 */
@Composable
fun AgentTerminalCard(
    record: ToolExecutionRecord,
    modifier: Modifier = Modifier
) {
    val clipboardManager = LocalClipboardManager.current
    var hasCopied by remember { mutableStateOf(false) }
    var isExpanded by remember { mutableStateOf(true) }

    val rawCommand = (record.arguments["command"] ?: record.arguments["cmd"] ?: record.arguments["code"] ?: record.arguments["script_path"])?.toString()
        ?: if (record.arguments["path"] != null) "${record.toolName} ${record.arguments["path"]}" else record.toolName
    val output = (if (record.isSuccess) record.result else record.error) ?: ""
    val isSuccess = record.isSuccess
    val statusColor = if (isSuccess) Color(0xFF3FB950) else Color(0xFFF85149)

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 4.dp)
            .testTag("agent_terminal_card"),
        shape = RoundedCornerShape(10.dp),
        color = Color(0xFF0D1117), // Termux dark console
        border = BorderStroke(1.dp, Color(0xFF30363D))
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // 1. Linux Window Title Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF161B22))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Window control dots (Red, Yellow, Green) + Prompt Location
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Box(modifier = Modifier.size(9.dp).background(Color(0xFFFF5F56), CircleShape))
                    Box(modifier = Modifier.size(9.dp).background(Color(0xFFFFBD2E), CircleShape))
                    Box(modifier = Modifier.size(9.dp).background(Color(0xFF27C93F), CircleShape))

                    Spacer(modifier = Modifier.width(6.dp))

                    Icon(
                        imageVector = Icons.Outlined.Terminal,
                        contentDescription = null,
                        tint = Color(0xFF3FB950),
                        modifier = Modifier.size(13.dp)
                    )

                    Text(
                        text = "ubuntu@termux: ~/workspace",
                        style = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 11.sp,
                            color = Color(0xFFE6EDF3)
                        )
                    )
                }

                // Exit Code Status + Copy Button
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = statusColor.copy(alpha = 0.15f),
                        border = BorderStroke(1.dp, statusColor.copy(alpha = 0.4f))
                    ) {
                        Text(
                            text = if (isSuccess) "exit 0" else "exit 1",
                            style = TextStyle(
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                fontSize = 9.sp,
                                color = statusColor
                            ),
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                        )
                    }

                    IconButton(
                        onClick = {
                            val copyContent = "ubuntu@termux:~$ $rawCommand\n$output"
                            clipboardManager.setText(AnnotatedString(copyContent))
                            hasCopied = true
                        },
                        modifier = Modifier.size(22.dp)
                    ) {
                        Icon(
                            imageVector = if (hasCopied) Icons.Default.Check else Icons.Outlined.ContentCopy,
                            contentDescription = "Copy terminal output",
                            tint = if (hasCopied) Color(0xFF3FB950) else Color.LightGray,
                            modifier = Modifier.size(12.dp)
                        )
                    }
                }
            }

            // 2. Terminal Console Body
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                // Command input line: ubuntu@termux:~$ <cmd>
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top
                ) {
                    Text(
                        text = "ubuntu@termux:~$ ",
                        style = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.5.sp,
                            color = Color(0xFF3FB950)
                        )
                    )
                    Text(
                        text = rawCommand,
                        style = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 11.5.sp,
                            color = Color(0xFF58A6FF)
                        )
                    )
                }

                // Output text
                if (output.isNotBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = output.trimEnd(),
                        style = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.5.sp,
                            lineHeight = 14.5.sp,
                            color = if (isSuccess) Color(0xFFE6EDF3) else Color(0xFFF85149)
                        ),
                        maxLines = if (isExpanded) 30 else 6,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun StandardToolExecutionCard(
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

