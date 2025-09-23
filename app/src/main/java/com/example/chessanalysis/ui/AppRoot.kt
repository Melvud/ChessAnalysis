package com.example.chessanalysis.ui

import android.widget.Toast
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.chessanalysis.GameHeader
import com.example.chessanalysis.Provider
import com.example.chessanalysis.GameLoaders
import com.example.chessanalysis.FullReport
import com.example.chessanalysis.ui.screens.BoardScreen
import com.example.chessanalysis.ui.screens.GamesListScreen
import com.example.chessanalysis.ui.screens.LoginScreen
import com.example.chessanalysis.ui.screens.ReportScreen
import kotlinx.coroutines.launch

@Composable
fun AppRoot() {
    val nav = rememberNavController()
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current

    var isLoading by remember { mutableStateOf(false) }
    var username by rememberSaveable { mutableStateOf("") }
    var provider by rememberSaveable { mutableStateOf<Provider?>(null) }
    var games by remember { mutableStateOf<List<GameHeader>>(emptyList()) }

    // Текущий отчёт — передаём между экранами без сериализации аргументов
    var currentReport by remember { mutableStateOf<FullReport?>(null) }

    Surface(color = MaterialTheme.colorScheme.background) {
        NavHost(navController = nav, startDestination = "login") {
            composable("login") {
                LoginScreen(
                    isLoading = isLoading,
                    onSubmit = { prov, user ->
                        provider = prov
                        username = user
                        scope.launch {
                            isLoading = true
                            val loaded = try {
                                when (prov) {
                                    Provider.LICHESS -> GameLoaders.loadLichess(user)
                                    Provider.CHESSCOM -> GameLoaders.loadChessCom(user)
                                    else -> emptyList()
                                }
                            } catch (t: Throwable) {
                                Toast.makeText(ctx, t.message ?: "Load error", Toast.LENGTH_SHORT).show()
                                emptyList()
                            } finally {
                                isLoading = false
                            }
                            games = loaded
                            if (games.isEmpty()) {
                                Toast.makeText(ctx, "Не удалось загрузить партии", Toast.LENGTH_SHORT).show()
                            } else {
                                nav.navigate("list")
                            }
                        }
                    }
                )
            }

            composable("list") {
                val prov = provider ?: Provider.LICHESS
                GamesListScreen(
                    provider = prov,
                    username = username,
                    games = games,
                    openingFens = emptySet(), // если у тебя есть множество FEN дебютов — подставь сюда
                    onBack = { nav.popBackStack() },
                    onOpenReport = { report ->
                        currentReport = report
                        nav.navigate("report")
                    }
                )
            }

            composable("report") {
                val report = currentReport
                if (report == null) {
                    Toast.makeText(ctx, "Отчёт не найден", Toast.LENGTH_SHORT).show()
                    nav.popBackStack()
                } else {
                    ReportScreen(
                        report = report,
                        onBack = { nav.popBackStack() },
                        onOpenBoard = { nav.navigate("board") }
                    )
                }
            }

            composable("board") {
                val report = currentReport
                if (report == null) {
                    Toast.makeText(ctx, "Отчёт не найден", Toast.LENGTH_SHORT).show()
                    nav.popBackStack()
                } else {
                    BoardScreen(
                        report = report,
                        onBack = { nav.popBackStack() }
                    )
                }
            }
        }
    }
}
