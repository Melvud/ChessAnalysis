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
import androidx.compose.foundation.Image // <-- ДОБАВЛЕН ИМПОРТ
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Settings
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
import androidx.compose.ui.res.painterResource // <-- ДОБАВЛЕН ИМПОРТ
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
import androidx.compose.ui.zIndex // <-- ДОБАВЛЕН ИМПОРТ
import com.github.movesense.EngineClient.analyzeGameByPgnWithProgress
import com.github.movesense.FullReport
import com.github.movesense.GameHeader
import com.github.movesense.LineEval
import com.github.movesense.MoveClass
import com.github.movesense.PgnChess
import com.github.movesense.PositionEval
import com.github.movesense.Provider
import com.github.movesense.data.local.gameRepository
import com.github.movesense.ui.UserProfile
import com.github.movesense.ui.components.BoardCanvas
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlin.math.max
import kotlin.math.roundToLong
import com.github.movesense.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TAG = "GamesListScreen"
private const val PREFS_NAME = "games_list_prefs"
private const val KEY_SHOW_EVAL_BAR = "show_eval_bar"

enum class GameFilter { ALL, LICHESS, CHESSCOM, MANUAL }

// Типы завершения партии
enum class GameTermination {
    CHECKMATE,      // Мат
    TIMEOUT,        // Время
    RESIGNATION,    // Сдача
    DRAW,           // Ничья
    STALEMATE,      // Пат
    AGREEMENT,      // По соглашению
    INSUFFICIENT,   // Недостаточно материала
    REPETITION,     // Троекратное повторение
    FIFTY_MOVE,     // Правило 50 ходов
    UNKNOWN         // Неизвестно
}

data class GameEndInfo(
    val termination: GameTermination,
    val winner: String? // "white", "black" или null для ничьих
)

// --- НОВЫЙ data class для титулов ---
private data class PlayerInfo(val name: String, val title: String?)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun GamesListScreen(
    profile: UserProfile,
    games: List<GameHeader>,
    openingFens: Set<String>,
    isFirstLoad: Boolean,
    onFirstLoadComplete: () -> Unit,
    onOpenReport: (FullReport) -> Unit
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val json = remember { Json { ignoreUnknownKeys = true; explicitNulls = false } }
    val repo = remember { context.gameRepository(json) }

    val prefs = remember { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }
    var showEvalBar by remember { mutableStateOf(prefs.getBoolean(KEY_SHOW_EVAL_BAR, false)) }
    var showSettingsDialog by remember { mutableStateOf(false) }

    var items by remember { mutableStateOf<List<GameHeader>>(emptyList()) }
    var analyzedGames by remember { mutableStateOf<Map<String, FullReport>>(emptyMap()) }

    var currentFilter by remember { mutableStateOf(GameFilter.ALL) }

    var showAnalysis by remember { mutableStateOf(false) }
    var liveFen by remember { mutableStateOf<String?>(null) }
    var liveUciMove by remember { mutableStateOf<String?>(null) }
    var liveMoveClass by remember { mutableStateOf<String?>(null) }
    var analysisProgress by remember { mutableStateOf(0f) }
    var analysisStage by remember { mutableStateOf<String?>(null) }
    var livePositions by remember { mutableStateOf<List<PositionEval>>(emptyList()) }
    var currentPlyForEval by remember { mutableStateOf(0) }

    // Новые состояния для анимации ходов
    var animatedMoveIndex by remember { mutableStateOf(0) }
    var allGameMoves by remember { mutableStateOf<List<Triple<String, String, String>>>(emptyList()) } // (fen, uci, san)
    var isServerMode by remember { mutableStateOf(false) }
    var analysisCompleted by remember { mutableStateOf(false) }
    var completedReport by remember { mutableStateOf<FullReport?>(null) }

    // 🔵 ETA: ... (остается без изменений)
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

    var showReAnalyzeSheet by remember { mutableStateOf(false) }
    var reAnalyzeDepth by remember { mutableStateOf(14) }
    var reAnalyzeMultiPv by remember { mutableStateOf(2) }
    var reAnalyzeTargetPgn by remember { mutableStateOf<String?>(null) }

    // --- НОВЫЕ Состояния для Загрузки ---
    var isDeltaSyncing by remember { mutableStateOf(false) } // Для Pull-to-refresh
    var isFullSyncing by remember { mutableStateOf(false) } // Для диалога загрузки
    var fullSyncProgress by remember { mutableStateOf<Float?>(null) }
    var fullSyncMessage by remember { mutableStateOf("") }

    // Состояние для Date Pickers
    var showDatePickerFrom by remember { mutableStateOf(false) }
    var showDatePickerUntil by remember { mutableStateOf(false) }
    var dateFromMillis by remember { mutableStateOf<Long?>(null) }
    var dateUntilMillis by remember { mutableStateOf<Long?>(null) }

    val dateFormatter = remember { SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()) }


    // ... (playMoveSound, pieceAtFen, isCapture - остаются без изменений) ...
    fun playMoveSound(cls: MoveClass?, isCapture: Boolean) {
        val resId = when {
            cls == MoveClass.INACCURACY || cls == MoveClass.MISTAKE || cls == MoveClass.BLUNDER -> R.raw.error
            isCapture -> R.raw.capture
            else -> R.raw.move
        }
        runCatching {
            MediaPlayer.create(context, resId)?.apply {
                setOnCompletionListener { it.release() }
                start()
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
        val pieceFrom = pieceAtFen(prevFen, from)
        val pieceTo = pieceAtFen(prevFen, to)
        if (pieceTo != null) return true
        val isPawn = pieceFrom != null && (pieceFrom == 'P' || pieceFrom == 'p')
        val fromFile = from[0]
        val toFile = to[0]
        return isPawn && fromFile != toFile
    }

    suspend fun loadFromLocal() {
        Log.d(TAG, "loadFromLocal: starting...")
        items = repo.getAllHeaders()
        Log.d(TAG, "loadFromLocal: loaded ${items.size} games")

        val analyzed = mutableMapOf<String, FullReport>()
        items.forEach { game ->
            game.pgn?.let { pgn ->
                repo.getCachedReport(pgn)?.let { report ->
                    analyzed[repo.pgnHash(pgn)] = report
                }
            }
        }
        analyzedGames = analyzed
        Log.d(TAG, "loadFromLocal: ${analyzed.size} games have cached analysis")
    }

    // --- НОВАЯ ФУНКЦИЯ: Дельта-загрузка (только новые) ---
    suspend fun deltaSyncWithRemote() {
        try {
            Log.d(TAG, "deltaSyncWithRemote: starting...")
            var addedCount = 0

            if (profile.lichessUsername.isNotEmpty()) {
                // 1. Узнаем время последней игры Lichess в БД
                val since = repo.getNewestGameTimestamp(Provider.LICHESS)
                Log.d(TAG, "Fetching Lichess games since: $since")
                val lichessList = com.github.movesense.GameLoaders.loadLichess(
                    profile.lichessUsername,
                    since = since,
                    max = null // Загружаем ВСЕ новые
                )
                Log.d(TAG, "Lichess returned ${lichessList.size} new games")
                val added = repo.mergeExternal(Provider.LICHESS, lichessList)
                addedCount += added
            }

            if (profile.chessUsername.isNotEmpty()) {
                // 2. Узнаем время последней игры Chess.com в БД
                val since = repo.getNewestGameTimestamp(Provider.CHESSCOM)
                Log.d(TAG, "Fetching Chess.com games since: $since")
                val chessList = com.github.movesense.GameLoaders.loadChessCom(
                    profile.chessUsername,
                    since = since,
                    max = null, // Загружаем ВСЕ новые
                    onProgress = { /* Прогресс не показываем, т.к. должно быть быстро */ }
                )
                Log.d(TAG, "Chess.com returned ${chessList.size} new games")
                val added = repo.mergeExternal(Provider.CHESSCOM, chessList)
                addedCount += added
            }

            Log.d(TAG, "deltaSyncWithRemote: total added = $addedCount")

            if (addedCount > 0) {
                Toast.makeText(
                    context,
                    context.getString(R.string.games_added, addedCount),
                    Toast.LENGTH_SHORT
                ).show()
            }
        } catch (e: Throwable) {
            Log.e(TAG, "deltaSyncWithRemote failed: ${e.message}", e)
            Toast.makeText(
                context,
                context.getString(R.string.sync_error, e.message ?: ""),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    // --- НОВАЯ ФУНКЦИЯ: Полная загрузка (с прогрессом) ---
    suspend fun fullSyncWithRemote(since: Long?, until: Long?) {
        try {
            isFullSyncing = true
            fullSyncProgress = null
            fullSyncMessage = context.getString(R.string.loading_lichess)
            Log.d(TAG, "fullSyncWithRemote: starting (since=$since, until=$until)")
            var addedCount = 0

            if (profile.lichessUsername.isNotEmpty()) {
                val lichessList = com.github.movesense.GameLoaders.loadLichess(
                    profile.lichessUsername,
                    since = since,
                    until = until,
                    max = null // Загружаем ВСЕ в диапазоне
                )
                Log.d(TAG, "Lichess returned ${lichessList.size} games")
                val added = repo.mergeExternal(Provider.LICHESS, lichessList)
                addedCount += added
            }

            fullSyncMessage = context.getString(R.string.loading_chesscom)
            fullSyncProgress = 0f
            if (profile.chessUsername.isNotEmpty()) {
                val chessList = com.github.movesense.GameLoaders.loadChessCom(
                    profile.chessUsername,
                    since = since,
                    until = until,
                    max = null, // Загружаем ВСЕ в диапазоне
                    onProgress = { progress ->
                        fullSyncProgress = progress
                    }
                )
                Log.d(TAG, "Chess.com returned ${chessList.size} games")
                val added = repo.mergeExternal(Provider.CHESSCOM, chessList)
                addedCount += added
            }

            Log.d(TAG, "fullSyncWithRemote: total added = $addedCount")
            Toast.makeText(
                context,
                context.getString(R.string.games_added, addedCount),
                Toast.LENGTH_SHORT
            ).show()

        } catch (e: Throwable) {
            Log.e(TAG, "fullSyncWithRemote failed: ${e.message}", e)
            Toast.makeText(
                context,
                context.getString(R.string.sync_error, e.message ?: ""),
                Toast.LENGTH_SHORT
            ).show()
        } finally {
            isFullSyncing = false
            fullSyncProgress = null
            showSettingsDialog = false // Закрываем диалог
            if (isFirstLoad) onFirstLoadComplete() // Отмечаем, что первая загрузка завершена
        }
    }

    // --- УДАЛЕНА старая `syncWithRemote()` ---

    // --- ОБНОВЛЕННЫЙ LaunchedEffect ---
    LaunchedEffect(profile, isFirstLoad) {
        if (isFirstLoad) {
            Log.d(TAG, "🔄 First load detected, loading from cache...")
            isDeltaSyncing = true
            loadFromLocal()
            isDeltaSyncing = false
            if (items.isEmpty()) {
                // Если кэш пуст, принудительно показываем диалог загрузки
                Log.d(TAG, "Cache is empty, showing settings dialog to load games.")
                showSettingsDialog = true
            } else {
                // Если кэш не пуст, просто завершаем "первую загрузку"
                // Пользователь сам обновит список (delta) или нажмет "Загрузить" (full)
                onFirstLoadComplete()
            }
        } else {
            Log.d(TAG, "✓ Not first load, loading from cache only")
            loadFromLocal()
        }
    }

    val pullState = rememberPullToRefreshState()

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        // ... (остается без изменений) ...
        if (uri != null) {
            scope.launch {
                runCatching {
                    val pgn = context.contentResolver.openInputStream(uri)?.use {
                        it.readBytes().toString(Charsets.UTF_8)
                    }.orEmpty()
                    if (pgn.isBlank()) error(context.getString(R.string.empty_pgn))
                    addManualGame(pgn, profile, repo)
                    loadFromLocal()
                    Toast.makeText(context, context.getString(R.string.game_added), Toast.LENGTH_SHORT).show()
                }.onFailure {
                    Toast.makeText(
                        context,
                        context.getString(R.string.add_error, it.message ?: ""),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    // ... (LaunchedEffect(showAnalysis...), formatEta - остаются без изменений) ...

    // --- ИСПРАВЛЕНИЕ ЛОГИКИ ОБНОВЛЕНИЯ liveFen ---
    fun startAnalysis(fullPgn: String, depth: Int, multiPv: Int) {
        if (showAnalysis) return
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

                // 🔵 Сброс ETA/скорости
                visibleEtaMs = null
                emaPerMoveMs = null
                lastTickDone = null
                lastTickAtMs = null
                etaAnchorStartMs = null
                etaInitialMs = null
                totalPly = null
                analysisStartAtMs = System.currentTimeMillis()

                val header = runCatching { PgnChess.headerFromPgn(fullPgn) }.getOrNull()

                // Определяем режим движка
                isServerMode = EngineClient.engineMode.value == EngineClient.EngineMode.SERVER

                // Парсим PGN для получения всех ходов
                val parsedMoves = PgnChess.movesWithFens(fullPgn)
                val startFen = parsedMoves.firstOrNull()?.beforeFen
                    ?: "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"

                // Создаем список всех позиций для анимации
                allGameMoves = buildList {
                    add(Triple(startFen, "", ""))
                    parsedMoves.forEach { move ->
                        add(Triple(move.afterFen, move.uci, move.san))
                    }
                }

                val accumulatedPositions = mutableMapOf<Int, PositionEval>()

                val report = analyzeGameByPgnWithProgress(
                    pgn = fullPgn,
                    depth = depth,
                    multiPv = multiPv,
                    header = header
                ) { snap ->
                    val now = System.currentTimeMillis()

                    val newFen = snap.fen
                    val newUci = snap.currentUci
                    val cls = snap.currentClass?.let { runCatching { MoveClass.valueOf(it) }.getOrNull() }

                    analysisProgress = (snap.percent ?: 0.0).toFloat() / 100f
                    analysisStage = snap.stage

                    // 🔵 Скорость и монотонный ETA
                    totalPly = snap.total
                    val prevDone = lastTickDone
                    if (snap.done > 0 && snap.total > 0) {
                        // EMA-оценка скорости (не используется для увеличения, только для возможного уменьшения)
                        if (prevDone != null && snap.done > prevDone) {
                            val dt = (now - (lastTickAtMs ?: now)).coerceAtLeast(1L)
                            val dDone = (snap.done - prevDone).coerceAtLeast(1)
                            val instPerMove = dt.toDouble() / dDone.toDouble()
                            emaPerMoveMs = emaPerMoveMs?.let { 0.2 * instPerMove + 0.8 * it } ?: instPerMove
                        }
                        lastTickDone = snap.done
                        lastTickAtMs = now

                        val remainingPly = (snap.total - snap.done).coerceAtLeast(0)

                        if (etaAnchorStartMs == null || etaInitialMs == null) {
                            // ⛳️ ПЕРВИЧНАЯ оценка: считаем ОДИН РАЗ и берём консервативную (бОльшую)
                            val avgPerMove = ((now - (analysisStartAtMs ?: now)).toDouble() / snap.done.toDouble())
                                .takeIf { it.isFinite() && it > 0 }
                            val localRemaining = avgPerMove?.times(remainingPly)?.roundToLong()
                            val backendRemaining = snap.etaMs
                            val initial = listOfNotNull(localRemaining, backendRemaining).maxOrNull()
                                ?: backendRemaining
                                ?: localRemaining
                                ?: 0L
                            etaAnchorStartMs = now
                            etaInitialMs = initial
                            visibleEtaMs = initial
                        } else {
                            // Кандидаты на уменьшение
                            val emaRemaining = emaPerMoveMs?.times(remainingPly)?.roundToLong()
                            val candidate = listOfNotNull(emaRemaining, snap.etaMs).minOrNull()
                            if (candidate != null) {
                                val currentLeft = max(0L, etaAnchorStartMs!! + etaInitialMs!! - now)
                                // Разрешаем только уменьшение
                                if (candidate < currentLeft) {
                                    etaAnchorStartMs = now
                                    etaInitialMs = candidate
                                    visibleEtaMs = candidate
                                }
                            }
                        }
                    } else if (visibleEtaMs == null && snap.etaMs != null) {
                        // Фолбэк до первого done
                        etaAnchorStartMs = now
                        etaInitialMs = snap.etaMs
                        visibleEtaMs = snap.etaMs
                    }

                    // ЛОКАЛЬНЫЙ РЕЖИМ: обновляем доску в реальном времени
                    if (!isServerMode) {
                        if (!newUci.isNullOrBlank() && newUci != lastSoundedUci) {
                            val captureNow = isCapture(prevFenForSound, newUci)
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
                    }

                    // Обновляем позиции для eval bar
                    if (newFen != null && (snap.evalCp != null || snap.evalMate != null)) {
                        val line = LineEval(
                            pv = emptyList(),
                            cp = snap.evalCp,
                            mate = snap.evalMate,
                            best = null,
                            depth = depth,
                            multiPv = 1
                        )
                        val pos = PositionEval(
                            fen = newFen,
                            idx = snap.done - 1,
                            lines = listOf(line)
                        )

                        accumulatedPositions[pos.idx] = pos

                        livePositions = accumulatedPositions.values
                            .sortedBy { it.idx }
                            .toList()

                        Log.d(TAG, "📊 Streaming: positions=${livePositions.size}, ply=${currentPlyForEval}, cp=${snap.evalCp}, mate=${snap.evalMate}")
                    }
                }

                livePositions = report.positions
                Log.d(TAG, "✅ Analysis complete, final positions count: ${livePositions.size}")

                repo.saveReport(fullPgn, report)

                // Сохраняем отчет для последующего использования
                completedReport = report

                // Устанавливаем флаг завершения анализа
                analysisCompleted = true

                // В серверном режиме ждем окончания анимации, в локальном - сразу переходим
                if (!isServerMode) {
                    showAnalysis = false
                    loadFromLocal()
                    onOpenReport(report)
                }
            } catch (t: Throwable) {
                showAnalysis = false
                Log.e(TAG, "Analysis error: ${t.message}", t)
                Toast.makeText(
                    context,
                    context.getString(R.string.analysis_error, t.message ?: ""),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    // LaunchedEffect для анимации ходов в серверном режиме
    LaunchedEffect(showAnalysis, isServerMode, allGameMoves) {
        if (!showAnalysis || !isServerMode || allGameMoves.isEmpty()) return@LaunchedEffect

        Log.d(TAG, "Starting server mode animation with ${allGameMoves.size} moves")
        animatedMoveIndex = 0

        while (showAnalysis && animatedMoveIndex < allGameMoves.size) {
            val (fen, uci, san) = allGameMoves[animatedMoveIndex]

            liveFen = fen
            liveUciMove = uci.takeIf { it.isNotBlank() }
            currentPlyForEval = animatedMoveIndex

            // Воспроизводим звук для нового хода
            if (uci.isNotBlank() && uci != lastSoundedUci) {
                val prevIdx = (animatedMoveIndex - 1).coerceAtLeast(0)
                val prevFen = if (prevIdx < allGameMoves.size) allGameMoves[prevIdx].first else null
                val captureNow = if (prevFen != null) isCapture(prevFen, uci) else false

                // Получаем классификацию хода из completedReport если доступен
                val cls = completedReport?.moves?.getOrNull(animatedMoveIndex - 1)?.classification
                playMoveSound(cls, captureNow)
                lastSoundedUci = uci
            }

            Log.d(TAG, "Animated move $animatedMoveIndex: $san")
            animatedMoveIndex++

            // Если анализ завершен и мы показали все ходы, переходим к репорту
            if (analysisCompleted && animatedMoveIndex >= allGameMoves.size) {
                delay(500) // Небольшая пауза перед переходом
                showAnalysis = false
                loadFromLocal()
                completedReport?.let { onOpenReport(it) }
                break
            }

            delay(500) // 0.5 секунды на ход
        }
    }

    // LaunchedEffect для автоматического перехода при завершении анализа в серверном режиме
    LaunchedEffect(analysisCompleted, isServerMode, animatedMoveIndex, allGameMoves.size) {
        if (analysisCompleted && isServerMode && animatedMoveIndex >= allGameMoves.size) {
            delay(300)
            showAnalysis = false
            loadFromLocal()
            completedReport?.let { onOpenReport(it) }
        }
    }

    LaunchedEffect(showAnalysis, etaAnchorStartMs, etaInitialMs) {
        if (!showAnalysis || etaAnchorStartMs == null || etaInitialMs == null) return@LaunchedEffect
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
    // --- КОНЕЦ БЛОКА АНАЛИЗА ---


    val filteredItems = remember(items, currentFilter) {
        when (currentFilter) {
            GameFilter.ALL -> items
            GameFilter.LICHESS -> items.filter { it.site == Provider.LICHESS }
            GameFilter.CHESSCOM -> items.filter { it.site == Provider.CHESSCOM }
            GameFilter.MANUAL -> items.filter { it.site == Provider.MANUAL }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.games_list)) },
                actions = {
                    IconButton(onClick = { showSettingsDialog = true }) {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = stringResource(R.string.settings)
                        )
                    }
                    IconButton(onClick = { showAddDialog = true }) {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = stringResource(R.string.add_game)
                        )
                    }
                }
            )
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            Column(Modifier.fillMaxSize()) {
                // ФИЛЬТРЫ
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // ... (FilterChip - остаются без изменений) ...
                    FilterChip(
                        selected = currentFilter == GameFilter.ALL,
                        onClick = { currentFilter = GameFilter.ALL },
                        label = {
                            Text(
                                context.getString(R.string.filter_all, items.size),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    )
                    FilterChip(
                        selected = currentFilter == GameFilter.LICHESS,
                        onClick = { currentFilter = GameFilter.LICHESS },
                        label = {
                            Text(
                                stringResource(R.string.filter_lichess),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    )
                    FilterChip(
                        selected = currentFilter == GameFilter.CHESSCOM,
                        onClick = { currentFilter = GameFilter.CHESSCOM },
                        label = {
                            Text(
                                stringResource(R.string.filter_chesscom),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    )
                    FilterChip(
                        selected = currentFilter == GameFilter.MANUAL,
                        onClick = { currentFilter = GameFilter.MANUAL },
                        label = {
                            Text(
                                stringResource(R.string.filter_manual),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    )
                }

                // --- ОБНОВЛЕННЫЙ PullToRefreshBox ---
                PullToRefreshBox(
                    modifier = Modifier.fillMaxSize(),
                    isRefreshing = isDeltaSyncing, // <-- ИЗМЕНЕНО
                    onRefresh = {
                        scope.launch {
                            isDeltaSyncing = true
                            deltaSyncWithRemote() // <-- ИЗМЕНЕНО
                            loadFromLocal()
                            isDeltaSyncing = false
                        }
                    },
                    state = pullState
                ) {
                    when {
                        isDeltaSyncing && items.isEmpty() -> { // <-- ИЗМЕНЕНО
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator()
                            }
                        }
                        filteredItems.isEmpty() && !isDeltaSyncing -> { // <-- ИЗМЕНЕНО
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text(
                                    stringResource(R.string.no_games),
                                    style = MaterialTheme.typography.titleMedium
                                )
                            }
                        }
                        else -> {
                            LazyColumn(Modifier.fillMaxSize()) {
                                itemsIndexed(
                                    filteredItems,
                                    key = { _, g ->
                                        val hashPart = (g.pgn?.length ?: 0).toString()
                                        "${g.site}|${g.date}|${g.white}|${g.black}|${g.result}|$hashPart"
                                    }
                                ) { index, game ->
                                    val analyzedReport = analyzedGames[repo.pgnHash(game.pgn.orEmpty())]
                                    CompactGameCard( // <-- ОН ОБНОВЛЕН
                                        game = game,
                                        profile = profile,
                                        analyzedReport = analyzedReport,
                                        index = index,
                                        isAnalyzing = showAnalysis,
                                        onClick = {
                                            if (showAnalysis) return@CompactGameCard
                                            scope.launch {
                                                val fullPgn = com.github.movesense.GameLoaders
                                                    .ensureFullPgn(game)
                                                    .ifBlank { game.pgn.orEmpty() }
                                                if (fullPgn.isBlank()) {
                                                    Toast.makeText(
                                                        context,
                                                        context.getString(R.string.pgn_not_found),
                                                        Toast.LENGTH_SHORT
                                                    ).show()
                                                    return@launch
                                                }
                                                if (game.site == Provider.LICHESS || game.site == Provider.CHESSCOM) {
                                                    repo.updateExternalPgn(game.site, game, fullPgn)
                                                }
                                                val cached = repo.getCachedReport(fullPgn)
                                                if (cached != null) onOpenReport(cached)
                                                else startAnalysis(fullPgn, depth = 12, multiPv = 3)
                                            }
                                        },
                                        onLongPress = {
                                            if (analyzedReport != null) {
                                                reAnalyzeTargetPgn = game.pgn
                                                reAnalyzeDepth = 12
                                                reAnalyzeMultiPv = 3
                                                showReAnalyzeSheet = true
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // ... (if (showAnalysis) { ... } - остается без изменений) ...

            // --- zIndex(10f) из прошлого шага ОСТАЕТСЯ ---
            if (showAnalysis) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .zIndex(10f) // <-- ЭТО ОСТАЕТСЯ
                        .background(Color.Black.copy(alpha = 0.75f)),
                    contentAlignment = Alignment.Center
                ) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        elevation = CardDefaults.cardElevation(defaultElevation = 12.dp),
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier
                            .padding(24.dp)
                            .width(320.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = stringResource(R.string.analyzing),
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                                fontSize = 22.sp
                            )

                            Spacer(Modifier.height(4.dp))

                            if (analysisProgress > 0f) {
                                Text(
                                    text = "${(analysisProgress * 100).toInt()}%",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                                    fontWeight = FontWeight.Medium
                                )
                            }

                            // 🔵 ETA — показываем прямо над прогресс-баром
                            val etaLabel = formatEta(visibleEtaMs)
                            if (etaLabel != "—") {
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    text = stringResource(R.string.remaining_time, etaLabel),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                                    fontWeight = FontWeight.SemiBold
                                )
                            }

                            Spacer(Modifier.height(10.dp))

                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(10.dp)
                                    .clip(RoundedCornerShape(5.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxHeight()
                                        .fillMaxWidth(analysisProgress.coerceIn(0f, 1f))
                                        .clip(RoundedCornerShape(5.dp))
                                        .background(
                                            Brush.horizontalGradient(
                                                colors = listOf(
                                                    Color(0xFF4CAF50),
                                                    Color(0xFF66BB6A),
                                                    Color(0xFF81C784)
                                                )
                                            )
                                        )
                                )
                            }

                            Spacer(Modifier.height(16.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (showEvalBar && livePositions.isNotEmpty()) {
                                    Log.d(TAG, "Drawing eval bar: positions=${livePositions.size}, ply=$currentPlyForEval")
                                    MiniEvalBar(
                                        positions = livePositions,
                                        currentPlyIndex = currentPlyForEval,
                                        modifier = Modifier
                                            .width(14.dp)
                                            .height(280.dp)
                                    )
                                    Spacer(Modifier.width(12.dp))
                                }

                                Box(
                                    modifier = Modifier.size(280.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (!liveFen.isNullOrBlank()) {
                                        val lastMovePair = if (!liveUciMove.isNullOrBlank() && liveUciMove!!.length >= 4) {
                                            liveUciMove!!.substring(0, 2) to liveUciMove!!.substring(2, 4)
                                        } else null

                                        val moveClassEnum = liveMoveClass?.let {
                                            runCatching { MoveClass.valueOf(it) }.getOrNull()
                                        }

                                        BoardCanvas(
                                            fen = liveFen!!,
                                            lastMove = lastMovePair,
                                            moveClass = moveClassEnum,
                                            bestMoveUci = null,
                                            showBestArrow = false,
                                            isWhiteBottom = true,
                                            selectedSquare = null,
                                            legalMoves = emptySet(),
                                            onSquareClick = null,
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    } else {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(48.dp),
                                            strokeWidth = 4.dp,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // --- ОБНОВЛЕННЫЙ Диалог Настроек (С ИСПРАВЛЕНИЕМ КРЕША) ---
    if (showSettingsDialog) {
        AlertDialog(
            onDismissRequest = { if (!isFullSyncing) showSettingsDialog = false },
            title = { Text(stringResource(R.string.settings)) },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    // 1. Старая настройка Eval Bar
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = showEvalBar,
                            onCheckedChange = { checked ->
                                showEvalBar = checked
                                prefs.edit().putBoolean(KEY_SHOW_EVAL_BAR, checked).apply()
                            }
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.show_eval_bar_mini),
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }

                    Divider(modifier = Modifier.padding(vertical = 12.dp))

                    // 2. Новая секция "Загрузить Партии"
                    Text(
                        stringResource(R.string.load_games_title),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(Modifier.height(12.dp))

                    if (isFullSyncing) {
                        // --- UI Во время загрузки ---
                        Text(fullSyncMessage, style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(16.dp))

                        // --- ИСПРАВЛЕНИЕ ЗДЕСЬ ---
                        // Условие изменено: `> 0f` убрано, чтобы показывать 0%
                        if (fullSyncProgress != null) {
                            // --- UI для ОПРЕДЕЛЕННОГО прогресса ---
                            LinearProgressIndicator(
                                // ИСПРАВЛЕНО: убран `!!`
                                progress = { fullSyncProgress ?: 0f },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text(
                                // ИСПРАВЛЕНО: убран `!!`
                                "${((fullSyncProgress ?: 0f) * 100).toInt()}%",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        } else {
                            // --- UI для НЕОПРЕДЕЛЕННОГО прогресса ---
                            // (Lichess или самое начало Chess.com)
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                        // --- КОНЕЦ ИСПРАВЛЕНИЯ ---

                    } else {
                        // --- UI Выбора загрузки ---
                        Text(
                            stringResource(R.string.load_games_desc),
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(16.dp))

                        // Date Pickers
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceAround
                        ) {
                            Button(onClick = { showDatePickerFrom = true }) {
                                Text(dateFromMillis?.let { dateFormatter.format(Date(it)) }
                                    ?: stringResource(R.string.date_from))
                            }
                            Button(onClick = { showDatePickerUntil = true }) {
                                Text(dateUntilMillis?.let { dateFormatter.format(Date(it)) }
                                    ?: stringResource(R.string.date_until))
                            }
                        }

                        Spacer(Modifier.height(8.dp))

                        Button(
                            onClick = {
                                scope.launch {
                                    fullSyncWithRemote(dateFromMillis, dateUntilMillis)
                                    // Переносим loadFromLocal сюда, чтобы он запускался
                                    // только после завершения
                                    loadFromLocal()
                                }
                            },
                            enabled = dateFromMillis != null || dateUntilMillis != null
                        ) {
                            Text(stringResource(R.string.load_date_range))
                        }

                        Spacer(Modifier.height(12.dp))
                        Divider()
                        Spacer(Modifier.height(12.dp))

                        Button(onClick = {
                            scope.launch {
                                fullSyncWithRemote(null, null) // null, null = все
                                // И сюда тоже
                                loadFromLocal()
                            }
                        }) {
                            Text(stringResource(R.string.load_all_games))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (!isFullSyncing) {
                            showSettingsDialog = false
                            if (isFirstLoad) onFirstLoadComplete() // Отмечаем, что юзер закрыл диалог
                        }
                    },
                    enabled = !isFullSyncing
                ) {
                    Text(stringResource(R.string.close))
                }
            }
        )
    }
    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text(stringResource(R.string.add_game_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.paste_pgn_hint))
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = pastedPgn,
                        onValueChange = { pastedPgn = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 120.dp),
                        placeholder = { Text(stringResource(R.string.paste_pgn_hint)) }
                    )
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = {
                        filePicker.launch(arrayOf("application/x-chess-pgn", "text/plain", "text/*"))
                    }) { Text(stringResource(R.string.select_file)) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        if (pastedPgn.isBlank()) {
                            Toast.makeText(
                                context,
                                context.getString(R.string.empty_pgn),
                                Toast.LENGTH_SHORT
                            ).show()
                            return@launch
                        }
                        runCatching {
                            addManualGame(pgn = pastedPgn, profile = profile, repo = repo)
                            loadFromLocal()
                        }.onSuccess {
                            pastedPgn = ""
                            showAddDialog = false
                            Toast.makeText(
                                context,
                                context.getString(R.string.game_added),
                                Toast.LENGTH_SHORT
                            ).show()
                        }.onFailure {
                            Toast.makeText(
                                context,
                                context.getString(R.string.add_error, it.message ?: ""),
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }) { Text(stringResource(R.string.save)) }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    // ... (if (showReAnalyzeSheet) { ... } - остается без изменений) ...
    if (showReAnalyzeSheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showReAnalyzeSheet = false },
            sheetState = sheetState
        ) {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Text(
                    stringResource(R.string.reanalyze_title),
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(Modifier.height(12.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.depth_label), modifier = Modifier.width(80.dp))
                    OutlinedTextField(
                        value = reAnalyzeDepth.toString(),
                        onValueChange = { s ->
                            reAnalyzeDepth = s.filter { it.isDigit() }.toIntOrNull()?.coerceIn(6, 40) ?: reAnalyzeDepth
                        },
                        singleLine = true,
                        modifier = Modifier.width(120.dp)
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.multipv_label), modifier = Modifier.width(80.dp))
                    OutlinedTextField(
                        value = reAnalyzeMultiPv.toString(),
                        onValueChange = { s ->
                            reAnalyzeMultiPv = s.filter { it.isDigit() }.toIntOrNull()?.coerceIn(1, 5) ?: reAnalyzeMultiPv
                        },
                        singleLine = true,
                        modifier = Modifier.width(120.dp)
                    )
                }

                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = {
                        val pgn = reAnalyzeTargetPgn
                        if (!pgn.isNullOrBlank()) {
                            showReAnalyzeSheet = false
                            startAnalysis(pgn, depth = reAnalyzeDepth, multiPv = reAnalyzeMultiPv)
                        } else {
                            showReAnalyzeSheet = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.reanalyze)) }
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    // --- НОВЫЕ Date Pickers ---
    if (showDatePickerFrom) {
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = dateFromMillis ?: System.currentTimeMillis()
        )
        DatePickerDialog(
            onDismissRequest = { showDatePickerFrom = false },
            confirmButton = {
                TextButton(onClick = {
                    dateFromMillis = datePickerState.selectedDateMillis
                    showDatePickerFrom = false
                }) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = { showDatePickerFrom = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        ) { DatePicker(state = datePickerState) }
    }

    if (showDatePickerUntil) {
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = dateUntilMillis ?: System.currentTimeMillis()
        )
        DatePickerDialog(
            onDismissRequest = { showDatePickerUntil = false },
            confirmButton = {
                TextButton(onClick = {
                    dateUntilMillis = datePickerState.selectedDateMillis
                    showDatePickerUntil = false
                }) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = { showDatePickerUntil = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        ) { DatePicker(state = datePickerState) }
    }
}

// ... (MiniEvalBar, parseGameEnd, formatGameEndText - остаются без изменений) ...
@Composable
private fun MiniEvalBar(
    positions: List<PositionEval>,
    currentPlyIndex: Int,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xFF2B2A27))
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val height = size.height
            val width = size.width

            if (positions.isEmpty()) {
                Log.w(TAG, "MiniEvalBar: no positions!")
                return@Canvas
            }

            val safeIndex = currentPlyIndex.coerceIn(0, positions.lastIndex)
            val pos = positions[safeIndex]
            val line = pos.lines.firstOrNull()

            val evalCp = when {
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

    val terminationTag = Regex("""\[Termination\s+"([^"]+)"]""")
        .find(pgn)?.groupValues?.getOrNull(1)?.lowercase() ?: ""

    val resultTag = result ?: Regex("""\[Result\s+"([^"]+)"]""")
        .find(pgn)?.groupValues?.getOrNull(1) ?: "*"

    val winner = when (resultTag) {
        "1-0" -> "white"
        "0-1" -> "black"
        else -> null
    }

    // КРИТИЧНО: Улучшенное определение для Chess.com и Lichess
    val termination = when {
        // Мат
        "normal" in terminationTag && resultTag != "1/2-1/2" -> GameTermination.CHECKMATE
        "checkmate" in terminationTag -> GameTermination.CHECKMATE

        // Время
        "time forfeit" in terminationTag -> GameTermination.TIMEOUT
        "timeout" in terminationTag -> GameTermination.TIMEOUT
        "time" in terminationTag && resultTag != "1/2-1/2" -> GameTermination.TIMEOUT

        // Сдача
        "abandoned" in terminationTag -> GameTermination.RESIGNATION
        "resignation" in terminationTag -> GameTermination.RESIGNATION
        "resign" in terminationTag -> GameTermination.RESIGNATION

        // Ничьи
        "stalemate" in terminationTag -> GameTermination.STALEMATE
        "insufficient material" in terminationTag -> GameTermination.INSUFFICIENT
        "50" in terminationTag && "move" in terminationTag -> GameTermination.FIFTY_MOVE
        "repetition" in terminationTag -> GameTermination.REPETITION
        "threefold" in terminationTag -> GameTermination.REPETITION

        // По соглашению
        "agreement" in terminationTag || "agreed" in terminationTag -> GameTermination.AGREEMENT
        resultTag == "1/2-1/2" && terminationTag.isNotBlank() -> GameTermination.DRAW

        // Fallback: если есть победитель, но причина неизвестна
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


// --- НОВЫЕ ХЕЛПЕРЫ для ТИТУЛОВ ---

// НОВАЯ функция парсинга имени и титула
private fun parsePlayerInfo(name: String?, pgn: String?, isWhite: Boolean): PlayerInfo {
    val base = name.orEmpty()
    if (pgn.isNullOrBlank()) return PlayerInfo(base, null)
    val tag = if (isWhite) """\[WhiteTitle\s+"([^"]+)"]""" else """\[BlackTitle\s+"([^"]+)"]"""
    val rx = Regex(tag)
    val title = rx.find(pgn)?.groupValues?.getOrNull(1)?.uppercase()
    return PlayerInfo(base, if (title.isNullOrBlank() || title == "NONE") null else title)
}

// НОВАЯ функция для определения титула самого пользователя
private fun getUserTitle(profile: UserProfile, whiteInfo: PlayerInfo, blackInfo: PlayerInfo): String? {
    val me = listOf(profile.nickname.trim(), profile.lichessUsername.trim(), profile.chessUsername.trim())
        .filter { it.isNotBlank() }.map { it.lowercase() }
    val w = whiteInfo.name.trim().lowercase()
    val b = blackInfo.name.trim().lowercase()

    return when {
        w.isNotBlank() && me.any { it == w } -> whiteInfo.title
        b.isNotBlank() && me.any { it == b } -> blackInfo.title
        else -> null
    }
}

// --- ОБНОВЛЕННЫЙ Composable для красивого отображения имени ---
@Composable
private fun PlayerName(
    info: PlayerInfo,
    modifier: Modifier = Modifier,
    textAlign: TextAlign? = null,
    maxLines: Int = 1,
    overflow: TextOverflow = TextOverflow.Ellipsis
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
    ) {
        if (info.title != null) {
            Text(
                text = info.title.uppercase(Locale.getDefault()), // <-- ИЗМЕНЕНО: .uppercase()
                style = MaterialTheme.typography.bodySmall.copy(
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White // <-- ИЗМЕНЕНО: Белый шрифт
                ),
                modifier = Modifier
                    .background(
                        Color(0xFFD32F2F), // <-- ИЗМЕНЕНО: Красный фон
                        RoundedCornerShape(4.dp)
                    )
                    .padding(horizontal = 4.dp, vertical = 2.dp) // <-- ИЗМЕНЕНО: vertical = 2.dp
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

// --- ОБНОВЛЕННАЯ CompactGameCard (С КОРОНОЙ) ---
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
    val userWon = mySide != null && ((mySide && game.result == "1-0") || (!mySide && game.result == "0-1"))
    val userLost = mySide != null && ((mySide && game.result == "0-1") || (!mySide && game.result == "1-0"))
    val isAnalyzed = analyzedReport != null

    // ИСПРАВЛЕНО: передаём result в parseGameEnd
    val gameEndInfo = remember(game.pgn, game.result) {
        parseGameEnd(game.pgn, game.result)
    }
    val endText = formatGameEndText(gameEndInfo, game)

    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.98f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "scale"
    )

    // --- НОВАЯ ЛОГИКА ТИТУЛОВ ---
    val whiteInfo = remember(game.white, game.pgn) { parsePlayerInfo(game.white, game.pgn, isWhite = true) }
    val blackInfo = remember(game.black, game.pgn) { parsePlayerInfo(game.black, game.pgn, isWhite = false) }

    val userTitle = remember(profile, whiteInfo, blackInfo) { getUserTitle(profile, whiteInfo, blackInfo) }
    val opponentTitle = when (mySide) {
        true -> blackInfo.title
        false -> whiteInfo.title
        null -> null
    }

    // Победа против титулованного, если у самого пользователя титула нет
    val isWinVsTitled = userWon && (userTitle == null) && (opponentTitle != null)
    // --- КОНЕЦ НОВОЙ ЛОГИКИ ---

    // --- ИЗМЕНЕНО: Card обернут в Box для размещения короны ---
    Box(
        contentAlignment = Alignment.TopCenter,
        modifier = Modifier
            .fillMaxWidth()
            // Отступы для элемента списка.
            // Динамический верхний отступ, чтобы корона не налезала на элемент выше
            // --- ИСПРАВЛЕНИЕ (из прошлого шага) ---
            .padding(
                start = 12.dp,
                end = 12.dp,
                top = if (isWinVsTitled) 18.dp else 4.dp, // 14dp для короны + 4dp отступ
                bottom = 4.dp
            )
            // --- КОНЕЦ ИСПРАВЛЕНИЯ ---
            .scale(scale)
            .combinedClickable(
                enabled = !isAnalyzing,
                onClick = { pressed = true; onClick(); pressed = false },
                onLongClick = { if (isAnalyzed) onLongPress() }
            )
    ) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = when {
                    userWon -> Color(0xFFDFF0D8)
                    userLost -> Color(0xFFF2DEDE)
                    else -> MaterialTheme.colorScheme.surface
                }
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
            // НОВОЕ СВОЙСТВО: золотая рамка (как в вашем коде)
            border = if (isWinVsTitled) BorderStroke(2.dp, Color(0xFFFFD700)) else null,
            modifier = Modifier
                .fillMaxWidth()
                .height(if (endText.isNotBlank()) 168.dp else 148.dp)
            // Модификаторы .scale и .combinedClickable ПЕРЕНЕСЕНЫ на Box
        ) {
            val siteName = when (game.site) {
                Provider.LICHESS -> stringResource(R.string.filter_lichess)
                Provider.CHESSCOM -> stringResource(R.string.filter_chesscom)
                Provider.MANUAL, Provider.BOT -> stringResource(R.string.filter_manual)
                null -> ""
            }
            val (modeLabel, openingLine) = deriveModeAndOpening(game, context)

            Column(Modifier.padding(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        buildAnnotatedString {
                            withStyle(SpanStyle(fontWeight = FontWeight.Medium, fontSize = 11.sp)) { append(siteName) }
                            if (!game.date.isNullOrBlank()) { append(" • "); append(game.date!!) }
                            if (modeLabel.isNotBlank()) { append(" • "); append(modeLabel) }
                        },
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (isAnalyzed) {
                        Badge(containerColor = Color(0xFF4CAF50), contentColor = Color.White) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(12.dp)
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    stringResource(R.string.analyzed),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.SemiBold
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
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        UserBubble(name = game.white ?: "W", size = 22.dp)
                        Spacer(Modifier.width(6.dp))

                        // ИСПОЛЬЗУЕМ ОБНОВЛЕННЫЙ PlayerName
                        PlayerName(info = whiteInfo, modifier = Modifier.weight(1f, fill = false))
                    }
                    Text(
                        game.result.orEmpty(),
                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold, fontSize = 12.sp),
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.End
                    ) {

                        // ИСПОЛЬЗУЕМ ОБНОВЛЕННЫЙ PlayerName
                        PlayerName(
                            info = blackInfo,
                            textAlign = TextAlign.End,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        Spacer(Modifier.width(6.dp))
                        UserBubble(name = game.black ?: "B", size = 22.dp)
                    }
                }

                // ... (Отображение способа завершения, openingLine, StatColumn - остаются без изменений) ...
                if (endText.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Surface(
                        color = when (gameEndInfo.termination) {
                            GameTermination.CHECKMATE -> Color(0xFFFFE0B2).copy(alpha = 0.6f)
                            GameTermination.TIMEOUT -> Color(0xFFFFCDD2).copy(alpha = 0.6f)
                            GameTermination.RESIGNATION -> Color(0xFFE0E0E0).copy(alpha = 0.6f)
                            else -> Color(0xFFB3E5FC).copy(alpha = 0.6f)
                        },
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = endText,
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp, fontWeight = FontWeight.Medium),
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                if (openingLine.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        openingLine,
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(Modifier.height(6.dp))
                Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(8.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        StatColumn(
                            accuracy = analyzedReport?.accuracy?.whiteMovesAcc?.itera,
                            performance = analyzedReport?.estimatedElo?.whiteEst
                        )
                        Box(
                            modifier = Modifier
                                .width(1.dp)
                                .height(24.dp)
                                .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
                        )
                        StatColumn(
                            accuracy = analyzedReport?.accuracy?.blackMovesAcc?.itera,
                            performance = analyzedReport?.estimatedElo?.blackEst
                        )
                    }
                }
            }
        }

        // --- НОВОЕ: Отображение иконки короны ---
        if (isWinVsTitled) {
            Image(
                painter = painterResource(id = R.drawable.icon_crown), // Убедитесь, что файл есть
                contentDescription = stringResource(id = R.string.victory_vs_titled),
                modifier = Modifier
                    .zIndex(1f) // Поверх Card
                    .size(28.dp)
                    .align(Alignment.TopCenter) // К верхнему краю Box
                    .offset(y = (-14).dp)     // Сдвиг вверх на половину высоты
            )
        }
        // --- КОНЕЦ: Отображение иконки короны ---
    }
}

// ... (StatColumn, getAccuracyColor, UserBubble, guessMySide - остаются без изменений) ...
@Composable
private fun StatColumn(accuracy: Double?, performance: Int?) {
    val accText = if (accuracy != null) "%.1f%%".format(accuracy) else "—"
    val perfText = performance?.toString() ?: "—"
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            accText,
            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold, fontSize = 14.sp),
            color = if (accuracy != null) getAccuracyColor(accuracy) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
        Text(
            " • $perfText",
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
            color = if (performance != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
    }
}

private fun getAccuracyColor(accuracy: Double): Color = when {
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
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(bg),
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
    val me = listOf(profile.nickname.trim(), profile.lichessUsername.trim(), profile.chessUsername.trim())
        .filter { it.isNotBlank() }.map { it.lowercase() }
    val w = game.white?.trim()?.lowercase()
    val b = game.black?.trim()?.lowercase()
    return when {
        w != null && me.any { it == w } -> true
        b != null && me.any { it == b } -> false
        else -> null
    }
}


// --- УДАЛЕНА старая `playerWithTitle()` ---


// ... (deriveModeAndOpening, mapTimeControlToMode, addManualGame, parseTags - остаются без изменений) ...
private fun deriveModeAndOpening(game: GameHeader, context: Context): Pair<String, String> {
    val pgn = game.pgn
    var mode = ""
    var openingLine = game.opening ?: game.eco ?: ""

    if (!pgn.isNullOrBlank()) {
        val tc = Regex("""\[TimeControl\s+"([^"]+)"]""").find(pgn)?.groupValues?.getOrNull(1)
        mode = tc?.let { mapTimeControlToMode(it, context) } ?: ""
        if (openingLine.isBlank()) {
            val op = Regex("""\[Opening\s+"([^"]+)"]""").find(pgn)?.groupValues?.getOrNull(1)
            val eco = Regex("""\[ECO\s+"([^"]+)"]""").find(pgn)?.groupValues?.getOrNull(1)
            openingLine = when {
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

    val white = header?.white
        ?: tags["White"]
        ?: Regex("""\[White\s+"([^"]+)"]""").find(pgn)?.groupValues?.getOrNull(1)
        ?: "Unknown"

    val black = header?.black
        ?: tags["Black"]
        ?: Regex("""\[Black\s+"([^"]+)"]""").find(pgn)?.groupValues?.getOrNull(1)
        ?: "Unknown"

    val result = header?.result
        ?: tags["Result"]
        ?: Regex("""\[Result\s+"([^"]+)"]""").find(pgn)?.groupValues?.getOrNull(1)
        ?: "*"

    val date = header?.date
        ?: tags["UTCDate"]
        ?: tags["Date"]
        ?: Regex("""\[(?:UTC)?Date\s+"([^"]+)"]""").find(pgn)?.groupValues?.getOrNull(1)

    val whiteElo = header?.whiteElo
        ?: tags["WhiteElo"]?.toIntOrNull()
        ?: Regex("""\[WhiteElo\s+"([^"]+)"]""").find(pgn)?.groupValues?.getOrNull(1)?.toIntOrNull()

    val blackElo = header?.blackElo
        ?: tags["BlackElo"]?.toIntOrNull()
        ?: Regex("""\[BlackElo\s+"([^"]+)"]""").find(pgn)?.groupValues?.getOrNull(1)?.toIntOrNull()

    val opening = header?.opening
        ?: tags["Opening"]
        ?: Regex("""\[Opening\s+"([^"]+)"]""").find(pgn)?.groupValues?.getOrNull(1)

    val eco = header?.eco
        ?: tags["ECO"]
        ?: Regex("""\[ECO\s+"([^"]+)"]""").find(pgn)?.groupValues?.getOrNull(1)

    val site = tags["Site"]
        ?: Regex("""\[Site\s+"([^"]+)"]""").find(pgn)?.groupValues?.getOrNull(1)

    val provider = when {
        site?.contains("lichess.org", ignoreCase = true) == true -> Provider.LICHESS
        site?.contains("chess.com", ignoreCase = true) == true -> Provider.CHESSCOM
        else -> Provider.BOT
    }

    val sideToView = guessMySide(
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

    val gh = GameHeader(
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

    Log.d(TAG, "📝 Adding manual game: $white ($whiteElo) vs $black ($blackElo), date=$date, provider=$provider")

    repo.mergeExternal(provider, listOf(gh))
}

private fun parseTags(pgn: String): Map<String, String> {
    val tagPattern = Regex("""\[(\w+)\s+"([^"]*)"\]""")
    return tagPattern.findAll(pgn).associate {
        it.groupValues[1] to it.groupValues[2]
    }
}
