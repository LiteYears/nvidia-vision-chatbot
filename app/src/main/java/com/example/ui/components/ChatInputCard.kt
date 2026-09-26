package com.example.ui.components

import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.data.rag.RagDocument
import com.example.ui.theme.WaveformBlack

@Composable
fun ChatInputCard(
    inputText: String,
    onInputTextChange: (String) -> Unit,
    selectedModel: String,
    onModelClick: () -> Unit,
    onSendClick: () -> Unit,
    onVoiceClick: () -> Unit,
    onMicDictationClick: () -> Unit,
    onAddAttachmentClick: () -> Unit,
    selectedImageUri: Uri? = null,
    onRemoveImage: () -> Unit = {},
    attachedDocument: RagDocument? = null,
    onRemoveDocument: () -> Unit = {},
    isStreaming: Boolean = false,
    onStopStreaming: () -> Unit = {},
    isVoiceRecording: Boolean = false,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .shadow(
                elevation = 6.dp,
                shape = RoundedCornerShape(26.dp),
                spotColor = Color(0x18000000),
                ambientColor = Color(0x0C000000)
            ),
        shape = RoundedCornerShape(26.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp)
        ) {
            // Optional attached image preview
            AnimatedVisibility(visible = selectedImageUri != null) {
                if (selectedImageUri != null) {
                    Box(
                        modifier = Modifier
                            .padding(bottom = 8.dp)
                            .size(72.dp)
                    ) {
                        AsyncImage(
                            model = selectedImageUri,
                            contentDescription = "Selected image preview",
                            modifier = Modifier
                                .size(72.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(14.dp)),
                            contentScale = ContentScale.Crop
                        )
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .size(22.dp)
                                .background(Color.Black.copy(alpha = 0.7f), CircleShape)
                                .clickable { onRemoveImage() },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Remove attached image",
                                tint = Color.White,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                }
            }

            // Optional attached RAG document preview chip
            AnimatedVisibility(visible = attachedDocument != null) {
                if (attachedDocument != null) {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                modifier = Modifier.weight(1f, fill = false)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(32.dp)
                                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f), RoundedCornerShape(8.dp)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.Description,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                Column {
                                    Text(
                                        text = attachedDocument.name,
                                        style = MaterialTheme.typography.labelMedium.copy(
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onSurface
                                        ),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "RAG Indexed • ${attachedDocument.chunks.size} knowledge segments",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            color = MaterialTheme.colorScheme.primary,
                                            fontSize = 11.sp
                                        )
                                    )
                                }
                            }

                            IconButton(
                                onClick = onRemoveDocument,
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Remove document",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
            }

            // Top area: Text Input
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 2.dp)
            ) {
                if (inputText.isEmpty()) {
                    Text(
                        text = if (attachedDocument != null) "Ask a question about this document..." else "How can I help you today?",
                        style = MaterialTheme.typography.bodyLarge.copy(
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    )
                }

                BasicTextField(
                    value = inputText,
                    onValueChange = onInputTextChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("chat_input_field"),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    maxLines = 5
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Bottom toolbar row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Left controls: Plus Icon and Model Selector
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // Plus button (Add attachment) with 48dp minimum touch target
                    IconButton(
                        onClick = onAddAttachmentClick,
                        modifier = Modifier
                            .size(44.dp)
                            .testTag("add_attachment_button")
                    ) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = "Add attachment",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    // Model Selector Pill (e.g. "Quantum 3", "Llama 3.2", "DeepSeek")
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                        modifier = Modifier
                            .clip(RoundedCornerShape(18.dp))
                            .clickable { onModelClick() }
                            .testTag("model_selector_button")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 11.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            val displayModel = when {
                                selectedModel.contains("llama", ignoreCase = true) -> "Llama 3.2"
                                selectedModel.contains("quantum", ignoreCase = true) -> "Quantum 3"
                                selectedModel.contains("gemma", ignoreCase = true) -> "Gemma 26B"
                                selectedModel.contains("muse", ignoreCase = true) -> "Muse 30B"
                                selectedModel.contains("deepseek", ignoreCase = true) -> "DeepSeek"
                                else -> selectedModel.substringAfterLast("/").take(12)
                            }

                            Text(
                                text = displayModel,
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            )
                            Icon(
                                imageVector = Icons.Default.KeyboardArrowDown,
                                contentDescription = "Select Model",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }

                // Right controls: Microphone & Waveform/Send Circle / Stop Streaming Button
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // Microphone button
                    IconButton(
                        onClick = onMicDictationClick,
                        modifier = Modifier
                            .size(44.dp)
                            .testTag("mic_dictation_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Mic,
                            contentDescription = "Voice Dictation",
                            tint = if (isVoiceRecording) Color(0xFFE11D48) else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(22.dp)
                        )
                    }

                    if (isStreaming) {
                        // ChatGPT Stop Generating Square Button
                        Box(
                            modifier = Modifier
                                .size(42.dp)
                                .clip(CircleShape)
                                .background(WaveformBlack)
                                .clickable { onStopStreaming() }
                                .testTag("stop_streaming_button"),
                            contentAlignment = Alignment.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(14.dp)
                                    .background(Color.White, RoundedCornerShape(3.dp))
                            )
                        }
                    } else {
                        // Black circular button: Send if text is present, Waveform (voice mode) if empty
                        val hasContent = inputText.isNotBlank() || selectedImageUri != null || attachedDocument != null

                        Box(
                            modifier = Modifier
                                .size(42.dp)
                                .clip(CircleShape)
                                .background(WaveformBlack)
                                .clickable {
                                    if (hasContent) {
                                        onSendClick()
                                    } else {
                                        onVoiceClick()
                                    }
                                }
                                .testTag("action_circle_button"),
                            contentAlignment = Alignment.Center
                        ) {
                            if (hasContent) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.Send,
                                    contentDescription = "Send Message",
                                    tint = Color.White,
                                    modifier = Modifier.size(19.dp)
                                )
                            } else {
                                // Exact waveform vertical lines icon as shown in screenshot
                                AudioWaveformIcon(
                                    modifier = Modifier.size(20.dp),
                                    color = Color.White
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Custom waveform icon with 5 vertical bars matching the reference design.
 */
@Composable
fun AudioWaveformIcon(
    modifier: Modifier = Modifier,
    color: Color = Color.White
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height

        val barCount = 5
        val barWidth = 2.4.dp.toPx()
        val spacing = (w - (barCount * barWidth)) / (barCount - 1)

        // Heights normalized: short, medium, tall, medium, short
        val heightMultipliers = floatArrayOf(0.35f, 0.65f, 1.0f, 0.65f, 0.35f)

        for (i in 0 until barCount) {
            val barHeight = h * heightMultipliers[i]
            val x = i * (barWidth + spacing)
            val y = (h - barHeight) / 2f

            drawRoundRect(
                color = color,
                topLeft = Offset(x, y),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f)
            )
        }
    }
}
