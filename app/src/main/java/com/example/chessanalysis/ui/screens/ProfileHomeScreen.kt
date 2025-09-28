package com.example.chessanalysis.ui.screens

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.QueryStats
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.compose.*
import com.example.chessanalysis.FullReport
import com.example.chessanalysis.GameHeader
import com.example.chessanalysis.ui.navigation.BottomItems
import com.example.chessanalysis.ui.navigation.Routes

@Composable
fun HomeWithBottomNav(
    profile: com.example.chessanalysis.ui.UserProfile,
    games: List<GameHeader>,
    openingFens: Set<String>,
    onOpenReport: (FullReport) -> Unit,
    onOpenProfileEdit: (() -> Unit)? = null,
    onOpenBotSetup: (() -> Unit)? = null // <-- НОВОЕ: просим родителя открыть setup бота
) {
    val tabsNav = rememberNavController()

    Scaffold(
        bottomBar = {
            val backStackEntry by tabsNav.currentBackStackEntryAsState()
            val currentDestination = backStackEntry?.destination
            NavigationBar {
                items.forEach { item ->
                    NavigationBarItem(
                        selected = currentDestination.isOnDestination(item.route),
                        onClick = {
                            if (item.route == Routes.BOT && onOpenBotSetup != null) {
                                // открываем setup в общем графе AppRoot
                                onOpenBotSetup.invoke()
                            } else {
                                tabsNav.navigate(item.route) {
                                    popUpTo(tabsNav.graph.startDestinationId) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        },
                        icon = { Icon(item.icon, contentDescription = item.label) },
                        label = { Text(item.label) }
                    )
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = tabsNav,
            startDestination = Routes.GAMES,
            modifier = Modifier.padding(padding)
        ) {
            composable(Routes.GAMES) {
                GamesListScreen(
                    profile = profile,
                    games = games,
                    openingFens = openingFens,
                    onOpenProfile = { tabsNav.navigate(Routes.PROFILE) },
                    onOpenReport = onOpenReport
                )
            }
            // сам экран Бота открывает общий граф (см. onOpenBotSetup)
            composable(Routes.STATS) { StatsScreen() }
            composable(Routes.PROFILE) { ProfileHomeScreen(onOpenProfileEdit) }
            composable(Routes.BOT) { /* пустышка, на случай прямой навигации */ }
        }
    }
}

private data class BottomItem(val route: String, val label: String, val icon: ImageVector)
private object Routes {
    const val GAMES = "tab_games"
    const val BOT = "tab_bot"
    const val STATS = "tab_stats"
    const val PROFILE = "tab_profile"
}
private fun NavDestination?.isOnDestination(route: String): Boolean {
    if (this == null) return false
    return hierarchy.any { it.route == route }
}
