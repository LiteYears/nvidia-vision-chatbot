package com.example.ui.components

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.automirrored.outlined.VolumeDown
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.delay
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.data.model.ChatMessage
import com.example.data.model.MessageRole

@Composable
fun ChatBubble(
    message: ChatMessage,
    onSpeakClick: (String) -> Unit = {},
    isCurrentlySpeaking: Boolean = false,
    onRetryClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val clipboardManager = LocalClipboardManager.current

    if (message.role == MessageRole.USER) {
        // User message: right-aligned with light gray rounded bubble
        Row(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.End
        ) {
            Surface(
                shape = RoundedCornerShape(
                    topStart = 18.dp,
                    topEnd = 18.dp,
                    bottomStart = 18.dp,
                    bottomEnd = 4.dp
                ),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier
                    .widthIn(max = 290.dp)
                    .testTag("user_message_bubble")
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    // Optional image attachment
                    if (!message.imageUri.isNullOrEmpty()) {
                        AsyncImage(
                            model = message.imageUri,
                            contentDescription = "User uploaded image",
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(160.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .padding(bottom = 8.dp),
                            contentScale = ContentScale.Crop
                        )
                    } else if (!message.imageBase64.isNullOrEmpty()) {
                        val bitmap = remember(message.imageBase64) {
                            try {
                                val bytes = Base64.decode(message.imageBase64, Base64.DEFAULT)
                                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                            } catch (_: Throwable) {
                                null
                            }
                        }
                        if (bitmap != null) {
                            Image(
                                bitmap = bitmap,
                                contentDescription = "Attached image",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(160.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .padding(bottom = 8.dp),
                                contentScale = ContentScale.Crop
                            )
                        }
                    }

                    if (message.content.isNotBlank()) {
                        Text(
                            text = parseRichInlineMarkdown(message.content),
                            style = MaterialTheme.typography.bodyLarge.copy(
                                color = MaterialTheme.colorScheme.onSurface,
                                fontSize = 15.sp,
                                lineHeight = 22.sp
                            )
                        )
                    }
                }
            }
        }
    } else {
        // Assistant message: left-aligned with transparent background and rich Markdown/Code/Math rendering
        Column(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp)
                .testTag("assistant_message_content")
        ) {
            if (message.isStreaming && message.content.isEmpty()) {
                TypingIndicator()
            } else {
                MarkdownRenderer(
                    content = message.content,
                    isStreaming = message.isStreaming
                )
            }

            if (message.isError) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(vertical = 4.dp)
                ) {
                    Text(
                        text = "Unable to complete request.",
                        style = MaterialTheme.typography.labelMedium.copy(
                            color = MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.Medium
                        )
                    )
                    IconButton(
                        onClick = onRetryClick,
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Refresh,
                            contentDescription = "Retry",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            } else if (!message.isStreaming && message.content.isNotEmpty()) {
                // Action buttons: Copy & Speak with 48dp accessibility targets
                var hasCopied by remember { mutableStateOf(false) }

                LaunchedEffect(hasCopied) {
                    if (hasCopied) {
                        delay(2000)
                        hasCopied = false
                    }
                }

                Row(
                    modifier = Modifier.padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(0.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = {
                            clipboardManager.setText(AnnotatedString(message.content))
                            hasCopied = true
                        },
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("copy_button")
                    ) {
                        Icon(
                            imageVector = if (hasCopied) Icons.Default.Check else Icons.Outlined.ContentCopy,
                            contentDescription = "Copy message",
                            tint = if (hasCopied) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                            modifier = Modifier.size(17.dp)
                        )
                    }

                    IconButton(
                        onClick = { onSpeakClick(message.content) },
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("speak_button")
                    ) {
                        Icon(
                            imageVector = if (isCurrentlySpeaking) Icons.AutoMirrored.Outlined.VolumeUp else Icons.AutoMirrored.Outlined.VolumeDown,
                            contentDescription = "Read aloud",
                            tint = if (isCurrentlySpeaking) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun TypingIndicator() {
    val transition = rememberInfiniteTransition(label = "typing_pulse")
    val alpha1 by transition.animateFloat(
        initialValue = 0.25f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(550, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dot1"
    )
    val alpha2 by transition.animateFloat(
        initialValue = 0.25f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(550, delayMillis = 180, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dot2"
    )
    val alpha3 by transition.animateFloat(
        initialValue = 0.25f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(550, delayMillis = 360, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dot3"
    )

    Row(
        modifier = Modifier.padding(vertical = 10.dp, horizontal = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .alpha(alpha1)
                .background(MaterialTheme.colorScheme.primary, CircleShape)
        )
        Box(
            modifier = Modifier
                .size(8.dp)
                .alpha(alpha2)
                .background(MaterialTheme.colorScheme.primary, CircleShape)
        )
        Box(
            modifier = Modifier
                .size(8.dp)
                .alpha(alpha3)
                .background(MaterialTheme.colorScheme.primary, CircleShape)
        )
    }
}
