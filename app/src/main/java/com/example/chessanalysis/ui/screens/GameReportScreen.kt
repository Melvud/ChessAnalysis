package com.example.chessanalysis.ui.screens

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
import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.Side
import com.github.bhlangonijr.chesslib.move.MoveGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

// --------------------- Топ-левел утилиты ДЛЯ ЧАСОВ ---------------------

/** Извлечь ID партии из PGN (Site или голый id) */
fun extractGameId(pgn: String?): String? {
    if (pgn.isNullOrBlank()) return null
    val sitePattern = Regex("""\[Site\s+".*/([\w]+)"\]""")
    sitePattern.find(pgn)?.groupValues?.getOrNull(1)?.let { return it }
    val lichessPattern = Regex("""([a-zA-Z0-9]{8})""")
    lichessPattern.find(pgn)?.groupValues?.getOrNull(1)?.let { return it }
    return null
}

/** Парсинг [%clk ...] из PGN в списки времени (сотые доли секунды) */
fun parseClockData(pgn: String): ClockData {
    val clockPattern = Regex("""\[%clk\s+((\d+):)?(\d{1,2}):(\d{1,2})\]""")
    val whiteTimes = mutableListOf<Int>()
    val blackTimes = mutableListOf<Int>()
    var moveIndex = 0

    clockPattern.findAll(pgn).forEach { m ->
        val hours = (m.groups[2]?.value?.toIntOrNull() ?: 0)
        val minutes = (m.groups[3]?.value?.toIntOrNull() ?: 0)
        val seconds = (m.groups[4]?.value?.toIntOrNull() ?: 0)
        val cs = (hours * 3600 + minutes * 60 + seconds) * 100
        if (moveIndex % 2 == 0) whiteTimes.add(cs) else blackTimes.add(cs)
        moveIndex++
    }

    return ClockData(white = whiteTimes, black = blackTimes)
}

/** Получить часы из Lichess экспортера, если они не пришли в PGN */
suspend fun fetchLichessClocks(gameId: String): ClockData? = withContext(Dispatchers.IO) {
    val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()
    val url = "https://lichess.org/game/export/$gameId?clocks=true&moves=false&tags=false"
    val request = Request.Builder()
        .url(url)
        .header("Accept", "application/x-chess-pgn")
        .build()
    try {
        client.newCall(request).execute().use { response ->
            if (response.isSuccessful) {
                val body = response.body?.string() ?: return@withContext null
                parseClockData(body)
            } else null
        }
    } catch (e: Exception) {
        println("Error fetching clocks: ${e.message}")
        null
    }
}

// ----------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GameReportScreen(
    report: FullReport,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current

    // Основные состояния
    var isWhiteBottom by remember { mutableStateOf(report.header.sideToView ?: true) }
    var currentPlyIndex by remember { mutableStateOf(0) }
    var isAutoPlaying by remember { mutableStateOf(false) }

    // Часы
    var clockData by remember { mutableStateOf<ClockData?>(null) }

    // Вариант (интерактивный анализ после кликов)
    var variationActive by remember { mutableStateOf(false) }
    var variationBoard by remember { mutableStateOf<Board?>(null) }
    var variationFen by remember { mutableStateOf<String?>(null) }
    var variationEval by remember { mutableStateOf<Float?>(null) }
    var variationBestUci by remember { mutableStateOf<String?>(null) }
    var variationMoveClass by remember { mutableStateOf<MoveClass?>(null) }
    var variationLastMove by remember { mutableStateOf<Pair<String, String>?>(null) }
    var selectedSquare by remember { mutableStateOf<String?>(null) }
    var legalTargets by remember { mutableStateOf<Set<String>>(emptySet()) }
    var isAnalyzing by remember { mutableStateOf(false) }

    val bgColor = Color(0xFF161512)
    val surfaceColor = Color(0xFF262522)
    val cardColor = Color(0xFF1E1C1A)

    // Загрузка часов
    LaunchedEffect(report) {
        scope.launch {
            val pgn = report.header.pgn
            if (!pgn.isNullOrBlank()) {
                val parsed = parseClockData(pgn)
                if (parsed.white.isNotEmpty() || parsed.black.isNotEmpty()) {
                    clockData = parsed
                } else {
                    // Пробуем загрузить с Lichess
                    val gameId = extractGameId(pgn)
                    if (gameId != null && report.header.site == Provider.LICHESS) {
                        clockData = fetchClockData(report)
                    }
                }
            }
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
                com.example.chessanalysis.R.raw.error
            isCapture -> com.example.chessanalysis.R.raw.capture
            else -> com.example.chessanalysis.R.raw.move
        }
        try {
            MediaPlayer.create(ctx, resId)?.apply {
                setOnCompletionListener { it.release() }
                start()
            }
        } catch (_: Exception) {}
    }

    fun classifyDelta(delta: Float, isBest: Boolean): MoveClass {
        // Исправленная классификация - delta уже с правильным знаком
        return when {
            isBest -> MoveClass.BEST
            delta >= 0.3f -> MoveClass.EXCELLENT
            delta >= 0f -> MoveClass.OKAY
            delta >= -0.6f -> MoveClass.INACCURACY
            delta >= -1.5f -> MoveClass.MISTAKE
            else -> MoveClass.BLUNDER
        }
    }

    fun handleSquareClick(square: String) {
        // Блокируем клики во время анализа
        if (isAnalyzing) return

        val currentPosition = report.positions.getOrNull(currentPlyIndex) ?: return

        // Инициализация доски для варианта, если еще не активен
        if (!variationActive) {
            variationBoard = Board().apply { loadFromFen(currentPosition.fen) }
            variationEval = evalOfPosition(currentPosition)
        }

        val board = variationBoard ?: return

        // Если нет выбранной фигуры - выбираем
        if (selectedSquare == null) {
            selectedSquare = square.lowercase()
            return
        }

        // Пытаемся сделать ход
        val from = selectedSquare!!.lowercase()
        val to = square.lowercase()
        selectedSquare = null

        if (from == to) return

        // Проверяем легальность хода
        val legalMoves = MoveGenerator.generateLegalMoves(board)
        val move = legalMoves.find {
            it.from.toString().equals(from, true) && it.to.toString().equals(to, true)
        } ?: legalMoves.find {
            it.toString().equals(from + to, true)
        }

        if (move == null) {
            // Если ход нелегален, пробуем выбрать новую фигуру
            selectedSquare = square.lowercase()
            return
        }

        // Определяем, была ли взята фигура
        val capturedPiece = board.getPiece(move.to)
        val isCapture = capturedPiece != com.github.bhlangonijr.chesslib.Piece.NONE &&
                capturedPiece.pieceSide != board.sideToMove

        // Сохраняем FEN до хода
        val beforeFen = board.fen

        // Делаем ход на доске для получения FEN после хода
        board.doMove(move)
        val afterFen = board.fen
        val uciMove = from + to + (if (move.promotion != null) {
            when (move.promotion!!.pieceType) {
                com.github.bhlangonijr.chesslib.PieceType.QUEEN -> "q"
                com.github.bhlangonijr.chesslib.PieceType.ROOK -> "r"
                com.github.bhlangonijr.chesslib.PieceType.BISHOP -> "b"
                com.github.bhlangonijr.chesslib.PieceType.KNIGHT -> "n"
                else -> ""
            }
        } else "")

        variationLastMove = from to to
        variationFen = afterFen
        variationActive = true
        isAutoPlaying = false

        // Устанавливаем флаг анализа
        isAnalyzing = true

        scope.launch {
            try {
                val (newEval, moveClass, bestMove) = analyzeMoveRealtime(
                    beforeFen = beforeFen,
                    afterFen = afterFen,
                    uciMove = uciMove,
                    depth = 16,
                    multiPv = 3
                )

                variationEval = newEval
                variationMoveClass = moveClass  // Классификация от сервера!
                variationBestUci = bestMove

                playMoveSound(moveClass, isCapture)

            } catch (e: Exception) {
                e.printStackTrace()

                variationEval = evalOfPosition(currentPosition)
                variationMoveClass = MoveClass.OKAY
                variationBestUci = null
            } finally {
                isAnalyzing = false
            }
        }
    }

    fun seekTo(index: Int) {
        variationActive = false
        selectedSquare = null
        legalTargets = emptySet()
        isAnalyzing = false
        currentPlyIndex = index.coerceIn(0, report.positions.lastIndex)

        // Воспроизводим звук при навигации
        if (currentPlyIndex > 0) {
            val move = report.moves.getOrNull(currentPlyIndex - 1)
            val isCapture = move?.san?.contains('x') == true
            playMoveSound(move?.classification, isCapture)
        }
    }

    fun goNext() {
        if (!isAnalyzing && currentPlyIndex < report.positions.lastIndex) {
            seekTo(currentPlyIndex + 1)
        }
    }

    fun goPrev() {
        if (!isAnalyzing && currentPlyIndex > 0) {
            seekTo(currentPlyIndex - 1)
        }
    }

    Scaffold(
        containerColor = bgColor,
        topBar = {
            TopAppBar(
                title = { Text("Анализ партии") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Назад", tint = Color.White)
                    }
                },
                actions = {
                    IconButton(
                        onClick = { if (!isAnalyzing) isWhiteBottom = !isWhiteBottom },
                        enabled = !isAnalyzing
                    ) {
                        Icon(
                            Icons.Default.ScreenRotation,
                            contentDescription = "Перевернуть",
                            tint = if (isAnalyzing) Color.Gray else Color.White
                        )
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
            // Верхний игрок
            val topIsWhite = !isWhiteBottom
            val topName = if (topIsWhite) (report.header.white ?: "White") else (report.header.black ?: "Black")
            val topElo = if (topIsWhite) report.header.whiteElo else report.header.blackElo
            val moveNumber = if (!variationActive) currentPlyIndex / 2 else currentPlyIndex / 2
            val topClock = if (topIsWhite) {
                clockData?.white?.getOrNull(moveNumber)
            } else {
                clockData?.black?.getOrNull(moveNumber)
            }
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
                    .padding(horizontal = 12.dp, vertical = 4.dp)
            )

            // Доска + eval bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
            ) {
                // Eval bar
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

                // Доска
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .weight(1f)
                ) {
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
                            .getOrNull(kotlin.math.max(0, currentPlyIndex - 1))
                            ?.lines?.firstOrNull()
                            ?.pv?.firstOrNull()
                    } else null

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
                            fen = fen,
                            lastMove = lastMovePair,
                            moveClass = moveClass,
                            bestMoveUci = bestUci,
                            showBestArrow = showBestArrow,
                            isWhiteBottom = isWhiteBottom,
                            selectedSquare = selectedSquare,
                            legalMoves = legalTargets,
                            onSquareClick = { handleSquareClick(it) },
                            modifier = Modifier.fillMaxSize()
                        )
                    }

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

            // Нижний игрок
            val bottomIsWhite = isWhiteBottom
            val bottomName = if (bottomIsWhite) (report.header.white ?: "White") else (report.header.black ?: "Black")
            val bottomElo = if (bottomIsWhite) report.header.whiteElo else report.header.blackElo
            val bottomClock = if (bottomIsWhite) {
                clockData?.white?.getOrNull(moveNumber)
            } else {
                clockData?.black?.getOrNull(moveNumber)
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
                    .padding(horizontal = 12.dp, vertical = 4.dp)
            )

            // Карусель ходов
            MovesCarousel(
                report = report,
                currentPlyIndex = currentPlyIndex,
                onSeekTo = { if (!isAnalyzing) seekTo(it) },
                onPrev = { if (!isAnalyzing) goPrev() },
                onNext = { if (!isAnalyzing) goNext() },
                modifier = Modifier.fillMaxWidth()
            )

            // Кнопки управления
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(cardColor)
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = { if (!isAnalyzing) seekTo(0) },
                    enabled = !isAnalyzing
                ) {
                    Icon(
                        Icons.Default.SkipPrevious,
                        contentDescription = "В начало",
                        tint = if (isAnalyzing) Color.Gray else Color.White
                    )
                }
                IconButton(
                    onClick = { goPrev() },
                    enabled = !isAnalyzing
                ) {
                    Icon(
                        Icons.Default.ArrowBack,
                        contentDescription = "Назад",
                        tint = if (isAnalyzing) Color.Gray else Color.White
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
                        contentDescription = if (isAutoPlaying) "Пауза" else "Играть",
                        tint = Color.White
                    )
                }
                IconButton(
                    onClick = { goNext() },
                    enabled = !isAnalyzing
                ) {
                    Icon(
                        Icons.Default.ArrowForward,
                        contentDescription = "Вперед",
                        tint = if (isAnalyzing) Color.Gray else Color.White
                    )
                }
                IconButton(
                    onClick = { if (!isAnalyzing) seekTo(report.positions.lastIndex) },
                    enabled = !isAnalyzing
                ) {
                    Icon(
                        Icons.Default.SkipNext,
                        contentDescription = "В конец",
                        tint = if (isAnalyzing) Color.Gray else Color.White
                    )
                }
            }

            // Автоплей
            LaunchedEffect(isAutoPlaying, currentPlyIndex, isAnalyzing) {
                if (isAutoPlaying && !isAnalyzing) {
                    delay(1500)
                    if (currentPlyIndex < report.positions.lastIndex) {
                        goNext()
                    } else {
                        isAutoPlaying = false
                    }
                }
            }
        }
    }
}

// Вспомогательные компоненты

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
            .background(Color(0xFF1E1C1A), RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
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
            text = clock?.let { formatClock(it) } ?: "",
            color = animatedColor.copy(alpha = 0.9f),
            fontSize = 14.sp
        )
    }
}

private fun formatClock(centiseconds: Int): String {
    val seconds = centiseconds / 100
    val minutes = seconds / 60
    val secs = seconds % 60
    return "%d:%02d".format(minutes, secs)
}

// Структура данных для часов
data class ClockData(
    val white: List<Int> = emptyList(),
    val black: List<Int> = emptyList()
)

// Функции для работы с часами
private suspend fun fetchClockData(report: FullReport): ClockData? = withContext(Dispatchers.IO) {
    try {
        val site = report.header.site ?: return@withContext null
        val gameId = extractGameId(report.header.pgn) ?: return@withContext null
        when (site) {
            Provider.LICHESS -> fetchLichessClocks(gameId)
            Provider.CHESSCOM -> null
        }
    } catch (_: Exception) { null }
}
