// app/src/main/java/com/example/chessanalysis/ui/components/EvalBar.kt
package com.example.chessanalysis.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import com.example.chessanalysis.PositionEval

@Composable
fun EvalBar(
    positions: List<PositionEval>,
    currentPlyIndex: Int,
    isWhiteBottom: Boolean,                 // ← НОВОЕ
    modifier: Modifier = Modifier
) {
    val evaluation = remember(positions, currentPlyIndex) {
        positions.getOrNull(currentPlyIndex)?.lines?.firstOrNull()?.let { line ->
            when {
                line.cp != null -> line.cp / 100.0f
                line.mate != null -> if (line.mate > 0) 30.0f else -30.0f
                else -> 0.0f
            }
        } ?: 0.0f
    }

    val cap = 8.0f
    val clamped = evaluation.coerceIn(-cap, cap)
    val t = (clamped + cap) / (2 * cap) // 0..1, 0 = лучше у чёрных, 1 = лучше у белых

    val animT = remember { Animatable(t.coerceIn(0.001f, 0.999f)) }
    LaunchedEffect(t) {
        animT.animateTo(t.coerceIn(0.001f, 0.999f), tween(350, easing = FastOutSlowInEasing))
    }

    // Никаких текстов, только белый/чёрный и НУЛЕВЫЕ отступы
    Box(modifier = modifier) {
        // Верхняя половина
        Box(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight(
                    fraction = if (isWhiteBottom) 1f - animT.value else animT.value
                )
                .background(if (isWhiteBottom) Color.Black else Color.White)
                .align(Alignment.TopStart)
        )
        // Нижняя половина
        Box(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight(
                    fraction = if (isWhiteBottom) animT.value else 1f - animT.value
                )
                .background(if (isWhiteBottom) Color.White else Color.Black)
                .align(Alignment.BottomStart)
        )
    }
}
