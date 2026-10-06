package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.HourglassTop
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.agent.plan.Subtask
import com.example.agent.plan.SubtaskStatus
import com.example.data.model.AgentSession
import com.example.data.model.AgentTaskStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Smartphone-first, calm, outcome-focused card summarizing the active agent task.
 *
 * Exposes:
 * - What is the agent doing?
 * - What is the current task?
 * - What has already been accomplished?
 * - What is next?
 * With clean expandable detail views and timeline when desired.
 */
@Composable
fun AgentTaskStateCard(
    session: AgentSession,
    onTogglePause: () -> Unit = {},
    onMarkCompleted: () -> Unit = {},
    onRetrySubtask: (String) -> Unit = {},
    onVerifySubtask: (String) -> Unit = {},
    isAlwaysAllowCommands: Boolean = true,
    onOpenCommandControl: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var isExpanded by remember { mutableStateOf(false) }
    var selectedTab by remember { mutableStateOf(0) } // 0: Milestones, 1: Timeline, 2: Deliverables
    val chevronRotation by animateFloatAsState(
        targetValue = if (isExpanded) 180f else 0f,
        animationSpec = tween(220),
        label = "task_state_chevron"
    )

    val plan = session.plan
    val activeSubtask = plan?.activeSubtask
    val completedCount = plan?.completedCount ?: 0
    val totalCount = plan?.totalCount ?: 0
    val progressFraction = if (totalCount > 0) (completedCount.toFloat() / totalCount.toFloat()).coerceIn(0f, 1f) else 0.1f

    val statusColor = when (session.status) {
        AgentTaskStatus.INITIALIZING, AgentTaskStatus.CREATED -> MaterialTheme.colorScheme.primary
        AgentTaskStatus.THINKING, AgentTaskStatus.PLANNING, AgentTaskStatus.REPLANNING -> Color(0xFF818CF8) // Indigo
        AgentTaskStatus.USING_TOOL, AgentTaskStatus.READY_TO_ACT, AgentTaskStatus.EXECUTING -> Color(0xFFF59E0B) // Amber
        AgentTaskStatus.OBSERVING, AgentTaskStatus.VERIFYING -> Color(0xFF38BDF8) // Sky blue
        AgentTaskStatus.IN_PROGRESS -> Color(0xFF38BDF8)
        AgentTaskStatus.COMPLETED -> Color(0xFF4ADE80) // Emerald green
        AgentTaskStatus.PAUSED, AgentTaskStatus.CANCELLED, AgentTaskStatus.WAITING_FOR_USER -> Color(0xFFFBBF24) // Amber
        AgentTaskStatus.FAILED, AgentTaskStatus.BLOCKED -> Color(0xFFF87171) // Red
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 4.dp)
            .testTag("agent_task_state_card"),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.22f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            // Header: Goal + Status badge + Pause button + Expand chevron
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { isExpanded = !isExpanded },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(statusColor.copy(alpha = 0.14f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.SmartToy,
                            contentDescription = "Agent",
                            tint = statusColor,
                            modifier = Modifier.size(19.dp)
                        )
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = session.goal,
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 14.5.sp
                            ),
                            maxLines = if (isExpanded) 3 else 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            // Status pill
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(statusColor.copy(alpha = 0.16f))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = session.status.displayName,
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        color = statusColor,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 10.5.sp
                                    )
                                )
                            }

                            if (totalCount > 0) {
                                Text(
                                    text = "• $completedCount of $totalCount milestones",
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                        fontSize = 11.sp
                                    )
                                )
                            }
                        }
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // Quick Pause / Resume button with 48dp touch target
                    IconButton(
                        onClick = onTogglePause,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("btn_task_toggle_pause")
                    ) {
                        Icon(
                            imageVector = if (session.status == AgentTaskStatus.PAUSED) Icons.Default.PlayArrow else Icons.Default.Pause,
                            contentDescription = if (session.status == AgentTaskStatus.PAUSED) "Resume Task" else "Pause Task",
                            tint = statusColor,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    // Expand / Collapse Chevron with 48dp touch target
                    IconButton(
                        onClick = { isExpanded = !isExpanded },
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("btn_task_expand_toggle")
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.KeyboardArrowDown,
                            contentDescription = if (isExpanded) "Collapse Details" else "Expand Details",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .size(20.dp)
                                .rotate(chevronRotation)
                        )
                    }
                }
            }

            // Minimal Progress bar
            if (totalCount > 0) {
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { progressFraction },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.5.dp)
                        .clip(RoundedCornerShape(2.dp)),
                    color = statusColor,
                    trackColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.18f)
                )
            }

            // Current Activity summary (Always visible, single calm line)
            if (activeSubtask != null && session.status != AgentTaskStatus.COMPLETED) {
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.HourglassTop,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(13.dp)
                    )
                    Text(
                        text = "Working on: ${activeSubtask.description}",
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // Expanded view: Outcomes, Milestones, and Timeline
            AnimatedVisibility(
                visible = isExpanded,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                ) {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f),
                        thickness = 1.dp
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    // Tab Selector: Milestones vs Activity Timeline
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilledTonalButton(
                            onClick = { selectedTab = 0 },
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = if (selectedTab == 0) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else Color.Transparent
                            ),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                text = "Milestones ($completedCount/$totalCount)",
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontWeight = if (selectedTab == 0) FontWeight.Bold else FontWeight.Normal,
                                    color = if (selectedTab == 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            )
                        }

                        FilledTonalButton(
                            onClick = { selectedTab = 1 },
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = if (selectedTab == 1) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else Color.Transparent
                            ),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                text = "Timeline",
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontWeight = if (selectedTab == 1) FontWeight.Bold else FontWeight.Normal,
                                    color = if (selectedTab == 1) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            )
                        }

                        if (session.artifacts.isNotEmpty()) {
                            FilledTonalButton(
                                onClick = { selectedTab = 2 },
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.filledTonalButtonColors(
                                    containerColor = if (selectedTab == 2) Color(0xFF10B981).copy(alpha = 0.18f) else Color.Transparent
                                ),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(
                                    text = "Deliverables (${session.artifacts.size})",
                                    style = MaterialTheme.typography.labelMedium.copy(
                                        fontWeight = if (selectedTab == 2) FontWeight.Bold else FontWeight.Normal,
                                        color = if (selectedTab == 2) Color(0xFF10B981) else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    if (selectedTab == 0) {
                        // Milestones list
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            plan?.subtasks?.forEach { subtask ->
                                MilestoneRow(
                                    subtask = subtask,
                                    isActive = subtask.id == plan.currentSubtaskId,
                                    onRetry = { onRetrySubtask(subtask.id) },
                                    onVerify = { onVerifySubtask(subtask.id) }
                                )
                            }
                        }
                    } else if (selectedTab == 1) {
                        // Clean Activity Timeline
                        TaskActivityTimeline(session = session)
                    } else {
                        // Deliverables list
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            session.artifacts.forEach { artifact ->
                                DeliverableRow(artifact = artifact)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Controls row at bottom of expanded sheet
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (session.status != AgentTaskStatus.COMPLETED) {
                            TextButton(
                                onClick = onMarkCompleted,
                                modifier = Modifier.testTag("btn_mark_task_completed")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Mark Completed")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MilestoneRow(
    subtask: Subtask,
    isActive: Boolean,
    onRetry: () -> Unit,
    onVerify: () -> Unit
) {
    val icon = when (subtask.status) {
        SubtaskStatus.COMPLETED -> Icons.Outlined.CheckCircle
        SubtaskStatus.RUNNING -> Icons.Outlined.HourglassTop
        SubtaskStatus.FAILED -> Icons.Outlined.ErrorOutline
        SubtaskStatus.PENDING -> Icons.Outlined.RadioButtonUnchecked
    }

    val iconColor = when (subtask.status) {
        SubtaskStatus.COMPLETED -> Color(0xFF4ADE80)
        SubtaskStatus.RUNNING -> MaterialTheme.colorScheme.primary
        SubtaskStatus.FAILED -> MaterialTheme.colorScheme.error
        SubtaskStatus.PENDING -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
    }

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (isActive) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f) else Color.Transparent,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconColor,
                    modifier = Modifier.size(17.dp)
                )

                Column {
                    Text(
                        text = subtask.description,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                            fontSize = 12.5.sp,
                            color = if (subtask.status == SubtaskStatus.COMPLETED) {
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                            } else MaterialTheme.colorScheme.onSurface
                        ),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )

                    if (!subtask.verificationResult.isNullOrBlank()) {
                        Text(
                            text = "Proof: ${subtask.verificationResult}",
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = Color(0xFF4ADE80),
                                fontSize = 10.5.sp
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    } else if (!subtask.result.isNullOrBlank()) {
                        Text(
                            text = subtask.result,
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                                fontSize = 10.5.sp
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            if (subtask.status == SubtaskStatus.FAILED) {
                IconButton(
                    onClick = onRetry,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Retry",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(15.dp)
                    )
                }
            }
        }
    }
}

/**
 * Clean activity timeline view showing only meaningful events (no trivial noise).
 */
@Composable
private fun TaskActivityTimeline(session: AgentSession) {
    val meaningfulEvents = remember(session.toolExecutions, session.reflections, session.plan) {
        val list = mutableListOf<TimelineEvent>()

        // 1. Goal Intake
        list.add(
            TimelineEvent(
                title = "Objective Formulated",
                detail = session.goal,
                timestamp = session.createdAt,
                type = TimelineType.OBJECTIVE
            )
        )

        // 2. Meaningful Tool Results (files created, commands verified)
        session.toolExecutions.forEach { record ->
            when (record.toolName.lowercase()) {
                "file_write", "file_patch" -> {
                    val file = (record.arguments["path"] ?: record.arguments["file"] ?: "file").toString()
                    list.add(
                        TimelineEvent(
                            title = if (record.isSuccess) "Artifact Created/Updated" else "File Operation Failed",
                            detail = file,
                            timestamp = record.timestamp,
                            type = if (record.isSuccess) TimelineType.ARTIFACT else TimelineType.FAILURE
                        )
                    )
                }
                "run_command", "python_execute" -> {
                    val cmd = (record.arguments["command"] ?: record.arguments["code"] ?: "").toString().take(40)
                    list.add(
                        TimelineEvent(
                            title = if (record.isSuccess) "Execution Verified" else "Execution Failed",
                            detail = cmd,
                            timestamp = record.timestamp,
                            type = if (record.isSuccess) TimelineType.VERIFICATION else TimelineType.FAILURE
                        )
                    )
                }
                "web_search", "web_open" -> {
                    val q = (record.arguments["query"] ?: record.arguments["url"] ?: "").toString().take(40)
                    list.add(
                        TimelineEvent(
                            title = "Discovered Information",
                            detail = q,
                            timestamp = record.timestamp,
                            type = TimelineType.DISCOVERY
                        )
                    )
                }
            }
        }

        // 3. Completed Sub-goals
        session.plan?.subtasks?.filter { it.status == SubtaskStatus.COMPLETED }?.forEach { sub ->
            list.add(
                TimelineEvent(
                    title = "Milestone Achieved",
                    detail = sub.description,
                    timestamp = sub.updatedAt,
                    type = TimelineType.MILESTONE
                )
            )
        }

        list.sortedBy { it.timestamp }.takeLast(8)
    }

    if (meaningfulEvents.isEmpty()) {
        Text(
            text = "No milestones recorded yet.",
            style = MaterialTheme.typography.bodySmall.copy(
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
        )
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            meaningfulEvents.forEach { event ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val badgeColor = when (event.type) {
                        TimelineType.OBJECTIVE -> MaterialTheme.colorScheme.primary
                        TimelineType.DISCOVERY -> Color(0xFF38BDF8)
                        TimelineType.ARTIFACT -> Color(0xFF818CF8)
                        TimelineType.VERIFICATION -> Color(0xFF4ADE80)
                        TimelineType.MILESTONE -> Color(0xFF4ADE80)
                        TimelineType.FAILURE -> MaterialTheme.colorScheme.error
                    }

                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(badgeColor)
                    )

                    Column {
                        Text(
                            text = event.title,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                                fontSize = 11.sp
                            )
                        )
                        Text(
                            text = event.detail,
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                                fontSize = 11.sp
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

private enum class TimelineType {
    OBJECTIVE,
    DISCOVERY,
    ARTIFACT,
    VERIFICATION,
    MILESTONE,
    FAILURE
}

private data class TimelineEvent(
    val title: String,
    val detail: String,
    val timestamp: Long,
    val type: TimelineType
)

@Composable
fun DeliverableRow(
    artifact: com.example.agent.artifact.Artifact,
    modifier: Modifier = Modifier
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)),
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f)
            ) {
                val icon = if (artifact.filename.endsWith(".docx", ignoreCase = true)) {
                    Icons.Outlined.Description
                } else if (artifact.filename.endsWith(".pdf", ignoreCase = true)) {
                    Icons.Outlined.Description
                } else {
                    Icons.AutoMirrored.Outlined.Article
                }

                val statusColor = if (artifact.valid) Color(0xFF10B981) else Color(0xFFF59E0B)

                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = statusColor,
                    modifier = Modifier.size(20.dp)
                )

                Column {
                    Text(
                        text = artifact.filename,
                        style = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = if (artifact.valid) "✓ Verified" else "Unverified",
                            style = TextStyle(
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.Medium,
                                color = statusColor
                            )
                        )
                        Text(
                            text = "• ${artifact.size} B",
                            style = TextStyle(
                                fontSize = 10.5.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        )
                    }
                }
            }

            IconButton(
                onClick = {
                    try {
                        val file = java.io.File(context.filesDir, "agent_workspaces/${artifact.taskId}/${artifact.path}")
                        val targetFile = if (file.exists()) file else java.io.File(context.filesDir, "agent_workspaces/default/${artifact.path}")
                        if (targetFile.exists()) {
                            val uri = androidx.core.content.FileProvider.getUriForFile(
                                context,
                                "${context.packageName}.fileprovider",
                                targetFile
                            )
                            val sendIntent = android.content.Intent().apply {
                                action = android.content.Intent.ACTION_SEND
                                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                                type = artifact.mimeType
                                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            val shareIntent = android.content.Intent.createChooser(sendIntent, "Share ${artifact.filename}")
                            shareIntent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            shareIntent.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            context.startActivity(shareIntent)
                        } else {
                            android.widget.Toast.makeText(context, "File path: ${artifact.path}", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: Exception) {
                        android.widget.Toast.makeText(context, "Cannot share: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                    }
                },
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.CheckCircle,
                    contentDescription = "Share or Open Deliverable",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}
