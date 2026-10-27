package org.nodescope.android.core.design

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb

private val lightSenderTones = listOf(0xFF0063A6, 0xFF8A4B00, 0xFF1B7A43, 0xFF9C2F6B, 0xFF5A4AB5, 0xFF00707A, 0xFFA23B2A, 0xFF52667A)
private val darkSenderTones = listOf(0xFF81C3FF, 0xFFFFB866, 0xFF6FD69B, 0xFFF08BC0, 0xFFB3A6FF, 0xFF5FD3DC, 0xFFFF9A85, 0xFFA9BCCF)

/** Stable per-sender tone, readable on the theme's surface. */
@Composable
fun senderColor(sender: String): Color {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.3f
    val tones = if (dark) darkSenderTones else lightSenderTones
    return Color(tones[Math.floorMod(sender.hashCode(), tones.size)])
}

/** Match iOS bubble opacity while retaining Android's stable sender palette. */
@Composable
fun senderBubbleFill(sender: String): Color {
    val tone = senderColor(sender)
    if (MaterialTheme.colorScheme.surface.luminance() < 0.3f) return tone.copy(alpha = 0.50f)
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(tone.toArgb(), hsv)
    return Color.hsv(hsv[0], 0.14f, 0.97f, alpha = 0.75f)
}

@Composable
fun senderBubbleStroke(sender: String): Color = senderColor(sender).copy(
    alpha = if (MaterialTheme.colorScheme.surface.luminance() < 0.3f) 0.55f else 0.25f,
)
