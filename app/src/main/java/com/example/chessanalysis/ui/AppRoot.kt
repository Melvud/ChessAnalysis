package com.example.chessanalysis.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.navigation.NavBackStackEntry
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.chessanalysis.FullReport
import com.example.chessanalysis.GameHeader
import com.example.chessanalysis.ui.screens.GameReportScreen
import com.example.chessanalysis.ui.screens.GamesListScreen
import com.example.chessanalysis.ui.screens.LoginScreen
import com.example.chessanalysis.ui.screens.ProfileScreen
import com.example.chessanalysis.ui.screens.ReportScreen
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Профиль пользователя — как он используется твоими экранами.
 * Вынес здесь, чтобы GamesListScreen/ProfileScreen могли его импортировать
 * как com.example.chessanalysis.ui.UserProfile (как у тебя было раньше).
 */
@Serializable
data class UserProfile(
    val email: String = "",
    val nickname: String = "",
    val lichessUsername: String = "",
    val chessUsername: String = ""
)

@Composable
fun AppRoot() {
    val navController = rememberNavController()

    // Состояния приложения
    var isBootLoading by rememberSaveable { mutableStateOf(false) }
    var currentUserProfile by rememberSaveable { mutableStateOf<UserProfile?>(null) }
    var games by rememberSaveable { mutableStateOf<List<GameHeader>>(emptyList()) }
    var openingFens by rememberSaveable { mutableStateOf<Set<String>>(emptySet()) }

    val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    // Если нужно что-то асинхронно прогреть — можно показать сплэш.
    LaunchedEffect(Unit) {
        isBootLoading = false
    }

    if (isBootLoading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    NavHost(
        navController = navController,
        startDestination = if (currentUserProfile == null) "login" else "home"
    ) {
        // --------- LOGIN ----------
        composable("login") {
            LoginScreen(
                onLoginSuccess = { profile ->
                    currentUserProfile = profile
                    navController.navigate("home") {
                        popUpTo("login") { inclusive = true }
                    }
                },
                onRegisterSuccess = { profile ->
                    currentUserProfile = profile
                    navController.navigate("home") {
                        popUpTo("login") { inclusive = true }
                    }
                }
            )
        }

        // --------- HOME (нижняя навигация внутри твоего экрана) ----------
        composable("home") {
            val profile = currentUserProfile
            if (profile == null) {
                navController.navigate("login") { popUpTo("home") { inclusive = true } }
            } else {
                /**
                 * Твой GamesListScreen ожидает:
                 * - profile: UserProfile
                 * - games: List<GameHeader>
                 * - openingFens: Set<String>
                 * - onOpenProfile: () -> Unit
                 * - onOpenReport: (FullReport) -> Unit
                 *
                 * Если GamesListScreen у тебя внутри HomeWithBottomNav — оставь как есть.
                 * Здесь мы просто вызываем его напрямую, чтобы не тянуть вкладку «бот».
                 */
                GamesListScreen(
                    profile = profile,
                    games = games,
                    openingFens = openingFens,
                    onOpenProfile = { navController.navigate("profile") },
                    onOpenReport = { report ->
                        // сериализуем отчёт и передаём через savedStateHandle
                        val packed = json.encodeToString(report)
                        navController.currentBackStackEntry
                            ?.savedStateHandle
                            ?.set("reportJson", packed)
                        navController.navigate("reportSummary")
                    }
                )
            }
        }

        // --------- PROFILE ----------
        composable("profile") {
            val profile = currentUserProfile
            if (profile == null) {
                navController.popBackStack()
            } else {
                ProfileScreen(
                    profile = profile,
                    onSave = { updated ->
                        currentUserProfile = updated
                        navController.popBackStack()
                    },
                    onLogout = {
                        currentUserProfile = null
                        games = emptyList()
                        openingFens = emptySet()
                        navController.navigate("login") {
                            popUpTo("home") { inclusive = true }
                        }
                    },
                    onBack = { navController.popBackStack() }
                )
            }
        }

        // --------- REPORT (summary) ----------
        composable("reportSummary") {
            val reportJson = readArg(
                current = navController.currentBackStackEntry,
                previous = navController.previousBackStackEntry,
                key = "reportJson"
            )
            val report: FullReport? = reportJson?.let {
                runCatching { json.decodeFromString(FullReport.serializer(), it) }.getOrNull()
            }

            if (report == null) {
                navController.popBackStack()
            } else {
                // Твой ReportScreen ожидает onOpenBoard, а НЕ onOpenFull.
                ReportScreen(
                    report = report,
                    onBack = { navController.popBackStack() },
                    onOpenBoard = {
                        // На детальный отчёт: ещё раз кладём JSON и открываем "reportBoard"
                        val packed = json.encodeToString(report)
                        navController.currentBackStackEntry
                            ?.savedStateHandle
                            ?.set("reportJson", packed)
                        navController.navigate("reportBoard")
                    }
                )
            }
        }

        // --------- REPORT (board / full) ----------
        composable("reportBoard") {
            val reportJson = readArg(
                current = navController.currentBackStackEntry,
                previous = navController.previousBackStackEntry,
                key = "reportJson"
            )
            val report: FullReport? = reportJson?.let {
                runCatching { json.decodeFromString(FullReport.serializer(), it) }.getOrNull()
            }

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
 * Устойчивое чтение аргумента из SavedStateHandle:
 * 1) из текущего, 2) из предыдущего back stack entry.
 */
private fun readArg(
    current: NavBackStackEntry?,
    previous: NavBackStackEntry?,
    key: String
): String? {
    val fromCurrent = current?.savedStateHandle?.get<String>(key)
    if (!fromCurrent.isNullOrEmpty()) return fromCurrent
    val fromPrev = previous?.savedStateHandle?.get<String>(key)
    if (!fromPrev.isNullOrEmpty()) return fromPrev
    return null
}
