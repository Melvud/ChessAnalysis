package com.example.chessanalysis.ui.navigation

import androidx.annotation.DrawableRes
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Person

// ПУБЛИЧНЫЕ (без private), чтобы импортировать из других экранов
data class BottomItem(
    val route: String,
    val label: String,
    val icon: ImageVector
)

object Routes {
    const val Games = "tab/games"
    const val Profile = "tab/profile"
}

// Набор пунктов для bottom bar
val BottomItems = listOf(
    BottomItem(Routes.Games,   "Игры",   Icons.Default.List),
    BottomItem(Routes.Profile, "Профиль",Icons.Default.Person),
)
