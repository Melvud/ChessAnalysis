package com.github.movesense.ui.navigation

import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.List
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
