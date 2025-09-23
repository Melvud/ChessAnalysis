package com.example.chessanalysis.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val ChesscomLike = lightColorScheme(
    primary = Color(0xFF2E7D32),
    secondary = Color(0xFF5D4037),
    surface = Color(0xFFF6F4EE),
    surfaceVariant = Color(0xFFEDE8D9),
    onSurface = Color(0xFF1B1B1B),
    outline = Color(0xFFBDB49A)
)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = ChesscomLike, content = content)
}
