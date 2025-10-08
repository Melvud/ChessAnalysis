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

    // ИСПРАВЛЕНО: Переворачиваем ранги если черные снизу
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

            // Рисуем клетки
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
                val fromSquare = squareToCanvas(from, isWhiteBottom)
                val toSquare = squareToCanvas(to, isWhiteBottom)
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
                squareToCanvas(sel, isWhiteBottom)?.let { (sf, sr) ->
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

        // 2) ИСПРАВЛЕНО: Фигуры с правильной обработкой переворота
        Column(modifier = Modifier.fillMaxSize()) {
            displayRanks.forEachIndexed { rankIndex, rank ->
                Row(Modifier.weight(1f)) {
                    // Парсим ранг в список фигур
                    val pieces = mutableListOf<Char?>()
                    for (ch in rank) {
                        if (ch.isDigit()) {
                            repeat(ch.code - '0'.code) {
                                pieces.add(null)
                            }
                        } else {
                            pieces.add(ch)
                        }
                    }

                    // КРИТИЧНО: Переворачиваем порядок фигур если черные снизу
                    val displayPieces = if (isWhiteBottom) pieces else pieces.reversed()

                    displayPieces.forEach { piece ->
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                        ) {
                            piece?.let { ch ->
                                val pieceName = when (ch) {
                                    'P' -> "wP"; 'N' -> "wN"; 'B' -> "wB"
                                    'R' -> "wR"; 'Q' -> "wQ"; 'K' -> "wK"
                                    'p' -> "bP"; 'n' -> "bN"; 'b' -> "bB"
                                    'r' -> "bR"; 'q' -> "bQ"; 'k' -> "bK"
                                    else -> null
                                }
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
                    squareToCanvas(target, isWhiteBottom)?.let { (tf, tr) ->
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
            squareToCanvas(lastMove.second, isWhiteBottom)?.let { (tf, tr) ->
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
                val from = squareToCanvas(bestMoveUci.substring(0, 2), isWhiteBottom)
                val to = squareToCanvas(bestMoveUci.substring(2, 4), isWhiteBottom)
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

/**
 * Преобразует шахматную нотацию (например, "e4") в координаты Canvas (col, row)
 * с учётом ориентации доски.
 */
private fun squareToCanvas(notation: String, isWhiteBottom: Boolean): Pair<Int, Int>? {
    if (notation.length != 2) return null
    val fileBoard = notation[0] - 'a'  // 0..7 (a..h)
    val rankBoard = notation[1] - '1'  // 0..7 (1..8)
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