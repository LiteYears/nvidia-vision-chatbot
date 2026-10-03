package com.example.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog

@Composable
fun ApiKeyDialog(
    isOpen: Boolean,
    currentKey: String,
    onSaveKey: (String) -> Unit,
    onDismiss: () -> Unit
) {
    if (!isOpen) return

    val context = LocalContext.current
    var keyInput by remember(currentKey) { mutableStateOf(currentKey) }
    var isKeyVisible by remember { mutableStateOf(false) }

    val isKeyConfigured = keyInput.isNotBlank() && !keyInput.contains("placeholder", ignoreCase = true)

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(22.dp),
            color = MaterialTheme.colorScheme.background,
            border = androidx.compose.foundation.BorderStroke(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .testTag("api_key_dialog")
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(22.dp)
            ) {
                // Header with icon and status pill
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .background(
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                                    RoundedCornerShape(10.dp)
                                )
                                .border(
                                    1.dp,
                                    MaterialTheme.colorScheme.outline.copy(alpha = 0.25f),
                                    RoundedCornerShape(10.dp)
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Key,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        Column {
                            Text(
                                text = "NVIDIA API Key",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 16.sp
                                )
                            )
                            Text(
                                text = "Developer configuration",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                                    fontSize = 11.sp
                                )
                            )
                        }
                    }

                    // Status Indicator Pill
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (isKeyConfigured) {
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                        },
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            if (isKeyConfigured) MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
                            else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                        )
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .background(
                                        if (isKeyConfigured) Color(0xFF4CAF50) else Color(0xFFFFB300),
                                        CircleShape
                                    )
                            )
                            Text(
                                text = if (isKeyConfigured) "ACTIVE" else "UNSET",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 10.sp,
                                    letterSpacing = 0.5.sp,
                                    color = if (isKeyConfigured) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // API Host Info Card
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Terminal,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                text = "build.nvidia.com/build",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.SemiBold,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 11.5.sp
                                )
                            )
                        }
                        Text(
                            text = "Connects directly to NVIDIA NIM endpoints for vision, reasoning, and coding models.",
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                                fontSize = 12.sp,
                                lineHeight = 16.sp
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // API Key Text Field with Actions
                OutlinedTextField(
                    value = keyInput,
                    onValueChange = { keyInput = it },
                    label = { Text("API Key (nvapi-...)") },
                    placeholder = { Text("nvapi-xxxxxxxxxxxxxxxx") },
                    visualTransformation = if (isKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (keyInput.isNotEmpty()) {
                                IconButton(
                                    onClick = { keyInput = "" },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.Clear,
                                        contentDescription = "Clear Key",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }

                            IconButton(
                                onClick = { isKeyVisible = !isKeyVisible },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    imageVector = if (isKeyVisible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                    contentDescription = if (isKeyVisible) "Hide Key" else "Show Key",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(17.dp)
                                )
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("api_key_input_field"),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
                        focusedBorderColor = MaterialTheme.colorScheme.primary
                    ),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp
                    ),
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Helper Quick-Actions: Paste from clipboard
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Key is saved securely on this device",
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            fontSize = 10.5.sp
                        )
                    )

                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
                        ),
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                val clip = clipboard.primaryClip
                                if (clip != null && clip.itemCount > 0) {
                                    val text = clip.getItemAt(0).text?.toString() ?: ""
                                    if (text.isNotBlank()) {
                                        keyInput = text.trim().trim('.', ',', ';', ':', '"', '\'', '`', ' ')
                                        Toast.makeText(context, "Pasted and formatted from clipboard", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.ContentPaste,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(13.dp)
                            )
                            Text(
                                text = "Paste",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.Medium,
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                Spacer(modifier = Modifier.height(14.dp))

                // Action Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        shape = RoundedCornerShape(12.dp),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
                        )
                    ) {
                        Text(
                            text = "Cancel",
                            style = MaterialTheme.typography.labelMedium.copy(
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        )
                    }

                    Spacer(modifier = Modifier.width(10.dp))

                    Button(
                        onClick = {
                            val sanitizedKey = keyInput.trim().trim('.', ',', ';', ':', '"', '\'', '`', ' ')
                            onSaveKey(sanitizedKey)
                            Toast.makeText(context, "API Key saved successfully", Toast.LENGTH_SHORT).show()
                            onDismiss()
                        },
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Check,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Save Key",
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontWeight = FontWeight.SemiBold
                            )
                        )
                    }
                }
            }
        }
    }
}
