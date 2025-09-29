package com.example.chessanalysis.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import com.example.chessanalysis.ui.navigation.ROUTE_GAMES
import com.example.chessanalysis.ui.navigation.ROUTE_HOME
import com.example.chessanalysis.ui.navigation.ROUTE_STATS

data class BottomItem(
    val route: String,
    val title: String,
    val icon: ImageVector
)

private val bottomItems = listOf(
    BottomItem(route = ROUTE_HOME, title = "Главная", icon = Icons.Default.Home),
    BottomItem(route = ROUTE_GAMES, title = "Партии", icon = Icons.Default.List),
    BottomItem(route = ROUTE_STATS, title = "Статистика", icon = Icons.Default.Assessment),
)

@Composable
fun HomeBottomBar(navController: NavHostController) {
    NavigationBar {
        val navBackStackEntry by navController.currentBackStackEntryAsState()
        val currentDestination = navBackStackEntry?.destination

        bottomItems.forEach { item ->
            NavigationBarItem(
                selected = currentDestination.isInHierarchy(item.route),
                onClick = {
                    navController.navigate(item.route) {
                        launchSingleTop = true
                        restoreState = true
                        popUpTo(navController.graph.startDestinationId) {
                            saveState = true
                        }
                    }
                },
                icon = { Icon(item.icon, contentDescription = item.title) },
                label = { Text(item.title) }
            )
        }
    }
}

private fun NavDestination?.isInHierarchy(route: String): Boolean =
    this?.hierarchy?.any { it.route == route } == true
