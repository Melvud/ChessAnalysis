package com.github.movesense.ui

import android.content.Context
import androidx.activity.ComponentActivity
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
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavBackStackEntry
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.github.movesense.FullReport
import com.github.movesense.GameHeader
import com.github.movesense.ui.screens.GameReportScreen
import com.github.movesense.ui.screens.HomeWithBottomNav
import com.github.movesense.ui.screens.LoginScreen
import com.github.movesense.ui.screens.ProfileScreen
import com.github.movesense.ui.screens.ReportScreen
import com.github.movesense.util.LocaleManager
import com.google.android.gms.tasks.Task
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@Composable
fun AppRoot() {
    val rootNav = rememberNavController()
    val context = LocalContext.current

    var isBootLoading by rememberSaveable { mutableStateOf(true) }
    var currentUserProfile by rememberSaveable { mutableStateOf<UserProfile?>(null) }
    var games by rememberSaveable { mutableStateOf<List<GameHeader>>(emptyList()) }
    var openingFens by rememberSaveable { mutableStateOf<Set<String>>(emptySet()) }
    var isFirstLoad by rememberSaveable { mutableStateOf(true) }

    val json = remember {
        Json { ignoreUnknownKeys = true; explicitNulls = false }
    }

    // В LaunchedEffect при загрузке профиля:
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

                val languageCode = doc.getString("language")

                if (!languageCode.isNullOrBlank()) {
                    val savedLanguageCode = LocaleManager.getSavedLanguageCode(context)

                    // ✅ Применяем язык только если он изменился
                    if (savedLanguageCode != languageCode) {
                        val language = LocaleManager.Language.fromCode(languageCode)
                        LocaleManager.setLocale(context as ComponentActivity, language)
                        return@runCatching // Activity перезапустится автоматически
                    }
                }

                currentUserProfile = UserProfile(
                    email = user.email ?: "",
                    nickname = doc.getString("nickname") ?: "",
                    lichessUsername = doc.getString("lichessUsername") ?: "",
                    chessUsername = doc.getString("chessUsername") ?: "",
                    language = languageCode ?: LocaleManager.Language.ENGLISH.code,
                    isPremium = doc.getBoolean("isPremium") ?: false
                )
            } else {
                currentUserProfile = null

                val savedLanguageCode = LocaleManager.getSavedLanguageCode(context)
                if (savedLanguageCode == null) {
                    LocaleManager.setLocale(context as ComponentActivity, LocaleManager.Language.ENGLISH)
                }
            }
        }.onFailure {
            currentUserProfile = null

            val savedLanguageCode = LocaleManager.getSavedLanguageCode(context)
            if (savedLanguageCode == null) {
                LocaleManager.setLocale(context as ComponentActivity, LocaleManager.Language.ENGLISH)
            }
        }

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
                    isFirstLoad = true
                    rootNav.navigate("home") {
                        popUpTo("login") { inclusive = true }
                    }
                },
                onRegisterSuccess = { profile ->
                    currentUserProfile = profile
                    isFirstLoad = true
                    rootNav.navigate("home") {
                        popUpTo("login") { inclusive = true }
                    }
                }
            )
        }

        // --- HOME ---
        composable("home") {
            val profile = currentUserProfile
            if (profile == null) {
                rootNav.navigate("login") {
                    popUpTo("home") { inclusive = true }
                }
            } else {
                HomeWithBottomNav(
                    profile = profile,
                    games = games,
                    openingFens = openingFens,
                    isFirstLoad = isFirstLoad,
                    onFirstLoadComplete = { isFirstLoad = false },
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
                        // ✅ НЕ вызываем popBackStack здесь - Activity уже перезапустится
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
                runCatching {
                    Json.decodeFromString(FullReport.serializer(), it)
                }.getOrNull()
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
                runCatching {
                    Json.decodeFromString(FullReport.serializer(), it)
                }.getOrNull()
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

// --- Утилиты ---

private suspend fun <T> Task<T>.await(): T {
    return suspendCancellableCoroutine { cont ->
        addOnCompleteListener { task ->
            if (task.isSuccessful) {
                cont.resume(task.result, null)
            } else {
                cont.resumeWithException(
                    task.exception ?: RuntimeException("Task failed")
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