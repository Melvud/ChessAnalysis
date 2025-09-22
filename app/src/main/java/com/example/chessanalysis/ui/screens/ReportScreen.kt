package com.example.chessanalysis.ui.screens

import android.media.MediaPlayer
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.chessanalysis.R
import com.example.chessanalysis.data.model.AnalysisResult
import com.example.chessanalysis.data.model.MoveAnalysis
import com.example.chessanalysis.data.model.MoveClass
import com.example.chessanalysis.data.util.BoardState
import kotlin.math.min

@Composable
fun ReportScreen(result: AnalysisResult, onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Детальный отчёт") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Назад") } }
            )
        }
    ) { paddingValues ->
        ReportContent(result, Modifier.padding(paddingValues))
    }
}

@Composable
fun ReportAwaitScreen(onBack: () -> Unit) {
    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Детальный отчёт") },
            navigationIcon = { TextButton(onClick = onBack) { Text("Назад") } }
        )
    }) { paddingValues ->
        Box(Modifier.padding(paddingValues).fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
    }
}

@Composable
private fun ReportContent(result: AnalysisResult, modifier: Modifier = Modifier) {
    val moves = result.moves
    val context = LocalContext.current

    // Media players for move sounds
    val movePlayer = remember { MediaPlayer.create(context, R.raw.move) }
    val capturePlayer = remember { MediaPlayer.create(context, R.raw.capture) }
    val errorPlayer = remember { MediaPlayer.create(context, R.raw.error) }
    DisposableEffect(Unit) {
        onDispose {
            // Release media players when leaving screen
            listOf(movePlayer, capturePlayer, errorPlayer).forEach { player ->
                runCatching { player.release() }
            }
        }
    }

    // Precompute board states after each half-move for replay
    val boards = remember(moves) {
        val sequence = mutableListOf(BoardState.initial())
        var boardState = sequence.first()
        moves.forEach { move ->
            val nextBoard = boardState.copy()
            val isWhiteMove = (move.ply % 2 == 1)
            nextBoard.applySan(move.san, isWhiteMove)
            sequence += nextBoard
            boardState = nextBoard
        }
        sequence
    }

    var currentIndex by remember { mutableIntStateOf(if (moves.isNotEmpty()) moves.lastIndex else -1) }

    Column(
        modifier = modifier.fillMaxSize().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // If a move is selected, show its details banner
        if (currentIndex in moves.indices) {
            val move = moves[currentIndex]
            MoveDetailBanner(move)
        }

        // Chessboard view showing the current move position
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            val boardSize = 320.dp
            if (currentIndex >= 0) {
                // Show the board after the selected move, highlighting last move squares and classification
                val prevBoard = boards[currentIndex]
                val currentBoard = boards[currentIndex + 1]
                val (fromSq, toSq) = findLastMoveSquares(prevBoard, currentBoard)
                ChessBoard(
                    board = currentBoard,
                    lastFrom = fromSq,
                    lastTo = toSq,
                    moveClass = moves[currentIndex].moveClass,
                    size = boardSize
                )
            } else {
                // No move selected (empty game): just initial board
                ChessBoard(
                    board = boards.first(),
                    lastFrom = null,
                    lastTo = null,
                    moveClass = null,
                    size = boardSize
                )
            }
        }

        // Moves list with chips
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            moves.forEachIndexed { index, move ->
                AssistChip(
                    onClick = {
                        currentIndex = index
                        // Play sound: error for blunders/mistakes, capture or move sound for others
                        when (move.moveClass) {
                            MoveClass.MISTAKE, MoveClass.BLUNDER -> errorPlayer.start()
                            else -> if (move.san.contains("x")) capturePlayer.start() else movePlayer.start()
                        }
                    },
                    label = { Text(move.san) },
                    leadingIcon = {
                        Icon(
                            painter = painterResource(id = classIconRes(move.moveClass)),
                            contentDescription = null
                        )
                    },
                    colors = AssistChipDefaults.assistChipColors(
                        containerColor = chipColorForClass(move.moveClass, selected = index == currentIndex),
                        labelColor = Color.Black,
                        leadingIconContentColor = Color.Black
                    )
                )
            }
        }
    }
}

@Composable
private fun MoveDetailBanner(move: MoveAnalysis) {
    val title = when (move.moveClass) {
        MoveClass.GREAT      -> "Best / Excellent"
        MoveClass.GOOD       -> "Good"
        MoveClass.INACCURACY -> "Inaccuracy"
        MoveClass.MISTAKE    -> "Mistake"
        MoveClass.BLUNDER    -> "Blunder"
    }
    val color = when (move.moveClass) {
        MoveClass.GREAT      -> Color(0xFF66BB6A)
        MoveClass.GOOD       -> Color(0xFF43A047)
        MoveClass.INACCURACY -> Color(0xFFFFC107)
        MoveClass.MISTAKE    -> Color(0xFFFF6D00)
        MoveClass.BLUNDER    -> Color(0xFFD32F2F)
    }
    Surface(color = color.copy(alpha = 0.12f)) {
        Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(painter = painterResource(id = classIconRes(move.moveClass)), contentDescription = null, tint = color)
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text("Сильнейший: ${move.bestMove ?: "-"}")
            }
            Text("Δ ${"%.1f".format(move.lossWinPct)}%", color = color, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun ChessBoard(
    board: BoardState,
    lastFrom: Pair<Int, Int>?,
    lastTo: Pair<Int, Int>?,
    moveClass: MoveClass?,
    modifier: Modifier = Modifier,
    size: dp = 320.dp
) {
    val lightSquareColor = Color(0xFFEEEED2)
    val darkSquareColor  = Color(0xFF769656)
    val highlightOverlay = when (moveClass) {
        MoveClass.GREAT      -> Color(0xFF00C853).copy(alpha = 0.28f)
        MoveClass.GOOD       -> Color(0xFF4CAF50).copy(alpha = 0.28f)
        MoveClass.INACCURACY -> Color(0xFFFFC107).copy(alpha = 0.28f)
        MoveClass.MISTAKE    -> Color(0xFFFF9800).copy(alpha = 0.28f)
        MoveClass.BLUNDER    -> Color(0xFFE53935).copy(alpha = 0.28f)
        null                 -> Color.Transparent
    }

    Box(
        modifier.size(size).background(Color.Transparent),
        contentAlignment = Alignment.Center
    ) {
        // Draw chess board squares
        Canvas(Modifier.fillMaxSize()) {
            val cellSize = min(size.width, size.height) / 8f
            for (rank in 0..7) {
                for (file in 0..7) {
                    val isLight = (rank + file) % 2 == 0
                    drawRect(
                        color = if (isLight) lightSquareColor else darkSquareColor,
                        topLeft = Offset(x = file * cellSize, y = (7 - rank) * cellSize),
                        size = androidx.compose.ui.geometry.Size(cellSize, cellSize)
                    )
                }
            }
            // Highlight last move squares if provided
            fun highlightSquare(square: Pair<Int, Int>?) {
                if (square == null) return
                val (r, f) = square
                drawRect(
                    color = highlightOverlay,
                    topLeft = Offset(x = f * cellSize, y = (7 - r) * cellSize),
                    size = androidx.compose.ui.geometry.Size(cellSize, cellSize),
                    style = Fill
                )
                drawRect(
                    color = highlightOverlay.copy(alpha = 0.9f),
                    topLeft = Offset(x = f * cellSize, y = (7 - r) * cellSize),
                    size = androidx.compose.ui.geometry.Size(cellSize, cellSize),
                    style = Stroke(width = 2f)
                )
            }
            highlightSquare(lastFrom)
            highlightSquare(lastTo)
        }

        // Draw pieces as text glyphs
        val glyphStyle = TextStyle(fontSize = (size.value / 10f).sp)
        Column(Modifier.fillMaxSize()) {
            for (rank in 7 downTo 0) {
                Row(Modifier.weight(1f)) {
                    for (file in 0..7) {
                        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                            val pieceChar = board.at(rank, file)
                            val glyph = glyphFor(pieceChar)
                            if (glyph != null) {
                                val isWhitePiece = pieceChar.isUpperCase()
                                Text(
                                    text = glyph.toString(),
                                    style = glyphStyle,
                                    color = if (isWhitePiece) Color.Black else Color(0xFF111111)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun glyphFor(piece: Char): Char? = when (piece) {
    'K' -> '♔'; 'Q' -> '♕'; 'R' -> '♖'; 'B' -> '♗'; 'N' -> '♘'; 'P' -> '♙'
    'k' -> '♚'; 'q' -> '♛'; 'r' -> '♜'; 'b' -> '♝'; 'n' -> '♞'; 'p' -> '♟'
    else -> null
}

private fun classIconRes(moveClass: MoveClass): Int = when (moveClass) {
    MoveClass.GREAT      -> R.drawable.excellent
    MoveClass.GOOD       -> R.drawable.okay
    MoveClass.INACCURACY -> R.drawable.inaccuracy
    MoveClass.MISTAKE    -> R.drawable.mistake
    MoveClass.BLUNDER    -> R.drawable.blunder
}

private fun chipColorForClass(moveClass: MoveClass, selected: Boolean): Color = when (moveClass) {
    MoveClass.GREAT  -> if (selected) Color(0xFFB9F6CA) else Color(0x80B9F6CA)
    MoveClass.GOOD   -> if (selected) Color(0xFFA5D6A7) else Color(0x80A5D6A7)
    MoveClass.INACCURACY -> if (selected) Color(0xFFFFECB3) else Color(0x80FFECB3)
    MoveClass.MISTAKE    -> if (selected) Color(0xFFFFCC80) else Color(0x80FFCC80)
    MoveClass.BLUNDER    -> if (selected) Color(0xFFEF9A9A) else Color(0x80EF9A9A)
}

private fun findLastMoveSquares(before: BoardState, after: BoardState): Pair<Pair<Int, Int>?, Pair<Int, Int>?> {
    var from: Pair<Int, Int>? = null
    var to: Pair<Int, Int>? = null
    for (r in 0..7) {
        for (f in 0..7) {
            val prev = before.at(r, f)
            val curr = after.at(r, f)
            if (prev != curr) {
                if (prev != '.' && curr == '.') from = r to f
                if (prev == '.' && curr != '.') to = r to f
            }
        }
    }
    return from to to
}
