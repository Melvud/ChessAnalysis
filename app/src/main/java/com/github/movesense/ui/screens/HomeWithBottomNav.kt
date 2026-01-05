package com.github.movesense.ui.screens

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.github.movesense.FullReport
import com.github.movesense.GameHeader
import com.github.movesense.R
import com.github.movesense.ui.UserProfile

private const val TAB_GAMES = "tab/games"
private const val TAB_PROFILE = "tab/profile"

/**
 * Внутренний граф для нижней навигации «Партии»/«Профиль». Снаружи (в AppRoot) остаются маршруты
 * отчётов.
 */
@Composable
fun HomeWithBottomNav(
        profile: UserProfile,
        games: List<GameHeader>,
        openingFens: Set<String>,
        isFirstLoad: Boolean,
        onFirstLoadComplete: () -> Unit,
        onOpenReport: (FullReport) -> Unit,
        onSaveProfile: (UserProfile) -> Unit,
        onLogout: () -> Unit,
        onAdminClick: () -> Unit,
        shouldShowDateSelection: Boolean,
        onDateSelectionShown: () -> Unit
) {
    val tabsNav = rememberNavController()
    val navBackStackEntry by tabsNav.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    val items =
            listOf(
                    BottomItem(
                            TAB_GAMES,
                            R.string.games,
                            { Icon(Icons.Default.List, contentDescription = null) }
                    ),
                    BottomItem(
                            TAB_PROFILE,
                            R.string.profile,
                            { Icon(Icons.Default.Person, contentDescription = null) }
                    )
            )

    val onNavigateToProfile = {
        tabsNav.navigate(TAB_PROFILE) {
            popUpTo(TAB_GAMES) { inclusive = false }
            launchSingleTop = true
        }
    }

    Scaffold(
            bottomBar = {
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
                                label = { Text(stringResource(item.labelRes)) }
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
                    onOpenReport = onOpenReport,
                    shouldShowDateSelection = shouldShowDateSelection,
                    onDateSelectionShown = onDateSelectionShown,
                    onNavigateToProfile = onNavigateToProfile
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
        onOpenReport: (FullReport) -> Unit,
        shouldShowDateSelection: Boolean,
        onDateSelectionShown: () -> Unit,
        onNavigateToProfile: () -> Unit
) {
    composable(TAB_GAMES) {
        GamesListScreen(
                profile = profile,
                games = games,
                openingFens = openingFens,
                isFirstLoad = isFirstLoad,
                onFirstLoadComplete = onFirstLoadComplete,
                onOpenReport = onOpenReport,
                shouldShowDateSelection = shouldShowDateSelection,
                onDateSelectionShown = onDateSelectionShown,
                onNavigateToProfile = onNavigateToProfile
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
