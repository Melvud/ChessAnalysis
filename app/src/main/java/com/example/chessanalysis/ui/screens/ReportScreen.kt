@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.chessanalysis.R
import com.example.chessanalysis.data.model.AnalysisResult
import com.example.chessanalysis.data.model.MoveAnalysis
import com.example.chessanalysis.data.model.MoveClass
import com.example.chessanalysis.data.util.BoardState    // ←← правильный пакет
import kotlin.math.abs
import kotlin.math.min

// ... остальной файл без изменений ...


@Composable
fun ReportAwaitScreen(onBack: () -> Unit) {
    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Детальный отчёт") },
            navigationIcon = { TextButton(onClick = onBack) { Text("Назад") } }
        )
    }) { p ->
        Box(Modifier.padding(p).fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
    }
}

@Composable
fun ReportScreen(result: AnalysisResult, onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Детальный отчёт") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Назад") } }
            )
        }
    ) { padding ->
        ReportContent(result, Modifier.padding(padding))
    }
}

private enum class Bucket { GREAT, GOOD, INA, MIST, BLUN }
private fun bucketOf(absDelta: Double): Bucket = when {
    absDelta < 0.05 -> Bucket.GREAT
    absDelta < 0.20 -> Bucket.GOOD
    absDelta < 0.60 -> Bucket.INA
    absDelta < 1.60 -> Bucket.MIST
    else            -> Bucket.BLUN
}

@Composable
private fun ReportContent(result: AnalysisResult, modifier: Modifier = Modifier) {
    val moves = result.moves
    val context = LocalContext.current

    val movePlayer = remember { MediaPlayer.create(context, R.raw.move) }
    val capturePlayer = remember { MediaPlayer.create(context, R.raw.capture) }
    val errorPlayer = remember { MediaPlayer.create(context, R.raw.error) }
    DisposableEffect(Unit) { onDispose {
        runCatching { movePlayer.release(); capturePlayer.release(); errorPlayer.release() }
    } }

    val boards = remember(moves) {
        val seq = mutableListOf(BoardState.initial())
        var b = seq.first()
        moves.forEach { m ->
            val next = b.copy()
            val isWhiteMove = (m.moveNumber % 2 == 1)
            next.applySan(m.san, isWhiteMove)
            seq += next
            b = next
        }
        seq
    }

    var index by remember { mutableIntStateOf(if (moves.isNotEmpty()) moves.lastIndex else -1) }

    Column(
        modifier
            .fillMaxSize()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (index in moves.indices) {
            val m = moves[index]
            val cls = when (bucketOf(abs(m.delta))) {
                Bucket.GREAT -> MoveClass.GREAT
                Bucket.GOOD  -> MoveClass.GOOD
                Bucket.INA   -> MoveClass.INACCURACY
                Bucket.MIST  -> MoveClass.MISTAKE
                Bucket.BLUN  -> MoveClass.BLUNDER
            }
            Banner(m, cls)
        }

        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            val sizeDp = 320.dp
            if (index >= 0) {
                val prev = boards[index]
                val cur = boards[index + 1]
                val (from, to) = findDiff(prev, cur)
                ChessBoard(
                    board = cur,
                    lastFrom = from,
                    lastTo = to,
                    moveClass = when (bucketOf(abs(moves[index].delta))) {
                        Bucket.GREAT -> MoveClass.GREAT
                        Bucket.GOOD  -> MoveClass.GOOD
                        Bucket.INA   -> MoveClass.INACCURACY
                        Bucket.MIST  -> MoveClass.MISTAKE
                        Bucket.BLUN  -> MoveClass.BLUNDER
                    },
                    size = sizeDp
                )
            } else {
                ChessBoard(
                    board = boards.first(),
                    lastFrom = null,
                    lastTo = null,
                    moveClass = null,
                    size = 320.dp
                )
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            moves.forEachIndexed { i, m ->
                val bucket = bucketOf(abs(m.delta))
                AssistChip(
                    onClick = {
                        index = i
                        when (bucket) {
                            Bucket.BLUN, Bucket.MIST -> errorPlayer.start()
                            else -> if (m.san.contains("x")) capturePlayer.start() else movePlayer.start()
                        }
                    },
                    label = { Text(m.san) },
                    leadingIcon = {
                        Icon(
                            painter = painterResource(id = classIconRes(
                                when (bucket) {
                                    Bucket.GREAT -> MoveClass.GREAT
                                    Bucket.GOOD  -> MoveClass.GOOD
                                    Bucket.INA   -> MoveClass.INACCURACY
                                    Bucket.MIST  -> MoveClass.MISTAKE
                                    Bucket.BLUN  -> MoveClass.BLUNDER
                                }
                            )),
                            contentDescription = null
                        )
                    },
                    colors = AssistChipDefaults.assistChipColors(
                        containerColor = chipColor(bucket, selected = i == index),
                        labelColor = Color.Black,
                        leadingIconContentColor = Color.Black
                    )
                )
            }
        }
    }
}

@Composable
private fun Banner(m: MoveAnalysis, cls: MoveClass) {
    val title = when (cls) {
        MoveClass.GREAT      -> "Best / Excellent"
        MoveClass.GOOD       -> "Good"
        MoveClass.INACCURACY -> "Inaccuracy"
        MoveClass.MISTAKE    -> "Mistake"
        MoveClass.BLUNDER    -> "Blunder"
    }
    val color = when (cls) {
        MoveClass.GREAT      -> Color(0xFF66BB6A)
        MoveClass.GOOD       -> Color(0xFF43A047)
        MoveClass.INACCURACY -> Color(0xFFFFC107)
        MoveClass.MISTAKE    -> Color(0xFFFF6D00)
        MoveClass.BLUNDER    -> Color(0xFFD32F2F)
    }
    Surface(color = color.copy(alpha = 0.12f)) {
        Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(painter = painterResource(id = classIconRes(cls)), contentDescription = null, tint = color)
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text("Сильнейший: ${m.bestMove}")
            }
            Text("Δ ${"%.2f".format(abs(m.delta))}", color = color, fontWeight = FontWeight.Bold)
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
    size: Dp = 320.dp
) {
    val light = Color(0xFFEEEED2)
    val dark  = Color(0xFF769656)

    val overlay = when (moveClass) {
        MoveClass.GREAT      -> Color(0xFF00C853).copy(alpha = 0.28f)
        MoveClass.GOOD       -> Color(0xFF4CAF50).copy(alpha = 0.28f)
        MoveClass.INACCURACY -> Color(0xFFFFC107).copy(alpha = 0.28f)
        MoveClass.MISTAKE    -> Color(0xFFFF9800).copy(alpha = 0.28f)
        MoveClass.BLUNDER    -> Color(0xFFE53935).copy(alpha = 0.28f)
        null                 -> Color.Transparent
    }

    Box(
        modifier
            .size(size)
            .background(Color.Transparent),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val cell = min(this.size.width, this.size.height) / 8f
            for (r in 0..7) for (c in 0..7) {
                val color = if ((r + c) % 2 == 0) light else dark
                drawRect(
                    color = color,
                    topLeft = Offset(c * cell, (7 - r) * cell),
                    size = androidx.compose.ui.geometry.Size(cell, cell)
                )
            }
            fun drawOverlay(rc: Pair<Int,Int>?) {
                if (rc == null) return
                val (r,c) = rc
                drawRect(
                    color = overlay,
                    topLeft = Offset(c * cell, (7 - r) * cell),
                    size = androidx.compose.ui.geometry.Size(cell, cell),
                    style = Fill
                )
                drawRect(
                    color = overlay.copy(alpha = 0.9f),
                    topLeft = Offset(c * cell, (7 - r) * cell),
                    size = androidx.compose.ui.geometry.Size(cell, cell),
                    style = Stroke(width = 2f)
                )
            }
            drawOverlay(lastFrom); drawOverlay(lastTo)
        }

        val glyphStyle = TextStyle(fontSize = (size.value / 10f).sp)
        Column(Modifier.fillMaxSize()) {
            for (rank in 7 downTo 0) {
                Row(Modifier.weight(1f)) {
                    for (file in 0..7) {
                        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                            val ch = board.at(rank, file)
                            val g = glyphFor(ch)
                            if (g != null) {
                                val isWhite = ch.isUpperCase()
                                Text(
                                    text = g.toString(),
                                    style = glyphStyle,
                                    color = if (isWhite) Color.Black else Color(0xFF111111)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun glyphFor(ch: Char): Char? = when (ch) {
    'K' -> '♔'; 'Q' -> '♕'; 'R' -> '♖'; 'B' -> '♗'; 'N' -> '♘'; 'P' -> '♙'
    'k' -> '♚'; 'q' -> '♛'; 'r' -> '♜'; 'b' -> '♝'; 'n' -> '♞'; 'p' -> '♟'
    else -> null
}

private fun classIconRes(cls: MoveClass): Int = when (cls) {
    MoveClass.GREAT      -> R.drawable.excellent   // <-- синхронизировано с Icons.kt
    MoveClass.GOOD       -> R.drawable.okay
    MoveClass.INACCURACY -> R.drawable.inaccuracy
    MoveClass.MISTAKE    -> R.drawable.mistake
    MoveClass.BLUNDER    -> R.drawable.blunder
}

private fun chipColor(b: Bucket, selected: Boolean): Color = when (b) {
    Bucket.GREAT -> if (selected) Color(0xFFB9F6CA) else Color(0x80B9F6CA)
    Bucket.GOOD  -> if (selected) Color(0xFFA5D6A7) else Color(0x80A5D6A7)
    Bucket.INA   -> if (selected) Color(0xFFFFECB3) else Color(0x80FFECB3)
    Bucket.MIST  -> if (selected) Color(0xFFFFCC80) else Color(0x80FFCC80)
    Bucket.BLUN  -> if (selected) Color(0xFFEF9A9A) else Color(0x80EF9A9A)
}

private fun findDiff(a: BoardState, b: BoardState): Pair<Pair<Int,Int>?, Pair<Int,Int>?> {
    var from: Pair<Int,Int>? = null; var to: Pair<Int,Int>? = null
    for (r in 0..7) for (c in 0..7) {
        val x = a.at(r,c); val y = b.at(r,c)
        if (x != y) {
            if (x != '.' && y == '.') from = r to c
            if (x == '.' && y != '.') to = r to c
        }
    }
    return from to to
}
