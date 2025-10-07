package com.example.chessanalysis.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
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
    selectedSquare: String? = null,
    legalMoves: Set<String> = emptySet(),
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

    val displayRanks = if (isWhiteBottom) {
        ranks
    } else {
        ranks.reversed()
    }

    var boardPx by remember { mutableStateOf(androidx.compose.ui.geometry.Size.Zero) }

    Box(
        modifier = modifier
            .aspectRatio(1f)
            .onSizeChanged { boardPx = it.toSize() }
    ) {
        // 1) Клетки и подсветка
        Canvas(modifier = Modifier.fillMaxSize()) {
            val squareSize = size.minDimension / 8f
            val lightColor = Color(0xFFF0D9B5)
            val darkColor = Color(0xFF8B6F4E)
            val defaultHl = Color(0xFFB58863)
            val classColor = moveClass?.let { mc -> moveClassBadgeRes(mc).container } ?: defaultHl

            // ИСПРАВЛЕНО: клетка a1 (левый нижний угол для белых) должна быть ТЁМНОЙ
            // rank=0 это 8-я горизонталь (сверху), rank=7 это 1-я горизонталь (снизу)
            // file=0 это вертикаль 'a'
            // Светлая клетка когда сумма (rank + file) ЧЁТНАЯ
            for (rank in 0..7) {
                for (file in 0..7) {
                    val isLight = (rank + file) % 2 == 0
                    val x = file * squareSize
                    val y = rank * squareSize
                    drawRect(
                        color = if (isLight) lightColor else darkColor,
                        topLeft = Offset(x, y),
                        size = androidx.compose.ui.geometry.Size(squareSize, squareSize)
                    )
                }
            }

            // Подсветка последнего хода
            lastMove?.let { (from, to) ->
                val fromSquare = squareFromNotation(from, isWhiteBottom)
                val toSquare = squareFromNotation(to, isWhiteBottom)
                if (fromSquare != null && toSquare != null) {
                    val (ff, fr) = fromSquare
                    val (tf, tr) = toSquare

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

            // Подсветка выбранной клетки
            selectedSquare?.let { sel ->
                squareFromNotation(sel, isWhiteBottom)?.let { (sf, sr) ->
                    val x = sf * squareSize
                    val y = sr * squareSize
                    drawRect(
                        color = Color(0xFF2B7FFF).copy(alpha = 0.65f),
                        topLeft = Offset(x, y),
                        size = androidx.compose.ui.geometry.Size(squareSize, squareSize),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3.dp.toPx())
                    )
                }
            }
        }

        // 2) Фигуры
        Column(modifier = Modifier.fillMaxSize()) {
            displayRanks.forEachIndexed { rankIndex, rank ->
                Row(Modifier.weight(1f)) {
                    var fileIndex = 0
                    for (ch in rank) {
                        if (ch.isDigit()) {
                            repeat(ch.code - '0'.code) {
                                Box(Modifier.weight(1f).fillMaxHeight())
                                fileIndex++
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
                                            .padding(2.dp),
                                        contentScale = ContentScale.Fit
                                    )
                                }
                            }
                            fileIndex++
                        }
                    }
                }
            }
        }

        // 3) Кружки легальных ходов
        if (legalMoves.isNotEmpty()) {
            Canvas(modifier = Modifier.fillMaxSize().zIndex(1f)) {
                val squareSize = size.minDimension / 8f
                val radius = squareSize * 0.18f
                val color = Color.Black.copy(alpha = 0.28f)

                legalMoves.forEach { target ->
                    squareFromNotation(target, isWhiteBottom)?.let { (tf, tr) ->
                        val cx = tf * squareSize + squareSize / 2
                        val cy = tr * squareSize + squareSize / 2
                        drawCircle(
                            color = color,
                            radius = radius,
                            center = Offset(cx, cy)
                        )
                    }
                }
            }
        }

        // 4) Иконка классификации хода
        if (lastMove != null && moveClass != null && boardPx.width > 0f) {
            val badge = moveClassBadgeRes(moveClass)
            squareFromNotation(lastMove.second, isWhiteBottom)?.let { (tf, tr) ->
                val sq = boardPx.minDimension / 8f
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

        // 5) Стрелка лучшего хода
        if (showBestArrow && bestMoveUci != null && bestMoveUci.length >= 4) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val squareSize = size.minDimension / 8f
                val from = squareFromNotation(bestMoveUci.substring(0, 2), isWhiteBottom)
                val to = squareFromNotation(bestMoveUci.substring(2, 4), isWhiteBottom)
                if (from != null && to != null) {
                    val (ff, fr) = from
                    val (tf, tr) = to
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

        // 6) Обработка кликов
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
                            val col = (pos.x / sq).toInt().coerceIn(0, 7)
                            val row = (pos.y / sq).toInt().coerceIn(0, 7)

                            val file = if (isWhiteBottom) col else (7 - col)
                            val rank = if (isWhiteBottom) (7 - row) else row

                            val nf = ('a'.code + file).toChar()
                            val nr = ('1'.code + rank).toChar()
                            onSquareClick("$nf$nr")
                        }
                    }
            )
        }
    }
}

private fun squareFromNotation(notation: String, isWhiteBottom: Boolean): Pair<Int, Int>? {
    if (notation.length != 2) return null
    val fileBoard = notation[0] - 'a'
    val rankBoard = notation[1] - '1'
    if (fileBoard !in 0..7 || rankBoard !in 0..7) return null

    val col = if (isWhiteBottom) fileBoard else (7 - fileBoard)
    val row = if (isWhiteBottom) (7 - rankBoard) else rankBoard

    return Pair(col, row)
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