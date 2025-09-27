package com.example.chessanalysis.ui

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.chessanalysis.*
import com.example.chessanalysis.ui.screens.GameReportScreen
import com.example.chessanalysis.ui.screens.GamesListScreen
import com.example.chessanalysis.ui.screens.LoginScreen
import com.example.chessanalysis.ui.screens.ReportScreen
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/**
 * Корневой навигационный граф приложения.
 * Отчёт хранится в SavedStateHandle как JSON-строка, чтобы избежать ошибок сериализации.
 */
@Composable
fun AppRoot() {
    val navController = rememberNavController()

    // выбранный провайдер и имя пользователя
    var provider by rememberSaveable { mutableStateOf<Provider?>(null) }
    var username by rememberSaveable { mutableStateOf("") }

    // загруженные партии и FEN-ы (опционально)
    var games by remember { mutableStateOf<List<GameHeader>>(emptyList()) }
    var openingFens by remember { mutableStateOf<Set<String>>(emptySet()) }

    // общий JSON-конвертер
    val json = remember {
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }
    }

    NavHost(navController = navController, startDestination = "login") {
        composable("login") {
            LoginScreen(
                isLoading = false,
                onSubmit = { p, user ->
                    provider = p
                    username = user
                    navController.navigate("gamesList") {
                        popUpTo("login") { inclusive = true }
                    }
                }
            )
        }

        composable("gamesList") {
            val currentProvider = provider ?: Provider.LICHESS
            val currentUser = username

            GamesListScreen(
                provider = currentProvider,
                username = currentUser,
                games = games,
                openingFens = openingFens,
                onBack = { navController.popBackStack() },
                onOpenReport = { report ->
                    // сериализуем отчёт в JSON и кладём в savedState
                    val reportJson = json.encodeToString(report)
                    navController.currentBackStackEntry
                        ?.savedStateHandle
                        ?.set("reportJson", reportJson)
                    navController.navigate("reportSummary")
                }
            )
        }

        composable("reportSummary") {
            val reportJson =
                navController.previousBackStackEntry
                    ?.savedStateHandle
                    ?.get<String>("reportJson")
            val report: FullReport? = reportJson?.let { runCatching { json.decodeFromString<FullReport>(it) }.getOrNull() }

            if (report == null) {
                navController.popBackStack()
            } else {
                ReportScreen(
                    report = report,
                    onBack = { navController.popBackStack() },
                    onOpenBoard = {
                        // кладём JSON в текущую запись стека, переходим на доску
                        navController.currentBackStackEntry
                            ?.savedStateHandle
                            ?.set("reportJson", reportJson)
                        navController.navigate("reportBoard")
                    }
                )
            }
        }

        composable("reportBoard") {
            val reportJson =
                navController.previousBackStackEntry
                    ?.savedStateHandle
                    ?.get<String>("reportJson")
            val report: FullReport? = reportJson?.let { runCatching { json.decodeFromString<FullReport>(it) }.getOrNull() }

            if (report == null) {
                navController.popBackStack()
            } else {
                GameReportScreen(
                    report = report,
                    onBack = { navController.popBackStack() }
                )
            }
        }
    }
}

/**
 * Запасной «пустой» отчёт на случай отсутствия/повреждения данных.
 */
private fun FullReport.Companion.empty(): FullReport =
    FullReport(
        header = GameHeader(),
        positions = emptyList(),
        moves = emptyList(),
        accuracy = AccuracySummary(
            whiteMovesAcc = AccByColor(0.0, 0.0, 0.0),
            blackMovesAcc = AccByColor(0.0, 0.0, 0.0)
        ),
        acpl = Acpl(white = 0, black = 0),
        estimatedElo = EstimatedElo(),
        analysisLog = emptyList()
    )
