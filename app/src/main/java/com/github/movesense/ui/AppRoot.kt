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
import com.github.movesense.ui.screens.GameReportScreen
import com.github.movesense.ui.screens.HomeWithBottomNav
import com.github.movesense.ui.screens.LoginScreen
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

    // В LaunchedEffect при загрузке профиля:
    LaunchedEffect(Unit) {
        val auth = FirebaseAuth.getInstance()
        val user = auth.currentUser

        if (user != null) {
            val docRef = FirebaseFirestore.getInstance().collection("users").document(user.uid)

            // ✅ Real-time listener
            val registration =
                    docRef.addSnapshotListener { snapshot, e ->
                        if (e != null) {
                            // Handle error if needed
                            return@addSnapshotListener
                        }

                        if (snapshot != null && snapshot.exists()) {
                            val languageCode = snapshot.getString("language")

                            if (!languageCode.isNullOrBlank()) {
                                val savedLanguageCode = LocaleManager.getSavedLanguageCode(context)
                                if (savedLanguageCode != languageCode) {
                                    // val language = LocaleManager.Language.fromCode(languageCode)
                                    // LocaleManager.setLocale(context as ComponentActivity, language)
                                    // Activity might restart, but we continue processing for now
                                    // DISABLED: This causes restart loops during login if local lang != profile lang.
                                }
                            }

                            val isPremium = snapshot.getBoolean("isPremium") ?: false
                            val premiumUntil = snapshot.getLong("premiumUntil") ?: -1L
                            var finalIsPremium = isPremium

                            // Check for expiration
                            if (isPremium && premiumUntil != -1L) {
                                if (System.currentTimeMillis() > premiumUntil) {
                                    // Expired!
                                    docRef.update("isPremium", false)
                                    finalIsPremium = false
                                }
                            }

                            val newProfile =
                                    UserProfile(
                                            email = user.email ?: "",

                                            lichessUsername = snapshot.getString("lichessUsername")
                                                            ?: "",
                                            chessUsername = snapshot.getString("chessUsername")
                                                            ?: "",
                                            language = languageCode
                                                            ?: LocaleManager.Language.ENGLISH.code,
                                            isPremium = finalIsPremium,
                                            premiumUntil = premiumUntil,
                                            isAdmin = snapshot.getBoolean("isAdmin") ?: false
                                    )

                            // ✅ Auto-switch to SERVER mode if user becomes premium
                            if (finalIsPremium && (currentUserProfile?.isPremium != true)) {
                                // Only if previously not premium (or null)
                                if (com.github.movesense.EngineClient.engineMode.value ==
                                                com.github.movesense.EngineClient.EngineMode.LOCAL
                                ) {
                                    kotlinx.coroutines.GlobalScope.launch(
                                            kotlinx.coroutines.Dispatchers.Main
                                    ) {
                                        com.github.movesense.EngineClient.setEngineMode(
                                                com.github.movesense.EngineClient.EngineMode.SERVER
                                        )
                                        android.widget.Toast.makeText(
                                                        context,
                                                        context.getString(
                                                                R.string.switched_to_server_mode
                                                        ),
                                                        android.widget.Toast.LENGTH_LONG
                                                )
                                                .show()
                                    }
                                }
                            }

                            currentUserProfile = newProfile

                            val gift = snapshot.getString("giftNotification")
                            if (!gift.isNullOrBlank()) {
                                giftNotification = gift
                                docRef.update("giftNotification", null)
                            }
                        } else {
                            currentUserProfile = null
                        }

                        isBootLoading = false
                    }

            // Keep listener alive? LaunchedEffect will cancel it when disposed?
            // No, addSnapshotListener returns a registration that needs to be removed.
            // But here we are inside a coroutine scope that might complete?
            // Actually LaunchedEffect block runs suspend functions. addSnapshotListener is async
            // callback.
            // We need to keep this active.
            // Ideally we should use produceState or DisposableEffect for listeners.
            // But since this is AppRoot and we want it to live as long as AppRoot lives (which is
            // app life), it's fine.
            // However, to be clean, let's use awaitClose if we were in callbackFlow, or just let it
            // be.
            // Better: use DisposableEffect for the listener.

            // Refactoring to DisposableEffect below...
        } else {
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
                                                                        ?: false
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
                    } else {
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
            startDestination = if (currentUserProfile == null) "welcome" else "home"
    ) {
        // --- WELCOME ---
        composable("welcome") {
            com.github.movesense.ui.screens.WelcomeScreen(
                    onNavigateToLogin = { isLogin -> rootNav.navigate("login/$isLogin") },
                    onLoginSuccess = { profile ->
                        currentUserProfile = profile
                        isFirstLoad = true
                        shouldShowDateSelection = true
                        rootNav.navigate("home") { popUpTo("welcome") { inclusive = true } }
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
                        rootNav.navigate("home") { popUpTo("welcome") { inclusive = true } }
                    },
                    onRegisterSuccess = { profile ->
                        currentUserProfile = profile
                        isFirstLoad = true
                        shouldShowDateSelection = true
                        rootNav.navigate("home") { popUpTo("welcome") { inclusive = true } }
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
                            currentUserProfile = null
                            games = emptyList()
                            openingFens = emptySet()
                            isFirstLoad = true
                            rootNav.navigate("login/true") { popUpTo("home") { inclusive = true } }
                        },
                        onAdminClick = { rootNav.navigate("admin_panel") },
                        shouldShowDateSelection = shouldShowDateSelection,
                        onDateSelectionShown = { shouldShowDateSelection = false }
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
