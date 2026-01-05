package com.github.movesense.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF4F7CFF),
    secondary = Color(0xFF00C7B1),
    background = Color(0xFFF6F3EA),
    surface = Color(0xFFF0EBDD),
    onPrimary = Color.White,
    onSecondary = Color.White,
    onBackground = Color(0xFF1F1D17),
    onSurface = Color(0xFF1F1D17)
)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LightColors,
        content = content
    )
}

@Composable
fun DatePickerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(),
        content = content
    )
}
