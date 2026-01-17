package com.github.movesense.ui.screens

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Person
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.github.movesense.R
import com.github.movesense.data.local.gameRepository
import com.github.movesense.FullReport
import com.github.movesense.GameHeader
import com.github.movesense.ui.UserProfile
import kotlinx.serialization.json.Json

private const val TAB_GAMES = "games"
private const val TAB_STATISTICS = "statistics"
private const val TAB_PROFILE = "profile"

@Composable
fun HomeWithBottomNav(
        profile: UserProfile,
        games: List<GameHeader>,
        openingFens: Set<String>,
        isFirstLoad: Boolean,
        onFirstLoadComplete: () -> Unit,
        onGamesUpdated: (List<GameHeader>) -> Unit,
        onOpenReport: (FullReport) -> Unit,
        onSaveProfile: (UserProfile) -> Unit,
        onLogout: () -> Unit,
        onAdminClick: () -> Unit,
        shouldShowDateSelection: Boolean,
        onDateSelectionShown: () -> Unit,
        onStartOnboarding: () -> Unit
) {
    val tabsNav = rememberNavController()
    val navBackStackEntry by tabsNav.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    // Add state for handling game navigation
    var gameToOpen by remember { mutableStateOf<GameHeader?>(null) }

    // Context and Repo for loading analyzed games
    val context = LocalContext.current
    val json = remember {
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }
    }
    val repo = remember { context.gameRepository(json) }

    // Hoisted state for analyzed games
    val analyzedGames = remember { mutableStateMapOf<String, FullReport>() }

    // Initial load of analyzed games when games list changes
    LaunchedEffect(games) {
        if (games.isNotEmpty()) {
            val pgns = games.mapNotNull { it.pgn }
            if (pgns.isNotEmpty()) {
                val reports = repo.getCachedReports(pgns)
                analyzedGames.clear()
                analyzedGames.putAll(reports)
            }
        }
    }

    val items = listOf(
        BottomItem(
            route = TAB_GAMES,
            labelRes = R.string.nav_games,
            icon = { Icon(Icons.Default.List, contentDescription = null) }
        ),
        BottomItem(
            route = TAB_STATISTICS,
            labelRes = R.string.statistics,
            icon = { Icon(Icons.Default.DateRange, contentDescription = null) }
        ),
        BottomItem(
            route = TAB_PROFILE,
            labelRes = R.string.nav_profile,
            icon = { Icon(Icons.Default.Person, contentDescription = null) }
        )
    )

    Scaffold(
        bottomBar = {
            NavigationBar {
                val navBackStackEntry by tabsNav.currentBackStackEntryAsState()
                val currentRoute = navBackStackEntry?.destination?.route
                items.forEach { item ->
                    NavigationBarItem(
                        icon = item.icon,
                        label = { Text(stringResource(item.labelRes)) },
                        selected = currentRoute == item.route,
                        onClick = {
                            tabsNav.navigate(item.route) {
                                popUpTo(tabsNav.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
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
                    isFirstLoad = isFirstLoad,
                    onFirstLoadComplete = onFirstLoadComplete,
                    onGamesUpdated = onGamesUpdated,
                    onOpenReport = onOpenReport,
                    shouldShowDateSelection = shouldShowDateSelection,
                    onDateSelectionShown = onDateSelectionShown,
                    onNavigateToProfile = {
                        tabsNav.navigate(TAB_PROFILE) {
                            popUpTo(TAB_GAMES) { inclusive = false }
                            launchSingleTop = true
                        }
                    },
                    onStartOnboarding = onStartOnboarding,
                    gameToOpen = gameToOpen,
                    onGameOpened = { gameToOpen = null },
                    analyzedGames = analyzedGames
            )
            addStatisticsTab(
                    profile = profile,
                    games = games,
                    analyzedGames = analyzedGames,
                    onBack = {
                        tabsNav.navigate(TAB_GAMES) {
                            popUpTo(TAB_GAMES) { inclusive = false }
                            launchSingleTop = true
                        }
                    },
// ...
                    onLoadGames = {
                        // Navigate to Games tab - the dialog will be triggered there
                        tabsNav.navigate(TAB_GAMES) {
                            popUpTo(TAB_GAMES) { inclusive = false }
                            launchSingleTop = true
                        }
                    },
                    onNavigateToProfile = {
                        tabsNav.navigate(TAB_PROFILE) {
                            popUpTo(TAB_GAMES) { inclusive = false }
                            launchSingleTop = true
                        }
                    },
                    onNavigateToGame = { game ->
                        gameToOpen = game
                        tabsNav.navigate(TAB_GAMES) {
                            popUpTo(TAB_GAMES) { inclusive = false }
                            launchSingleTop = true
                        }
                    }
            )
            addProfileTab(
                    profile = profile,
                    onSave = onSaveProfile,
                    onLogout = onLogout,
                    onBack = {
                        // При нажатии "Назад" в профиле возвращаемся на партии
                        tabsNav.navigate(TAB_GAMES) {
                            popUpTo(TAB_GAMES) { inclusive = false }
                            launchSingleTop = true
                        }
                    },
                    onAdminClick = onAdminClick
            )
        }
    }
}

private data class BottomItem(
        val route: String,
        val labelRes: Int,
        val icon: @Composable () -> Unit
)

private fun NavGraphBuilder.addGamesTab(
        profile: UserProfile,
        games: List<GameHeader>,
        openingFens: Set<String>,
        isFirstLoad: Boolean,
        onFirstLoadComplete: () -> Unit,
        onGamesUpdated: (List<GameHeader>) -> Unit,
        onOpenReport: (FullReport) -> Unit,
        shouldShowDateSelection: Boolean,
        onDateSelectionShown: () -> Unit,
        onNavigateToProfile: () -> Unit,
        onStartOnboarding: () -> Unit,
        gameToOpen: GameHeader?,
        onGameOpened: () -> Unit,
        analyzedGames: MutableMap<String, FullReport>
) {
    composable(TAB_GAMES) {
        GamesListScreen(
                profile = profile,
                games = games,
                openingFens = openingFens,
                isFirstLoad = isFirstLoad,
                onFirstLoadComplete = onFirstLoadComplete,
                onGamesUpdated = onGamesUpdated,
                onOpenReport = onOpenReport,
                shouldShowDateSelection = shouldShowDateSelection,
                onDateSelectionShown = onDateSelectionShown,
                onNavigateToProfile = onNavigateToProfile,
                onStartOnboarding = onStartOnboarding,
                gameToOpen = gameToOpen,
                onGameOpened = onGameOpened,
                analyzedGames = analyzedGames
        )
    }
}

private fun NavGraphBuilder.addStatisticsTab(
        profile: UserProfile,
        games: List<GameHeader>,
        onBack: () -> Unit,
        onLoadGames: () -> Unit,
        onNavigateToProfile: () -> Unit,
        onNavigateToGame: (GameHeader) -> Unit,
        analyzedGames: Map<String, FullReport>
) {
    composable(TAB_STATISTICS) {
        StatisticsScreen(
                profile = profile,
                games = games,
                onBack = onBack,
                onLoadGames = onLoadGames,
                onNavigateToProfile = onNavigateToProfile,
                onNavigateToGame = onNavigateToGame,
                analyzedGames = analyzedGames
        )
    }
}

private fun NavGraphBuilder.addProfileTab(
        profile: UserProfile,
        onSave: (UserProfile) -> Unit,
        onLogout: () -> Unit,
        onBack: () -> Unit,
        onAdminClick: () -> Unit
) {
    composable(TAB_PROFILE) {
        ProfileScreen(
                profile = profile,
                onSave = onSave,
                onLogout = onLogout,
                onBack = onBack,
                onAdminClick = onAdminClick
        )
    }
}
