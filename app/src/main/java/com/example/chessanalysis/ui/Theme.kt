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
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import androidx.compose.foundation.layout.aspectRatio
import com.example.chessanalysis.data.model.MoveClass.*

private val LightColors = lightColorScheme(
    primary = Color(0xFF2E7D32),
    onPrimary = Color.White,
    secondary = Color(0xFF388E3C),
    onSecondary = Color.White,
    background = Color(0xFFF1F8E9),
    onBackground = Color(0xFF212121),
    surface = Color.White,
    onSurface = Color(0xFF212121)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF66BB6A),
    secondary = Color(0xFF43A047),
    background = Color(0xFF0F1E15),
    surface = Color(0xFF1B362A),
    onSurface = Color(0xFFE8F5E9)
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

/** Ресурсы для иконок классов. */
@DrawableRes
fun classIconRes(cls: MoveClass): Int = when (cls) {
    BEST -> R.drawable.best
    EXCELLENT -> R.drawable.excellent
    INACCURACY -> R.drawable.inaccuracy
    MISTAKE -> R.drawable.mistake
    BLUNDER -> R.drawable.blunder
    SPLENDID -> R.drawable.splendid
    PERFECT -> R.drawable.perfect
    OPENING -> R.drawable.opening
    OKAY -> R.drawable.okay
    GREAT -> R.drawable.perfect
    GOOD -> R.drawable.okay
}

/** Отображение SVG фигуры. */
@Composable
fun PieceIcon(
    isWhite: Boolean,
    pieceLetter: Char,
    modifier: Modifier = Modifier
        .width(24.dp)
        .aspectRatio(1f)
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
    val loader = ImageLoader.Builder(ctx)
        .components { add(SvgDecoder.Factory()) }
        .build()
    AsyncImage(
        model = ImageRequest.Builder(ctx)
            .data("file:///android_asset/fresca/$fileName")
            .build(),
        imageLoader = loader,
        contentDescription = null,
        modifier = modifier
    )
}

/** Преобразование оценки в вероятность для вертикальной полоски. */
private fun evalToWin(e: Double): Double {
    val cp = e * 100.0
    val k = 0.00368208
    val win = 50.0 + 50.0 * (2.0 / (1.0 + exp(-k * cp)) - 1.0)
    return max(0.0, min(100.0, win))
}

/** Полоска оценки (как на chess.com). */
@Composable
fun EvalBarVertical(
    evaluationPawns: Double,
    modifier: Modifier = Modifier
        .width(8.dp)
        .fillMaxHeight()
) {
    val win = evalToWin(evaluationPawns).toFloat() / 100f
    Box(modifier.background(MaterialTheme.colorScheme.surface)) {
        Box(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight(win)
                .align(Alignment.TopStart)
                .background(MaterialTheme.colorScheme.primary)
        )
    }
}
