package com.example.ui.viewmodel

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.speech.tts.TextToSpeech
import android.util.Base64
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.agent.plan.AgentFlailingDetector
import com.example.agent.plan.Subtask
import com.example.agent.plan.SubtaskStatus
import com.example.agent.plan.TaskPlan
import com.example.agent.plan.TaskPlanner
import com.example.agent.service.AgentExecutionCoordinator
import com.example.agent.service.AgentExecutionService
import com.example.agent.tools.ToolCall
import com.example.agent.tools.ToolCallParser
import com.example.agent.tools.ToolRegistry
import com.example.agent.tools.ToolResult
import com.example.agent.tools.workspace.AgentWorkspaceContext
import com.example.agent.tools.workspace.AgentWorkspaceManager
import com.example.agent.tools.workspace.FileChangeType
import com.example.agent.tools.workspace.VerificationStatus
import java.io.File
import com.example.agent.termux.TermuxCommandRunner
import com.example.agent.termux.TermuxEnvironmentManager
import com.example.data.model.TerminalLine
import com.example.data.model.TerminalLineType
import com.example.data.model.TerminalSessionState
import com.example.data.local.ChatDatabase
import com.example.data.model.AgentReflection
import com.example.data.model.AgentSession
import com.example.data.model.AgentStep
import com.example.data.model.AgentTaskStatus
import com.example.data.model.AppMode
import com.example.data.model.ChatMessage
import com.example.data.model.Conversation
import com.example.data.model.MessageRole
import com.example.data.model.ToolExecutionRecord
import com.example.data.preferences.SettingsManager
import com.example.data.rag.RagDocument
import com.example.data.rag.RagEngine
import com.example.data.remote.NvidiaApiClient
import com.example.data.repository.AgentPlanRepository
import com.example.data.repository.ChatRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.content.ContentResolver
import android.provider.OpenableColumns
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.UUID

data class ChatUiState(
    val currentConversation: Conversation? = null,
    val messages: List<ChatMessage> = emptyList(),
    val conversations: List<Conversation> = emptyList(),
    val inputText: String = "",
    val selectedImageUri: Uri? = null,
    val selectedImageBase64: String? = null,
    val attachedDocument: RagDocument? = null,
    val isRagIndexing: Boolean = false,
    val isLoading: Boolean = false,
    val selectedModel: String = SettingsManager.MODEL_QUANTUM,
    val userName: String = SettingsManager.DEFAULT_USER_NAME,
    val planType: String = "Free plan",
    val isIncognito: Boolean = false,
    val isVoiceModeOpen: Boolean = false,
    val voiceStatusText: String = "Listening...",
    val voiceTranscriptText: String = "",
    val isVoiceMuted: Boolean = false,
    val isModelSheetOpen: Boolean = false,
    val isUpgradeDialogOpen: Boolean = false,
    val isApiKeyDialogOpen: Boolean = false,
    val isAttachmentSheetOpen: Boolean = false,
    val isParametersSheetOpen: Boolean = false,
    val isAboutDialogOpen: Boolean = false,
    val isVoiceRecording: Boolean = false,
    val currentlySpeakingMessageId: String? = null,
    val errorMessage: String? = null,
    val apiKey: String = "",
    val systemPrompt: String = SettingsManager.DEFAULT_SYSTEM_PROMPT,
    val temperature: Float = SettingsManager.DEFAULT_TEMPERATURE,
    val topP: Float = SettingsManager.DEFAULT_TOP_P,
    val maxTokens: Int = SettingsManager.DEFAULT_MAX_TOKENS,
    val currentMode: AppMode = AppMode.CHAT,
    val currentAgentSession: AgentSession? = null,
    val agentSessions: List<AgentSession> = emptyList(),
    val agentInputText: String = "",
    val isAgentLoading: Boolean = false,
    val agentErrorMessage: String? = null,
    val lastUploadedZip: UploadedZipInfo? = null,
    val isWorkspaceSheetOpen: Boolean = false,
    val isTerminalVisible: Boolean = false
)

data class UploadedZipInfo(
    val fileName: String,
    val totalFiles: Int,
    val totalBytes: Long,
    val extractedPaths: List<String>
)

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val database = ChatDatabase.getDatabase(application)
    private val settingsManager = SettingsManager(application)
    private val apiClient = NvidiaApiClient { settingsManager.getEffectiveApiKey() }
    private val repository = ChatRepository(database.chatDao(), settingsManager, apiClient)
    private val agentPlanRepository = AgentPlanRepository(database.agentPlanDao(), database.chatDao())
    private val taskPlanner = TaskPlanner()
    private val workspaceManager = AgentWorkspaceManager.init(File(application.filesDir, "agent_workspaces"))
    private val toolRegistry = ToolRegistry.defaultRegistry(workspaceManager)

    private val termuxEnvManager = TermuxEnvironmentManager.getInstance()
    private val termuxRunner = TermuxCommandRunner(
        envManager = termuxEnvManager,
        workspaceManager = workspaceManager
    )

    private val _terminalState = MutableStateFlow(
        TerminalSessionState(
            isEnvInitialized = termuxEnvManager.isInitialized,
            workingDirectory = "~/workspace"
        )
    )
    val terminalState: StateFlow<TerminalSessionState> = _terminalState.asStateFlow()

    fun getToolRegistry(): ToolRegistry = toolRegistry
    fun getWorkspaceManager(): AgentWorkspaceManager = workspaceManager
    fun getAgentPlanRepository(): AgentPlanRepository = agentPlanRepository
    fun getTaskPlanner(): TaskPlanner = taskPlanner

    private val _uiState = MutableStateFlow(
        ChatUiState(
            selectedModel = settingsManager.getSelectedModel(),
            userName = settingsManager.getUserName(),
            planType = settingsManager.getPlanType(),
            isIncognito = settingsManager.isIncognito(),
            apiKey = settingsManager.getEffectiveApiKey(),
            systemPrompt = settingsManager.getSystemPrompt(),
            temperature = settingsManager.getTemperature(),
            topP = settingsManager.getTopP(),
            maxTokens = settingsManager.getMaxTokens(),
            isTerminalVisible = settingsManager.isShowTerminal()
        )
    )
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private val _availableModels = MutableStateFlow<List<com.example.ui.components.ModelOption>>(com.example.ui.components.AvailableModels)
    val availableModels: StateFlow<List<com.example.ui.components.ModelOption>> = _availableModels.asStateFlow()

    private val _isSyncingModels = MutableStateFlow(false)
    val isSyncingModels: StateFlow<Boolean> = _isSyncingModels.asStateFlow()

    fun syncModelsFromNvidia() {
        viewModelScope.launch {
            _isSyncingModels.value = true
            try {
                val result = apiClient.fetchAvailableModels()
                result.onSuccess { remoteItems ->
                    if (remoteItems.isNotEmpty()) {
                        // Filter out non-chat models (embeddings, reward, safety classifiers, parsers)
                        val chatModels = remoteItems.filter { item ->
                            val id = item.id.lowercase()
                            !id.contains("embed") && !id.contains("reward") &&
                                    !id.contains("detector") && !id.contains("guard") &&
                                    !id.contains("clip") && !id.contains("parse") &&
                                    !id.contains("synthetic") && !id.contains("calibration")
                        }
                        val mapped = chatModels.map { com.example.ui.components.modelIdToOption(it.id) }
                        _availableModels.value = listOf(com.example.ui.components.Quantum3Option) + mapped.filter { it.id != "Quantum 3" }
                    }
                }
            } catch (_: Exception) {
            }
            _isSyncingModels.value = false
        }
    }

    private var tts: TextToSpeech? = null
    private var messageCollectionJob: Job? = null
    private var generationJob: Job? = null

    init {
        // Initial sync of live models from build.nvidia.com
        syncModelsFromNvidia()
        // Observe conversation history
        viewModelScope.launch {
            repository.allConversations.collectLatest { convs ->
                _uiState.update { it.copy(conversations = convs) }
            }
        }

        // Observe persisted Agent sessions reactively from Room database (single source of truth)
        viewModelScope.launch {
            agentPlanRepository.observeAllAgentSessions().collectLatest { sessions ->
                _uiState.update { state ->
                    val currentId = state.currentAgentSession?.id
                    val updatedCurrent = if (currentId != null) {
                        sessions.find { it.id == currentId }
                    } else {
                        state.currentAgentSession
                    }
                    state.copy(
                        agentSessions = sessions,
                        currentAgentSession = updatedCurrent
                    )
                }
            }
        }

        // Observe Foreground Service active agent session updates (for live progress of running task)
        viewModelScope.launch {
            AgentExecutionCoordinator.activeSession.collectLatest { active ->
                if (active != null) {
                    _uiState.update { state ->
                        // Never resurrect deleted sessions
                        val sessionExists = state.agentSessions.any { it.id == active.id }
                        if (!sessionExists) return@update state

                        val updatedSessions = state.agentSessions.map { if (it.id == active.id) active else it }
                        val isSameCurrent = state.currentAgentSession?.id == active.id
                        state.copy(
                            currentAgentSession = if (isSameCurrent) active else state.currentAgentSession,
                            agentSessions = updatedSessions
                        )
                    }
                }
            }
        }

        // Observe Foreground Service running state
        viewModelScope.launch {
            AgentExecutionCoordinator.isRunning.collectLatest { running ->
                _uiState.update { it.copy(isAgentLoading = running) }
                _terminalState.update { it.copy(isAgentRunning = running) }
            }
        }

        // Observe Foreground Service action descriptions for terminal header
        viewModelScope.launch {
            AgentExecutionCoordinator.currentActionDescription.collectLatest { action ->
                _terminalState.update { it.copy(currentAgentAction = action) }
            }
        }

        // Live stream Agent terminal lines into the terminal buffer
        viewModelScope.launch {
            AgentExecutionCoordinator.terminalEvents.collect { line ->
                _terminalState.update { current ->
                    current.copy(lines = (current.lines + line).takeLast(1000))
                }
            }
        }

        // Observe Foreground Service error messages
        viewModelScope.launch {
            AgentExecutionCoordinator.errorMessage.collectLatest { err ->
                if (err != null) {
                    _uiState.update { it.copy(agentErrorMessage = err) }
                }
            }
        }

        // Initialize TTS
        initTts(application)

        // Ground-up initialization of Termux and Ubuntu environment on first run
        viewModelScope.launch {
            if (!termuxEnvManager.isInitialized) {
                initializeTermuxEnvironment(force = false)
            }
        }
    }

    private fun initTts(context: Context) {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.getDefault()
                tts?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {
                        _uiState.update { current ->
                            if (current.currentlySpeakingMessageId == utteranceId) {
                                current.copy(currentlySpeakingMessageId = null)
                            } else current
                        }
                    }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        _uiState.update { current ->
                            if (current.currentlySpeakingMessageId == utteranceId) {
                                current.copy(currentlySpeakingMessageId = null)
                            } else current
                        }
                    }
                })
            }
        }
    }

    fun onInputTextChanged(text: String) {
        _uiState.update { it.copy(inputText = text) }
    }

    fun onSelectImageUri(uri: Uri?) {
        if (uri == null) {
            _uiState.update { it.copy(selectedImageUri = null, selectedImageBase64 = null) }
            return
        }

        viewModelScope.launch {
            val base64 = encodeUriToBase64(getApplication(), uri)
            _uiState.update { it.copy(selectedImageUri = uri, selectedImageBase64 = base64) }
        }
    }

    fun onRemoveSelectedImage() {
        _uiState.update { it.copy(selectedImageUri = null, selectedImageBase64 = null) }
    }

    private fun getFileNameFromUri(uri: Uri): String {
        var name = "archive.zip"
        try {
            if (uri.scheme == ContentResolver.SCHEME_CONTENT) {
                val cursor = getApplication<Application>().contentResolver.query(uri, null, null, null, null)
                cursor?.use {
                    if (it.moveToFirst()) {
                        val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (nameIndex != -1) {
                            val displayName = it.getString(nameIndex)
                            if (!displayName.isNullOrBlank()) {
                                name = displayName
                            }
                        }
                    }
                }
            } else if (uri.scheme == ContentResolver.SCHEME_FILE || uri.path != null) {
                val path = uri.path
                if (path != null) {
                    name = File(path).name
                }
            }
        } catch (_: Exception) {
        }
        return name
    }

    fun onUploadZipFile(uri: Uri, targetSubdir: String = ".") {
        _uiState.update { it.copy(isAgentLoading = true, agentErrorMessage = null) }
        viewModelScope.launch {
            try {
                val fileName = getFileNameFromUri(uri)
                val inputStream = getApplication<Application>().contentResolver.openInputStream(uri)
                if (inputStream == null) {
                    _uiState.update {
                        it.copy(
                            isAgentLoading = false,
                            agentErrorMessage = "Failed to open input stream for $fileName"
                        )
                    }
                    return@launch
                }

                val extractResult = withContext(Dispatchers.IO) {
                    inputStream.use { stream ->
                        val destDir = if (targetSubdir.isBlank() || targetSubdir == ".") {
                            workspaceManager.getWorkspaceDir()
                        } else {
                            workspaceManager.resolvePath(targetSubdir)
                        }
                        workspaceManager.extractZipStream(
                            inputStream = stream,
                            destinationDir = destDir,
                            archiveName = fileName,
                            overwrite = true
                        )
                    }
                }

                if (extractResult.isSuccess) {
                    val zipInfo = UploadedZipInfo(
                        fileName = fileName,
                        totalFiles = extractResult.totalFiles,
                        totalBytes = extractResult.totalBytes,
                        extractedPaths = extractResult.extractedPaths
                    )

                    val previewList = if (extractResult.extractedPaths.size <= 10) {
                        extractResult.extractedPaths.joinToString("\n- ") { it }
                    } else {
                        extractResult.extractedPaths.take(10).joinToString("\n- ") { it } +
                            "\n... and ${extractResult.extractedPaths.size - 10} more files"
                    }

                    val prepopulatedPrompt = "I have uploaded the project codebase '$fileName' (${extractResult.totalFiles} files extracted into workspace).\n" +
                        "Key extracted files:\n- $previewList\n\n" +
                        "Please analyze the codebase architecture, review the key source files, and tell me what logical improvements, bug fixes, or enhancements you can make."

                    _uiState.update {
                        it.copy(
                            currentMode = AppMode.AGENT,
                            isAgentLoading = false,
                            lastUploadedZip = zipInfo,
                            agentInputText = prepopulatedPrompt
                        )
                    }
                } else {
                    _uiState.update {
                        it.copy(
                            isAgentLoading = false,
                            agentErrorMessage = "Failed to extract $fileName: ${extractResult.errorMessage}"
                        )
                    }
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isAgentLoading = false,
                        agentErrorMessage = "Error extracting zip archive: ${e.message}"
                    )
                }
            }
        }
    }

    fun onSelectDocumentUri(uri: Uri) {
        val fileName = getFileNameFromUri(uri)
        if (fileName.endsWith(".zip", ignoreCase = true)) {
            onUploadZipFile(uri)
            return
        }

        _uiState.update { it.copy(isRagIndexing = true, errorMessage = null) }
        viewModelScope.launch {
            val result = RagEngine.indexDocument(getApplication(), uri)
            result.onSuccess { doc ->
                _uiState.update {
                    it.copy(
                        attachedDocument = doc,
                        isRagIndexing = false
                    )
                }
            }.onFailure { err ->
                _uiState.update {
                    it.copy(
                        isRagIndexing = false,
                        errorMessage = "Document indexing error: ${err.message ?: "Could not read file."}"
                    )
                }
            }
        }
    }

    fun onRemoveAttachedDocument() {
        _uiState.update { it.copy(attachedDocument = null) }
    }

    fun onSendMessage(overrideText: String? = null) {
        val textToSend = overrideText ?: _uiState.value.inputText
        val imageBase64ToSend = _uiState.value.selectedImageBase64
        val imageUriToSend = _uiState.value.selectedImageUri?.toString()
        val docToUse = _uiState.value.attachedDocument

        if (textToSend.isBlank() && imageBase64ToSend == null) return

        generationJob?.cancel()

        val state = _uiState.value
        val conversationId = state.currentConversation?.id ?: UUID.randomUUID().toString()
        val isIncognito = state.isIncognito

        val userMessage = ChatMessage(
            id = UUID.randomUUID().toString(),
            conversationId = conversationId,
            role = MessageRole.USER,
            content = textToSend,
            imageBase64 = imageBase64ToSend,
            imageUri = imageUriToSend,
            modelUsed = state.selectedModel
        )

        // Clear input field immediately and optimistically add userMessage to UI
        _uiState.update {
            it.copy(
                inputText = "",
                selectedImageUri = null,
                selectedImageBase64 = null,
                isLoading = true,
                errorMessage = null,
                messages = it.messages + userMessage
            )
        }

        generationJob = viewModelScope.launch {
            // Ensure conversation exists in state
            if (state.currentConversation == null) {
                val newConv = Conversation(
                    id = conversationId,
                    title = textToSend.take(30).ifBlank { "New Chat" },
                    modelName = state.selectedModel,
                    isIncognito = isIncognito
                )
                _uiState.update { it.copy(currentConversation = newConv) }
                startCollectingMessages(newConv.id)
            }

            // Save user message to database
            repository.saveMessage(userMessage, isIncognito)

            // Prepare RAG Grounding Context if a document is attached
            val ragContext = if (docToUse != null) {
                val relevantChunks = RagEngine.retrieveRelevantContext(
                    query = textToSend,
                    document = docToUse,
                    topK = 4
                )
                RagEngine.formatRagPrompt(relevantChunks, docToUse.name)
            } else null

            // Create initial placeholder streaming assistant message
            val assistantMessageId = UUID.randomUUID().toString()
            val streamingAssistantMessage = ChatMessage(
                id = assistantMessageId,
                conversationId = conversationId,
                role = MessageRole.ASSISTANT,
                content = "",
                modelUsed = state.selectedModel,
                isStreaming = true
            )

            _uiState.update { current ->
                current.copy(messages = current.messages + streamingAssistantMessage)
            }

            // Request AI Completion
            val history = _uiState.value.messages.filter {
                it.id != userMessage.id && it.id != assistantMessageId && !it.isError
            }

            val result = repository.requestAiCompletion(
                history = history,
                userMessage = userMessage,
                modelName = state.selectedModel,
                ragContext = ragContext,
                systemPrompt = state.systemPrompt,
                temperature = state.temperature,
                topP = state.topP,
                maxTokens = state.maxTokens
            )

            result.onSuccess { fullReplyText ->
                // ChatGPT smooth animated typewriter streaming playback
                val words = fullReplyText.split(Regex("(?<=\\s)|(?=\\s)"))
                val accumulated = StringBuilder()

                for (token in words) {
                    accumulated.append(token)
                    val currentText = accumulated.toString()

                    _uiState.update { cur ->
                        cur.copy(
                            messages = cur.messages.map { msg ->
                                if (msg.id == assistantMessageId) {
                                    msg.copy(content = currentText, isStreaming = true)
                                } else msg
                            }
                        )
                    }
                    // Realistic typing speed: faster for whitespace, rhythmic for words
                    val delayMs = if (token.isBlank()) 10L else (16L + (token.length % 5) * 2L)
                    delay(delayMs)
                }

                // Finalize streaming
                val finalAssistantMessage = streamingAssistantMessage.copy(
                    content = fullReplyText,
                    isStreaming = false
                )

                repository.saveMessage(finalAssistantMessage, isIncognito)

                _uiState.update { current ->
                    current.copy(
                        isLoading = false,
                        messages = current.messages.map { msg ->
                            if (msg.id == assistantMessageId) finalAssistantMessage else msg
                        }
                    )
                }
            }.onFailure { error ->
                val errorMessage = streamingAssistantMessage.copy(
                    content = "Error: ${error.message ?: "Could not complete request."}",
                    isStreaming = false,
                    isError = true
                )

                repository.saveMessage(errorMessage, isIncognito)

                _uiState.update { current ->
                    current.copy(
                        isLoading = false,
                        errorMessage = error.message,
                        messages = current.messages.map { msg ->
                            if (msg.id == assistantMessageId) errorMessage else msg
                        }
                    )
                }
            }
        }
    }

    fun stopGeneration() {
        generationJob?.cancel()
        val state = _uiState.value
        val streamingMsg = state.messages.lastOrNull { it.isStreaming }

        if (streamingMsg != null && streamingMsg.content.isNotBlank()) {
            val finalized = streamingMsg.copy(isStreaming = false)
            viewModelScope.launch {
                repository.saveMessage(finalized, state.isIncognito)
            }
            _uiState.update { cur ->
                cur.copy(
                    isLoading = false,
                    messages = cur.messages.map { if (it.id == streamingMsg.id) finalized else it }
                )
            }
        } else {
            _uiState.update { cur ->
                cur.copy(
                    isLoading = false,
                    messages = cur.messages.filterNot { it.isStreaming && it.content.isBlank() }
                )
            }
        }
    }

    fun clearCurrentConversationMessages() {
        stopSpeaking()
        generationJob?.cancel()
        val convId = _uiState.value.currentConversation?.id
        _uiState.update { it.copy(messages = emptyList(), isLoading = false, errorMessage = null) }
        if (convId != null) {
            viewModelScope.launch {
                repository.deleteMessagesForConversation(convId)
            }
        }
    }

    fun startNewChat() {
        stopSpeaking()
        generationJob?.cancel()
        messageCollectionJob?.cancel()
        _uiState.update {
            it.copy(
                currentConversation = null,
                messages = emptyList(),
                inputText = "",
                selectedImageUri = null,
                selectedImageBase64 = null,
                attachedDocument = null,
                errorMessage = null,
                isLoading = false
            )
        }
    }

    fun selectConversation(conversation: Conversation) {
        stopSpeaking()
        generationJob?.cancel()
        _uiState.update {
            it.copy(
                currentConversation = conversation,
                selectedModel = conversation.modelName,
                isIncognito = conversation.isIncognito,
                inputText = "",
                selectedImageUri = null,
                selectedImageBase64 = null,
                errorMessage = null,
                isLoading = false
            )
        }
        startCollectingMessages(conversation.id)
    }

    private fun startCollectingMessages(conversationId: String) {
        messageCollectionJob?.cancel()
        messageCollectionJob = viewModelScope.launch {
            repository.getMessagesForConversation(conversationId).collectLatest { msgs ->
                // Don't overwrite if generation is active
                if (_uiState.value.isLoading) return@collectLatest
                _uiState.update { it.copy(messages = msgs) }
            }
        }
    }

    fun deleteConversation(id: String) {
        viewModelScope.launch {
            repository.deleteConversation(id)
            if (_uiState.value.currentConversation?.id == id) {
                startNewChat()
            }
        }
    }

    fun clearAllHistory() {
        viewModelScope.launch {
            repository.deleteAllConversations()
            startNewChat()
        }
    }

    fun toggleIncognito() {
        val newState = !_uiState.value.isIncognito
        settingsManager.setIncognito(newState)
        _uiState.update { it.copy(isIncognito = newState) }
    }

    fun setSelectedModel(model: String) {
        settingsManager.setSelectedModel(model)
        _uiState.update { it.copy(selectedModel = model) }
    }

    fun setPlanType(plan: String) {
        settingsManager.setPlanType(plan)
        _uiState.update { it.copy(planType = plan) }
    }

    fun setCustomApiKey(key: String) {
        settingsManager.setCustomApiKey(key)
        _uiState.update { it.copy(apiKey = settingsManager.getEffectiveApiKey()) }
    }

    fun setUserName(name: String) {
        settingsManager.setUserName(name)
        _uiState.update { it.copy(userName = name) }
    }

    // Modal Sheet Controllers
    fun setModelSheetOpen(open: Boolean) = _uiState.update { it.copy(isModelSheetOpen = open) }
    fun setUpgradeDialogOpen(open: Boolean) = _uiState.update { it.copy(isUpgradeDialogOpen = open) }
    fun setApiKeyDialogOpen(open: Boolean) = _uiState.update { it.copy(isApiKeyDialogOpen = open) }
    fun setAttachmentSheetOpen(open: Boolean) = _uiState.update { it.copy(isAttachmentSheetOpen = open) }
    fun setParametersSheetOpen(open: Boolean) = _uiState.update { it.copy(isParametersSheetOpen = open) }
    fun setAboutDialogOpen(open: Boolean) = _uiState.update { it.copy(isAboutDialogOpen = open) }
    fun setWorkspaceSheetOpen(open: Boolean) = _uiState.update { it.copy(isWorkspaceSheetOpen = open) }

    fun getWorkspaceFiles(): List<com.example.agent.tools.workspace.WorkspaceFileInfo> {
        val sessionId = _uiState.value.currentAgentSession?.id ?: "default"
        return workspaceManager.listWorkspaceFiles(sessionId)
    }

    fun getWorkspaceStats(): com.example.agent.tools.workspace.WorkspaceStats {
        val sessionId = _uiState.value.currentAgentSession?.id ?: "default"
        return workspaceManager.getWorkspaceStats(sessionId)
    }

    fun readWorkspaceFile(relativePath: String): String {
        val sessionId = _uiState.value.currentAgentSession?.id ?: "default"
        return workspaceManager.readWorkspaceFile(sessionId, relativePath)
    }

    fun exportWorkspaceZip(context: Context): Uri? {
        return try {
            val sessionId = _uiState.value.currentAgentSession?.id ?: "default"
            val exportDir = File(context.cacheDir, "exports").apply { mkdirs() }
            val zipFile = File(exportDir, "workspace_${sessionId.take(6)}.zip")
            workspaceManager.exportWorkspaceToZip(sessionId, zipFile)
            androidx.core.content.FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                zipFile
            )
        } catch (_: Exception) {
            null
        }
    }

    fun updateParameters(
        systemPrompt: String,
        temperature: Float,
        topP: Float,
        maxTokens: Int
    ) {
        settingsManager.setSystemPrompt(systemPrompt)
        settingsManager.setTemperature(temperature)
        settingsManager.setTopP(topP)
        settingsManager.setMaxTokens(maxTokens)

        _uiState.update {
            it.copy(
                systemPrompt = systemPrompt,
                temperature = temperature,
                topP = topP,
                maxTokens = maxTokens
            )
        }
    }
    fun setVoiceModeOpen(open: Boolean) {
        if (!open) {
            stopSpeaking()
        }
        _uiState.update {
            it.copy(
                isVoiceModeOpen = open,
                voiceStatusText = if (open) "Listening..." else "",
                voiceTranscriptText = ""
            )
        }
    }

    fun updateVoiceStatus(status: String) {
        _uiState.update { it.copy(voiceStatusText = status) }
    }

    fun updateVoiceTranscript(transcript: String) {
        _uiState.update { it.copy(voiceTranscriptText = transcript) }
    }

    fun toggleVoiceMute() = _uiState.update { it.copy(isVoiceMuted = !it.isVoiceMuted) }

    fun speakText(text: String, messageId: String? = null) {
        if (text.isBlank()) return
        stopSpeaking()
        val utteranceId = messageId ?: UUID.randomUUID().toString()
        _uiState.update { it.copy(currentlySpeakingMessageId = utteranceId) }
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
    }

    fun stopSpeaking() {
        tts?.stop()
        _uiState.update { it.copy(currentlySpeakingMessageId = null) }
    }

    private fun encodeUriToBase64(context: Context, uri: Uri): String? {
        return try {
            val contentResolver = context.contentResolver
            val maxDim = 1024

            // Step 1: Decode image dimensions only
            val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, boundsOptions)
            } ?: return null

            val rawWidth = boundsOptions.outWidth
            val rawHeight = boundsOptions.outHeight
            if (rawWidth <= 0 || rawHeight <= 0) return null

            // Step 2: Compute inSampleSize to avoid loading huge raw pixels into memory
            var inSampleSize = 1
            var halfWidth = rawWidth
            var halfHeight = rawHeight
            while (halfWidth / inSampleSize > maxDim * 2 || halfHeight / inSampleSize > maxDim * 2) {
                inSampleSize *= 2
            }

            // Step 3: Decode sampled bitmap
            val decodeOptions = BitmapFactory.Options().apply {
                this.inSampleSize = inSampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val sampledBitmap = contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, decodeOptions)
            } ?: return null

            // Step 4: Scale to exact target max dimension if still larger than maxDim
            val maxCurrentDim = maxOf(sampledBitmap.width, sampledBitmap.height)
            val scale = if (maxCurrentDim > maxDim) maxDim.toFloat() / maxCurrentDim else 1f
            val finalBitmap = if (scale < 1f) {
                val scaled = Bitmap.createScaledBitmap(
                    sampledBitmap,
                    (sampledBitmap.width * scale).toInt().coerceAtLeast(1),
                    (sampledBitmap.height * scale).toInt().coerceAtLeast(1),
                    true
                )
                if (scaled != sampledBitmap) {
                    sampledBitmap.recycle()
                }
                scaled
            } else {
                sampledBitmap
            }

            val out = ByteArrayOutputStream()
            finalBitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
            if (!finalBitmap.isRecycled && finalBitmap != sampledBitmap) {
                finalBitmap.recycle()
            }
            Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        } catch (t: Throwable) {
            null
        }
    }

    // Mode Management
    fun setAppMode(mode: AppMode) {
        stopSpeaking()
        _uiState.update { it.copy(currentMode = mode) }
    }

    // Terminal Mode Management
    fun onTerminalInputChanged(text: String) {
        _terminalState.update { it.copy(currentInput = text) }
    }

    fun clearTerminal() {
        _terminalState.update { it.copy(lines = emptyList(), historyIndex = -1) }
    }

    fun initializeTermuxEnvironment(force: Boolean = false) {
        if (_terminalState.value.isInitializingEnv) return
        viewModelScope.launch {
            _terminalState.update {
                it.copy(
                    isInitializingEnv = true,
                    lines = it.lines + TerminalLine(
                        type = TerminalLineType.SYSTEM_INFO,
                        text = ">>> [Termux Bootstrap] Initializing Andronix Ubuntu 22.04 LTS CLI environment..."
                    )
                )
            }

            val success = termuxEnvManager.ensureInitialized(force = force) { logLine ->
                _terminalState.update { current ->
                    current.copy(
                        lines = current.lines + TerminalLine(
                            type = TerminalLineType.STDOUT,
                            text = logLine
                        )
                    )
                }
            }

            _terminalState.update {
                it.copy(
                    isInitializingEnv = false,
                    isEnvInitialized = success,
                    lines = it.lines + TerminalLine(
                        type = if (success) TerminalLineType.SYSTEM_INFO else TerminalLineType.STDERR,
                        text = if (success) ">>> [Termux Bootstrap] Andronix Ubuntu 22.04 LTS CLI environment ready on Android kernel (/system/bin/sh)."
                               else ">>> [Termux Bootstrap] Initialization finished with fallback mode."
                    )
                )
            }
        }
    }

    fun executeTerminalCommand(rawCommand: String) {
        val command = rawCommand.trim()
        if (command.isBlank() || _terminalState.value.isRunning) return

        viewModelScope.launch {
            val inputLine = TerminalLine(type = TerminalLineType.INPUT, text = command)
            val updatedHistory = (_terminalState.value.history + command).takeLast(100)

            _terminalState.update {
                it.copy(
                    lines = it.lines + inputLine,
                    currentInput = "",
                    isRunning = true,
                    history = updatedHistory,
                    historyIndex = -1
                )
            }

            // Handle built-in clear
            if (command == "clear" || command == "cls") {
                _terminalState.update { it.copy(lines = emptyList(), isRunning = false) }
                return@launch
            }

            // Handle CTRL+C / interrupt signal cleanly without calling /system/bin/sh: ^C
            if (command == "^C" || command == "\u0003" || command.endsWith("^C")) {
                _terminalState.update {
                    it.copy(
                        currentInput = "",
                        isRunning = false
                    )
                }
                return@launch
            }

            // Handle interactive cd directory changes in terminal shell
            if (command == "cd" || command.startsWith("cd ")) {
                val targetArg = command.removePrefix("cd").trim().trim('\'', '"')
                val workspaceRoot = workspaceManager.getWorkspaceDir()
                val currentCwd = workspaceManager.getCurrentWorkingDir()
                val targetDir = when {
                    targetArg.isEmpty() || targetArg == "~" || targetArg == "~/workspace" || targetArg == "/workspace" -> workspaceRoot
                    File(targetArg).isAbsolute && File(targetArg).exists() && File(targetArg).isDirectory -> File(targetArg).canonicalFile
                    File(currentCwd, targetArg).exists() && File(currentCwd, targetArg).isDirectory -> File(currentCwd, targetArg).canonicalFile
                    else -> try {
                        val resolved = workspaceManager.resolvePath(targetArg)
                        if (resolved.exists() && resolved.isDirectory) resolved else null
                    } catch (_: Exception) { null }
                }
                if (targetDir != null && targetDir.exists() && targetDir.isDirectory) {
                    workspaceManager.setCurrentWorkingDir(targetDir)
                    val relPath = try {
                        val rel = targetDir.relativeTo(workspaceRoot).path
                        if (rel.isBlank() || rel == ".") "~/workspace" else "~/workspace/$rel"
                    } catch (_: Exception) { targetDir.canonicalPath }
                    _terminalState.update { it.copy(workingDirectory = relPath, isRunning = false) }
                    return@launch
                } else {
                    _terminalState.update {
                        it.copy(
                            lines = it.lines + TerminalLine(type = TerminalLineType.STDERR, text = "bash: cd: $targetArg: No such file or directory", exitCode = 1),
                            isRunning = false
                        )
                    }
                    return@launch
                }
            }

            try {
                // Run command via real Linux process runner in current working directory
                val currentDir = workspaceManager.getCurrentWorkingDir()
                val result = termuxRunner.run(
                    command = command,
                    workingDir = currentDir,
                    timeoutMs = 60000L,
                    maxOutputBytes = 65536
                )

                val newLines = mutableListOf<TerminalLine>()
                if (result.stdout.isNotBlank()) {
                    val stdoutLines = result.stdout.trimEnd().lines()
                    stdoutLines.forEach { line ->
                        newLines.add(TerminalLine(type = TerminalLineType.STDOUT, text = line))
                    }
                }
                if (result.stderr.isNotBlank()) {
                    val stderrLines = result.stderr.trimEnd().lines()
                    stderrLines.forEach { line ->
                        newLines.add(TerminalLine(type = TerminalLineType.STDERR, text = line, exitCode = result.exitCode))
                    }
                }
                if (result.stdout.isBlank() && result.stderr.isBlank() && result.exitCode != 0) {
                    newLines.add(
                        TerminalLine(
                            type = TerminalLineType.STDERR,
                            text = "Process exited with code ${result.exitCode}",
                            exitCode = result.exitCode
                        )
                    )
                }

                _terminalState.update {
                    it.copy(
                        lines = it.lines + newLines,
                        isRunning = false
                    )
                }
            } catch (e: CancellationException) {
                _terminalState.update { it.copy(isRunning = false) }
                throw e
            } catch (e: Throwable) {
                _terminalState.update {
                    it.copy(
                        lines = it.lines + TerminalLine(
                            type = TerminalLineType.STDERR,
                            text = "Error executing command: ${e.message ?: e.toString()}",
                            exitCode = -1
                        ),
                        isRunning = false
                    )
                }
            }
        }
    }

    fun navigateTerminalHistory(direction: Int) {
        val history = _terminalState.value.history
        if (history.isEmpty()) return

        val currentIndex = _terminalState.value.historyIndex
        val nextIndex = if (direction < 0) {
            // UP (older)
            if (currentIndex == -1) history.size - 1 else (currentIndex - 1).coerceAtLeast(0)
        } else {
            // DOWN (newer)
            if (currentIndex == -1) -1 else (currentIndex + 1).coerceAtMost(history.size - 1)
        }

        val nextText = if (nextIndex in history.indices) history[nextIndex] else ""
        _terminalState.update {
            it.copy(
                currentInput = nextText,
                historyIndex = nextIndex
            )
        }
    }

    fun onAgentInputTextChanged(text: String) {
        _uiState.update { it.copy(agentInputText = text) }
    }

    fun startNewAgentSession() {
        AgentExecutionCoordinator.stopExecution(getApplication())
        workspaceManager.activeSessionId = "default"
        _uiState.update {
            it.copy(
                currentAgentSession = null,
                agentInputText = "",
                isAgentLoading = false,
                agentErrorMessage = null
            )
        }
    }

    fun selectAgentSession(session: AgentSession) {
        workspaceManager.activeSessionId = session.id
        AgentExecutionCoordinator.setActiveSession(session)
        _uiState.update {
            it.copy(
                currentAgentSession = session,
                agentInputText = "",
                isAgentLoading = AgentExecutionCoordinator.isRunning.value && AgentExecutionCoordinator.activeSession.value?.id == session.id,
                agentErrorMessage = null
            )
        }
        viewModelScope.launch {
            try {
                val plan = agentPlanRepository.getPlan(session.id)
                database.chatDao().getMessagesForConversation(session.id).collectLatest { messageEntities ->
                    val domainMessages = messageEntities.map { it.toDomain() }
                    _uiState.update { state ->
                        val current = state.currentAgentSession
                        if (current?.id == session.id) {
                            val updated = current.copy(
                                plan = plan ?: current.plan,
                                messages = if (domainMessages.isNotEmpty()) domainMessages else current.messages
                            )
                            state.copy(
                                currentAgentSession = updated,
                                agentSessions = state.agentSessions.map { if (it.id == updated.id) updated else it }
                            )
                        } else {
                            state
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }
    }

    fun selectAgentSessionById(sessionId: String) {
        val existing = _uiState.value.agentSessions.find { it.id == sessionId }
        if (existing != null) {
            selectAgentSession(existing)
        } else {
            viewModelScope.launch {
                try {
                    val loaded = agentPlanRepository.getAgentSession(sessionId)
                    if (loaded != null) {
                        selectAgentSession(loaded)
                    }
                } catch (_: Exception) {}
            }
        }
    }

    private var isCreatingTask = false

    fun setShowTerminal(enabled: Boolean) {
        settingsManager.setShowTerminal(enabled)
        _uiState.update { it.copy(isTerminalVisible = enabled) }
        if (!enabled && _uiState.value.currentMode == AppMode.TERMINAL) {
            setAppMode(AppMode.CHAT)
        }
    }

    fun deleteAgentSession(sessionId: String) {
        if (AgentExecutionCoordinator.activeSession.value?.id == sessionId) {
            AgentExecutionCoordinator.stopExecution(getApplication())
        }
        AgentExecutionCoordinator.clearSessionIf(sessionId)
        workspaceManager.deleteWorkspace(sessionId)
        _uiState.update { state ->
            val updatedSessions = state.agentSessions.filterNot { it.id == sessionId }
            val newCurrent = if (state.currentAgentSession?.id == sessionId) {
                updatedSessions.firstOrNull()
            } else {
                state.currentAgentSession
            }
            state.copy(
                agentSessions = updatedSessions,
                currentAgentSession = newCurrent
            )
        }
        viewModelScope.launch {
            try {
                agentPlanRepository.deleteAgentSession(sessionId)
            } catch (_: Exception) {
            }
        }
    }

    fun retrySubtask(subtaskId: String) {
        val current = _uiState.value.currentAgentSession ?: return
        val plan = current.plan ?: return
        val updatedPlan = taskPlanner.retrySubtask(plan, subtaskId)
        updateSessionPlan(updatedPlan)
    }

    fun verifySubtask(subtaskId: String) {
        val current = _uiState.value.currentAgentSession ?: return
        val plan = current.plan ?: return
        val updatedPlan = taskPlanner.verifyAndCompleteSubtask(plan, subtaskId, "Verified objective achieved")
        updateSessionPlan(updatedPlan)
    }

    private fun updateSessionPlan(plan: TaskPlan) {
        val curSession = _uiState.value.currentAgentSession ?: return
        AgentExecutionCoordinator.updatePlan(curSession.id, plan)
        _uiState.update { cur ->
            val curSess = cur.currentAgentSession ?: return@update cur
            val updatedStatus = if (plan.isCompleted) AgentTaskStatus.COMPLETED else curSess.status
            val s = curSess.copy(
                plan = plan,
                status = updatedStatus,
                updatedAt = System.currentTimeMillis()
            )
            cur.copy(
                currentAgentSession = s,
                agentSessions = cur.agentSessions.map { if (it.id == curSess.id) s else it }
            )
        }
        viewModelScope.launch {
            try {
                agentPlanRepository.savePlan(plan)
                _uiState.value.currentAgentSession?.let { agentPlanRepository.saveAgentSession(it) }
            } catch (_: Exception) {
            }
        }
    }

    fun updateAgentTaskStatus(status: AgentTaskStatus) {
        val current = _uiState.value.currentAgentSession ?: return
        if (status == AgentTaskStatus.PAUSED) {
            stopAgentGeneration()
            return
        }
        if (status == AgentTaskStatus.IN_PROGRESS && (current.status == AgentTaskStatus.PAUSED || current.status == AgentTaskStatus.FAILED)) {
            retryCurrentAgentTask()
            return
        }
        val updated = current.copy(
            status = status,
            updatedAt = System.currentTimeMillis()
        )
        AgentExecutionCoordinator.updateStatus(updated.id, status, "Status changed to ${status.displayName}")
        _uiState.update { state ->
            val updatedSessions = state.agentSessions.map {
                if (it.id == updated.id) updated else it
            }
            state.copy(
                currentAgentSession = updated,
                agentSessions = updatedSessions
            )
        }
        viewModelScope.launch {
            try {
                agentPlanRepository.saveAgentSession(updated)
            } catch (_: Exception) {}
        }
    }

    fun createAgentSession(goal: String) {
        val trimmedGoal = goal.trim()
        if (trimmedGoal.isBlank() || isCreatingTask) return
        isCreatingTask = true

        try {
            val state = _uiState.value
            val sessionId = UUID.randomUUID().toString()
            val now = System.currentTimeMillis()

            val initialPlan = taskPlanner.createInitialPlan(sessionId, trimmedGoal, MAX_AUTONOMOUS_TOOL_STEPS)

            val initialSteps = initialPlan.subtasks.map { subtask ->
                AgentStep(
                    id = subtask.id,
                    title = subtask.description,
                    description = subtask.verificationCriteria ?: "Verifiable milestone",
                    status = when (subtask.status) {
                        SubtaskStatus.COMPLETED -> AgentTaskStatus.COMPLETED
                        SubtaskStatus.RUNNING -> AgentTaskStatus.IN_PROGRESS
                        SubtaskStatus.FAILED -> AgentTaskStatus.FAILED
                        SubtaskStatus.PENDING -> AgentTaskStatus.INITIALIZING
                    },
                    timestamp = subtask.updatedAt
                )
            }

            val userGoalMessage = ChatMessage(
                id = UUID.randomUUID().toString(),
                conversationId = sessionId,
                role = MessageRole.USER,
                content = trimmedGoal,
                modelUsed = state.selectedModel,
                timestamp = now
            )

            val assistantMessageId = UUID.randomUUID().toString()
            val streamingAssistantMessage = ChatMessage(
                id = assistantMessageId,
                conversationId = sessionId,
                role = MessageRole.ASSISTANT,
                content = "",
                modelUsed = state.selectedModel,
                isStreaming = true,
                timestamp = now + 1
            )

            val newSession = AgentSession(
                id = sessionId,
                goal = trimmedGoal,
                status = AgentTaskStatus.IN_PROGRESS,
                createdAt = now,
                updatedAt = now,
                modelUsed = state.selectedModel,
                steps = initialSteps,
                plan = initialPlan,
                messages = listOf(userGoalMessage, streamingAssistantMessage)
            )

            _uiState.update {
                it.copy(
                    currentAgentSession = newSession,
                    agentSessions = listOf(newSession) + it.agentSessions.filterNot { s -> s.id == sessionId },
                    agentInputText = "",
                    isAgentLoading = true,
                    agentErrorMessage = null
                )
            }

            viewModelScope.launch {
                try {
                    agentPlanRepository.saveAgentSession(newSession)
                    agentPlanRepository.savePlan(initialPlan)
                } catch (_: Exception) {
                }
            }

            // Move the agent loop out of viewModelScope into Android ForegroundService with persistent notification
            AgentExecutionCoordinator.startSession(
                context = getApplication(),
                session = newSession,
                userMessage = userGoalMessage,
                initialSteps = initialSteps
            )
        } finally {
            isCreatingTask = false
        }
    }

    fun retryCurrentAgentTask() {
        sendAgentFollowUp("Please resume execution of the task from where it stopped and complete the remaining steps.")
    }

    fun sendAgentFollowUp(overrideText: String? = null) {
        val state = _uiState.value
        val currentSession = state.currentAgentSession ?: return
        val rawInput = (overrideText ?: state.agentInputText).trim()
        val textToSend = if (rawInput.isNotBlank()) {
            rawInput
        } else if (currentSession.status == AgentTaskStatus.FAILED || currentSession.status == AgentTaskStatus.PAUSED) {
            "Please resume execution of the task from where it stopped and complete the remaining steps."
        } else {
            return
        }

        val userMessage = ChatMessage(
            id = UUID.randomUUID().toString(),
            conversationId = currentSession.id,
            role = MessageRole.USER,
            content = textToSend,
            modelUsed = state.selectedModel
        )

        val assistantMessageId = UUID.randomUUID().toString()
        val streamingAssistantMessage = ChatMessage(
            id = assistantMessageId,
            conversationId = currentSession.id,
            role = MessageRole.ASSISTANT,
            content = "",
            modelUsed = state.selectedModel,
            isStreaming = true
        )

        val updatedMessagesWithUser = currentSession.messages + userMessage + streamingAssistantMessage
        val updatedSession = currentSession.copy(
            messages = updatedMessagesWithUser,
            status = AgentTaskStatus.IN_PROGRESS,
            updatedAt = System.currentTimeMillis()
        )

        _uiState.update {
            it.copy(
                agentInputText = "",
                isAgentLoading = true,
                agentErrorMessage = null,
                currentAgentSession = updatedSession,
                agentSessions = it.agentSessions.map { s -> if (s.id == currentSession.id) updatedSession else s }
            )
        }

        viewModelScope.launch {
            try {
                agentPlanRepository.saveAgentSession(updatedSession)
            } catch (_: Exception) {}
        }

        // Delegate agent loop to ForegroundService so it continues running when user switches apps
        AgentExecutionCoordinator.sendFollowUp(
            context = getApplication(),
            session = updatedSession,
            userMessage = userMessage
        )
    }

    fun stopAgentGeneration() {
        AgentExecutionCoordinator.stopExecution(getApplication())
        _uiState.update { cur ->
            val curSession = cur.currentAgentSession ?: return@update cur
            val updatedMessages = curSession.messages.map { msg ->
                if (msg.isStreaming) msg.copy(isStreaming = false) else msg
            }.filterNot { it.isStreaming && it.content.isBlank() }
            val s = curSession.copy(
                messages = updatedMessages,
                status = AgentTaskStatus.PAUSED,
                updatedAt = System.currentTimeMillis()
            )
            cur.copy(
                isAgentLoading = false,
                currentAgentSession = s,
                agentSessions = cur.agentSessions.map { if (it.id == curSession.id) s else it }
            )
        }
    }

    companion object {
        private const val MAX_AUTONOMOUS_TOOL_STEPS = 30
    }

    override fun onCleared() {
        super.onCleared()
        generationJob?.cancel()
        // DO NOT cancel the agent task here: it runs inside AgentExecutionService (ForegroundService)
        // so multi-step builds and test suites continue running when the user switches apps.
        try { tts?.stop() } catch (_: Exception) {}
        try { tts?.shutdown() } catch (_: Exception) {}
    }
}

