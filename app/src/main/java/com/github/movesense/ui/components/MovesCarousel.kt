package com.github.movesense.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.movesense.FullReport
import kotlinx.coroutines.launch

/**
 * Горизонтальная карусель ходов с центрированием текущего хода.
 * Нулевой ход (стартовая позиция) невидим, но занимает индекс 0.
 * Реальные ходы начинаются с индекса 1.
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
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    // Центрируем текущий ход
    LaunchedEffect(currentPlyIndex) {
        scope.launch {
            if (currentPlyIndex == 0) {
                // Для нулевого хода просто прокручиваем в начало
                lazyListState.animateScrollToItem(0, scrollOffset = 0)
            } else {
                // Для реальных ходов центрируем элемент
                // Индекс в LazyRow = currentPlyIndex (0 = невидимый, 1+ = ходы)
                val itemIndex = currentPlyIndex

                // Получаем информацию о видимой области
                val layoutInfo = lazyListState.layoutInfo
                val viewportCenter = layoutInfo.viewportEndOffset / 2

                // Прокручиваем элемент к центру экрана
                lazyListState.animateScrollToItem(
                    index = itemIndex,
                    scrollOffset = -viewportCenter
                )
            }
        }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(darkBg)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(
            onClick = onPrev,
            modifier = Modifier.size(48.dp)
        ) {
            Icon(
                Icons.Filled.KeyboardArrowLeft,
                contentDescription = "Назад",
                tint = Color.White,
                modifier = Modifier.size(32.dp)
            )
        }

        LazyRow(
            state = lazyListState,
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(horizontal = 8.dp)
        ) {
            // Нулевая плитка — НЕВИДИМАЯ (занимает место, но ничего не рендерит)
            item(key = "start") {
                // Пустой Spacer шириной 0dp — плитка существует, но не видна
                Spacer(modifier = Modifier.width(0.dp))
            }

            // Остальные плитки — реальные ходы
            itemsIndexed(moves, key = { idx, _ -> "move_$idx" }) { index, move ->
                val badge = moveClassBadgeRes(move.classification)

                // Индекс плитки = index + 1 (так как 0 занят невидимой плиткой)
                val tileIndex = index + 1
                val isCurrent = currentPlyIndex == tileIndex

                // Определяем номер хода для отображения
                val isWhiteMove = index % 2 == 0
                val moveNumber = if (isWhiteMove) {
                    "${index / 2 + 1}."  // 1. 2. 3. ...
                } else {
                    "${index / 2 + 1}..." // 1... 2... 3... ...
                }

                val bgColor = if (isCurrent) {
                    Color(0xFF4A90E2)
                } else {
                    Color(0xFF2F2F2F)
                }

                Surface(
                    color = bgColor,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.clickable { onSeekTo(tileIndex) }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Номер хода
                        Text(
                            text = moveNumber,
                            color = Color.White.copy(alpha = 0.6f),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium
                        )

                        // Нотация хода
                        Text(
                            text = move.san,
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )

                        // Иконка качества хода
                        Icon(
                            painter = painterResource(badge.iconRes),
                            contentDescription = null,
                            tint = Color.Unspecified,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }

        IconButton(
            onClick = onNext,
            modifier = Modifier.size(48.dp)
        ) {
            Icon(
                Icons.Filled.KeyboardArrowRight,
                contentDescription = "Вперёд",
                tint = Color.White,
                modifier = Modifier.size(32.dp)
            )
        }
    }
}
