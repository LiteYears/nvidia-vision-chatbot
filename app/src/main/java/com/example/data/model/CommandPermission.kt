package com.example.data.model

data class PendingCommandPermission(
    val callId: String,
    val command: String,
    val workingDir: String,
    val onPermit: () -> Unit,
    val onAlwaysAllow: () -> Unit,
    val onDeny: () -> Unit
)
