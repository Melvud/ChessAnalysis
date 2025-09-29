package com.example.chessanalysis.ui.screens

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.List
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.chessanalysis.FullReport
import com.example.chessanalysis.GameHeader
import com.example.chessanalysis.ui.UserProfile

private const val TAB_GAMES = "tab/games"
private const val TAB_PROFILE = "tab/profile"

/**
 * Внутренний граф для нижней навигации «Партии»/«Профиль».
 * Снаружи (в AppRoot) остаются маршруты отчётов.
 */
@Composable
fun HomeWithBottomNav(
    profile: UserProfile,
    games: List<GameHeader>,
    openingFens: Set<String>,
    onOpenReport: (FullReport) -> Unit,
    onSaveProfile: (UserProfile) -> Unit,
    onLogout: () -> Unit
) {
    val tabsNav = rememberNavController()

    val items = listOf(
        BottomItem(
            route = TAB_GAMES,
            label = "Партии",
            icon = { Icon(Icons.Default.List, contentDescription = "Партии") }
        ),
        BottomItem(
            route = TAB_PROFILE,
            label = "Профиль",
            icon = { Icon(Icons.Default.AccountCircle, contentDescription = "Профиль") }
        )
    )

    Scaffold(
        bottomBar = {
            val backStack by tabsNav.currentBackStackEntryAsState()
            val currentRoute = backStack?.destination?.route
            NavigationBar {
                items.forEach { item ->
                    NavigationBarItem(
                        selected = currentRoute == item.route,
                        onClick = {
                            if (currentRoute != item.route) {
                                tabsNav.navigate(item.route) {
                                    popUpTo(TAB_GAMES) { inclusive = false }
                                    launchSingleTop = true
                                }
                            }
                        },
                        icon = item.icon,
                        label = { Text(item.label) }
                    )
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = tabsNav,
            startDestination = TAB_GAMES,
            modifier = Modifier.padding(innerPadding)
        ) {
            addGamesTab(
                profile = profile,
                games = games,
                openingFens = openingFens,
                onOpenReport = onOpenReport
            )
            addProfileTab(
                profile = profile,
                onSave = onSaveProfile,
                onLogout = onLogout
            )
        }
    }
}

private data class BottomItem(
    val route: String,
    val label: String,
    val icon: @Composable () -> Unit
)

private fun NavGraphBuilder.addGamesTab(
    profile: UserProfile,
    games: List<GameHeader>,
    openingFens: Set<String>,
    onOpenReport: (FullReport) -> Unit
) {
    composable(TAB_GAMES) {
        GamesListScreen(
            profile = profile,
            games = games,
            openingFens = openingFens,
            onOpenReport = onOpenReport
        )
    }
}

private fun NavGraphBuilder.addProfileTab(
    profile: UserProfile,
    onSave: (UserProfile) -> Unit,
    onLogout: () -> Unit
) {
    composable(TAB_PROFILE) {
        // Вкладка профиля внутри нижнего меню: кнопки «назад» нет.
        ProfileScreen(
            profile = profile,
            onSave = onSave,
            onLogout = onLogout,
            onBack = { /* игнорируем, профиль — вкладка */ }
        )
    }
}
