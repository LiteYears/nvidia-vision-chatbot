package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.HourglassTop
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material3.Icon
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.AgentSession
import com.example.data.model.AgentStep
import com.example.data.model.AgentTaskStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun AgentTaskStateCard(
    session: AgentSession,
    onTogglePause: () -> Unit = {},
    onMarkCompleted: () -> Unit = {},
    isAlwaysAllowCommands: Boolean = true,
    onOpenCommandControl: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var isExpanded by remember { mutableStateOf(true) }

    val statusColor = when (session.status) {
        AgentTaskStatus.INITIALIZING -> MaterialTheme.colorScheme.primary
        AgentTaskStatus.THINKING -> Color(0xFF818CF8) // Indigo
        AgentTaskStatus.USING_TOOL -> Color(0xFFF59E0B) // Amber
        AgentTaskStatus.OBSERVING -> Color(0xFF38BDF8) // Sky blue
        AgentTaskStatus.IN_PROGRESS -> Color(0xFF38BDF8)
        AgentTaskStatus.COMPLETED -> Color(0xFF4ADE80) // Emerald green
        AgentTaskStatus.PAUSED -> Color(0xFFFBBF24) // Amber
        AgentTaskStatus.FAILED -> Color(0xFFF87171) // Red
    }

    val formattedTime = remember(session.createdAt) {
        SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(session.createdAt))
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .testTag("agent_task_state_card"),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
        )
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // Header Row: Session ID & Status Badge & Expand toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.SmartToy,
                            contentDescription = "Agent",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                    }

                    Column {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "TASK #${session.id.take(8).uppercase()}",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 0.5.sp,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            )
                            Text(
                                text = "• $formattedTime",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                    fontSize = 11.sp
                                )
                            )
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Text(
                                text = session.modelUsed.substringAfterLast("/"),
                                style = MaterialTheme.typography.labelSmall.copy(
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                    fontSize = 10.5.sp
                                )
                            )
                            Text(
                                text = "•",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                    fontSize = 10.sp
                                )
                            )
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable { onOpenCommandControl() }
                                    .padding(horizontal = 4.dp, vertical = 1.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(3.dp)
                            ) {
                                Text(
                                    text = if (isAlwaysAllowCommands) "⚡ Shell: Always Allow" else "⚡ Shell: Ask Permission",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        color = if (isAlwaysAllowCommands) MaterialTheme.colorScheme.primary else Color(0xFFF59E0B),
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 10.sp
                                    )
                                )
                            }
                        }
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // Status Badge
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = statusColor.copy(alpha = 0.15f),
                        border = BorderStroke(1.dp, statusColor.copy(alpha = 0.4f))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .background(statusColor, CircleShape)
                            )
                            Text(
                                text = session.status.displayName.uppercase(),
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = statusColor,
                                    fontSize = 10.sp,
                                    letterSpacing = 0.4.sp
                                )
                            )
                        }
                    }

                    // Expand / Collapse Toggle
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier
                            .size(26.dp)
                            .clip(CircleShape)
                            .clickable { isExpanded = !isExpanded },
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = if (isExpanded) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown,
                                contentDescription = if (isExpanded) "Collapse" else "Expand",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // User Goal Section
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Flag,
                        contentDescription = "Goal",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .size(16.dp)
                            .padding(top = 2.dp)
                    )
                    Column {
                        Text(
                            text = "GOAL",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 10.sp,
                                letterSpacing = 0.8.sp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        )
                        Text(
                            text = session.goal,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface,
                                fontSize = 13.5.sp,
                                lineHeight = 18.sp
                            )
                        )
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(8.dp))

            // Live State Progression Pipeline: THINKING → USING TOOL → OBSERVING → COMPLETED/FAILED
            AgentStatePipelineView(currentStatus = session.status)

            // Expandable Steps & State Controls
            AnimatedVisibility(visible = isExpanded) {
                Column(modifier = Modifier.padding(top = 10.dp)) {
                    if (session.steps.isNotEmpty()) {
                        Text(
                            text = "TASK PIPELINE",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 10.sp,
                                letterSpacing = 0.9.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                            ),
                            modifier = Modifier.padding(start = 2.dp, bottom = 6.dp)
                        )

                        session.steps.forEachIndexed { index, step ->
                            AgentStepRow(
                                stepNumber = index + 1,
                                step = step
                            )
                            if (index < session.steps.size - 1) {
                                Spacer(modifier = Modifier.height(4.dp))
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Action buttons: Pause/Resume and Mark Complete
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Pause / Resume Button
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)),
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { onTogglePause() }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = if (session.status == AgentTaskStatus.PAUSED) Icons.Default.PlayArrow else Icons.Default.Pause,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(14.dp)
                                )
                                Text(
                                    text = if (session.status == AgentTaskStatus.PAUSED) "Resume" else "Pause",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        // Complete Button
                        if (session.status != AgentTaskStatus.COMPLETED) {
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = Color(0xFF4ADE80).copy(alpha = 0.15f),
                                border = BorderStroke(1.dp, Color(0xFF4ADE80).copy(alpha = 0.4f)),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable { onMarkCompleted() }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = null,
                                        tint = Color(0xFF4ADE80),
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Text(
                                        text = "Complete",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontWeight = FontWeight.SemiBold,
                                            color = Color(0xFF4ADE80)
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
}

@Composable
private fun AgentStepRow(
    stepNumber: Int,
    step: AgentStep
) {
    val stepColor = when (step.status) {
        AgentTaskStatus.COMPLETED -> Color(0xFF4ADE80)
        AgentTaskStatus.THINKING -> Color(0xFF818CF8)
        AgentTaskStatus.USING_TOOL -> Color(0xFFF59E0B)
        AgentTaskStatus.OBSERVING -> Color(0xFF38BDF8)
        AgentTaskStatus.IN_PROGRESS -> Color(0xFF38BDF8)
        AgentTaskStatus.PAUSED -> Color(0xFFFBBF24)
        AgentTaskStatus.FAILED -> Color(0xFFF87171)
        AgentTaskStatus.INITIALIZING -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.weight(1f)
        ) {
            Box(
                modifier = Modifier
                    .size(18.dp)
                    .background(stepColor.copy(alpha = 0.15f), CircleShape)
                    .border(1.dp, stepColor.copy(alpha = 0.5f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                if (step.status == AgentTaskStatus.COMPLETED) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = "Done",
                        tint = stepColor,
                        modifier = Modifier.size(11.dp)
                    )
                } else {
                    Text(
                        text = "$stepNumber",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 9.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = stepColor
                        )
                    )
                }
            }

            Column {
                Text(
                    text = step.title,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontWeight = if (step.status == AgentTaskStatus.IN_PROGRESS || step.status == AgentTaskStatus.THINKING || step.status == AgentTaskStatus.USING_TOOL) FontWeight.SemiBold else FontWeight.Normal,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 12.5.sp
                    )
                )
                if (step.description.isNotBlank()) {
                    Text(
                        text = step.description,
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                            fontSize = 10.5.sp
                        )
                    )
                }
            }
        }

        Text(
            text = step.status.displayName,
            style = MaterialTheme.typography.labelSmall.copy(
                color = stepColor,
                fontWeight = FontWeight.Medium,
                fontSize = 10.5.sp
            )
        )
    }
}

@Composable
fun AgentStatePipelineView(
    currentStatus: AgentTaskStatus,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)),
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            val isThinkingActive = currentStatus == AgentTaskStatus.THINKING
            val isUsingToolActive = currentStatus == AgentTaskStatus.USING_TOOL
            val isObservingActive = currentStatus == AgentTaskStatus.OBSERVING
            val isTerminal = currentStatus == AgentTaskStatus.COMPLETED || currentStatus == AgentTaskStatus.FAILED

            StatePill(
                label = "THINKING",
                isActive = isThinkingActive,
                activeColor = Color(0xFF818CF8)
            )

            StateArrow()

            StatePill(
                label = "USING TOOL",
                isActive = isUsingToolActive,
                activeColor = Color(0xFFF59E0B)
            )

            StateArrow()

            StatePill(
                label = "OBSERVING",
                isActive = isObservingActive,
                activeColor = Color(0xFF38BDF8)
            )

            StateArrow()

            StatePill(
                label = if (currentStatus == AgentTaskStatus.FAILED) "FAILED" else "COMPLETED",
                isActive = isTerminal,
                activeColor = if (currentStatus == AgentTaskStatus.FAILED) Color(0xFFF87171) else Color(0xFF4ADE80)
            )
        }
    }
}

@Composable
private fun StatePill(
    label: String,
    isActive: Boolean,
    activeColor: Color
) {
    val textColor = if (isActive) activeColor else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
    val dotColor = if (isActive) activeColor else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
    val bgColor = if (isActive) activeColor.copy(alpha = 0.16f) else Color.Transparent
    val borderStroke = if (isActive) BorderStroke(1.dp, activeColor.copy(alpha = 0.5f)) else null

    Surface(
        shape = RoundedCornerShape(6.dp),
        color = bgColor,
        border = borderStroke
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.5.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(5.dp)
                    .background(dotColor, CircleShape)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium,
                    fontSize = 9.sp,
                    color = textColor,
                    letterSpacing = 0.3.sp
                )
            )
        }
    }
}

@Composable
private fun StateArrow() {
    Text(
        text = "→",
        style = MaterialTheme.typography.labelSmall.copy(
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold
        )
    )
}
