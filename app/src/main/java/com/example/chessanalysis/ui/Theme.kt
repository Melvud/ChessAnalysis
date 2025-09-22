package com.example.chessanalysis.ui.theme

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.decode.SvgDecoder
import coil.request.ImageRequest
import com.example.chessanalysis.R
import com.example.chessanalysis.data.model.MoveClass
import com.example.chessanalysis.ui.Typography
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

private val LightColors = lightColorScheme(
    primary = Color(0xFF2E7D32),
    onPrimary = Color.White,
    secondary = Color(0xFF1B5E20),
    onSecondary = Color.White,
    background = Color(0xFFF1F8E9),
    onBackground = Color(0xFF1B1B1B),
    surface = Color.White,
    onSurface = Color(0xFF101010)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF66BB6A),
    secondary = Color(0xFF43A047),
    background = Color(0xFF0D0F0D),
    surface = Color(0xFF121212),
    onSurface = Color(0xFFEDEDED)
)

val Typography = Typography()

@Composable
fun ChessAnalyzerTheme(content: @Composable () -> Unit) {
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        typography = Typography,
        content = content
    )
}

/* --- Иконки ходов --- */

@DrawableRes
fun classIconRes(cls: MoveClass): Int = when (cls) {
    MoveClass.GREAT      -> R.drawable.excellent
    MoveClass.GOOD       -> R.drawable.okay
    MoveClass.INACCURACY -> R.drawable.inaccuracy
    MoveClass.MISTAKE    -> R.drawable.mistake
    MoveClass.BLUNDER    -> R.drawable.blunder
}

/** Компонент шахматной фигурки из assets */
@Composable
fun PieceIcon(
    isWhite: Boolean,
    pieceLetter: Char,
    modifier: Modifier = Modifier.width(20.dp)
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

/** Полоска оценки (как на Chess.com): сверху белые, снизу чёрные. */
private fun evalToWin(e: Double): Double {
    val cp = e * 100.0
    val k = 0.00368208
    val win = 50.0 + 50.0 * (2.0 / (1.0 + exp(-k * cp)) - 1.0)
    return max(0.0, min(100.0, win))
}

@Composable
fun EvalBarVertical(
    evaluationPawns: Double,
    modifier: Modifier = Modifier
        .width(8.dp)
        .fillMaxHeight()
) {
    val win = evalToWin(evaluationPawns).toFloat() / 100f
    Box(modifier.background(Color(0xFF0D0F0D))) {
        Box(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight(win)
                .align(Alignment.TopStart)
                .background(MaterialTheme.colorScheme.surface)
        )
    }
}

/** Определить букву фигуры из SAN ('K','Q','R','B','N', иначе 'P') */
fun pieceFromSAN(san: String): Char {
    val c = san.firstOrNull() ?: 'P'
    return if (c in listOf('K','Q','R','B','N')) c else 'P'
}
