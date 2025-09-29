package com.example.chessanalysis.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.List
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.chessanalysis.FullReport
import com.example.chessanalysis.GameHeader
import com.example.chessanalysis.ui.screens.GamesListScreen
import com.example.chessanalysis.ui.screens.ProfileScreen

private const val TAB_GAMES = "tab/games"
private const val TAB_PROFILE = "tab/profile"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeWithBottomNav(
    profile: UserProfile,
    games: List<GameHeader>,
    openingFens: Set<String>,
    onOpenReport: (FullReport) -> Unit,
    onUpdateProfile: (UserProfile) -> Unit,
    onLogout: () -> Unit,
) {
    val tabs = remember {
        listOf(
            BottomItem(route = TAB_GAMES, title = "Партии", icon = { Icon(Icons.Default.List, contentDescription = null) }),
            BottomItem(route = TAB_PROFILE, title = "Профиль", icon = { Icon(Icons.Default.AccountCircle, contentDescription = null) }),
        )
    }
    val tabNav: NavHostController = rememberNavController()
    var selectedRoute by remember { mutableStateOf(TAB_GAMES) }

    Scaffold(
        bottomBar = {
            NavigationBar {
                tabs.forEach { item ->
                    NavigationBarItem(
                        selected = selectedRoute == item.route,
                        onClick = {
                            selectedRoute = item.route
                            if (tabNav.currentBackStackEntry?.destination?.route != item.route) {
                                tabNav.navigate(item.route) {
                                    popUpTo(TAB_GAMES)
                                    launchSingleTop = true
                                }
                            }
                        },
                        icon = item.icon,
                        label = { Text(item.title) }
                    )
                }
            }
        }
    ) { innerPadding ->
        TabsNavHost(
            nav = tabNav,
            contentPadding = innerPadding,
            profile = profile,
            games = games,
            openingFens = openingFens,
            onOpenReport = onOpenReport,
            onUpdateProfile = onUpdateProfile,
            onLogout = onLogout
        )
    }
}

private data class BottomItem(
    val route: String,
    val title: String,
    val icon: @Composable () -> Unit
)

@Composable
private fun TabsNavHost(
    nav: NavHostController,
    contentPadding: PaddingValues,
    profile: UserProfile,
    games: List<GameHeader>,
    openingFens: Set<String>,
    onOpenReport: (FullReport) -> Unit,
    onUpdateProfile: (UserProfile) -> Unit,
    onLogout: () -> Unit
) {
    NavHost(
        navController = nav,
        startDestination = TAB_GAMES,
        modifier = Modifier.fillMaxSize()
    ) {
        composable(TAB_GAMES) {
            GamesListScreen(
                profile = profile,
                games = games,
                openingFens = openingFens,
                onOpenProfile = { nav.navigate(TAB_PROFILE) },
                onOpenReport = onOpenReport
            )
        }
        composable(TAB_PROFILE) {
            ProfileScreen(
                profile = profile,
                onSave = { updated ->
                    onUpdateProfile(updated)
                },
                onLogout = onLogout,
                onBack = { nav.navigate(TAB_GAMES) }
            )
        }
    }
}
