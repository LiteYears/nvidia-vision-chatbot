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
import com.example.agent.tools.ToolCall
import com.example.agent.tools.ToolCallParser
import com.example.agent.tools.ToolRegistry
import com.example.agent.tools.ToolResult
import com.example.agent.tools.workspace.AgentWorkspaceManager
import java.io.File
import com.example.data.local.ChatDatabase
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
import com.example.data.repository.ChatRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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
    val agentErrorMessage: String? = null
)

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val database = ChatDatabase.getDatabase(application)
    private val settingsManager = SettingsManager(application)
    private val apiClient = NvidiaApiClient { settingsManager.getEffectiveApiKey() }
    private val workspaceManager = AgentWorkspaceManager(File(application.filesDir, "agent_workspaces"))
    private val toolRegistry = ToolRegistry.defaultRegistry(workspaceManager)

    fun getToolRegistry(): ToolRegistry = toolRegistry
    fun getWorkspaceManager(): AgentWorkspaceManager = workspaceManager

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

    fun onSelectDocumentUri(uri: Uri) {
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

        val newSession = AgentSession(
            id = sessionId,
            goal = trimmedGoal,
            status = AgentTaskStatus.IN_PROGRESS,
            createdAt = now,
            updatedAt = now,
            modelUsed = state.selectedModel,
            steps = initialSteps,
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

        agentGenerationJob = viewModelScope.launch {
            val toolsPrompt = toolRegistry.formatToolsForPrompt()
            val agentSystemPrompt = "You are an autonomous NVIDIA Vision Agent. The user has set the following objective/goal:\n\n\"$trimmedGoal\"\n\n" +
                "Provide a structured task roadmap. Outline:\n" +
                "1. Objective Analysis & Scope\n" +
                "2. Strategic Milestones\n" +
                "3. Key Considerations & Trade-offs\n" +
                "4. Immediate Next Steps\n\n" +
                "$toolsPrompt\n\n" +
                "Keep your response concise, well-structured, and formatted with clean Markdown headings and bullet points."

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
            val toolsPrompt = toolRegistry.formatToolsForPrompt()
            val agentSystemPrompt = "You are an autonomous NVIDIA Vision Agent working on the goal: \"${currentSession.goal}\".\n\n" +
                "$toolsPrompt\n\n" +
                "Respond to user inquiries and task adjustments concisely with technical precision."

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
        private const val MAX_AUTONOMOUS_TOOL_STEPS = 6
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
        val state = _uiState.value
        val historyMessages = session.messages
            .filter { it.id != assistantMessageId && !it.isError }
            .filterNot { it.id == userMessage.id }
            .toMutableList()

        var currentTurnMessage = userMessage
        var toolStepCount = 0
        var loopActive = true

        while (loopActive && toolStepCount < MAX_AUTONOMOUS_TOOL_STEPS) {
            // 1. STATE: THINKING
            updateAgentSessionStatus(AgentTaskStatus.THINKING)

            val attemptResult = repository.requestAiCompletion(
                history = historyMessages,
                userMessage = currentTurnMessage,
                modelName = state.selectedModel,
                systemPrompt = systemPrompt,
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
            val toolCall = ToolCallParser.parse(rawResponse)

            if (toolCall != null) {
                toolStepCount++

                // 2. STATE: USING_TOOL
                updateAgentSessionStatus(AgentTaskStatus.USING_TOOL)

                val preToolNarrative = ToolCallParser.stripToolCalls(rawResponse)
                val intermediateContent = if (preToolNarrative.isNotBlank()) {
                    "$preToolNarrative\n\n*(Step $toolStepCount/$MAX_AUTONOMOUS_TOOL_STEPS: Using tool '${toolCall.toolName}')*"
                } else {
                    "*(Step $toolStepCount/$MAX_AUTONOMOUS_TOOL_STEPS: Using tool '${toolCall.toolName}')*"
                }

                updateAssistantStreamingContent(assistantMessageId, intermediateContent)

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

                // 3. STATE: OBSERVING
                updateAgentSessionStatus(AgentTaskStatus.OBSERVING)
                delay(120L)

                // Build structured observation feedback for the model
                val toolFeedbackContent = buildString {
                    appendLine("[TOOL_RESULT: ${record.toolName}]")
                    appendLine("Status: ${if (record.isSuccess) "SUCCESS" else "FAILURE"}")
                    if (record.isSuccess) {
                        appendLine("Result: ${record.result}")
                    } else {
                        appendLine("Error: ${record.error}")
                    }
                    appendLine("Autonomous tool step $toolStepCount of $MAX_AUTONOMOUS_TOOL_STEPS complete.")
                    if (toolStepCount >= MAX_AUTONOMOUS_TOOL_STEPS) {
                        appendLine("Maximum tool steps reached. Deliver your final complete answer to the user now without any tool calls.")
                    } else {
                        appendLine("If you require another tool, output a ```tool_call``` block. Otherwise, deliver your final answer to conclude the task.")
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
                // Model provided its final answer (no tool call requested)
                loopActive = false

                streamAgentResponse(
                    assistantMessageId = assistantMessageId,
                    streamingAssistantMessage = streamingAssistantMessage,
                    fullReplyText = rawResponse,
                    initialSteps = initialSteps
                )
                return
            }
        }

        // Fallback: If loop exited due to MAX_AUTONOMOUS_TOOL_STEPS limit
        if (loopActive) {
            updateAgentSessionStatus(AgentTaskStatus.THINKING)
            val finalPrompt = ChatMessage(
                id = UUID.randomUUID().toString(),
                conversationId = session.id,
                role = MessageRole.USER,
                content = "You have completed $toolStepCount autonomous tool steps. Summarize all findings and provide your final response to the user now.",
                modelUsed = state.selectedModel
            )
            val finalResult = repository.requestAiCompletion(
                history = historyMessages,
                userMessage = finalPrompt,
                modelName = state.selectedModel,
                systemPrompt = systemPrompt,
                temperature = state.temperature,
                topP = state.topP,
                maxTokens = state.maxTokens
            )

            val finalReply = finalResult.getOrElse {
                val lastTool = _uiState.value.currentAgentSession?.toolExecutions?.lastOrNull()
                "Completed $toolStepCount autonomous tool steps. Last output: ${lastTool?.result ?: "Success"}."
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

