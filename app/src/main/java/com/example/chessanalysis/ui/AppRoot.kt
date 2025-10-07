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
import com.example.chessanalysis.ui.screens.HomeWithBottomNav
import com.example.chessanalysis.ui.screens.LoginScreen
import com.example.chessanalysis.ui.screens.ProfileScreen
import com.example.chessanalysis.ui.screens.ReportScreen
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlin.coroutines.resumeWithException

@Composable
fun AppRoot() {
    val rootNav = rememberNavController()

    var isBootLoading by rememberSaveable { mutableStateOf(true) }
    var currentUserProfile by rememberSaveable { mutableStateOf<UserProfile?>(null) }
    var games by rememberSaveable { mutableStateOf<List<GameHeader>>(emptyList()) }
    var openingFens by rememberSaveable { mutableStateOf<Set<String>>(emptySet()) }

    // НОВОЕ: флаг первой загрузки
    var isFirstLoad by rememberSaveable { mutableStateOf(true) }

    val json = remember {
        Json { ignoreUnknownKeys = true; explicitNulls = false }
    }

    // Проверяем сохранённую сессию Firebase при старте
    LaunchedEffect(Unit) {
        runCatching {
            val auth = FirebaseAuth.getInstance()
            val user = auth.currentUser
            if (user != null) {
                val doc = FirebaseFirestore.getInstance()
                    .collection("users")
                    .document(user.uid)
                    .get()
                    .await()

                currentUserProfile = UserProfile(
                    email = user.email ?: "",
                    nickname = doc.getString("nickname") ?: "",
                    lichessUsername = doc.getString("lichessUsername") ?: "",
                    chessUsername = doc.getString("chessUsername") ?: ""
                )
            } else {
                currentUserProfile = null
            }
        }.onFailure { currentUserProfile = null }
        isBootLoading = false
    }

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
                    isFirstLoad = true // Сбрасываем флаг при логине
                    rootNav.navigate("home") { popUpTo("login") { inclusive = true } }
                },
                onRegisterSuccess = { profile ->
                    currentUserProfile = profile
                    isFirstLoad = true // Сбрасываем флаг при регистрации
                    rootNav.navigate("home") { popUpTo("login") { inclusive = true } }
                }
            )
        }

        // --- HOME ---
        composable("home") {
            val profile = currentUserProfile
            if (profile == null) {
                rootNav.navigate("login") { popUpTo("home") { inclusive = true } }
            } else {
                HomeWithBottomNav(
                    profile = profile,
                    games = games,
                    openingFens = openingFens,
                    isFirstLoad = isFirstLoad, // ПЕРЕДАЕМ ФЛАГ
                    onFirstLoadComplete = { isFirstLoad = false }, // КОЛБЭК
                    onOpenReport = { report ->
                        val packed = json.encodeToString(report)
                        rootNav.currentBackStackEntry?.savedStateHandle?.set("reportJson", packed)
                        rootNav.navigate("reportSummary")
                    },
                    onSaveProfile = { updated ->
                        currentUserProfile = updated
                    },
                    onLogout = {
                        FirebaseAuth.getInstance().signOut()
                        currentUserProfile = null
                        games = emptyList()
                        openingFens = emptySet()
                        isFirstLoad = true
                        rootNav.navigate("login") {
                            popUpTo("home") { inclusive = true }
                        }
                    }
                )
            }
        }

        // --- PROFILE ---
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
                        FirebaseAuth.getInstance().signOut()
                        currentUserProfile = null
                        games = emptyList()
                        openingFens = emptySet()
                        isFirstLoad = true
                        rootNav.navigate("login") { popUpTo("home") { inclusive = true } }
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
                        rootNav.currentBackStackEntry?.savedStateHandle?.set("reportJson", packed)
                        rootNav.navigate("reportBoard")
                    }
                )
            }
        }

        // --- REPORT (board) ---
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

// --- утилиты ---

private suspend fun <T> com.google.android.gms.tasks.Task<T>.await(): T {
    return kotlinx.coroutines.suspendCancellableCoroutine { cont ->
        addOnCompleteListener { task ->
            if (task.isSuccessful) cont.resume(task.result, null)
            else cont.resumeWithException(task.exception ?: RuntimeException("Task failed"))
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