package com.example.chessanalysis.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.decode.SvgDecoder
import coil.request.ImageRequest
import com.example.chessanalysis.R
import com.example.chessanalysis.data.model.MoveClass
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/* ---------- SVG фигурка из assets ---------- */

@Composable
fun PieceIcon(
    isWhite: Boolean,
    pieceLetter: Char,           // 'K','Q','R','B','N' или 'P'
    modifier: Modifier = Modifier.size(20.dp)
) {
    val colorPrefix = if (isWhite) "w" else "b"
    val fileName = when (pieceLetter.uppercaseChar()) {
        'K' -> "${colorPrefix}K.svg"
        'Q' -> "${colorPrefix}Q.svg"
        'R' -> "${colorPrefix}R.svg"
        'B' -> "${colorPrefix}B.svg"
        'N' -> "${colorPrefix}N.svg"
        else -> "${colorPrefix}P.svg"
    }
    val ctx = LocalContext.current
    val imageLoader = ImageLoader.Builder(ctx)
        .components { add(SvgDecoder.Factory()) }
        .build()

    AsyncImage(
        model = ImageRequest.Builder(ctx)
            .data("file:///android_asset/fresca/$fileName")
            .build(),
        imageLoader = imageLoader,
        contentDescription = null,
        modifier = modifier
    )
}

/* ---------- Иконка класса хода из drawable ---------- */

@DrawableRes
fun classIconRes(cls: MoveClass): Int = when (cls) {
    MoveClass.GREAT      -> R.drawable.excellent
    MoveClass.GOOD       -> R.drawable.okay
    MoveClass.INACCURACY -> R.drawable.inaccuracy
    MoveClass.MISTAKE    -> R.drawable.mistake
    MoveClass.BLUNDER    -> R.drawable.blunder
}

@Composable
fun ClassBadge(cls: MoveClass, modifier: Modifier = Modifier.size(20.dp)) {
    Image(
        painter = painterResource(id = classIconRes(cls)),
        contentDescription = null,
        modifier = modifier
    )
}

/* ---------- Вертикальный эвал-бар (как на Chess.com) ---------- */

private fun evalToWin(e: Double): Double {
    // на вход: оценка в пешках, на выход: Win% (0..100)
    val cp = e * 100.0
    val k = 0.00368208
    val win = 50.0 + 50.0 * (2.0 / (1.0 + exp(-k * cp)) - 1.0)
    return max(0.0, min(100.0, win))
}

/** Высота — под карточку; ширина фиксированная. Слева белые, снизу чёрные. */
@Composable
fun EvalBarVertical(
    evaluationPawns: Double,
    modifier: Modifier = Modifier
        .width(8.dp)
        .fillMaxHeight()
) {
    val win = evalToWin(evaluationPawns).toFloat() / 100f
    Box(modifier.background(Color(0xFF0D0F0D))) { // фон тёмный
        // белая часть сверху
        Box(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight(win)
                .align(Alignment.TopStart)
                .background(MaterialTheme.colorScheme.surface)
        )
        // чёрная часть снизу — остаётся фоном
    }
}

/* ---------- Вспомогалка: определить фигуру из SAN ---------- */

/** Возвращает букву фигуры из SAN ('K','Q','R','B','N'), иначе 'P' (пешка). */
fun pieceFromSAN(san: String): Char {
    val c = san.firstOrNull() ?: 'P'
    return if (c in listOf('K','Q','R','B','N')) c else 'P'
}
