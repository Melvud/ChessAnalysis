package com.github.movesense.ui.screens

import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.zIndex
import com.github.movesense.EngineClient
import com.github.movesense.FullReport
import com.github.movesense.GameHeader
import com.github.movesense.LineEval
import com.github.movesense.MoveClass
import com.github.movesense.PgnChess
import com.github.movesense.PositionEval
import com.github.movesense.Provider
import com.github.movesense.R
import com.github.movesense.pgnHash
import com.github.movesense.data.local.gameRepository
import com.github.movesense.data.local.GuestPreferences
import com.github.movesense.subscription.GooglePlayBillingManager
import com.github.movesense.ui.UserProfile
import com.github.movesense.ui.components.BoardCanvas
import com.github.movesense.ui.components.PremiumBanner
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToLong
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.auth.api.signin.GoogleSignInStatusCodes
import com.google.android.gms.common.api.ApiException
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.withContext

private const val TAG = "GamesListScreen"

enum class GameFilter {
        ALL,
        LICHESS,
        CHESSCOM,
        MANUAL
}

enum class GameTermination {
        CHECKMATE,
        TIMEOUT,
        RESIGNATION,
        DRAW,
        STALEMATE,
        AGREEMENT,
        INSUFFICIENT,
        REPETITION,
        FIFTY_MOVE,
        UNKNOWN
}

data class GameEndInfo(val termination: GameTermination, val winner: String?)

private data class PlayerInfo(val name: String, val title: String?)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun GamesListScreen(
        profile: UserProfile,
        games: List<GameHeader>,
        openingFens: Set<String>,
        isFirstLoad: Boolean,
        onFirstLoadComplete: () -> Unit,
        onGamesUpdated: (List<GameHeader>) -> Unit,
        onOpenReport: (FullReport) -> Unit,
        shouldShowDateSelection: Boolean,
        onDateSelectionShown: () -> Unit,
        onNavigateToProfile: () -> Unit,
        onStartOnboarding: () -> Unit,
        gameToOpen: GameHeader? = null,
        onGameOpened: () -> Unit = {},
        analyzedGames: MutableMap<String, FullReport>
) {
        val scope = rememberCoroutineScope()
        val context = LocalContext.current
        val json = remember {
                Json {
                        ignoreUnknownKeys = true
                        explicitNulls = false
                }
        }
        val repo = remember { context.gameRepository(json) }
        
        // Google Sign-In Launcher
        val googleSignInLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.StartActivityForResult()
        ) { result ->
            val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
            try {
                val account = task.getResult(ApiException::class.java)
                account?.idToken?.let { token ->
                    val credential = GoogleAuthProvider.getCredential(token, null)
                    // Link the anonymous account with the Google credential
                    FirebaseAuth.getInstance().currentUser?.linkWithCredential(credential)
                        ?.addOnSuccessListener {
                            // Link successful, convert guest data to registered user data
                            // This logic is handled in AppRoot/ProfileScreen, but we need to ensure UI updates
                            Toast.makeText(context, context.getString(R.string.google_linked), Toast.LENGTH_SHORT).show()
                        }
                        ?.addOnFailureListener { e ->
                            if (e is FirebaseAuthUserCollisionException) {
                                // Account already exists, maybe sign in instead?
                                // For now just show error
                                Toast.makeText(context, context.getString(R.string.link_error, e.message), Toast.LENGTH_LONG).show()
                            } else {
                                Toast.makeText(context, context.getString(R.string.link_error, e.message), Toast.LENGTH_LONG).show()
                            }
                        }
                }
            } catch (e: ApiException) {
                if (e.statusCode == GoogleSignInStatusCodes.SIGN_IN_CANCELLED) {
                    Log.d(TAG, "Google sign in cancelled")
                } else {
                    Log.e(TAG, "Google sign in failed", e)
                    Toast.makeText(context, "Google sign in failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }

        val launchGoogleSignIn = {
            val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestIdToken(context.getString(R.string.default_web_client_id))
                .requestEmail()
                .build()
            val googleSignInClient = GoogleSignIn.getClient(context, gso)
            // Always sign out first to force account selection
            googleSignInClient.signOut().addOnCompleteListener {
                googleSignInLauncher.launch(googleSignInClient.signInIntent)
            }
            Unit
        }

        val billingPremium by GooglePlayBillingManager.isPremiumFlow.collectAsState()

        // Use profile directly as it is now updated in real-time by AppRoot
        val isPremiumUser = profile.isPremium || billingPremium

        var isBannerClosedSession by remember {
                mutableStateOf(com.github.movesense.App.isBannerDismissed)
        }
        val showPremiumBanner = !isPremiumUser && !isBannerClosedSession

        // Check purchase history to decide banner type (Promo vs Standard)
        var hasPurchaseHistory by remember { mutableStateOf(false) } // Default to false (show promo) until loaded
        LaunchedEffect(Unit) {
            hasPurchaseHistory = GooglePlayBillingManager.hasPurchaseHistory()
        }



        var showSettingsDialog by remember { mutableStateOf(false) }
        var showLoadAllWarning by remember { mutableStateOf(false) }
        var showNoAccountsDialog by remember { mutableStateOf(false) }

        val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
        var isBackgrounded by remember { mutableStateOf(false) }

        DisposableEffect(lifecycleOwner) {
                val observer =
                        androidx.lifecycle.LifecycleEventObserver { _, event ->
                                isBackgrounded =
                                        event == androidx.lifecycle.Lifecycle.Event.ON_PAUSE ||
                                                event == androidx.lifecycle.Lifecycle.Event.ON_STOP
                        }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }

        var items by remember(games) { mutableStateOf(games) }
        // analyzedGames passed as parameter

        var currentFilter by remember { mutableStateOf(GameFilter.ALL) }
        var searchQuery by remember { mutableStateOf("") }

        var showAnalysis by remember { mutableStateOf(false) }
        var analysisJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
        var liveFen by remember { mutableStateOf<String?>(null) }
        var liveUciMove by remember { mutableStateOf<String?>(null) }
        var liveMoveClass by remember { mutableStateOf<String?>(null) }
        var analysisProgress by remember { mutableStateOf(0f) }
        var analysisStage by remember { mutableStateOf<String?>(null) }
        var livePositions by remember { mutableStateOf<List<PositionEval>>(emptyList()) }
        var currentPlyForEval by remember { mutableStateOf(0) }

        var animatedMoveIndex by remember { mutableStateOf(0) }
        var allGameMoves by remember {
                mutableStateOf<List<Triple<String, String, String>>>(emptyList())
        }
        var isServerMode by remember { mutableStateOf(false) }
        var analysisCompleted by remember { mutableStateOf(false) }
        var completedReport by remember { mutableStateOf<FullReport?>(null) }

        var visibleEtaMs by remember { mutableStateOf<Long?>(null) }
        var emaPerMoveMs by remember { mutableStateOf<Double?>(null) }
        var lastTickDone by remember { mutableStateOf<Int?>(null) }
        var lastTickAtMs by remember { mutableStateOf<Long?>(null) }
        var etaAnchorStartMs by remember { mutableStateOf<Long?>(null) }
        var etaInitialMs by remember { mutableStateOf<Long?>(null) }
        var totalPly by remember { mutableStateOf<Int?>(null) }
        var analysisStartAtMs by remember { mutableStateOf<Long?>(null) }

        var prevFenForSound by remember { mutableStateOf<String?>(null) }
        var lastSoundedUci by remember { mutableStateOf<String?>(null) }

        var showAddDialog by remember { mutableStateOf(false) }
        var pastedPgn by remember { mutableStateOf("") }

        var isDeltaSyncing by remember { mutableStateOf(false) }
        var isFullSyncing by remember { mutableStateOf(false) }
        var syncProgressLoaded by remember { mutableStateOf(0) }
        var syncProgressTotal by remember { mutableStateOf(0) }
        var syncStatusMessage by remember { mutableStateOf("") }

        var showDatePickerFrom by remember { mutableStateOf(false) }
        var showDatePickerUntil by remember { mutableStateOf(false) }
        var dateFromMillis by remember { mutableStateOf<Long?>(null) }
        var dateUntilMillis by remember { mutableStateOf<Long?>(null) }

        // State for source selection in dialog
        var loadLichess by remember { mutableStateOf(true) }
        var loadChessCom by remember { mutableStateOf(true) }

        val dateFormatter = remember { SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()) }

        var showPaywall by remember { mutableStateOf(false) }

        fun playMoveSound(cls: MoveClass?, isCapture: Boolean) {
                val resId =
                        when {
                                cls == MoveClass.INACCURACY ||
                                        cls == MoveClass.MISTAKE ||
                                        cls == MoveClass.BLUNDER -> R.raw.error
                                isCapture -> R.raw.capture
                                else -> R.raw.move
                        }
                runCatching {
                        if (!isBackgrounded) {
                                MediaPlayer.create(context, resId)?.apply {
                                        setOnCompletionListener { it.release() }
                                        start()
                                }
                        }
                }
        }

        fun pieceAtFen(fen: String, square: String): Char? {
                val fields = fen.split(" ")
                if (fields.isEmpty()) return null
                val board = fields[0]
                val ranks = board.split("/")
                if (ranks.size != 8) return null
                val fileChar = square[0] - 'a'
                val rankIdxFromTop = 8 - (square[1] - '0')
                if (fileChar !in 0..7 || rankIdxFromTop !in 0..7) return null
                val rank = ranks[rankIdxFromTop]
                var col = 0
                for (ch in rank) {
                        if (ch.isDigit()) {
                                col += (ch.code - '0'.code)
                        } else {
                                if (col == fileChar) return ch
                                col++
                        }
                }
                return null
        }

        fun isCapture(prevFen: String?, uci: String): Boolean {
                if (prevFen.isNullOrBlank() || uci.length < 4) return false
                val from = uci.substring(0, 2)
                val to = uci.substring(2, 4)

                val pieceTo = pieceAtFen(prevFen, to)
                if (pieceTo != null) return true

                val pieceFrom = pieceAtFen(prevFen, from)
                val isPawn = pieceFrom in listOf('P', 'p')
                val fromFile = from[0]
                val toFile = to[0]

                return isPawn && fromFile != toFile
        }

        suspend fun loadFromLocal() {
                Log.d(TAG, "loadFromLocal: starting...")
                items = repo.getAllHeaders()
                onGamesUpdated(items)


                // Check if we need to prompt for download for any linked account
                val hasLichess = profile.lichessUsername.isNotBlank()
                val hasChessCom = profile.chessUsername.isNotBlank()

                val hasLichessGames =
                        if (hasLichess) items.any { it.site == Provider.LICHESS } else true
                val hasChessComGames =
                        if (hasChessCom) items.any { it.site == Provider.CHESSCOM } else true

                if ((hasLichess && !hasLichessGames) || (hasChessCom && !hasChessComGames)) {
                        if (!isFullSyncing && !isDeltaSyncing) {
                                // Pre-select only the missing ones
                                loadLichess = hasLichess && !hasLichessGames
                                loadChessCom = hasChessCom && !hasChessComGames

                                // Fallback: if for some reason both are false (shouldn't happen due
                                // to if condition), select available
                                if (!loadLichess && !loadChessCom) {
                                        if (hasLichess) loadLichess = true
                                        if (hasChessCom) loadChessCom = true
                                }

                                showSettingsDialog = true
                        }
                }

                val pgnsToLoad = items.mapNotNull { it.pgn }.filter { it.isNotBlank() }
                val analyzed = mutableMapOf<String, FullReport>()

                if (pgnsToLoad.isNotEmpty()) {
                        val cachedMap = repo.getCachedReports(pgnsToLoad)
                        analyzed.putAll(cachedMap)
                }
                analyzedGames.clear()
                analyzedGames.putAll(analyzed)
        }

        suspend fun deltaSyncWithRemote() {
                try {
                        if (profile.lichessUsername.isBlank() && profile.chessUsername.isBlank()) {
                                Toast.makeText(
                                                context,
                                                context.getString(R.string.no_nicknames_error),
                                                Toast.LENGTH_LONG
                                        )
                                        .show()
                                return
                        }

                        var addedCount = 0
                        syncProgressLoaded = 0
                        syncProgressTotal = 0
                        syncStatusMessage = context.getString(R.string.loading_calculating_total)

                        if (profile.lichessUsername.isNotEmpty()) {
                                val since = repo.getNewestGameTimestamp(Provider.LICHESS)
                                // Fix: If since is 0 (never loaded), SKIP delta sync. User must use
                                // initial load dialog.
                                if ((since ?: 0) > 0) {
                                        syncStatusMessage =
                                                context.getString(R.string.loading_lichess)
                                        val lichessList =
                                                com.github.movesense.GameLoaders.loadLichess(
                                                        profile.lichessUsername,
                                                        since = since,
                                                        max = null,
                                                        onProgress = { loaded, _ ->
                                                                syncProgressLoaded = loaded
                                                        }
                                                )
                                        val added =
                                                repo.mergeExternal(Provider.LICHESS, lichessList)
                                        addedCount += added
                                }
                        }

                        if (profile.chessUsername.isNotEmpty()) {
                                val since = repo.getNewestGameTimestamp(Provider.CHESSCOM)
                                // Fix: If since is 0 (never loaded), SKIP delta sync.
                                if ((since ?: 0) > 0) {
                                        syncStatusMessage =
                                                context.getString(R.string.loading_chesscom)
                                        val chessList =
                                                com.github.movesense.GameLoaders.loadChessCom(
                                                        profile.chessUsername,
                                                        since = since,
                                                        max = null,
                                                        onProgress = { loaded, _ ->
                                                                syncProgressLoaded = loaded
                                                        }
                                                )
                                        val added = repo.mergeExternal(Provider.CHESSCOM, chessList)
                                        addedCount += added
                                }
                        }

                        if (addedCount > 0) {
                                Toast.makeText(
                                                context,
                                                context.getString(R.string.games_added, addedCount),
                                                Toast.LENGTH_SHORT
                                        )
                                        .show()
                        }
                } catch (e: Throwable) {
                        Log.e(TAG, "deltaSyncWithRemote failed: ${e.message}", e)
                        Toast.makeText(
                                        context,
                                        context.getString(R.string.sync_error, e.message ?: ""),
                                        Toast.LENGTH_SHORT
                                )
                                .show()
                }
        }

        suspend fun fullSyncWithRemote(
                since: Long?,
                until: Long?,
                useLichess: Boolean,
                useChessCom: Boolean,
                lichessUser: String = profile.lichessUsername,
                chessUser: String = profile.chessUsername
        ) {
                try {
                        isFullSyncing = true
                        syncProgressLoaded = 0
                        syncProgressTotal = 0
                        syncStatusMessage = context.getString(R.string.loading_calculating_total)

                        if (!useLichess && !useChessCom) return

                        // 1. Calculate total games
                        var totalLichess = 0
                        var totalChessCom = 0

                        if (useLichess && lichessUser.isNotEmpty()) {
                                totalLichess =
                                        com.github.movesense.GameLoaders.getLichessGameCount(
                                                lichessUser
                                        )
                        }
                        if (useChessCom && chessUser.isNotEmpty()) {
                                totalChessCom =
                                        com.github.movesense.GameLoaders.getChessComGameCount(
                                                chessUser
                                        )
                        }

                        val isLoadAll = since == null && until == null
                        if (isLoadAll) {
                                syncProgressTotal = totalLichess + totalChessCom
                        } else {
                                syncProgressTotal = 0
                        }

                        var addedCount = 0
                        var currentLoadedTotal = 0

                        if (useLichess && lichessUser.isNotEmpty()) {
                                syncStatusMessage = context.getString(R.string.loading_lichess)
                                val lichessList =
                                        com.github.movesense.GameLoaders.loadLichess(
                                                lichessUser,
                                                since = since,
                                                until = until,
                                                max = null,
                                                onProgress = { loaded, _ ->
                                                        syncProgressLoaded =
                                                                currentLoadedTotal + loaded
                                                }
                                        )
                                val added = repo.mergeExternal(Provider.LICHESS, lichessList)
                                addedCount += added
                                currentLoadedTotal += lichessList.size
                        }

                        if (useChessCom && chessUser.isNotEmpty()) {
                                syncStatusMessage = context.getString(R.string.loading_chesscom)
                                val chessList =
                                        com.github.movesense.GameLoaders.loadChessCom(
                                                chessUser,
                                                since = since,
                                                until = until,
                                                max = null,
                                                onProgress = { loaded, _ ->
                                                        syncProgressLoaded =
                                                                currentLoadedTotal + loaded
                                                }
                                        )
                                val added = repo.mergeExternal(Provider.CHESSCOM, chessList)
                                addedCount += added
                        }

                        if (addedCount > 0) {
                                Toast.makeText(
                                                context,
                                                context.getString(R.string.games_added, addedCount),
                                                Toast.LENGTH_SHORT
                                        )
                                        .show()
                        }
                } catch (e: Throwable) {
                        Log.e(TAG, "fullSyncWithRemote failed: ${e.message}", e)
                        Toast.makeText(
                                        context,
                                        context.getString(R.string.sync_error, e.message ?: ""),
                                        Toast.LENGTH_SHORT
                                )
                                .show()
                } finally {
                        isFullSyncing = false
                        syncProgressLoaded = 0
                        syncProgressTotal = 0
                        showSettingsDialog = false
                }
        }

        LaunchedEffect(profile, isFirstLoad, shouldShowDateSelection) {
                if (isFirstLoad) {
                        loadFromLocal()

                        // If we have items, signal load complete immediately
                        if (items.isNotEmpty()) {
                                onFirstLoadComplete()
                                
                                // Only show date selection if requested AND we have games (normal flow)
                                if (shouldShowDateSelection) {
                                        showSettingsDialog = true
                                        onDateSelectionShown()
                                }

                                // Auto-sync delta if we have games
                                isDeltaSyncing = true
                                scope.launch {
                                        deltaSyncWithRemote()
                                        loadFromLocal()
                                        isDeltaSyncing = false
                                }
                        } else {
                                // No items. Check if we have accounts.
                                val hasCredentials =
                                        profile.lichessUsername.isNotBlank() ||
                                                profile.chessUsername.isNotBlank()
                                if (hasCredentials) {
                                        // NEW BEHAVIOR: Show dialog instead of auto-loading
                                        // 🌟 Remove test games if any
                                        scope.launch {
                                            repo.deleteTestGames()
                                        }
                                        showSettingsDialog = true
                                        if (shouldShowDateSelection) {
                                            onDateSelectionShown()
                                        }
                                } else {
                                        // Load test games (Magnus Carlsen)
                                        // Load test games (Magnus Carlsen)
                                        // 🌟 Use GlobalScope to ensure loading continues even if user navigates to Tutorial
                                        @OptIn(DelicateCoroutinesApi::class)
                                        GlobalScope.launch(Dispatchers.IO) {
                                            try {
                                                val testGames = com.github.movesense.GameLoaders.loadChessCom("MagnusCarlsen", max = 10)
                                                    .map { it.copy(isTest = true) }
                                                repo.mergeExternal(Provider.CHESSCOM, testGames)
                                                
                                                val headers = repo.getAllHeaders()
                                                withContext(Dispatchers.Main) {
                                                    // Update AppRoot state so it persists
                                                    onGamesUpdated(headers)
                                                    // Update local state if we are still active
                                                    items = headers
                                                }
                                            } catch (e: Exception) {
                                                Log.e(TAG, "Failed to load test games", e)
                                                withContext(Dispatchers.Main) {
                                                    Toast.makeText(context, "Failed to load test games: ${e.message}", Toast.LENGTH_LONG).show()
                                                }
                                            }
                                        }
                                        showNoAccountsDialog = true
                                        // 🌟 Fix: Ensure Load Games dialog doesn't overlap with Welcome dialog
                                        showSettingsDialog = false
                                        // 🌟 Fix: Consume the flag so it doesn't trigger dialog on recomposition
                                        if (shouldShowDateSelection) {
                                            onDateSelectionShown()
                                        }
                                }
                                // Signal complete so we show the empty screen (or dialog)
                                onFirstLoadComplete()
                        }
                } else {
                        loadFromLocal()
                        if (shouldShowDateSelection) {
                                val hasCredentials = profile.lichessUsername.isNotBlank() || profile.chessUsername.isNotBlank()
                                if (hasCredentials) {
                                    showSettingsDialog = true
                                }
                                onDateSelectionShown()
                        }
                }
        }

        val pullState = rememberPullToRefreshState()

        val filePicker =
                rememberLauncherForActivityResult(
                        contract = ActivityResultContracts.OpenDocument()
                ) { uri: Uri? ->
                        if (uri != null) {
                                scope.launch {
                                        runCatching {
                                                val pgn =
                                                        context.contentResolver
                                                                .openInputStream(uri)
                                                                ?.use {
                                                                        it.readBytes()
                                                                                .toString(
                                                                                        Charsets.UTF_8
                                                                                )
                                                                }
                                                                .orEmpty()
                                                if (pgn.isBlank())
                                                        error(context.getString(R.string.empty_pgn))
                                                addManualGame(pgn, profile, repo)
                                                loadFromLocal()
                                                Toast.makeText(
                                                                context,
                                                                context.getString(
                                                                        R.string.game_added
                                                                ),
                                                                Toast.LENGTH_SHORT
                                                        )
                                                        .show()
                                        }
                                                .onFailure {
                                                        Toast.makeText(
                                                                        context,
                                                                        context.getString(
                                                                                R.string.add_error,
                                                                                it.message ?: ""
                                                                        ),
                                                                        Toast.LENGTH_LONG
                                                                )
                                                                .show()
                                                }
                                }
                        }
                }

        fun startAnalysis(fullPgn: String, depth: Int, multiPv: Int, force: Boolean = false) {
                if (showAnalysis && !force) return

                val currentMode = EngineClient.engineMode.value

                if (currentMode == EngineClient.EngineMode.SERVER && !isPremiumUser) {
                        showPaywall = true
                        return
                }

                analysisJob?.cancel()
                analysisJob =
                        scope.launch {
                                try {
                                        showAnalysis = true
                                        liveFen = null
                                        liveUciMove = null
                                        liveMoveClass = null
                                        prevFenForSound = null
                                        lastSoundedUci = null
                                        analysisProgress = 0f
                                        analysisStage = null
                                        livePositions = emptyList()
                                        currentPlyForEval = 0
                                        analysisCompleted = false
                                        animatedMoveIndex = 0
                                        completedReport = null

                                        visibleEtaMs = null
                                        emaPerMoveMs = null
                                        lastTickDone = null
                                        lastTickAtMs = null
                                        etaAnchorStartMs = null
                                        etaInitialMs = null
                                        totalPly = null
                                        analysisStartAtMs = System.currentTimeMillis()

                                        val header =
                                                runCatching { PgnChess.headerFromPgn(fullPgn) }
                                                        .getOrNull()

                                        isServerMode =
                                                EngineClient.engineMode.value ==
                                                        EngineClient.EngineMode.SERVER

                                        val parsedMoves = PgnChess.movesWithFens(fullPgn)
                                        val startFen =
                                                parsedMoves.firstOrNull()?.beforeFen
                                                        ?: "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"

                                        allGameMoves = buildList {
                                                add(Triple(startFen, "", ""))
                                                parsedMoves.forEach { move ->
                                                        add(
                                                                Triple(
                                                                        move.afterFen,
                                                                        move.uci,
                                                                        move.san
                                                                )
                                                        )
                                                }
                                        }

                                        val accumulatedPositions = mutableMapOf<Int, PositionEval>()

                                        val report =
                                                EngineClient.analyzeGameByPgnWithProgress(
                                                        pgn = fullPgn,
                                                        depth = depth,
                                                        multiPv = multiPv,
                                                        header = header
                                                ) { snap ->
                                                        val now = System.currentTimeMillis()

                                                        val newFen = snap.fen
                                                        val newUci = snap.currentUci
                                                        val cls =
                                                                snap.currentClass?.let {
                                                                        runCatching {
                                                                                        MoveClass
                                                                                                .valueOf(
                                                                                                        it
                                                                                                )
                                                                                }
                                                                                .getOrNull()
                                                                }

                                                        analysisProgress =
                                                                (snap.percent ?: 0.0).toFloat() /
                                                                        100f
                                                        analysisStage = snap.stage

                                                        totalPly = snap.total
                                                        val prevDone = lastTickDone
                                                        if (snap.done > 0 && snap.total > 0) {
                                                                if (prevDone != null &&
                                                                                snap.done > prevDone
                                                                ) {
                                                                        val dt =
                                                                                (now -
                                                                                                (lastTickAtMs
                                                                                                        ?: now))
                                                                                        .coerceAtLeast(
                                                                                                1L
                                                                                        )
                                                                        val dDone =
                                                                                (snap.done -
                                                                                                prevDone)
                                                                                        .coerceAtLeast(
                                                                                                1
                                                                                        )
                                                                        val instPerMove =
                                                                                dt.toDouble() /
                                                                                        dDone.toDouble()
                                                                        emaPerMoveMs =
                                                                                emaPerMoveMs?.let {
                                                                                        0.2 *
                                                                                                instPerMove +
                                                                                                0.8 *
                                                                                                        it
                                                                                }
                                                                                        ?: instPerMove
                                                                }
                                                                lastTickDone = snap.done
                                                                lastTickAtMs = now

                                                                val remainingPly =
                                                                        (snap.total - snap.done)
                                                                                .coerceAtLeast(0)

                                                                if (etaAnchorStartMs == null ||
                                                                                etaInitialMs == null
                                                                ) {
                                                                        val avgPerMove =
                                                                                ((now -
                                                                                                        (analysisStartAtMs
                                                                                                                ?: now))
                                                                                                .toDouble() /
                                                                                                snap.done
                                                                                                        .toDouble())
                                                                                        .takeIf {
                                                                                                it.isFinite() &&
                                                                                                        it >
                                                                                                                0
                                                                                        }
                                                                        val localRemaining =
                                                                                avgPerMove
                                                                                        ?.times(
                                                                                                remainingPly
                                                                                        )
                                                                                        ?.roundToLong()
                                                                                        ?: 0L
                                                                        etaAnchorStartMs = now
                                                                        etaInitialMs =
                                                                                localRemaining
                                                                        visibleEtaMs =
                                                                                localRemaining
                                                                } else {
                                                                        val emaRemaining =
                                                                                emaPerMoveMs
                                                                                        ?.times(
                                                                                                remainingPly
                                                                                        )
                                                                                        ?.roundToLong()
                                                                        if (emaRemaining != null) {
                                                                                val currentLeft =
                                                                                        max(
                                                                                                0L,
                                                                                                etaAnchorStartMs!! +
                                                                                                        etaInitialMs!! -
                                                                                                        now
                                                                                        )
                                                                                if (emaRemaining <
                                                                                                currentLeft
                                                                                ) {
                                                                                        etaAnchorStartMs =
                                                                                                now
                                                                                        etaInitialMs =
                                                                                                emaRemaining
                                                                                        visibleEtaMs =
                                                                                                emaRemaining
                                                                                }
                                                                        }
                                                                }
                                                        }

                                                        if (!newUci.isNullOrBlank() &&
                                                                        newUci != lastSoundedUci
                                                        ) {
                                                                val captureNow =
                                                                        isCapture(
                                                                                prevFenForSound,
                                                                                newUci
                                                                        )
                                                                playMoveSound(cls, captureNow)
                                                                lastSoundedUci = newUci
                                                        }

                                                        prevFenForSound = newFen ?: prevFenForSound

                                                        liveFen = newFen
                                                        liveUciMove = newUci
                                                        liveMoveClass = snap.currentClass

                                                        if (snap.done > 0) {
                                                                currentPlyForEval = snap.done - 1
                                                        }

                                                        if (newFen != null &&
                                                                        (snap.evalCp != null ||
                                                                                snap.evalMate !=
                                                                                        null)
                                                        ) {
                                                                val line =
                                                                        LineEval(
                                                                                pv = emptyList(),
                                                                                cp = snap.evalCp,
                                                                                mate =
                                                                                        snap.evalMate,
                                                                                best = null,
                                                                                depth = depth,
                                                                                multiPv = 1
                                                                        )
                                                                val pos =
                                                                        PositionEval(
                                                                                fen = newFen,
                                                                                idx = snap.done - 1,
                                                                                lines = listOf(line)
                                                                        )

                                                                accumulatedPositions[pos.idx] = pos

                                                                livePositions =
                                                                        accumulatedPositions
                                                                                .values
                                                                                .sortedBy { it.idx }
                                                                                .toList()
                                                        }
                                                }

                                        livePositions = report.positions
                                        repo.saveReport(fullPgn, report)

                                        completedReport = report
                                        analysisCompleted = true
                                } catch (t: Throwable) {
                                        showAnalysis = false
                                        Log.e(TAG, "Analysis error: ${t.message}", t)
                                        Toast.makeText(
                                                        context,
                                                        context.getString(
                                                                R.string.analysis_error,
                                                                t.message ?: ""
                                                        ),
                                                        Toast.LENGTH_LONG
                                                )
                                                .show()
                                }
                        }
        }


        LaunchedEffect(gameToOpen) {
            gameToOpen?.let { header ->
                val pgn = header.pgn
                Log.d(TAG, "Opening game from stats: $pgn")
                if (!pgn.isNullOrBlank()) {
                     // Check if already analyzed
                     val existingReport = analyzedGames[pgnHash(header.pgn)]
                     if (existingReport != null) {
                         Log.d(TAG, "Opening existing report")
                         onOpenReport(existingReport)
                     } else {
                         Log.d(TAG, "Starting new analysis from stats")
                         // Force start analysis even if one is running
                         startAnalysis(pgn, 20, 1, force = true)
                     }
                     onGameOpened()
                } else {
                     Log.e(TAG, "PGN is empty/null for gameToOpen")
                }
            }
        }

        LaunchedEffect(analysisCompleted) {
                if (analysisCompleted) {
                        if (isServerMode) delay(100)
                        showAnalysis = false
                        loadFromLocal()
                        completedReport?.let { onOpenReport(it) }
                }
        }

        LaunchedEffect(showAnalysis, etaAnchorStartMs, etaInitialMs) {
                if (!showAnalysis || etaAnchorStartMs == null || etaInitialMs == null)
                        return@LaunchedEffect
                while (showAnalysis && etaAnchorStartMs != null && etaInitialMs != null) {
                        val now = System.currentTimeMillis()
                        val left = max(0L, etaAnchorStartMs!! + etaInitialMs!! - now)
                        visibleEtaMs = left
                        delay(1000)
                }
        }

        fun formatEta(ms: Long?): String {
                if (ms == null) return "—"
                val totalSec = (ms / 1000.0).roundToLong()
                val h = totalSec / 3600
                val m = (totalSec % 3600) / 60
                val s = totalSec % 60
                return when {
                        h > 0 -> String.format("%d:%02d:%02d", h, m, s)
                        else -> String.format("%d:%02d", m, s)
                }
        }

        val filteredItems =
                remember(items, currentFilter, searchQuery) {
                        val baseList = when (currentFilter) {
                                GameFilter.ALL -> items
                                GameFilter.LICHESS -> items.filter { it.site == Provider.LICHESS }
                                GameFilter.CHESSCOM -> items.filter { it.site == Provider.CHESSCOM }
                                GameFilter.MANUAL -> items.filter { it.site == Provider.MANUAL }
                        }
                        if (searchQuery.isBlank()) {
                                baseList
                        } else {
                                baseList.filter {
                                        (it.white?.contains(searchQuery, ignoreCase = true) == true) ||
                                                (it.black?.contains(searchQuery, ignoreCase = true) == true)
                                }
                        }
                }

        val listState = rememberLazyListState()

        LaunchedEffect(filteredItems) {
            listState.scrollToItem(0)
        }

        Scaffold(
                topBar = {
                        TopAppBar(
                                title = {
                                        Text(
                                                stringResource(R.string.games_list),
                                                style =
                                                        MaterialTheme.typography.titleLarge.copy(
                                                                fontWeight = FontWeight.Bold
                                                        )
                                        )
                                },
                                actions = {
                                        TextButton(onClick = { showSettingsDialog = true }) {
                                                Icon(
                                                        Icons.Default.CloudDownload,
                                                        contentDescription = null,
                                                        modifier = Modifier.size(18.dp)
                                                )
                                                Spacer(Modifier.width(8.dp))
                                                Text(
                                                        stringResource(R.string.load_game),
                                                        style =
                                                                MaterialTheme.typography.labelLarge
                                                                        .copy(
                                                                                fontWeight =
                                                                                        FontWeight
                                                                                                .Bold
                                                                        )
                                                )
                                        }

                                },
                                colors =
                                        TopAppBarDefaults.topAppBarColors(
                                                containerColor = MaterialTheme.colorScheme.surface,
                                                titleContentColor =
                                                        MaterialTheme.colorScheme.onSurface
                                        )
                        )
                }
        ) { padding ->
                Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                        Column(Modifier.fillMaxSize()) {
                                OutlinedTextField(
                                        value = searchQuery,
                                        onValueChange = { searchQuery = it },
                                        modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 16.dp, vertical = 8.dp),
                                        placeholder = {
                                                Text(
                                                        stringResource(R.string.search_hint),
                                                        style = MaterialTheme.typography.bodyLarge
                                                )
                                        },
                                        leadingIcon = {
                                                Icon(
                                                        Icons.Default.Search,
                                                        contentDescription = null,
                                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                        },
                                        trailingIcon = {
                                                if (searchQuery.isNotEmpty()) {
                                                        IconButton(onClick = { searchQuery = "" }) {
                                                                Icon(
                                                                        Icons.Default.Close,
                                                                        contentDescription = stringResource(R.string.close),
                                                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                                                )
                                                        }
                                                }
                                        },
                                        shape = CircleShape,
                                        colors = OutlinedTextFieldDefaults.colors(
                                                focusedBorderColor = MaterialTheme.colorScheme.primary,
                                                unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                                                focusedContainerColor = MaterialTheme.colorScheme.surface,
                                                unfocusedContainerColor = MaterialTheme.colorScheme.surface
                                        ),
                                        singleLine = true
                                )

                                Row(
                                        modifier =
                                                Modifier.fillMaxWidth()
                                                        .horizontalScroll(rememberScrollState())
                                                        .padding(
                                                                start = 16.dp,
                                                                end = 16.dp,
                                                                bottom = 12.dp
                                                        ),
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                        FilterChip(
                                                selected = currentFilter == GameFilter.ALL,
                                                onClick = { currentFilter = GameFilter.ALL },
                                                label = {
                                                        Text(
                                                                context.getString(
                                                                        R.string.filter_all,
                                                                        items.size
                                                                ),
                                                                maxLines = 1,
                                                                overflow = TextOverflow.Ellipsis,
                                                                style =
                                                                        MaterialTheme.typography
                                                                                .labelLarge
                                                        )
                                                },
                                                colors =
                                                        FilterChipDefaults.filterChipColors(
                                                                selectedContainerColor =
                                                                        MaterialTheme.colorScheme
                                                                                .primaryContainer,
                                                                selectedLabelColor =
                                                                        MaterialTheme.colorScheme
                                                                                .onPrimaryContainer
                                                        )
                                        )
                                        FilterChip(
                                                selected = currentFilter == GameFilter.LICHESS,
                                                onClick = { currentFilter = GameFilter.LICHESS },
                                                label = {
                                                        Text(
                                                                stringResource(
                                                                        R.string.filter_lichess
                                                                ),
                                                                maxLines = 1,
                                                                overflow = TextOverflow.Ellipsis,
                                                                style =
                                                                        MaterialTheme.typography
                                                                                .labelLarge
                                                        )
                                                },
                                                colors =
                                                        FilterChipDefaults.filterChipColors(
                                                                selectedContainerColor =
                                                                        MaterialTheme.colorScheme
                                                                                .primaryContainer,
                                                                selectedLabelColor =
                                                                        MaterialTheme.colorScheme
                                                                                .onPrimaryContainer
                                                        )
                                        )
                                        FilterChip(
                                                selected = currentFilter == GameFilter.CHESSCOM,
                                                onClick = { currentFilter = GameFilter.CHESSCOM },
                                                label = {
                                                        Text(
                                                                stringResource(
                                                                        R.string.filter_chesscom
                                                                ),
                                                                maxLines = 1,
                                                                overflow = TextOverflow.Ellipsis,
                                                                style =
                                                                        MaterialTheme.typography
                                                                                .labelLarge
                                                        )
                                                },
                                                colors =
                                                        FilterChipDefaults.filterChipColors(
                                                                selectedContainerColor =
                                                                        MaterialTheme.colorScheme
                                                                                .primaryContainer,
                                                                selectedLabelColor =
                                                                        MaterialTheme.colorScheme
                                                                                .onPrimaryContainer
                                                        )
                                        )
                                        FilterChip(
                                                selected = currentFilter == GameFilter.MANUAL,
                                                onClick = { currentFilter = GameFilter.MANUAL },
                                                label = {
                                                        Text(
                                                                stringResource(
                                                                        R.string.filter_manual
                                                                ),
                                                                maxLines = 1,
                                                                overflow = TextOverflow.Ellipsis,
                                                                style =
                                                                        MaterialTheme.typography
                                                                                .labelLarge
                                                        )
                                                },
                                                colors =
                                                        FilterChipDefaults.filterChipColors(
                                                                selectedContainerColor =
                                                                        MaterialTheme.colorScheme
                                                                                .primaryContainer,
                                                                selectedLabelColor =
                                                                        MaterialTheme.colorScheme
                                                                                .onPrimaryContainer
                                                        )
                                        )
                                }

                                if (!isPremiumUser && showPremiumBanner && !showAnalysis) {
                                        PremiumBanner(
                                                onUpgradeClick = { 
                                                    if (profile.isGuest) {
                                                        launchGoogleSignIn()
                                                    } else {
                                                        showPaywall = true 
                                                    }
                                                },
                                                onDismiss = {
                                                        com.github.movesense.App.isBannerDismissed =
                                                                true
                                                        isBannerClosedSession = true
                                                },
                                                modifier =
                                                        Modifier.padding(
                                                                horizontal = 16.dp,
                                                                vertical = 8.dp
                                                        ),
                                                isPromo = !hasPurchaseHistory
                                        )
                                }

                                PullToRefreshBox(
                                        modifier = Modifier.fillMaxWidth().weight(1f),
                                        isRefreshing = isDeltaSyncing,
                                        onRefresh = {
                                                scope.launch {
                                                        isDeltaSyncing = true
                                                        deltaSyncWithRemote()
                                                        loadFromLocal()
                                                        isDeltaSyncing = false
                                                }
                                        },
                                        state = pullState
                                ) {
                                        when {
                                                isDeltaSyncing && items.isEmpty() -> {
                                                        Box(
                                                                Modifier.fillMaxSize(),
                                                                contentAlignment = Alignment.Center
                                                        ) { CircularProgressIndicator() }
                                                }
                                                filteredItems.isEmpty() && !isDeltaSyncing -> {
                                                        Box(
                                                                Modifier.fillMaxSize(),
                                                                contentAlignment = Alignment.Center
                                                        ) {
                                                                Text(
                                                                        stringResource(
                                                                                R.string.no_games
                                                                        ),
                                                                        style =
                                                                                MaterialTheme
                                                                                        .typography
                                                                                        .titleMedium
                                                                )
                                                        }
                                                }
                                                else -> {
                                                        LazyColumn(
                                                                modifier = Modifier.fillMaxSize(),
                                                                state = listState
                                                        ) {
                                                                itemsIndexed(
                                                                        filteredItems,
                                                                        key = { _, g ->
                                                                                val hashPart =
                                                                                        (g.pgn?.length
                                                                                                        ?: 0)
                                                                                                .toString()
                                                                                "${g.site}|${g.date}|${g.white}|${g.black}|${g.result}|$hashPart"
                                                                        }
                                                                ) { index, game ->
                                                                        val analyzedReport =
                                                                                analyzedGames[
                                                                                        repo.pgnHash(
                                                                                                game.pgn
                                                                                                        .orEmpty()
                                                                                        )]

                                                                        CompactGameCard(
                                                                                game = game,
                                                                                profile = profile,
                                                                                analyzedReport =
                                                                                        analyzedReport,
                                                                                index = index,
                                                                                isAnalyzing =
                                                                                        showAnalysis,
                                                                                onClick = {
                                                                                        if (showAnalysis
                                                                                        )
                                                                                                return@CompactGameCard

                                                                                        val currentPgn =
                                                                                                game.pgn
                                                                                                        .orEmpty()

                                                                                        scope
                                                                                                .launch {
                                                                                                        try {
                                                                                                                val cachedReport =
                                                                                                                        currentPgn
                                                                                                                                .takeIf {
                                                                                                                                        it.isNotBlank()
                                                                                                                                }
                                                                                                                                ?.let {
                                                                                                                                        repo.getCachedReport(
                                                                                                                                                it
                                                                                                                                        )
                                                                                                                                }

                                                                                                                if (cachedReport !=
                                                                                                                                null
                                                                                                                ) {
                                                                                                                        onOpenReport(
                                                                                                                                cachedReport
                                                                                                                        )
                                                                                                                        return@launch
                                                                                                                }

                                                                                                                val pgn =
                                                                                                                        currentPgn
                                                                                                                                .takeIf {
                                                                                                                                        it.isNotBlank()
                                                                                                                                }
                                                                                                                                ?: ""

                                                                                                                if (pgn.isBlank()
                                                                                                                ) {
                                                                                                                        Toast.makeText(
                                                                                                                                        context,
                                                                                                                                        context.getString(
                                                                                                                                                R.string
                                                                                                                                                        .pgn_not_found
                                                                                                                                        ),
                                                                                                                                        Toast.LENGTH_SHORT
                                                                                                                                )
                                                                                                                                .show()
                                                                                                                        return@launch
                                                                                                                }

                                                                                                                startAnalysis(
                                                                                                                        pgn,
                                                                                                                        depth =
                                                                                                                                12,
                                                                                                                        multiPv =
                                                                                                                                3
                                                                                                                )
                                                                                                        } catch (
                                                                                                                e:
                                                                                                                        Exception) {
                                                                                                                Log.e(
                                                                                                                        TAG,
                                                                                                                        "❌ Error: ${e.message}",
                                                                                                                        e
                                                                                                                )
                                                                                                                showAnalysis =
                                                                                                                        false
                                                                                                                Toast.makeText(
                                                                                                                                context,
                                                                                                                                context.getString(
                                                                                                                                        R.string
                                                                                                                                                .loading_error,
                                                                                                                                        e.message
                                                                                                                                                ?: ""
                                                                                                                                ),
                                                                                                                                Toast.LENGTH_SHORT
                                                                                                                        )
                                                                                                                        .show()
                                                                                                        }
                                                                                                }
                                                                                },
                                                                                onLongPress = {}
                                                                        )
                                                                }
                                                        }
                                                }
                                        }
                                }
                        }

                        if (showAnalysis && !isPremiumUser && !isServerMode) {
                                CompactPremiumBanner(
                                        onUpgradeClick = { 
                                            if (profile.isGuest) {
                                                launchGoogleSignIn()
                                            } else {
                                                showPaywall = true 
                                            }
                                        },
                                        modifier =
                                                Modifier.align(Alignment.TopCenter)
                                                        .padding(top = 16.dp)
                                                        .zIndex(11f)
                                )
                        }

                        if (showAnalysis) {
                                Box(
                                        Modifier.fillMaxSize()
                                                .zIndex(10f)
                                                .background(Color.Black.copy(alpha = 0.75f)),
                                        contentAlignment = Alignment.Center
                                ) {
                                        Card(
                                                colors =
                                                        CardDefaults.cardColors(
                                                                containerColor =
                                                                        MaterialTheme.colorScheme
                                                                                .surface
                                                        ),
                                                elevation =
                                                        CardDefaults.cardElevation(
                                                                defaultElevation = 16.dp
                                                        ),
                                                shape = RoundedCornerShape(24.dp),
                                                modifier = Modifier.padding(24.dp).width(340.dp)
                                        ) {
                                                Column(
                                                        modifier =
                                                                Modifier.fillMaxWidth()
                                                                        .padding(24.dp),
                                                        horizontalAlignment =
                                                                Alignment.CenterHorizontally
                                                ) {
                                                        Text(
                                                                text =
                                                                        stringResource(
                                                                                R.string.analyzing
                                                                        ),
                                                                style =
                                                                        MaterialTheme.typography
                                                                                .headlineSmall,
                                                                fontWeight = FontWeight.Bold,
                                                                color =
                                                                        MaterialTheme.colorScheme
                                                                                .onSurface,
                                                                fontSize = 24.sp
                                                        )

                                                        Spacer(Modifier.height(6.dp))

                                                        if (analysisProgress > 0f) {
                                                                Text(
                                                                        text =
                                                                                "${(analysisProgress * 100).toInt()}%",
                                                                        style =
                                                                                MaterialTheme
                                                                                        .typography
                                                                                        .bodyLarge,
                                                                        color =
                                                                                MaterialTheme
                                                                                        .colorScheme
                                                                                        .onSurface
                                                                                        .copy(
                                                                                                alpha =
                                                                                                        0.7f
                                                                                        ),
                                                                        fontWeight =
                                                                                FontWeight.Medium
                                                                )
                                                        }

                                                        val etaLabel = formatEta(visibleEtaMs)
                                                        if (etaLabel != "—") {
                                                                Spacer(Modifier.height(6.dp))
                                                                Text(
                                                                        text =
                                                                                stringResource(
                                                                                        R.string
                                                                                                .remaining_time,
                                                                                        etaLabel
                                                                                ),
                                                                        style =
                                                                                MaterialTheme
                                                                                        .typography
                                                                                        .bodyMedium,
                                                                        color =
                                                                                MaterialTheme
                                                                                        .colorScheme
                                                                                        .onSurface
                                                                                        .copy(
                                                                                                alpha =
                                                                                                        0.85f
                                                                                        ),
                                                                        fontWeight =
                                                                                FontWeight.SemiBold
                                                                )
                                                        }

                                                        Spacer(Modifier.height(12.dp))

                                                        Box(
                                                                modifier =
                                                                        Modifier.fillMaxWidth()
                                                                                .height(12.dp)
                                                                                .clip(
                                                                                        RoundedCornerShape(
                                                                                                6.dp
                                                                                        )
                                                                                )
                                                                                .background(
                                                                                        MaterialTheme
                                                                                                .colorScheme
                                                                                                .surfaceVariant
                                                                                )
                                                        ) {
                                                                Box(
                                                                        modifier =
                                                                                Modifier.fillMaxHeight()
                                                                                        .fillMaxWidth(
                                                                                                analysisProgress
                                                                                                        .coerceIn(
                                                                                                                0f,
                                                                                                                1f
                                                                                                        )
                                                                                        )
                                                                                        .clip(
                                                                                                RoundedCornerShape(
                                                                                                        6.dp
                                                                                                )
                                                                                        )
                                                                                        .background(
                                                                                                Brush.horizontalGradient(
                                                                                                        colors =
                                                                                                                listOf(
                                                                                                                        Color(
                                                                                                                                0xFF4CAF50
                                                                                                                        ),
                                                                                                                        Color(
                                                                                                                                0xFF66BB6A
                                                                                                                        ),
                                                                                                                        Color(
                                                                                                                                0xFF81C784
                                                                                                                        )
                                                                                                                )
                                                                                                )
                                                                                        )
                                                                )
                                                        }

                                                        Spacer(Modifier.height(20.dp))

                                                        Row(
                                                                modifier = Modifier.fillMaxWidth(),
                                                                horizontalArrangement =
                                                                        Arrangement.Center,
                                                                verticalAlignment =
                                                                        Alignment.CenterVertically
                                                        ) {
                                                                Box(
                                                                        modifier =
                                                                                Modifier.size(
                                                                                        280.dp
                                                                                ),
                                                                        contentAlignment =
                                                                                Alignment.Center
                                                                ) {
                                                                        if (!liveFen.isNullOrBlank()
                                                                        ) {
                                                                                val lastMovePair =
                                                                                        if (!liveUciMove
                                                                                                        .isNullOrBlank() &&
                                                                                                        liveUciMove!!
                                                                                                                .length >=
                                                                                                                4
                                                                                        ) {
                                                                                                liveUciMove!!
                                                                                                        .substring(
                                                                                                                0,
                                                                                                                2
                                                                                                        ) to
                                                                                                        liveUciMove!!
                                                                                                                .substring(
                                                                                                                        2,
                                                                                                                        4
                                                                                                                )
                                                                                        } else null

                                                                                val moveClassEnum =
                                                                                        liveMoveClass
                                                                                                ?.let {
                                                                                                        runCatching {
                                                                                                                        MoveClass
                                                                                                                                .valueOf(
                                                                                                                                        it
                                                                                                                                )
                                                                                                                }
                                                                                                                .getOrNull()
                                                                                                }

                                                                                BoardCanvas(
                                                                                        fen =
                                                                                                liveFen!!,
                                                                                        lastMove =
                                                                                                lastMovePair,
                                                                                        moveClass =
                                                                                                moveClassEnum,
                                                                                        bestMoveUci =
                                                                                                null,
                                                                                        showBestArrow =
                                                                                                false,
                                                                                        isWhiteBottom =
                                                                                                true,
                                                                                        selectedSquare =
                                                                                                null,
                                                                                        legalMoves =
                                                                                                emptySet(),
                                                                                        onSquareClick =
                                                                                                null,
                                                                                        modifier =
                                                                                                Modifier.fillMaxSize()
                                                                                )
                                                                        } else {
                                                                                CircularProgressIndicator(
                                                                                        modifier =
                                                                                                Modifier.size(
                                                                                                        48.dp
                                                                                                ),
                                                                                        strokeWidth =
                                                                                                4.dp,
                                                                                        color =
                                                                                                MaterialTheme
                                                                                                        .colorScheme
                                                                                                        .primary
                                                                                )
                                                                        }
                                                                }
                                                        }
                                                        Spacer(Modifier.height(16.dp))

                                                        OutlinedButton(
                                                                onClick = {
                                                                        analysisJob?.cancel()
                                                                        analysisJob = null
                                                                        showAnalysis = false
                                                                },
                                                                colors =
                                                                        ButtonDefaults
                                                                                .outlinedButtonColors(
                                                                                        contentColor =
                                                                                                MaterialTheme
                                                                                                        .colorScheme
                                                                                                        .error
                                                                                ),
                                                                border =
                                                                        BorderStroke(
                                                                                1.dp,
                                                                                MaterialTheme
                                                                                        .colorScheme
                                                                                        .error
                                                                        ),
                                                                shape = RoundedCornerShape(12.dp),
                                                                modifier =
                                                                        Modifier.fillMaxWidth()
                                                                                .height(48.dp)
                                                        ) { Text(stringResource(R.string.cancel)) }
                                                }
                                        }
                                }
                        }
                }
        }

        if (isDeltaSyncing || isFullSyncing) {
                Dialog(
                        onDismissRequest = {},
                        properties =
                                DialogProperties(
                                        dismissOnBackPress = false,
                                        dismissOnClickOutside = false
                                )
                ) {
                        Card(
                                shape = RoundedCornerShape(16.dp),
                                colors =
                                        CardDefaults.cardColors(
                                                containerColor = MaterialTheme.colorScheme.surface
                                        )
                        ) {
                                Column(
                                        modifier = Modifier.padding(24.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                        CircularProgressIndicator()
                                        Spacer(Modifier.height(16.dp))
                                        Text(
                                                text = syncStatusMessage,
                                                style = MaterialTheme.typography.bodyLarge,
                                                textAlign = TextAlign.Center
                                        )
                                }
                        }
                }
        }
        if (showSettingsDialog) {
                var lichessInput by remember { mutableStateOf(profile.lichessUsername) }
                var chessComInput by remember { mutableStateOf(profile.chessUsername) }
                var isSaving by remember { mutableStateOf(false) }
                var saveError by remember { mutableStateOf<String?>(null) }

                Dialog(
                        onDismissRequest = { if (!isFullSyncing && !isSaving) showSettingsDialog = false },
                        properties = DialogProperties(usePlatformDefaultWidth = false)
                ) {
                        Card(
                                modifier = Modifier.fillMaxWidth(0.95f).padding(16.dp),
                                shape = RoundedCornerShape(24.dp),
                                colors =
                                        CardDefaults.cardColors(
                                                containerColor = MaterialTheme.colorScheme.surface
                                        )
                        ) {
                                Column(
                                        modifier = Modifier.padding(24.dp).fillMaxWidth().verticalScroll(rememberScrollState()),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                        // Header Icon
                                        Box(
                                                modifier =
                                                        Modifier.size(48.dp)
                                                                .clip(CircleShape)
                                                                .background(
                                                                        MaterialTheme.colorScheme
                                                                                .primaryContainer
                                                                ),
                                                contentAlignment = Alignment.Center
                                        ) {
                                                Icon(
                                                        Icons.Default.CloudDownload,
                                                        contentDescription = null,
                                                        tint =
                                                                MaterialTheme.colorScheme
                                                                        .onPrimaryContainer,
                                                        modifier = Modifier.size(24.dp)
                                                )
                                        }

                                        Spacer(Modifier.height(16.dp))

                                        Text(
                                                text = stringResource(R.string.load_games_title),
                                                style = MaterialTheme.typography.headlineSmall,
                                                fontWeight = FontWeight.Bold,
                                                textAlign = TextAlign.Center
                                        )

                                        Spacer(Modifier.height(8.dp))

                                        Text(
                                                text = stringResource(R.string.load_games_desc),
                                                style = MaterialTheme.typography.bodyMedium,
                                                textAlign = TextAlign.Center,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )

                                        Spacer(Modifier.height(24.dp))

                                        // Inputs
                                        OutlinedTextField(
                                            value = lichessInput,
                                            onValueChange = { lichessInput = it },
                                            label = { Text("Lichess Username") },
                                            modifier = Modifier.fillMaxWidth(),
                                            singleLine = true,
                                            shape = RoundedCornerShape(12.dp)
                                        )
                                        
                                        Spacer(Modifier.height(12.dp))
                                        
                                        OutlinedTextField(
                                            value = chessComInput,
                                            onValueChange = { chessComInput = it },
                                            label = { Text("Chess.com Username") },
                                            modifier = Modifier.fillMaxWidth(),
                                            singleLine = true,
                                            shape = RoundedCornerShape(12.dp)
                                        )

                                        Spacer(Modifier.height(24.dp))

                                        // Source Selection (Checkboxes)
                                        Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement =
                                                        Arrangement.SpaceEvenly
                                        ) {
                                                Row(
                                                        verticalAlignment =
                                                                Alignment.CenterVertically
                                                ) {
                                                        Checkbox(
                                                                checked = loadLichess,
                                                                onCheckedChange = {
                                                                        loadLichess = it
                                                                }
                                                        )
                                                        Text("Lichess")
                                                }
                                                Row(
                                                        verticalAlignment =
                                                                Alignment.CenterVertically
                                                ) {
                                                        Checkbox(
                                                                checked = loadChessCom,
                                                                onCheckedChange = {
                                                                        loadChessCom = it
                                                                }
                                                        )
                                                        Text("Chess.com")
                                                }
                                        }
                                        Spacer(Modifier.height(16.dp))

                                        // Date Selection Area
                                        Text(
                                                text = stringResource(R.string.select_date_range),
                                                style = MaterialTheme.typography.titleSmall,
                                                color = MaterialTheme.colorScheme.primary
                                        )

                                        Spacer(Modifier.height(12.dp))

                                        Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                                        ) {
                                                OutlinedButton(
                                                        onClick = { showDatePickerFrom = true },
                                                        shape = RoundedCornerShape(12.dp),
                                                        modifier = Modifier.weight(1f),
                                                        colors =
                                                                ButtonDefaults.outlinedButtonColors(
                                                                        containerColor =
                                                                                if (dateFromMillis !=
                                                                                                null
                                                                                )
                                                                                        MaterialTheme
                                                                                                .colorScheme
                                                                                                .primaryContainer
                                                                                                .copy(
                                                                                                        alpha =
                                                                                                                0.3f
                                                                                                )
                                                                                else
                                                                                        Color.Transparent
                                                                )
                                                ) {
                                                        Text(
                                                                text =
                                                                        if (dateFromMillis != null)
                                                                                dateFormatter.format(
                                                                                        Date(
                                                                                                dateFromMillis!!
                                                                                        )
                                                                                )
                                                                        else
                                                                                stringResource(
                                                                                        R.string
                                                                                                .date_from
                                                                                ),
                                                                maxLines = 1,
                                                                overflow = TextOverflow.Ellipsis
                                                        )
                                                }

                                                OutlinedButton(
                                                        onClick = { showDatePickerUntil = true },
                                                        shape = RoundedCornerShape(12.dp),
                                                        modifier = Modifier.weight(1f),
                                                        colors =
                                                                ButtonDefaults.outlinedButtonColors(
                                                                        containerColor =
                                                                                if (dateUntilMillis !=
                                                                                                null
                                                                                )
                                                                                        MaterialTheme
                                                                                                .colorScheme
                                                                                                .primaryContainer
                                                                                                .copy(
                                                                                                        alpha =
                                                                                                                0.3f
                                                                                                )
                                                                                else
                                                                                        Color.Transparent
                                                                )
                                                ) {
                                                        Text(
                                                                text =
                                                                        if (dateUntilMillis != null)
                                                                                dateFormatter.format(
                                                                                        Date(
                                                                                                dateUntilMillis!!
                                                                                        )
                                                                                )
                                                                        else
                                                                                stringResource(
                                                                                        R.string
                                                                                                .date_until
                                                                                ),
                                                                maxLines = 1,
                                                                overflow = TextOverflow.Ellipsis
                                                        )
                                                }
                                        }

                                        Spacer(Modifier.height(24.dp))

                                        if (saveError != null) {
                                            Text(
                                                text = saveError!!,
                                                color = MaterialTheme.colorScheme.error,
                                                style = MaterialTheme.typography.bodySmall,
                                                modifier = Modifier.padding(bottom = 8.dp)
                                            )
                                        }

                                        Button(
                                                onClick = {
                                                        scope.launch {
                                                                isSaving = true
                                                                saveError = null
                                                                try {
                                                                    val userId = FirebaseAuth.getInstance().currentUser?.uid
                                                                    if (profile.isGuest) {
                                                                        // 🌟 Fix: Save to local preferences for Guest
                                                                        GuestPreferences.setLichessUsername(context, lichessInput)
                                                                        GuestPreferences.setChessUsername(context, chessComInput)
                                                                    } else if (userId != null) {
                                                                        // Save to Firestore for registered users
                                                                        FirebaseFirestore.getInstance().collection("users").document(userId)
                                                                            .update(mapOf(
                                                                                "lichessUsername" to lichessInput, 
                                                                                "chessUsername" to chessComInput
                                                                            ))
                                                                            .await()
                                                                    }
                                                                    
                                                                    fullSyncWithRemote(
                                                                            since = dateFromMillis,
                                                                            until = dateUntilMillis,
                                                                            useLichess = loadLichess,
                                                                            useChessCom = loadChessCom,
                                                                            lichessUser = lichessInput,
                                                                            chessUser = chessComInput
                                                                    )
                                                                    loadFromLocal()
                                                                } catch (e: Exception) {
                                                                    saveError = e.message
                                                                    isSaving = false
                                                                }
                                                        }
                                                },
                                                modifier =
                                                        Modifier.fillMaxWidth().height(50.dp),
                                                shape = RoundedCornerShape(12.dp),
                                                enabled = !isFullSyncing && !isSaving
                                        ) {
                                                if (isFullSyncing || isSaving) {
                                                        CircularProgressIndicator(
                                                                modifier = Modifier.size(24.dp),
                                                                color =
                                                                        MaterialTheme.colorScheme
                                                                                .onPrimary,
                                                                strokeWidth = 2.dp
                                                        )
                                                        Spacer(Modifier.width(8.dp))
                                                        Text(stringResource(R.string.loading))
                                                } else {
                                                        Text(
                                                                stringResource(
                                                                        R.string.load_games_button
                                                                ),
                                                                fontSize = 16.sp,
                                                                fontWeight = FontWeight.Bold
                                                        )
                                                }
                                        }
                                        
                                        if (!isFullSyncing && !isSaving) {
                                            TextButton(
                                                onClick = { showSettingsDialog = false },
                                                modifier = Modifier.padding(top = 8.dp)
                                            ) {
                                                Text(stringResource(R.string.cancel))
                                            }
                                        }
                                }
                        }
                }
        }
        if (showAddDialog) {
                Dialog(onDismissRequest = { showAddDialog = false }) {
                        Card(
                                shape = RoundedCornerShape(24.dp),
                                colors =
                                        CardDefaults.cardColors(
                                                containerColor = MaterialTheme.colorScheme.surface
                                        ),
                                modifier = Modifier.fillMaxWidth().padding(16.dp)
                        ) {
                                Column(
                                        modifier = Modifier.padding(24.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                        Box(
                                                modifier =
                                                        Modifier.size(48.dp)
                                                                .clip(CircleShape)
                                                                .background(
                                                                        MaterialTheme.colorScheme
                                                                                .primaryContainer
                                                                ),
                                                contentAlignment = Alignment.Center
                                        ) {
                                                Icon(
                                                        Icons.Default.UploadFile,
                                                        contentDescription = null,
                                                        tint =
                                                                MaterialTheme.colorScheme
                                                                        .onPrimaryContainer
                                                )
                                        }

                                        Spacer(Modifier.height(16.dp))

                                        Text(
                                                text = stringResource(R.string.upload_pgn),
                                                style =
                                                        MaterialTheme.typography.headlineSmall.copy(
                                                                fontWeight = FontWeight.Bold
                                                        ),
                                                textAlign = TextAlign.Center
                                        )

                                        Spacer(Modifier.height(8.dp))

                                        Text(
                                                text = stringResource(R.string.paste_pgn_hint),
                                                style = MaterialTheme.typography.bodyMedium,
                                                textAlign = TextAlign.Center,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )

                                        Spacer(Modifier.height(24.dp))

                                        OutlinedTextField(
                                                value = pastedPgn,
                                                onValueChange = { pastedPgn = it },
                                                modifier =
                                                        Modifier.fillMaxWidth()
                                                                .heightIn(min = 140.dp),
                                                placeholder = {
                                                        Text(
                                                                stringResource(
                                                                        R.string.paste_pgn_hint
                                                                )
                                                        )
                                                },
                                                shape = RoundedCornerShape(12.dp)
                                        )

                                        Spacer(Modifier.height(16.dp))

                                        OutlinedButton(
                                                onClick = {
                                                        filePicker.launch(
                                                                arrayOf(
                                                                        "application/x-chess-pgn",
                                                                        "text/plain",
                                                                        "text/*"
                                                                )
                                                        )
                                                },
                                                modifier = Modifier.fillMaxWidth(),
                                                shape = RoundedCornerShape(12.dp)
                                        ) {
                                                Icon(
                                                        Icons.Default.FolderOpen,
                                                        contentDescription = null,
                                                        modifier = Modifier.size(18.dp)
                                                )
                                                Spacer(Modifier.width(8.dp))
                                                Text(stringResource(R.string.select_file))
                                        }

                                        Spacer(Modifier.height(24.dp))

                                        Button(
                                                onClick = {
                                                        scope.launch {
                                                                if (pastedPgn.isBlank()) {
                                                                        Toast.makeText(
                                                                                        context,
                                                                                        context.getString(
                                                                                                R.string
                                                                                                        .empty_pgn
                                                                                        ),
                                                                                        Toast.LENGTH_SHORT
                                                                                )
                                                                                .show()
                                                                        return@launch
                                                                }
                                                                runCatching {
                                                                        addManualGame(
                                                                                pgn = pastedPgn,
                                                                                profile = profile,
                                                                                repo = repo
                                                                        )
                                                                        loadFromLocal()
                                                                }
                                                                        .onSuccess {
                                                                                pastedPgn = ""
                                                                                showAddDialog =
                                                                                        false
                                                                                Toast.makeText(
                                                                                                context,
                                                                                                context.getString(
                                                                                                        R.string
                                                                                                                .game_added
                                                                                                ),
                                                                                                Toast.LENGTH_SHORT
                                                                                        )
                                                                                        .show()
                                                                        }
                                                                        .onFailure {
                                                                                Toast.makeText(
                                                                                                context,
                                                                                                context.getString(
                                                                                                        R.string
                                                                                                                .add_error,
                                                                                                        it.message
                                                                                                                ?: ""
                                                                                                ),
                                                                                                Toast.LENGTH_LONG
                                                                                        )
                                                                                        .show()
                                                                        }
                                                        }
                                                },
                                                modifier = Modifier.fillMaxWidth().height(50.dp),
                                                shape = RoundedCornerShape(12.dp)
                                        ) {
                                                Text(
                                                        stringResource(R.string.save),
                                                        style = MaterialTheme.typography.titleMedium
                                                )
                                        }

                                        Spacer(Modifier.height(12.dp))

                                        TextButton(onClick = { showAddDialog = false }) {
                                                Text(stringResource(R.string.cancel))
                                        }
                                }
                        }
                }
        }

        if (showNoAccountsDialog) {
                Dialog(onDismissRequest = { showNoAccountsDialog = false }) {
                        Card(
                                shape = RoundedCornerShape(24.dp),
                                colors =
                                        CardDefaults.cardColors(
                                                containerColor = MaterialTheme.colorScheme.surface
                                        ),
                                modifier = Modifier.fillMaxWidth().padding(16.dp)
                        ) {
                                Column(
                                        modifier = Modifier.padding(24.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                        Box(
                                                modifier =
                                                        Modifier.size(48.dp)
                                                                .clip(CircleShape)
                                                                .background(
                                                                        MaterialTheme.colorScheme
                                                                                .primaryContainer
                                                                ),
                                                contentAlignment = Alignment.Center
                                        ) {
                                                Icon(
                                                        Icons.Default.Info,
                                                        contentDescription = null,
                                                        tint =
                                                                MaterialTheme.colorScheme
                                                                        .onPrimaryContainer
                                                )
                                        }

                                        Spacer(Modifier.height(16.dp))

                                        Text(
                                                text = stringResource(R.string.welcome_title),
                                                style = MaterialTheme.typography.headlineSmall,
                                                fontWeight = FontWeight.Bold,
                                                textAlign = TextAlign.Center
                                        )

                                        Spacer(Modifier.height(8.dp))

                                        Text(
                                                text = stringResource(R.string.welcome_desc_no_accounts),
                                                style = MaterialTheme.typography.bodyMedium,
                                                textAlign = TextAlign.Center,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )

                                        Spacer(Modifier.height(24.dp))

                                        Button(
                                                onClick = { showNoAccountsDialog = false },
                                                modifier = Modifier.fillMaxWidth().height(50.dp),
                                                shape = RoundedCornerShape(12.dp)
                                        ) {
                                                Text(
                                                        stringResource(R.string.ok),
                                                        fontSize = 16.sp,
                                                        fontWeight = FontWeight.Bold
                                                )
                                        }
                                }
                        }
                }
        }

        if (showDatePickerFrom) {
                val datePickerState =
                        rememberDatePickerState(
                                initialSelectedDateMillis = dateFromMillis
                                                ?: System.currentTimeMillis()
                        )
                com.github.movesense.ui.theme.DatePickerTheme {
                        DatePickerDialog(
                                onDismissRequest = { showDatePickerFrom = false },
                                confirmButton = {
                                        TextButton(
                                                onClick = {
                                                        dateFromMillis =
                                                                datePickerState.selectedDateMillis
                                                        showDatePickerFrom = false
                                                }
                                        ) { Text(stringResource(R.string.ok)) }
                                },
                                dismissButton = {
                                        TextButton(onClick = { showDatePickerFrom = false }) {
                                                Text(stringResource(R.string.cancel))
                                        }
                                }
                        ) { DatePicker(state = datePickerState) }
                }
        }

        if (showDatePickerUntil) {
                val datePickerState =
                        rememberDatePickerState(
                                initialSelectedDateMillis = dateUntilMillis
                                                ?: System.currentTimeMillis()
                        )
                com.github.movesense.ui.theme.DatePickerTheme {
                        DatePickerDialog(
                                onDismissRequest = { showDatePickerUntil = false },
                                confirmButton = {
                                        TextButton(
                                                onClick = {
                                                        dateUntilMillis =
                                                                datePickerState.selectedDateMillis
                                                        showDatePickerUntil = false
                                                }
                                        ) { Text(stringResource(R.string.ok)) }
                                },
                                dismissButton = {
                                        TextButton(onClick = { showDatePickerUntil = false }) {
                                                Text(stringResource(R.string.cancel))
                                        }
                                }
                        ) { DatePicker(state = datePickerState) }
                }
        }

        if (showPaywall) {
                PaywallDialog(
                        onDismiss = { showPaywall = false },
                        onPurchaseSuccess = {
                                showPaywall = false
                                Toast.makeText(
                                                context,
                                                context.getString(R.string.premium_activated),
                                                Toast.LENGTH_LONG
                                        )
                                        .show()
                        }
                )
        }

        if (showNoAccountsDialog) {
                AlertDialog(
                        onDismissRequest = { /* No dismiss on outside click for welcome */ },
                        title = { Text(stringResource(R.string.welcome_title)) },
                        text = {
                                Column {
                                        Text(stringResource(R.string.welcome_desc_new))
                                }
                        },
                        confirmButton = {
                                Button(
                                    onClick = {
                                        showNoAccountsDialog = false
                                        onStartOnboarding()
                                    }
                                ) {
                                        Text(stringResource(R.string.start_tutorial))
                                }
                        },
                        dismissButton = {
                                TextButton(onClick = { showNoAccountsDialog = false }) {
                                        Text(stringResource(R.string.skip_tutorial))
                                }
                        },
                        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)
                )
        }
}

@Composable
private fun CompactPremiumBanner(onUpgradeClick: () -> Unit, modifier: Modifier = Modifier) {
        Surface(
                modifier =
                        modifier.clickable(onClick = onUpgradeClick)
                                .clip(RoundedCornerShape(24.dp)),
                color = Color(0xFFFFD700),
                shadowElevation = 6.dp
        ) {
                Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                ) {
                        Icon(
                                painter = painterResource(id = R.drawable.icon_crown),
                                contentDescription = null,
                                tint = Color.Black,
                                modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                                text = stringResource(R.string.upgrade_to_premium),
                                style = MaterialTheme.typography.labelLarge,
                                color = Color.Black,
                                fontWeight = FontWeight.Bold
                        )
                }
        }
}

@Composable
private fun MiniEvalBar(
        positions: List<PositionEval>,
        currentPlyIndex: Int,
        modifier: Modifier = Modifier
) {
        Box(modifier = modifier.clip(RoundedCornerShape(6.dp)).background(Color(0xFF2B2A27))) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                        val height = size.height
                        val width = size.width

                        if (positions.isEmpty()) {
                                return@Canvas
                        }

                        val safeIndex = currentPlyIndex.coerceIn(0, positions.lastIndex)
                        val pos = positions[safeIndex]
                        val line = pos.lines.firstOrNull()

                        val evalCp =
                                when {
                                        line?.cp != null -> line.cp.toFloat()
                                        line?.mate != null -> if (line.mate!! > 0) 3000f else -3000f
                                        else -> 0f
                                }

                        val clamped = evalCp.coerceIn(-800f, 800f)
                        val whiteRatio = (clamped + 800f) / 1600f

                        val whiteHeight = height * whiteRatio
                        val blackHeight = height - whiteHeight

                        drawRect(
                                color = Color(0xFF1E1E1E),
                                topLeft = Offset(0f, 0f),
                                size = Size(width, blackHeight)
                        )

                        drawRect(
                                color = Color(0xFFF5F5F5),
                                topLeft = Offset(0f, blackHeight),
                                size = Size(width, whiteHeight)
                        )

                        drawLine(
                                color = Color(0xFF666666),
                                start = Offset(0f, height / 2f),
                                end = Offset(width, height / 2f),
                                strokeWidth = 1.5.dp.toPx()
                        )

                        val currentY = height - whiteHeight
                        drawLine(
                                color = if (evalCp > 0) Color(0xFF4CAF50) else Color(0xFFF44336),
                                start = Offset(0f, currentY),
                                end = Offset(width, currentY),
                                strokeWidth = 2.dp.toPx()
                        )
                }
        }
}

private fun parseGameEnd(pgn: String?, result: String?): GameEndInfo {
        if (pgn.isNullOrBlank()) return GameEndInfo(GameTermination.UNKNOWN, null)

        val terminationTag =
                Regex("""\[Termination\s+"([^"]+)"]""")
                        .find(pgn)
                        ?.groupValues
                        ?.getOrNull(1)
                        ?.lowercase()
                        ?: ""

        val resultTag =
                result
                        ?: Regex("""\[Result\s+"([^"]+)"]""").find(pgn)?.groupValues?.getOrNull(1)
                                ?: "*"

        val winner =
                when (resultTag) {
                        "1-0" -> "white"
                        "0-1" -> "black"
                        else -> null
                }

        // ИСПРАВЛЕНИЕ: Вернули "normal" - проблема была в неправильной обработке мата в
        // WinPercentage.kt
        // Теперь WinPercentage правильно обрабатывает mate:0 и другие случаи мата
        val termination =
                when {
                        "normal" in terminationTag && resultTag != "1/2-1/2" ->
                                GameTermination.CHECKMATE
                        "checkmate" in terminationTag -> GameTermination.CHECKMATE
                        "time forfeit" in terminationTag -> GameTermination.TIMEOUT
                        "timeout" in terminationTag -> GameTermination.TIMEOUT
                        "time" in terminationTag && resultTag != "1/2-1/2" ->
                                GameTermination.TIMEOUT
                        "abandoned" in terminationTag -> GameTermination.RESIGNATION
                        "resignation" in terminationTag -> GameTermination.RESIGNATION
                        "resign" in terminationTag -> GameTermination.RESIGNATION
                        "stalemate" in terminationTag -> GameTermination.STALEMATE
                        "insufficient material" in terminationTag -> GameTermination.INSUFFICIENT
                        "50" in terminationTag && "move" in terminationTag ->
                                GameTermination.FIFTY_MOVE
                        "repetition" in terminationTag -> GameTermination.REPETITION
                        "threefold" in terminationTag -> GameTermination.REPETITION
                        "agreement" in terminationTag || "agreed" in terminationTag ->
                                GameTermination.AGREEMENT
                        resultTag == "1/2-1/2" && terminationTag.isNotBlank() ->
                                GameTermination.DRAW
                        resultTag == "1-0" || resultTag == "0-1" -> GameTermination.UNKNOWN
                        resultTag == "1/2-1/2" -> GameTermination.DRAW
                        else -> GameTermination.UNKNOWN
                }

        return GameEndInfo(termination, winner)
}

@Composable
private fun formatGameEndText(endInfo: GameEndInfo, game: GameHeader): String {
        val context = LocalContext.current
        val white = game.white ?: context.getString(R.string.white)
        val black = game.black ?: context.getString(R.string.black)

        return when (endInfo.termination) {
                GameTermination.CHECKMATE -> {
                        when (endInfo.winner) {
                                "white" -> context.getString(R.string.checkmate_by, white.take(12))
                                "black" -> context.getString(R.string.checkmate_by, black.take(12))
                                else -> context.getString(R.string.checkmate)
                        }
                }
                GameTermination.TIMEOUT -> {
                        when (endInfo.winner) {
                                "white" -> context.getString(R.string.timeout_lost, black.take(12))
                                "black" -> context.getString(R.string.timeout_lost, white.take(12))
                                else -> context.getString(R.string.timeout)
                        }
                }
                GameTermination.RESIGNATION -> {
                        when (endInfo.winner) {
                                "white" -> context.getString(R.string.resigned, black.take(12))
                                "black" -> context.getString(R.string.resigned, white.take(12))
                                else -> context.getString(R.string.resignation)
                        }
                }
                GameTermination.STALEMATE -> context.getString(R.string.stalemate)
                GameTermination.DRAW, GameTermination.AGREEMENT -> context.getString(R.string.draw)
                GameTermination.INSUFFICIENT -> context.getString(R.string.insufficient_material)
                GameTermination.REPETITION -> context.getString(R.string.threefold_repetition)
                GameTermination.FIFTY_MOVE -> context.getString(R.string.fifty_move_rule)
                GameTermination.UNKNOWN -> ""
        }
}

private fun parsePlayerInfo(name: String?, pgn: String?, isWhite: Boolean): PlayerInfo {
        val base = name.orEmpty()
        if (pgn.isNullOrBlank()) return PlayerInfo(base, null)
        val tag = if (isWhite) """\[WhiteTitle\s+"([^"]+)"]""" else """\[BlackTitle\s+"([^"]+)"]"""
        val rx = Regex(tag)
        val title = rx.find(pgn)?.groupValues?.getOrNull(1)?.uppercase()
        return PlayerInfo(base, if (title.isNullOrBlank() || title == "NONE") null else title)
}

private fun getUserTitle(
        profile: UserProfile,
        whiteInfo: PlayerInfo,
        blackInfo: PlayerInfo
): String? {
        val me =
                listOf(

                                profile.lichessUsername.trim(),
                                profile.chessUsername.trim()
                        )
                        .filter { it.isNotBlank() }
                        .map { it.lowercase() }
        val w = whiteInfo.name.trim().lowercase()
        val b = blackInfo.name.trim().lowercase()

        return when {
                w.isNotBlank() && me.any { it == w } -> whiteInfo.title
                b.isNotBlank() && me.any { it == b } -> blackInfo.title
                else -> null
        }
}

@Composable
private fun PlayerName(
        info: PlayerInfo,
        modifier: Modifier = Modifier,
        textAlign: TextAlign? = null,
        maxLines: Int = 1,
        overflow: TextOverflow = TextOverflow.Ellipsis
) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
                if (info.title != null) {
                        Text(
                                text = info.title.uppercase(Locale.getDefault()),
                                style =
                                        MaterialTheme.typography.bodySmall.copy(
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = Color.White
                                        ),
                                modifier =
                                        Modifier.background(
                                                        Color(0xFFD32F2F),
                                                        RoundedCornerShape(4.dp)
                                                )
                                                .padding(horizontal = 4.dp, vertical = 2.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                }
                Text(
                        text = info.name,
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                        maxLines = maxLines,
                        overflow = overflow,
                        textAlign = textAlign
                )
        }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CompactGameCard(
        game: GameHeader,
        profile: UserProfile,
        analyzedReport: FullReport?,
        index: Int,
        isAnalyzing: Boolean,
        onClick: () -> Unit,
        onLongPress: () -> Unit
) {
        val context = LocalContext.current
        val mySide: Boolean? = guessMySide(profile, game)
        val userWon =
                mySide != null &&
                        ((mySide && game.result == "1-0") || (!mySide && game.result == "0-1"))
        val userLost =
                mySide != null &&
                        ((mySide && game.result == "0-1") || (!mySide && game.result == "1-0"))
        val isAnalyzed = analyzedReport != null

        val gameEndInfo = remember(game.pgn, game.result) { parseGameEnd(game.pgn, game.result) }
        val endText = formatGameEndText(gameEndInfo, game)

        var pressed by remember { mutableStateOf(false) }
        val scale by
                animateFloatAsState(
                        targetValue = if (pressed) 0.98f else 1f,
                        animationSpec = spring(stiffness = Spring.StiffnessMedium),
                        label = "scale"
                )

        val whiteInfo =
                remember(game.white, game.pgn) {
                        parsePlayerInfo(game.white, game.pgn, isWhite = true)
                }
        val blackInfo =
                remember(game.black, game.pgn) {
                        parsePlayerInfo(game.black, game.pgn, isWhite = false)
                }

        val userTitle =
                remember(profile, whiteInfo, blackInfo) {
                        getUserTitle(profile, whiteInfo, blackInfo)
                }
        val opponentTitle =
                when (mySide) {
                        true -> blackInfo.title
                        false -> whiteInfo.title
                        null -> null
                }

        val isWinVsTitled = userWon && (userTitle == null) && (opponentTitle != null)

        Box(
                contentAlignment = Alignment.TopCenter,
                modifier =
                        Modifier.fillMaxWidth()
                                .padding(
                                        start = 16.dp,
                                        end = 16.dp,
                                        top = if (isWinVsTitled) 20.dp else 6.dp,
                                        bottom = 6.dp
                                )
                                .scale(scale)
                                .combinedClickable(
                                        enabled = !isAnalyzing,
                                        onClick = {
                                                pressed = true
                                                onClick()
                                                pressed = false
                                        },
                                        onLongClick = { if (isAnalyzed) onLongPress() }
                                )
        ) {
                Card(
                        colors =
                                CardDefaults.cardColors(
                                        containerColor =
                                                when {
                                                        userWon -> Color(0xFFE8F5E9)
                                                        userLost -> Color(0xFFFFEBEE)
                                                        else ->
                                                                MaterialTheme.colorScheme
                                                                        .surfaceVariant
                                                }
                                ),
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                        border = if (isWinVsTitled) BorderStroke(2.dp, Color(0xFFFFD700)) else null,
                        shape = RoundedCornerShape(16.dp),
                        modifier =
                                Modifier.fillMaxWidth()
                                        .height(if (endText.isNotBlank()) 172.dp else 152.dp)
                ) {
                        val siteName =
                                when (game.site) {
                                        Provider.LICHESS -> stringResource(R.string.filter_lichess)
                                        Provider.CHESSCOM ->
                                                stringResource(R.string.filter_chesscom)
                                        Provider.MANUAL, Provider.BOT ->
                                                stringResource(R.string.filter_manual)
                                        null -> ""
                                }
                        val (modeLabel, openingLine) = deriveModeAndOpening(game, context)

                        Column(Modifier.padding(12.dp)) {
                                Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                ) {
                                        Text(
                                                buildAnnotatedString {
                                                        withStyle(
                                                                SpanStyle(
                                                                        fontWeight =
                                                                                FontWeight.Medium,
                                                                        fontSize = 11.sp
                                                                )
                                                        ) { append(siteName) }
                                                        if (!game.date.isNullOrBlank()) {
                                                                append(" • ")
                                                                append(game.date!!)
                                                        }
                                                        if (modeLabel.isNotBlank()) {
                                                                append(" • ")
                                                                append(modeLabel)
                                                        }
                                                },
                                                style =
                                                        MaterialTheme.typography.bodySmall.copy(
                                                                fontSize = 11.sp
                                                        ),
                                                color =
                                                        MaterialTheme.colorScheme.onSurface.copy(
                                                                alpha = 0.7f
                                                        ),
                                                modifier = Modifier.weight(1f),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                        )
                                        if (isAnalyzed) {
                                                Badge(
                                                        containerColor = Color(0xFF4CAF50),
                                                        contentColor = Color.White
                                                ) {
                                                        Row(
                                                                modifier =
                                                                        Modifier.padding(
                                                                                horizontal = 10.dp,
                                                                                vertical = 4.dp
                                                                        ),
                                                                verticalAlignment =
                                                                        Alignment.CenterVertically,
                                                                horizontalArrangement =
                                                                        Arrangement.spacedBy(4.dp)
                                                        ) {
                                                                Icon(
                                                                        Icons.Default.CheckCircle,
                                                                        contentDescription = null,
                                                                        tint = Color.White,
                                                                        modifier =
                                                                                Modifier.size(14.dp)
                                                                )
                                                                Text(
                                                                        stringResource(
                                                                                R.string.analyzed
                                                                        ),
                                                                        fontSize = 11.sp,
                                                                        fontWeight = FontWeight.Bold
                                                                )
                                                        }
                                                }
                                        }
                                }

                                Spacer(Modifier.height(6.dp))
                                Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        modifier = Modifier.fillMaxWidth()
                                ) {
                                        Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                modifier = Modifier.weight(1f)
                                        ) {
                                                UserBubble(name = game.white ?: "W", size = 22.dp)
                                                Spacer(Modifier.width(6.dp))
                                                PlayerName(
                                                        info = whiteInfo,
                                                        modifier = Modifier.weight(1f, fill = false)
                                                )
                                        }
                                        Text(
                                                game.result.orEmpty(),
                                                style =
                                                        MaterialTheme.typography.bodySmall.copy(
                                                                fontWeight = FontWeight.Bold,
                                                                fontSize = 12.sp
                                                        ),
                                                modifier = Modifier.padding(horizontal = 8.dp)
                                        )
                                        Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                modifier = Modifier.weight(1f),
                                                horizontalArrangement = Arrangement.End
                                        ) {
                                                PlayerName(
                                                        info = blackInfo,
                                                        textAlign = TextAlign.End,
                                                        modifier = Modifier.weight(1f, fill = false)
                                                )
                                                Spacer(Modifier.width(6.dp))
                                                UserBubble(name = game.black ?: "B", size = 22.dp)
                                        }
                                }

                                if (endText.isNotBlank()) {
                                        Spacer(Modifier.height(6.dp))
                                        Surface(
                                                color =
                                                        when (gameEndInfo.termination) {
                                                                GameTermination.CHECKMATE ->
                                                                        Color(0xFFFFE0B2)
                                                                                .copy(alpha = 0.6f)
                                                                GameTermination.TIMEOUT ->
                                                                        Color(0xFFFFCDD2)
                                                                                .copy(alpha = 0.6f)
                                                                GameTermination.RESIGNATION ->
                                                                        Color(0xFFE0E0E0)
                                                                                .copy(alpha = 0.6f)
                                                                else ->
                                                                        Color(0xFFB3E5FC)
                                                                                .copy(alpha = 0.6f)
                                                        },
                                                shape = RoundedCornerShape(6.dp)
                                        ) {
                                                Text(
                                                        text = endText,
                                                        style =
                                                                MaterialTheme.typography.bodySmall
                                                                        .copy(
                                                                                fontSize = 11.sp,
                                                                                fontWeight =
                                                                                        FontWeight
                                                                                                .Medium
                                                                        ),
                                                        color =
                                                                MaterialTheme.colorScheme.onSurface
                                                                        .copy(alpha = 0.85f),
                                                        modifier =
                                                                Modifier.padding(
                                                                        horizontal = 8.dp,
                                                                        vertical = 4.dp
                                                                ),
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                )
                                        }
                                }

                                if (openingLine.isNotBlank()) {
                                        Spacer(Modifier.height(4.dp))
                                        Text(
                                                openingLine,
                                                style =
                                                        MaterialTheme.typography.bodySmall.copy(
                                                                fontSize = 10.sp
                                                        ),
                                                color =
                                                        MaterialTheme.colorScheme.onSurface.copy(
                                                                alpha = 0.6f
                                                        ),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                        )
                                }

                                Spacer(Modifier.height(8.dp))
                                Surface(
                                        color = MaterialTheme.colorScheme.surface,
                                        shape = RoundedCornerShape(12.dp)
                                ) {
                                        Row(
                                                modifier =
                                                        Modifier.fillMaxWidth()
                                                                .padding(
                                                                        horizontal = 10.dp,
                                                                        vertical = 6.dp
                                                                ),
                                                horizontalArrangement = Arrangement.SpaceEvenly,
                                                verticalAlignment = Alignment.CenterVertically
                                        ) {
                                                StatColumn(
                                                        accuracy =
                                                                analyzedReport
                                                                        ?.accuracy
                                                                        ?.whiteMovesAcc
                                                                        ?.itera,
                                                        performance =
                                                                analyzedReport
                                                                        ?.estimatedElo
                                                                        ?.whiteEst
                                                )
                                                Box(
                                                        modifier =
                                                                Modifier.width(1.dp)
                                                                        .height(24.dp)
                                                                        .background(
                                                                                MaterialTheme
                                                                                        .colorScheme
                                                                                        .outline
                                                                                        .copy(
                                                                                                alpha =
                                                                                                        0.25f
                                                                                        )
                                                                        )
                                                )
                                                StatColumn(
                                                        accuracy =
                                                                analyzedReport
                                                                        ?.accuracy
                                                                        ?.blackMovesAcc
                                                                        ?.itera,
                                                        performance =
                                                                analyzedReport
                                                                        ?.estimatedElo
                                                                        ?.blackEst
                                                )
                                        }
                                }
                        }
                }

                if (isWinVsTitled) {
                        /*
                        /*
                        Image(
                            painter = painterResource(id = R.drawable.icon_crown),
                            contentDescription = stringResource(id = R.string.victory_vs_titled),
                            modifier = Modifier
                                .zIndex(1f)
                                .size(24.dp)
                                .align(Alignment.TopCenter)
                                .offset(y = (-12).dp)
                        )
                        */
                        */
                }
        }
}

@Composable
private fun StatColumn(accuracy: Double?, performance: Int?) {
        val accText = if (accuracy != null) "%.1f%%".format(accuracy) else "—"
        val perfText = performance?.toString() ?: "—"
        Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                        accText,
                        style =
                                MaterialTheme.typography.bodySmall.copy(
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp
                                ),
                        color =
                                if (accuracy != null) getAccuracyColor(accuracy)
                                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
                Text(
                        " • $perfText",
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                        color =
                                if (performance != null) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
        }
}

private fun getAccuracyColor(accuracy: Double): Color =
        when {
                accuracy >= 90 -> Color(0xFF2E7D32)
                accuracy >= 80 -> Color(0xFF558B2F)
                accuracy >= 70 -> Color(0xFFF9A825)
                accuracy >= 60 -> Color(0xFFEF6C00)
                else -> Color(0xFFC62828)
        }

@Composable
private fun UserBubble(
        name: String,
        size: Dp,
        bg: Color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
        fg: Color = MaterialTheme.colorScheme.primary
) {
        val letter = name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
        Box(
                modifier = Modifier.size(size).clip(CircleShape).background(bg),
                contentAlignment = Alignment.Center
        ) {
                Text(
                        text = letter,
                        color = fg,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        fontWeight = FontWeight.SemiBold
                )
        }
}

private fun guessMySide(profile: UserProfile, game: GameHeader): Boolean? {
        val me =
                listOf(

                                profile.lichessUsername.trim(),
                                profile.chessUsername.trim()
                        )
                        .filter { it.isNotBlank() }
                        .map { it.lowercase() }
        val w = game.white?.trim()?.lowercase()
        val b = game.black?.trim()?.lowercase()
        return when {
                w != null && me.any { it == w } -> true
                b != null && me.any { it == b } -> false
                else -> null
        }
}

private fun deriveModeAndOpening(game: GameHeader, context: Context): Pair<String, String> {
        val pgn = game.pgn
        var mode = ""
        var openingLine = game.opening ?: game.eco ?: ""

        if (!pgn.isNullOrBlank()) {
                val tc =
                        Regex("""\[TimeControl\s+"([^"]+)"]""").find(pgn)?.groupValues?.getOrNull(1)
                mode = tc?.let { mapTimeControlToMode(it, context) } ?: ""
                if (openingLine.isBlank()) {
                        val op =
                                Regex("""\[Opening\s+"([^"]+)"]""")
                                        .find(pgn)
                                        ?.groupValues
                                        ?.getOrNull(1)
                        val eco =
                                Regex("""\[ECO\s+"([^"]+)"]""").find(pgn)?.groupValues?.getOrNull(1)
                        openingLine =
                                when {
                                        !op.isNullOrBlank() && !eco.isNullOrBlank() -> "$op ($eco)"
                                        !op.isNullOrBlank() -> op
                                        !eco.isNullOrBlank() -> eco
                                        else -> ""
                                }
                }
        }
        return mode to (openingLine ?: "")
}

private fun mapTimeControlToMode(tc: String, context: Context): String {
        val main = tc.substringBefore('+', tc).toIntOrNull() ?: return ""
        return when {
                main <= 60 -> context.getString(R.string.bullet)
                main <= 300 -> context.getString(R.string.blitz)
                main <= 1500 -> context.getString(R.string.rapid)
                else -> context.getString(R.string.classical)
        }
}

private suspend fun addManualGame(
        pgn: String,
        profile: UserProfile,
        repo: com.github.movesense.data.local.GameRepository
) {
        val header = runCatching { PgnChess.headerFromPgn(pgn) }.getOrNull()
        val tags = parseTags(pgn)

        val white =
                header?.white
                        ?: tags["White"]
                                ?: Regex("""\[White\s+"([^"]+)"]""")
                                .find(pgn)
                                ?.groupValues
                                ?.getOrNull(1)
                                ?: "Unknown"

        val black =
                header?.black
                        ?: tags["Black"]
                                ?: Regex("""\[Black\s+"([^"]+)"]""")
                                .find(pgn)
                                ?.groupValues
                                ?.getOrNull(1)
                                ?: "Unknown"

        val result =
                header?.result
                        ?: tags["Result"]
                                ?: Regex("""\[Result\s+"([^"]+)"]""")
                                .find(pgn)
                                ?.groupValues
                                ?.getOrNull(1)
                                ?: "*"

        val date =
                header?.date
                        ?: tags["UTCDate"] ?: tags["Date"]
                                ?: Regex("""\[(?:UTC)?Date\s+"([^"]+)"]""")
                                .find(pgn)
                                ?.groupValues
                                ?.getOrNull(1)

        val whiteElo =
                header?.whiteElo
                        ?: tags["WhiteElo"]?.toIntOrNull()
                                ?: Regex("""\[WhiteElo\s+"([^"]+)"]""")
                                .find(pgn)
                                ?.groupValues
                                ?.getOrNull(1)
                                ?.toIntOrNull()

        val blackElo =
                header?.blackElo
                        ?: tags["BlackElo"]?.toIntOrNull()
                                ?: Regex("""\[BlackElo\s+"([^"]+)"]""")
                                .find(pgn)
                                ?.groupValues
                                ?.getOrNull(1)
                                ?.toIntOrNull()

        val opening =
                header?.opening
                        ?: tags["Opening"]
                                ?: Regex("""\[Opening\s+"([^"]+)"]""")
                                .find(pgn)
                                ?.groupValues
                                ?.getOrNull(1)

        val eco =
                header?.eco
                        ?: tags["ECO"]
                                ?: Regex("""\[ECO\s+"([^"]+)"]""")
                                .find(pgn)
                                ?.groupValues
                                ?.getOrNull(1)

        val site =
                tags["Site"]
                        ?: Regex("""\[Site\s+"([^"]+)"]""").find(pgn)?.groupValues?.getOrNull(1)

        val provider =
                when {
                        site?.contains("lichess.org", ignoreCase = true) == true -> Provider.LICHESS
                        site?.contains("chess.com", ignoreCase = true) == true -> Provider.CHESSCOM
                        else -> Provider.BOT
                }

        val sideToView =
                guessMySide(
                        profile,
                        GameHeader(
                                site = provider,
                                pgn = pgn,
                                white = white,
                                black = black,
                                result = result,
                                date = date,
                                sideToView = null,
                                opening = opening,
                                eco = eco,
                                whiteElo = whiteElo,
                                blackElo = blackElo
                        )
                )

        val gh =
                GameHeader(
                        site = provider,
                        pgn = pgn,
                        white = white,
                        black = black,
                        result = result,
                        date = date,
                        sideToView = sideToView,
                        opening = opening,
                        eco = eco,
                        whiteElo = whiteElo,
                        blackElo = blackElo
                )

        Log.d(
                TAG,
                "📝 Adding manual game: $white ($whiteElo) vs $black ($blackElo), date=$date, provider=$provider"
        )

        repo.mergeExternal(provider, listOf(gh))
}

private fun parseTags(pgn: String): Map<String, String> {
        val tagPattern = Regex("""\[(\w+)\s+"([^"]*)"\]""")
        return tagPattern.findAll(pgn).associate { it.groupValues[1] to it.groupValues[2] }
}
