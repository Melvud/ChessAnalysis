package com.example.chessanalysis.ui

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.navigation.navDeepLink
import com.example.chessanalysis.*
import com.example.chessanalysis.ui.screens.GameReportScreen
import com.example.chessanalysis.ui.screens.GamesListScreen
import com.example.chessanalysis.ui.screens.LoginScreen
import com.example.chessanalysis.ui.screens.ReportScreen
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.URLDecoder
import java.net.URLEncoder

@Composable
fun AppRoot() {
    val nav = rememberNavController()

    // Простое состояние приложения
    var provider by rememberSaveable { mutableStateOf<Provider?>(null) }
    var username by rememberSaveable { mutableStateOf("") }

    // Предзагруженные данные/кэши (по желанию можно убрать)
    var games by remember { mutableStateOf<List<GameHeader>>(emptyList()) }
    var openingFens by remember { mutableStateOf<Set<String>>(emptySet()) }

    // Единая настройка сериализации
    val json = remember {
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }
    }

    NavHost(navController = nav, startDestination = "login") {

        // ---------- LOGIN ----------
        composable("login") {
            LoginScreen(
                isLoading = false,
                onSubmit = { p, user ->
                    provider = p
                    username = user
                    // после логина идём на список игр
                    nav.navigate("gamesList") {
                        popUpTo("login") { inclusive = true }
                    }
                }
            )
        }

        // ---------- GAMES LIST ----------
        composable("gamesList") {
            val currentProvider = provider ?: Provider.LICHESS
            val currentUser = username

            GamesListScreen(
                provider = currentProvider,
                username = currentUser,
                games = games,
                openingFens = openingFens,
                onBack = { nav.popBackStack() },
                onOpenReport = { report ->
                    // сериализуем полный отчёт в query-параметр
                    val encoded = URLEncoder.encode(json.encodeToString(report), "UTF-8")
                    nav.navigate("reportSummary?report=$encoded")
                }
            )
        }

        // ---------- REPORT (сводка) ----------
        composable(
            route = "reportSummary?report={report}",
            arguments = listOf(
                navArgument("report") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                }
            ),
            deepLinks = listOf(
                navDeepLink { uriPattern = "app://report?report={report}" }
            )
        ) { backStackEntry ->
            val raw = backStackEntry.arguments?.getString("report")
            val decoded = raw?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrElse { it } }
            val report = decoded?.let { runCatching { json.decodeFromString<FullReport>(it as String) }.getOrNull() }
                ?: FullReport.empty()

            ReportScreen(
                report = report,
                onBack = { nav.popBackStack() },
                onOpenBoard = {
                    val encoded = URLEncoder.encode(json.encodeToString(report), "UTF-8")
                    nav.navigate("reportBoard?report=$encoded")
                }
            )
        }

        // ---------- GAME REPORT (доска/плейер) ----------
        composable(
            route = "reportBoard?report={report}",
            arguments = listOf(
                navArgument("report") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                }
            ),
            deepLinks = listOf(
                navDeepLink { uriPattern = "app://game_report?report={report}" }
            )
        ) { backStackEntry ->
            val raw = backStackEntry.arguments?.getString("report")
            val decoded = raw?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrElse { it } }
            val report = decoded?.let { runCatching { json.decodeFromString<FullReport>(it as String) }.getOrNull() }
                ?: FullReport.empty()

            GameReportScreen(
                report = report,
                onBack = { nav.popBackStack() }
            )
        }
    }
}

/**
 * Запасной «пустой» отчёт на случай отсутствия/повреждения данных.
 * Подобран под твою модель FullReport.
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
