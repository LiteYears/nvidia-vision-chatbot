package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Face
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun TopNavigationBar(
    planText: String = "Free plan",
    isIncognito: Boolean = false,
    canClearMessages: Boolean = false,
    onMenuClick: () -> Unit,
    onPlanClick: () -> Unit,
    onIncognitoClick: () -> Unit,
    onClearMessagesClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Left: Hamburger Menu Icon
        IconButton(
            onClick = onMenuClick,
            modifier = Modifier
                .size(44.dp)
                .testTag("menu_button")
        ) {
            Icon(
                imageVector = Icons.Default.Menu,
                contentDescription = "Open Menu",
                tint = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.size(24.dp)
            )
        }

        // Center: "Free plan • Upgrade" pill button
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier
                .clip(RoundedCornerShape(20.dp))
                .clickable { onPlanClick() }
                .testTag("upgrade_plan_button")
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Text(
                    text = planText,
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = FontWeight.Normal,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f)
                    )
                )

                Text(
                    text = "•",
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
                    )
                )

                Text(
                    text = "Upgrade",
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onBackground,
                        textDecoration = TextDecoration.Underline
                    )
                )
            }
        }

        // Right actions: Clear Messages (if messages present) + Incognito toggle
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (canClearMessages) {
                IconButton(
                    onClick = onClearMessagesClick,
                    modifier = Modifier
                        .size(44.dp)
                        .testTag("clear_messages_button")
                ) {
                    Icon(
                        imageVector = Icons.Outlined.DeleteSweep,
                        contentDescription = "Clear Chat Messages",
                        tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f),
                        modifier = Modifier.size(23.dp)
                    )
                }
            }

            Box(contentAlignment = Alignment.TopEnd) {
                IconButton(
                    onClick = onIncognitoClick,
                    modifier = Modifier
                        .size(44.dp)
                        .testTag("incognito_button")
                ) {
                    Icon(
                        imageVector = if (isIncognito) Icons.Outlined.VisibilityOff else Icons.Outlined.Face,
                        contentDescription = if (isIncognito) "Incognito Active" else "Toggle Incognito",
                        tint = if (isIncognito) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.85f),
                        modifier = Modifier.size(24.dp)
                    )
                }

                if (isIncognito) {
                    Box(
                        modifier = Modifier
                            .size(9.dp)
                            .background(Color(0xFFE11D48), CircleShape)
                            .border(1.5.dp, MaterialTheme.colorScheme.background, CircleShape)
                    )
                }
            }
        }
    }
}
