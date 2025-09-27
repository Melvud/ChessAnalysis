package com.example.chessanalysis.ui.screens

import android.annotation.SuppressLint
import android.media.MediaPlayer
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.chessanalysis.*
import com.example.chessanalysis.ui.components.BoardCanvas
import com.example.chessanalysis.ui.components.EvalBar
import com.example.chessanalysis.ui.components.MovesCarousel
import com.example.chessanalysis.ui.components.moveClassBadgeRes
import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.move.MoveGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

@SuppressLint("UnsafeOptInUsageError")
@Serializable
data class ClockData(
    val white: List<Int> = emptyList(), // centiseconds per move
    val black: List<Int> = emptyList()
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GameReportScreen(
    report: FullReport,
    onBack: () -> Unit,
    onNextKeyMoment: (() -> Unit)? = null
) {
    val scope = rememberCoroutineScope()
    val androidContext = LocalContext.current
    val userWasWhite = remember(report.header) { true }

    var currentPlyIndex by remember { mutableStateOf(0) }
    var isWhiteBottom by remember { mutableStateOf(userWasWhite) }
    var clockData by remember { mutableStateOf<ClockData?>(null) }
    var isAutoPlaying by remember { mutableStateOf(false) }

    // интерактивные варианты
    var variationActive by remember { mutableStateOf(false) }
    var variationBoard by remember { mutableStateOf<Board?>(null) }
    var variationFen by remember { mutableStateOf<String?>(null) }
    var variationEval by remember { mutableStateOf<Float?>(null) }
    var variationMoveClass by remember { mutableStateOf<MoveClass?>(null) }
    var variationBestUci by remember { mutableStateOf<String?>(null) }
    var variationLastMove by remember { mutableStateOf<Pair<String, String>?>(null) }
    var selectedSquare by remember { mutableStateOf<String?>(null) }

    val bgColor = Color(0xFF161512)
    val surfaceColor = Color(0xFF262522)
    val cardColor = Color(0xFF1E1C1A)

    // часы
    LaunchedEffect(report) {
        scope.launch {
            val local = report.header.pgn?.let { runCatching { parseClockData(it) }.getOrNull() }
            clockData = local ?: fetchClockData(report)
        }
    }

    // звуки ходов
    fun playMoveSound(moveClass: MoveClass?, isCapture: Boolean) {
        val resId = when {
            moveClass == MoveClass.INACCURACY || moveClass == MoveClass.MISTAKE || moveClass == MoveClass.BLUNDER ->
                com.example.chessanalysis.R.raw.error
            isCapture -> com.example.chessanalysis.R.raw.capture
            else -> com.example.chessanalysis.R.raw.move
        }
        val mp = MediaPlayer.create(androidContext, resId)
        mp?.setOnCompletionListener { it.release() }
        mp?.start()
    }

    // ∆ → класс (используем серверные eval/bestmove)
    fun classifyDelta(delta: Float, isBest: Boolean): MoveClass =
        when {
            isBest -> MoveClass.BEST
            delta <= -3f -> MoveClass.BLUNDER
            delta <= -1.5f -> MoveClass.MISTAKE
            delta <= -0.6f -> MoveClass.INACCURACY
            delta <= -0.3f -> MoveClass.OKAY
            else -> MoveClass.EXCELLENT
        }

    fun evalOfPosition(pos: PositionEval?): Float {
        val line = pos?.lines?.firstOrNull() ?: return 0f
        return when {
            line.cp != null -> line.cp / 100.0f
            line.mate != null -> if (line.mate > 0) 30f else -30f
            else -> 0f
        }
    }

    fun seekTo(index: Int) {
        variationActive = false
        val clamped = index.coerceIn(0, report.positions.lastIndex)
        currentPlyIndex = clamped
    }

    fun goNext() { if (currentPlyIndex < report.positions.lastIndex) seekTo(currentPlyIndex + 1) }
    fun goPrev() { if (currentPlyIndex > 0) seekTo(currentPlyIndex - 1) }

    // обработка кликов
    fun handleSquareClick(square: String) {
        val currentPosition = report.positions.getOrNull(currentPlyIndex) ?: return
        if (!variationActive) {
            variationBoard = Board().apply { loadFromFen(currentPosition.fen) }
            variationEval = evalOfPosition(currentPosition)
        }
        val board = variationBoard ?: return

        if (selectedSquare == null) {
            selectedSquare = square.lowercase()
            return
        }

        val from = selectedSquare!!.lowercase()
        val to = square.lowercase()
        selectedSquare = null
        if (from == to) return

        val legalMoves = MoveGenerator.generateLegalMoves(board)
        val move = legalMoves.find { it.from.toString().equals(from, true) && it.to.toString().equals(to, true) }
            ?: legalMoves.find { it.toString().equals(from + to, true) }
        if (move == null) return

        val capturedPiece = board.getPiece(move.to)
        val isCapture = capturedPiece != com.github.bhlangonijr.chesslib.Piece.NONE &&
                capturedPiece.pieceSide != board.sideToMove

        val evalBefore = variationEval ?: evalOfPosition(currentPosition)
        board.doMove(move)
        variationLastMove = from to to
        variationFen = board.fen
        variationActive = true
        isAutoPlaying = false

        // серверная оценка
        scope.launch {
            val fen = variationFen ?: return@launch
            val resp = analyzeFen(fen)
            val newEval: Float = resp.mate?.let { if (it > 0) 30f else -30f } ?: (resp.evaluation?.toFloat() ?: 0f)
            variationEval = newEval
            val bestMoveUci = resp.bestmove ?: resp.continuation?.split(" ")?.firstOrNull()
            variationBestUci = bestMoveUci
            val wasWhiteToMove = board.sideToMove == com.github.bhlangonijr.chesslib.Side.BLACK
            val delta = (newEval - evalBefore) * (if (wasWhiteToMove) 1f else -1f)
            val isBest = bestMoveUci?.equals((from + to), ignoreCase = true) ?: false
            variationMoveClass = classifyDelta(delta, isBest)
            playMoveSound(variationMoveClass, isCapture)
        }
    }

    Scaffold(
        containerColor = bgColor,
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                },
                actions = {
                    IconButton(onClick = { isWhiteBottom = !isWhiteBottom }) {
                        Icon(Icons.Default.ScreenRotation, contentDescription = "Flip board", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
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
            // Верхняя карточка
            val topIsWhite = !isWhiteBottom
            val topName = if (topIsWhite) (report.header.white ?: "White") else (report.header.black ?: "Black")
            val topElo = if (topIsWhite) report.header.whiteElo else report.header.blackElo
            val topClock = if (topIsWhite) clockData?.white?.getOrNull(currentPlyIndex / 2)
            else clockData?.black?.getOrNull(currentPlyIndex / 2)
            val topActive = if (!variationActive) {
                (currentPlyIndex % 2 == 0 && topIsWhite) || (currentPlyIndex % 2 == 1 && !topIsWhite)
            } else false

            PlayerCard(
                name = topName,
                rating = topElo,
                clock = topClock,
                isActive = topActive,
                inverted = !topIsWhite,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(cardColor)
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            )

            // Доска + эвал-бар (ВСЕГДА слева)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f),
                horizontalArrangement = Arrangement.Start
            ) {
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

                EvalBar(
                    positions = evalPositions,
                    currentPlyIndex = evalIndex,
                    isWhiteBottom = isWhiteBottom,
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(20.dp)
                )

                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .weight(1f)
                ) {
                    val currentPosition = report.positions.getOrNull(currentPlyIndex)
                    val lastMovePairActual = if (currentPlyIndex > 0) {
                        val uci = report.moves[currentPlyIndex - 1].uci
                        if (uci.length >= 4) uci.substring(0, 2) to uci.substring(2, 4) else null
                    } else null
                    val lastMoveClassActual = report.moves.getOrNull(currentPlyIndex - 1)?.classification
                    val bestUciActual: String? = report.positions
                        .getOrNull(kotlin.math.max(0, currentPlyIndex - 1))
                        ?.lines?.firstOrNull()
                        ?.pv?.firstOrNull()

                    val boardFen = if (variationActive) variationFen else currentPosition?.fen
                    val lastMovePair = if (variationActive) variationLastMove else lastMovePairActual
                    val moveClass = if (variationActive) variationMoveClass else lastMoveClassActual
                    val bestUci = if (variationActive) variationBestUci else bestUciActual
                    val showBestArrow = when (moveClass) {
                        MoveClass.INACCURACY, MoveClass.MISTAKE, MoveClass.BLUNDER -> true
                        else -> false
                    }
                    boardFen?.let { fen ->
                        BoardCanvas(
                            fen = if (isWhiteBottom) fen else flipFen(fen),
                            lastMove = lastMovePair,
                            moveClass = moveClass,
                            bestMoveUci = bestUci,
                            showBestArrow = showBestArrow,
                            isWhiteBottom = isWhiteBottom,
                            onSquareClick = { handleSquareClick(it) },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }

            // Нижняя карточка
            val bottomIsWhite = isWhiteBottom
            val bottomName = if (bottomIsWhite) (report.header.white ?: "White") else (report.header.black ?: "Black")
            val bottomElo = if (bottomIsWhite) report.header.whiteElo else report.header.blackElo
            val bottomClock = if (bottomIsWhite) clockData?.white?.getOrNull(currentPlyIndex / 2)
            else clockData?.black?.getOrNull(currentPlyIndex / 2)
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
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            )

            // Карусель ходов
            MovesCarousel(
                report = report,
                currentPlyIndex = currentPlyIndex,
                onSeekTo = { seekTo(it) },
                onPrev = { goPrev() },
                onNext = { goNext() },
                modifier = Modifier.fillMaxWidth()
            )

            // Кнопки
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(cardColor)
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { seekTo(0) }) {
                    Icon(Icons.Default.SkipPrevious, contentDescription = "Start", tint = Color.White)
                }
                IconButton(onClick = { goPrev() }) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Previous", tint = Color.White)
                }
                IconButton(
                    onClick = {
                        isAutoPlaying = !isAutoPlaying
                        variationActive = false
                    },
                    modifier = Modifier
                        .size(56.dp)
                        .background(MaterialTheme.colorScheme.primary, CircleShape)
                ) {
                    Icon(if (isAutoPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isAutoPlaying) "Pause" else "Play", tint = Color.White)
                }
                IconButton(onClick = { goNext() }) {
                    Icon(Icons.Default.ArrowForward, contentDescription = "Next", tint = Color.White)
                }
                IconButton(onClick = { seekTo(report.positions.lastIndex) }) {
                    Icon(Icons.Default.SkipNext, contentDescription = "End", tint = Color.White)
                }
            }

            // авто-плей
            LaunchedEffect(isAutoPlaying, currentPlyIndex) {
                if (isAutoPlaying) {
                    kotlinx.coroutines.delay(1500)
                    if (currentPlyIndex < report.positions.lastIndex) goNext() else isAutoPlaying = false
                }
            }
        }
    }
}

// ===== ВСПОМОГАТЕЛЬНОЕ =====

@Composable
private fun PlayerCard(
    name: String,
    rating: Int?,
    clock: Int?, // centiseconds
    isActive: Boolean,
    inverted: Boolean,
    modifier: Modifier = Modifier
) {
    val animatedColor by animateColorAsState(
        targetValue = if (isActive) Color.White else Color.White.copy(alpha = 0.6f),
        animationSpec = tween(250)
    )
    Row(
        modifier = modifier
            .background(Color(0xFF1E1C1A), RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .background(if (inverted) Color.Black else Color.White, CircleShape)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = buildString {
                    append(name)
                    if (rating != null) append(" ($rating)")
                },
                color = animatedColor,
                fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium,
                fontSize = 15.sp
            )
        }
        Text(
            text = clock?.let { formatClock(it) } ?: "—",
            color = animatedColor.copy(alpha = 0.9f),
            fontSize = 14.sp
        )
    }
}

// формат времени
private fun formatClock(centiseconds: Int): String {
    val seconds = centiseconds / 100
    val minutes = seconds / 60
    val secs = seconds % 60
    return "%d:%02d".format(minutes, secs)
}

// переворот FEN
private fun flipFen(fen: String): String {
    val parts = fen.split(" ")
    if (parts.isEmpty()) return fen
    val board = parts[0]
    val ranks = board.split("/")
    val flippedBoard = ranks.reversed().joinToString("/")
    return buildString {
        append(flippedBoard)
        if (parts.size > 1) {
            append(" ")
            append(parts.drop(1).joinToString(" "))
        }
    }
}

// --- ЧАСЫ ---

private suspend fun fetchClockData(report: FullReport): ClockData? = withContext(Dispatchers.IO) {
    try {
        val site = report.header.site ?: return@withContext null
        val gameId = extractGameId(report.header.pgn) ?: return@withContext null
        when (site) {
            Provider.LICHESS -> fetchLichessClocks(gameId)
            Provider.CHESSCOM -> fetchChesscomClocks(gameId)
        }
    } catch (_: Exception) { null }
}

private fun extractGameId(pgn: String?): String? {
    if (pgn.isNullOrBlank()) return null
    val sitePattern = Regex("""\[Site\s+".*/([\w]+)"\]""")
    sitePattern.find(pgn)?.groupValues?.getOrNull(1)?.let { return it }
    val lichessPattern = Regex("""([a-zA-Z0-9]{8})""")
    lichessPattern.find(pgn)?.groupValues?.getOrNull(1)?.let { return it }
    return null
}

private suspend fun fetchLichessClocks(gameId: String): ClockData? {
    val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()
    val url = "https://lichess.org/game/export/$gameId?clocks=true"
    val request = Request.Builder().url(url).build()
    return try {
        client.newCall(request).execute().use { response ->
            if (response.isSuccessful) {
                val body = response.body?.string() ?: return null
                parseClockData(body)
            } else null
        }
    } catch (_: Exception) { null }
}

private suspend fun fetchChesscomClocks(@Suppress("UNUSED_PARAMETER") gameId: String): ClockData? = null

/** Парсим часы вида `[%clk H:MM:SS]` или `[%clk M:SS]`. */
private fun parseClockData(pgn: String): ClockData {
    val clockPattern = Regex("""\[%clk\s+((\d+):)?(\d{1,2}):(\d{1,2})\]""")
    val whiteTimes = mutableListOf<Int>()
    val blackTimes = mutableListOf<Int>()
    var moveIndex = 0
    clockPattern.findAll(pgn).forEach { m ->
        val hours = (m.groups[2]?.value ?: "0").toInt()
        val minutes = (m.groups[3]?.value ?: "0").toInt()
        val seconds = (m.groups[4]?.value ?: "0").toInt()
        val cs = (hours * 3600 + minutes * 60 + seconds) * 100
        if (moveIndex % 2 == 0) whiteTimes.add(cs) else blackTimes.add(cs)
        moveIndex++
    }
    return ClockData(white = whiteTimes, black = blackTimes)
}
