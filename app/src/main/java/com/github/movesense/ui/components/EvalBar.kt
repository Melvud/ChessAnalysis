// app/src/main/java/com/example/chessanalysis/ui/components/EvalBar.kt
package com.github.movesense.ui.components

import android.util.Log
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.github.movesense.PositionEval
import kotlin.math.abs

private const val TAG = "EvalBar"

@Composable
fun EvalBar(
    positions: List<PositionEval>,
    currentPlyIndex: Int,
    isWhiteBottom: Boolean,
    modifier: Modifier = Modifier
) {
    // ИСПРАВЛЕНО: Зависимость от конкретной позиции, а не от списка
    val pos = positions.getOrNull(currentPlyIndex)
    val positionFen = pos?.fen // Зависимость от FEN гарантирует обновление

    // КРИТИЧНО: Стабильная оценка без миганий - вычисляется при изменении FEN
    val evaluation = remember(positionFen, currentPlyIndex) {
        val line = pos?.lines?.firstOrNull()

        val eval = when {
            line?.cp != null -> {
                val cpValue = line.cp / 100.0f
                Log.d(TAG, "📊 Vertical eval bar: ply=$currentPlyIndex, cp=${line.cp}, eval=$cpValue")
                cpValue
            }
            line?.mate != null -> {
                val mateValue = if (line.mate > 0) 30.0f else -30.0f
                Log.d(TAG, "📊 Vertical eval bar: ply=$currentPlyIndex, mate=${line.mate}, eval=$mateValue")
                mateValue
            }
            else -> {
                Log.d(TAG, "⚠️ Vertical eval bar: ply=$currentPlyIndex, no evaluation available")
                0.0f
            }
        }

        // Возвращаем стабильное значение
        eval
    }

    val cap = 8.0f
    val clamped = evaluation.coerceIn(-cap, cap)
    val t = (clamped + cap) / (2 * cap) // 0..1, где 0 = лучше у чёрных, 1 = лучше у белых

    // ИСПРАВЛЕНИЕ: Более плавная анимация с защитой от резких скачков
    val animT = remember { Animatable(t.coerceIn(0.001f, 0.999f)) }
    LaunchedEffect(t) {
        val targetT = t.coerceIn(0.001f, 0.999f)
        // Проверяем, не слишком ли большой скачок
        val currentValue = animT.value
        val diff = abs(targetT - currentValue)

        if (diff > 0.5f) {
            // Большой скачок - быстрая анимация
            animT.animateTo(targetT, tween(200, easing = FastOutSlowInEasing))
        } else {
            // Обычная плавная анимация
            animT.animateTo(targetT, tween(350, easing = FastOutSlowInEasing))
        }
    }

    Box(modifier = modifier) {
        // Правильная логика отображения с учетом ориентации доски
        if (isWhiteBottom) {
            // Белые внизу: белая часть внизу растет при преимуществе белых
            Box(
                Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(fraction = 1f - animT.value)
                    .background(Color.Black)
                    .align(Alignment.TopStart)
            )
            Box(
                Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(fraction = animT.value)
                    .background(Color.White)
                    .align(Alignment.BottomStart)
            )
        } else {
            // Черные внизу: черная часть внизу растет при преимуществе черных
            Box(
                Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(fraction = animT.value)
                    .background(Color.White)
                    .align(Alignment.TopStart)
            )
            Box(
                Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(fraction = 1f - animT.value)
                    .background(Color.Black)
                    .align(Alignment.BottomStart)
            )
        }
    }
}


// НОВЫЙ КОМПОНЕНТ: Горизонтальный eval bar (для размещения сверху)
@Composable
fun HorizontalEvalBar(
    positions: List<PositionEval>,
    currentPlyIndex: Int,
    isWhiteBottom: Boolean,
    modifier: Modifier = Modifier
) {
    // ИСПРАВЛЕНО: Зависимость от конкретной позиции
    val pos = positions.getOrNull(currentPlyIndex)
    val positionFen = pos?.fen

    val evaluation = remember(positionFen, currentPlyIndex) {
        val line = pos?.lines?.firstOrNull()

        when {
            line?.cp != null -> {
                val cpValue = line.cp / 100.0f
                Log.d(TAG, "📊 Horizontal eval bar: ply=$currentPlyIndex, cp=${line.cp}, eval=$cpValue")
                cpValue
            }
            line?.mate != null -> {
                val mateValue = if (line.mate > 0) 30.0f else -30.0f
                Log.d(TAG, "📊 Horizontal eval bar: ply=$currentPlyIndex, mate=${line.mate}, eval=$mateValue")
                mateValue
            }
            else -> {
                Log.d(TAG, "⚠️ Horizontal eval bar: ply=$currentPlyIndex, no evaluation available")
                0.0f
            }
        }
    }

    val cap = 8.0f
    val clamped = evaluation.coerceIn(-cap, cap)
    val t = (clamped + cap) / (2 * cap) // 0 = черные выигрывают, 1 = белые выигрывают

    val animT = remember { Animatable(t.coerceIn(0.001f, 0.999f)) }
    LaunchedEffect(t) {
        val targetT = t.coerceIn(0.001f, 0.999f)
        val currentValue = animT.value
        val diff = abs(targetT - currentValue)

        if (diff > 0.5f) {
            animT.animateTo(targetT, tween(200, easing = FastOutSlowInEasing))
        } else {
            animT.animateTo(targetT, tween(350, easing = FastOutSlowInEasing))
        }
    }

    Box(modifier = modifier) {
        // ИСПРАВЛЕНО: Правильное горизонтальное отображение
        // Слева черные, справа белые
        Row(modifier = Modifier.fillMaxSize()) {
            // Черная часть слева
            Box(
                Modifier
                    .fillMaxHeight()
                    .weight(1f - animT.value)
                    .background(Color.Black)
            )
            // Белая часть справа
            Box(
                Modifier
                    .fillMaxHeight()
                    .weight(animT.value)
                    .background(Color.White)
            )
        }
    }
}