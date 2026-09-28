package com.example.data.model

import java.util.UUID

enum class TerminalLineType {
    INPUT,
    STDOUT,
    STDERR,
    SYSTEM_INFO,
    BANNER
}

data class TerminalLine(
    val id: String = UUID.randomUUID().toString(),
    val type: TerminalLineType,
    val text: String,
    val timestamp: Long = System.currentTimeMillis(),
    val exitCode: Int? = null
)

data class TerminalSessionState(
    val lines: List<TerminalLine> = emptyList(),
    val currentInput: String = "",
    val isRunning: Boolean = false,
    val workingDirectory: String = "~/workspace",
    val isEnvInitialized: Boolean = false,
    val isInitializingEnv: Boolean = false,
    val initLogs: List<String> = emptyList(),
    val history: List<String> = emptyList(),
    val historyIndex: Int = -1
)
