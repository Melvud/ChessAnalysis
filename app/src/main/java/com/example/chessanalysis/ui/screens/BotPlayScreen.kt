package com.example.chessanalysis.ui.screens

import android.os.Build
import android.util.Log
import android.widget.Toast
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
import com.example.chessanalysis.EngineClient.analyzeGameByPgnWithProgress
import com.example.chessanalysis.EngineClient.analyzeMoveRealtime
import com.example.chessanalysis.EngineClient.evaluateFenDetailed
import com.example.chessanalysis.data.local.gameRepository
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import kotlinx.serialization.json.Json
import com.example.chessanalysis.ui.screens.bot.BotConfig
import com.example.chessanalysis.ui.screens.bot.BotSide
import kotlin.math.max

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

    // читаем тумблеры из BotConfig
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

    // Состояния для прогресса анализа
    var showAnalysis by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    var stage by remember { mutableStateOf("Подготовка…") }
    var done by remember { mutableStateOf(0) }
    var total by remember { mutableStateOf(0) }
    var etaMs by remember { mutableStateOf<Long?>(null) }

    // Флаг для предотвращения множественных вызовов анализа
    var isAnalyzing by remember { mutableStateOf(false) }

    fun formatEta(ms: Long?): String {
        if (ms == null) return "—"
        val sec = max(0, (ms / 1000).toInt())
        val mm = sec / 60
        val ss = sec % 60
        return "%d:%02d".format(mm, ss)
    }

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
        pvLines = if (showLines) {
            pos.lines.take(3).map { line ->
                LineEval(pv = line.pv, cp = line.cp, mate = line.mate, best = line.pv.firstOrNull())
            }
        } else emptyList()

        val firstLine = pos.lines.firstOrNull()
        currentEvalPos = firstLine?.let {
            PositionEval(
                fen = board.fen,
                idx = 0,
                lines = listOf(LineEval(pv = it.pv, cp = it.cp, mate = it.mate, best = it.pv.firstOrNull()))
            )
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
        if (isGameOver || isEngineThinking || !userToMove || isAnalyzing) return
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
        currentEvalPos = null
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
        if (isAnalyzing) return
        isAnalyzing = true

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

        try {
            // Проверяем кэш
            val cached = repo.getCachedReport(pgn)
            if (cached != null) {
                Log.d("BotPlayScreen", "Found cached report")
                val stored = BotGameSave(pgn, headerWhite, headerBlack, resStr, dateIso)
                repo.insertBotGame(pgn, headerWhite, headerBlack, resStr, dateIso)
                isAnalyzing = false
                onFinish(BotFinishResult(stored, cached))
                return
            }

            // Показываем прогресс анализа
            showAnalysis = true
            stage = "Подготовка к анализу…"
            progress = 0.01f
            done = 0
            total = 0
            etaMs = null

            val header = runCatching { PgnChess_bot.headerFromPgn(pgn) }.getOrNull()

            Log.d("BotPlayScreen", "Starting analysis")

            val report = withContext(Dispatchers.IO) {
                analyzeGameByPgnWithProgress(
                    pgn = pgn,
                    depth = 15,
                    multiPv = 3,
                    header = header
                ) { snap: EngineClient.ProgressSnapshot ->
                    Log.d("BotPlayScreen", "Progress: ${snap.done}/${snap.total} - ${snap.stage}")
                    total = snap.total
                    done = snap.done
                    stage = when (snap.stage) {
                        "queued"      -> "В очереди…"
                        "preparing"   -> "Подготовка…"
                        "evaluating"  -> "Анализ позиций…"
                        "postprocess" -> "Постобработка…"
                        "done"        -> "Готово"
                        else          -> snap.stage ?: "Анализ…"
                    }
                    if (snap.total > 0) {
                        progress = (snap.done.toFloat() / snap.total.toFloat()).coerceIn(0.01f, 0.99f)
                    }
                    etaMs = snap.etaMs
                    postProgress(snap)
                }
            }

            Log.d("BotPlayScreen", "Analysis completed, saving report")

            // Сохраняем отчет
            repo.saveReport(pgn, report)

            // Сохраняем партию
            val stored = BotGameSave(pgn, headerWhite, headerBlack, resStr, dateIso)
            repo.insertBotGame(pgn, headerWhite, headerBlack, resStr, dateIso)

            // Скрываем прогресс перед навигацией
            showAnalysis = false
            isAnalyzing = false

            // Вызываем навигацию
            Log.d("BotPlayScreen", "Calling onFinish")
            onFinish(BotFinishResult(stored, report))

        } catch (e: Exception) {
            Log.e("BotPlayScreen", "Analysis failed", e)
            showAnalysis = false
            isAnalyzing = false
            withContext(Dispatchers.Main) {
                Toast.makeText(ctx, "Ошибка анализа: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    fun undo() {
        if (!allowUndo || moveList.isEmpty() || isEngineThinking || isAnalyzing) return
        repeat(2) {
            try {
                board.undoMove()
                if (moveList.isNotEmpty()) moveList.removeAt(moveList.lastIndex)
            } catch (_: Exception) {
                return@repeat
            }
        }
        userToMove = board.sideToMove == mySide
        isGameOver = false
        result = GameResult.ONGOING
        lastMovePair = null
        bestMoveHint = null
        pvLines = emptyList()
        currentEvalPos = null
        if ((config.hints || showLines || showEvalBar) && userToMove) {
            scope.launch { updateHints() }
        }
    }

    fun onClickPvMove(lineIdx: Int, moveIdx: Int) {
        if (isAnalyzing) return
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
            isEngineThinking = true
            try {
                val (newEval, _, _) = analyzeMoveRealtime(
                    beforeFen = beforeFen, afterFen = afterFen,
                    uciMove = uciMove, depth = 16, multiPv = 3
                )
                val evalCp = (newEval * 100).toInt()
                currentEvalPos = PositionEval(
                    fen = afterFen, idx = 0,
                    lines = listOf(LineEval(pv = emptyList(), cp = evalCp, mate = null, best = null))
                )
                val detailed = evaluateFenDetailed(afterFen, depth = 16, multiPv = multiPvForThink, skillLevel = engineSkill)
                pvLines = if (showLines) {
                    detailed.lines.take(3).map {
                        LineEval(pv = it.pv, cp = it.cp, mate = it.mate, best = it.pv.firstOrNull())
                    }
                } else emptyList()
            } catch (_: Exception) {
            } finally {
                isEngineThinking = false
            }
        }
    }

    Scaffold(
        containerColor = bgColor,
        topBar = {
            TopAppBar(
                title = { Text("Игра с ботом") },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        enabled = !isAnalyzing
                    ) {
                        Icon(Icons.Default.ArrowBack, null, tint = if (!isAnalyzing) Color.White else Color.Gray)
                    }
                },
                actions = {
                    if (allowUndo) {
                        IconButton(
                            onClick = { undo() },
                            enabled = moveList.isNotEmpty() && !isEngineThinking && !isGameOver && !isAnalyzing
                        ) {
                            Icon(
                                Icons.Default.Undo,
                                contentDescription = "Вернуть ход",
                                tint = if (moveList.isNotEmpty() && !isEngineThinking && !isGameOver && !isAnalyzing)
                                    Color.White else Color.Gray
                            )
                        }
                    }
                    IconButton(
                        onClick = { isWhiteBottom = !isWhiteBottom },
                        enabled = !isEngineThinking && !isAnalyzing
                    ) {
                        Icon(
                            Icons.Default.ScreenRotation,
                            contentDescription = "Перевернуть",
                            tint = if (!isEngineThinking && !isAnalyzing) Color.White else Color.Gray
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
                        val eval = currentEvalPos
                        if (eval != null) {
                            EvalBar(
                                positions = listOf(eval),
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
                            bestMoveUci = if (config.hints && userToMove && !isAnalyzing) bestMoveHint else null,
                            showBestArrow = config.hints && userToMove && !isAnalyzing,
                            isWhiteBottom = isWhiteBottom,
                            selectedSquare = selected,
                            legalMoves = legalTargets,
                            onSquareClick = { if (!isAnalyzing) onUserSquareClick(it) },
                            modifier = Modifier.fillMaxSize()
                        )
                        if (isEngineThinking) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }

                if (showLines && pvLines.isNotEmpty() && !isAnalyzing) {
                    BotEnginePvPanel(
                        baseFen = board.fen,
                        lines = pvLines,
                        onClickMoveInLine = ::onClickPvMove,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 64.dp)
                    )
                }

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { if (!isGameOver && !isAnalyzing) resign() },
                        modifier = Modifier.weight(1f),
                        enabled = !isGameOver && !isAnalyzing
                    ) { Text("Сдаться") }
                }
            }

            // Оверлей конца игры
            if (isGameOver && !showAnalysis) {
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
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !isAnalyzing) {
                                        scope.launch { openReport() }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = !isAnalyzing
                            ) { Text("Смотреть отчёт") }
                        }
                    }
                }
            }

            // Оверлей прогресса анализа
            if (showAnalysis) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.7f)),
                    contentAlignment = Alignment.Center
                ) {
                    Surface(
                        tonalElevation = 6.dp,
                        shape = RoundedCornerShape(12.dp),
                        color = cardColor
                    ) {
                        Column(
                            modifier = Modifier
                                .widthIn(min = 300.dp)
                                .padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                "Анализ партии",
                                style = MaterialTheme.typography.titleMedium,
                                color = Color.White
                            )
                            Spacer(Modifier.height(12.dp))
                            LinearProgressIndicator(
                                progress = { progress.coerceIn(0f, 1f) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(8.dp),
                                color = MaterialTheme.colorScheme.primary,
                                trackColor = Color(0xFF3A3936)
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                buildString {
                                    append(stage)
                                    if (total > 0) append("  •  $done/$total позиций")
                                    append("  •  ETA: ${formatEta(etaMs)}")
                                },
                                color = Color.White.copy(alpha = 0.9f),
                                style = MaterialTheme.typography.bodyMedium
                            )
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