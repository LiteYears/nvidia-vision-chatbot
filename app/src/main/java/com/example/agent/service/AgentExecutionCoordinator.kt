package com.example.agent.service

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.example.agent.plan.TaskPlan
import com.example.data.model.AgentReflection
import com.example.data.model.AgentSession
import com.example.data.model.AgentStep
import com.example.data.model.AgentTaskStatus
import com.example.data.model.ChatMessage
import com.example.data.model.MessageRole
import com.example.data.model.TerminalLine
import com.example.data.model.TerminalLineType
import com.example.data.model.ToolExecutionRecord
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Shared state coordinator and bridge between [AgentExecutionService], [com.example.ui.viewmodel.ChatViewModel], and UI.
 *
 * Maintains the live single source of truth for the active autonomous agent session across
 * Android foreground service lifecycles, configuration changes, and app backgrounding.
 */
object AgentExecutionCoordinator {

    private val _activeSession = MutableStateFlow<AgentSession?>(null)
    val activeSession: StateFlow<AgentSession?> = _activeSession.asStateFlow()

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _currentStatus = MutableStateFlow(AgentTaskStatus.INITIALIZING)
    val currentStatus: StateFlow<AgentTaskStatus> = _currentStatus.asStateFlow()

    private val _currentActionDescription = MutableStateFlow("")
    val currentActionDescription: StateFlow<String> = _currentActionDescription.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    // Live terminal events channel for piping agent execution into the Linux Terminal UI
    private val _terminalEvents = MutableSharedFlow<TerminalLine>(extraBufferCapacity = 500)
    val terminalEvents: SharedFlow<TerminalLine> = _terminalEvents.asSharedFlow()

    fun postTerminalEvent(line: TerminalLine) {
        _terminalEvents.tryEmit(line)
    }

    fun setActiveSession(session: AgentSession?) {
        _activeSession.value = session
        if (session != null) {
            _currentStatus.value = session.status
        }
    }

    fun startSession(
        context: Context,
        session: AgentSession,
        userMessage: ChatMessage,
        initialSteps: List<AgentStep>? = null
    ) {
        _activeSession.value = session
        _isRunning.value = true
        _currentStatus.value = AgentTaskStatus.IN_PROGRESS
        _errorMessage.value = null
        _currentActionDescription.value = "Starting agent execution..."

        postTerminalEvent(
            TerminalLine(
                type = TerminalLineType.BANNER,
                text = "════════════════════════════════════════════════════════\n" +
                        "▶ [AGENT TASK STARTED] ${userMessage.content.trim().take(70)}\n" +
                        "  Model: ${session.modelUsed} | Environment: Ubuntu 22.04 LTS\n" +
                        "════════════════════════════════════════════════════════",
                isAgent = true
            )
        )

        val intent = Intent(context, AgentExecutionService::class.java).apply {
            action = AgentExecutionService.ACTION_START_AGENT
            putExtra(AgentExecutionService.EXTRA_SESSION_ID, session.id)
            putExtra(AgentExecutionService.EXTRA_USER_MESSAGE_ID, userMessage.id)
            putExtra(AgentExecutionService.EXTRA_USER_MESSAGE_CONTENT, userMessage.content)
            putExtra(AgentExecutionService.EXTRA_MODEL, session.modelUsed)
        }
        ContextCompat.startForegroundService(context, intent)
    }

    fun sendFollowUp(
        context: Context,
        session: AgentSession,
        userMessage: ChatMessage
    ) {
        _activeSession.value = session
        _isRunning.value = true
        _currentStatus.value = AgentTaskStatus.IN_PROGRESS
        _errorMessage.value = null
        _currentActionDescription.value = "Processing follow-up command..."

        postTerminalEvent(
            TerminalLine(
                type = TerminalLineType.BANNER,
                text = "▶ [AGENT RESUMING] Follow-up: ${userMessage.content.trim().take(70)}",
                isAgent = true
            )
        )

        val intent = Intent(context, AgentExecutionService::class.java).apply {
            action = AgentExecutionService.ACTION_FOLLOW_UP
            putExtra(AgentExecutionService.EXTRA_SESSION_ID, session.id)
            putExtra(AgentExecutionService.EXTRA_USER_MESSAGE_ID, userMessage.id)
            putExtra(AgentExecutionService.EXTRA_USER_MESSAGE_CONTENT, userMessage.content)
            putExtra(AgentExecutionService.EXTRA_MODEL, session.modelUsed)
        }
        ContextCompat.startForegroundService(context, intent)
    }

    fun stopExecution(context: Context) {
        val intent = Intent(context, AgentExecutionService::class.java).apply {
            action = AgentExecutionService.ACTION_STOP_AGENT
        }
        context.startService(intent)
        _isRunning.value = false
        _currentStatus.value = AgentTaskStatus.PAUSED
        _currentActionDescription.value = "Task paused"

        postTerminalEvent(
            TerminalLine(
                type = TerminalLineType.SYSTEM_INFO,
                text = "⏸ [AGENT PAUSED] Autonomous execution paused by user.",
                isAgent = true
            )
        )

        _activeSession.update { current ->
            current?.copy(
                status = AgentTaskStatus.PAUSED,
                updatedAt = System.currentTimeMillis()
            )
        }
    }

    fun updateStatus(sessionId: String, status: AgentTaskStatus, actionDescription: String) {
        _currentStatus.value = status
        _currentActionDescription.value = actionDescription
        _activeSession.update { current ->
            if (current?.id == sessionId) {
                current.copy(
                    status = status,
                    updatedAt = System.currentTimeMillis()
                )
            } else {
                current
            }
        }
        if (actionDescription.isNotBlank()) {
            postTerminalEvent(
                TerminalLine(
                    type = TerminalLineType.AGENT_STEP,
                    text = "● [AGENT] $actionDescription",
                    isAgent = true
                )
            )
        }
    }

    fun updatePlan(sessionId: String, plan: TaskPlan) {
        _activeSession.update { current ->
            if (current?.id == sessionId) {
                val updatedStatus = if (plan.isCompleted) AgentTaskStatus.COMPLETED else current.status
                current.copy(
                    plan = plan,
                    status = updatedStatus,
                    updatedAt = System.currentTimeMillis()
                )
            } else {
                current
            }
        }
    }

    fun addReflection(sessionId: String, thought: String, timestamp: Long = System.currentTimeMillis()) {
        val cleanThought = thought.trim()
        if (cleanThought.isBlank()) return
        _activeSession.update { current ->
            if (current?.id == sessionId) {
                if (current.reflections.lastOrNull()?.thought == cleanThought) return@update current
                val reflection = AgentReflection(
                    thought = cleanThought,
                    timestamp = timestamp
                )
                current.copy(
                    reflections = current.reflections + reflection,
                    updatedAt = System.currentTimeMillis()
                )
            } else {
                current
            }
        }
        postTerminalEvent(
            TerminalLine(
                type = TerminalLineType.AGENT_THOUGHT,
                text = "💬 [AGENT THOUGHT] ${cleanThought.take(160)}...",
                isAgent = true
            )
        )
    }

    fun addToolExecution(sessionId: String, record: ToolExecutionRecord) {
        _activeSession.update { current ->
            if (current?.id == sessionId) {
                current.copy(
                    toolExecutions = current.toolExecutions + record,
                    updatedAt = System.currentTimeMillis()
                )
            } else {
                current
            }
        }

        // Live stream agent tool execution into the Linux terminal buffer
        when (record.toolName.lowercase()) {
            "run_command", "bash", "terminal", "sh", "cmd", "exec" -> {
                val cmd = (record.arguments["command"] ?: record.arguments["cmd"] ?: "").toString()
                postTerminalEvent(
                    TerminalLine(
                        type = TerminalLineType.AGENT_COMMAND,
                        text = "agent@termux:~$ $cmd",
                        isAgent = true,
                        tag = "RUN_COMMAND"
                    )
                )
                if (!record.result.isNullOrBlank()) {
                    record.result.lines().take(80).forEach { line ->
                        postTerminalEvent(
                            TerminalLine(
                                type = TerminalLineType.AGENT_OUTPUT,
                                text = line,
                                isAgent = true
                            )
                        )
                    }
                }
                if (!record.error.isNullOrBlank()) {
                    record.error.lines().take(40).forEach { line ->
                        postTerminalEvent(
                            TerminalLine(
                                type = TerminalLineType.STDERR,
                                text = line,
                                isAgent = true
                            )
                        )
                    }
                }
            }
            "python_execute" -> {
                val code = (record.arguments["code"] ?: "").toString()
                val preview = code.lines().firstOrNull()?.take(55) ?: "python script"
                postTerminalEvent(
                    TerminalLine(
                        type = TerminalLineType.AGENT_COMMAND,
                        text = "agent@termux:~$ python3 -c \"$preview...\"",
                        isAgent = true,
                        tag = "PYTHON"
                    )
                )
                if (!record.result.isNullOrBlank()) {
                    record.result.lines().take(80).forEach { line ->
                        postTerminalEvent(
                            TerminalLine(
                                type = TerminalLineType.AGENT_OUTPUT,
                                text = line,
                                isAgent = true
                            )
                        )
                    }
                }
                if (!record.error.isNullOrBlank()) {
                    record.error.lines().take(40).forEach { line ->
                        postTerminalEvent(
                            TerminalLine(
                                type = TerminalLineType.STDERR,
                                text = line,
                                isAgent = true
                            )
                        )
                    }
                }
            }
            "file_write", "file_patch" -> {
                val path = (record.arguments["path"] ?: record.arguments["file"] ?: "").toString()
                val status = if (record.isSuccess) "✓ Created/Patched" else "✗ Failed writing"
                postTerminalEvent(
                    TerminalLine(
                        type = TerminalLineType.SYSTEM_INFO,
                        text = "📝 [AGENT FILE] $status: $path",
                        isAgent = true
                    )
                )
            }
            "file_read" -> {
                val path = (record.arguments["path"] ?: record.arguments["file"] ?: "").toString()
                postTerminalEvent(
                    TerminalLine(
                        type = TerminalLineType.SYSTEM_INFO,
                        text = "📖 [AGENT FILE] Inspected: $path",
                        isAgent = true
                    )
                )
            }
            "file_delete" -> {
                val path = (record.arguments["path"] ?: record.arguments["file"] ?: "").toString()
                postTerminalEvent(
                    TerminalLine(
                        type = TerminalLineType.SYSTEM_INFO,
                        text = "🗑 [AGENT FILE] Deleted: $path",
                        isAgent = true
                    )
                )
            }
            else -> {
                val summary = (record.arguments["query"] ?: record.arguments["url"] ?: record.arguments["command"] ?: "").toString().take(60)
                postTerminalEvent(
                    TerminalLine(
                        type = TerminalLineType.SYSTEM_INFO,
                        text = "⚙ [AGENT TOOL: ${record.toolName}] $summary ${if (record.isSuccess) "✓" else "✗"}",
                        isAgent = true
                    )
                )
            }
        }
    }

    fun updateStreamingContent(sessionId: String, assistantMessageId: String, content: String) {
        _activeSession.update { current ->
            if (current?.id == sessionId) {
                val exists = current.messages.any { it.id == assistantMessageId }
                val updatedMessages = if (exists) {
                    current.messages.map { msg ->
                        if (msg.id == assistantMessageId) {
                            msg.copy(content = content, isStreaming = true)
                        } else msg
                    }
                } else {
                    current.messages + ChatMessage(
                        id = assistantMessageId,
                        conversationId = sessionId,
                        role = MessageRole.ASSISTANT,
                        content = content,
                        isStreaming = true,
                        modelUsed = current.modelUsed
                    )
                }
                current.copy(
                    messages = updatedMessages,
                    updatedAt = System.currentTimeMillis()
                )
            } else {
                current
            }
        }
    }

    fun completeSession(
        sessionId: String,
        finalAssistantMessage: ChatMessage,
        updatedSteps: List<AgentStep>?,
        finalPlan: TaskPlan?
    ) {
        _isRunning.value = false
        _currentStatus.value = AgentTaskStatus.COMPLETED
        _currentActionDescription.value = "Task completed successfully"

        postTerminalEvent(
            TerminalLine(
                type = TerminalLineType.BANNER,
                text = "════════════════════════════════════════════════════════\n" +
                        "✔ [AGENT TASK COMPLETED] Objective successfully achieved.\n" +
                        "════════════════════════════════════════════════════════",
                isAgent = true
            )
        )

        _activeSession.update { current ->
            if (current?.id == sessionId) {
                val exists = current.messages.any { it.id == finalAssistantMessage.id }
                val updatedMessages = if (exists) {
                    current.messages.map { msg ->
                        if (msg.id == finalAssistantMessage.id) finalAssistantMessage else msg
                    }
                } else {
                    current.messages + finalAssistantMessage
                }
                val finalSteps = updatedSteps ?: current.steps.map { it.copy(status = AgentTaskStatus.COMPLETED) }
                current.copy(
                    status = AgentTaskStatus.COMPLETED,
                    steps = finalSteps,
                    plan = finalPlan ?: current.plan?.copy(isCompleted = true),
                    messages = updatedMessages,
                    updatedAt = System.currentTimeMillis()
                )
            } else {
                current
            }
        }
    }

    fun failSession(
        sessionId: String,
        assistantMessageId: String,
        streamingAssistantMessage: ChatMessage,
        error: Throwable
    ) {
        _isRunning.value = false
        _currentStatus.value = AgentTaskStatus.FAILED
        val errorText = error.message ?: "Could not complete autonomous agent loop."
        _errorMessage.value = errorText
        _currentActionDescription.value = "Task failed: $errorText"

        postTerminalEvent(
            TerminalLine(
                type = TerminalLineType.STDERR,
                text = "✖ [AGENT FAILED] $errorText",
                isAgent = true
            )
        )

        val errorMessage = streamingAssistantMessage.copy(
            content = "Task Error: $errorText",
            isStreaming = false,
            isError = true
        )

        _activeSession.update { current ->
            if (current?.id == sessionId) {
                val exists = current.messages.any { it.id == assistantMessageId }
                val updatedMessages = if (exists) {
                    current.messages.map { msg ->
                        if (msg.id == assistantMessageId) errorMessage else msg
                    }
                } else {
                    current.messages + errorMessage
                }
                current.copy(
                    status = AgentTaskStatus.FAILED,
                    messages = updatedMessages,
                    updatedAt = System.currentTimeMillis()
                )
            } else {
                current
            }
        }
    }

    fun notifyPaused(sessionId: String) {
        _isRunning.value = false
        _currentStatus.value = AgentTaskStatus.PAUSED
        _currentActionDescription.value = "Task paused"
        _activeSession.update { current ->
            if (current?.id == sessionId) {
                val updatedMessages = current.messages.map { msg ->
                    if (msg.isStreaming) msg.copy(isStreaming = false) else msg
                }.filterNot { it.isStreaming && it.content.isBlank() }
                current.copy(
                    messages = updatedMessages,
                    status = AgentTaskStatus.PAUSED,
                    updatedAt = System.currentTimeMillis()
                )
            } else {
                current
            }
        }
    }
}
