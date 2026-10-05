package com.example.ui.screens

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
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
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.TerminalLine
import com.example.data.model.TerminalLineType
import com.example.data.model.TerminalSessionState
import kotlinx.coroutines.launch

// =========================================================================
// MODERN DEVELOPER TERMINAL PALETTE (Clean, high-contrast, developer-grade)
// =========================================================================
private val TermBg = Color(0xFF090D14)
private val TermSurface = Color(0xFF111722)
private val TermElevated = Color(0xFF172030)
private val TermBorder = Color(0xFF222F44)
private val TermBorderSubtle = Color(0xFF1B2536)
private val TermGreen = Color(0xFF10B981)
private val TermCyan = Color(0xFF38BDF8)
private val TermAmber = Color(0xFFF59E0B)
private val TermRed = Color(0xFFF87171)
private val TermPurple = Color(0xFFC084FC)
private val TermPurplePill = Color(0xFF2A1B40)
private val TermTextPrimary = Color(0xFFF8FAFC)
private val TermTextSecondary = Color(0xFF94A3B8)
private val TermTextMuted = Color(0xFF64748B)

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

    // Auto-scroll smoothly to bottom on new output
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
            .background(TermBg)
    ) {
        // 1. Consolidated Terminal Workspace Header (Clean, professional, single row)
        TerminalHeaderBar(
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
                        TerminalLineType.INPUT -> "root@localhost:~# ${line.text}"
                        TerminalLineType.AGENT_COMMAND -> line.text
                        else -> line.text
                    }
                }
                clipboardManager.setText(AnnotatedString(fullText))
                Toast.makeText(context, "Terminal buffer copied (${filteredLines.size} lines)", Toast.LENGTH_SHORT).show()
            }
        )

        // 2. Active Agent Live Stream Banner (Clean purple card, visible when agent runs in terminal)
        AnimatedVisibility(
            visible = state.isAgentRunning,
            enter = fadeIn(tween(200)) + slideInVertically(tween(200)),
            exit = fadeOut(tween(150)) + slideOutVertically(tween(150))
        ) {
            TerminalAgentBanner(
                actionDescription = state.currentAgentAction,
                onViewAgentClick = onViewAgentClick
            )
        }

        // 3. Compact Stream Channel Filters ("All", "Shell", "Agent", "Errors")
        TerminalFilterDeck(
            selectedFilter = selectedFilter,
            onFilterSelected = { selectedFilter = it },
            allCount = state.lines.size,
            agentCount = state.lines.count { it.isAgent || it.type == TerminalLineType.AGENT_COMMAND || it.type == TerminalLineType.AGENT_OUTPUT || it.type == TerminalLineType.AGENT_STEP },
            shellCount = state.lines.count { !it.isAgent && it.type != TerminalLineType.AGENT_COMMAND && it.type != TerminalLineType.AGENT_OUTPUT },
            errorCount = state.lines.count { it.type == TerminalLineType.STDERR }
        )

        // 4. Quick Command Directives (neofetch, python3, apt, pip, etc.)
        TerminalDirectivesBar(
            enabled = !state.isRunning,
            onCommandClick = onSendCommand
        )

        // 5. Main Terminal Log Output (Maximizes screen space with high-contrast monospace rendering)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(TermBg)
                .padding(horizontal = 12.dp)
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("terminal_output_list"),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                if (filteredLines.isEmpty()) {
                    item {
                        TerminalWelcomeCard(
                            isInitialized = state.isEnvInitialized,
                            onInitClick = onReinitializeEnv
                        )
                    }
                }

                items(filteredLines, key = { it.id }) { line ->
                    TerminalLineRow(line = line)
                }

                if (state.isRunning) {
                    item {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(TermSurface)
                                .border(BorderStroke(1.dp, TermGreen.copy(alpha = 0.35f)), RoundedCornerShape(6.dp))
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(13.dp),
                                strokeWidth = 2.dp,
                                color = TermGreen
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Running command in Linux container...",
                                style = TextStyle(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = TermGreen
                                )
                            )
                        }
                    }
                }
            }

            // Scroll to latest log FAB (appears smoothly when user scrolled up)
            if (isScrolledUp) {
                SmallFloatingActionButton(
                    onClick = {
                        coroutineScope.launch {
                            if (filteredLines.isNotEmpty()) {
                                listState.animateScrollToItem(filteredLines.size - 1)
                            }
                        }
                    },
                    containerColor = TermElevated,
                    contentColor = TermCyan,
                    elevation = FloatingActionButtonDefaults.elevation(4.dp),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(bottom = 12.dp, end = 6.dp)
                        .border(1.dp, TermCyan.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.ArrowDownward,
                            contentDescription = "Scroll to bottom",
                            modifier = Modifier.size(13.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "LATEST",
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

        // 6. Bottom Terminal Controls: Accessory Keys + Command Input Dock
        // Protected with navigationBarsPadding() & imePadding() to prevent system bar and keyboard clipping
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(TermSurface)
                .navigationBarsPadding()
                .imePadding()
        ) {
            // Tactile Key Deck (ESC, TAB, CTRL+C, ▲, ▼, |, /, -, ~, &&, clear)
            TerminalKeyDeck(
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
                        "CTRL+C" -> onInputChange(state.currentInput + "^C")
                        else -> onInputChange(state.currentInput + key)
                    }
                }
            )

            // Command Input Deck
            TerminalInputDock(
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
}

@Composable
private fun TerminalHeaderBar(
    isRunning: Boolean,
    isAgentRunning: Boolean,
    isEnvInitialized: Boolean,
    isInitializing: Boolean,
    workingDir: String,
    onClearClick: () -> Unit,
    onReinitClick: () -> Unit,
    onCopyClick: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse_transition")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(700, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_alpha"
    )

    Surface(
        color = TermSurface,
        border = BorderStroke(1.dp, TermBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Left: Icon + Status + Working Directory
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(TermElevated)
                        .border(BorderStroke(1.dp, TermBorder), RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Terminal,
                        contentDescription = "Terminal Workspace",
                        tint = if (isAgentRunning) TermPurple else TermGreen,
                        modifier = Modifier.size(18.dp)
                    )
                }

                Spacer(modifier = Modifier.width(10.dp))

                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(
                                    when {
                                        isAgentRunning -> TermPurple
                                        isInitializing -> TermAmber
                                        isRunning -> TermGreen
                                        isEnvInitialized -> TermGreen
                                        else -> TermRed
                                    }
                                )
                                .alpha(if (isAgentRunning || isRunning || isInitializing) pulseAlpha else 1f)
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(
                            text = when {
                                isAgentRunning -> "Agent Active • Ubuntu 22.04"
                                isInitializing -> "Booting PRoot container..."
                                isRunning -> "Command Running..."
                                isEnvInitialized -> "Ubuntu 22.04 LTS Ready"
                                else -> "Container Offline"
                            },
                            style = TextStyle(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = when {
                                    isAgentRunning -> TermPurple
                                    isInitializing || isRunning -> TermAmber
                                    isEnvInitialized -> TermTextPrimary
                                    else -> TermRed
                                }
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 1.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Folder,
                            contentDescription = null,
                            tint = TermTextMuted,
                            modifier = Modifier.size(11.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = workingDir,
                            style = TextStyle(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                color = TermTextSecondary
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            // Right: Action buttons (Clear, Copy, Reinit) with standard 40dp minimum touch target
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Copy Buffer
                IconButton(
                    onClick = onCopyClick,
                    modifier = Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(TermElevated)
                        .border(BorderStroke(1.dp, TermBorder), RoundedCornerShape(6.dp))
                ) {
                    Icon(
                        imageVector = Icons.Outlined.ContentCopy,
                        contentDescription = "Copy Terminal Logs",
                        tint = TermTextSecondary,
                        modifier = Modifier.size(15.dp)
                    )
                }

                // Clear Buffer
                IconButton(
                    onClick = onClearClick,
                    modifier = Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(TermElevated)
                        .border(BorderStroke(1.dp, TermBorder), RoundedCornerShape(6.dp))
                ) {
                    Icon(
                        imageVector = Icons.Outlined.CleaningServices,
                        contentDescription = "Clear Terminal Screen",
                        tint = TermTextSecondary,
                        modifier = Modifier.size(15.dp)
                    )
                }

                // Re-initialize / Restart
                IconButton(
                    onClick = onReinitClick,
                    modifier = Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(TermElevated)
                        .border(BorderStroke(1.dp, TermBorder), RoundedCornerShape(6.dp)),
                    enabled = !isRunning && !isInitializing
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Refresh,
                        contentDescription = "Restart PRoot Environment",
                        tint = if (isInitializing) TermAmber else TermTextSecondary,
                        modifier = Modifier.size(15.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun TerminalAgentBanner(
    actionDescription: String,
    onViewAgentClick: (() -> Unit)?
) {
    Surface(
        color = TermPurplePill,
        border = BorderStroke(1.dp, TermPurple.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(13.dp),
                    strokeWidth = 2.dp,
                    color = TermPurple
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(
                        text = "AUTONOMOUS AGENT ACTIVE",
                        style = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.5.sp,
                            color = TermPurple,
                            letterSpacing = 0.5.sp
                        )
                    )
                    Text(
                        text = actionDescription.ifBlank { "Executing task step..." },
                        style = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            color = TermTextPrimary
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            if (onViewAgentClick != null) {
                Surface(
                    color = TermElevated,
                    shape = RoundedCornerShape(6.dp),
                    border = BorderStroke(1.dp, TermPurple.copy(alpha = 0.7f)),
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { onViewAgentClick() }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.SmartToy,
                            contentDescription = "View Agent Feed",
                            tint = TermPurple,
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Agent Feed ❯",
                            style = TextStyle(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = TermPurple
                            )
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TerminalFilterDeck(
    selectedFilter: String,
    onFilterSelected: (String) -> Unit,
    allCount: Int,
    agentCount: Int,
    shellCount: Int,
    errorCount: Int
) {
    val filters = listOf(
        "All" to allCount,
        "Shell" to shellCount,
        "Agent" to agentCount,
        "Errors" to errorCount
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(TermSurface)
            .border(BorderStroke(1.dp, TermBorderSubtle))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        filters.forEach { (name, count) ->
            val isSelected = selectedFilter == name
            val activeColor = when (name) {
                "Agent" -> TermPurple
                "Errors" -> TermRed
                else -> TermCyan
            }

            Surface(
                color = if (isSelected) TermElevated else Color.Transparent,
                shape = RoundedCornerShape(6.dp),
                border = BorderStroke(
                    width = 1.dp,
                    color = if (isSelected) activeColor else TermBorderSubtle
                ),
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { onFilterSelected(name) }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = name,
                        style = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.5.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            color = if (isSelected) activeColor else TermTextSecondary
                        )
                    )
                    Spacer(modifier = Modifier.width(5.dp))
                    Box(
                        modifier = Modifier
                            .background(
                                if (isSelected) activeColor.copy(alpha = 0.2f) else TermElevated,
                                RoundedCornerShape(4.dp)
                            )
                            .padding(horizontal = 4.dp, vertical = 1.dp)
                    ) {
                        Text(
                            text = "$count",
                            style = TextStyle(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 9.sp,
                                color = if (isSelected) activeColor else TermTextMuted,
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
private fun TerminalDirectivesBar(
    enabled: Boolean,
    onCommandClick: (String) -> Unit
) {
    val directives = listOf(
        "neofetch",
        "python3 --version",
        "pip list",
        "apt update",
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
            .background(TermSurface)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 10.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        directives.forEach { cmd ->
            Surface(
                color = TermElevated,
                shape = RoundedCornerShape(5.dp),
                border = BorderStroke(1.dp, TermBorder),
                modifier = Modifier
                    .clip(RoundedCornerShape(5.dp))
                    .clickable(enabled = enabled) { onCommandClick(cmd) }
            ) {
                Text(
                    text = "❯ $cmd",
                    style = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.5.sp,
                        color = TermCyan,
                        fontWeight = FontWeight.SemiBold
                    ),
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun TerminalLineRow(line: TerminalLine) {
    when (line.type) {
        TerminalLineType.AGENT_COMMAND -> {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(TermPurplePill)
                        .border(BorderStroke(0.5.dp, TermPurple.copy(alpha = 0.5f)), RoundedCornerShape(4.dp))
                        .padding(horizontal = 5.dp, vertical = 1.5.dp)
                ) {
                    Text(
                        text = "AGENT",
                        style = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = TermPurple
                        )
                    )
                }
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = line.text,
                    style = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = TermPurple
                    )
                )
            }
        }
        TerminalLineType.AGENT_STEP -> {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = line.text,
                    style = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TermCyan
                    )
                )
            }
        }
        TerminalLineType.AGENT_THOUGHT -> {
            Text(
                text = line.text,
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    fontStyle = FontStyle.Italic,
                    color = TermPurple.copy(alpha = 0.9f)
                ),
                modifier = Modifier.padding(vertical = 1.dp)
            )
        }
        TerminalLineType.AGENT_OUTPUT -> {
            Text(
                text = line.text,
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = TermTextPrimary
                ),
                modifier = Modifier.padding(vertical = 0.5.dp)
            )
        }
        TerminalLineType.INPUT -> {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "root@localhost:~# ",
                    style = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = TermGreen
                    )
                )
                Text(
                    text = line.text,
                    style = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TermCyan
                    )
                )
            }
        }
        TerminalLineType.STDOUT -> {
            Text(
                text = line.text,
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = TermTextPrimary
                ),
                modifier = Modifier.padding(vertical = 0.5.dp)
            )
        }
        TerminalLineType.STDERR -> {
            Text(
                text = line.text,
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = TermRed,
                    fontWeight = FontWeight.Medium
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .background(TermRed.copy(alpha = 0.1f), RoundedCornerShape(3.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }
        TerminalLineType.SYSTEM_INFO -> {
            Text(
                text = line.text,
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.5.sp,
                    color = TermAmber
                ),
                modifier = Modifier.padding(vertical = 1.dp)
            )
        }
        TerminalLineType.BANNER -> {
            Surface(
                color = TermSurface,
                shape = RoundedCornerShape(6.dp),
                border = BorderStroke(1.dp, if (line.isAgent) TermPurple.copy(alpha = 0.5f) else TermGreen.copy(alpha = 0.4f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
            ) {
                Text(
                    text = line.text,
                    style = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (line.isAgent) TermPurple else TermGreen
                    ),
                    modifier = Modifier.padding(10.dp)
                )
            }
        }
    }
}

@Composable
private fun TerminalWelcomeCard(
    isInitialized: Boolean,
    onInitClick: () -> Unit
) {
    Surface(
        color = TermSurface,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, TermBorder),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "UBUNTU 22.04 LTS TERMINAL",
                    style = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        color = TermGreen,
                        letterSpacing = 0.5.sp
                    )
                )
                Text(
                    text = "PRoot-Termux Engine",
                    style = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TermTextMuted
                    )
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Autonomous engineering environment with live rootless Linux subsystem on Android.",
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = TermTextSecondary
                )
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "• DIRECT SHELL: Run standard Linux commands (python3, pip, apt, bash scripts).\n" +
                    "• LIVE AGENT: Autonomous AI planning & execution output streams directly here.\n" +
                    "• WORKSPACE: Files created or modified in ~/workspace persist across sessions.",
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.5.sp,
                    color = TermTextMuted
                )
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(if (isInitialized) TermGreen else TermAmber)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (isInitialized) "SYSTEM STATE: READY" else "SYSTEM STATE: INITIALIZING CONTAINER...",
                    style = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isInitialized) TermGreen else TermAmber,
                        letterSpacing = 0.5.sp
                    )
                )
            }
        }
    }
}

@Composable
private fun TerminalKeyDeck(
    onKeyPress: (String) -> Unit
) {
    val keys = listOf("ESC", "TAB", "CTRL+C", "UP", "DOWN", "|", "/", "-", "~", "&&", "CLR")

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(TermSurface)
            .border(BorderStroke(1.dp, TermBorderSubtle))
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 6.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        keys.forEach { key ->
            val label = when (key) {
                "UP" -> "▲"
                "DOWN" -> "▼"
                "CLR" -> "CLEAR"
                else -> key
            }

            Surface(
                color = TermElevated,
                shape = RoundedCornerShape(6.dp),
                border = BorderStroke(1.dp, TermBorder),
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { onKeyPress(key) }
            ) {
                Text(
                    text = label,
                    style = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = when (key) {
                            "UP", "DOWN" -> TermGreen
                            "CLR" -> TermRed
                            "|" -> TermAmber
                            "ESC", "CTRL+C" -> TermCyan
                            else -> TermTextPrimary
                        }
                    ),
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                )
            }
        }
    }
}

@Composable
private fun TerminalInputDock(
    inputText: String,
    isRunning: Boolean,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit
) {
    Surface(
        color = TermBg,
        border = BorderStroke(1.dp, TermBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "❯ ",
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = TermGreen
                )
            )

            BasicTextField(
                value = inputText,
                onValueChange = onInputChange,
                textStyle = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    color = TermTextPrimary
                ),
                cursorBrush = SolidColor(TermGreen),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { onSend() }),
                modifier = Modifier
                    .weight(1f)
                    .testTag("terminal_input_field"),
                decorationBox = { innerTextField ->
                    if (inputText.isEmpty()) {
                        Text(
                            text = "enter command (e.g. neofetch, python3)...",
                            style = TextStyle(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                color = TermTextMuted
                            )
                        )
                    }
                    innerTextField()
                }
            )

            Spacer(modifier = Modifier.width(6.dp))

            IconButton(
                onClick = onSend,
                enabled = inputText.isNotBlank() && !isRunning,
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(
                        if (inputText.isNotBlank() && !isRunning) TermGreen.copy(alpha = 0.2f) else Color.Transparent
                    )
                    .border(
                        BorderStroke(
                            1.dp,
                            if (inputText.isNotBlank() && !isRunning) TermGreen else TermBorder
                        ),
                        RoundedCornerShape(6.dp)
                    )
                    .testTag("terminal_send_button")
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = "Execute Command",
                    tint = if (inputText.isNotBlank() && !isRunning) TermGreen else TermTextMuted,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}
