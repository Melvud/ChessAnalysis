package com.example.chessanalysis.ui.screens

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.QueryStats
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.*
import com.example.chessanalysis.FullReport
import com.example.chessanalysis.GameHeader
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import androidx.navigation.NavDestination.Companion.hierarchy
import com.example.chessanalysis.ui.navigation.BottomItems
import com.example.chessanalysis.ui.navigation.Routes
import androidx.compose.ui.Modifier

@Composable
fun HomeWithBottomNav(
    profile: com.example.chessanalysis.ui.UserProfile,
    games: List<GameHeader>,
    openingFens: Set<String>,
    onOpenReport: (FullReport) -> Unit,
    onOpenProfileEdit: (() -> Unit)? = null
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
            // Вкладка «Бот»: свой локальный стек — setup → play
            composable(Routes.BOT) {
                val botNav = rememberNavController()
                NavHost(
                    navController = botNav,
                    startDestination = "bot_setup"
                ) {
                    composable("bot_setup") {
                        BotGameScreen(
                            onStart = { cfg ->
                                val json = Json.encodeToString(cfg)
                                botNav.currentBackStackEntry?.savedStateHandle?.set("cfg", json)
                                botNav.navigate("bot_play")
                            }
                        )
                    }
                    composable("bot_play") {
                        val json = botNav.previousBackStackEntry?.savedStateHandle?.get<String>("cfg")
                        val cfg = json?.let { runCatching { Json.decodeFromString(BotConfig.serializer(), it) }.getOrNull() }
                        if (cfg == null) {
                            botNav.popBackStack()
                        } else {
                            BotPlayScreen(
                                config = cfg,
                                onBack = { botNav.popBackStack() }
                            )
                        }
                    }
                }
            }
            composable(Routes.STATS) { StatsScreen() }
            composable(Routes.PROFILE) { ProfileHomeScreen(onOpenProfileEdit) }
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

/* ----- общее описание конфигурации бота ----- */
@Serializable
data class BotConfig(
    val elo: Int,               // 800..2600
    val side: BotSide,          // WHITE / BLACK / RANDOM
    val hints: Boolean,         // показывать зелёную стрелку лучшего хода
    val showLines: Boolean,     // показывать панель из 3 линий
    val multiPv: Int = 3        // кол-во линий для панели
)

@Serializable
enum class BotSide { WHITE, BLACK, RANDOM }
