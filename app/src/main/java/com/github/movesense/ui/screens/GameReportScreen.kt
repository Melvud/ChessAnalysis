// app/src/main/java/com/github/movesense/ui/screens/GameReportScreen.kt

package com.github.movesense.ui.screens

import android.annotation.SuppressLint
import android.media.MediaPlayer
import android.util.Log
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.rememberAsyncImagePainter
import coil.decode.SvgDecoder
import coil.request.ImageRequest
import com.github.movesense.*
import com.github.movesense.EngineClient.analyzeMoveRealtime
import com.github.movesense.EngineClient.evaluateFenDetailedStreaming
import com.github.movesense.ui.components.BoardCanvas
import com.github.movesense.ui.components.MovesCarousel
import com.github.bhlangonijr.chesslib.*
import com.github.bhlangonijr.chesslib.move.Move
import com.github.bhlangonijr.chesslib.move.MoveGenerator
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import kotlin.collections.get
import kotlin.math.abs
import kotlin.math.max
import com.github.movesense.R

private const val TAG = "GameReportScreen"

private data class ViewSettings(
    val showEvalBar: Boolean = true,
    val evalBarPosition: EvalBarPosition = EvalBarPosition.TOP,
    val showBestMoveArrow: Boolean = true,
    val showThreatArrows: Boolean = false,
    val showEngineLines: Boolean = true,
    val numberOfLines: Int = 1
)

private enum class EvalBarPosition { LEFT, TOP }

private data class PositionLinesState(
    val lines: List<LineEval>,
    val isAnalyzing: Boolean,
    val depth: Int,
    val multiPv: Int,
    val isFromReport: Boolean = false
)

@Composable
private fun PieceAssetIcon(name: String, size: Dp) {
    val ctx = LocalContext.current
    val painter = rememberAsyncImagePainter(
        ImageRequest.Builder(ctx)
            .data("file:///android_asset/fresca/$name")
            .decoderFactory(SvgDecoder.Factory())
            .build()
    )
    Image(painter = painter, contentDescription = null, modifier = Modifier.size(size))
}

private fun pieceAssetName(p: Piece): String {
    val pref = if (p.pieceSide == Side.WHITE) "w" else "b"
    val name = when (p.pieceType) {
        PieceType.KING   -> "K"
        PieceType.QUEEN  -> "Q"
        PieceType.ROOK   -> "R"
        PieceType.BISHOP -> "B"
        PieceType.KNIGHT -> "N"
        else -> "P"
    }
    return "$pref$name.svg"
}

private fun findLegalMove(board: Board, uci: String): Move? {
    if (uci.length < 4) return null
    val from = Square.fromValue(uci.substring(0, 2).uppercase())
    val to   = Square.fromValue(uci.substring(2, 4).uppercase())
    val promoChar = if (uci.length > 4) uci[4].lowercaseChar() else null
    val legal = MoveGenerator.generateLegalMoves(board)
    return legal.firstOrNull { m ->
        m.from == from && m.to == to &&
                (promoChar == null || when (m.promotion?.pieceType) {
                    PieceType.QUEEN  -> promoChar == 'q'
                    PieceType.ROOK   -> promoChar == 'r'
                    PieceType.BISHOP -> promoChar == 'b'
                    PieceType.KNIGHT -> promoChar == 'n'
                    null -> false
                    else -> false
                })
    } ?: legal.firstOrNull { it.from == from && it.to == to }
}

fun extractGameId(pgn: String?): String? {
    if (pgn.isNullOrBlank()) return null
    val sitePattern = Regex("""\[Site\s+".*/([\w]+)"\]""")
    sitePattern.find(pgn)?.groupValues?.getOrNull(1)?.let { return it }
    val lichessPattern = Regex("""([a-zA-Z0-9]{8})""")
    lichessPattern.find(pgn)?.groupValues?.getOrNull(1)?.let { return it }
    return null
}

fun parseClockData(pgn: String): ClockData {
    Log.d(TAG, "parseClockData: parsing PGN for clocks...")
    val clockPattern = Regex("""\[%clk\s+(?:(\d+):)?(\d{1,2}):(\d{1,2})(?:\.(\d+))?\]""", RegexOption.IGNORE_CASE)
    val whiteTimes = mutableListOf<Int>()
    val blackTimes = mutableListOf<Int>()
    var plyIndex = 0

    clockPattern.findAll(pgn).forEach { m ->
        val hours = m.groups[1]?.value?.toIntOrNull() ?: 0
        val minutes = m.groups[2]?.value?.toIntOrNull() ?: 0
        val seconds = m.groups[3]?.value?.toIntOrNull() ?: 0
        val cs = (hours * 3600 + minutes * 60 + seconds) * 100

        if (plyIndex % 2 == 0) {
            whiteTimes.add(cs)
        } else {
            blackTimes.add(cs)
        }
        plyIndex++
    }

    Log.d(TAG, "parseClockData: found ${whiteTimes.size} white clocks, ${blackTimes.size} black clocks")
    return ClockData(white = whiteTimes, black = blackTimes)
}

private suspend fun fetchLichessClocks(gameId: String): ClockData? = withContext(Dispatchers.IO) {
    Log.d(TAG, "fetchLichessClocks: fetching for gameId=$gameId")
    val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()
    val url = "https://lichess.org/game/export/$gameId?clocks=true&moves=false&tags=false"
    val request = Request.Builder().url(url).header("Accept", "application/x-chess-pgn").build()
    try {
        client.newCall(request).execute().use { response ->
            if (response.isSuccessful) {
                val body = response.body?.string() ?: return@withContext null
                Log.d(TAG, "fetchLichessClocks: got response, parsing...")
                parseClockData(body)
            } else {
                Log.w(TAG, "fetchLichessClocks: failed with code ${response.code}")
                null
            }
        }
    } catch (e: Exception) {
        Log.e(TAG, "fetchLichessClocks: error", e)
        null
    }
}

@SuppressLint("UnrememberedMutableState")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GameReportScreen(
    report: FullReport,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current

    var isWhiteBottom by remember { mutableStateOf(report.header.sideToView ?: true) }
    var currentPlyIndex by remember { mutableStateOf(0) }
    var isAutoPlaying by remember { mutableStateOf(false) }

    var clockData by remember { mutableStateOf(report.clockData) }

    var variationActive by remember { mutableStateOf(false) }
    var variationFen by remember { mutableStateOf<String?>(null) }
    var variationEval by remember { mutableStateOf<Float?>(null) }
    var variationBestUci by remember { mutableStateOf<String?>(null) }
    var variationMoveClass by remember { mutableStateOf<MoveClass?>(null) }
    var variationLastMove by remember { mutableStateOf<Pair<String, String>?>(null) }
    var selectedSquare by remember { mutableStateOf<String?>(null) }
    var legalTargets by remember { mutableStateOf<Set<String>>(emptySet()) }
    var isAnalyzing by remember { mutableStateOf(false) }

    val linesStateMap = remember { mutableStateMapOf<String, PositionLinesState>() }

    // ИСПРАВЛЕНИЕ: Стабильное отображение линий с сортировкой
    var displayedLines by remember { mutableStateOf<List<LineEval>>(emptyList()) }
    var isAnalysisRunning by remember { mutableStateOf(false) }

    val positionSettings = remember { mutableStateMapOf<Int, Pair<Int, Int>>() }

    val defaultDepth = remember {
        report.positions.firstOrNull()?.lines?.firstOrNull()?.depth ?: 12
    }

    var currentDepth by remember { mutableStateOf(12) }
    var targetDepth by remember { mutableStateOf(12) }
    var targetMultiPv by remember { mutableStateOf(2) }

    var analysisJob by remember { mutableStateOf<Job?>(null) }
    var analysisVersion by remember { mutableStateOf(0) }

    // НОВОЕ: Хранение последних валидных линий для предотвращения мигания
    val lastValidLines = remember { mutableStateMapOf<String, List<LineEval>>() }

    var viewSettings by remember {
        mutableStateOf(ViewSettings(
            showEvalBar = true,
            evalBarPosition = EvalBarPosition.LEFT,
            showBestMoveArrow = true,
            showThreatArrows = false,
            showEngineLines = true,
            numberOfLines = 1
        ))
    }

    var showSettingsDialog by remember { mutableStateOf(false) }
    var showDepthDialog by remember { mutableStateOf(false) }

    val bgColor = Color(0xFF161512)
    val surfaceColor = Color(0xFF262522)
    val cardColor = Color(0xFF1E1C1A)

    fun normalizeLinesToWhitePOV(lines: List<EngineClient.LineDTO>, fen: String): List<EngineClient.LineDTO> {
        val fenParts = fen.split(" ")
        val whiteToPlay = fenParts.getOrNull(1) == "w"

        Log.d(TAG, "🔄 Normalizing ${lines.size} lines, FEN side to move: ${fenParts.getOrNull(1)}, whiteToPlay=$whiteToPlay")

        return lines.map { line ->
            // КРИТИЧНО: Если белые ходят, оценка уже в правильной перспективе (положительная = хорошо для белых)
            // Если черные ходят, нужно инвертировать оценку
            val normalizedCp = if (whiteToPlay) {
                line.cp
            } else {
                line.cp?.let { -it }
            }

            val normalizedMate = line.mate?.let { m ->
                when {
                    // Мат в 0 - особый случай, обрабатываем отдельно
                    m == 0 -> if (whiteToPlay) -1 else 1
                    // Если белые ходят, оценка мата уже правильная
                    whiteToPlay -> m
                    // Если черные ходят, инвертируем оценку мата
                    else -> -m
                }
            }

            Log.d(TAG, "  Line: cp=${line.cp} -> ${normalizedCp}, mate=${line.mate} -> ${normalizedMate}")

            EngineClient.LineDTO(
                pv = line.pv,
                cp = normalizedCp,
                mate = normalizedMate,
                depth = line.depth,
                multiPv = line.multiPv
            )
        }
    }

    // НОВОЕ: Функция сортировки линий по качеству (лучшая линия сверху)
    fun sortLinesByQuality(lines: List<LineEval>): List<LineEval> {
        return lines.sortedWith(compareByDescending<LineEval> { line ->
            // Приоритет: мат лучше материала
            when {
                line.mate != null && line.mate!! > 0 -> 100000 + line.mate!! // Мат белыми
                line.mate != null && line.mate!! < 0 -> -100000 + line.mate!! // Мат чёрными
                line.cp != null -> line.cp!! // Материальная оценка
                else -> 0
            }
        })
    }

    LaunchedEffect(report) {
        Log.d(TAG, "🔄 Initializing lines from report...")
        report.positions.forEachIndexed { index, posEval ->
            if (posEval.lines.isNotEmpty()) {
                val key = "${posEval.fen}-${posEval.lines.firstOrNull()?.depth ?: defaultDepth}-${posEval.lines.size}"
                val sortedLines = sortLinesByQuality(posEval.lines)
                linesStateMap[key] = PositionLinesState(
                    lines = sortedLines,
                    isAnalyzing = false,
                    depth = posEval.lines.firstOrNull()?.depth ?: defaultDepth,
                    multiPv = posEval.lines.size,
                    isFromReport = true
                )
                // Сохраняем как последние валидные
                lastValidLines[posEval.fen] = sortedLines
            }
        }

        val initialLines = sortLinesByQuality(report.positions.getOrNull(0)?.lines ?: emptyList())
        displayedLines = initialLines.take(viewSettings.numberOfLines)
        Log.d(TAG, "✅ Set initial displayed lines: ${initialLines.size}")
    }

    LaunchedEffect(report) {
        Log.d(TAG, "🕐 LaunchedEffect: checking clocks...")

        if (report.clockData != null &&
            (report.clockData!!.white.isNotEmpty() || report.clockData!!.black.isNotEmpty())) {
            Log.d(TAG, "✅ Using clocks from report")
            clockData = report.clockData
            return@LaunchedEffect
        }

        val pgn = report.header.pgn
        if (pgn.isNullOrBlank()) {
            Log.w(TAG, "⚠ No PGN in report")
            return@LaunchedEffect
        }

        val parsed = parseClockData(pgn)

        if (parsed.white.isNotEmpty() || parsed.black.isNotEmpty()) {
            clockData = parsed
            return@LaunchedEffect
        }

        val gameId = extractGameId(pgn)
        if (gameId != null && report.header.site == Provider.LICHESS) {
            val fetched = fetchLichessClocks(gameId)
            if (fetched != null) {
                clockData = fetched
            }
        }
    }

    val currentFen by derivedStateOf {
        if (variationActive) {
            variationFen ?: report.positions.getOrNull(currentPlyIndex)?.fen ?: ""
        } else {
            report.positions.getOrNull(currentPlyIndex)?.fen ?: ""
        }
    }

    // ИСПРАВЛЕНИЕ: Немедленное отображение линий из отчета с правильной оценкой
    LaunchedEffect(currentPlyIndex, variationActive) {
        if (!variationActive) {
            val saved = positionSettings[currentPlyIndex]
            if (saved != null) {
                targetDepth = saved.first
                targetMultiPv = saved.second
            } else {
                targetDepth = 12
                targetMultiPv = viewSettings.numberOfLines
            }

            // КРИТИЧНО: Немедленно показываем отсортированные линии из отчета
            val reportLines = sortLinesByQuality(report.positions.getOrNull(currentPlyIndex)?.lines ?: emptyList())
            if (reportLines.isNotEmpty()) {
                // ВАЖНО: Сохраняем линии ПЕРЕД обновлением UI для стабильности
                lastValidLines[currentFen] = reportLines
                // КРИТИЧНО: Всегда показываем линии, даже если их больше чем нужно - берем первые N
                val linesToShow = reportLines.take(viewSettings.numberOfLines.coerceAtLeast(1))
                displayedLines = linesToShow
                Log.d(TAG, "📋 Instantly displayed ${linesToShow.size} lines from report for ply $currentPlyIndex (total: ${reportLines.size})")
            } else {
                // Если нет линий в отчете, сохраняем текущие линии (не очищаем!)
                val cachedLines = lastValidLines[currentFen]
                if (cachedLines != null && cachedLines.isNotEmpty()) {
                    displayedLines = cachedLines.take(viewSettings.numberOfLines.coerceAtLeast(1))
                    Log.d(TAG, "📋 Using cached lines for position")
                } else {
                    // Только если совсем нет кэша - оставляем текущие displayedLines как есть
                    Log.d(TAG, "⚠️ No lines in report or cache, keeping current displayed lines")
                }
            }
        }
    }

    // ИСПРАВЛЕНИЕ: СТАБИЛЬНЫЕ линии БЕЗ мигания - обновляем ТОЛЬКО когда получены ПОЛНЫЕ данные
    fun startAutoDepthAnalysis(fen: String, startDepth: Int, maxDepth: Int, multiPv: Int) {
        if (fen.isBlank()) return

        analysisJob?.cancel()
        isAnalysisRunning = true
        currentDepth = startDepth

        // КРИТИЧНО: НЕ очищаем displayedLines! Сохраняем текущие линии до получения новых
        // Если уже есть последние валидные линии для этой позиции, используем их
        val cachedLines = lastValidLines[fen]
        if (cachedLines != null && cachedLines.isNotEmpty()) {
            displayedLines = cachedLines.take(multiPv)
            Log.d(TAG, "🔄 Keeping stable lines from cache during analysis start")
        }
        // Иначе: оставляем текущие displayedLines как есть

        analysisJob = scope.launch {
            try {
                for (depth in startDepth..maxDepth) {
                    if (!isActive) break

                    currentDepth = depth
                    val stateKey = "$fen-$depth-$multiPv"

                    // Проверяем кэш
                    val cached = linesStateMap[stateKey]
                    if (cached != null && !cached.isAnalyzing) {
                        val linesToShow = if (cached.isFromReport) {
                            cached.lines
                        } else {
                            val normalizedLines = normalizeLinesToWhitePOV(cached.lines.map { lineEval ->
                                EngineClient.LineDTO(
                                    pv = lineEval.pv,
                                    cp = lineEval.cp,
                                    mate = lineEval.mate,
                                    depth = lineEval.depth,
                                    multiPv = lineEval.multiPv ?: 1
                                )
                            }, fen)

                            normalizedLines.map { dto ->
                                LineEval(
                                    pv = dto.pv,
                                    cp = dto.cp,
                                    mate = dto.mate,
                                    depth = dto.depth,
                                    best = dto.pv.firstOrNull(),
                                    multiPv = dto.multiPv
                                )
                            }
                        }

                        val sortedLines = sortLinesByQuality(linesToShow)
                        displayedLines = sortedLines.take(multiPv)
                        lastValidLines[fen] = sortedLines
                        continue
                    }

                    // КРИТИЧНО: НЕ обновляем displayedLines во время стриминга - только в конце!
                    var pendingLines: List<LineEval>? = null
                    var lastStreamedLines: List<LineEval>? = null

                    val finalResult = evaluateFenDetailedStreaming(
                        fen = fen,
                        depth = depth,
                        multiPv = multiPv,
                        skillLevel = null
                    ) { linesDto ->
                        // Собираем данные, но НЕ обновляем UI
                        val validLines = linesDto.filter { l ->
                            l.pv.isNotEmpty() && l.pv.size >= 2 && (l.cp != null || l.mate != null)
                        }

                        if (validLines.size >= multiPv) {
                            val normalizedLines = normalizeLinesToWhitePOV(validLines, fen)

                            val newLines = normalizedLines.map { l ->
                                LineEval(
                                    pv = l.pv,
                                    cp = l.cp,
                                    mate = l.mate,
                                    best = l.pv.firstOrNull(),
                                    depth = l.depth,
                                    multiPv = l.multiPv
                                )
                            }

                            if (newLines.size >= multiPv) {
                                lastStreamedLines = sortLinesByQuality(newLines)
                            }
                        }
                    }

                    // Обработка финального результата
                    val finalValidLines = finalResult.lines.filter { l ->
                        l.pv.isNotEmpty() && l.pv.size >= 2 && (l.cp != null || l.mate != null)
                    }

                    if (finalValidLines.size >= multiPv) {
                        val normalizedFinalLines = normalizeLinesToWhitePOV(finalValidLines, fen)

                        val finalLines = normalizedFinalLines.map { l ->
                            LineEval(
                                pv = l.pv,
                                cp = l.cp,
                                mate = l.mate,
                                best = l.pv.firstOrNull(),
                                depth = l.depth,
                                multiPv = l.multiPv
                            )
                        }

                        val sortedFinalLines = sortLinesByQuality(finalLines)

                        // КРИТИЧНО: Обновляем UI ТОЛЬКО ОДИН РАЗ с полными данными
                        displayedLines = sortedFinalLines.take(multiPv)
                        lastValidLines[fen] = sortedFinalLines

                        linesStateMap[stateKey] = PositionLinesState(
                            lines = sortedFinalLines,
                            isAnalyzing = false,
                            depth = depth,
                            multiPv = multiPv,
                            isFromReport = false
                        )
                        Log.d(TAG, "✅ Updated displayed lines for depth $depth with ${sortedFinalLines.size} lines")
                    } else if (lastStreamedLines != null && lastStreamedLines!!.size >= multiPv) {
                        // Используем последние валидные промежуточные данные
                        displayedLines = lastStreamedLines!!.take(multiPv)
                        lastValidLines[fen] = lastStreamedLines!!
                        Log.d(TAG, "✅ Updated displayed lines from streaming data")
                    }
                    // Иначе: линии остаются прежними (не обновляем displayedLines)
                }
            } catch (e: Exception) {
                if (e !is CancellationException) {
                    Log.e(TAG, "❌ Analysis error: ${e.message}", e)
                }
                // Восстанавливаем последние валидные линии при ошибке
                lastValidLines[fen]?.let {
                    if (it.isNotEmpty()) {
                        displayedLines = it.take(multiPv)
                    }
                }
            } finally {
                isAnalysisRunning = false
            }
        }
    }

    LaunchedEffect(currentFen, targetMultiPv, variationActive, analysisVersion) {
        if (currentFen.isNotBlank()) {
            delay(100)
            startAutoDepthAnalysis(currentFen, 12, 18, targetMultiPv)
        }
    }

    fun evalOfPosition(pos: PositionEval?): Float {
        val line = pos?.lines?.firstOrNull()
        return when {
            line?.cp != null -> line.cp / 100f
            line?.mate != null -> if (line.mate > 0) 30f else -30f
            else -> 0f
        }
    }

    fun playMoveSound(cls: MoveClass?, isCapture: Boolean) {
        val resId = when {
            cls == MoveClass.INACCURACY || cls == MoveClass.MISTAKE || cls == MoveClass.BLUNDER ->
                R.raw.error
            isCapture -> R.raw.capture
            else -> R.raw.move
        }
        try {
            MediaPlayer.create(ctx, resId)?.apply {
                setOnCompletionListener { it.release() }
                start()
            }
        } catch (_: Exception) {}
    }

    fun handleSquareClick(square: String) {
        if (isAnalyzing) return

        val baseFen = if (variationActive) variationFen
        else report.positions.getOrNull(currentPlyIndex)?.fen
        val boardFenNow = baseFen ?: return

        val board = Board().apply { loadFromFen(boardFenNow) }

        if (selectedSquare != null && selectedSquare.equals(square, ignoreCase = true)) {
            selectedSquare = null
            legalTargets = emptySet()
            return
        }

        if (selectedSquare == null) {
            selectedSquare = square.lowercase()
            val all = MoveGenerator.generateLegalMoves(board)
            legalTargets = all.filter { it.from.toString().equals(selectedSquare, true) }
                .map { it.to.toString().lowercase() }
                .toSet()
            if (legalTargets.isEmpty()) selectedSquare = null
            return
        }

        val from = selectedSquare!!.lowercase()
        val to   = square.lowercase()
        run {
            val all = MoveGenerator.generateLegalMoves(board)
            val hasMovesForNew = all.any { it.from.toString().equals(to, true) }
            val isDirectMove = all.any { it.from.toString().equals(from, true) && it.to.toString().equals(to, true) }
            if (hasMovesForNew && !isDirectMove) {
                selectedSquare = to
                legalTargets = all.filter { it.from.toString().equals(selectedSquare, true) }
                    .map { it.to.toString().lowercase() }
                    .toSet()
                return
            }
        }

        val legalMoves = MoveGenerator.generateLegalMoves(board)
        val move = legalMoves.firstOrNull { it.from.toString().equals(from, true) && it.to.toString().equals(to, true) }
            ?: legalMoves.firstOrNull { it.toString().equals(from + to, true) }
        if (move == null) {
            selectedSquare = null
            legalTargets = emptySet()
            return
        }

        val beforeFen = board.fen
        val captured = board.getPiece(move.to) != Piece.NONE
        board.doMove(move)
        val afterFen = board.fen
        val uciMove = buildString {
            append(from).append(to)
            move.promotion?.pieceType?.let {
                append(
                    when (it) {
                        PieceType.QUEEN  -> "q"
                        PieceType.ROOK   -> "r"
                        PieceType.BISHOP -> "b"
                        PieceType.KNIGHT -> "n"
                        else -> ""
                    }
                )
            }
        }

        variationActive = true
        variationFen = afterFen
        variationLastMove = from to to
        isAutoPlaying = false
        selectedSquare = null
        legalTargets = emptySet()
        isAnalyzing = true

        scope.launch {
            try {
                val (newEval, moveClass, bestMove) = analyzeMoveRealtime(
                    beforeFen = beforeFen,
                    afterFen = afterFen,
                    uciMove = uciMove,
                    depth = currentDepth,
                    multiPv = targetMultiPv
                )
                variationEval = newEval
                variationMoveClass = moveClass
                variationBestUci = bestMove
                playMoveSound(moveClass, captured)

                analysisVersion++

            } catch (e: Exception) {
                Log.e(TAG, "Error in handleSquareClick", e)
                variationEval = evalOfPosition(report.positions.getOrNull(currentPlyIndex))
                variationMoveClass = MoveClass.OKAY
                variationBestUci = null
            } finally {
                isAnalyzing = false
            }
        }
    }

    fun onClickPvMove(lineIdx: Int, moveIdx: Int) {
        val baseFen = if (variationActive) variationFen
        else report.positions.getOrNull(currentPlyIndex)?.fen
        val line = displayedLines.getOrNull(lineIdx) ?: return
        val fen0 = baseFen ?: return
        val pv = line.pv
        if (moveIdx !in pv.indices) return

        val b = Board().apply { loadFromFen(fen0) }
        for (i in 0 until moveIdx) {
            val m = findLegalMove(b, pv[i]) ?: return
            b.doMove(m)
        }
        val before = b.fen
        val move = findLegalMove(b, pv[moveIdx]) ?: return
        val from = move.from.toString().lowercase()
        val to   = move.to.toString().lowercase()
        val uci = buildString {
            append(from).append(to)
            move.promotion?.pieceType?.let {
                append(
                    when (it) {
                        PieceType.QUEEN  -> "q"
                        PieceType.ROOK   -> "r"
                        PieceType.BISHOP -> "b"
                        PieceType.KNIGHT -> "n"
                        else -> ""
                    }
                )
            }
        }
        val captured = b.getPiece(move.to) != Piece.NONE
        b.doMove(move)
        val after = b.fen

        variationActive = true
        variationFen = after
        variationLastMove = from to to
        isAutoPlaying = false
        selectedSquare = null
        legalTargets = emptySet()
        isAnalyzing = true

        scope.launch {
            try {
                val (newEval, moveClass, bestMove) = analyzeMoveRealtime(
                    beforeFen = before,
                    afterFen = after,
                    uciMove = uci,
                    depth = currentDepth,
                    multiPv = targetMultiPv
                )
                variationEval = newEval
                variationMoveClass = moveClass
                variationBestUci = bestMove
                playMoveSound(moveClass, captured)

                analysisVersion++

            } catch (e: Exception) {
                Log.e(TAG, "Error in onClickPvMove", e)
                variationEval = evalOfPosition(report.positions.getOrNull(currentPlyIndex))
                variationMoveClass = MoveClass.OKAY
                variationBestUci = null
            } finally {
                isAnalyzing = false
            }
        }
    }

    fun exitVariation() {
        if (!variationActive) return
        variationActive = false
        variationFen = null
        variationEval = null
        variationBestUci = null
        variationMoveClass = null
        variationLastMove = null
        selectedSquare = null
        legalTargets = emptySet()

        val reportLines = sortLinesByQuality(report.positions.getOrNull(currentPlyIndex)?.lines ?: emptyList())
        if (reportLines.isNotEmpty()) {
            displayedLines = reportLines.take(viewSettings.numberOfLines)
        }
    }

    fun seekTo(index: Int) {
        variationActive = false
        selectedSquare = null
        legalTargets = emptySet()
        isAnalyzing = false
        currentPlyIndex = index.coerceIn(0, report.positions.lastIndex)

        if (currentPlyIndex > 0) {
            val mv = report.moves.getOrNull(currentPlyIndex - 1)
            playMoveSound(mv?.classification, mv?.san?.contains('x') == true)
        }
    }

    fun goNext() { if (!isAnalyzing && currentPlyIndex < report.positions.lastIndex) seekTo(currentPlyIndex + 1) }
    fun goPrev() { if (!isAnalyzing && currentPlyIndex > 0) seekTo(currentPlyIndex - 1) }

    Scaffold(
        containerColor = bgColor,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.game_analysis)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.Default.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                            tint = Color.White
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showDepthDialog = true }) {
                        Badge(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = Color.White
                        ) {
                            Text(
                                "$currentDepth",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    IconButton(onClick = { showSettingsDialog = true }) {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = stringResource(R.string.settings),
                            tint = Color.White
                        )
                    }

                    IconButton(
                        onClick = { if (!isAnalyzing) isWhiteBottom = !isWhiteBottom },
                        enabled = !isAnalyzing
                    ) {
                        Icon(
                            Icons.Default.ScreenRotation,
                            contentDescription = stringResource(R.string.flip_board),
                            tint = if (isAnalyzing) Color.Gray else Color.White
                        )
                    }
                },colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = surfaceColor,
                    titleContentColor = Color.White
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(bgColor)
        ) {
            val topIsWhite = !isWhiteBottom
            val topName = if (topIsWhite) {
                report.header.white ?: stringResource(R.string.white)
            } else {
                report.header.black ?: stringResource(R.string.black)
            }
            val topElo = if (topIsWhite) report.header.whiteElo else report.header.blackElo

            val topClock = if (topIsWhite) {
                val moveIndex = currentPlyIndex / 2
                clockData?.white?.getOrNull(moveIndex)
            } else {
                val moveIndex = if (currentPlyIndex > 0) (currentPlyIndex - 1) / 2 else 0
                clockData?.black?.getOrNull(moveIndex)
            }

            val topActive = if (!variationActive) {
                (currentPlyIndex % 2 == 0 && topIsWhite) || (currentPlyIndex % 2 == 1 && !topIsWhite)
            } else false

            // ИСПРАВЛЕНИЕ: Тонкий Eval bar сверху (если выбрано)
            if (viewSettings.showEvalBar && viewSettings.evalBarPosition == EvalBarPosition.TOP) {
                val evalPositions: List<PositionEval>
                val evalIndex: Int
                if (variationActive && variationEval != null) {
                    val evalCp = (variationEval!! * 100).toInt()
                    val fakeLine = LineEval(pv = emptyList(), cp = evalCp, mate = null, best = null)
                    val baseFen = variationFen ?: report.positions.getOrNull(currentPlyIndex)?.fen.orEmpty()
                    val fakePos = PositionEval(fen = baseFen, idx = 0, lines = listOf(fakeLine))
                    evalPositions = listOf(fakePos)
                    evalIndex = 0
                } else {
                    evalPositions = report.positions
                    evalIndex = currentPlyIndex
                }

                HorizontalEvalBar(
                    positions = evalPositions,
                    currentPlyIndex = evalIndex,
                    isWhiteBottom = isWhiteBottom,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(24.dp) // ИСПРАВЛЕНИЕ: Тонкий eval bar
                )
            }

            // ИСПРАВЛЕНИЕ: Фиксированная высота для линий движка
            if (viewSettings.showEngineLines && displayedLines.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(
                            when (viewSettings.numberOfLines) {
                                1 -> 34.dp
                                2 -> 66.dp
                                3 -> 98.dp
                                else -> 66.dp
                            }
                        )
                        .background(cardColor)
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    CompactEngineLines(
                        baseFen = currentFen,
                        lines = displayedLines,
                        onClickMoveInLine = ::onClickPvMove,
                        isAnalyzing = isAnalysisRunning,
                        currentDepth = currentDepth
                    )
                }
            }

            PlayerCard(
                name = topName,
                rating = topElo,
                clock = topClock,
                isActive = topActive,
                inverted = !topIsWhite,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(cardColor)
                    .padding(horizontal = 12.dp, vertical = 2.dp)
            )

            // ИСПРАВЛЕНИЕ: Доска с правильным весом
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                when (viewSettings.evalBarPosition) {
                    EvalBarPosition.LEFT -> {
                        Row(modifier = Modifier.fillMaxSize()) {
                            if (viewSettings.showEvalBar) {
                                val evalPositions: List<PositionEval>
                                val evalIndex: Int
                                if (variationActive && variationEval != null) {
                                    val evalCp = (variationEval!! * 100).toInt()
                                    val fakeLine = LineEval(pv = emptyList(), cp = evalCp, mate = null, best = null)
                                    val baseFen = variationFen ?: report.positions.getOrNull(currentPlyIndex)?.fen.orEmpty()
                                    val fakePos = PositionEval(fen = baseFen, idx = 0, lines = listOf(fakeLine))
                                    evalPositions = listOf(fakePos)
                                    evalIndex = 0
                                } else {
                                    evalPositions = report.positions
                                    evalIndex = currentPlyIndex
                                }

                                com.github.movesense.ui.components.EvalBar(
                                    positions = evalPositions,
                                    currentPlyIndex = evalIndex,
                                    isWhiteBottom = isWhiteBottom,
                                    modifier = Modifier
                                        .fillMaxHeight()
                                        .width(16.dp) // ИСПРАВЛЕНИЕ: Тонкий eval bar
                                )
                            }

                            Box(
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .weight(1f)
                            ) {
                                BoardWithOverlay(
                                    currentFen = currentFen,
                                    report = report,
                                    currentPlyIndex = currentPlyIndex,
                                    variationActive = variationActive,
                                    variationLastMove = variationLastMove,
                                    variationMoveClass = variationMoveClass,
                                    variationBestUci = variationBestUci,
                                    isWhiteBottom = isWhiteBottom,
                                    selectedSquare = selectedSquare,
                                    legalTargets = legalTargets,
                                    isAnalyzing = isAnalyzing,
                                    viewSettings = viewSettings,
                                    onSquareClick = ::handleSquareClick
                                )
                            }
                        }
                    }

                    EvalBarPosition.TOP -> {
                        Box(modifier = Modifier.fillMaxSize()) {
                            BoardWithOverlay(
                                currentFen = currentFen,
                                report = report,
                                currentPlyIndex = currentPlyIndex,
                                variationActive = variationActive,
                                variationLastMove = variationLastMove,
                                variationMoveClass = variationMoveClass,
                                variationBestUci = variationBestUci,
                                isWhiteBottom = isWhiteBottom,
                                selectedSquare = selectedSquare,
                                legalTargets = legalTargets,
                                isAnalyzing = isAnalyzing,
                                viewSettings = viewSettings,
                                onSquareClick = ::handleSquareClick
                            )
                        }
                    }
                }
            }

            val bottomIsWhite = isWhiteBottom
            val bottomName = if (bottomIsWhite) {
                report.header.white ?: stringResource(R.string.white)
            } else {
                report.header.black ?: stringResource(R.string.black)
            }
            val bottomElo = if (bottomIsWhite) report.header.whiteElo else report.header.blackElo

            val bottomClock = if (bottomIsWhite) {
                val moveIndex = currentPlyIndex / 2
                clockData?.white?.getOrNull(moveIndex)
            } else {
                val moveIndex = if (currentPlyIndex > 0) (currentPlyIndex - 1) / 2 else 0
                clockData?.black?.getOrNull(moveIndex)
            }

            val bottomActive = if (!variationActive) {
                (currentPlyIndex % 2 == 0 && bottomIsWhite) || (currentPlyIndex % 2 == 1 && !bottomIsWhite)
            } else false

            PlayerCard(
                name = bottomName,
                rating = bottomElo,
                clock = bottomClock,
                isActive = bottomActive,
                inverted = !bottomIsWhite,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(cardColor)
                    .padding(horizontal = 12.dp, vertical = 2.dp)
            )

            // ИСПРАВЛЕНИЕ: Только стрелки по краям
            MovesCarousel(
                report = report,
                currentPlyIndex = currentPlyIndex,
                onSeekTo = { if (!isAnalyzing) seekTo(it) },
                onPrev = { if (!isAnalyzing) goPrev() },
                onNext = { if (!isAnalyzing) goNext() },
                modifier = Modifier.fillMaxWidth()
            )

            // ИСПРАВЛЕНИЕ: Компактная строка управления с увеличенными кнопками
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(cardColor)
                    .padding(vertical = 6.dp, horizontal = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = { if (!isAnalyzing) seekTo(0) },
                    enabled = !isAnalyzing,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        Icons.Default.SkipPrevious,
                        contentDescription = stringResource(R.string.to_start),
                        tint = if (isAnalyzing) Color.Gray else Color.White,
                        modifier = Modifier.size(32.dp)
                    )
                }

                IconButton(
                    onClick = {
                        if (!isAnalyzing) {
                            isAutoPlaying = !isAutoPlaying
                            variationActive = false
                            selectedSquare = null
                        }
                    },
                    enabled = !isAnalyzing,
                    modifier = Modifier
                        .size(56.dp)
                        .background(
                            if (isAnalyzing) Color.Gray else MaterialTheme.colorScheme.primary,
                            CircleShape
                        )
                ) {
                    Icon(
                        if (isAutoPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isAutoPlaying) {
                            stringResource(R.string.pause)
                        } else {
                            stringResource(R.string.play)
                        },
                        tint = Color.White,
                        modifier = Modifier.size(32.dp)
                    )
                }

                IconButton(
                    onClick = { if (!isAnalyzing) seekTo(report.positions.lastIndex) },
                    enabled = !isAnalyzing,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        Icons.Default.SkipNext,
                        contentDescription = stringResource(R.string.to_end),
                        tint = if (isAnalyzing) Color.Gray else Color.White,
                        modifier = Modifier.size(32.dp)
                    )
                }
            }

            if (variationActive) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFFF9800).copy(alpha = 0.2f)),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.variation_mode),
                                color = Color(0xFFFF9800),
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                        }
                        OutlinedButton(
                            onClick = { if (!isAnalyzing) exitVariation() },
                            enabled = !isAnalyzing,
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = Color(0xFFFF9800)
                            ),
                            modifier = Modifier.height(32.dp)
                        ) {
                            Text(stringResource(R.string.exit_variation), fontSize = 12.sp)
                        }
                    }
                }
            }

            LaunchedEffect(isAutoPlaying, currentPlyIndex, isAnalyzing) {
                if (isAutoPlaying && !isAnalyzing) {
                    delay(1500)
                    if (currentPlyIndex < report.positions.lastIndex) goNext() else isAutoPlaying = false
                }
            }
        }
    }

    if (showSettingsDialog) {
        SettingsDialog(
            viewSettings = viewSettings,
            onDismiss = { showSettingsDialog = false },
            onSettingsChange = { newSettings ->
                viewSettings = newSettings
                targetMultiPv = newSettings.numberOfLines

                val reportLines = sortLinesByQuality(report.positions.getOrNull(currentPlyIndex)?.lines ?: emptyList())
                if (reportLines.isNotEmpty()) {
                    displayedLines = reportLines.take(newSettings.numberOfLines)
                }

                analysisVersion++
            }
        )
    }

    if (showDepthDialog) {
        DepthDialog(
            currentDepth = targetDepth,
            onDismiss = { showDepthDialog = false },
            onDepthSelected = { depth ->
                targetDepth = depth
                if (!variationActive) {
                    positionSettings[currentPlyIndex] = Pair(depth, targetMultiPv)
                }
                analysisVersion++
                showDepthDialog = false
            }
        )
    }
}

@Composable
private fun CompactEngineLines(
    baseFen: String,
    lines: List<LineEval>,
    onClickMoveInLine: (lineIdx: Int, moveIdx: Int) -> Unit,
    isAnalyzing: Boolean,
    currentDepth: Int,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        lines.forEachIndexed { lineIdx, line ->
            CompactPvRow(
                baseFen = baseFen,
                line = line,
                lineNumber = lineIdx + 1,
                onClickMoveAtIndex = { moveIdx -> onClickMoveInLine(lineIdx, moveIdx) },
                modifier = Modifier.fillMaxWidth()
            )
            if (lineIdx < lines.lastIndex) {
                Spacer(Modifier.height(4.dp))
            }
        }
    }
}

@Composable
private fun CompactPvRow(
    baseFen: String,
    line: LineEval,
    lineNumber: Int,
    onClickMoveAtIndex: (idx: Int) -> Unit,
    modifier: Modifier = Modifier
) {
    if (line.pv.isEmpty()) return

    val tokens = remember(baseFen, line.pv) { buildIconTokens(baseFen, line.pv) }

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        CompactEvalChip(line = line)

        Spacer(Modifier.width(6.dp))

        LazyRow(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            itemsIndexed(tokens.take(10)) { idx, token ->
                Row(
                    modifier = Modifier
                        .clickable { onClickMoveAtIndex(idx) }
                        .background(
                            Color.White.copy(alpha = 0.05f),
                            RoundedCornerShape(4.dp)
                        )
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // ИСПРАВЛЕНИЕ: Нумерация продолжается от текущего хода партии
                    val baseFenParts = baseFen.split(" ")
                    val fullMoveNumber = baseFenParts.getOrNull(5)?.toIntOrNull() ?: 1
                    val isWhiteToMove = baseFenParts.getOrNull(1) == "w"

                    // Вычисляем номер хода с учетом текущей позиции
                    val moveNumber = if (idx % 2 == 0) {
                        if (isWhiteToMove) {
                            "$fullMoveNumber."
                        } else {
                            "${fullMoveNumber + idx / 2}."
                        }
                    } else {
                        if (isWhiteToMove) {
                            "${fullMoveNumber + (idx + 1) / 2}..."
                        } else {
                            "${fullMoveNumber + (idx + 1) / 2}..."
                        }
                    }

                    Text(
                        moveNumber,
                        color = Color.White.copy(alpha = 0.4f),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium
                    )

                    Spacer(Modifier.width(3.dp))

                    PieceAssetIcon(token.iconAsset, 14.dp)
                    Spacer(Modifier.width(2.dp))

                    val suffix = buildString {
                        append(token.toSquare)
                        if (token.capture) append("x")
                        append(token.promoSuffix)
                    }
                    Text(
                        suffix,
                        color = Color.White.copy(alpha = 0.85f),
                        fontSize = 11.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun CompactEvalChip(line: LineEval, modifier: Modifier = Modifier) {
    val txt = when {
        line.mate != null -> if (line.mate!! > 0) "M${abs(line.mate!!)}" else "M-${abs(line.mate!!)}"
        line.cp != null   -> String.format("%+.1f", line.cp!! / 100f)
        else -> "—"
    }

    val backgroundColor = when {
        line.mate != null -> if (line.mate!! > 0) Color.White else Color(0xFF1C1C1C)
        line.cp != null -> if (line.cp!! > 0) Color.White else Color(0xFF1C1C1C)
        else -> Color(0xFF2F2F2F)
    }

    val textColor = when {
        line.mate != null -> if (line.mate!! > 0) Color.Black else Color.White
        line.cp != null -> if (line.cp!! > 0) Color.Black else Color.White
        else -> Color.White
    }

    Box(
        modifier = modifier
            .background(backgroundColor, RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(
            txt,
            color = textColor,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun BoardWithOverlay(
    currentFen: String,
    report: FullReport,
    currentPlyIndex: Int,
    variationActive: Boolean,
    variationLastMove: Pair<String, String>?,
    variationMoveClass: MoveClass?,
    variationBestUci: String?,
    isWhiteBottom: Boolean,
    selectedSquare: String?,
    legalTargets: Set<String>,
    isAnalyzing: Boolean,
    viewSettings: ViewSettings,
    onSquareClick: (String) -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        val currentPosition = report.positions.getOrNull(currentPlyIndex)

        val lastMovePairActual = if (currentPlyIndex > 0 && !variationActive) {
            val uci = report.moves[currentPlyIndex - 1].uci
            if (uci.length >= 4) uci.substring(0, 2) to uci.substring(2, 4) else null
        } else null

        val lastMoveClassActual = if (!variationActive) {
            report.moves.getOrNull(currentPlyIndex - 1)?.classification
        } else null

        val bestUciActual: String? = if (!variationActive) {
            report.positions
                .getOrNull(max(0, currentPlyIndex - 1))
                ?.lines?.firstOrNull()
                ?.pv?.firstOrNull()
        } else null

        val lastMovePair = if (variationActive) variationLastMove else lastMovePairActual
        val moveClass = if (variationActive) variationMoveClass else lastMoveClassActual
        val bestUci = if (variationActive) variationBestUci else bestUciActual

        val showBestArrow = viewSettings.showBestMoveArrow && when (moveClass) {
            MoveClass.INACCURACY, MoveClass.MISTAKE, MoveClass.BLUNDER -> true
            else -> false
        }

        BoardCanvas(
            fen = currentFen,
            lastMove = lastMovePair,
            moveClass = moveClass,
            bestMoveUci = if (showBestArrow) bestUci else null,
            showBestArrow = showBestArrow,
            isWhiteBottom = isWhiteBottom,
            selectedSquare = selectedSquare,
            legalMoves = legalTargets,
            onSquareClick = { onSquareClick(it) },
            modifier = Modifier.fillMaxSize()
        )

        if (isAnalyzing) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.3f)),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(48.dp),
                    color = MaterialTheme.colorScheme.primary,
                    strokeWidth = 3.dp
                )
            }
        }
    }
}

@Composable
private fun HorizontalEvalBar(
    positions: List<PositionEval>,
    currentPlyIndex: Int,
    isWhiteBottom: Boolean,
    modifier: Modifier = Modifier
) {
    // КРИТИЧНО: Стабильная оценка без миганий
    val evaluation = remember(positions, currentPlyIndex) {
        val pos = positions.getOrNull(currentPlyIndex)
        val line = pos?.lines?.firstOrNull()

        val eval = when {
            line?.cp != null -> {
                val cpValue = line.cp / 100.0f
                Log.d(TAG, "📊 Eval bar: ply=$currentPlyIndex, cp=${line.cp}, eval=$cpValue")
                cpValue
            }
            line?.mate != null -> {
                val mateValue = if (line.mate > 0) 30.0f else -30.0f
                Log.d(TAG, "📊 Eval bar: ply=$currentPlyIndex, mate=${line.mate}, eval=$mateValue")
                mateValue
            }
            else -> {
                Log.d(TAG, "⚠️ Eval bar: ply=$currentPlyIndex, no evaluation available")
                0.0f
            }
        }

        // Возвращаем стабильное значение
        eval
    }

    val cap = 8.0f
    val clamped = evaluation.coerceIn(-cap, cap)
    val t = (clamped + cap) / (2 * cap)

    // ИСПРАВЛЕНИЕ: Более плавная анимация с защитой от резких скачков
    val animT = remember { Animatable(t.coerceIn(0.001f, 0.999f)) }
    LaunchedEffect(t) {
        val targetT = t.coerceIn(0.001f, 0.999f)
        // Проверяем, не слишком ли большой скачок
        val currentValue = animT.value
        val diff = kotlin.math.abs(targetT - currentValue)

        if (diff > 0.5f) {
            // Большой скачок - быстрая анимация
            animT.animateTo(targetT, tween(200, easing = FastOutSlowInEasing))
        } else {
            // Обычная плавная анимация
            animT.animateTo(targetT, tween(350, easing = FastOutSlowInEasing))
        }
    }

    Box(modifier = modifier) {
        Row(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .weight(1f - animT.value)
                    .background(Color.Black)
            )
            Box(
                Modifier
                    .fillMaxHeight()
                    .weight(animT.value)
                    .background(Color.White)
            )
        }
    }
}

@Composable
private fun SettingsDialog(
    viewSettings: ViewSettings,
    onDismiss: () -> Unit,
    onSettingsChange: (ViewSettings) -> Unit
) {
    var localSettings by remember { mutableStateOf(viewSettings) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(stringResource(R.string.show_eval_bar))
                    Switch(
                        checked = localSettings.showEvalBar,
                        onCheckedChange = { localSettings = localSettings.copy(showEvalBar = it) }
                    )
                }

                if (localSettings.showEvalBar) {
                    Text(
                        stringResource(R.string.eval_bar_position),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = localSettings.evalBarPosition == EvalBarPosition.LEFT,
                            onClick = { localSettings = localSettings.copy(evalBarPosition = EvalBarPosition.LEFT) },
                            label = { Text(stringResource(R.string.left)) },
                            modifier = Modifier.weight(1f)
                        )
                        FilterChip(
                            selected = localSettings.evalBarPosition == EvalBarPosition.TOP,
                            onClick = { localSettings = localSettings.copy(evalBarPosition = EvalBarPosition.TOP) },
                            label = { Text(stringResource(R.string.top)) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(stringResource(R.string.show_best_move_arrows))
                    Switch(
                        checked = localSettings.showBestMoveArrow,
                        onCheckedChange = { localSettings = localSettings.copy(showBestMoveArrow = it) }
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.show_threats))
                        Text(
                            stringResource(R.string.coming_soon),
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.Gray
                        )
                    }
                    Switch(
                        checked = localSettings.showThreatArrows,
                        onCheckedChange = { },
                        enabled = false
                    )
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(stringResource(R.string.show_engine_lines))
                    Switch(
                        checked = localSettings.showEngineLines,
                        onCheckedChange = { localSettings = localSettings.copy(showEngineLines = it) }
                    )
                }

                if (localSettings.showEngineLines) {
                    Text(
                        stringResource(R.string.number_of_lines),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        for (n in 1..3) {
                            FilterChip(
                                selected = localSettings.numberOfLines == n,
                                onClick = { localSettings = localSettings.copy(numberOfLines = n) },
                                label = { Text("$n") },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSettingsChange(localSettings)
                onDismiss()
            }) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@Composable
private fun DepthDialog(
    currentDepth: Int,
    onDismiss: () -> Unit,
    onDepthSelected: (Int) -> Unit
) {
    val depths = listOf(8, 10, 12, 14, 16, 18, 20, 22, 24)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.select_depth)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.depth_description),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 12.dp)
                )

                depths.chunked(3).forEach { row ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        row.forEach { depth ->
                            FilterChip(
                                selected = currentDepth == depth,
                                onClick = { onDepthSelected(depth) },
                                label = { Text("$depth") },
                                modifier = Modifier.weight(1f)
                            )
                        }

                        repeat(3 - row.size) {
                            Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.close))
            }
        }
    )
}

private data class PvToken(
    val iconAsset: String,
    val toSquare: String,
    val capture: Boolean,
    val promoSuffix: String = ""
)

private fun buildIconTokens(fen: String, pv: List<String>): List<PvToken> {
    val b = Board().apply { loadFromFen(fen) }
    val out = mutableListOf<PvToken>()
    for (uci in pv) {
        val legal = findLegalMove(b, uci) ?: break
        val mover = b.getPiece(legal.from)
        val dst   = b.getPiece(legal.to)
        val capture = dst != Piece.NONE ||
                (mover.pieceType == PieceType.PAWN && legal.from.file != legal.to.file)
        val promoSuffix = when (legal.promotion?.pieceType) {
            PieceType.QUEEN  -> "=Q"
            PieceType.ROOK   -> "=R"
            PieceType.BISHOP -> "=B"
            PieceType.KNIGHT -> "=N"
            else -> ""
        }
        out += PvToken(
            iconAsset = pieceAssetName(mover),
            toSquare = legal.to.toString().lowercase(),
            capture = capture,
            promoSuffix = promoSuffix
        )
        b.doMove(legal)
    }
    return out
}

@Composable
private fun PlayerCard(
    name: String,
    rating: Int?,
    clock: Int?,
    isActive: Boolean,
    inverted: Boolean,
    modifier: Modifier = Modifier
) {
    val animatedColor by animateColorAsState(
        targetValue = if (isActive) Color.White else Color.White.copy(alpha = 0.6f),
        animationSpec = tween(250),
        label = "playerCardColor"
    )
    Row(
        modifier = modifier
            .background(Color(0xFF1E1C1A), RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .background(if (inverted) Color.Black else Color.White, CircleShape)
            )
            Spacer(Modifier.width(10.dp))
            InitialAvatar(name = name, size = 32.dp)
            Spacer(Modifier.width(10.dp))
            Column {
                Text(
                    text = name,
                    color = animatedColor,
                    fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium,
                    fontSize = 16.sp
                )
                if (rating != null) {
                    Text(
                        text = "($rating)",
                        color = animatedColor.copy(alpha = 0.8f),
                        fontSize = 13.sp
                    )
                }
            }
        }

        if (clock != null) {
            Text(
                text = formatClock(clock),
                color = animatedColor.copy(alpha = 0.9f),
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

private fun formatClock(centiseconds: Int): String {
    val totalSeconds = centiseconds / 100
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val secs = totalSeconds % 60

    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, secs)
    } else {
        "%d:%02d".format(minutes, secs)
    }
}

@Composable
private fun InitialAvatar(
    name: String,
    size: Dp,
    bg: Color = Color(0xFF6D5E4A),
    fg: Color = Color(0xFFF5F3EF)
) {
    val initial = name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
    Box(
        modifier = Modifier
            .size(size)
            .background(bg, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = initial,
            color = fg,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            fontSize = 16.sp
        )
    }
}