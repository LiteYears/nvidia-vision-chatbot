package com.example.ui.screens

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.TerminalLine
import com.example.data.model.TerminalLineType
import com.example.data.model.TerminalSessionState

private val TermuxBgColor = Color(0xFF0D1117)
private val TermuxSurfaceColor = Color(0xFF161B22)
private val TermuxBorderColor = Color(0xFF30363D)
private val TermuxPromptColor = Color(0xFF3FB950)
private val TermuxCommandColor = Color(0xFF58A6FF)
private val TermuxStdoutColor = Color(0xFFE6EDF3)
private val TermuxStderrColor = Color(0xFFF85149)
private val TermuxNoticeColor = Color(0xFFD29922)
private val TermuxKeyBgColor = Color(0xFF21262D)

@Composable
fun TerminalScreen(
    state: TerminalSessionState,
    onSendCommand: (String) -> Unit,
    onInputChange: (String) -> Unit,
    onClearTerminal: () -> Unit,
    onReinitializeEnv: () -> Unit,
    onHistoryNavigate: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current

    // Auto-scroll to bottom on new output
    LaunchedEffect(state.lines.size, state.isRunning) {
        if (state.lines.isNotEmpty()) {
            listState.animateScrollToItem(state.lines.size - 1)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(TermuxBgColor)
            .imePadding()
    ) {
        // 1. Termux Header & Status Bar
        TerminalHeaderBar(
            isRunning = state.isRunning,
            isEnvInitialized = state.isEnvInitialized,
            isInitializing = state.isInitializingEnv,
            workingDir = state.workingDirectory,
            onClearClick = onClearTerminal,
            onReinitClick = onReinitializeEnv,
            onCopyClick = {
                val fullText = state.lines.joinToString("\n") { line ->
                    when (line.type) {
                        TerminalLineType.INPUT -> "ubuntu@termux:~$ ${line.text}"
                        else -> line.text
                    }
                }
                clipboardManager.setText(AnnotatedString(fullText))
                Toast.makeText(context, "Terminal buffer copied to clipboard", Toast.LENGTH_SHORT).show()
            }
        )

        // 2. Quick Command Chips
        QuickCommandChipsRow(
            enabled = !state.isRunning,
            onCommandClick = onSendCommand
        )

        // 3. Terminal Output Stream
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(TermuxBgColor)
                .padding(horizontal = 12.dp)
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("terminal_output_list"),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                // If brand new or empty, show welcome banner
                if (state.lines.isEmpty()) {
                    item {
                        DefaultWelcomeBanner(
                            isInitialized = state.isEnvInitialized,
                            onInitClick = onReinitializeEnv
                        )
                    }
                }

                items(state.lines, key = { it.id }) { line ->
                    TerminalLineItem(line = line)
                }

                if (state.isRunning) {
                    item {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(vertical = 4.dp)
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(12.dp),
                                strokeWidth = 2.dp,
                                color = TermuxPromptColor
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Executing process on Linux kernel...",
                                style = TextStyle(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp,
                                    color = TermuxNoticeColor
                                )
                            )
                        }
                    }
                }
            }
        }

        // 4. Termux Quick Keys Accessory Bar (ESC, TAB, CTRL, ALT, |, /, ~, ↑, ↓, CLR)
        TermuxQuickKeyBar(
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

        // 5. Command Input Row
        TerminalInputRow(
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
private fun TerminalHeaderBar(
    isRunning: Boolean,
    isEnvInitialized: Boolean,
    isInitializing: Boolean,
    workingDir: String,
    onClearClick: () -> Unit,
    onReinitClick: () -> Unit,
    onCopyClick: () -> Unit
) {
    Surface(
        color = TermuxSurfaceColor,
        border = androidx.compose.foundation.BorderStroke(1.dp, TermuxBorderColor),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = Icons.Outlined.Terminal,
                    contentDescription = "Termux Terminal",
                    tint = TermuxPromptColor,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Ubuntu 22.04 LTS",
                            style = TextStyle(
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                color = TermuxStdoutColor
                            )
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(
                                    when {
                                        isInitializing -> TermuxNoticeColor
                                        isRunning -> TermuxNoticeColor
                                        isEnvInitialized -> TermuxPromptColor
                                        else -> TermuxStderrColor
                                    }
                                )
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Outlined.Folder,
                            contentDescription = null,
                            tint = Color.Gray,
                            modifier = Modifier.size(11.dp)
                        )
                        Spacer(modifier = Modifier.width(3.dp))
                        Text(
                            text = workingDir,
                            style = TextStyle(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                color = Color.Gray
                            )
                        )
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                IconButton(
                    onClick = onReinitClick,
                    modifier = Modifier.size(32.dp),
                    enabled = !isRunning && !isInitializing
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Refresh,
                        contentDescription = "Reinitialize Environment",
                        tint = if (isInitializing) TermuxNoticeColor else Color.LightGray,
                        modifier = Modifier.size(16.dp)
                    )
                }

                IconButton(
                    onClick = onCopyClick,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.ContentCopy,
                        contentDescription = "Copy Output",
                        tint = Color.LightGray,
                        modifier = Modifier.size(16.dp)
                    )
                }

                IconButton(
                    onClick = onClearClick,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.CleaningServices,
                        contentDescription = "Clear Terminal",
                        tint = Color.LightGray,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun QuickCommandChipsRow(
    enabled: Boolean,
    onCommandClick: (String) -> Unit
) {
    val quickCommands = listOf(
        "neofetch",
        "apt update",
        "apt list",
        "uname -a",
        "cat /etc/os-release",
        "ls -la",
        "env",
        "df -h",
        "free -m",
        "ps aux",
        "python3 --version",
        "proot-distro status ubuntu"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(TermuxSurfaceColor)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        quickCommands.forEach { cmd ->
            Surface(
                color = TermuxKeyBgColor,
                shape = RoundedCornerShape(12.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, TermuxBorderColor),
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(enabled = enabled) { onCommandClick(cmd) }
            ) {
                Text(
                    text = cmd,
                    style = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = TermuxCommandColor,
                        fontWeight = FontWeight.Medium
                    ),
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun TerminalLineItem(line: TerminalLine) {
    when (line.type) {
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
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = TermuxPromptColor
                    )
                )
                Text(
                    text = line.text,
                    style = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TermuxCommandColor
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
                    color = TermuxStdoutColor
                ),
                modifier = Modifier.padding(vertical = 1.dp)
            )
        }
        TerminalLineType.STDERR -> {
            Text(
                text = line.text,
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = TermuxStderrColor
                ),
                modifier = Modifier.padding(vertical = 1.dp)
            )
        }
        TerminalLineType.SYSTEM_INFO, TerminalLineType.BANNER -> {
            Text(
                text = line.text,
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = TermuxNoticeColor
                ),
                modifier = Modifier.padding(vertical = 1.dp)
            )
        }
    }
}

@Composable
private fun DefaultWelcomeBanner(
    isInitialized: Boolean,
    onInitClick: () -> Unit
) {
    Surface(
        color = TermuxSurfaceColor,
        shape = RoundedCornerShape(8.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, TermuxBorderColor),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "=== Ubuntu 22.04 LTS (Termux Rootless Linux) ===",
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = TermuxPromptColor
                )
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Real Linux process execution powered by Android kernel shell (/system/bin/sh).",
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = TermuxStdoutColor
                )
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "• Package Management: apt update, apt install -y <pkg>, pkg, dpkg -l\n" +
                    "• Utilities: proot, proot-distro, python3, pip, neofetch, uname, ls, curl\n" +
                    "• Full Pipelines & Redirection: cmd1 | cmd2, cmd > file.txt, cmd1 && cmd2",
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = Color.LightGray
                )
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = if (isInitialized) "Status: Environment Initialized & Ready" else "Status: Initializing environment...",
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isInitialized) TermuxPromptColor else TermuxNoticeColor
                )
            )
        }
    }
}

@Composable
private fun TermuxQuickKeyBar(
    onKeyPress: (String) -> Unit
) {
    val keys = listOf("ESC", "TAB", "CTRL", "ALT", "-", "/", "|", "~", "&&", "UP", "DOWN", "CLR")

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(TermuxSurfaceColor)
            .border(1.dp, TermuxBorderColor)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 4.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        keys.forEach { key ->
            val label = when (key) {
                "UP" -> "↑"
                "DOWN" -> "↓"
                else -> key
            }

            Surface(
                color = TermuxKeyBgColor,
                shape = RoundedCornerShape(6.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, TermuxBorderColor),
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { onKeyPress(key) }
            ) {
                Text(
                    text = label,
                    style = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = when (key) {
                            "UP", "DOWN" -> TermuxPromptColor
                            "CLR" -> TermuxStderrColor
                            "|" -> TermuxNoticeColor
                            else -> TermuxStdoutColor
                        }
                    ),
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }
        }
    }
}

@Composable
private fun TerminalInputRow(
    inputText: String,
    isRunning: Boolean,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit
) {
    Surface(
        color = TermuxBgColor,
        border = androidx.compose.foundation.BorderStroke(1.dp, TermuxBorderColor),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "$ ",
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = TermuxPromptColor
                )
            )

            BasicTextField(
                value = inputText,
                onValueChange = onInputChange,
                textStyle = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    color = TermuxStdoutColor
                ),
                cursorBrush = SolidColor(TermuxPromptColor),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { onSend() }),
                modifier = Modifier
                    .weight(1f)
                    .testTag("terminal_input_field"),
                decorationBox = { innerTextField ->
                    if (inputText.isEmpty()) {
                        Text(
                            text = "type Linux command (e.g. neofetch, apt update)...",
                            style = TextStyle(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                color = Color.DarkGray
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
                    .size(36.dp)
                    .testTag("terminal_send_button")
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = "Execute Command",
                    tint = if (inputText.isNotBlank() && !isRunning) TermuxPromptColor else Color.Gray,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}
