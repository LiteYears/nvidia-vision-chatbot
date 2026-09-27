package com.example.ui.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import com.example.data.model.AgentSession
import com.example.data.model.AgentTaskStatus
import com.example.data.model.AppMode
import com.example.data.model.MessageRole
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.ui.components.AboutDeveloperDialog
import com.example.ui.components.AgentGoalWelcomeView
import com.example.ui.components.AgentTaskStateCard
import com.example.ui.components.AiChatbotLogo
import com.example.ui.components.ApiKeyDialog
import com.example.ui.components.AttachmentBottomSheet
import com.example.ui.components.ChatBubble
import com.example.ui.components.ChatDrawerContent
import com.example.ui.components.ChatInputCard
import com.example.ui.components.ModeSelectorTabs
import com.example.ui.components.ModelParametersDialog
import com.example.ui.components.ModelSelectorSheet
import com.example.ui.components.ToolExecutionCard
import com.example.ui.components.TopNavigationBar
import com.example.ui.components.UpgradePlanDialog
import com.example.ui.components.VoiceModeDialog
import com.example.ui.viewmodel.ChatViewModel
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.Locale

@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val listState = rememberLazyListState()
    val agentListState = rememberLazyListState()
    val snackbarHostState = remember { SnackbarHostState() }

    // Auto-scroll to latest message in Chat Mode
    LaunchedEffect(uiState.messages.size, uiState.isLoading) {
        if (uiState.messages.isNotEmpty()) {
            listState.animateScrollToItem(uiState.messages.size - 1)
        }
    }

    // Auto-scroll to latest message in Agent Mode
    LaunchedEffect(uiState.currentAgentSession?.messages?.size, uiState.isAgentLoading) {
        val agentMsgs = uiState.currentAgentSession?.messages
        if (!agentMsgs.isNullOrEmpty()) {
            agentListState.animateScrollToItem(agentMsgs.size - 1)
        }
    }

    // Photo picker launcher (zero broad permissions required)
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            viewModel.onSelectImageUri(uri)
        }
    }

    // Document picker launcher for RAG indexing
    val documentPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            viewModel.onSelectDocumentUri(uri)
        }
    }

    // Clear messages confirmation dialog state
    var showClearDialog by remember { mutableStateOf(false) }

    LaunchedEffect(uiState.attachedDocument) {
        val doc = uiState.attachedDocument
        if (doc != null) {
            Toast.makeText(
                context,
                "Indexed '${doc.name}' (${doc.chunks.size} knowledge segments for RAG)",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    if (showClearDialog) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = {
                Text(
                    text = "Clear Chat",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                )
            },
            text = {
                Text(
                    text = "Are you sure you want to clear all messages in this conversation?",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = {
                        showClearDialog = false
                        viewModel.clearCurrentConversationMessages()
                        Toast.makeText(context, "Chat messages cleared", Toast.LENGTH_SHORT).show()
                    }
                ) {
                    Text(
                        text = "Clear",
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(
                    onClick = { showClearDialog = false }
                ) {
                    Text("Cancel")
                }
            }
        )
    }

    // Speech Recognizer setup for microphone button
    var isDictating by remember { mutableStateOf(false) }
    var speechRecognizer by remember { mutableStateOf<SpeechRecognizer?>(null) }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            startSpeechRecognition(
                context = context,
                onResult = { text ->
                    isDictating = false
                    viewModel.onInputTextChanged(
                        if (uiState.inputText.isBlank()) text else "${uiState.inputText} $text"
                    )
                },
                onError = {
                    isDictating = false
                },
                onRecognizerCreated = { recognizer ->
                    speechRecognizer = recognizer
                }
            )
            isDictating = true
        } else {
            Toast.makeText(context, "Microphone permission needed for speech input", Toast.LENGTH_SHORT).show()
        }
    }

    // Close drawer on system back press instead of exiting app
    BackHandler(enabled = drawerState.isOpen) {
        scope.launch { drawerState.close() }
    }

    // Voice Mode Speech Recognition and auto-TTS lifecycle
    var voiceModeRecognizer by remember { mutableStateOf<SpeechRecognizer?>(null) }

    LaunchedEffect(uiState.isVoiceModeOpen) {
        if (uiState.isVoiceModeOpen) {
            val hasPermission = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED

            if (hasPermission) {
                viewModel.updateVoiceStatus("Listening...")
                startSpeechRecognition(
                    context = context,
                    onResult = { resultText ->
                        if (resultText.isNotBlank()) {
                            viewModel.updateVoiceTranscript(resultText)
                            viewModel.updateVoiceStatus("Thinking...")
                            viewModel.onSendMessage(resultText)
                        } else {
                            viewModel.updateVoiceStatus("Didn't catch that. Tap to retry.")
                        }
                    },
                    onError = {
                        viewModel.updateVoiceStatus("Didn't catch that. Tap to retry.")
                    },
                    onRecognizerCreated = { voiceModeRecognizer = it },
                    onPartialResult = { partial ->
                        viewModel.updateVoiceTranscript(partial)
                    }
                )
            } else {
                viewModel.updateVoiceStatus("Microphone permission required")
                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        } else {
            voiceModeRecognizer?.destroy()
            voiceModeRecognizer = null
            viewModel.stopSpeaking()
        }
    }

    // Auto-speak response in Voice Mode when assistant completes reply
    LaunchedEffect(uiState.isVoiceModeOpen, uiState.isLoading) {
        if (uiState.isVoiceModeOpen && !uiState.isLoading && uiState.messages.isNotEmpty()) {
            val lastMsg = uiState.messages.lastOrNull()
            if (lastMsg != null && lastMsg.role == MessageRole.ASSISTANT && !lastMsg.isStreaming && !lastMsg.isError) {
                viewModel.updateVoiceStatus("Speaking...")
                if (!uiState.isVoiceMuted) {
                    viewModel.speakText(lastMsg.content, lastMsg.id)
                }
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            speechRecognizer?.destroy()
            voiceModeRecognizer?.destroy()
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ChatDrawerContent(
                conversations = uiState.conversations,
                activeConversationId = uiState.currentConversation?.id,
                userName = uiState.userName,
                planType = uiState.planType,
                onSelectConversation = { conv ->
                    viewModel.setAppMode(AppMode.CHAT)
                    viewModel.selectConversation(conv)
                    scope.launch { drawerState.close() }
                },
                onNewChatClick = {
                    viewModel.setAppMode(AppMode.CHAT)
                    viewModel.startNewChat()
                    scope.launch { drawerState.close() }
                },
                onDeleteConversation = { id ->
                    viewModel.deleteConversation(id)
                },
                onClearAllClick = {
                    viewModel.clearAllHistory()
                    scope.launch { drawerState.close() }
                },
                onOpenApiKeyClick = {
                    scope.launch { drawerState.close() }
                    viewModel.setApiKeyDialogOpen(true)
                },
                onOpenPlanClick = {
                    scope.launch { drawerState.close() }
                    viewModel.setUpgradeDialogOpen(true)
                },
                onOpenAboutClick = {
                    scope.launch { drawerState.close() }
                    viewModel.setAboutDialogOpen(true)
                },
                onOpenParametersClick = {
                    scope.launch { drawerState.close() }
                    viewModel.setParametersSheetOpen(true)
                },
                currentMode = uiState.currentMode,
                onModeChange = { mode ->
                    viewModel.setAppMode(mode)
                },
                agentSessions = uiState.agentSessions,
                activeAgentSessionId = uiState.currentAgentSession?.id,
                onSelectAgentSession = { session ->
                    viewModel.setAppMode(AppMode.AGENT)
                    viewModel.selectAgentSession(session)
                    scope.launch { drawerState.close() }
                },
                onNewAgentTaskClick = {
                    viewModel.setAppMode(AppMode.AGENT)
                    viewModel.startNewAgentSession()
                    scope.launch { drawerState.close() }
                },
                onDeleteAgentSession = { id ->
                    viewModel.deleteAgentSession(id)
                }
            )
        }
    ) {
        val imeInsets = WindowInsets.ime
        val navBarInsets = WindowInsets.navigationBars
        val density = LocalDensity.current
        val imeBottomPx = imeInsets.getBottom(density)
        val navBottomPx = navBarInsets.getBottom(density)
        val isKeyboardOpen = imeBottomPx > navBottomPx
        val bottomInsetDp = with(density) { maxOf(imeBottomPx, navBottomPx).toDp() }
        val extraBottomMargin = if (isKeyboardOpen) 4.dp else 10.dp

        LaunchedEffect(isKeyboardOpen) {
            if (isKeyboardOpen) {
                if (uiState.currentMode == AppMode.CHAT && uiState.messages.isNotEmpty()) {
                    listState.animateScrollToItem(uiState.messages.size - 1)
                } else if (uiState.currentMode == AppMode.AGENT && !uiState.currentAgentSession?.messages.isNullOrEmpty()) {
                    agentListState.animateScrollToItem(uiState.currentAgentSession!!.messages.size - 1)
                }
            }
        }

        Scaffold(
            modifier = modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                ) {
                    TopNavigationBar(
                        planText = uiState.planType,
                        isIncognito = uiState.isIncognito,
                        canClearMessages = if (uiState.currentMode == AppMode.CHAT) uiState.messages.isNotEmpty() else false,
                        onMenuClick = {
                            scope.launch { drawerState.open() }
                        },
                        onPlanClick = {
                            viewModel.setUpgradeDialogOpen(true)
                        },
                        onIncognitoClick = {
                            viewModel.toggleIncognito()
                            val msg = if (uiState.isIncognito) "Incognito Mode disabled" else "Incognito Mode active: Chat won't be saved"
                            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                        },
                        onClearMessagesClick = {
                            showClearDialog = true
                        }
                    )

                    // Mode Selector Tabs (Chat Mode vs Agent Mode)
                    ModeSelectorTabs(
                        currentMode = uiState.currentMode,
                        onModeChange = viewModel::setAppMode
                    )
                }
            },
            bottomBar = {
                val shouldShowBottomBar = uiState.currentMode == AppMode.CHAT || uiState.currentAgentSession != null

                if (shouldShowBottomBar) {
                    // Floating chat/agent input card positioned cleanly above the keyboard or navigation bar
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(
                                start = 14.dp,
                                end = 14.dp,
                                top = 2.dp,
                                bottom = bottomInsetDp + extraBottomMargin
                            )
                    ) {
                        if (uiState.currentMode == AppMode.CHAT) {
                            ChatInputCard(
                                inputText = uiState.inputText,
                                onInputTextChange = viewModel::onInputTextChanged,
                                selectedModel = uiState.selectedModel,
                                onModelClick = { viewModel.setModelSheetOpen(true) },
                                onSendClick = { viewModel.onSendMessage() },
                                onVoiceClick = { viewModel.setVoiceModeOpen(true) },
                                onMicDictationClick = {
                                    val hasPermission = ContextCompat.checkSelfPermission(
                                        context,
                                        Manifest.permission.RECORD_AUDIO
                                    ) == PackageManager.PERMISSION_GRANTED

                                    if (hasPermission) {
                                        if (isDictating) {
                                            speechRecognizer?.stopListening()
                                            isDictating = false
                                        } else {
                                            startSpeechRecognition(
                                                context = context,
                                                onResult = { text ->
                                                    isDictating = false
                                                    viewModel.onInputTextChanged(
                                                        if (uiState.inputText.isBlank()) text else "${uiState.inputText} $text"
                                                    )
                                                },
                                                onError = { isDictating = false },
                                                onRecognizerCreated = { speechRecognizer = it }
                                            )
                                            isDictating = true
                                        }
                                    } else {
                                        micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                    }
                                },
                                onAddAttachmentClick = { viewModel.setAttachmentSheetOpen(true) },
                                selectedImageUri = uiState.selectedImageUri,
                                onRemoveImage = viewModel::onRemoveSelectedImage,
                                attachedDocument = uiState.attachedDocument,
                                onRemoveDocument = viewModel::onRemoveAttachedDocument,
                                isStreaming = uiState.isLoading || uiState.messages.lastOrNull()?.isStreaming == true,
                                onStopStreaming = viewModel::stopGeneration,
                                isVoiceRecording = isDictating
                            )
                        } else {
                            // Agent Mode Follow-up Input Card
                            ChatInputCard(
                                inputText = uiState.agentInputText,
                                onInputTextChange = viewModel::onAgentInputTextChanged,
                                selectedModel = uiState.selectedModel,
                                onModelClick = { viewModel.setModelSheetOpen(true) },
                                onSendClick = { viewModel.sendAgentFollowUp() },
                                onVoiceClick = { viewModel.setVoiceModeOpen(true) },
                                onMicDictationClick = {
                                    val hasPermission = ContextCompat.checkSelfPermission(
                                        context,
                                        Manifest.permission.RECORD_AUDIO
                                    ) == PackageManager.PERMISSION_GRANTED

                                    if (hasPermission) {
                                        if (isDictating) {
                                            speechRecognizer?.stopListening()
                                            isDictating = false
                                        } else {
                                            startSpeechRecognition(
                                                context = context,
                                                onResult = { text ->
                                                    isDictating = false
                                                    viewModel.onAgentInputTextChanged(
                                                        if (uiState.agentInputText.isBlank()) text else "${uiState.agentInputText} $text"
                                                    )
                                                },
                                                onError = { isDictating = false },
                                                onRecognizerCreated = { speechRecognizer = it }
                                            )
                                            isDictating = true
                                        }
                                    } else {
                                        micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                    }
                                },
                                onAddAttachmentClick = { viewModel.setAttachmentSheetOpen(true) },
                                selectedImageUri = null,
                                onRemoveImage = {},
                                attachedDocument = null,
                                onRemoveDocument = {},
                                isStreaming = uiState.isAgentLoading || uiState.currentAgentSession?.messages?.lastOrNull()?.isStreaming == true,
                                onStopStreaming = viewModel::stopAgentGeneration,
                                isVoiceRecording = isDictating
                            )
                        }
                    }
                }
            }
        ) { paddingValues ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                if (uiState.currentMode == AppMode.CHAT) {
                    // Chat Mode Content (100% Intact)
                    if (uiState.messages.isEmpty()) {
                        // Clean landing screen with ONLY the animated logo and dynamic greeting
                        val currentHour = remember { Calendar.getInstance().get(Calendar.HOUR_OF_DAY) }
                        val greeting = remember(currentHour) {
                            when (currentHour) {
                                in 5..11 -> "Good Morning"
                                in 12..16 -> "Good Afternoon"
                                else -> "Good Evening"
                            }
                        }

                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 24.dp)
                                .padding(bottom = 60.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            // Animated geometric ribbon AI logo
                            AiChatbotLogo(
                                size = 72.dp,
                                color = MaterialTheme.colorScheme.onBackground,
                                animate = true
                            )

                            Spacer(modifier = Modifier.height(24.dp))

                            // Editorial greeting
                            Text(
                                text = greeting,
                                style = MaterialTheme.typography.headlineMedium.copy(
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onBackground,
                                    letterSpacing = 0.2.sp
                                ),
                                textAlign = TextAlign.Center
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            Text(
                                text = "How can I help you today?",
                                style = MaterialTheme.typography.bodyLarge.copy(
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                                    fontSize = 15.sp
                                ),
                                textAlign = TextAlign.Center
                            )
                        }
                    } else {
                        // Active Chat conversation with refined padding
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(top = 10.dp, bottom = 16.dp)
                        ) {
                            items(uiState.messages, key = { it.id }) { msg ->
                                ChatBubble(
                                    message = msg,
                                    isCurrentlySpeaking = uiState.currentlySpeakingMessageId == msg.id,
                                    onSpeakClick = { text ->
                                        if (uiState.currentlySpeakingMessageId == msg.id) {
                                            viewModel.stopSpeaking()
                                        } else {
                                            viewModel.speakText(text, msg.id)
                                        }
                                    },
                                    onRetryClick = {
                                        viewModel.onSendMessage()
                                    }
                                )
                            }
                        }
                    }
                } else {
                    // Agent Mode Content
                    if (uiState.currentAgentSession == null) {
                        AgentGoalWelcomeView(
                            goalInput = uiState.agentInputText,
                            onGoalInputChange = viewModel::onAgentInputTextChanged,
                            onSubmitGoal = { goal ->
                                viewModel.createAgentSession(goal)
                            },
                            isLoading = uiState.isAgentLoading
                        )
                    } else {
                        val currentSession = uiState.currentAgentSession!!
                        Column(modifier = Modifier.fillMaxSize()) {
                            // Task State Card
                            AgentTaskStateCard(
                                session = currentSession,
                                onTogglePause = {
                                    val newStatus = if (currentSession.status == AgentTaskStatus.PAUSED) {
                                        AgentTaskStatus.IN_PROGRESS
                                    } else {
                                        AgentTaskStatus.PAUSED
                                    }
                                    viewModel.updateAgentTaskStatus(newStatus)
                                },
                                onMarkCompleted = {
                                    viewModel.updateAgentTaskStatus(AgentTaskStatus.COMPLETED)
                                },
                                onRetrySubtask = viewModel::retrySubtask,
                                onVerifySubtask = viewModel::verifySubtask
                            )

                            // Agent Conversation Stream
                            LazyColumn(
                                state = agentListState,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f),
                                contentPadding = PaddingValues(top = 6.dp, bottom = 16.dp)
                            ) {
                                items(currentSession.messages, key = { it.id }) { msg ->
                                    if (msg.role == MessageRole.ASSISTANT) {
                                        val associatedTools = currentSession.toolExecutions.filter {
                                            it.messageId == msg.id || (it.messageId == null && currentSession.messages.firstOrNull { m -> m.role == MessageRole.ASSISTANT }?.id == msg.id)
                                        }
                                        for (record in associatedTools) {
                                            ToolExecutionCard(record = record)
                                        }
                                    }

                                    ChatBubble(
                                        message = msg,
                                        isCurrentlySpeaking = uiState.currentlySpeakingMessageId == msg.id,
                                        onSpeakClick = { text ->
                                            if (uiState.currentlySpeakingMessageId == msg.id) {
                                                viewModel.stopSpeaking()
                                            } else {
                                                viewModel.speakText(text, msg.id)
                                            }
                                        },
                                        onRetryClick = {
                                            viewModel.sendAgentFollowUp()
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Modal Sheets and Dialogs
    ModelSelectorSheet(
        isOpen = uiState.isModelSheetOpen,
        selectedModel = uiState.selectedModel,
        onModelSelected = viewModel::setSelectedModel,
        onOpenParametersClick = { viewModel.setParametersSheetOpen(true) },
        onDismiss = { viewModel.setModelSheetOpen(false) }
    )

    ModelParametersDialog(
        isOpen = uiState.isParametersSheetOpen,
        currentSystemPrompt = uiState.systemPrompt,
        currentTemperature = uiState.temperature,
        currentTopP = uiState.topP,
        currentMaxTokens = uiState.maxTokens,
        onSaveParameters = viewModel::updateParameters,
        onDismiss = { viewModel.setParametersSheetOpen(false) }
    )

    AboutDeveloperDialog(
        isOpen = uiState.isAboutDialogOpen,
        onDismiss = { viewModel.setAboutDialogOpen(false) },
        onSecretDeveloperUnlocked = { viewModel.setApiKeyDialogOpen(true) }
    )

    UpgradePlanDialog(
        isOpen = uiState.isUpgradeDialogOpen,
        currentPlan = uiState.planType,
        onDismiss = { viewModel.setUpgradeDialogOpen(false) },
        onSelectPlan = viewModel::setPlanType
    )

    ApiKeyDialog(
        isOpen = uiState.isApiKeyDialogOpen,
        currentKey = uiState.apiKey,
        onSaveKey = viewModel::setCustomApiKey,
        onDismiss = { viewModel.setApiKeyDialogOpen(false) }
    )

    AttachmentBottomSheet(
        isOpen = uiState.isAttachmentSheetOpen,
        onPickImage = {
            photoPickerLauncher.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
            )
        },
        onPickDocument = {
            documentPickerLauncher.launch(
                arrayOf(
                    "text/*",
                    "application/pdf",
                    "application/json",
                    "application/msword",
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                )
            )
        },
        onSelectPrompt = { prompt ->
            viewModel.onSendMessage(prompt)
        },
        onDismiss = { viewModel.setAttachmentSheetOpen(false) }
    )

    VoiceModeDialog(
        isOpen = uiState.isVoiceModeOpen,
        onDismiss = { viewModel.setVoiceModeOpen(false) },
        statusText = uiState.voiceStatusText,
        transcriptText = uiState.voiceTranscriptText,
        isMuted = uiState.isVoiceMuted,
        onToggleMute = viewModel::toggleVoiceMute,
        onOrbClick = {
            val hasPermission = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED

            if (hasPermission) {
                viewModel.updateVoiceStatus("Listening...")
                startSpeechRecognition(
                    context = context,
                    onResult = { resultText ->
                        if (resultText.isNotBlank()) {
                            viewModel.updateVoiceTranscript(resultText)
                            viewModel.updateVoiceStatus("Thinking...")
                            viewModel.onSendMessage(resultText)
                        } else {
                            viewModel.updateVoiceStatus("Tap orb to speak")
                        }
                    },
                    onError = {
                        viewModel.updateVoiceStatus("Didn't catch that. Tap to retry.")
                    },
                    onRecognizerCreated = { voiceModeRecognizer = it },
                    onPartialResult = { partial ->
                        viewModel.updateVoiceTranscript(partial)
                    }
                )
            } else {
                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    )
}

private fun getTimeBasedGreeting(): String {
    val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    return when (hour) {
        in 5..11 -> "Good Morning"
        in 12..16 -> "Good Afternoon"
        else -> "Good Evening"
    }
}

private fun startSpeechRecognition(
    context: Context,
    onResult: (String) -> Unit,
    onError: () -> Unit,
    onRecognizerCreated: (SpeechRecognizer) -> Unit,
    onPartialResult: ((String) -> Unit)? = null
) {
    if (!SpeechRecognizer.isRecognitionAvailable(context)) {
        Toast.makeText(context, "Speech recognition not available on device", Toast.LENGTH_SHORT).show()
        onError()
        return
    }

    val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
    onRecognizerCreated(recognizer)

    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, onPartialResult != null)
    }

    recognizer.setRecognitionListener(object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onError(error: Int) {
            try { recognizer.destroy() } catch (_: Exception) {}
            onError()
        }
        override fun onResults(results: Bundle?) {
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val text = matches?.firstOrNull() ?: ""
            try { recognizer.destroy() } catch (_: Exception) {}
            onResult(text)
        }
        override fun onPartialResults(partialResults: Bundle?) {
            val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val text = matches?.firstOrNull() ?: ""
            if (text.isNotBlank()) {
                onPartialResult?.invoke(text)
            }
        }
        override fun onEvent(eventType: Int, params: Bundle?) {}
    })

    recognizer.startListening(intent)
}
