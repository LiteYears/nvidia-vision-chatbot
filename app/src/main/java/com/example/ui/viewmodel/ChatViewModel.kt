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
import com.example.data.local.ChatDatabase
import com.example.data.model.ChatMessage
import com.example.data.model.Conversation
import com.example.data.model.MessageRole
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
    val maxTokens: Int = SettingsManager.DEFAULT_MAX_TOKENS
)

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val database = ChatDatabase.getDatabase(application)
    private val settingsManager = SettingsManager(application)
    private val apiClient = NvidiaApiClient { settingsManager.getEffectiveApiKey() }
    private val repository = ChatRepository(database.chatDao(), settingsManager, apiClient)

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

    override fun onCleared() {
        super.onCleared()
        generationJob?.cancel()
        tts?.shutdown()
    }
}

