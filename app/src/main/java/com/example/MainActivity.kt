package com.example

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.agent.service.AgentExecutionService
import com.example.data.model.AppMode
import com.example.ui.screens.ChatScreen
import com.example.ui.screens.SplashScreen
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.viewmodel.ChatViewModel

class MainActivity : ComponentActivity() {

    private var currentIntentState by mutableStateOf<Intent?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        checkNotificationPermission()
        currentIntentState = intent

        setContent {
            MyApplicationTheme {
                val viewModel: ChatViewModel = viewModel()
                var showSplash by remember { mutableStateOf(true) }

                LaunchedEffect(currentIntentState) {
                    currentIntentState?.let { handleAgentNotificationIntent(it, viewModel) }
                }

                Surface(
                    modifier = Modifier.fillMaxSize()
                ) {
                    Crossfade(
                        targetState = showSplash,
                        animationSpec = tween(400),
                        label = "splash_crossfade"
                    ) { isSplash ->
                        if (isSplash) {
                            SplashScreen(
                                onSplashFinished = { showSplash = false }
                            )
                        } else {
                            ChatScreen(viewModel = viewModel)
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        currentIntentState = intent
    }

    private fun handleAgentNotificationIntent(intent: Intent, viewModel: ChatViewModel) {
        val navigateToAgent = intent.getBooleanExtra(AgentExecutionService.EXTRA_NAVIGATE_TO_AGENT, false)
        if (navigateToAgent) {
            viewModel.setAppMode(AppMode.AGENT)
            val sessionId = intent.getStringExtra(AgentExecutionService.EXTRA_SESSION_ID)
            if (!sessionId.isNullOrBlank()) {
                viewModel.selectAgentSessionById(sessionId)
            }
        }
    }

    private fun checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1001)
            }
        }
    }
}
