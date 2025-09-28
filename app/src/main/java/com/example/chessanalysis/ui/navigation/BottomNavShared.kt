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
    const val Bot   = "tab/bot"
    const val Stats = "tab/stats"
    const val Profile = "tab/profile"
}

// Набор пунктов для bottom bar
val BottomItems = listOf(
    BottomItem(Routes.Games,   "Игры",   Icons.Default.List),
    BottomItem(Routes.Bot,     "Бот",    Icons.Default.SmartToy),
    BottomItem(Routes.Stats,   "Статы",  Icons.Default.Insights),
    BottomItem(Routes.Profile, "Профиль",Icons.Default.Person),
)
