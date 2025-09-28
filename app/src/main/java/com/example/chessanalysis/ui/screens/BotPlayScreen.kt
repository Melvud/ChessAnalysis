package com.example.chessanalysis.ui.screens

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.chessanalysis.ui.components.BoardCanvas
import com.example.chessanalysis.ui.components.EvalBar
import com.example.chessanalysis.ui.components.BotEnginePvPanel
import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.Side
import com.github.bhlangonijr.chesslib.game.GameResult
import com.github.bhlangonijr.chesslib.move.Move
import com.github.bhlangonijr.chesslib.move.MoveGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.example.chessanalysis.*
import com.example.chessanalysis.data.local.gameRepository
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import kotlinx.serialization.json.Json
import com.example.chessanalysis.ui.screens.bot.BotConfig
import com.example.chessanalysis.ui.screens.bot.BotSide

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BotPlayScreen(
    config: BotConfig,
    onBack: () -> Unit,
    onFinish: (BotFinishResult) -> Unit
) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val json = remember { Json { ignoreUnknownKeys = true; explicitNulls = false } }
    val repo = remember { ctx.gameRepository(json) }

    val mySide = remember(config.side) {
        when (config.side) {
            BotSide.WHITE -> Side.WHITE
            BotSide.BLACK -> Side.BLACK
            BotSide.AUTO  -> if (System.currentTimeMillis() % 2L == 0L) Side.WHITE else Side.BLACK
        }
    }
    val engineSide = if (mySide == Side.WHITE) Side.BLACK else Side.WHITE

    fun skillFromElo(elo: Int): Int =
        (((elo - 800) / 50.0).toInt()).coerceIn(1, 20)

    val showLines   = config.showMultiPv
    val showEvalBar = config.showEvalBar
    val allowUndo   = config.allowUndo
    val multiPvForThink = if (showLines || showEvalBar) 3 else 1
    val engineSkill = skillFromElo(config.elo)

    var board by remember { mutableStateOf(Board()) }
    var bestMoveHint by remember { mutableStateOf<String?>(null) }
    var pvLines by remember { mutableStateOf<List<LineEval>>(emptyList()) }
    var lastMovePair by remember { mutableStateOf<Pair<String, String>?>(null) }
    var userToMove by remember { mutableStateOf(mySide == Side.WHITE) }
    var isEngineThinking by remember { mutableStateOf(false) }
    var isGameOver by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf(GameResult.ONGOING) }
    val moveList = remember { mutableStateListOf<String>() }

    var isWhiteBottom by remember { mutableStateOf(mySide == Side.WHITE) }
    var currentEvalPos by remember { mutableStateOf<PositionEval?>(null) }

    // Сохраняем предыдущие значения, чтобы не исчезали во время анализа
    var previousEvalPos by remember { mutableStateOf<PositionEval?>(null) }
    var previousPvLines by remember { mutableStateOf<List<LineEval>>(emptyList()) }

    val bgColor = Color(0xFF161512)
    val cardColor = Color(0xFF1E1C1A)
    val surfaceColor = Color(0xFF262522)

    fun checkEnd() {
        val legal = MoveGenerator.generateLegalMoves(board)
        if (legal.isEmpty()) {
            isGameOver = true
            result = when {
                board.isMated -> if (board.sideToMove == Side.WHITE) GameResult.BLACK_WON else GameResult.WHITE_WON
                else -> GameResult.DRAW
            }
        }
    }

    suspend fun updateHints() {
        val pos = withContext(Dispatchers.IO) {
            evaluateFenDetailed(board.fen, depth = 16, multiPv = multiPvForThink, skillLevel = engineSkill)
        }
        bestMoveHint = pos.bestMove

        val newLines = if (showLines) {
            pos.lines.take(3).map { line ->
                LineEval(pv = line.pv, cp = line.cp, mate = line.mate, best = line.pv.firstOrNull())
            }
        } else emptyList()

        if (newLines.isNotEmpty()) {
            pvLines = newLines
            previousPvLines = newLines
        }

        val firstLine = pos.lines.firstOrNull()
        val newEvalPos = firstLine?.let {
            PositionEval(
                fen = board.fen,
                idx = 0,
                lines = listOf(LineEval(pv = it.pv, cp = it.cp, mate = it.mate, best = it.pv.firstOrNull()))
            )
        }

        if (newEvalPos != null) {
            currentEvalPos = newEvalPos
            previousEvalPos = newEvalPos
        }
    }

    suspend fun engineThinkAndMove() {
        isEngineThinking = true
        val pos = withContext(Dispatchers.IO) {
            evaluateFenDetailed(board.fen, depth = 16, multiPv = 1, skillLevel = engineSkill)
        }
        isEngineThinking = false
        pos.bestMove?.let { uci ->
            val m = uciToLegal(board, uci)
            if (m != null) {
                lastMovePair = m.from.toString().lowercase() to m.to.toString().lowercase()
                val san = board.sanMove(m)
                board.doMove(m)
                moveList.add(san)
                userToMove = true
                checkEnd()
            }
        }
        if (!isGameOver && (config.hints || showLines || showEvalBar)) {
            updateHints()
        }
    }

    LaunchedEffect(Unit) {
        if (!userToMove) {
            engineThinkAndMove()
        } else if (config.hints || showLines || showEvalBar) {
            updateHints()
        }
    }

    var selected by remember { mutableStateOf<String?>(null) }
    var legalTargets by remember { mutableStateOf<Set<String>>(emptySet()) }

    fun onUserSquareClick(sq: String) {
        if (isGameOver || isEngineThinking || !userToMove) return
        if (selected == null) {
            selected = sq
            legalTargets = legalTargetsFrom(board, sq)
            return
        }
        if (selected == sq) {
            selected = null
            legalTargets = emptySet()
            return
        }
        val mv = uciToLegal(board, (selected + sq).lowercase())
        if (mv == null) {
            selected = sq
            legalTargets = legalTargetsFrom(board, sq)
            return
        }
        lastMovePair = mv.from.toString().lowercase() to mv.to.toString().lowercase()
        val san = board.sanMove(mv)
        board.doMove(mv)
        moveList.add(san)
        selected = null
        legalTargets = emptySet()
        userToMove = false
        bestMoveHint = null
        // Не обнуляем линии и эвал, оставляем предыдущие значения
        checkEnd()
        if (!isGameOver) {
            scope.launch { engineThinkAndMove() }
        }
    }

    fun resign() {
        isGameOver = true
        result = if (mySide == Side.WHITE) GameResult.BLACK_WON else GameResult.WHITE_WON
    }

    @RequiresApi(Build.VERSION_CODES.O)
    suspend fun openReport() {
        val headerWhite = if (mySide == Side.WHITE) "You" else "Bot"
        val headerBlack = if (mySide == Side.BLACK) "You" else "Bot"
        val resStr = when (result) {
            GameResult.WHITE_WON -> "1-0"
            GameResult.BLACK_WON -> "0-1"
            GameResult.DRAW -> "1/2-1/2"
            else -> "*"
        }
        val movesPgn = PgnChess_bot.sanListToPgn(moveList.toList())
        val pgn = buildString {
            appendLine("""[Event "Bot"]""")
            appendLine("""[Site "Local"]""")
            appendLine("""[White "$headerWhite"]""")
            appendLine("""[Black "$headerBlack"]""")
            appendLine("""[Result "$resStr"]""")
            appendLine()
            append(movesPgn)
            append(" ")
            append(resStr)
        }

        val dateIso = OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        val stored = BotGameSave(pgn, headerWhite, headerBlack, resStr, dateIso)

        repo.insertBotGame(pgn, headerWhite, headerBlack, resStr, dateIso)

        val cached = repo.getCachedReport(pgn)
        val report = cached ?: analyzeGameByPgnWithProgress(
            pgn = pgn, depth = 15, multiPv = 3,
            header = runCatching { PgnChess_bot.headerFromPgn(pgn) }.getOrNull()
        ) { }.also { repo.saveReport(pgn, it) }

        onFinish(BotFinishResult(stored, report))
    }

    fun undo() {
        if (!allowUndo || moveList.isEmpty() || isEngineThinking) return
        repeat(2) {
            try {
                board.undoMove()
                if (moveList.isNotEmpty()) moveList.removeLast()
            } catch (_: Exception) {
                return@repeat
            }
        }
        userToMove = board.sideToMove == mySide
        isGameOver = false
        result = GameResult.ONGOING
        lastMovePair = null
        bestMoveHint = null
        // Не обнуляем линии и эвал при отмене хода
        if ((config.hints || showLines || showEvalBar) && userToMove) {
            scope.launch { updateHints() }
        }
    }

    fun onClickPvMove(lineIdx: Int, moveIdx: Int) {
        val line = pvLines.getOrNull(lineIdx) ?: return
        val fen0 = board.fen
        if (moveIdx !in line.pv.indices) return
        val b = Board().apply { loadFromFen(fen0) }
        for (i in 0 until moveIdx) {
            val m = uciToLegal(b, line.pv[i]) ?: return
            b.doMove(m)
        }
        val beforeFen = b.fen
        val move = uciToLegal(b, line.pv[moveIdx]) ?: return
        val from = move.from.toString().lowercase()
        val to   = move.to.toString().lowercase()
        val uciMove = buildString {
            append(from).append(to)
            move.promotion?.pieceType?.let {
                append(
                    when (it) {
                        com.github.bhlangonijr.chesslib.PieceType.QUEEN  -> "q"
                        com.github.bhlangonijr.chesslib.PieceType.ROOK   -> "r"
                        com.github.bhlangonijr.chesslib.PieceType.BISHOP -> "b"
                        com.github.bhlangonijr.chesslib.PieceType.KNIGHT -> "n"
                        else -> ""
                    }
                )
            }
        }
        b.doMove(move)
        val afterFen = b.fen
        lastMovePair = from to to

        scope.launch {
            try {
                val (newEval, _, _) = analyzeMoveRealtime(
                    beforeFen = beforeFen, afterFen = afterFen,
                    uciMove = uciMove, depth = 16, multiPv = 3
                )
                val evalCp = (newEval * 100).toInt()
                val newEvalPos = PositionEval(
                    fen = afterFen, idx = 0,
                    lines = listOf(LineEval(pv = emptyList(), cp = evalCp, mate = null, best = null))
                )
                currentEvalPos = newEvalPos
                previousEvalPos = newEvalPos

                val detailed = evaluateFenDetailed(afterFen, depth = 16, multiPv = multiPvForThink, skillLevel = engineSkill)
                val newLines = if (showLines) {
                    detailed.lines.take(3).map {
                        LineEval(pv = it.pv, cp = it.cp, mate = it.mate, best = it.pv.firstOrNull())
                    }
                } else emptyList()

                if (newLines.isNotEmpty()) {
                    pvLines = newLines
                    previousPvLines = newLines
                }
            } catch (_: Exception) {}
        }
    }

    // Используем предыдущие значения, если текущие null
    val displayEvalPos = currentEvalPos ?: previousEvalPos
    val displayPvLines = if (pvLines.isNotEmpty()) pvLines else previousPvLines

    Scaffold(
        containerColor = bgColor,
        topBar = {
            TopAppBar(
                title = { Text("Игра с ботом") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null, tint = Color.White) } },
                actions = {
                    if (allowUndo) {
                        IconButton(
                            onClick = { undo() },
                            enabled = moveList.isNotEmpty() && !isEngineThinking && !isGameOver
                        ) {
                            Icon(
                                Icons.Default.Undo,
                                contentDescription = "Вернуть ход",
                                tint = if (moveList.isNotEmpty() && !isEngineThinking && !isGameOver) Color.White else Color.Gray
                            )
                        }
                    }
                    IconButton(
                        onClick = { isWhiteBottom = !isWhiteBottom },
                        enabled = !isEngineThinking
                    ) {
                        Icon(Icons.Default.ScreenRotation, contentDescription = "Перевернуть", tint = if (!isEngineThinking) Color.White else Color.Gray)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = surfaceColor,
                    titleContentColor = Color.White
                )
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(bgColor)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Surface(color = cardColor, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            "Elo: ${config.elo} • " + if (mySide == Side.WHITE) "Вы белыми" else "Вы чёрными",
                            color = Color.White
                        )
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .background(
                                    if (userToMove) MaterialTheme.colorScheme.primary else Color.Gray,
                                    CircleShape
                                )
                        )
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                ) {
                    if (showEvalBar) {
                        if (displayEvalPos != null) {
                            EvalBar(
                                positions = listOf(displayEvalPos),
                                currentPlyIndex = 0,
                                isWhiteBottom = isWhiteBottom,
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .width(20.dp)
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .width(20.dp)
                                    .background(Color(0xFF1E1C1A))
                            )
                        }
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .weight(1f)
                    ) {
                        BoardCanvas(
                            fen = board.fen,
                            lastMove = lastMovePair,
                            moveClass = null,
                            bestMoveUci = if (config.hints && userToMove) bestMoveHint else null,
                            showBestArrow = config.hints && userToMove,
                            isWhiteBottom = isWhiteBottom,
                            selectedSquare = selected,
                            legalMoves = legalTargets,
                            onSquareClick = { onUserSquareClick(it) },
                            modifier = Modifier.fillMaxSize()
                        )
                        // Убираем CircularProgressIndicator
                    }
                }

                if (showLines && displayPvLines.isNotEmpty()) {
                    BotEnginePvPanel(
                        baseFen = board.fen,
                        lines = displayPvLines,
                        onClickMoveInLine = ::onClickPvMove,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 64.dp)
                    )
                }

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { if (!isGameOver) resign() },
                        modifier = Modifier.weight(1f)
                    ) { Text("Сдаться") }
                }
            }

            if (isGameOver) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.6f)),
                    contentAlignment = Alignment.Center
                ) {
                    Surface(
                        color = cardColor,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth(0.85f)
                            .padding(16.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                text = "Бот (сила ${engineSkill})  –  Вы",
                                color = Color.White,
                                style = MaterialTheme.typography.titleLarge
                            )
                            val resStrText = when (result) {
                                GameResult.WHITE_WON -> if (mySide == Side.WHITE) "Вы победили" else "Вы проиграли"
                                GameResult.BLACK_WON -> if (mySide == Side.BLACK) "Вы победили" else "Вы проиграли"
                                GameResult.DRAW -> "Ничья"
                                else -> ""
                            }
                            Text(
                                text = resStrText,
                                color = Color.White.copy(alpha = 0.9f),
                                style = MaterialTheme.typography.titleMedium
                            )
                            Button(
                                onClick = {
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                        scope.launch { openReport() }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("Смотреть отчёт") }
                        }
                    }
                }
            }
        }
    }
}

private fun uciToLegal(board: Board, uci: String): Move? {
    val moves = MoveGenerator.generateLegalMoves(board)
    return moves.firstOrNull { it.toString().equals(uci, true) }
}

private fun legalTargetsFrom(board: Board, from: String): Set<String> {
    val sqFrom = from.uppercase()
    return MoveGenerator.generateLegalMoves(board)
        .filter { it.from.toString() == sqFrom }
        .map { it.to.toString().lowercase() }
        .toSet()
}