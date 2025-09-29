package com.example.chessanalysis.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.example.chessanalysis.ui.screens.HomeWithBottomNav
import com.example.chessanalysis.ui.screens.LoginScreen
import com.example.chessanalysis.ui.screens.ProfileScreen
import com.example.chessanalysis.ui.screens.ReportScreen
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class UserProfile(
    val email: String = "",
    val nickname: String = "",
    val lichessUsername: String = "",
    val chessUsername: String = ""
)

@Composable
fun AppRoot() {
    val rootNav = rememberNavController()

    var isBootLoading by rememberSaveable { mutableStateOf(false) }
    var currentUserProfile by rememberSaveable { mutableStateOf<UserProfile?>(null) }
    var games by rememberSaveable { mutableStateOf<List<GameHeader>>(emptyList()) }
    var openingFens by rememberSaveable { mutableStateOf<Set<String>>(emptySet()) }

    val json = remember {
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }
    }

    LaunchedEffect(Unit) { isBootLoading = false }

    if (isBootLoading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    NavHost(
        navController = rootNav,
        startDestination = if (currentUserProfile == null) "login" else "home"
    ) {
        // --- LOGIN ---
        composable("login") {
            LoginScreen(
                onLoginSuccess = { profile ->
                    currentUserProfile = profile
                    rootNav.navigate("home") {
                        popUpTo("login") { inclusive = true }
                    }
                },
                onRegisterSuccess = { profile ->
                    currentUserProfile = profile
                    rootNav.navigate("home") {
                        popUpTo("login") { inclusive = true }
                    }
                }
            )
        }

        // --- HOME c нижним меню (табами "Партии" и "Профиль") ---
        composable("home") {
            val profile = currentUserProfile
            if (profile == null) {
                rootNav.navigate("login") { popUpTo("home") { inclusive = true } }
            } else {
                HomeWithBottomNav(
                    profile = profile,
                    games = games,
                    openingFens = openingFens,
                    onOpenReport = { report ->
                        val packed = json.encodeToString(report)
                        rootNav.currentBackStackEntry
                            ?.savedStateHandle
                            ?.set("reportJson", packed)
                        rootNav.navigate("reportSummary")
                    },
                    onSaveProfile = { updated ->
                        currentUserProfile = updated
                    },
                    onLogout = {
                        currentUserProfile = null
                        games = emptyList()
                        openingFens = emptySet()
                        rootNav.navigate("login") {
                            popUpTo("home") { inclusive = true }
                        }
                    }
                )
            }
        }

        // --- Оставляем отдельный маршрут profile (не используется из UI, но не ломает навигацию) ---
        composable("profile") {
            val profile = currentUserProfile
            if (profile == null) {
                rootNav.popBackStack()
            } else {
                ProfileScreen(
                    profile = profile,
                    onSave = { updated ->
                        currentUserProfile = updated
                        rootNav.popBackStack()
                    },
                    onLogout = {
                        currentUserProfile = null
                        games = emptyList()
                        openingFens = emptySet()
                        rootNav.navigate("login") {
                            popUpTo("home") { inclusive = true }
                        }
                    },
                    onBack = { rootNav.popBackStack() }
                )
            }
        }

        // --- REPORT (summary) ---
        composable("reportSummary") {
            val reportJson = readArg(
                current = rootNav.currentBackStackEntry,
                previous = rootNav.previousBackStackEntry,
                key = "reportJson"
            )
            val report: FullReport? = reportJson?.let {
                runCatching { Json.decodeFromString(FullReport.serializer(), it) }.getOrNull()
            }

            if (report == null) {
                rootNav.popBackStack()
            } else {
                ReportScreen(
                    report = report,
                    onBack = { rootNav.popBackStack() },
                    onOpenBoard = {
                        val packed = json.encodeToString(report)
                        rootNav.currentBackStackEntry
                            ?.savedStateHandle
                            ?.set("reportJson", packed)
                        rootNav.navigate("reportBoard")
                    }
                )
            }
        }

        // --- REPORT (full board) ---
        composable("reportBoard") {
            val reportJson = readArg(
                current = rootNav.currentBackStackEntry,
                previous = rootNav.previousBackStackEntry,
                key = "reportJson"
            )
            val report: FullReport? = reportJson?.let {
                runCatching { Json.decodeFromString(FullReport.serializer(), it) }.getOrNull()
            }

            if (report == null) {
                rootNav.popBackStack()
            } else {
                GameReportScreen(
                    report = report,
                    onBack = { rootNav.popBackStack() }
                )
            }
        }
    }
}

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
