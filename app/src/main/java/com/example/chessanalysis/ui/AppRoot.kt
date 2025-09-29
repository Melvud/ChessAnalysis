package com.example.chessanalysis.ui

import androidx.compose.ui.platform.LocalContext
import com.google.firebase.FirebaseApp
import androidx.compose.foundation.layout.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.navigation.NavBackStackEntry
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.chessanalysis.*
import com.example.chessanalysis.ui.screens.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.example.chessanalysis.ui.screens.bot.BotConfig

/**
 * Простое временное хранилище для передачи больших объектов
 * между экранами, чтобы не упираться в savedStateHandle-ограничения.
 */
private object NavTempStore {
    var reportJson: String? = null
}

@Serializable
data class UserProfile(
    val email: String = "",
    val nickname: String = "",
    val lichessUsername: String = "",
    val chessUsername: String = ""
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot() {
    val navController = rememberNavController()

    var currentUserProfile by rememberSaveable { mutableStateOf<UserProfile?>(null) }
    var games by remember { mutableStateOf<List<GameHeader>>(emptyList()) }
    var openingFens by remember { mutableStateOf<Set<String>>(emptySet()) }
    val context = LocalContext.current
    var authChecked by remember { mutableStateOf(false) }

    val json = remember {
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }
    }

    LaunchedEffect(Unit) {
        if (FirebaseApp.getApps(context).isEmpty()) {
            FirebaseApp.initializeApp(context.applicationContext)
        }
        val firebaseUser = FirebaseAuth.getInstance().currentUser
        if (firebaseUser != null) {
            FirebaseFirestore.getInstance().collection("users")
                .document(firebaseUser.uid).get()
                .addOnSuccessListener { doc ->
                    val email = firebaseUser.email ?: ""
                    val nickname = doc.getString("nickname") ?: ""
                    val lichessName = doc.getString("lichessUsername") ?: ""
                    val chessName = doc.getString("chessUsername") ?: ""
                    currentUserProfile = UserProfile(email, nickname, lichessName, chessName)
                    authChecked = true
                }
                .addOnFailureListener {
                    FirebaseAuth.getInstance().signOut()
                    currentUserProfile = null
                    authChecked = true
                }
        } else {
            authChecked = true
        }
    }

    if (!authChecked) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    NavHost(
        navController = navController,
        startDestination = if (currentUserProfile != null) "home" else "login"
    ) {
        composable("login") {
            LoginScreen(
                onLoginSuccess = { profile ->
                    currentUserProfile = profile
                    navController.navigate("home") { popUpTo("login") { inclusive = true } }
                },
                onRegisterSuccess = { profile ->
                    currentUserProfile = profile
                    navController.navigate("home") { popUpTo("login") { inclusive = true } }
                }
            )
        }

        composable("home") {
            currentUserProfile?.let { profile ->
                HomeWithBottomNav(
                    profile = profile,
                    games = games,
                    openingFens = openingFens,
                    onOpenReport = { report ->
                        val reportJson = json.encodeToString(report)
                        // Дублируем: и в savedStateHandle, и в временное хранилище
                        navController.currentBackStackEntry
                            ?.savedStateHandle
                            ?.set("reportJson", reportJson)
                        NavTempStore.reportJson = reportJson

                        navController.navigate("reportSummary")
                    },
                    onOpenProfileEdit = { navController.navigate("profile") },
                    onOpenBotSetup = { navController.navigate("botSetup") }
                )
            }
        }

        composable("profile") {
            currentUserProfile?.let { profile ->
                ProfileScreen(
                    profile = profile,
                    onSave = { updatedProfile ->
                        currentUserProfile = updatedProfile
                        games = emptyList()
                        navController.popBackStack()
                    },
                    onLogout = {
                        FirebaseAuth.getInstance().signOut()
                        currentUserProfile = null
                        games = emptyList()
                        navController.navigate("login") { popUpTo("home") { inclusive = true } }
                    },
                    onBack = { navController.popBackStack() }
                )
            }
        }

        composable("botSetup") {
            BotGameScreen { cfg ->
                val cfgJson = json.encodeToString(cfg)
                navController.currentBackStackEntry?.savedStateHandle?.set("bot_cfg", cfgJson)
                navController.navigate("botPlay")
            }
        }

        composable("botPlay") {
            val cfgJson = navController.previousBackStackEntry?.savedStateHandle?.get<String>("bot_cfg")
            val cfg: BotConfig? = cfgJson?.let { runCatching { json.decodeFromString<BotConfig>(it) }.getOrNull() }
            if (cfg == null) {
                navController.popBackStack()
            } else {
                BotPlayScreen(
                    config = cfg,
                    onBack = { navController.popBackStack() },

                    // Сохраняем игру и показываем отчёт ТОЛЬКО при успехе анализа
                    onFinish = { finishResult ->
                        // finishResult.report гарантируется BotPlayScreen-ом при успехе
                        BotGamesLocal.append(context, finishResult.stored)
                        val reportJson = json.encodeToString(finishResult.report)
                        navController.currentBackStackEntry?.savedStateHandle?.set("reportJson", reportJson)
                        NavTempStore.reportJson = reportJson
                        navController.navigate("reportSummary")
                    }
                )
            }
        }

        // Краткий отчёт
        composable("reportSummary") {
            val reportJson = readArg(
                navController.currentBackStackEntry,
                navController.previousBackStackEntry,
                key = "reportJson",
                fallback = { NavTempStore.reportJson }
            )

            val report: FullReport? = reportJson?.let {
                runCatching { json.decodeFromString<FullReport>(it) }.getOrNull()
            }
            if (report == null) {
                navController.popBackStack()
            } else {
                ReportScreen(
                    report = report,
                    onBack = { navController.popBackStack("home", inclusive = false) },
                    onOpenBoard = {
                        // Переход к детальному борду
                        navController.currentBackStackEntry?.savedStateHandle?.set("reportJson", reportJson)
                        NavTempStore.reportJson = reportJson
                        navController.navigate("reportBoard")
                    }
                )
            }
        }

        // Полный отчёт (борд)
        composable("reportBoard") {
            val reportJson = readArg(
                navController.currentBackStackEntry,
                navController.previousBackStackEntry,
                key = "reportJson",
                fallback = { NavTempStore.reportJson }
            )

            val report: FullReport? = reportJson?.let {
                runCatching { json.decodeFromString<FullReport>(it) }.getOrNull()
            }
            if (report == null) navController.popBackStack() else {
                GameReportScreen(report = report, onBack = { navController.popBackStack() })
            }
        }
    }
}

/**
 * Надёжное чтение аргумента: current -> previous -> fallback
 */
private fun readArg(
    current: NavBackStackEntry?,
    previous: NavBackStackEntry?,
    key: String,
    fallback: () -> String?
): String? {
    val fromCurrent = current?.savedStateHandle?.get<String>(key)
    if (!fromCurrent.isNullOrEmpty()) return fromCurrent

    val fromPrev = previous?.savedStateHandle?.get<String>(key)
    if (!fromPrev.isNullOrEmpty()) return fromPrev

    return fallback()
}
