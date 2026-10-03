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
import com.example.data.model.ToolExecutionRecord
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
