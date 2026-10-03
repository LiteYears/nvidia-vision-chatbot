package com.example.agent.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.agent.plan.AgentFlailingDetector
import com.example.agent.plan.Subtask
import com.example.agent.plan.SubtaskStatus
import com.example.agent.plan.TaskPlan
import com.example.agent.plan.TaskPlanner
import com.example.agent.tools.ToolCallParser
import com.example.agent.tools.ToolRegistry
import com.example.agent.tools.workspace.AgentWorkspaceContext
import com.example.agent.tools.workspace.AgentWorkspaceManager
import com.example.agent.tools.workspace.FileChangeType
import com.example.agent.tools.workspace.VerificationStatus
import com.example.data.local.ChatDatabase
import com.example.data.model.AgentSession
import com.example.data.model.AgentStep
import com.example.data.model.AgentTaskStatus
import com.example.data.model.ChatMessage
import com.example.data.model.MessageRole
import com.example.data.model.ToolExecutionRecord
import com.example.data.preferences.SettingsManager
import com.example.data.remote.NvidiaApiClient
import com.example.data.repository.AgentPlanRepository
import com.example.data.repository.ChatRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

/**
 * Android Foreground Service hosting autonomous agent execution loops outside of ViewModel lifecycles.
 *
 * Provides a persistent status bar notification with real-time step progress, active command details,
 * and a quick "Stop Task" control so long-running multi-step builds and test suites continue running
 * without interruption when the user switches apps.
 */
class AgentExecutionService : Service() {

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Default + serviceJob)
    private var executionJob: Job? = null

    private lateinit var notificationManager: NotificationManager
    private lateinit var database: ChatDatabase
    private lateinit var settingsManager: SettingsManager
    private lateinit var apiClient: NvidiaApiClient
    private lateinit var repository: ChatRepository
    private lateinit var agentPlanRepository: AgentPlanRepository
    private lateinit var taskPlanner: TaskPlanner
    private lateinit var workspaceManager: AgentWorkspaceManager
    private lateinit var toolRegistry: ToolRegistry

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()

        database = ChatDatabase.getDatabase(applicationContext)
        settingsManager = SettingsManager(applicationContext)
        apiClient = NvidiaApiClient { settingsManager.getEffectiveApiKey() }
        repository = ChatRepository(database.chatDao(), settingsManager, apiClient)
        agentPlanRepository = AgentPlanRepository(database.agentPlanDao())
        taskPlanner = TaskPlanner()
        workspaceManager = AgentWorkspaceManager.init(File(filesDir, "agent_workspaces"))
        toolRegistry = ToolRegistry.defaultRegistry(workspaceManager)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: return START_NOT_STICKY

        when (action) {
            ACTION_START_AGENT, ACTION_FOLLOW_UP -> {
                startForegroundCompat(buildStartupNotification())
                val sessionId = intent.getStringExtra(EXTRA_SESSION_ID) ?: run {
                    stopForegroundCompat(true)
                    stopSelf()
                    return START_NOT_STICKY
                }
                val userMessageId = intent.getStringExtra(EXTRA_USER_MESSAGE_ID) ?: UUID.randomUUID().toString()
                val userMessageContent = intent.getStringExtra(EXTRA_USER_MESSAGE_CONTENT) ?: ""
                val modelName = intent.getStringExtra(EXTRA_MODEL) ?: settingsManager.getSelectedModel()

                handleStartOrFollowUp(
                    sessionId = sessionId,
                    userMessageId = userMessageId,
                    userMessageContent = userMessageContent,
                    modelName = modelName
                )
            }
            ACTION_STOP_AGENT -> {
                handleStop()
            }
        }

        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        executionJob?.cancel()
        serviceJob.cancel()
        super.onDestroy()
    }

    private fun handleStartOrFollowUp(
        sessionId: String,
        userMessageId: String,
        userMessageContent: String,
        modelName: String
    ) {
        executionJob?.cancel()

        serviceScope.launch {
            var session = AgentExecutionCoordinator.activeSession.value
            if (session == null || session.id != sessionId) {
                session = agentPlanRepository.getAgentSession(sessionId)
            }

            if (session == null) {
                stopForegroundCompat(true)
                stopSelf()
                return@launch
            }

            // Immediately enter foreground with persistent notification
            val initialNotification = buildNotification(
                session = session,
                status = AgentTaskStatus.INITIALIZING,
                actionDescription = "Initializing agent environment..."
            )
            startForegroundCompat(initialNotification)

            // Find or construct the user message
            val existingUserMessage = session.messages.find { it.id == userMessageId }
            val userMessage = existingUserMessage ?: ChatMessage(
                id = userMessageId,
                conversationId = sessionId,
                role = MessageRole.USER,
                content = userMessageContent,
                modelUsed = modelName,
                timestamp = System.currentTimeMillis()
            )

            // Persist user message to establish conversation and prevent asymmetric loss
            try {
                repository.saveMessage(userMessage, isIncognito = false)
            } catch (_: Exception) {}

            // Assistant message placeholder
            val assistantMessage = session.messages.lastOrNull { it.role == MessageRole.ASSISTANT && it.isStreaming }
            val assistantMessageId = assistantMessage?.id ?: UUID.randomUUID().toString()
            val streamingAssistantMessage = assistantMessage ?: ChatMessage(
                id = assistantMessageId,
                conversationId = sessionId,
                role = MessageRole.ASSISTANT,
                content = "",
                modelUsed = modelName,
                isStreaming = true,
                timestamp = System.currentTimeMillis() + 1
            )

            executionJob = serviceScope.launch {
                try {
                    runAgentLoop(
                        initialSession = session,
                        userMessage = userMessage,
                        assistantMessageId = assistantMessageId,
                        streamingAssistantMessage = streamingAssistantMessage
                    )
                } catch (e: CancellationException) {
                    if (!AgentExecutionCoordinator.isRunning.value) {
                        val cur = AgentExecutionCoordinator.activeSession.value ?: session
                        AgentExecutionCoordinator.notifyPaused(cur.id)
                        try {
                            agentPlanRepository.saveAgentSession(
                                cur.copy(
                                    status = AgentTaskStatus.PAUSED,
                                    updatedAt = System.currentTimeMillis()
                                )
                            )
                        } catch (_: Exception) {}
                        stopForegroundCompat(true)
                        stopSelf()
                    }
                    throw e
                } catch (e: Throwable) {
                    val cur = AgentExecutionCoordinator.activeSession.value ?: session
                    AgentExecutionCoordinator.failSession(
                        sessionId = cur.id,
                        assistantMessageId = assistantMessageId,
                        streamingAssistantMessage = streamingAssistantMessage,
                        error = e
                    )
                    try {
                        agentPlanRepository.saveAgentSession(
                            cur.copy(
                                status = AgentTaskStatus.FAILED,
                                updatedAt = System.currentTimeMillis()
                            )
                        )
                    } catch (_: Exception) {}
                    showFailureNotification(cur, e.message ?: "Task execution error")
                    stopForegroundCompat(true)
                    stopSelf()
                }
            }
        }
    }

    private fun handleStop() {
        executionJob?.cancel()
        val currentSession = AgentExecutionCoordinator.activeSession.value
        if (currentSession != null) {
            AgentExecutionCoordinator.notifyPaused(currentSession.id)
            serviceScope.launch {
                try {
                    agentPlanRepository.saveAgentSession(
                        currentSession.copy(
                            status = AgentTaskStatus.PAUSED,
                            updatedAt = System.currentTimeMillis()
                        )
                    )
                } catch (_: Exception) {}
            }
        }
        stopForegroundCompat(true)
        stopSelf()
    }

    private suspend fun runAgentLoop(
        initialSession: AgentSession,
        userMessage: ChatMessage,
        assistantMessageId: String,
        streamingAssistantMessage: ChatMessage
    ) {
        var session = initialSession
        workspaceManager.activeSessionId = session.id
        val workspaceContext = AgentWorkspaceContext(
            sessionId = session.id,
            workspaceManager = workspaceManager
        )
        workspaceContext.discoverWorkspace()
        val flailingDetector = AgentFlailingDetector()

        val historyMessages = session.messages
            .filter { it.id != assistantMessageId && !it.isError }
            .filterNot { it.id == userMessage.id }
            .toMutableList()

        var currentPlan = session.plan ?: taskPlanner.createInitialPlan(session.id, session.goal, MAX_AUTONOMOUS_TOOL_STEPS)
        AgentExecutionCoordinator.updatePlan(session.id, currentPlan)
        try {
            agentPlanRepository.savePlan(currentPlan)
        } catch (_: Exception) {}

        val agentSystemPrompt = buildAgentSystemPrompt(session.goal)
        var currentTurnMessage = userMessage
        var toolStepCount = 0
        var consecutiveNudges = 0
        var totalNudges = 0
        var loopActive = true

        while (loopActive && toolStepCount < MAX_AUTONOMOUS_TOOL_STEPS && !currentPlan.isStepLimitExceeded && currentCoroutineContext().isActive) {
            // Ensure an active subtask is assigned and set to RUNNING
            if (currentPlan.activeSubtask == null || currentPlan.activeSubtask?.status == SubtaskStatus.COMPLETED) {
                currentPlan = taskPlanner.startNextSubtask(currentPlan)
                AgentExecutionCoordinator.updatePlan(session.id, currentPlan)
                try {
                    agentPlanRepository.savePlan(currentPlan)
                } catch (_: Exception) {}
            }
            val activeSubtask = currentPlan.activeSubtask

            // 1. STATE: THINKING
            val thinkingDesc = "Thinking: Planning next step..."
            AgentExecutionCoordinator.updateStatus(session.id, AgentTaskStatus.THINKING, thinkingDesc)
            updateNotification(session, AgentTaskStatus.THINKING, thinkingDesc)

            val planPromptSnippet = taskPlanner.formatPlanForPrompt(currentPlan)
            val workspacePromptSnippet = workspaceContext.formatContextForPrompt()
            val fullSystemPrompt = "$agentSystemPrompt\n\n$workspacePromptSnippet\n\n$planPromptSnippet"

            var attemptResult = repository.requestAiCompletion(
                history = historyMessages,
                userMessage = currentTurnMessage,
                modelName = session.modelUsed,
                systemPrompt = fullSystemPrompt,
                temperature = settingsManager.getTemperature(),
                topP = settingsManager.getTopP(),
                maxTokens = settingsManager.getMaxTokens()
            )

            var networkRetries = 0
            while (attemptResult.isFailure && networkRetries < 3 && currentCoroutineContext().isActive) {
                val err = attemptResult.exceptionOrNull()
                val errText = err?.message ?: ""
                val isNetwork = errText.contains("Network connection", ignoreCase = true) ||
                    errText.contains("Unable to resolve host", ignoreCase = true) ||
                    errText.contains("timeout", ignoreCase = true) ||
                    errText.contains("Failed to connect", ignoreCase = true)
                if (!isNetwork) break

                networkRetries++
                delay(2000L * networkRetries)
                attemptResult = repository.requestAiCompletion(
                    history = historyMessages,
                    userMessage = currentTurnMessage,
                    modelName = session.modelUsed,
                    systemPrompt = fullSystemPrompt,
                    temperature = settingsManager.getTemperature(),
                    topP = settingsManager.getTopP(),
                    maxTokens = settingsManager.getMaxTokens()
                )
            }

            if (attemptResult.isFailure) {
                val error = attemptResult.exceptionOrNull() ?: Exception("Unknown error during agent generation")
                AgentExecutionCoordinator.failSession(session.id, assistantMessageId, streamingAssistantMessage, error)
                showFailureNotification(session, error.message ?: "Agent generation failure")
                stopForegroundCompat(true)
                stopSelf()
                return
            }

            val rawResponse = attemptResult.getOrThrow()

            // Dynamic agent plan update detection
            val dynamicPlan = taskPlanner.parsePlanFromAgentOutput(rawResponse, currentPlan)
            if (dynamicPlan != null) {
                currentPlan = dynamicPlan
                AgentExecutionCoordinator.updatePlan(session.id, currentPlan)
                try {
                    agentPlanRepository.savePlan(currentPlan)
                } catch (_: Exception) {}
            }

            val toolCall = ToolCallParser.parse(rawResponse)

            if (toolCall != null) {
                consecutiveNudges = 0
                toolStepCount++
                currentPlan = taskPlanner.incrementStep(currentPlan)
                AgentExecutionCoordinator.updatePlan(session.id, currentPlan)

                val cmdPreview = (toolCall.arguments["command"] ?: toolCall.arguments["cmd"] ?: toolCall.arguments["code"] ?: toolCall.arguments["path"] ?: "").toString().take(45)
                val usingToolDesc = if (cmdPreview.isNotBlank()) "Running ${toolCall.toolName}: $cmdPreview" else "Executing ${toolCall.toolName}..."

                // 2. STATE: USING_TOOL
                AgentExecutionCoordinator.updateStatus(session.id, AgentTaskStatus.USING_TOOL, usingToolDesc)
                updateNotification(session, AgentTaskStatus.USING_TOOL, usingToolDesc)

                val preToolNarrative = ToolCallParser.stripToolCalls(rawResponse).trim()
                if (preToolNarrative.isNotBlank()) {
                    AgentExecutionCoordinator.addReflection(session.id, preToolNarrative)
                }

                // Execute the requested tool safely via the modular registry
                val toolResult = toolRegistry.execute(toolCall)
                val record = ToolExecutionRecord(
                    callId = toolCall.callId,
                    messageId = assistantMessageId,
                    toolName = toolCall.toolName,
                    arguments = toolCall.arguments,
                    isSuccess = toolResult.isSuccess,
                    result = toolResult.result,
                    error = toolResult.error,
                    timestamp = System.currentTimeMillis()
                )

                // Store tool execution record in active session
                AgentExecutionCoordinator.addToolExecution(session.id, record)

                // Update workspace context with tool effects
                val pathArg = (toolCall.arguments["path"] ?: toolCall.arguments["file"] ?: toolCall.arguments["script_path"])?.toString()
                when (toolCall.toolName.lowercase()) {
                    "file_list", "file_tree" -> {
                        val lines = (toolResult.result ?: "").lines()
                        val discovered = lines.mapNotNull { line ->
                            val trimmed = line.trim()
                            if (trimmed.startsWith("[FILE]") || trimmed.startsWith("[DIR ]")) {
                                trimmed.substring(6).trim().split(" ").firstOrNull()
                            } else if (trimmed.contains("── ") && !trimmed.endsWith("/")) {
                                trimmed.substringAfter("── ").split(" ").firstOrNull()
                            } else null
                        }
                        if (discovered.isNotEmpty()) {
                            workspaceContext.recordDiscoveredFiles(discovered)
                        }
                    }
                    "file_read" -> {
                        if (pathArg != null) {
                            workspaceContext.recordFileInspection(pathArg, (toolResult.result ?: "").length.toLong(), "Inspected file content")
                        }
                    }
                    "file_write" -> {
                        if (pathArg != null && toolResult.isSuccess) {
                            workspaceContext.recordFileModification(pathArg, FileChangeType.CREATED, "Wrote file content")
                        }
                    }
                    "file_patch" -> {
                        if (pathArg != null && toolResult.isSuccess) {
                            workspaceContext.recordFileModification(pathArg, FileChangeType.PATCHED, "Patched file content")
                        }
                    }
                    "file_delete" -> {
                        if (pathArg != null && toolResult.isSuccess) {
                            workspaceContext.recordFileDeletion(pathArg)
                        }
                    }
                    "run_command", "python_execute", "bash", "terminal", "sh", "cmd", "exec" -> {
                        val cmd = (toolCall.arguments["command"] ?: toolCall.arguments["code"] ?: toolCall.arguments["script_path"] ?: toolCall.toolName).toString()
                        workspaceContext.recordCommandExecution(
                            command = cmd,
                            exitCode = if (toolResult.isSuccess) 0 else 1,
                            output = toolResult.result ?: toolResult.error ?: "",
                            isSuccess = toolResult.isSuccess
                        )
                    }
                }
                if (!toolResult.isSuccess) {
                    workspaceContext.recordToolFailure(toolCall.toolName, toolResult.error ?: "Error")
                }

                if (toolResult.isSuccess) {
                    // Advance subtask state if this tool logically achieves its objective
                    currentPlan = taskPlanner.advanceSubtaskOnToolSuccess(
                        plan = currentPlan,
                        subtaskId = activeSubtask?.id,
                        toolName = toolCall.toolName,
                        toolResult = toolResult.result ?: ""
                    )
                    AgentExecutionCoordinator.updatePlan(session.id, currentPlan)
                } else {
                    // Record failure and allow retry or alternative
                    currentPlan = taskPlanner.recordToolFailure(
                        plan = currentPlan,
                        subtaskId = activeSubtask?.id,
                        toolName = toolCall.toolName,
                        error = toolResult.error ?: "Unknown error"
                    )
                    AgentExecutionCoordinator.updatePlan(session.id, currentPlan)
                }
                try {
                    agentPlanRepository.savePlan(currentPlan)
                } catch (_: Exception) {}

                // Track tool execution signature for loop/flailing analysis
                flailingDetector.recordExecution(
                    toolName = toolCall.toolName,
                    arguments = toolCall.arguments,
                    isSuccess = toolResult.isSuccess,
                    error = toolResult.error
                )
                val flailingReport = flailingDetector.detectFlailing()
                if (flailingReport?.shouldAutoAdaptPlan == true && activeSubtask != null) {
                    val recoverySubtask = Subtask(
                        description = "Diagnose '${flailingReport.toolName}' failure and implement alternative approach",
                        status = SubtaskStatus.RUNNING
                    )
                    currentPlan = taskPlanner.adaptPlanForFailure(
                        plan = currentPlan,
                        failedSubtaskId = activeSubtask.id,
                        failureDiagnostic = flailingReport.description,
                        recoverySubtask = recoverySubtask
                    )
                    AgentExecutionCoordinator.updatePlan(session.id, currentPlan)
                    try {
                        agentPlanRepository.savePlan(currentPlan)
                    } catch (_: Exception) {}
                }

                // 3. STATE: OBSERVING
                val observingDesc = "Observing command output..."
                AgentExecutionCoordinator.updateStatus(session.id, AgentTaskStatus.OBSERVING, observingDesc)
                updateNotification(session, AgentTaskStatus.OBSERVING, observingDesc)
                delay(120L)

                // Build structured observation feedback for the model
                val nextActiveSubtask = currentPlan.activeSubtask
                val cmdExecuted = (record.arguments["command"] ?: record.arguments["cmd"] ?: record.arguments["code"] ?: record.arguments["script_path"])?.toString() ?: record.toolName
                val toolFeedbackContent = buildString {
                    appendLine("```terminal-output")
                    appendLine("ubuntu@termux:~/workspace$ $cmdExecuted")
                    if (record.isSuccess) {
                        appendLine(record.result?.trim() ?: "(Command finished with no output)")
                        appendLine("[Process exited 0]")
                    } else {
                        appendLine(record.error?.trim() ?: "Command execution error")
                        appendLine("[Process exited with non-zero status]")
                    }
                    appendLine("```")
                    if (record.isSuccess) {
                        if (nextActiveSubtask != null && nextActiveSubtask.id != activeSubtask?.id) {
                            appendLine("\n[SUBTASK PROGRESSION: Subtask \"${activeSubtask?.description}\" is COMPLETED. Active subtask is now: \"${nextActiveSubtask.description}\".]")
                        } else {
                            appendLine("\n[UBUNTU TERMINAL: Command executed cleanly. Continue with your next terminal command in ```bash or deliver your final solution.]")
                        }
                    } else {
                        appendLine("\n[UBUNTU TERMINAL: Command failed with the error shown in the terminal output above. Analyze the terminal output, inspect errors, and run your corrective terminal command in ```bash.]")
                        if (flailingReport != null) {
                            appendLine()
                            appendLine(flailingReport.guidanceDirective)
                        }
                    }
                    appendLine("Autonomous plan step ${currentPlan.stepCount} of ${currentPlan.maxSteps} executed.")
                    if (currentPlan.isStepLimitExceeded) {
                        appendLine("Maximum plan step limit reached. Deliver your final complete answer to the user now without any further terminal commands.")
                    } else {
                        appendLine("\nNEXT ACTION MANDATE:")
                        appendLine("- If the overall objective is NOT fully verified and achieved, you MUST immediately invoke the next command in your Ubuntu terminal using ```bash.")
                        appendLine("- Only provide a final text response when all terminal steps and objectives are completed and verified.")
                    }
                }

                // Add past turn to ongoing history
                historyMessages.add(currentTurnMessage)
                historyMessages.add(
                    ChatMessage(
                        id = UUID.randomUUID().toString(),
                        conversationId = session.id,
                        role = MessageRole.ASSISTANT,
                        content = rawResponse,
                        modelUsed = session.modelUsed
                    )
                )
                pruneAndCompactHistory(historyMessages)

                // Set feedback message as next user message to feed back to model
                currentTurnMessage = ChatMessage(
                    id = UUID.randomUUID().toString(),
                    conversationId = session.id,
                    role = MessageRole.USER,
                    content = toolFeedbackContent,
                    modelUsed = session.modelUsed
                )

                // Refresh latest session reference from coordinator
                session = AgentExecutionCoordinator.activeSession.value ?: session
            } else {
                // Model returned text without a tool call
                val hasPendingAction = taskPlanner.hasPendingActionSubtasks(currentPlan) || workspaceContext.hasUnverifiedModifications()
                val canNudge = hasPendingAction &&
                    consecutiveNudges < MAX_CONSECUTIVE_NUDGES &&
                    totalNudges < MAX_TOTAL_NUDGES &&
                    toolStepCount < MAX_AUTONOMOUS_TOOL_STEPS

                if (canNudge) {
                    consecutiveNudges++
                    totalNudges++

                    val preToolNarrative = rawResponse.trim()
                    if (preToolNarrative.isNotBlank()) {
                        AgentExecutionCoordinator.addReflection(session.id, preToolNarrative)
                    }

                    // Add past turn to ongoing history
                    historyMessages.add(currentTurnMessage)
                    historyMessages.add(
                        ChatMessage(
                            id = UUID.randomUUID().toString(),
                            conversationId = session.id,
                            role = MessageRole.ASSISTANT,
                            content = rawResponse,
                            modelUsed = session.modelUsed
                        )
                    )
                    pruneAndCompactHistory(historyMessages)

                    val nudgeContent = buildString {
                        appendLine("[EXECUTION CONTROL: Command or tool execution required]")
                        appendLine("You provided commentary or described next steps, but did not execute a command in your Ubuntu terminal.")
                        appendLine("Unfinished action subtask: \"${activeSubtask?.description}\".")
                        if (workspaceContext.hasUnverifiedModifications()) {
                            val unverified = workspaceContext.modifiedFiles.filter { it.value.verificationStatus == VerificationStatus.NEEDS_VERIFICATION }.keys
                            appendLine("Unverified modifications exist on disk for: ${unverified.joinToString(", ")}.")
                            appendLine("You must run compilation or test checks (using standard bash commands or 'run_command') to verify the modified workspace state before completing.")
                        }
                        if (activeSubtask?.status == SubtaskStatus.FAILED) {
                            appendLine("The previous tool execution for subtask \"${activeSubtask.description}\" FAILED.")
                            appendLine("You must inspect diagnostics, apply a repair (using bash heredocs or file tools), test an alternative approach, or adapt your plan before concluding.")
                        } else if (activeSubtask?.description?.contains("test", ignoreCase = true) == true ||
                            activeSubtask?.description?.contains("compile", ignoreCase = true) == true ||
                            activeSubtask?.description?.contains("verify", ignoreCase = true) == true ||
                            activeSubtask?.description?.contains("execute", ignoreCase = true) == true ||
                            activeSubtask?.description?.contains("run", ignoreCase = true) == true) {
                            appendLine("The implementation has not been tested or verified yet. You MUST run tests or verify the build (via a ```bash block or 'run_command') before claiming completion.")
                        } else if (activeSubtask?.description?.contains("inspect", ignoreCase = true) == true ||
                            activeSubtask?.description?.contains("locate", ignoreCase = true) == true ||
                            activeSubtask?.description?.contains("search", ignoreCase = true) == true) {
                            appendLine("Before editing, inspect the existing code and architecture using standard terminal commands (ls, cat, grep, find) or file tools.")
                        } else if (activeSubtask?.description?.contains("modify", ignoreCase = true) == true ||
                            activeSubtask?.description?.contains("implement", ignoreCase = true) == true ||
                            activeSubtask?.description?.contains("fix", ignoreCase = true) == true ||
                            activeSubtask?.description?.contains("write", ignoreCase = true) == true) {
                            appendLine("The required code modifications have not been saved yet. You MUST write or update the files using a bash heredoc (cat << 'EOF' > filename ... EOF) or file tools.")
                        }
                        appendLine("You MUST output your next command in a ```bash ... ``` block or a ```tool_call``` block now to proceed with execution.")
                        appendLine("Do NOT deliver an intermediate text-only response without executing a command until the objective is fully executed and verified.")
                    }

                    currentTurnMessage = ChatMessage(
                        id = UUID.randomUUID().toString(),
                        conversationId = session.id,
                        role = MessageRole.USER,
                        content = nudgeContent,
                        modelUsed = session.modelUsed
                    )
                } else {
                    // Model delivered final response
                    loopActive = false

                    val hasUnfinishedActions = taskPlanner.hasPendingActionSubtasks(currentPlan) || workspaceContext.hasUnverifiedModifications()
                    if (!hasUnfinishedActions && activeSubtask != null && activeSubtask.status != SubtaskStatus.FAILED) {
                        currentPlan = taskPlanner.verifyAndCompleteSubtask(
                            plan = currentPlan,
                            subtaskId = activeSubtask.id,
                            verificationNotes = "Verified by agent completion"
                        )
                    }

                    val updatedSubtasks = currentPlan.subtasks.map {
                        if (it.status != SubtaskStatus.COMPLETED && taskPlanner.isSynthesisOrFinalSubtask(it.description) && !hasUnfinishedActions) {
                            it.copy(status = SubtaskStatus.COMPLETED, result = it.result ?: "Completed in final deliverable")
                        } else it
                    }
                    val allCompleted = updatedSubtasks.isNotEmpty() && updatedSubtasks.all { it.status == SubtaskStatus.COMPLETED }
                    currentPlan = currentPlan.copy(
                        subtasks = updatedSubtasks,
                        isCompleted = allCompleted,
                        updatedAt = System.currentTimeMillis()
                    )
                    AgentExecutionCoordinator.updatePlan(session.id, currentPlan)
                    try {
                        agentPlanRepository.savePlan(currentPlan)
                    } catch (_: Exception) {}

                    streamAndCompleteResponse(
                        sessionId = session.id,
                        assistantMessageId = assistantMessageId,
                        streamingAssistantMessage = streamingAssistantMessage,
                        fullReplyText = rawResponse,
                        plan = currentPlan
                    )
                    return
                }
            }
        }

        // Fallback: If loop exited due to step limit
        if (loopActive && currentCoroutineContext().isActive) {
            AgentExecutionCoordinator.updateStatus(session.id, AgentTaskStatus.THINKING, "Synthesizing final deliverable...")
            val finalPrompt = ChatMessage(
                id = UUID.randomUUID().toString(),
                conversationId = session.id,
                role = MessageRole.USER,
                content = "You have completed ${currentPlan.stepCount} autonomous plan steps (limit: ${currentPlan.maxSteps}). Summarize all findings and verify your subtasks to conclude.",
                modelUsed = session.modelUsed
            )
            val finalResult = repository.requestAiCompletion(
                history = historyMessages,
                userMessage = finalPrompt,
                modelName = session.modelUsed,
                systemPrompt = "$agentSystemPrompt\n\n${taskPlanner.formatPlanForPrompt(currentPlan)}",
                temperature = settingsManager.getTemperature(),
                topP = settingsManager.getTopP(),
                maxTokens = settingsManager.getMaxTokens()
            )

            val finalReply = finalResult.getOrElse {
                val lastTool = session.toolExecutions.lastOrNull()
                "Completed ${currentPlan.stepCount} autonomous plan steps. Last output: ${lastTool?.result ?: "Success"}."
            }

            streamAndCompleteResponse(
                sessionId = session.id,
                assistantMessageId = assistantMessageId,
                streamingAssistantMessage = streamingAssistantMessage,
                fullReplyText = ToolCallParser.stripToolCalls(finalReply),
                plan = currentPlan
            )
        }
    }

    private suspend fun streamAndCompleteResponse(
        sessionId: String,
        assistantMessageId: String,
        streamingAssistantMessage: ChatMessage,
        fullReplyText: String,
        plan: TaskPlan
    ) {
        val responseTimestamp = System.currentTimeMillis()
        val words = fullReplyText.split(Regex("(?<=\\s)|(?=\\s)"))
        val accumulated = StringBuilder()

        for (token in words) {
            accumulated.append(token)
            AgentExecutionCoordinator.updateStreamingContent(sessionId, assistantMessageId, accumulated.toString())
            val delayMs = if (token.isBlank()) 8L else (14L + (token.length % 5) * 2L)
            delay(delayMs)
        }

        val finalAssistantMessage = streamingAssistantMessage.copy(
            content = fullReplyText,
            isStreaming = false,
            timestamp = responseTimestamp
        )

        val updatedSteps = AgentExecutionCoordinator.activeSession.value?.steps?.map { step ->
            step.copy(status = AgentTaskStatus.COMPLETED)
        }

        AgentExecutionCoordinator.completeSession(
            sessionId = sessionId,
            finalAssistantMessage = finalAssistantMessage,
            updatedSteps = updatedSteps,
            finalPlan = plan
        )

        val finalSession = AgentExecutionCoordinator.activeSession.value
        if (finalSession != null) {
            try {
                agentPlanRepository.saveAgentSession(finalSession)
                agentPlanRepository.savePlan(plan)
                repository.saveMessage(finalAssistantMessage, isIncognito = false)
            } catch (_: Exception) {}
            showCompletionNotification(finalSession)
        }

        stopForegroundCompat(true)
        stopSelf()
    }

    private fun pruneAndCompactHistory(
        history: MutableList<ChatMessage>,
        maxRecentTurns: Int = 4,
        maxMessageChars: Int = 1200
    ) {
        if (history.size <= maxRecentTurns * 2) return

        val initialUserMsg = history.firstOrNull()
        val recentWindow = history.takeLast(maxRecentTurns * 2).toMutableList()

        for (i in 0 until recentWindow.size - 1) {
            val msg = recentWindow[i]
            if (msg.content.length > maxMessageChars) {
                val truncated = msg.content.take(maxMessageChars) + "\n...[Output truncated to conserve model context budget]..."
                recentWindow[i] = msg.copy(content = truncated)
            }
        }

        history.clear()
        if (initialUserMsg != null && !recentWindow.any { it.id == initialUserMsg.id }) {
            history.add(initialUserMsg)
            if (recentWindow.firstOrNull()?.role == MessageRole.USER) {
                history.add(
                    ChatMessage(
                        id = UUID.randomUUID().toString(),
                        conversationId = initialUserMsg.conversationId,
                        role = MessageRole.ASSISTANT,
                        content = "Understood. Executing plan steps.",
                        modelUsed = initialUserMsg.modelUsed
                    )
                )
            }
        }
        history.addAll(recentWindow)
    }

    private fun buildAgentSystemPrompt(goal: String): String {
        val toolsPrompt = toolRegistry.formatToolsForPrompt()
        return "You are an autonomous engineering agent with direct and exclusive access to a live rootless Ubuntu 22.04 LTS bash terminal running on the device.\n\n" +
            "USER OBJECTIVE: \"$goal\"\n\n" +
            "$toolsPrompt\n\n" +
            "CORE ARCHITECTURE MANDATE - THE TERMINAL IS BUILT FOR YOU:\n" +
            "- You do NOT call abstract tools on the chat interface. You execute commands directly on your Ubuntu terminal.\n" +
            "- The chat interface will display your textual thoughts and the commands executed on your Ubuntu terminal.\n" +
            "- To run commands on your terminal, write your commands in a ```bash ... ``` block or ```tool_call with tool 'bash'.\n" +
            "- Always use standard Linux commands:\n" +
            "  * Writing files: cat << 'EOF' > filename ... EOF\n" +
            "  * Reading files: cat filename, head -n 40 filename\n" +
            "  * Running Python: python3 filename.py\n" +
            "  * Installing: apt update && apt install -y <pkg> or pip install <pkg>\n" +
            "  * Testing & verifying: check stdout, stderr, and exit codes.\n" +
            "- Prior to each command, provide a brief 1-2 sentence thought, execute the terminal command, inspect the result, and finish with your verified solution."
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Agent Task Execution",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Persistent status and controls for autonomous builds, test suites, and multi-step tasks."
                setShowBadge(true)
                enableVibration(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun buildStartupNotification(): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_NAVIGATE_TO_AGENT, true)
        }
        val contentPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Agent: Starting")
            .setContentText("Initializing autonomous workspace...")
            .setSubText("Autonomous Agent")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(contentPendingIntent)
            .build()
    }

    private fun buildNotification(
        session: AgentSession,
        status: AgentTaskStatus,
        actionDescription: String
    ): Notification {
        val plan = session.plan
        val activeSubtask = plan?.activeSubtask?.description

        val title = when (status) {
            AgentTaskStatus.THINKING -> "Agent: Thinking"
            AgentTaskStatus.USING_TOOL -> if (actionDescription.isNotBlank()) actionDescription else "Agent: Executing Command"
            AgentTaskStatus.OBSERVING -> "Agent: Observing Results"
            AgentTaskStatus.IN_PROGRESS -> "Agent: Working"
            AgentTaskStatus.COMPLETED -> "Agent: Task Completed"
            AgentTaskStatus.PAUSED -> "Agent: Paused"
            AgentTaskStatus.FAILED -> "Agent: Task Failed"
            AgentTaskStatus.INITIALIZING -> "Agent: Initializing Environment"
        }

        val contentText = when {
            !activeSubtask.isNullOrBlank() -> activeSubtask
            session.goal.isNotBlank() -> session.goal
            else -> "Autonomous task execution in progress"
        }

        val subText = if (plan != null && plan.totalCount > 0) {
            "Step ${plan.stepCount}/${plan.maxSteps} • ${plan.completedCount}/${plan.totalCount} subtasks"
        } else {
            "Autonomous Agent"
        }

        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_NAVIGATE_TO_AGENT, true)
            putExtra(EXTRA_SESSION_ID, session.id)
        }
        val contentPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, AgentExecutionService::class.java).apply {
            action = ACTION_STOP_AGENT
            putExtra(EXTRA_SESSION_ID, session.id)
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(contentText)
            .setSubText(subText)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setContentIntent(contentPendingIntent)
            .addAction(
                android.R.drawable.ic_media_pause,
                "Stop Task",
                stopPendingIntent
            )

        if (plan != null && plan.maxSteps > 0) {
            builder.setProgress(plan.maxSteps, plan.stepCount, false)
        } else {
            builder.setProgress(0, 0, true)
        }

        return builder.build()
    }

    private fun updateNotification(
        session: AgentSession,
        status: AgentTaskStatus,
        actionDescription: String
    ) {
        val notification = buildNotification(session, status, actionDescription)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun showCompletionNotification(session: AgentSession) {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_NAVIGATE_TO_AGENT, true)
            putExtra(EXTRA_SESSION_ID, session.id)
        }
        val contentPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Agent Task Complete")
            .setContentText(session.goal)
            .setSubText("All objectives verified")
            .setOngoing(false)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(contentPendingIntent)
            .build()

        notificationManager.notify(COMPLETION_NOTIFICATION_ID, notification)
    }

    private fun showFailureNotification(session: AgentSession, errorText: String) {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_NAVIGATE_TO_AGENT, true)
            putExtra(EXTRA_SESSION_ID, session.id)
        }
        val contentPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Agent Task Failed")
            .setContentText(errorText)
            .setSubText(session.goal)
            .setOngoing(false)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(contentPendingIntent)
            .build()

        notificationManager.notify(COMPLETION_NOTIFICATION_ID, notification)
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun stopForegroundCompat(removeNotification: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(if (removeNotification) STOP_FOREGROUND_REMOVE else STOP_FOREGROUND_DETACH)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(removeNotification)
        }
    }

    companion object {
        const val CHANNEL_ID = "agent_execution_channel"
        const val NOTIFICATION_ID = 2001
        const val COMPLETION_NOTIFICATION_ID = 2002

        const val ACTION_START_AGENT = "com.example.agent.action.START_AGENT"
        const val ACTION_STOP_AGENT = "com.example.agent.action.STOP_AGENT"
        const val ACTION_FOLLOW_UP = "com.example.agent.action.FOLLOW_UP"

        const val EXTRA_SESSION_ID = "com.example.agent.extra.SESSION_ID"
        const val EXTRA_USER_MESSAGE_ID = "com.example.agent.extra.USER_MESSAGE_ID"
        const val EXTRA_USER_MESSAGE_CONTENT = "com.example.agent.extra.USER_MESSAGE_CONTENT"
        const val EXTRA_MODEL = "com.example.agent.extra.MODEL"
        const val EXTRA_NAVIGATE_TO_AGENT = "com.example.agent.extra.NAVIGATE_TO_AGENT"

        private const val MAX_AUTONOMOUS_TOOL_STEPS = 30
        private const val MAX_CONSECUTIVE_NUDGES = 4
        private const val MAX_TOTAL_NUDGES = 10
    }
}
