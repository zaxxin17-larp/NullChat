package com.nullsec.nullchat.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val NullChatColors = darkColorScheme(
    primary = Color(0xFF3FD6E8),
    onPrimary = Color(0xFF05090D),
    primaryContainer = Color(0xFF0C1219),
    onPrimaryContainer = Color(0xFFDBE8EC),
    secondary = Color(0xFF8AEAF5),
    background = Color(0xFF05090D),
    onBackground = Color(0xFFDBE8EC),
    surface = Color(0xFF0A1118),
    onSurface = Color(0xFFDBE8EC),
    surfaceVariant = Color(0xFF131D26),
    onSurfaceVariant = Color(0xFF9AABB5),
    outline = Color(0xFF1B2833),
    error = Color(0xFFEF5B5B)
)

@Composable
fun NullChatTheme(
    @Suppress("UNUSED_PARAMETER") darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(colorScheme = NullChatColors, content = content)
}
