package com.example.chessanalysis.ui.screens

import android.media.MediaPlayer
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.rememberAsyncImagePainter
import coil.decode.SvgDecoder
import coil.request.ImageRequest
import com.example.chessanalysis.*
import com.example.chessanalysis.ui.components.BoardCanvas
import com.example.chessanalysis.ui.components.EvalBar
import com.example.chessanalysis.ui.components.MovesCarousel
import com.github.bhlangonijr.chesslib.*
import com.github.bhlangonijr.chesslib.move.Move
import com.github.bhlangonijr.chesslib.move.MoveGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import kotlin.math.abs

// ---------------- Вспомогательные типы для PV-панели ----------------

private data class PvToken(
    val iconAsset: String,          // "wN.svg", "bQ.svg" ...
    val toSquare: String,           // "e5"
    val capture: Boolean,
    val promoSuffix: String = ""    // "=Q", "=N" и т.п.
)

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

@Composable
private fun PieceAssetIcon(name: String, size: Dp) {
    val ctx = LocalContext.current
    val painter = rememberAsyncImagePainter(
        ImageRequest.Builder(ctx)
            .data("file:///android_asset/fresca/$name")
            .decoderFactory(SvgDecoder.Factory())
            .build()
    )
    Image(
        painter = painter,
        contentDescription = null,
        modifier = Modifier.size(size)
    )
}

/** Безопасный поиск ЛЕГАЛЬНОГО хода (с поддержкой промоций) */
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

/** Построение токенов для «SAN-подобной» строки PV с иконками */
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

/** Чип с оценкой (+1.53 / M…) */
@Composable
private fun EvalChip(line: LineEval, modifier: Modifier = Modifier) {
    val txt = when {
        line.mate != null -> if (line.mate!! > 0) "M${abs(line.mate!!)}" else "M-${abs(line.mate!!)}"
        line.cp != null   -> String.format("%+,.2f", line.cp!! / 100f)
        else -> "—"
    }
    Box(
        modifier = modifier
            .background(Color(0xFF2F2F2F), RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(txt, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** Одна строка PV: чип с оценкой + ряд иконок-ходов */
@Composable
private fun PvRow(
    baseFen: String,
    line: LineEval,
    onClickMoveAtIndex: (idx: Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val tokens = remember(baseFen, line.pv) { buildIconTokens(baseFen, line.pv) }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        EvalChip(line)
        Spacer(Modifier.width(10.dp))
        Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
            tokens.forEachIndexed { i, t ->
                Row(
                    modifier = Modifier
                        .clickable { onClickMoveAtIndex(i) }
                        .padding(end = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    PieceAssetIcon(t.iconAsset, 20.dp)
                    Spacer(Modifier.width(4.dp))
                    val suffix = buildString {
                        append(t.toSquare)
                        if (t.capture) append("x")
                        append(t.promoSuffix)
                    }
                    Text(suffix, color = Color.White.copy(alpha = 0.9f), fontSize = 16.sp)
                }
            }
        }
    }
}

/** Панель из трёх линий */
@Composable
private fun EnginePvPanel(
    baseFen: String,
    lines: List<LineEval>,
    onClickMoveInLine: (lineIdx: Int, moveIdx: Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xFF1E1C1A))
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        lines.take(3).forEachIndexed { li, line ->
            PvRow(
                baseFen = baseFen,
                line = line,
                onClickMoveAtIndex = { mi -> onClickMoveInLine(li, mi) }
            )
        }
    }
}

// --------------------- Топ-левел утилиты ДЛЯ ЧАСОВ ---------------------

fun extractGameId(pgn: String?): String? {
    if (pgn.isNullOrBlank()) return null
    val sitePattern = Regex("""\[Site\s+".*/([\w]+)"\]""")
    sitePattern.find(pgn)?.groupValues?.getOrNull(1)?.let { return it }
    val lichessPattern = Regex("""([a-zA-Z0-9]{8})""")
    lichessPattern.find(pgn)?.groupValues?.getOrNull(1)?.let { return it }
    return null
}

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

private suspend fun fetchLichessClocks(gameId: String): ClockData? = withContext(Dispatchers.IO) {
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
                parseClockData(body)
            } else null
        }
    } catch (_: Exception) { null }
}

// --------------------- ЭКРАН ---------------------

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

    var clockData by remember { mutableStateOf<ClockData?>(null) }

    var variationActive by remember { mutableStateOf(false) }
    var variationFen by remember { mutableStateOf<String?>(null) }
    var variationEval by remember { mutableStateOf<Float?>(null) }
    var variationBestUci by remember { mutableStateOf<String?>(null) }
    var variationMoveClass by remember { mutableStateOf<MoveClass?>(null) }
    var variationLastMove by remember { mutableStateOf<Pair<String, String>?>(null) }
    var selectedSquare by remember { mutableStateOf<String?>(null) }
    var legalTargets by remember { mutableStateOf<Set<String>>(emptySet()) }
    var isAnalyzing by remember { mutableStateOf(false) }

    // линии для нижней панели
    var engineLines by remember { mutableStateOf<List<LineEval>>(emptyList()) }

    val bgColor = Color(0xFF161512)
    val surfaceColor = Color(0xFF262522)
    val cardColor = Color(0xFF1E1C1A)

    LaunchedEffect(report) {
        scope.launch {
            val pgn = report.header.pgn
            if (!pgn.isNullOrBlank()) {
                val parsed = parseClockData(pgn)
                clockData = if (parsed.white.isNotEmpty() || parsed.black.isNotEmpty()) {
                    parsed
                } else {
                    val gameId = extractGameId(pgn)
                    if (gameId != null && report.header.site == Provider.LICHESS) {
                        fetchClockData(report)
                    } else null
                }
            }
        }
    }

    // Обновляем PV-панель при смене позиции, если не в вариации
    LaunchedEffect(currentPlyIndex, variationActive) {
        if (!variationActive) {
            engineLines = report.positions.getOrNull(currentPlyIndex)?.lines?.take(3) ?: emptyList()
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

    /** Обработчик клика по клетке на доске — вариации + realtime-анализ */
    fun handleSquareClick(square: String) {
        if (isAnalyzing) return

        val baseFen = if (variationActive)
            (variationFen ?: report.positions.getOrNull(currentPlyIndex)?.fen)
        else report.positions.getOrNull(currentPlyIndex)?.fen
        val boardFenNow = baseFen ?: return

        val board = Board().apply { loadFromFen(boardFenNow) }

        // Повторный клик — снять выделение
        if (selectedSquare != null && selectedSquare.equals(square, ignoreCase = true)) {
            selectedSquare = null
            legalTargets = emptySet()
            return
        }

        // Если нет выбранного источника — показать возможные цели
        if (selectedSquare == null) {
            selectedSquare = square.lowercase()
            val all = MoveGenerator.generateLegalMoves(board)
            legalTargets = all.filter { it.from.toString().equals(selectedSquare, true) }
                .map { it.to.toString().lowercase() }
                .toSet()
            if (legalTargets.isEmpty()) {
                selectedSquare = null
            }
            return
        }

        // Источник есть — проверяем переключение на другую фигуру
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

        // Пробуем выполнить ход
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

        // Входим в вариацию
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
                    depth = 16,
                    multiPv = 3
                )
                variationEval = newEval
                variationMoveClass = moveClass
                variationBestUci = bestMove
                playMoveSound(moveClass, captured)

                val detailed = evaluateFenDetailed(afterFen, depth = 16, multiPv = 3)
                engineLines = detailed.lines.map { l ->
                    LineEval(
                        pv = l.pv,
                        cp = l.cp,
                        mate = l.mate,
                        best = l.pv.firstOrNull()
                    )
                }.take(3)
            } catch (e: Exception) {
                e.printStackTrace()
                variationEval = evalOfPosition(report.positions.getOrNull(currentPlyIndex))
                variationMoveClass = MoveClass.OKAY
                variationBestUci = null
            } finally {
                isAnalyzing = false
            }
        }
    }

    /** Клик по ходу внутри одной из трёх PV-строк */
    fun onClickPvMove(lineIdx: Int, moveIdx: Int) {
        val baseFen = if (variationActive)
            (variationFen ?: report.positions.getOrNull(currentPlyIndex)?.fen)
        else report.positions.getOrNull(currentPlyIndex)?.fen
        val line = engineLines.getOrNull(lineIdx) ?: return
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
                    depth = 16,
                    multiPv = 3
                )
                variationEval = newEval
                variationMoveClass = moveClass
                variationBestUci = bestMove
                playMoveSound(moveClass, captured)

                // Переоценить «после» и показать новые 3 линии
                val detailed = evaluateFenDetailed(after, depth = 16, multiPv = 3)
                engineLines = detailed.lines.map { l ->
                    LineEval(
                        pv = l.pv,
                        cp = l.cp,
                        mate = l.mate,
                        best = l.pv.firstOrNull()
                    )
                }.take(3)
            } catch (e: Exception) {
                e.printStackTrace()
                variationEval = evalOfPosition(report.positions.getOrNull(currentPlyIndex))
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
                        Icon(Icons.Default.ScreenRotation, contentDescription = "Перевернуть", tint = if (isAnalyzing) Color.Gray else Color.White)
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
            val moveNumber = currentPlyIndex / 2
            val topClock = if (topIsWhite) clockData?.white?.getOrNull(moveNumber) else clockData?.black?.getOrNull(moveNumber)
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

            // Доска + Eval bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
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
                            onSquareClick = { handleSquareClick(it) }, // ВКЛЮЧИЛИ КЛИКИ ПО ДОСКЕ
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
            val bottomClock = if (bottomIsWhite) clockData?.white?.getOrNull(moveNumber) else clockData?.black?.getOrNull(moveNumber)
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
                IconButton(onClick = { if (!isAnalyzing) seekTo(0) }, enabled = !isAnalyzing) {
                    Icon(Icons.Default.SkipPrevious, contentDescription = "В начало", tint = if (isAnalyzing) Color.Gray else Color.White)
                }
                IconButton(onClick = { goPrev() }, enabled = !isAnalyzing) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Назад", tint = if (isAnalyzing) Color.Gray else Color.White)
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
                        .background(if (isAnalyzing) Color.Gray else MaterialTheme.colorScheme.primary, CircleShape)
                ) {
                    Icon(
                        if (isAutoPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isAutoPlaying) "Пауза" else "Играть",
                        tint = Color.White
                    )
                }
                IconButton(onClick = { goNext() }, enabled = !isAnalyzing) {
                    Icon(Icons.Default.ArrowForward, contentDescription = "Вперед", tint = if (isAnalyzing) Color.Gray else Color.White)
                }
                IconButton(onClick = { if (!isAnalyzing) seekTo(report.positions.lastIndex) }, enabled = !isAnalyzing) {
                    Icon(Icons.Default.SkipNext, contentDescription = "В конец", tint = if (isAnalyzing) Color.Gray else Color.White)
                }
            }

            // ---- НИЖНЯЯ ПАНЕЛЬ С ТРЕМЯ ЛИНИЯМИ ДВИЖКА ----
            val baseFenForPanel =
                if (variationActive) (variationFen ?: report.positions.getOrNull(currentPlyIndex)?.fen)
                else report.positions.getOrNull(currentPlyIndex)?.fen
            EnginePvPanel(
                baseFen = baseFenForPanel ?: report.positions.firstOrNull()?.fen.orEmpty(),
                lines = engineLines,
                onClickMoveInLine = ::onClickPvMove,
                modifier = Modifier.weight(1f)
            )

            // Автоплей
            LaunchedEffect(isAutoPlaying, currentPlyIndex, isAnalyzing) {
                if (isAutoPlaying && !isAnalyzing) {
                    delay(1500)
                    if (currentPlyIndex < report.positions.lastIndex) goNext() else isAutoPlaying = false
                }
            }
        }
    }
}

// --------- Вспомогательные компоненты / часы ---------

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

data class ClockData(
    val white: List<Int> = emptyList(),
    val black: List<Int> = emptyList()
)

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
