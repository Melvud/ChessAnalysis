package com.example.chessanalysis.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.ImageLoader
import coil.compose.rememberAsyncImagePainter
import coil.decode.SvgDecoder
import coil.request.ImageRequest

/**
 * Отрисовка доски по FEN. Фигуры — SVG из assets/fresca.
 */
@Composable
fun FenBoard(
    fen: String,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    val svgLoader = remember(context) {
        ImageLoader.Builder(context)
            .components { add(SvgDecoder.Factory()) }
            .build()
    }

    val board = fen.substringBefore(' ')
    val ranks = board.split('/')

    Column(modifier = modifier.aspectRatio(1f)) {
        ranks.forEachIndexed { rIndex, rank ->
            Row(Modifier.weight(1f)) {
                var file = 0
                @Composable
                fun square(mod: Modifier, piece: Char?) {
                    // a8 — тёмное. Мы идём сверху вниз (8->1), слева направо (a->h).
                    val isDark = ((rIndex + file) % 2 == 0)
                    Box(
                        modifier = mod
                            .weight(1f)
                            .fillMaxHeight()
                            .background(if (isDark) Color(0xFF769656) else Color(0xFFF0D9B5))
                    ) {
                        if (piece != null) {
                            val name = when (piece) {
                                'P' -> "wP"; 'N' -> "wN"; 'B' -> "wB"; 'R' -> "wR"; 'Q' -> "wQ"; 'K' -> "wK";
                                'p' -> "bP"; 'n' -> "bN"; 'b' -> "bB"; 'r' -> "bR"; 'q' -> "bQ"; 'k' -> "bK";
                                else -> null
                            }
                            name?.let {
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
                    }
                    file += 1
                }
                for (ch in rank) {
                    if (ch.isDigit()) {
                        repeat(ch.code - '0'.code) { square(Modifier, null) }
                    } else {
                        square(Modifier, ch)
                    }
                }
            }
        }
    }
}
