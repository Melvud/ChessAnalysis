package com.example.chessanalysis.ui.screens

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.QueryStats
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier

/**
 * Нижняя навигация без вкладки «Игра с ботом».
 * Оставлены только: Список партий, Статистика, Профиль.
 */
@Composable
fun HomeWithBottomNav(
    selectedRoute: String,
    onSelect: (String) -> Unit,
) {
    NavigationBar {
        NavigationBarItem(
            selected = selectedRoute == "games",
            onClick = { onSelect("games") },
            icon = { Icon(Icons.Default.List, contentDescription = null) },
            label = { Text("Игры") }
        )
        NavigationBarItem(
            selected = selectedRoute == "stats",
            onClick = { onSelect("stats") },
            icon = { Icon(Icons.Default.QueryStats, contentDescription = null) },
            label = { Text("Статистика") }
        )
        NavigationBarItem(
            selected = selectedRoute == "profile",
            onClick = { onSelect("profile") },
            icon = { Icon(Icons.Default.Person, contentDescription = null) },
            label = { Text("Профиль") }
        )
    }
}
