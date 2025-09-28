package com.example.chessanalysis.ui.screens

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.chessanalysis.ui.components.BoardCanvas
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
            BotSide.RANDOM -> if (System.currentTimeMillis() % 2L == 0L) Side.WHITE else Side.BLACK
        }
    }
    val engineSide = if (mySide == Side.WHITE) Side.BLACK else Side.WHITE

    var board by remember { mutableStateOf(Board()) }
    var bestMoveHint by remember { mutableStateOf<String?>(null) }
    var pvLines by remember { mutableStateOf<List<LineEval>>(emptyList()) }
    var lastMovePair by remember { mutableStateOf<Pair<String, String>?>(null) }
    var userToMove by remember { mutableStateOf(mySide == Side.WHITE) }
    var isEngineThinking by remember { mutableStateOf(false) }
    var isGameOver by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf(GameResult.ONGOING) }
    val moveList = remember { mutableStateListOf<String>() }

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
            evaluateFenDetailed(board.fen, depth = 16, multiPv = config.multiPv, skillLevel = config.skill)
        }
        bestMoveHint = pos.bestMove
        pvLines = if (config.showLines) {
            pos.lines.take(3).map { line ->
                LineEval(
                    pv = line.pv,
                    cp = line.cp,
                    mate = line.mate,
                    best = line.pv.firstOrNull()
                )
            }
        } else emptyList()
    }

    suspend fun engineThinkAndMove() {
        isEngineThinking = true
        val pos = withContext(Dispatchers.IO) {
            evaluateFenDetailed(board.fen, depth = 16, multiPv = 1, skillLevel = config.skill)
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

        if (!isGameOver && config.hints) {
            updateHints()
        }
    }

    LaunchedEffect(Unit) {
        if (!userToMove) {
            engineThinkAndMove()
        } else if (config.hints) {
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
        pvLines = emptyList()
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
        val headerWhite = if (mySide == Side.WHITE) "You" else "Engine"
        val headerBlack = if (mySide == Side.BLACK) "You" else "Engine"
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

        // Сохраняем в локальную БД
        repo.insertBotGame(pgn, headerWhite, headerBlack, resStr, dateIso)

        val cached = repo.getCachedReport(pgn)
        val report = cached ?: analyzeGameByPgnWithProgress(
            pgn = pgn,
            depth = 15,
            multiPv = 3,
            header = runCatching { PgnChess_bot.headerFromPgn(pgn) }.getOrNull()
        ) { }.also { repo.saveReport(pgn, it) }

        onFinish(BotFinishResult(stored, report))
    }

    Scaffold(
        containerColor = bgColor,
        topBar = {
            TopAppBar(
                title = { Text("Игра с ботом") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null, tint = Color.White) } },
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
                        "Skill: ${config.skill} • " + if (mySide == Side.WHITE) "Вы белыми" else "Вы чёрными",
                        color = Color.White
                    )
                    Box(
                        modifier = Modifier.size(10.dp).background(if (userToMove) MaterialTheme.colorScheme.primary else Color.Gray, CircleShape)
                    )
                }
            }

            Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
                BoardCanvas(
                    fen = board.fen,
                    lastMove = lastMovePair,
                    moveClass = null,
                    bestMoveUci = if (config.hints && userToMove) bestMoveHint else null,
                    showBestArrow = config.hints && userToMove,
                    isWhiteBottom = mySide == Side.WHITE,
                    selectedSquare = selected,
                    legalMoves = legalTargets,
                    onSquareClick = { onUserSquareClick(it) },
                    modifier = Modifier.fillMaxSize()
                )
                if (isEngineThinking) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    }
                }
            }

            if (config.showLines && pvLines.isNotEmpty()) {
                Surface(color = cardColor, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)) {
                    Column(Modifier.verticalScroll(rememberScrollState()).padding(12.dp)) {
                        pvLines.take(3).forEachIndexed { i, line ->
                            val head = when {
                                line.mate != null -> if (line.mate!! > 0) "M${line.mate}" else "M-${-line.mate!!}"
                                line.cp != null -> String.format("%+,.2f", line.cp!! / 100f)
                                else -> "—"
                            }
                            Text("${i + 1}) $head  ${line.pv.joinToString(" ")}", color = Color.White)
                            Spacer(Modifier.height(6.dp))
                        }
                    }
                }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { if (!isGameOver) resign() },
                    modifier = Modifier.weight(1f)
                ) { Text("Сдаться") }

                Button(
                    onClick = {
                        scope.launch {
                            if (!isGameOver) resign()
                            openReport()
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("Смотреть отчёт") }
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