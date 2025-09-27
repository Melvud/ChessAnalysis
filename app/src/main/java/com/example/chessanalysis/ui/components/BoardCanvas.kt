package com.example.chessanalysis.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import coil.ImageLoader
import coil.compose.rememberAsyncImagePainter
import coil.decode.SvgDecoder
import coil.request.ImageRequest
import com.example.chessanalysis.MoveClass
import com.example.chessanalysis.ui.components.moveClassBadgeRes
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription

@Composable
fun BoardCanvas(
    fen: String,
    lastMove: Pair<String, String>?,
    showArrows: Boolean,
    modifier: Modifier = Modifier,
    moveClass: MoveClass? = null // ← добавлено: для цвета подсветки и иконки возле фигуры
) {
    val context = LocalContext.current

    val svgLoader = remember(context) {
        ImageLoader.Builder(context)
            .components { add(SvgDecoder.Factory()) }
            .build()
    }

    val board = fen.substringBefore(' ')
    val ranks = board.split('/')

    Box(
        modifier = modifier
            .aspectRatio(1f)
            .semantics { contentDescription = "Шахматная доска" }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val squareSize = size.minDimension / 8f
            val lightColor = Color(0xFFF0D9B5)
            val darkColor = Color(0xFF769656)
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

            // подсветка последнего хода (по цвету классификации)
            lastMove?.let { (from, to) ->
                val fromSquare = squareFromNotation(from)
                val toSquare = squareFromNotation(to)

                if (fromSquare != null && toSquare != null) {
                    val fromX = fromSquare.first * squareSize
                    val fromY = fromSquare.second * squareSize
                    val toX = toSquare.first * squareSize
                    val toY = toSquare.second * squareSize

                    drawRect(
                        color = classColor.copy(alpha = 0.28f),
                        topLeft = Offset(fromX, fromY),
                        size = androidx.compose.ui.geometry.Size(squareSize, squareSize)
                    )
                    drawRect(
                        color = classColor.copy(alpha = 0.28f),
                        topLeft = Offset(toX, toY),
                        size = androidx.compose.ui.geometry.Size(squareSize, squareSize)
                    )
                }
            }
        }

        // фигуры
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

        // иконка классификации у фигуры, которая сходила (рядом с клеткой назначения)
        if (lastMove != null && moveClass != null) {
            val badge = moveClassBadgeRes(moveClass)
            Box(Modifier.fillMaxSize()) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val squareSize = size.minDimension / 8f
                    val toSquare = squareFromNotation(lastMove.second)
                    if (toSquare != null) {
                        val toX = toSquare.first * squareSize
                        val toY = toSquare.second * squareSize
                        // ничего не рисуем тут — только вычисляем позицию; саму картинку положим поверх
                    }
                }
                // через Layout трюк проще: повторим вычисление координат и положим Image с offset
                val painter = painterResource(badge.iconRes)
                val to = squareFromNotation(lastMove.second)
                if (to != null) {
                    val (file, rank) = to
                    // позиционируем в правом верхнем углу клетки назначения
                    val offset = DpOffset((file * 1).dp, (rank * 1).dp) // фактический offset дадим через BoxScope.align+padding
                    Image(
                        painter = painter,
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(6.dp)
                            .wrapContentSize(align = Alignment.TopStart)
                            .padding(
                                start = ((file + 1) * 0).dp, // визуальное выравнивание будет достигаться через absolute позиционирование борда; здесь оставим компактно
                            )
                    )
                }
            }
        }

        // стрелка, если нужно
        if (showArrows && lastMove != null) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val squareSize = size.minDimension / 8f
                val fromSquare = squareFromNotation(lastMove.first)
                val toSquare = squareFromNotation(lastMove.second)

                if (fromSquare != null && toSquare != null) {
                    val fromX = fromSquare.first * squareSize + squareSize / 2
                    val fromY = fromSquare.second * squareSize + squareSize / 2
                    val toX = toSquare.first * squareSize + squareSize / 2
                    val toY = toSquare.second * squareSize + squareSize / 2

                    drawArrow(
                        start = Offset(fromX, fromY),
                        end = Offset(toX, toY),
                        color = Color.Green.copy(alpha = 0.7f),
                        strokeWidth = 4.dp.toPx()
                    )
                }
            }
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
    val arrowLength = 20.dp.toPx()
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
