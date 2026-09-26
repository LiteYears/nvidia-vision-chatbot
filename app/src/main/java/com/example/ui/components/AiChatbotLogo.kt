package com.example.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Renders the geometric four-quadrant folding ribbon AI logo.
 * Supports smooth animated mode (breathing pulse, rotation sway, and radiant aura)
 * while preserving the exact original geometry and iconography.
 */
@Composable
fun AiChatbotLogo(
    modifier: Modifier = Modifier,
    size: Dp = 56.dp,
    color: Color = MaterialTheme.colorScheme.onBackground,
    animate: Boolean = true
) {
    if (!animate) {
        StaticAiChatbotLogo(
            modifier = modifier,
            size = size,
            color = color
        )
    } else {
        AnimatedAiChatbotLogo(
            modifier = modifier,
            size = size,
            color = color
        )
    }
}

@Composable
fun AnimatedAiChatbotLogo(
    modifier: Modifier = Modifier,
    size: Dp = 56.dp,
    color: Color = MaterialTheme.colorScheme.onBackground
) {
    val infiniteTransition = rememberInfiniteTransition(label = "logo_anim")

    // Breathing pulse scale
    val scale by infiniteTransition.animateFloat(
        initialValue = 0.94f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "logo_scale"
    )

    // Gentle rotational sway
    val rotation by infiniteTransition.animateFloat(
        initialValue = -3.5f,
        targetValue = 3.5f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "logo_rotation"
    )

    // Ambient glow pulse
    val auraAlpha by infiniteTransition.animateFloat(
        initialValue = 0.12f,
        targetValue = 0.38f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "logo_aura"
    )

    val primaryColor = MaterialTheme.colorScheme.primary

    Box(
        modifier = modifier
            .size(size)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                rotationZ = rotation
            }
    ) {
        Canvas(modifier = Modifier.size(size)) {
            val w = this.size.width
            val h = this.size.height

            // Background luminous glow
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        primaryColor.copy(alpha = auraAlpha),
                        primaryColor.copy(alpha = auraAlpha * 0.3f),
                        Color.Transparent
                    ),
                    center = Offset(w * 0.5f, h * 0.5f),
                    radius = w * 0.65f
                )
            )

            // Top facet
            val path1 = Path().apply {
                moveTo(w * 0.44f, h * 0.08f)
                lineTo(w * 0.72f, h * 0.36f)
                lineTo(w * 0.58f, h * 0.50f)
                lineTo(w * 0.30f, h * 0.22f)
                close()
            }
            drawPath(path = path1, color = color, style = Fill)

            // Right facet
            val path2 = Path().apply {
                moveTo(w * 0.64f, h * 0.42f)
                lineTo(w * 0.92f, h * 0.70f)
                lineTo(w * 0.78f, h * 0.84f)
                lineTo(w * 0.50f, h * 0.56f)
                close()
            }
            drawPath(path = path2, color = color, style = Fill)

            // Bottom facet
            val path3 = Path().apply {
                moveTo(w * 0.28f, h * 0.48f)
                lineTo(w * 0.56f, h * 0.76f)
                lineTo(w * 0.42f, h * 0.90f)
                lineTo(w * 0.14f, h * 0.62f)
                close()
            }
            drawPath(path = path3, color = color, style = Fill)

            // Left / inner ribbon facet
            val path4 = Path().apply {
                moveTo(w * 0.50f, h * 0.16f)
                lineTo(w * 0.36f, h * 0.30f)
                lineTo(w * 0.22f, h * 0.16f)
                lineTo(w * 0.36f, h * 0.02f)
                close()
            }
            drawPath(path = path4, color = color, style = Fill)
        }
    }
}

@Composable
private fun StaticAiChatbotLogo(
    modifier: Modifier = Modifier,
    size: Dp = 52.dp,
    color: Color = MaterialTheme.colorScheme.onBackground
) {
    Box(modifier = modifier.size(size)) {
        Canvas(modifier = Modifier.size(size)) {
            val w = this.size.width
            val h = this.size.height

            // Top facet
            val path1 = Path().apply {
                moveTo(w * 0.44f, h * 0.08f)
                lineTo(w * 0.72f, h * 0.36f)
                lineTo(w * 0.58f, h * 0.50f)
                lineTo(w * 0.30f, h * 0.22f)
                close()
            }
            drawPath(path = path1, color = color, style = Fill)

            // Right facet
            val path2 = Path().apply {
                moveTo(w * 0.64f, h * 0.42f)
                lineTo(w * 0.92f, h * 0.70f)
                lineTo(w * 0.78f, h * 0.84f)
                lineTo(w * 0.50f, h * 0.56f)
                close()
            }
            drawPath(path = path2, color = color, style = Fill)

            // Bottom facet
            val path3 = Path().apply {
                moveTo(w * 0.28f, h * 0.48f)
                lineTo(w * 0.56f, h * 0.76f)
                lineTo(w * 0.42f, h * 0.90f)
                lineTo(w * 0.14f, h * 0.62f)
                close()
            }
            drawPath(path = path3, color = color, style = Fill)

            // Left / inner ribbon facet
            val path4 = Path().apply {
                moveTo(w * 0.50f, h * 0.16f)
                lineTo(w * 0.36f, h * 0.30f)
                lineTo(w * 0.22f, h * 0.16f)
                lineTo(w * 0.36f, h * 0.02f)
                close()
            }
            drawPath(path = path4, color = color, style = Fill)
        }
    }
}
