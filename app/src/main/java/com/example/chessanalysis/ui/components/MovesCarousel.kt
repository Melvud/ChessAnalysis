package com.example.chessanalysis.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.chessanalysis.FullReport
import com.example.chessanalysis.MoveClass
import kotlin.math.max

/**
 * Горизонтальная карусель ходов:
 * - текущий ход стараемся держать в центре (прокруткой)
 * - плитка каждого хода прокрашивается по качеству
 * - слева/справа — стрелки навигации
 * - на плитке: иконка качества из ваших PNG и SAN.
 */
@Composable
fun MovesCarousel(
    report: FullReport,
    currentPlyIndex: Int,
    onSeekTo: (Int) -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier
) {
    val darkBg = Color(0xFF1E1C1A)
    val moves = report.moves
    val lazyListState = rememberLazyListState()

    // Центрируем текущий элемент (плюс-минус)
    LaunchedEffect(currentPlyIndex) {
        val anchor = max(0, currentPlyIndex - 2)
        lazyListState.animateScrollToItem(anchor)
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(darkBg),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onPrev) {
            Icon(Icons.Filled.KeyboardArrowLeft, contentDescription = "Назад", tint = Color.White)
        }

        LazyRow(
            state = lazyListState,
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            itemsIndexed(moves) { index, move ->
                val badge = moveClassBadgeRes(move.classification)

                // мягкая подложка под плитку с учётом класса
                val tileColor = when (move.classification) {
                    MoveClass.SPLENDID, MoveClass.PERFECT, MoveClass.BEST,
                    MoveClass.EXCELLENT, MoveClass.OKAY ->
                        badge.container.copy(alpha = if (index == currentPlyIndex) 0.36f else 0.20f)

                    MoveClass.INACCURACY ->
                        badge.container.copy(alpha = if (index == currentPlyIndex) 0.40f else 0.22f)

                    MoveClass.MISTAKE ->
                        badge.container.copy(alpha = if (index == currentPlyIndex) 0.40f else 0.22f)

                    MoveClass.BLUNDER ->
                        badge.container.copy(alpha = if (index == currentPlyIndex) 0.42f else 0.24f)

                    MoveClass.FORCED, MoveClass.OPENING ->
                        badge.container.copy(alpha = if (index == currentPlyIndex) 0.28f else 0.18f)
                }

                Surface(
                    color = tileColor,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier
                        .padding(horizontal = 4.dp)
                        .clickable { onSeekTo(index) }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            painter = painterResource(badge.iconRes),
                            contentDescription = null,
                            tint = Color.Unspecified,
                            modifier = Modifier
                                .size(16.dp)
                                .padding(end = 6.dp)
                        )
                        Text(
                            text = move.san,
                            color = Color.White,
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = if (index == currentPlyIndex) FontWeight.Bold else FontWeight.Medium)
                        )
                    }
                }
            }
        }

        IconButton(onClick = onNext) {
            Icon(Icons.Filled.KeyboardArrowRight, contentDescription = "Вперёд", tint = Color.White)
        }
    }
}
