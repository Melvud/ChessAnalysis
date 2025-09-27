package com.example.chessanalysis.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.zIndex
import coil.ImageLoader
import coil.compose.rememberAsyncImagePainter
import coil.decode.SvgDecoder
import coil.request.ImageRequest
import com.example.chessanalysis.MoveClass
import androidx.compose.ui.input.pointer.pointerInput

@Composable
fun BoardCanvas(
    fen: String,
    lastMove: Pair<String, String>?,
    moveClass: MoveClass? = null,
    bestMoveUci: String? = null,
    showBestArrow: Boolean = false,
    isWhiteBottom: Boolean = true,
    onSquareClick: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    val svgLoader = remember(context) {
        ImageLoader.Builder(context)
            .components { add(SvgDecoder.Factory()) }
            .build()
    }

    val board = fen.substringBefore(' ')
    val ranks = board.split('/')

    var boardPx by remember { mutableStateOf(androidx.compose.ui.geometry.Size.Zero) }

    Box(
        modifier = modifier
            .aspectRatio(1f)
            .onSizeChanged { boardPx = it.toSize() }
    ) {
        // 1) клетки и подсветка
        Canvas(modifier = Modifier.fillMaxSize()) {
            val squareSize = size.minDimension / 8f
            val lightColor = Color(0xFFF0D9B5)
            val darkColor = Color(0xFF8B6F4E)
            val defaultHl = Color(0xFFB58863)
            val classColor = moveClass?.let { mc -> moveClassBadgeRes(mc).container } ?: defaultHl

            // клетки
            for (rank in 0..7) {
                for (file in 0..7) {
                    val isDark = (rank + file) % 2 == 0
                    val x = file * squareSize
                    val y = rank * squareSize
                    drawRect(
                        color = if (isDark) darkColor else lightColor,
                        topLeft = Offset(x, y),
                        size = androidx.compose.ui.geometry.Size(squareSize, squareSize)
                    )
                }
            }
            // подсветка from/to
            lastMove?.let { (from, to) ->
                val fromSquare = squareFromNotation(from)
                val toSquare = squareFromNotation(to)
                if (fromSquare != null && toSquare != null) {
                    val (ff, fr) =
                        if (isWhiteBottom) fromSquare else Pair(7 - fromSquare.first, 7 - fromSquare.second)
                    val (tf, tr) =
                        if (isWhiteBottom) toSquare else Pair(7 - toSquare.first, 7 - toSquare.second)
                    val fromX = ff * squareSize
                    val fromY = fr * squareSize
                    val toX = tf * squareSize
                    val toY = tr * squareSize
                    drawRect(
                        color = classColor.copy(alpha = 0.30f),
                        topLeft = Offset(fromX, fromY),
                        size = androidx.compose.ui.geometry.Size(squareSize, squareSize)
                    )
                    drawRect(
                        color = classColor.copy(alpha = 0.35f),
                        topLeft = Offset(toX, toY),
                        size = androidx.compose.ui.geometry.Size(squareSize, squareSize)
                    )
                }
            }
        }

        // 2) фигуры
        Column(modifier = Modifier.fillMaxSize()) {
            ranks.forEach { rank ->
                Row(Modifier.weight(1f)) {
                    var file = 0
                    for (ch in rank) {
                        if (ch.isDigit()) {
                            repeat(ch.code - '0'.code) {
                                Box(Modifier.weight(1f).fillMaxHeight())
                                file++
                            }
                        } else {
                            val pieceName = when (ch) {
                                'P' -> "wP"; 'N' -> "wN"; 'B' -> "wB"; 'R' -> "wR"; 'Q' -> "wQ"; 'K' -> "wK"
                                'p' -> "bP"; 'n' -> "bN"; 'b' -> "bB"; 'r' -> "bR"; 'q' -> "bQ"; 'k' -> "bK"
                                else -> null
                            }
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                            ) {
                                pieceName?.let {
                                    val painter = rememberAsyncImagePainter(
                                        model = ImageRequest.Builder(context)
                                            .data("file:///android_asset/fresca/$it.svg")
                                            .build(),
                                        imageLoader = svgLoader
                                    )
                                    Image(
                                        painter = painter,
                                        contentDescription = null,
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(6.dp),
                                        contentScale = ContentScale.Fit
                                    )
                                }
                            }
                            file++
                        }
                    }
                }
            }
        }

        // 3) значок класса хода (правый верхний угол клетки назначения)
        if (lastMove != null && moveClass != null && boardPx.width > 0f) {
            val badge = moveClassBadgeRes(moveClass)
            val to = squareFromNotation(lastMove.second)
            if (to != null) {
                val sq = boardPx.minDimension / 8f
                val (tf, tr) = if (isWhiteBottom) to else Pair(7 - to.first, 7 - to.second)
                val x = (tf * sq + sq * 0.62f).toInt()
                val y = (tr * sq + sq * 0.08f).toInt()
                Image(
                    painter = painterResource(badge.iconRes),
                    contentDescription = null,
                    modifier = Modifier
                        .size(22.dp)
                        .zIndex(2f)
                        .offset { IntOffset(x, y) }
                )
            }
        }

        // 4) стрелка лучшего хода
        if (showBestArrow && bestMoveUci != null) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val squareSize = size.minDimension / 8f
                if (bestMoveUci.length >= 4) {
                    val from = squareFromNotation(bestMoveUci.substring(0, 2))
                    val to = squareFromNotation(bestMoveUci.substring(2, 4))
                    if (from != null && to != null) {
                        val (ff, fr) =
                            if (isWhiteBottom) from else Pair(7 - from.first, 7 - from.second)
                        val (tf, tr) =
                            if (isWhiteBottom) to else Pair(7 - to.first, 7 - to.second)
                        val fromX = ff * squareSize + squareSize / 2
                        val fromY = fr * squareSize + squareSize / 2
                        val toX = tf * squareSize + squareSize / 2
                        val toY = tr * squareSize + squareSize / 2
                        drawArrow(
                            start = Offset(fromX, fromY),
                            end = Offset(toX, toY),
                            color = Color(0xFF3FA64A).copy(alpha = 0.85f),
                            strokeWidth = 4.dp.toPx()
                        )
                    }
                }
            }
        }

        // 5) клики по клеткам (detectTapGestures)
        if (onSquareClick != null) {
            val sizeSnapshot = boardPx
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(isWhiteBottom, sizeSnapshot) {
                        detectTapGestures { pos ->
                            val size = sizeSnapshot
                            if (size.width <= 0f) return@detectTapGestures
                            val sq = size.minDimension / 8f
                            var file = (pos.x / sq).toInt().coerceIn(0, 7)
                            var rank = (pos.y / sq).toInt().coerceIn(0, 7)
                            if (!isWhiteBottom) {
                                file = 7 - file
                                rank = 7 - rank
                            }
                            val nf = ('a'.code + file).toChar()
                            val nr = ('8'.code - rank).toChar()
                            onSquareClick("$nf$nr")
                        }
                    }
            )
        }
    }
}

private fun squareFromNotation(notation: String): Pair<Int, Int>? {
    if (notation.length != 2) return null
    val file = notation[0] - 'a'
    val rank = 7 - (notation[1] - '1')
    return if (file in 0..7 && rank in 0..7) Pair(file, rank) else null
}

private fun DrawScope.drawArrow(
    start: Offset,
    end: Offset,
    color: Color,
    strokeWidth: Float
) {
    val path = Path().apply {
        moveTo(start.x, start.y)
        lineTo(end.x, end.y)
    }
    drawPath(path = path, color = color, style = androidx.compose.ui.graphics.drawscope.Stroke(width = strokeWidth))

    val angle = kotlin.math.atan2(end.y - start.y, end.x - start.x)
    val arrowLength = 18.dp.toPx()
    val arrowAngle = kotlin.math.PI / 6

    val arrow1 = Offset(
        end.x - arrowLength * kotlin.math.cos(angle - arrowAngle).toFloat(),
        end.y - arrowLength * kotlin.math.sin(angle - arrowAngle).toFloat()
    )
    val arrow2 = Offset(
        end.x - arrowLength * kotlin.math.cos(angle + arrowAngle).toFloat(),
        end.y - arrowLength * kotlin.math.sin(angle + arrowAngle).toFloat()
    )

    val arrowPath = Path().apply {
        moveTo(end.x, end.y)
        lineTo(arrow1.x, arrow1.y)
        moveTo(end.x, end.y)
        lineTo(arrow2.x, arrow2.y)
    }
    drawPath(path = arrowPath, color = color, style = androidx.compose.ui.graphics.drawscope.Stroke(width = strokeWidth))
}
