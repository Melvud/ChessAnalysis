package com.example.chessanalysis.ui.navigation

import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.example.chessanalysis.data.model.AnalysisResult
import com.example.chessanalysis.data.model.ChessSite
import com.example.chessanalysis.data.model.GameSummary
import com.example.chessanalysis.ui.screens.*
import kotlinx.coroutines.flow.MutableStateFlow

sealed class Screen(val route: String) {
    object Login : Screen("login")
    object GameList : Screen("games/{site}/{username}")
    object Summary : Screen("summary")
    object Report : Screen("report")
}

@Composable
fun AppNavGraph(navController: NavHostController) {
    NavHost(navController, startDestination = Screen.Login.route) {

        composable(Screen.Login.route) {
            LoginScreen { site, username ->
                navController.navigate("games/${site.name}/${username}")
            }
        }

        composable(Screen.GameList.route) { backStackEntry ->
            val siteStr = backStackEntry.arguments?.getString("site")
            val user = backStackEntry.arguments?.getString("username")
            val site = siteStr?.let { runCatching { ChessSite.valueOf(it) }.getOrNull() }
            if (site == null || user.isNullOrBlank()) {
                // Неверные аргументы — безопасно назад
                navController.popBackStack()
                return@composable
            }
            GameListScreen(
                site = site,
                username = user,
                onGameSelected = { game ->
                    // Положим выбранную партию в savedState текущего экрана
                    navController.currentBackStackEntry
                        ?.savedStateHandle
                        ?.set("selectedGame", game)
                    navController.navigate(Screen.Summary.route)
                }
            )
        }

        composable(Screen.Summary.route) {
            // Берём игру из entry предыдущего экрана (GameList)
            val game: GameSummary? = navController.previousBackStackEntry
                ?.savedStateHandle
                ?.get("selectedGame")

            if (game == null) {
                navController.popBackStack()
                return@composable
            }

            AnalysisSummaryScreen(
                game = game,
                onViewReport = { result: AnalysisResult ->
                    // Кладём результат в savedState этой записи стека (Summary)
                    navController.currentBackStackEntry
                        ?.savedStateHandle
                        ?.set("analysisResult", result)
                    navController.navigate(Screen.Report.route)
                },
                onBack = { navController.popBackStack() }
            )
        }

        composable(Screen.Report.route) {
            // КРИТИЧЕСКИЙ ФИКС: читаем данные из конкретного backStackEntry экрана Summary,
            // а не из previousBackStackEntry, который может быть потерян при рекомпозиции.
            val summaryEntry = runCatching {
                navController.getBackStackEntry(Screen.Summary.route)
            }.getOrNull()

            val stateFlow = summaryEntry
                ?.savedStateHandle
                ?.getStateFlow<AnalysisResult?>("analysisResult", null)
                ?: MutableStateFlow(null)

            val result by stateFlow.collectAsState()

            when (val r = result) {
                null -> ReportAwaitScreen(onBack = { navController.popBackStack() })
                else -> ReportScreen(result = r, onBack = { navController.popBackStack() })
            }
        }
    }
}
