package com.example.chessanalysis.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.QueryStats
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.chessanalysis.FullReport
import com.example.chessanalysis.GameHeader
import com.example.chessanalysis.ui.screens.GamesListScreen
import com.example.chessanalysis.ui.screens.StatsScreen
import com.example.chessanalysis.ui.screens.bot.BotTabScreen

@Composable
fun HomeWithBottomNav(
    profile: UserProfile,
    games: List<GameHeader>,
    openingFens: Set<String>,
    onOpenReport: (FullReport) -> Unit,
    onOpenProfileEdit: (() -> Unit)? = null,
    onOpenBotSetup: (() -> Unit)? = null
) {
    val tabsNav = rememberNavController()

    val items = listOf(
        BottomItem(route = Routes.GAMES,   label = "Игры",    icon = Icons.Filled.List),
        BottomItem(route = Routes.BOT,     label = "Бот",     icon = Icons.Filled.SmartToy),
        BottomItem(route = Routes.STATS,   label = "Статы",   icon = Icons.Filled.QueryStats),
        BottomItem(route = Routes.PROFILE, label = "Профиль", icon = Icons.Filled.Person),
    )

    Scaffold(
        bottomBar = {
            val backStackEntry by tabsNav.currentBackStackEntryAsState()
            val currentDestination = backStackEntry?.destination
            NavigationBar {
                items.forEach { item ->
                    NavigationBarItem(
                        selected = currentDestination.isOnDestination(item.route),
                        onClick = {
                            tabsNav.navigate(item.route) {
                                popUpTo(tabsNav.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
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
            modifier = Modifier.then(Modifier.padding(padding))
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
            /**
             * Вкладка «Бот».
             * ВАЖНО: больше НИКАКОЙ авто-навигации в теле композиции.
             * Только явный клик → вызов onOpenBotSetup(), который уже определён в AppRoot.
             * Это устраняет цикл, когда после finish экран снова «улетал» на BotPlayScreen.
             */
            composable(Routes.BOT) {
                BotTabScreen(
                    onStartNewBotGame = {
                        // Запускаем корневой поток botSetup → botPlay
                        onOpenBotSetup?.invoke()
                    }
                )
            }
            composable(Routes.STATS) { StatsScreen() }
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
