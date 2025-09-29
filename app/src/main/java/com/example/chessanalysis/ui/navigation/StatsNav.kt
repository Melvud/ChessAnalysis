package com.example.chessanalysis.ui.navigation

import androidx.navigation.NavHostController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.example.chessanalysis.data.repo.StatsRepository
import com.example.chessanalysis.ui.screens.StatsScreen

// Маршруты вашего приложения (подставьте свои, если уже есть)
const val ROUTE_HOME = "home"
const val ROUTE_GAMES = "games"
const val ROUTE_REPORT = "report"
const val ROUTE_STATS = "stats"

fun NavGraphBuilder.registerStatsScreen(
    repositoryProvider: () -> StatsRepository,
    navController: NavHostController? = null,
) {
    composable(ROUTE_STATS) {
        val repo = repositoryProvider()
        StatsScreen(
            repository = repo,
            onBack = { navController?.popBackStack() }
        )
    }
}
