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
import com.example.agent.plan.SubtaskStatus
import com.example.agent.plan.TaskPlan
import com.example.agent.plan.TaskPlanner
import com.example.agent.tools.ToolCall
import com.example.agent.tools.ToolCallParser
import com.example.agent.tools.ToolRegistry
import com.example.agent.tools.ToolResult
import com.example.agent.tools.workspace.AgentWorkspaceContext
import com.example.agent.tools.workspace.AgentWorkspaceManager
import com.example.agent.tools.workspace.FileChangeType
import com.example.agent.tools.workspace.VerificationStatus
import java.io.File
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
import kotlinx.coroutines.delay
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
    val lastUploadedZip: UploadedZipInfo? = null
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
    private val agentPlanRepository = AgentPlanRepository(database.agentPlanDao())
    private val taskPlanner = TaskPlanner()
    private val workspaceManager = AgentWorkspaceManager(File(application.filesDir, "agent_workspaces"))
    private val toolRegistry = ToolRegistry.defaultRegistry(workspaceManager)

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
            maxTokens = settingsManager.getMaxTokens()
        )
    )
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private var tts: TextToSpeech? = null
    private var messageCollectionJob: Job? = null
    private var generationJob: Job? = null
    private var agentGenerationJob: Job? = null

    init {
        // Observe conversation history
        viewModelScope.launch {
            repository.allConversations.collectLatest { convs ->
                _uiState.update { it.copy(conversations = convs) }
            }
        }

        // Load persisted Agent sessions and task plans
        viewModelScope.launch {
            try {
                val savedSessions = agentPlanRepository.getAllAgentSessions()
                if (savedSessions.isNotEmpty()) {
                    _uiState.update { it.copy(agentSessions = savedSessions) }
                }
            } catch (_: Exception) {
            }
        }

        // Initialize TTS
        initTts(application)
    }

    private fun initTts(context: Context) {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.US
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
        _uiState.update { it.copy(currentlySpeakingMessageId = messageId) }
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, messageId)
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

    // Agent Mode Management
    fun setAppMode(mode: AppMode) {
        stopSpeaking()
        _uiState.update { it.copy(currentMode = mode) }
    }

    fun onAgentInputTextChanged(text: String) {
        _uiState.update { it.copy(agentInputText = text) }
    }

    fun startNewAgentSession() {
        agentGenerationJob?.cancel()
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
        agentGenerationJob?.cancel()
        workspaceManager.activeSessionId = session.id
        _uiState.update {
            it.copy(
                currentAgentSession = session,
                agentInputText = "",
                isAgentLoading = false,
                agentErrorMessage = null
            )
        }
        viewModelScope.launch {
            try {
                val plan = agentPlanRepository.getPlan(session.id)
                if (plan != null && _uiState.value.currentAgentSession?.id == session.id) {
                    val updated = _uiState.value.currentAgentSession!!.copy(plan = plan)
                    _uiState.update { state ->
                        state.copy(
                            currentAgentSession = updated,
                            agentSessions = state.agentSessions.map { if (it.id == updated.id) updated else it }
                        )
                    }
                }
            } catch (_: Exception) {
            }
        }
    }

    fun deleteAgentSession(sessionId: String) {
        workspaceManager.deleteWorkspace(sessionId)
        _uiState.update { state ->
            val updatedSessions = state.agentSessions.filterNot { it.id == sessionId }
            val newCurrent = if (state.currentAgentSession?.id == sessionId) null else state.currentAgentSession
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
        _uiState.update { cur ->
            val curSession = cur.currentAgentSession ?: return@update cur
            val updatedStatus = if (plan.isCompleted) AgentTaskStatus.COMPLETED else curSession.status
            val s = curSession.copy(
                plan = plan,
                status = updatedStatus,
                updatedAt = System.currentTimeMillis()
            )
            cur.copy(
                currentAgentSession = s,
                agentSessions = cur.agentSessions.map { if (it.id == curSession.id) s else it }
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
        val updated = current.copy(
            status = status,
            updatedAt = System.currentTimeMillis()
        )
        _uiState.update { state ->
            val updatedSessions = state.agentSessions.map {
                if (it.id == updated.id) updated else it
            }
            state.copy(
                currentAgentSession = updated,
                agentSessions = updatedSessions
            )
        }
    }

    fun createAgentSession(goal: String) {
        val trimmedGoal = goal.trim()
        if (trimmedGoal.isBlank()) return
        agentGenerationJob?.cancel()

        val state = _uiState.value
        val sessionId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()

        val initialSteps = listOf(
            AgentStep(
                title = "Goal Intake & Verification",
                description = "Parse objective and establish session parameters",
                status = AgentTaskStatus.COMPLETED,
                timestamp = now
            ),
            AgentStep(
                title = "Task Decomposition & Roadmap",
                description = "Formulate milestones and execution strategy",
                status = AgentTaskStatus.IN_PROGRESS,
                timestamp = now
            ),
            AgentStep(
                title = "Execution Synthesis",
                description = "Synthesize actionable deliverables for goal",
                status = AgentTaskStatus.INITIALIZING,
                timestamp = now
            )
        )

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

        val initialPlan = taskPlanner.createInitialPlan(sessionId, trimmedGoal, MAX_AUTONOMOUS_TOOL_STEPS)

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
            } catch (_: Exception) {
            }
        }

        agentGenerationJob = viewModelScope.launch {
            val agentSystemPrompt = buildAgentSystemPrompt(trimmedGoal)

            executeAgentSessionTurn(
                session = newSession,
                userMessage = userGoalMessage,
                assistantMessageId = assistantMessageId,
                streamingAssistantMessage = streamingAssistantMessage,
                systemPrompt = agentSystemPrompt,
                initialSteps = initialSteps
            )
        }
    }

    private fun buildAgentSystemPrompt(goal: String): String {
        val toolsPrompt = toolRegistry.formatToolsForPrompt()
        return "You are an autonomous engineering agent with top-tier coding workflows (Claude Code, OpenCode, Codex style).\n\n" +
            "USER OBJECTIVE: \"$goal\"\n\n" +
            "$toolsPrompt\n\n" +
            "WORKFLOW MANDATE (CLAUDE CODE / OPENCODE / CODEX STYLE):\n" +
            "- CONTINUOUS REFLECTION & EXECUTION LOOP:\n" +
            "  * Never restrict yourself to rigid artificial limits. Continuously reflect, discover, edit, write, test, and improve until the user's objective is completely achieved and verified.\n" +
            "  * Before invoking each tool (or when analyzing findings), concisely explain your reasoning and next intent in a short, focused reflection (1-2 sentences). For example:\n" +
            "    \"the file contains python code that needed to be updated the user said add some imports to it so i need to look for the correct imports required...\"\n" +
            "  * Follow your reflection immediately with the appropriate ```tool_call``` block.\n" +
            "- CODE INSPECTION & DISCOVERY BEFORE EDITING:\n" +
            "  * Never modify code blindly. First inspect project structure ('file_list' / 'file_tree'), locate candidate files ('file_search'), and read existing files ('file_read') to understand architecture and requirements.\n" +
            "- SURGICAL & MULTI-FILE EDITING:\n" +
            "  * Prefer surgical diffs with 'file_patch' for modifying existing files without truncating or rewriting.\n" +
            "  * Use 'file_write' when creating new files or when a full rewrite is cleaner.\n" +
            "  * For multi-file changes, edit each affected file cleanly.\n" +
            "- TESTING & CONTINUOUS IMPROVEMENT:\n" +
            "  * Always test and verify after editing ('run_command' or 'python_execute').\n" +
            "  * Observe diagnostics/stdout/stderr. If errors occur, reflect on the traceback, formulate fixes, apply them with 'file_patch', and re-test until clean.\n" +
            "- DYNAMIC PLAN ADAPTATION:\n" +
            "  * Adapt your approach if an initial attempt fails. You may output updated ```plan blocks with revised milestones.\n" +
            "- FINAL COMPLETE DELIVERABLE:\n" +
            "  * Only provide your complete, final text answer (without any ```tool_call``` block) when all steps are completed and verified."
    }

    fun sendAgentFollowUp(overrideText: String? = null) {
        val textToSend = (overrideText ?: _uiState.value.agentInputText).trim()
        if (textToSend.isBlank()) return

        val state = _uiState.value
        val currentSession = state.currentAgentSession ?: return

        agentGenerationJob?.cancel()

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

        agentGenerationJob = viewModelScope.launch {
            val agentSystemPrompt = buildAgentSystemPrompt(currentSession.goal)

            executeAgentSessionTurn(
                session = updatedSession,
                userMessage = userMessage,
                assistantMessageId = assistantMessageId,
                streamingAssistantMessage = streamingAssistantMessage,
                systemPrompt = agentSystemPrompt,
                initialSteps = null
            )
        }
    }

    companion object {
        private const val MAX_AUTONOMOUS_TOOL_STEPS = 30
        private const val MAX_CONSECUTIVE_NUDGES = 2
        private const val MAX_TOTAL_NUDGES = 4
    }

    private fun updateAgentSessionStatus(status: AgentTaskStatus) {
        _uiState.update { cur ->
            val curSession = cur.currentAgentSession ?: return@update cur
            val s = curSession.copy(status = status, updatedAt = System.currentTimeMillis())
            cur.copy(
                currentAgentSession = s,
                agentSessions = cur.agentSessions.map { if (it.id == curSession.id) s else it }
            )
        }
    }

    private fun addReflectionToSession(thought: String, timestamp: Long = System.currentTimeMillis()) {
        val cleanThought = thought.trim()
        if (cleanThought.isBlank()) return
        _uiState.update { cur ->
            val curSession = cur.currentAgentSession ?: return@update cur
            if (curSession.reflections.lastOrNull()?.thought == cleanThought) return@update cur
            val reflection = AgentReflection(
                thought = cleanThought,
                timestamp = timestamp
            )
            val updatedReflections = curSession.reflections + reflection
            val s = curSession.copy(reflections = updatedReflections, updatedAt = System.currentTimeMillis())
            cur.copy(
                currentAgentSession = s,
                agentSessions = cur.agentSessions.map { if (it.id == curSession.id) s else it }
            )
        }
    }

    private fun updateAssistantStreamingContent(assistantMessageId: String, content: String) {
        _uiState.update { cur ->
            val curSession = cur.currentAgentSession ?: return@update cur
            val updatedMessages = curSession.messages.map { msg ->
                if (msg.id == assistantMessageId) {
                    msg.copy(content = content, isStreaming = true)
                } else msg
            }
            val s = curSession.copy(messages = updatedMessages)
            cur.copy(
                currentAgentSession = s,
                agentSessions = cur.agentSessions.map { if (it.id == curSession.id) s else it }
            )
        }
    }

    private fun addToolExecutionToSession(record: ToolExecutionRecord) {
        _uiState.update { cur ->
            val curSession = cur.currentAgentSession ?: return@update cur
            val updatedExecutions = curSession.toolExecutions + record
            val s = curSession.copy(toolExecutions = updatedExecutions, updatedAt = System.currentTimeMillis())
            cur.copy(
                currentAgentSession = s,
                agentSessions = cur.agentSessions.map { if (it.id == curSession.id) s else it }
            )
        }
    }

    private suspend fun executeAgentSessionTurn(
        session: AgentSession,
        userMessage: ChatMessage,
        assistantMessageId: String,
        streamingAssistantMessage: ChatMessage,
        systemPrompt: String,
        initialSteps: List<AgentStep>?
    ) {
        workspaceManager.activeSessionId = session.id
        val workspaceContext = AgentWorkspaceContext(
            sessionId = session.id,
            workspaceManager = workspaceManager
        )
        workspaceContext.discoverWorkspace()

        val state = _uiState.value
        val historyMessages = session.messages
            .filter { it.id != assistantMessageId && !it.isError }
            .filterNot { it.id == userMessage.id }
            .toMutableList()

        var currentPlan = session.plan ?: taskPlanner.createInitialPlan(session.id, session.goal, MAX_AUTONOMOUS_TOOL_STEPS)
        updateSessionPlan(currentPlan)

        var currentTurnMessage = userMessage
        var toolStepCount = 0
        var consecutiveNudges = 0
        var totalNudges = 0
        var loopActive = true

        while (loopActive && toolStepCount < MAX_AUTONOMOUS_TOOL_STEPS && !currentPlan.isStepLimitExceeded) {
            // Ensure an active subtask is assigned and set to RUNNING
            if (currentPlan.activeSubtask == null || currentPlan.activeSubtask?.status == SubtaskStatus.COMPLETED) {
                currentPlan = taskPlanner.startNextSubtask(currentPlan)
                updateSessionPlan(currentPlan)
            }
            val activeSubtask = currentPlan.activeSubtask

            // 1. STATE: THINKING
            updateAgentSessionStatus(AgentTaskStatus.THINKING)

            val planPromptSnippet = taskPlanner.formatPlanForPrompt(currentPlan)
            val workspacePromptSnippet = workspaceContext.formatContextForPrompt()
            val fullSystemPrompt = "$systemPrompt\n\n$workspacePromptSnippet\n\n$planPromptSnippet"

            val attemptResult = repository.requestAiCompletion(
                history = historyMessages,
                userMessage = currentTurnMessage,
                modelName = state.selectedModel,
                systemPrompt = fullSystemPrompt,
                temperature = state.temperature,
                topP = state.topP,
                maxTokens = state.maxTokens
            )

            if (attemptResult.isFailure) {
                val error = attemptResult.exceptionOrNull() ?: Exception("Unknown error during agent generation")
                handleAgentError(assistantMessageId, streamingAssistantMessage, error)
                return
            }

            val rawResponse = attemptResult.getOrThrow()

            // Dynamic agent plan update detection
            val dynamicPlan = taskPlanner.parsePlanFromAgentOutput(rawResponse, currentPlan)
            if (dynamicPlan != null) {
                currentPlan = dynamicPlan
                updateSessionPlan(currentPlan)
            }

            val toolCall = ToolCallParser.parse(rawResponse)

            if (toolCall != null) {
                consecutiveNudges = 0
                toolStepCount++
                currentPlan = taskPlanner.incrementStep(currentPlan)

                // 2. STATE: USING_TOOL
                updateAgentSessionStatus(AgentTaskStatus.USING_TOOL)

                val preToolNarrative = ToolCallParser.stripToolCalls(rawResponse).trim()
                if (preToolNarrative.isNotBlank()) {
                    addReflectionToSession(preToolNarrative)
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
                addToolExecutionToSession(record)

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
                    "run_command", "python_execute" -> {
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
                    updateSessionPlan(currentPlan)
                } else {
                    // Record failure and allow retry or alternative
                    currentPlan = taskPlanner.recordToolFailure(
                        plan = currentPlan,
                        subtaskId = activeSubtask?.id,
                        toolName = toolCall.toolName,
                        error = toolResult.error ?: "Unknown error"
                    )
                    updateSessionPlan(currentPlan)
                }

                // 3. STATE: OBSERVING
                updateAgentSessionStatus(AgentTaskStatus.OBSERVING)
                delay(120L)

                // Build structured observation feedback for the model
                val nextActiveSubtask = currentPlan.activeSubtask
                val toolFeedbackContent = buildString {
                    appendLine("[TOOL_RESULT: ${record.toolName}]")
                    appendLine("Status: ${if (record.isSuccess) "SUCCESS" else "FAILURE"}")
                    if (record.isSuccess) {
                        appendLine("Result: ${record.result}")
                        if (nextActiveSubtask != null && nextActiveSubtask.id != activeSubtask?.id) {
                            appendLine("\n[SUBTASK PROGRESSION: Subtask \"${activeSubtask?.description}\" is COMPLETED. Active subtask is now: \"${nextActiveSubtask.description}\".]")
                        } else {
                            appendLine("\n[SUBTASK STATUS: Objective for \"${activeSubtask?.description}\" is currently RUNNING with output above.]")
                        }
                        if (record.toolName == "file_list" && record.result?.contains("empty") == true) {
                            appendLine("\n[WORKSPACE NOTE: The workspace is empty. Create any needed script files with 'file_write'.]")
                        } else if (record.toolName == "web_search") {
                            appendLine("\n[RESEARCH AGENT GUIDANCE:")
                            appendLine("- Review search results, domain credibility, and snippets above.")
                            appendLine("- Select authoritative, diverse sources and call 'web_open' with the target URL to retrieve full, accurate page content.")
                            appendLine("- Distinguish snippets from verified page content: snippets may be incomplete or truncated; use 'web_open' for source inspection.")
                            appendLine("- If the search results are insufficient, irrelevant, or missing key aspects of the user query, perform a follow-up 'web_search' with refined, more specific keywords.")
                            appendLine("- If the search provides direct authoritative answers (such as official IP/RDAP/WHOIS registry records or verified data) and no further lookups are needed, proceed to synthesize your answer citing the sources.]")
                        } else if (record.toolName == "web_open") {
                            appendLine("\n[RESEARCH & FACT-EXTRACTION GUIDANCE:")
                            appendLine("- The web page content above has been cleaned, sanitized against prompt injection, and enclosed in untrusted data fences.")
                            appendLine("- Review the DOCUMENT OUTLINE and content to extract verified facts, technical specifications, and news.")
                            appendLine("- Identify missing information: If this source leaves unanswered questions, perform a follow-up 'web_search' with targeted keywords or open another candidate source URL.")
                            appendLine("- Cross-reference: For critical facts or comparisons, check across multiple independent sources.")
                            appendLine("- If more content is available and you need further details, call 'web_open' with the indicated 'offset' or jump to any section with section='<Heading>'.")
                            appendLine("- When presenting your findings to the user, ALWAYS cite your source using markdown links [Source Title](URL).")
                            appendLine("- If you have enough verified facts to answer the user's objective, proceed to deliver your response.]")
                        } else if (record.toolName == "file_read") {
                            appendLine("\n[CODE INSPECTION GUIDANCE:")
                            appendLine("- The file content has been retrieved above.")
                            appendLine("- Locate the exact line(s) that need modification or cause the error.")
                            appendLine("- You MUST now apply the modification using 'file_patch' (specifying path, target_content, and replacement_content) or 'file_write' (to update the file).")
                            appendLine("- Do NOT merely explain what needs to be changed in text: invoke 'file_patch' or 'file_write' immediately.]")
                        } else if (record.toolName == "file_write" || record.toolName == "file_patch") {
                            appendLine("\n[MODIFICATION SAVED & TEST MANDATE:")
                            appendLine("- The file has been successfully written/patched on disk in the workspace.")
                            appendLine("- MANDATORY NEXT STEP: You MUST execute and test the modified code now using 'python_execute' (for Python scripts) or 'run_command' (for shell/build commands).")
                            appendLine("- Do NOT claim the file is fixed until you run it and verify the execution output has no errors.]")
                        } else if (record.toolName == "python_execute") {
                            appendLine("\n[EXECUTION VERIFICATION GUIDANCE:")
                            appendLine("- Script executed. Inspect the stdout/stderr output above.")
                            appendLine("- If the script executed cleanly (Exit Code 0) and output verifies the fix, conclude and deliver your final answer.")
                            appendLine("- If an error occurred or fixes are needed, modify the code with 'file_patch' or 'file_write' and re-run with 'python_execute' to verify the fix.]")
                        }
                        val notice = workspaceContext.formatPostActionNotice(record.toolName, record.isSuccess)
                        if (notice != null) {
                            appendLine("\n$notice")
                        }
                    } else {
                        appendLine("Error: ${record.error}")
                        val notice = workspaceContext.formatPostActionNotice(record.toolName, record.isSuccess)
                        if (notice != null) {
                            appendLine("\n$notice")
                        }
                        appendLine("\n[SUBTASK STATUS: Subtask \"${activeSubtask?.description}\" failed with tool ${record.toolName}. You may retry, use a different tool, or choose another approach.]")
                        if (record.error?.contains("CAPABILITY_UNAVAILABLE") == true) {
                            appendLine("\n[RECOVERY INSTRUCTION: The requested executable is not available in the Android shell. Switch to 'python_execute' for Python execution. Do NOT attempt shell package managers (apt, pkg, curl).]")
                        } else if (record.error?.contains("SyntaxError") == true || record.error?.contains("Traceback") == true || record.error?.contains("Error Classification") == true) {
                            appendLine("\n[DIAGNOSTIC GUIDANCE: A Python error occurred. Inspect the traceback and exception message above, modify the code with 'file_patch' or 'file_write', and re-run with 'python_execute' to verify the fix.]")
                        }
                    }
                    appendLine("Autonomous plan step ${currentPlan.stepCount} of ${currentPlan.maxSteps} executed.")
                    if (currentPlan.isStepLimitExceeded) {
                        appendLine("Maximum plan step limit reached. Deliver your final complete answer to the user now without any further tool calls.")
                    } else {
                        appendLine("\nNEXT ACTION MANDATE:")
                        appendLine("- If the overall objective is NOT fully verified and achieved, you MUST immediately invoke the next tool call inside a ```tool_call``` block.")
                        appendLine("- Do NOT respond with conversational text, intermediate progress reports, or descriptions of what you plan to do next without including the required ```tool_call``` block.")
                        appendLine("- ONLY provide a pure final text answer without a ```tool_call``` block when ALL required steps (e.g. creating, running, verifying, or fixing) are completed and verified.")
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
                        modelUsed = state.selectedModel
                    )
                )

                // Set feedback message as next user message to feed back to model
                currentTurnMessage = ChatMessage(
                    id = UUID.randomUUID().toString(),
                    conversationId = session.id,
                    role = MessageRole.USER,
                    content = toolFeedbackContent,
                    modelUsed = state.selectedModel
                )

                // Loop continues to next iteration (THINKING)
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
                        addReflectionToSession(preToolNarrative)
                    }

                    // Add past turn to ongoing history
                    historyMessages.add(currentTurnMessage)
                    historyMessages.add(
                        ChatMessage(
                            id = UUID.randomUUID().toString(),
                            conversationId = session.id,
                            role = MessageRole.ASSISTANT,
                            content = rawResponse,
                            modelUsed = state.selectedModel
                        )
                    )

                    val nudgeContent = buildString {
                        appendLine("[EXECUTION CONTROL: Tool invocation required]")
                        appendLine("You provided commentary or described next steps, but did not emit a ```tool_call``` block.")
                        appendLine("Unfinished action subtask: \"${activeSubtask?.description}\".")
                        if (workspaceContext.hasUnverifiedModifications()) {
                            val unverified = workspaceContext.modifiedFiles.filter { it.value.verificationStatus == VerificationStatus.NEEDS_VERIFICATION }.keys
                            appendLine("Unverified modifications exist on disk for: ${unverified.joinToString(", ")}.")
                            appendLine("You must run compilation or test checks (using 'run_command' or 'python_execute') to verify the modified workspace state before completing.")
                        }
                        if (activeSubtask?.status == SubtaskStatus.FAILED) {
                            appendLine("The previous tool execution for subtask \"${activeSubtask.description}\" FAILED.")
                            appendLine("You must inspect diagnostics, apply a repair (via 'file_patch' or 'file_write'), test an alternative approach, or adapt your plan before concluding.")
                        } else if (activeSubtask?.description?.contains("test", ignoreCase = true) == true ||
                            activeSubtask?.description?.contains("compile", ignoreCase = true) == true ||
                            activeSubtask?.description?.contains("verify", ignoreCase = true) == true ||
                            activeSubtask?.description?.contains("execute", ignoreCase = true) == true ||
                            activeSubtask?.description?.contains("run", ignoreCase = true) == true) {
                            appendLine("The implementation has not been tested or verified yet. You MUST run tests or verify the build (via 'run_command' or 'python_execute') before claiming completion.")
                        } else if (activeSubtask?.description?.contains("inspect", ignoreCase = true) == true ||
                            activeSubtask?.description?.contains("locate", ignoreCase = true) == true ||
                            activeSubtask?.description?.contains("search", ignoreCase = true) == true) {
                            appendLine("Before editing, inspect the existing code and architecture using 'file_list', 'file_tree', 'file_search', or 'file_read'.")
                        } else if (activeSubtask?.description?.contains("modify", ignoreCase = true) == true ||
                            activeSubtask?.description?.contains("implement", ignoreCase = true) == true ||
                            activeSubtask?.description?.contains("fix", ignoreCase = true) == true ||
                            activeSubtask?.description?.contains("write", ignoreCase = true) == true) {
                            appendLine("The required code modifications have not been saved yet. You MUST invoke 'file_patch' or 'file_write' to apply the changes on disk.")
                        }
                        appendLine("You MUST output the next tool call inside a ```tool_call``` block now to proceed with execution.")
                        appendLine("Do NOT deliver an intermediate text-only response without a tool call until the objective is fully executed and verified.")
                    }

                    currentTurnMessage = ChatMessage(
                        id = UUID.randomUUID().toString(),
                        conversationId = session.id,
                        role = MessageRole.USER,
                        content = nudgeContent,
                        modelUsed = state.selectedModel
                    )
                    // Loop continues to next iteration (THINKING)
                } else {
                    // Model provided its final answer (all actions complete or nudge limit reached)
                    loopActive = false

                    // Only verify active subtask if it is a synthesis/final subtask or if all action subtasks were already completed
                    val hasUnfinishedActions = taskPlanner.hasPendingActionSubtasks(currentPlan) || workspaceContext.hasUnverifiedModifications()
                    if (!hasUnfinishedActions && activeSubtask != null && activeSubtask.status != SubtaskStatus.FAILED) {
                        currentPlan = taskPlanner.verifyAndCompleteSubtask(
                            plan = currentPlan,
                            subtaskId = activeSubtask.id,
                            verificationNotes = "Verified by agent completion"
                        )
                    }

                    // Complete only synthesis/final subtasks that remained pending; never falsely claim action subtasks are complete
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
                    updateSessionPlan(currentPlan)

                    streamAgentResponse(
                        assistantMessageId = assistantMessageId,
                        streamingAssistantMessage = streamingAssistantMessage,
                        fullReplyText = rawResponse,
                        initialSteps = initialSteps
                    )
                    return
                }
            }
        }

        // Fallback: If loop exited due to step limit
        if (loopActive) {
            updateAgentSessionStatus(AgentTaskStatus.THINKING)
            val finalPrompt = ChatMessage(
                id = UUID.randomUUID().toString(),
                conversationId = session.id,
                role = MessageRole.USER,
                content = "You have completed ${currentPlan.stepCount} autonomous plan steps (limit: ${currentPlan.maxSteps}). Summarize all findings and verify your subtasks to conclude.",
                modelUsed = state.selectedModel
            )
            val finalResult = repository.requestAiCompletion(
                history = historyMessages,
                userMessage = finalPrompt,
                modelName = state.selectedModel,
                systemPrompt = "$systemPrompt\n\n${taskPlanner.formatPlanForPrompt(currentPlan)}",
                temperature = state.temperature,
                topP = state.topP,
                maxTokens = state.maxTokens
            )

            val finalReply = finalResult.getOrElse {
                val lastTool = _uiState.value.currentAgentSession?.toolExecutions?.lastOrNull()
                "Completed ${currentPlan.stepCount} autonomous plan steps. Last output: ${lastTool?.result ?: "Success"}."
            }

            streamAgentResponse(
                assistantMessageId = assistantMessageId,
                streamingAssistantMessage = streamingAssistantMessage,
                fullReplyText = ToolCallParser.stripToolCalls(finalReply),
                initialSteps = initialSteps
            )
        }
    }

    private suspend fun streamAgentResponse(
        assistantMessageId: String,
        streamingAssistantMessage: ChatMessage,
        fullReplyText: String,
        initialSteps: List<AgentStep>?
    ) {
        val words = fullReplyText.split(Regex("(?<=\\s)|(?=\\s)"))
        val accumulated = StringBuilder()

        for (token in words) {
            accumulated.append(token)
            val currentText = accumulated.toString()

            _uiState.update { cur ->
                val curSession = cur.currentAgentSession ?: return@update cur
                val updatedMessages = curSession.messages.map { msg ->
                    if (msg.id == assistantMessageId) {
                        msg.copy(content = currentText, isStreaming = true)
                    } else msg
                }
                val updatedSession = curSession.copy(messages = updatedMessages)
                cur.copy(
                    currentAgentSession = updatedSession,
                    agentSessions = cur.agentSessions.map { if (it.id == curSession.id) updatedSession else it }
                )
            }
            val delayMs = if (token.isBlank()) 10L else (16L + (token.length % 5) * 2L)
            delay(delayMs)
        }

        val finalAssistantMessage = streamingAssistantMessage.copy(
            content = fullReplyText,
            isStreaming = false
        )

        val updatedSteps = initialSteps?.map { step ->
            step.copy(status = AgentTaskStatus.COMPLETED)
        }

        _uiState.update { cur ->
            val curSession = cur.currentAgentSession ?: return@update cur
            val updatedMessages = curSession.messages.map { msg ->
                if (msg.id == assistantMessageId) finalAssistantMessage else msg
            }
            val updatedSession = curSession.copy(
                status = AgentTaskStatus.COMPLETED,
                steps = updatedSteps ?: curSession.steps.map { it.copy(status = AgentTaskStatus.COMPLETED) },
                messages = updatedMessages,
                updatedAt = System.currentTimeMillis()
            )
            cur.copy(
                isAgentLoading = false,
                currentAgentSession = updatedSession,
                agentSessions = cur.agentSessions.map { if (it.id == curSession.id) updatedSession else it }
            )
        }
    }

    private fun handleAgentError(
        assistantMessageId: String,
        streamingAssistantMessage: ChatMessage,
        error: Throwable
    ) {
        val errorMessage = streamingAssistantMessage.copy(
            content = "Task Error: ${error.message ?: "Could not complete autonomous agent loop."}",
            isStreaming = false,
            isError = true
        )

        _uiState.update { cur ->
            val curSession = cur.currentAgentSession ?: return@update cur
            val updatedMessages = curSession.messages.map { msg ->
                if (msg.id == assistantMessageId) errorMessage else msg
            }
            val updatedSession = curSession.copy(
                status = AgentTaskStatus.FAILED,
                messages = updatedMessages,
                updatedAt = System.currentTimeMillis()
            )
            cur.copy(
                isAgentLoading = false,
                agentErrorMessage = error.message,
                currentAgentSession = updatedSession,
                agentSessions = cur.agentSessions.map { if (it.id == curSession.id) updatedSession else it }
            )
        }
    }

    fun stopAgentGeneration() {
        agentGenerationJob?.cancel()
        _uiState.update { cur ->
            val curSession = cur.currentAgentSession ?: return@update cur
            val updatedMessages = curSession.messages.map { msg ->
                if (msg.isStreaming) msg.copy(isStreaming = false) else msg
            }.filterNot { it.isStreaming && it.content.isBlank() }
            val s = curSession.copy(messages = updatedMessages)
            cur.copy(
                isAgentLoading = false,
                currentAgentSession = s,
                agentSessions = cur.agentSessions.map { if (it.id == curSession.id) s else it }
            )
        }
    }

    override fun onCleared() {
        super.onCleared()
        generationJob?.cancel()
        agentGenerationJob?.cancel()
        tts?.shutdown()
    }
}

