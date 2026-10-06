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
import com.example.agent.artifact.Artifact
import com.example.agent.artifact.ArtifactDetector
import com.example.agent.plan.AgentFlailingDetector
import com.example.agent.plan.Subtask
import com.example.agent.plan.SubtaskStatus
import com.example.agent.plan.TaskPlan
import com.example.agent.plan.TaskPlanner
import com.example.agent.state.ActionRecord
import com.example.agent.state.AgentExecutionState
import com.example.agent.state.AgentStateMachine
import com.example.agent.state.TaskObjective
import com.example.agent.state.TaskState
import com.example.agent.tools.ToolCallParser
import com.example.agent.tools.ToolRegistry
import com.example.agent.tools.workspace.AgentWorkspaceContext
import com.example.agent.tools.workspace.AgentWorkspaceManager
import com.example.agent.tools.workspace.FileChangeType
import com.example.agent.tools.workspace.VerificationStatus
import com.example.agent.trace.AgentTraceLogger
import com.example.agent.verification.TaskVerificationEngine
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
        agentPlanRepository = AgentPlanRepository(database.agentPlanDao(), database.chatDao())
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

        executionJob = serviceScope.launch {
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

        val taskObjective = TaskObjective.fromUserGoal(session.goal)
        val stateMachine = AgentStateMachine(AgentExecutionState.CREATED)
        val artifactDetector = ArtifactDetector(workspaceManager.getWorkspaceDir(), session.id)
        val flailingDetector = AgentFlailingDetector()

        var currentPlan = session.plan ?: taskPlanner.createInitialPlan(session.id, session.goal, MAX_AUTONOMOUS_TOOL_STEPS)
        AgentExecutionCoordinator.updatePlan(session.id, currentPlan)
        try {
            agentPlanRepository.savePlan(currentPlan)
        } catch (_: Exception) {}

        var taskState = TaskState(
            taskId = session.id,
            originalObjective = taskObjective,
            currentState = AgentExecutionState.CREATED,
            currentPlan = currentPlan
        )

        stateMachine.transitionTo(AgentExecutionState.PLANNING, "Initialized autonomous execution for: \"${session.goal}\"")
        AgentExecutionCoordinator.updateExecutionState(session.id, AgentExecutionState.PLANNING, "Analyzing objective: ${session.goal}")
        AgentTraceLogger.record(session.id, "TaskStarted", iteration = 0, payload = mapOf("goal" to session.goal))

        val historyMessages = session.messages
            .filter { it.id != assistantMessageId && !it.isError }
            .filterNot { it.id == userMessage.id }
            .toMutableList()

        val agentSystemPrompt = buildAgentSystemPrompt(session.goal)
        var currentTurnMessage = userMessage
        var toolStepCount = 0
        var consecutiveNudges = 0
        var totalNudges = 0
        var consecutiveEmptyResponses = 0
        var loopActive = true

        while (loopActive && toolStepCount < MAX_AUTONOMOUS_TOOL_STEPS && !currentPlan.isStepLimitExceeded && currentCoroutineContext().isActive) {
            // 1. Ensure active subtask
            if (currentPlan.activeSubtask == null || currentPlan.activeSubtask?.status == SubtaskStatus.COMPLETED) {
                currentPlan = taskPlanner.startNextSubtask(currentPlan)
                AgentExecutionCoordinator.updatePlan(session.id, currentPlan)
                try {
                    agentPlanRepository.savePlan(currentPlan)
                } catch (_: Exception) {}
            }
            val activeSubtask = currentPlan.activeSubtask

            // 2. Planning State
            if (stateMachine.currentState != AgentExecutionState.REPLANNING) {
                stateMachine.transitionTo(AgentExecutionState.PLANNING, "Formulating next action for subtask \"${activeSubtask?.description}\"")
                AgentExecutionCoordinator.updateExecutionState(session.id, AgentExecutionState.PLANNING, "Planning next step...")
            }
            updateNotification(session, stateMachine.currentState.toAgentTaskStatus(), "Planning: ${activeSubtask?.description ?: "next step"}")

            val planPromptSnippet = taskPlanner.formatPlanForPrompt(currentPlan)
            val workspacePromptSnippet = workspaceContext.formatContextForPrompt()
            val statePromptSnippet = taskState.toContextPrompt()
            val toolsPromptSnippet = toolRegistry.formatToolsForPrompt()
            val fullSystemPrompt = "$agentSystemPrompt\n\n$toolsPromptSnippet\n\n$workspacePromptSnippet\n\n$statePromptSnippet\n\n$planPromptSnippet"

            // 3. Request LLM completion
            AgentTraceLogger.record(session.id, "ModelCalled", iteration = toolStepCount)
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
                stateMachine.transitionTo(AgentExecutionState.FAILED, "Fatal generation error: ${error.message}")
                AgentTraceLogger.record(session.id, "TaskFailed", iteration = toolStepCount, payload = mapOf("error" to (error.message ?: "")))
                AgentExecutionCoordinator.failSession(session.id, assistantMessageId, streamingAssistantMessage, error)
                showFailureNotification(session, error.message ?: "Agent generation failure")
                stopForegroundCompat(true)
                stopSelf()
                return
            }

            val rawResponse = attemptResult.getOrThrow()
            val cleanRawResponse = rawResponse.trim()

            // 4. Handle empty/null model response
            if (cleanRawResponse.isBlank() || cleanRawResponse.equals("null", ignoreCase = true)) {
                consecutiveEmptyResponses++
                AgentTraceLogger.record(session.id, "MODEL_RESPONSE_EMPTY", iteration = toolStepCount, payload = mapOf("streak" to consecutiveEmptyResponses))
                if (consecutiveEmptyResponses >= 3) {
                    stateMachine.transitionTo(AgentExecutionState.REPLANNING, "Model returned repeated empty responses. Rebuilding context.")
                    currentTurnMessage = ChatMessage(
                        id = UUID.randomUUID().toString(),
                        conversationId = session.id,
                        role = MessageRole.USER,
                        content = "[SYSTEM ALERT: Repeated empty response detected. You must emit your next terminal command inside a ```bash block or a structured ```tool_call block to accomplish: \"${session.goal}\".]",
                        modelUsed = session.modelUsed
                    )
                } else {
                    currentTurnMessage = ChatMessage(
                        id = UUID.randomUUID().toString(),
                        conversationId = session.id,
                        role = MessageRole.USER,
                        content = "Your previous output was empty. Please provide your next terminal command inside a ```bash block or ```tool_call block to proceed with: \"${session.goal}\".",
                        modelUsed = session.modelUsed
                    )
                }
                delay(300L)
                continue
            }
            consecutiveEmptyResponses = 0

            // Dynamic plan update parsing
            val dynamicPlan = taskPlanner.parsePlanFromAgentOutput(rawResponse, currentPlan)
            if (dynamicPlan != null) {
                currentPlan = dynamicPlan
                taskState = taskState.copy(currentPlan = currentPlan)
                AgentExecutionCoordinator.updatePlan(session.id, currentPlan)
                try {
                    agentPlanRepository.savePlan(currentPlan)
                } catch (_: Exception) {}
            }

            // 5. Parse tool call
            val toolCall = ToolCallParser.parse(rawResponse)

            if (toolCall != null) {
                consecutiveNudges = 0
                toolStepCount++
                currentPlan = taskPlanner.incrementStep(currentPlan)
                taskState = taskState.copy(currentPlan = currentPlan, iterationCount = toolStepCount)
                AgentExecutionCoordinator.updatePlan(session.id, currentPlan)

                // Validate tool call schema before execution
                val validation = toolRegistry.validateToolCall(toolCall)
                if (!validation.isValid) {
                    val valError = validation.errorMessage ?: "Invalid tool call format"
                    AgentTraceLogger.record(session.id, "ToolCallValidated", iteration = toolStepCount, payload = mapOf("valid" to false, "error" to valError))
                    stateMachine.transitionTo(AgentExecutionState.REPLANNING, valError)
                    AgentExecutionCoordinator.updateExecutionState(session.id, AgentExecutionState.REPLANNING, valError)

                    currentTurnMessage = ChatMessage(
                        id = UUID.randomUUID().toString(),
                        conversationId = session.id,
                        role = MessageRole.USER,
                        content = "[TOOL VALIDATION ERROR]\n$valError\nPlease correct the tool call parameters and re-invoke.",
                        modelUsed = session.modelUsed
                    )
                    continue
                }

                // 6. Transition: READY_TO_ACT
                stateMachine.transitionTo(AgentExecutionState.READY_TO_ACT, "Tool '${toolCall.toolName}' selected and validated")
                val cmdPreview = (toolCall.arguments["command"] ?: toolCall.arguments["cmd"] ?: toolCall.arguments["code"] ?: toolCall.arguments["path"] ?: "").toString().take(45)
                val usingToolDesc = if (cmdPreview.isNotBlank()) "Running ${toolCall.toolName}: $cmdPreview" else "Executing ${toolCall.toolName}..."

                // 7. Transition: EXECUTING
                stateMachine.transitionTo(AgentExecutionState.EXECUTING, "Executing ${toolCall.toolName}")
                AgentExecutionCoordinator.updateExecutionState(session.id, AgentExecutionState.EXECUTING, usingToolDesc)
                updateNotification(session, AgentTaskStatus.USING_TOOL, usingToolDesc)

                val preToolNarrative = ToolCallParser.stripToolCalls(rawResponse).trim()
                if (preToolNarrative.isNotBlank()) {
                    AgentExecutionCoordinator.addReflection(session.id, preToolNarrative)
                }

                // Capture pre-execution workspace snapshot for artifact discovery
                val beforeSnapshot = artifactDetector.captureSnapshot()

                AgentTraceLogger.record(session.id, "ToolStarted", iteration = toolStepCount, payload = mapOf("tool" to toolCall.toolName, "args" to toolCall.arguments))

                // Execute tool
                val toolResult = toolRegistry.execute(toolCall)

                // 8. Transition: OBSERVING
                stateMachine.transitionTo(AgentExecutionState.OBSERVING, "Captured output from ${toolCall.toolName}")
                val observingDesc = "Observing output from ${toolCall.toolName}..."
                AgentExecutionCoordinator.updateExecutionState(session.id, AgentExecutionState.OBSERVING, observingDesc)
                updateNotification(session, AgentTaskStatus.OBSERVING, observingDesc)

                AgentTraceLogger.record(session.id, if (toolResult.isSuccess) "ToolCompleted" else "ToolFailed", iteration = toolStepCount, payload = mapOf("tool" to toolCall.toolName, "exitCode" to (toolResult.exitCode ?: 0)))

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
                AgentExecutionCoordinator.addToolExecution(session.id, record)

                // 9. Discover and verify newly created artifacts
                val discoveredArtifacts = artifactDetector.detectNewOrModifiedArtifacts(beforeSnapshot, taskState.artifacts)
                if (discoveredArtifacts.isNotEmpty()) {
                    val updatedArtifacts = (taskState.artifacts + discoveredArtifacts).distinctBy { it.path }
                    taskState = taskState.copy(artifacts = updatedArtifacts)
                    AgentExecutionCoordinator.setArtifacts(session.id, updatedArtifacts)
                    discoveredArtifacts.forEach { art ->
                        AgentTraceLogger.record(session.id, "ArtifactDetected", iteration = toolStepCount, payload = mapOf("file" to art.filename, "valid" to art.valid, "size" to art.size))
                    }
                }

                // 10. Verification state
                stateMachine.transitionTo(AgentExecutionState.VERIFYING, "Verifying deliverables and command exit state")

                // Update workspace context
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
                            exitCode = toolResult.exitCode ?: (if (toolResult.isSuccess) 0 else 1),
                            output = toolResult.stdout ?: toolResult.result ?: toolResult.error ?: "",
                            isSuccess = toolResult.isSuccess
                        )
                    }
                }

                val actionRecord = ActionRecord(
                    actionId = toolCall.callId,
                    toolName = toolCall.toolName,
                    arguments = toolCall.arguments,
                    isSuccess = toolResult.isSuccess,
                    exitCode = toolResult.exitCode,
                    outputSummary = (toolResult.result ?: toolResult.error ?: "").take(200),
                    timestamp = System.currentTimeMillis()
                )

                if (toolResult.isSuccess) {
                    taskState = taskState.copy(
                        actionsAttempted = taskState.actionsAttempted + actionRecord,
                        successfulActions = taskState.successfulActions + actionRecord
                    )
                    currentPlan = taskPlanner.advanceSubtaskOnToolSuccess(
                        plan = currentPlan,
                        subtaskId = activeSubtask?.id,
                        toolName = toolCall.toolName,
                        toolResult = toolResult.result ?: ""
                    )
                } else {
                    taskState = taskState.copy(
                        actionsAttempted = taskState.actionsAttempted + actionRecord,
                        failedActions = taskState.failedActions + actionRecord
                    )
                    currentPlan = taskPlanner.recordToolFailure(
                        plan = currentPlan,
                        subtaskId = activeSubtask?.id,
                        toolName = toolCall.toolName,
                        error = toolResult.error ?: "Command execution error"
                    )
                    workspaceContext.recordToolFailure(toolCall.toolName, toolResult.error ?: "Error")
                }
                AgentExecutionCoordinator.updatePlan(session.id, currentPlan)
                try {
                    agentPlanRepository.savePlan(currentPlan)
                } catch (_: Exception) {}

                // Loop & Flailing analysis
                flailingDetector.recordExecution(
                    toolName = toolCall.toolName,
                    arguments = toolCall.arguments,
                    isSuccess = toolResult.isSuccess,
                    error = toolResult.error
                )
                val flailingReport = flailingDetector.detectFlailing()
                if (flailingReport?.shouldAutoAdaptPlan == true && activeSubtask != null) {
                    stateMachine.transitionTo(AgentExecutionState.REPLANNING, "Loop detected: ${flailingReport.description}")
                    val recoverySubtask = Subtask(
                        description = "Diagnose '${flailingReport.toolName}' issue and implement alternative approach",
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

                // Construct structured feedback with separate STDOUT and STDERR (Section 8)
                val cmdExecuted = (record.arguments["command"] ?: record.arguments["cmd"] ?: record.arguments["code"] ?: record.arguments["path"])?.toString() ?: record.toolName
                val toolFeedbackContent = buildString {
                    appendLine("```terminal-output")
                    appendLine("root@localhost:~# $cmdExecuted")
                    val effectiveStdout = toolResult.stdout ?: (if (toolResult.isSuccess) toolResult.result else null)
                    val effectiveStderr = toolResult.stderr ?: (if (!toolResult.isSuccess) toolResult.error else null)

                    if (!effectiveStdout.isNullOrBlank()) {
                        appendLine("STDOUT:")
                        appendLine(effectiveStdout.trim())
                    }
                    if (!effectiveStderr.isNullOrBlank()) {
                        appendLine("STDERR:")
                        appendLine(effectiveStderr.trim())
                    }
                    if (effectiveStdout.isNullOrBlank() && effectiveStderr.isNullOrBlank()) {
                        appendLine("(Command finished with no output)")
                    }
                    val code = toolResult.exitCode ?: (if (toolResult.isSuccess) 0 else 1)
                    appendLine("[Process exited with exitCode $code]")
                    appendLine("```")

                    // Show detected artifacts
                    if (taskState.artifacts.isNotEmpty()) {
                        appendLine("\n[WORKSPACE ARTIFACT STATUS]")
                        taskState.artifacts.forEach { art ->
                            val statusMark = if (art.valid) "✓ VALID" else "✗ INVALID"
                            appendLine("- ${art.filename} ($statusMark, ${art.size} bytes): ${art.verificationDetails}")
                        }
                    }

                    if (toolResult.isSuccess) {
                        appendLine("\n[ACTION SUCCEEDED: Exit status 0. Review verified artifacts above. Continue with next action or complete objective.]")
                    } else {
                        stateMachine.transitionTo(AgentExecutionState.REPLANNING, "Action failed: ${toolResult.error?.take(80)}. Diagnosing error.")
                        appendLine("\n[ACTION FAILED: Exit code $code - NON-FATAL RECOVERY MANDATE]")
                        appendLine("Inspect the STDERR above, diagnose root cause, repair code or dependencies, and continue.")
                        if (flailingReport != null) {
                            appendLine()
                            appendLine(flailingReport.guidanceDirective)
                        }
                    }
                    appendLine("Autonomous plan step ${currentPlan.stepCount} of ${currentPlan.maxSteps} executed.")
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

                currentTurnMessage = ChatMessage(
                    id = UUID.randomUUID().toString(),
                    conversationId = session.id,
                    role = MessageRole.USER,
                    content = toolFeedbackContent,
                    modelUsed = session.modelUsed
                )
                session = AgentExecutionCoordinator.activeSession.value ?: session
            } else {
                // Model returned text without tool call
                // Run deterministic objective verification (Section 29)
                val objVerification = TaskVerificationEngine.verifyTaskObjective(
                    taskObjective,
                    taskState.artifacts,
                    workspaceManager.getWorkspaceDir()
                )

                val hasUnfinishedActions = taskPlanner.hasPendingActionSubtasks(currentPlan) ||
                    taskState.artifacts.any { !it.valid } ||
                    !objVerification.isPassed

                val canNudge = hasUnfinishedActions &&
                    consecutiveNudges < MAX_CONSECUTIVE_NUDGES &&
                    totalNudges < MAX_TOTAL_NUDGES &&
                    toolStepCount < MAX_AUTONOMOUS_TOOL_STEPS

                if (canNudge) {
                    consecutiveNudges++
                    totalNudges++
                    stateMachine.transitionTo(AgentExecutionState.REPLANNING, "Objective not yet verified: ${objVerification.details}")
                    AgentExecutionCoordinator.updateExecutionState(session.id, AgentExecutionState.REPLANNING, "Re-planning to satisfy objective criteria")

                    val preToolNarrative = rawResponse.trim()
                    if (preToolNarrative.isNotBlank()) {
                        AgentExecutionCoordinator.addReflection(session.id, preToolNarrative)
                    }

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
                        appendLine("[EXECUTION CONTROL: OBJECTIVE NOT YET VERIFIED]")
                        appendLine("You provided commentary, but the objective completion criteria have not been satisfied:")
                        appendLine("STATUS: ${objVerification.details}")
                        if (taskObjective.requiredArtifacts.isNotEmpty()) {
                            appendLine("Required Deliverables: ${taskObjective.requiredArtifacts.joinToString(", ")}")
                        }
                        appendLine("You MUST execute the required command or tool now (using a ```bash block or ```tool_call block) to satisfy all criteria before concluding.")
                    }

                    currentTurnMessage = ChatMessage(
                        id = UUID.randomUUID().toString(),
                        conversationId = session.id,
                        role = MessageRole.USER,
                        content = nudgeContent,
                        modelUsed = session.modelUsed
                    )
                } else {
                    // Task concludes
                    loopActive = false
                    val finalState = if (objVerification.isPassed) AgentExecutionState.COMPLETED else AgentExecutionState.FAILED
                    stateMachine.transitionTo(finalState, objVerification.details)
                    AgentExecutionCoordinator.updateExecutionState(session.id, finalState, objVerification.details)

                    val updatedSubtasks = currentPlan.subtasks.map {
                        if (it.status != SubtaskStatus.COMPLETED && (objVerification.isPassed || taskPlanner.isSynthesisOrFinalSubtask(it.description))) {
                            it.copy(status = SubtaskStatus.COMPLETED, result = it.result ?: "Completed and verified")
                        } else it
                    }
                    currentPlan = currentPlan.copy(
                        subtasks = updatedSubtasks,
                        isCompleted = objVerification.isPassed,
                        updatedAt = System.currentTimeMillis()
                    )
                    AgentExecutionCoordinator.updatePlan(session.id, currentPlan)
                    try {
                        agentPlanRepository.savePlan(currentPlan)
                    } catch (_: Exception) {}

                    // Build formatted final deliverable response (Section 30)
                    val formattedResponse = buildString {
                        appendLine(ToolCallParser.stripToolCalls(rawResponse).trim())
                        if (taskState.artifacts.isNotEmpty()) {
                            appendLine("\n\n### 📦 Generated Deliverables & Artifacts")
                            taskState.artifacts.forEach { art ->
                                val mark = if (art.valid) "✓" else "⚠"
                                appendLine("- **${art.filename}** ($mark ${art.verificationStatus}, ${art.size} bytes)")
                                if (art.verificationDetails.isNotBlank()) {
                                    appendLine("  *${art.verificationDetails}*")
                                }
                            }
                        }
                        appendLine("\n### 🔍 Objective Verification Status")
                        appendLine(if (objVerification.isPassed) "✓ All required deliverables and criteria verified successfully." else "⚠ Objective status: ${objVerification.details}")
                    }

                    streamAndCompleteResponse(
                        sessionId = session.id,
                        assistantMessageId = assistantMessageId,
                        streamingAssistantMessage = streamingAssistantMessage,
                        fullReplyText = formattedResponse,
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
        val safeReplyText = if (fullReplyText.trim().equals("null", ignoreCase = true) || fullReplyText.isBlank()) {
            val lastTool = AgentExecutionCoordinator.activeSession.value?.toolExecutions?.lastOrNull()
            if (lastTool != null && !lastTool.isSuccess) {
                "Execution concluded. The last command resulted in: ${lastTool.error?.take(200)}. Created files and outputs are preserved in the workspace."
            } else {
                "Objective concluded. Artifacts and outputs have been generated and saved to the workspace."
            }
        } else {
            fullReplyText
        }

        val responseTimestamp = System.currentTimeMillis()
        val words = safeReplyText.split(Regex("(?<=\\s)|(?=\\s)"))
        val accumulated = StringBuilder()

        for (token in words) {
            accumulated.append(token)
            AgentExecutionCoordinator.updateStreamingContent(sessionId, assistantMessageId, accumulated.toString())
            val delayMs = if (token.isBlank()) 8L else (14L + (token.length % 5) * 2L)
            delay(delayMs)
        }

        val finalAssistantMessage = streamingAssistantMessage.copy(
            content = safeReplyText,
            isStreaming = false,
            timestamp = responseTimestamp
        )

        val updatedSteps = AgentExecutionCoordinator.activeSession.value?.steps?.map { step ->
            if (plan.isCompleted) step.copy(status = AgentTaskStatus.COMPLETED) else step
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
        maxRecentTurns: Int = 6,
        maxMessageChars: Int = 3500
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
            AgentTaskStatus.THINKING, AgentTaskStatus.PLANNING, AgentTaskStatus.REPLANNING -> "Agent: Planning"
            AgentTaskStatus.USING_TOOL, AgentTaskStatus.EXECUTING, AgentTaskStatus.READY_TO_ACT -> if (actionDescription.isNotBlank()) actionDescription else "Agent: Executing Command"
            AgentTaskStatus.OBSERVING, AgentTaskStatus.VERIFYING -> "Agent: Observing & Verifying"
            AgentTaskStatus.IN_PROGRESS -> "Agent: Working"
            AgentTaskStatus.COMPLETED -> "Agent: Task Completed"
            AgentTaskStatus.PAUSED, AgentTaskStatus.WAITING_FOR_USER -> "Agent: Paused"
            AgentTaskStatus.FAILED, AgentTaskStatus.BLOCKED -> "Agent: Task Failed"
            AgentTaskStatus.INITIALIZING, AgentTaskStatus.CREATED -> "Agent: Initializing Environment"
            else -> "Agent: ${status.displayName}"
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

        private const val MAX_AUTONOMOUS_TOOL_STEPS = 100
        private const val MAX_CONSECUTIVE_NUDGES = 10
        private const val MAX_TOTAL_NUDGES = 25
    }
}
