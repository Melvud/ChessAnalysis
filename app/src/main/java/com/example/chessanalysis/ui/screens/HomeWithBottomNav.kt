package com.example.chessanalysis.ui.screens

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.*
import com.example.chessanalysis.FullReport
import com.example.chessanalysis.GameHeader
import com.example.chessanalysis.ui.navigation.BottomItems
import com.example.chessanalysis.ui.navigation.Routes
import androidx.compose.foundation.layout.padding

@Composable
fun HomeWithBottomNav(
    profile: com.example.chessanalysis.ui.UserProfile,
    games: List<GameHeader>,
    openingFens: Set<String>,
    onOpenReport: (FullReport) -> Unit,
    onOpenProfileEdit: (() -> Unit)? = null,
    onOpenBotSetup: (() -> Unit)? = null
) {
    val tabsNav = rememberNavController()

    Scaffold(
        bottomBar = {
            val backStackEntry by tabsNav.currentBackStackEntryAsState()
            val currentDestination = backStackEntry?.destination
            NavigationBar {
                BottomItems.forEach { item ->
                    NavigationBarItem(
                        selected = currentDestination?.hierarchy?.any { it.route == item.route } == true,
                        onClick = {
                            if (item.route == Routes.Bot && onOpenBotSetup != null) {
                                onOpenBotSetup.invoke()
                            } else {
                                tabsNav.navigate(item.route) {
                                    popUpTo(tabsNav.graph.findStartDestination().id) {
                                        saveState = true
                                    }
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
            startDestination = Routes.Games,
            modifier = Modifier.padding(padding)
        ) {
            composable(Routes.Games) {
                GamesListScreen(
                    profile = profile,
                    games = games,
                    openingFens = openingFens,
                    onOpenProfile = { tabsNav.navigate(Routes.Profile) },
                    onOpenReport = onOpenReport
                )
            }
            composable(Routes.Stats) { StatsScreen() }
            composable(Routes.Profile) { ProfileHomeScreen(onOpenProfileEdit) }
            composable(Routes.Bot) { /* пустышка, управляется через onOpenBotSetup */ }
        }
    }
}

@Composable
fun ProfileHomeScreen(onOpenProfileEdit: (() -> Unit)?) {
    onOpenProfileEdit?.invoke()
}