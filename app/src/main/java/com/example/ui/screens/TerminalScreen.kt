package com.example.ui.screens

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.TerminalLine
import com.example.data.model.TerminalLineType
import com.example.data.model.TerminalSessionState
import kotlinx.coroutines.launch

// ==========================================
// MODERN WARFARE TACTICAL PALETTE & HUD THEME
// ==========================================
private val WarfareBg = Color(0xFF090C10)
private val WarfarePanelBg = Color(0xFF10141C)
private val WarfarePanelSurface = Color(0xFF161C26)
private val WarfareBorder = Color(0xFF222F3E)
private val WarfareBorderSubtle = Color(0xFF1A232E)
private val WarfareTacticalGreen = Color(0xFF00FF88)
private val WarfareTacticalCyan = Color(0xFF00E5FF)
private val WarfareTacticalAmber = Color(0xFFF59E0B)
private val WarfareTacticalRed = Color(0xFFFF3B30)
private val WarfareAgentPurple = Color(0xFFC084FC)
private val WarfareAgentPill = Color(0xFF2D164D)
private val WarfareTextPrimary = Color(0xFFF1F5F9)
private val WarfareTextSecondary = Color(0xFF94A3B8)
private val WarfareTextMuted = Color(0xFF64748B)

@Composable
fun TerminalScreen(
    state: TerminalSessionState,
    onSendCommand: (String) -> Unit,
    onInputChange: (String) -> Unit,
    onClearTerminal: () -> Unit,
    onReinitializeEnv: () -> Unit,
    onHistoryNavigate: (Int) -> Unit,
    onViewAgentClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val coroutineScope = rememberCoroutineScope()

    var selectedFilter by remember { mutableStateOf("All") }

    val filteredLines = remember(state.lines, selectedFilter) {
        when (selectedFilter) {
            "Agent" -> state.lines.filter {
                it.isAgent ||
                    it.type == TerminalLineType.AGENT_COMMAND ||
                    it.type == TerminalLineType.AGENT_OUTPUT ||
                    it.type == TerminalLineType.AGENT_STEP ||
                    it.type == TerminalLineType.AGENT_THOUGHT
            }
            "Shell" -> state.lines.filter {
                !it.isAgent &&
                    it.type != TerminalLineType.AGENT_COMMAND &&
                    it.type != TerminalLineType.AGENT_OUTPUT &&
                    it.type != TerminalLineType.AGENT_STEP &&
                    it.type != TerminalLineType.AGENT_THOUGHT
            }
            "Errors" -> state.lines.filter { it.type == TerminalLineType.STDERR }
            else -> state.lines
        }
    }

    // Auto-scroll to bottom on new output
    LaunchedEffect(filteredLines.size, state.isRunning, state.isAgentRunning) {
        if (filteredLines.isNotEmpty()) {
            listState.animateScrollToItem(filteredLines.size - 1)
        }
    }

    val isScrolledUp by remember {
        derivedStateOf {
            val totalItems = listState.layoutInfo.totalItemsCount
            if (totalItems == 0) false
            else {
                val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                lastVisible < totalItems - 3
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(WarfareBg)
            .imePadding()
    ) {
        // 1. Modern Warfare Tactical Telemetry & C2 Header Bar
        TacticalHeaderBar(
            isRunning = state.isRunning,
            isAgentRunning = state.isAgentRunning,
            isEnvInitialized = state.isEnvInitialized,
            isInitializing = state.isInitializingEnv,
            workingDir = state.workingDirectory,
            onClearClick = onClearTerminal,
            onReinitClick = onReinitializeEnv,
            onCopyClick = {
                val fullText = filteredLines.joinToString("\n") { line ->
                    when (line.type) {
                        TerminalLineType.INPUT -> "ubuntu@termux:~$ ${line.text}"
                        TerminalLineType.AGENT_COMMAND -> line.text
                        else -> line.text
                    }
                }
                clipboardManager.setText(AnnotatedString(fullText))
                Toast.makeText(context, "Tactical buffer exported (${filteredLines.size} lines)", Toast.LENGTH_SHORT).show()
            }
        )

        // 2. Active Agent Warfare Execution HUD Banner (Live Streamed)
        AnimatedVisibility(
            visible = state.isAgentRunning,
            enter = fadeIn() + slideInVertically(),
            exit = fadeOut() + slideOutVertically()
        ) {
            TacticalAgentMissionBanner(
                actionDescription = state.currentAgentAction,
                onViewAgentClick = onViewAgentClick
            )
        }

        // 3. Military Communication Channels / Telemetry Filters
        TacticalChannelFilterDeck(
            selectedFilter = selectedFilter,
            onFilterSelected = { selectedFilter = it },
            allCount = state.lines.size,
            agentCount = state.lines.count { it.isAgent || it.type == TerminalLineType.AGENT_COMMAND || it.type == TerminalLineType.AGENT_OUTPUT || it.type == TerminalLineType.AGENT_STEP },
            shellCount = state.lines.count { !it.isAgent && it.type != TerminalLineType.AGENT_COMMAND && it.type != TerminalLineType.AGENT_OUTPUT },
            errorCount = state.lines.count { it.type == TerminalLineType.STDERR }
        )

        // 4. Quick Combat Directive Chips
        TacticalDirectivesRow(
            enabled = !state.isRunning,
            onCommandClick = onSendCommand
        )

        // 5. Tactical Terminal Stream Console
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(WarfareBg)
                .padding(horizontal = 10.dp)
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("terminal_output_list"),
                contentPadding = PaddingValues(vertical = 6.dp)
            ) {
                // If clean or newly opened buffer, display military boot banner
                if (filteredLines.isEmpty()) {
                    item {
                        TacticalWelcomeBanner(
                            isInitialized = state.isEnvInitialized,
                            onInitClick = onReinitializeEnv
                        )
                    }
                }

                items(filteredLines, key = { it.id }) { line ->
                    TacticalLineItem(line = line)
                }

                if (state.isRunning) {
                    item {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp, horizontal = 4.dp)
                                .background(WarfarePanelBg, RoundedCornerShape(4.dp))
                                .border(BorderStroke(1.dp, WarfareTacticalGreen.copy(alpha = 0.3f)), RoundedCornerShape(4.dp))
                                .padding(horizontal = 8.dp, vertical = 5.dp)
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(12.dp),
                                strokeWidth = 2.dp,
                                color = WarfareTacticalGreen
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "SYS//EXEC: Dispatched to kernel sub-process...",
                                style = TextStyle(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = WarfareTacticalGreen
                                )
                            )
                        }
                    }
                }
            }

            // Scroll to bottom HUD pill
            if (isScrolledUp) {
                SmallFloatingActionButton(
                    onClick = {
                        coroutineScope.launch {
                            if (filteredLines.isNotEmpty()) {
                                listState.animateScrollToItem(filteredLines.size - 1)
                            }
                        }
                    },
                    containerColor = WarfarePanelSurface,
                    contentColor = WarfareTacticalCyan,
                    elevation = FloatingActionButtonDefaults.elevation(4.dp),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(bottom = 8.dp, end = 4.dp)
                        .border(1.dp, WarfareTacticalCyan.copy(alpha = 0.4f), RoundedCornerShape(6.dp))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.ArrowDownward,
                            contentDescription = "Scroll to bottom",
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "LATEST LOGS",
                            style = TextStyle(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.5.sp
                            )
                        )
                    }
                }
            }
        }

        // 6. Tactical Soft-Keys Accessory Deck
        TacticalKeyDeckBar(
            onKeyPress = { key ->
                when (key) {
                    "UP" -> onHistoryNavigate(-1)
                    "DOWN" -> onHistoryNavigate(1)
                    "CLR" -> onClearTerminal()
                    "TAB" -> onInputChange(state.currentInput + "    ")
                    "|" -> onInputChange(state.currentInput + " | ")
                    "/" -> onInputChange(state.currentInput + "/")
                    "-" -> onInputChange(state.currentInput + "-")
                    "~" -> onInputChange(state.currentInput + "~")
                    "&&" -> onInputChange(state.currentInput + " && ")
                    "ESC" -> onInputChange("")
                    "CTRL" -> onInputChange(state.currentInput + "^C")
                    else -> onInputChange(state.currentInput + key)
                }
            }
        )

        // 7. Tactical Command Input Deck
        TacticalInputDeck(
            inputText = state.currentInput,
            isRunning = state.isRunning,
            onInputChange = onInputChange,
            onSend = {
                if (state.currentInput.isNotBlank()) {
                    onSendCommand(state.currentInput)
                }
            }
        )
    }
}

@Composable
private fun TacticalHeaderBar(
    isRunning: Boolean,
    isAgentRunning: Boolean,
    isEnvInitialized: Boolean,
    isInitializing: Boolean,
    workingDir: String,
    onClearClick: () -> Unit,
    onReinitClick: () -> Unit,
    onCopyClick: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "hud_beacon")
    val beaconAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800),
            repeatMode = RepeatMode.Reverse
        ),
        label = "beacon_pulse"
    )

    Surface(
        color = WarfarePanelBg,
        border = BorderStroke(1.dp, WarfareBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Top HUD Telemetry strip
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(WarfarePanelSurface)
                    .padding(horizontal = 10.dp, vertical = 3.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(
                                when {
                                    isAgentRunning -> WarfareAgentPurple
                                    isInitializing -> WarfareTacticalAmber
                                    isRunning -> WarfareTacticalGreen
                                    isEnvInitialized -> WarfareTacticalGreen
                                    else -> WarfareTacticalRed
                                }
                            )
                            .alpha(if (isAgentRunning || isRunning) beaconAlpha else 1f)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = when {
                            isAgentRunning -> "AGENT OPS // AUTONOMOUS LINK"
                            isInitializing -> "SYS//BOOTING: INITIALIZING PROOT"
                            isRunning -> "C2//BUSY: EXEC RUNNING"
                            isEnvInitialized -> "NET: SECURE // UBUNTU 22.04 LTS"
                            else -> "NET: OFFLINE // ENVIRONMENT ERROR"
                        },
                        style = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 9.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = when {
                                isAgentRunning -> WarfareAgentPurple
                                isInitializing || isRunning -> WarfareTacticalAmber
                                isEnvInitialized -> WarfareTacticalGreen
                                else -> WarfareTacticalRed
                            },
                            letterSpacing = 0.5.sp
                        )
                    )
                }

                Text(
                    text = "CALLSIGN: TF-AI // PROOT",
                    style = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 9.sp,
                        color = WarfareTextMuted,
                        fontWeight = FontWeight.SemiBold
                    )
                )
            }

            // Main command bar row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .background(WarfarePanelSurface, RoundedCornerShape(4.dp))
                            .border(BorderStroke(1.dp, WarfareBorder), RoundedCornerShape(4.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Terminal,
                            contentDescription = "Tactical Terminal",
                            tint = if (isAgentRunning) WarfareAgentPurple else WarfareTacticalGreen,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "TERMINAL // ROOT SHELL",
                                style = TextStyle(
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp,
                                    color = WarfareTextPrimary,
                                    letterSpacing = 0.5.sp
                                )
                            )
                            if (isAgentRunning) {
                                Spacer(modifier = Modifier.width(6.dp))
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(3.dp))
                                        .background(WarfareAgentPill)
                                        .border(BorderStroke(0.5.dp, WarfareAgentPurple.copy(alpha = 0.5f)), RoundedCornerShape(3.dp))
                                        .padding(horizontal = 4.dp, vertical = 1.dp)
                                ) {
                                    Text(
                                        text = "LIVE AGENT",
                                        style = TextStyle(
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 8.5.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = WarfareAgentPurple
                                        )
                                    )
                                }
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Outlined.Folder,
                                contentDescription = null,
                                tint = WarfareTextMuted,
                                modifier = Modifier.size(10.dp)
                            )
                            Spacer(modifier = Modifier.width(3.dp))
                            Text(
                                text = workingDir,
                                style = TextStyle(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 9.5.sp,
                                    color = WarfareTextSecondary
                                ),
                                maxLines = 1
                            )
                        }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    // Reinit action
                    IconButton(
                        onClick = onReinitClick,
                        modifier = Modifier
                            .size(30.dp)
                            .background(WarfarePanelSurface, RoundedCornerShape(4.dp))
                            .border(BorderStroke(1.dp, WarfareBorder), RoundedCornerShape(4.dp)),
                        enabled = !isRunning && !isInitializing
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Refresh,
                            contentDescription = "Reboot Environment",
                            tint = if (isInitializing) WarfareTacticalAmber else WarfareTextSecondary,
                            modifier = Modifier.size(14.dp)
                        )
                    }

                    // Copy action
                    IconButton(
                        onClick = onCopyClick,
                        modifier = Modifier
                            .size(30.dp)
                            .background(WarfarePanelSurface, RoundedCornerShape(4.dp))
                            .border(BorderStroke(1.dp, WarfareBorder), RoundedCornerShape(4.dp))
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.ContentCopy,
                            contentDescription = "Copy Tactical Buffer",
                            tint = WarfareTextSecondary,
                            modifier = Modifier.size(14.dp)
                        )
                    }

                    // Clear action
                    IconButton(
                        onClick = onClearClick,
                        modifier = Modifier
                            .size(30.dp)
                            .background(WarfarePanelSurface, RoundedCornerShape(4.dp))
                            .border(BorderStroke(1.dp, WarfareBorder), RoundedCornerShape(4.dp))
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.CleaningServices,
                            contentDescription = "Purge Buffer",
                            tint = WarfareTextSecondary,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TacticalAgentMissionBanner(
    actionDescription: String,
    onViewAgentClick: (() -> Unit)?
) {
    Surface(
        color = WarfarePanelBg,
        border = BorderStroke(1.dp, WarfareAgentPurple.copy(alpha = 0.6f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(12.dp),
                    strokeWidth = 2.dp,
                    color = WarfareAgentPurple
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(
                        text = "▶ AUTONOMOUS AGENT ACTIVE IN TERMINAL",
                        style = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.5.sp,
                            color = WarfareAgentPurple,
                            letterSpacing = 0.5.sp
                        )
                    )
                    Text(
                        text = actionDescription.ifBlank { "Executing autonomous plan sequence..." },
                        style = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 9.5.sp,
                            color = WarfareTextPrimary
                        ),
                        maxLines = 1
                    )
                }
            }

            if (onViewAgentClick != null) {
                Surface(
                    color = WarfareAgentPill,
                    shape = RoundedCornerShape(4.dp),
                    border = BorderStroke(1.dp, WarfareAgentPurple.copy(alpha = 0.7f)),
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .clickable { onViewAgentClick() }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.SmartToy,
                            contentDescription = "View Agent Feed",
                            tint = WarfareAgentPurple,
                            modifier = Modifier.size(11.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "AGENT FEED ❯",
                            style = TextStyle(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 9.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = WarfareAgentPurple
                            )
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TacticalChannelFilterDeck(
    selectedFilter: String,
    onFilterSelected: (String) -> Unit,
    allCount: Int,
    agentCount: Int,
    shellCount: Int,
    errorCount: Int
) {
    val filters = listOf(
        "All" to allCount,
        "Agent" to agentCount,
        "Shell" to shellCount,
        "Errors" to errorCount
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(WarfarePanelBg)
            .border(BorderStroke(1.dp, WarfareBorderSubtle))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        filters.forEachIndexed { index, (name, count) ->
            val isSelected = selectedFilter == name
            val channelCode = "CH-0${index + 1}"

            Surface(
                color = if (isSelected) WarfarePanelSurface else Color.Transparent,
                shape = RoundedCornerShape(4.dp),
                border = BorderStroke(
                    width = 1.dp,
                    color = if (isSelected) {
                        when (name) {
                            "Agent" -> WarfareAgentPurple
                            "Errors" -> WarfareTacticalRed
                            else -> WarfareTacticalCyan
                        }
                    } else WarfareBorderSubtle
                ),
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .clickable { onFilterSelected(name) }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "$channelCode $name",
                        style = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            color = when {
                                name == "Agent" && isSelected -> WarfareAgentPurple
                                name == "Errors" && count > 0 -> WarfareTacticalRed
                                isSelected -> WarfareTacticalCyan
                                else -> WarfareTextMuted
                            }
                        )
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Box(
                        modifier = Modifier
                            .background(
                                if (isSelected) WarfareBorder else WarfarePanelBg,
                                RoundedCornerShape(3.dp)
                            )
                            .padding(horizontal = 3.dp, vertical = 1.dp)
                    ) {
                        Text(
                            text = "$count",
                            style = TextStyle(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 8.5.sp,
                                color = if (isSelected) WarfareTextPrimary else WarfareTextMuted,
                                fontWeight = FontWeight.Bold
                            )
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TacticalDirectivesRow(
    enabled: Boolean,
    onCommandClick: (String) -> Unit
) {
    val quickDirectives = listOf(
        "neofetch",
        "./start-ubuntu22.sh",
        "apt update",
        "pkg update -y",
        "pip list",
        "python3 --version",
        "ls -la",
        "cat requirements.txt",
        "uname -a",
        "ps aux",
        "df -h",
        "free -m"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(WarfarePanelBg)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        quickDirectives.forEach { cmd ->
            Surface(
                color = WarfarePanelSurface,
                shape = RoundedCornerShape(4.dp),
                border = BorderStroke(1.dp, WarfareBorder),
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .clickable(enabled = enabled) { onCommandClick(cmd) }
            ) {
                Text(
                    text = "❯ $cmd",
                    style = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        color = WarfareTacticalCyan,
                        fontWeight = FontWeight.SemiBold
                    ),
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                )
            }
        }
    }
}

@Composable
private fun TacticalLineItem(line: TerminalLine) {
    when (line.type) {
        TerminalLineType.AGENT_COMMAND -> {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(3.dp))
                        .background(WarfareAgentPill)
                        .border(BorderStroke(0.5.dp, WarfareAgentPurple.copy(alpha = 0.5f)), RoundedCornerShape(3.dp))
                        .padding(horizontal = 4.dp, vertical = 1.dp)
                ) {
                    Text(
                        text = "AGENT",
                        style = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 8.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = WarfareAgentPurple
                        )
                    )
                }
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = line.text,
                    style = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = WarfareAgentPurple
                    )
                )
            }
        }
        TerminalLineType.AGENT_STEP -> {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp)
            ) {
                Text(
                    text = line.text,
                    style = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = WarfareTacticalCyan
                    )
                )
            }
        }
        TerminalLineType.AGENT_THOUGHT -> {
            Text(
                text = line.text,
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.5.sp,
                    fontStyle = FontStyle.Italic,
                    color = WarfareAgentPurple.copy(alpha = 0.9f)
                ),
                modifier = Modifier.padding(vertical = 1.dp)
            )
        }
        TerminalLineType.AGENT_OUTPUT -> {
            Text(
                text = line.text,
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.5.sp,
                    color = WarfareTextPrimary
                ),
                modifier = Modifier.padding(vertical = 0.5.dp)
            )
        }
        TerminalLineType.INPUT -> {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp)
            ) {
                Text(
                    text = "ubuntu@termux:~$ ",
                    style = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = WarfareTacticalGreen
                    )
                )
                Text(
                    text = line.text,
                    style = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = WarfareTacticalCyan
                    )
                )
            }
        }
        TerminalLineType.STDOUT -> {
            Text(
                text = line.text,
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.5.sp,
                    color = WarfareTextPrimary
                ),
                modifier = Modifier.padding(vertical = 0.5.dp)
            )
        }
        TerminalLineType.STDERR -> {
            Text(
                text = line.text,
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.5.sp,
                    color = WarfareTacticalRed,
                    fontWeight = FontWeight.Medium
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .background(WarfareTacticalRed.copy(alpha = 0.08f), RoundedCornerShape(2.dp))
                    .padding(horizontal = 4.dp, vertical = 1.dp)
            )
        }
        TerminalLineType.SYSTEM_INFO -> {
            Text(
                text = line.text,
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = WarfareTacticalAmber
                ),
                modifier = Modifier.padding(vertical = 1.dp)
            )
        }
        TerminalLineType.BANNER -> {
            Surface(
                color = WarfarePanelBg,
                shape = RoundedCornerShape(4.dp),
                border = BorderStroke(1.dp, if (line.isAgent) WarfareAgentPurple.copy(alpha = 0.6f) else WarfareTacticalGreen.copy(alpha = 0.5f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 3.dp)
            ) {
                Text(
                    text = line.text,
                    style = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (line.isAgent) WarfareAgentPurple else WarfareTacticalGreen
                    ),
                    modifier = Modifier.padding(8.dp)
                )
            }
        }
    }
}

@Composable
private fun TacticalWelcomeBanner(
    isInitialized: Boolean,
    onInitClick: () -> Unit
) {
    Surface(
        color = WarfarePanelBg,
        shape = RoundedCornerShape(6.dp),
        border = BorderStroke(1.dp, WarfareBorder),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "┌ [ C2 // TACTICAL LINUX TERMINAL ]",
                    style = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.5.sp,
                        color = WarfareTacticalGreen,
                        letterSpacing = 0.5.sp
                    )
                )
                Text(
                    text = "UBUNTU 22.04 LTS",
                    style = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = WarfareTextMuted
                    )
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Autonomous Agentic AI Environment with PRoot-Termux sandboxed container.",
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    color = WarfareTextSecondary
                )
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "• DIRECT OPS: Agent tool execution (bash, Python scripts, file writes) streams live.\n" +
                    "• WARFARE DIRECTIVES: Run apt, pip, python3, bash scripts, and inspect system telemetry.\n" +
                    "• INTEGRATED C2: Output is piped into autonomous task monitoring in real time.",
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.5.sp,
                    color = WarfareTextMuted
                )
            )
            Spacer(modifier = Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(if (isInitialized) WarfareTacticalGreen else WarfareTacticalAmber)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (isInitialized) "SYSTEM STATE: ONLINE & READY" else "SYSTEM STATE: INITIALIZING...",
                    style = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isInitialized) WarfareTacticalGreen else WarfareTacticalAmber,
                        letterSpacing = 0.5.sp
                    )
                )
            }
        }
    }
}

@Composable
private fun TacticalKeyDeckBar(
    onKeyPress: (String) -> Unit
) {
    val tacticalKeys = listOf("ESC", "TAB", "CTRL", "ALT", "-", "/", "|", "~", "&&", "UP", "DOWN", "CLR")

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(WarfarePanelBg)
            .border(BorderStroke(1.dp, WarfareBorder))
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 4.dp, vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        tacticalKeys.forEach { key ->
            val label = when (key) {
                "UP" -> "▲"
                "DOWN" -> "▼"
                else -> key
            }

            Surface(
                color = WarfarePanelSurface,
                shape = RoundedCornerShape(4.dp),
                border = BorderStroke(1.dp, WarfareBorder),
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .clickable { onKeyPress(key) }
            ) {
                Text(
                    text = label,
                    style = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = when (key) {
                            "UP", "DOWN" -> WarfareTacticalGreen
                            "CLR" -> WarfareTacticalRed
                            "|" -> WarfareTacticalAmber
                            "ESC", "CTRL" -> WarfareTacticalCyan
                            else -> WarfareTextPrimary
                        }
                    ),
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun TacticalInputDeck(
    inputText: String,
    isRunning: Boolean,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit
) {
    Surface(
        color = WarfareBg,
        border = BorderStroke(1.dp, WarfareBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "❯ ",
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = WarfareTacticalGreen
                )
            )

            BasicTextField(
                value = inputText,
                onValueChange = onInputChange,
                textStyle = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = WarfareTextPrimary
                ),
                cursorBrush = SolidColor(WarfareTacticalGreen),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { onSend() }),
                modifier = Modifier
                    .weight(1f)
                    .testTag("terminal_input_field"),
                decorationBox = { innerTextField ->
                    if (inputText.isEmpty()) {
                        Text(
                            text = "enter tactical directive (e.g. neofetch, apt update)...",
                            style = TextStyle(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                color = WarfareTextMuted
                            )
                        )
                    }
                    innerTextField()
                }
            )

            IconButton(
                onClick = onSend,
                enabled = inputText.isNotBlank() && !isRunning,
                modifier = Modifier
                    .size(32.dp)
                    .background(
                        if (inputText.isNotBlank() && !isRunning) WarfareTacticalGreen.copy(alpha = 0.2f) else Color.Transparent,
                        RoundedCornerShape(4.dp)
                    )
                    .border(
                        BorderStroke(
                            1.dp,
                            if (inputText.isNotBlank() && !isRunning) WarfareTacticalGreen else WarfareBorder
                        ),
                        RoundedCornerShape(4.dp)
                    )
                    .testTag("terminal_send_button")
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = "Execute Command",
                    tint = if (inputText.isNotBlank() && !isRunning) WarfareTacticalGreen else WarfareTextMuted,
                    modifier = Modifier.size(15.dp)
                )
            }
        }
    }
}
