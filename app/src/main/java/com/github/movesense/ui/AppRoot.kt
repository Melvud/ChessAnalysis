package com.github.movesense.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavBackStackEntry
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.github.movesense.FullReport
import com.github.movesense.GameHeader
import com.github.movesense.R
import com.github.movesense.data.local.GuestPreferences
import com.github.movesense.ui.screens.GameReportScreen
import com.github.movesense.ui.screens.HomeWithBottomNav
import com.github.movesense.ui.screens.LoginScreen
import com.github.movesense.ui.screens.OnboardingScreen
import com.github.movesense.ui.screens.ProfileScreen
import com.github.movesense.ui.screens.ReportScreen
import com.github.movesense.ui.screens.admin.AdminPanelScreen
import com.github.movesense.util.LocaleManager
import com.google.android.gms.tasks.Task
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Composable
fun AppRoot() {
    val rootNav = rememberNavController()
    val context = LocalContext.current

    var isBootLoading by rememberSaveable { mutableStateOf(true) }
    var currentUserProfile by rememberSaveable { mutableStateOf<UserProfile?>(null) }
    var games by rememberSaveable { mutableStateOf<List<GameHeader>>(emptyList()) }
    var openingFens by rememberSaveable { mutableStateOf<Set<String>>(emptySet()) }
    var isFirstLoad by rememberSaveable { mutableStateOf(true) }
    var giftNotification by rememberSaveable { mutableStateOf<String?>(null) }
    var shouldShowDateSelection by rememberSaveable { mutableStateOf(false) }

    val json = remember {
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }
    }



    // Better implementation using DisposableEffect for the listener
    DisposableEffect(Unit) {
        val auth = FirebaseAuth.getInstance()
        var registration: com.google.firebase.firestore.ListenerRegistration? = null

        val authListener =
                FirebaseAuth.AuthStateListener { firebaseAuth ->
                    val user = firebaseAuth.currentUser

                    // Remove existing registration when user changes (or logs out)
                    registration?.remove()
                    registration = null

                    if (user != null) {
                        if (user.isAnonymous) {
                            // 🌟 GUEST MODE
                            val lichess = com.github.movesense.data.local.GuestPreferences.getLichessUsername(context)
                            val chess = com.github.movesense.data.local.GuestPreferences.getChessUsername(context)
                            val lang = com.github.movesense.data.local.GuestPreferences.getLanguage(context) 
                                ?: LocaleManager.getSavedLanguageCode(context) 
                                ?: LocaleManager.Language.ENGLISH.code

                            currentUserProfile = UserProfile(
                                email = "",
                                lichessUsername = lichess,
                                chessUsername = chess,
                                language = lang,
                                isPremium = false,
                                premiumUntil = -1L,
                                isAdmin = false,
                                isGuest = true
                            )
                            isBootLoading = false
                        } else {
                            // 🌟 REGISTERED USER (Firestore)
                            val docRef =
                                    FirebaseFirestore.getInstance()
                                            .collection("users")
                                            .document(user.uid)

                            registration =
                                    docRef.addSnapshotListener { snapshot, e ->
                                        if (e != null) return@addSnapshotListener

                                        if (snapshot != null && snapshot.exists()) {
                                            val languageCode = snapshot.getString("language")

                                            if (!languageCode.isNullOrBlank()) {
                                                val savedLanguageCode =
                                                        LocaleManager.getSavedLanguageCode(context)
                                                if (savedLanguageCode != languageCode) {
                                                    // val language =
                                                    //         LocaleManager.Language.fromCode(
                                                    //                 languageCode
                                                    //         )
                                                    // LocaleManager.setLocale(
                                                    //         context as ComponentActivity,
                                                    //         language
                                                    // )
                                                    // DISABLED: This causes restart loops/reverts during login.
                                                }
                                            }

                                            val isPremium = snapshot.getBoolean("isPremium") ?: false
                                            val premiumUntil = snapshot.getLong("premiumUntil") ?: -1L
                                            var finalIsPremium = isPremium

                                            if (isPremium &&
                                                            premiumUntil != -1L &&
                                                            System.currentTimeMillis() > premiumUntil
                                            ) {
                                                docRef.update("isPremium", false)
                                                finalIsPremium = false
                                            }

                                            val wasPremium = currentUserProfile?.isPremium == true

                                            val newProfile =
                                                    UserProfile(
                                                            email = user.email ?: "",

                                                            lichessUsername =
                                                                    snapshot.getString(
                                                                            "lichessUsername"
                                                                    )
                                                                            ?: "",
                                                            chessUsername =
                                                                    snapshot.getString("chessUsername")
                                                                            ?: "",
                                                            language = languageCode
                                                                            ?: LocaleManager.Language
                                                                                    .ENGLISH
                                                                                    .code,
                                                            isPremium = finalIsPremium,
                                                            premiumUntil = premiumUntil,
                                                            isAdmin = snapshot.getBoolean("isAdmin")
                                                                            ?: false,
                                                            isGuest = false
                                                    )

                                            currentUserProfile = newProfile
                                            isBootLoading = false

                                            // Auto-switch logic
                                            if (finalIsPremium && !wasPremium) {
                                                if (com.github.movesense.EngineClient.engineMode
                                                                .value ==
                                                                com.github.movesense.EngineClient
                                                                        .EngineMode.LOCAL
                                                ) {
                                                    android.os.Handler(
                                                                    android.os.Looper.getMainLooper()
                                                            )
                                                            .post {
                                                                kotlinx.coroutines.GlobalScope.launch(
                                                                        kotlinx.coroutines.Dispatchers
                                                                                .Main
                                                                ) {
                                                                    com.github.movesense.EngineClient
                                                                            .setEngineMode(
                                                                                    com.github.movesense
                                                                                            .EngineClient
                                                                                            .EngineMode
                                                                                            .SERVER
                                                                            )
                                                                    android.widget.Toast.makeText(
                                                                                    context,
                                                                                    context.getString(
                                                                                            R.string
                                                                                                    .switched_to_server_mode
                                                                                    ),
                                                                                    android.widget.Toast
                                                                                            .LENGTH_LONG
                                                                            )
                                                                            .show()
                                                                }
                                                            }
                                                }
                                            }

                                            val gift = snapshot.getString("giftNotification")
                                            if (!gift.isNullOrBlank()) {
                                                giftNotification = gift
                                                docRef.update("giftNotification", null)
                                            }
                                        } else {
                                            currentUserProfile = null
                                            isBootLoading = false
                                        }
                                    }
                        }
                    } else {
                        // Check if we should auto-login as guest
                        val isGuestActive = com.github.movesense.data.local.GuestPreferences.isGuestActive(context)
                        if (isGuestActive) {
                            // Try to restore guest session
                            FirebaseAuth.getInstance().signInAnonymously().addOnCompleteListener { task ->
                                if (!task.isSuccessful) {
                                    android.widget.Toast.makeText(context, "Guest login failed: ${task.exception?.message}", android.widget.Toast.LENGTH_LONG).show()
                                    // Failed to restore, fallback to Welcome
                                    currentUserProfile = null
                                    isBootLoading = false
                                    val savedLanguageCode = LocaleManager.getSavedLanguageCode(context)
                                    if (savedLanguageCode == null) {
                                        LocaleManager.setLocale(
                                                context as ComponentActivity,
                                                LocaleManager.Language.ENGLISH
                                        )
                                    }
                                }
                                // If successful, the listener will fire again with user != null
                            }
                            // Keep isBootLoading = true
                            return@AuthStateListener
                        }

                        currentUserProfile = null
                        isBootLoading = false
                        val savedLanguageCode = LocaleManager.getSavedLanguageCode(context)
                        if (savedLanguageCode == null) {
                            LocaleManager.setLocale(
                                    context as ComponentActivity,
                                    LocaleManager.Language.ENGLISH
                            )
                        }
                    }
                }

        auth.addAuthStateListener(authListener)

        onDispose {
            auth.removeAuthStateListener(authListener)
            registration?.remove()
        }
    }

    if (isBootLoading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    if (giftNotification != null) {
        AlertDialog(
                onDismissRequest = { giftNotification = null },
                title = { Text("Подарок!") },
                text = { Text(giftNotification!!) },
                confirmButton = { TextButton(onClick = { giftNotification = null }) { Text("OK") } }
        )
    }

    NavHost(
            navController = rootNav,
            startDestination = if (currentUserProfile == null) "welcome" else {
                if (!GuestPreferences.isOnboardingShown(context)) "onboarding" else "home"
            }
    ) {
        // --- ONBOARDING ---
        composable("onboarding") {
            OnboardingScreen(
                onFinish = {
                    GuestPreferences.setOnboardingShown(context, true)
                    rootNav.navigate("home") { popUpTo("onboarding") { inclusive = true } }
                }
            )
        }

        // --- WELCOME ---
        composable("welcome") {
            com.github.movesense.ui.screens.WelcomeScreen(
                    onNavigateToLogin = { isLogin -> rootNav.navigate("login/$isLogin") },
                    onLoginSuccess = { profile ->
                        currentUserProfile = profile
                        isFirstLoad = true
                        shouldShowDateSelection = true
                        if (!GuestPreferences.isOnboardingShown(context)) {
                            rootNav.navigate("onboarding") { popUpTo("welcome") { inclusive = true } }
                        } else {
                            rootNav.navigate("home") { popUpTo("welcome") { inclusive = true } }
                        }
                    }
            )
        }

        // --- LOGIN ---
        composable(
                route = "login/{isLogin}",
                arguments =
                        listOf(
                                androidx.navigation.navArgument("isLogin") {
                                    type = androidx.navigation.NavType.BoolType
                                }
                        )
        ) { backStackEntry ->
            val isLogin = backStackEntry.arguments?.getBoolean("isLogin") ?: true
            LoginScreen(
                    initialLoginMode = isLogin,
                    onLoginSuccess = { profile ->
                        currentUserProfile = profile
                        isFirstLoad = true
                        shouldShowDateSelection = true
                        if (!GuestPreferences.isOnboardingShown(context)) {
                            rootNav.navigate("onboarding") { popUpTo("welcome") { inclusive = true } }
                        } else {
                            rootNav.navigate("home") { popUpTo("welcome") { inclusive = true } }
                        }
                    },
                    onRegisterSuccess = { profile ->
                        currentUserProfile = profile
                        isFirstLoad = true
                        shouldShowDateSelection = true
                        if (!GuestPreferences.isOnboardingShown(context)) {
                            rootNav.navigate("onboarding") { popUpTo("welcome") { inclusive = true } }
                        } else {
                            rootNav.navigate("home") { popUpTo("welcome") { inclusive = true } }
                        }
                    }
            )
        }

        // --- HOME ---
        composable("home") {
            val profile = currentUserProfile
            if (profile == null) {
                rootNav.navigate("login/true") { popUpTo("home") { inclusive = true } }
            } else {
                HomeWithBottomNav(
                        profile = profile,
                        games = games,
                        openingFens = openingFens,
                        isFirstLoad = isFirstLoad,
                        onFirstLoadComplete = { isFirstLoad = false },
                        onOpenReport = { report ->
                            val packed = json.encodeToString(report)
                            rootNav.currentBackStackEntry?.savedStateHandle?.set(
                                    "reportJson",
                                    packed
                            )
                            rootNav.navigate("reportSummary")
                        },
                        onSaveProfile = { updated -> currentUserProfile = updated },
                        onLogout = {
                            FirebaseAuth.getInstance().signOut()
                            com.github.movesense.data.local.GuestPreferences.setGuestActive(context, false)
                            currentUserProfile = null
                            games = emptyList()
                            openingFens = emptySet()
                            isFirstLoad = true
                            rootNav.navigate("login/true") { popUpTo("home") { inclusive = true } }
                        },
                        onAdminClick = { rootNav.navigate("admin_panel") },
                        shouldShowDateSelection = shouldShowDateSelection,
                        onDateSelectionShown = { shouldShowDateSelection = false },
                        onStartOnboarding = { rootNav.navigate("onboarding") }
                )

                if (giftNotification != null) {
                    AlertDialog(
                            onDismissRequest = {
                                // Clear notification
                                val user = FirebaseAuth.getInstance().currentUser
                                if (user != null) {
                                    FirebaseFirestore.getInstance()
                                            .collection("users")
                                            .document(user.uid)
                                            .update("giftNotification", null)
                                }
                                giftNotification = null
                            },
                            title = { Text(stringResource(R.string.gift_dialog_title)) },
                            text = { Text(giftNotification!!) },
                            confirmButton = {
                                TextButton(
                                        onClick = {
                                            val user = FirebaseAuth.getInstance().currentUser
                                            if (user != null) {
                                                FirebaseFirestore.getInstance()
                                                        .collection("users")
                                                        .document(user.uid)
                                                        .update("giftNotification", null)
                                            }
                                            giftNotification = null
                                        }
                                ) { Text(stringResource(R.string.ok)) }
                            }
                    )
                }
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
                            com.github.movesense.data.local.GuestPreferences.setGuestActive(context, false)
                            currentUserProfile = null
                            games = emptyList()
                            openingFens = emptySet()
                            isFirstLoad = true
                            rootNav.navigate("login/true") { popUpTo("home") { inclusive = true } }
                        },
                        onBack = { rootNav.popBackStack() },
                        onAdminClick = { rootNav.navigate("admin_panel") }
                )
            }
        }

        // --- REPORT (summary) ---
        composable("reportSummary") {
            val reportJson =
                    readArg(
                            current = rootNav.currentBackStackEntry,
                            previous = rootNav.previousBackStackEntry,
                            key = "reportJson"
                    )
            val report: FullReport? =
                    reportJson?.let {
                        runCatching { Json.decodeFromString(FullReport.serializer(), it) }
                                .getOrNull()
                    }

            if (report == null) {
                rootNav.popBackStack()
            } else {
                ReportScreen(
                        report = report,
                        onBack = { rootNav.popBackStack() },
                        onOpenBoard = {
                            val packed = json.encodeToString(report)
                            rootNav.currentBackStackEntry?.savedStateHandle?.set(
                                    "reportJson",
                                    packed
                            )
                            rootNav.navigate("reportBoard")
                        }
                )
            }
        }

        // --- REPORT (board) ---
        composable("reportBoard") {
            val reportJson =
                    readArg(
                            current = rootNav.currentBackStackEntry,
                            previous = rootNav.previousBackStackEntry,
                            key = "reportJson"
                    )
            val report: FullReport? =
                    reportJson?.let {
                        runCatching { Json.decodeFromString(FullReport.serializer(), it) }
                                .getOrNull()
                    }

            if (report == null) {
                rootNav.popBackStack()
            } else {
                GameReportScreen(report = report, onBack = { rootNav.popBackStack() })
            }
        }

        // --- ADMIN PANEL ---
        composable("admin_panel") {
            val profile = currentUserProfile
            if (profile == null || !profile.isAdmin) {
                rootNav.popBackStack()
            } else {
                AdminPanelScreen(onBack = { rootNav.popBackStack() })
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
                cont.resumeWithException(task.exception ?: RuntimeException("Task failed"))
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
