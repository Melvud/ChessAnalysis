package com.example.chessanalysis.ui.screens

import android.annotation.SuppressLint
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.chessanalysis.FullReport
import com.example.chessanalysis.ui.components.FenBoard
import kotlin.math.max
import kotlin.math.min

@SuppressLint("UnusedBoxWithConstraintsScope")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BoardScreen(
    report: FullReport,
    onBack: () -> Unit
) {
    // индекс текущей позиции: 0 = начальная, далее после каждого полухода
    var idx by remember { mutableStateOf(0) }
    val last = report.positions.lastIndex

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Отчёт о партии") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = null)
                    }
                }
            )
        },
        bottomBar = {
            // одна фиксированная кнопка снизу
            Surface(tonalElevation = 3.dp) {
                Box(Modifier.fillMaxWidth().padding(12.dp)) {
                    Button(
                        onClick = onBack,
                        modifier = Modifier.fillMaxWidth().height(56.dp)
                    ) { Text("Вернуться") }
                }
            }
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Центрированная связка «эвал-бар + доска»
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val evalW: Dp = 18.dp
                val gap: Dp = 12.dp
                val boardSize = maxWidth - evalW - gap

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Эвал-бар слева; высота совпадает с размером доски
                    EvalBar(
                        cp = report.positions[idx].lines.firstOrNull()?.cp ?: 0,
                        modifier = Modifier
                            .width(evalW)
                            .height(boardSize)
                    )
                    Spacer(Modifier.width(gap))
                    // Большая доска по центру
                    FenBoard(
                        fen = report.positions[idx].fen,
                        modifier = Modifier.size(boardSize)
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            // Лента ходов с навигационными стрелками
            MovesStrip(
                report = report,
                currentIndex = idx,
                onPrev = { if (idx > 0) idx-- },
                onNext = { if (idx < last) idx++ },
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(80.dp)) // чтобы контент не перекрывался bottomBar'ом
        }
    }
}

@Composable
private fun MovesStrip(
    report: FullReport,
    currentIndex: Int,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scroll = rememberScrollState()
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onPrev, enabled = currentIndex > 0) {
            Icon(Icons.Filled.KeyboardArrowLeft, contentDescription = "Назад")
        }

        Row(
            Modifier
                .weight(1f)
                .horizontalScroll(scroll),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Формат: 1. e4 e5 2. Nf3 Nc6 ... ; подсветка текущего полухода (currentIndex - 1)
            val hm = (currentIndex - 1).coerceAtLeast(-1)
            val moves = report.moves
            var num = 1
            var i = 0
            while (i < moves.size) {
                Text(text = "$num.", color = Color.LightGray)
                Spacer(Modifier.width(6.dp))

                val w = moves[i]
                MovePill(text = w.san, active = hm == i)
                Spacer(Modifier.width(8.dp))

                val b = moves.getOrNull(i + 1)
                if (b != null) {
                    MovePill(text = b.san, active = hm == i + 1)
                    Spacer(Modifier.width(14.dp))
                } else {
                    Spacer(Modifier.width(14.dp))
                }

                num += 1
                i += 2
            }
        }

        IconButton(onClick = onNext, enabled = currentIndex < report.positions.lastIndex) {
            Icon(Icons.Filled.KeyboardArrowRight, contentDescription = "Вперёд")
        }
    }
}

@Composable
private fun MovePill(text: String, active: Boolean) {
    val bg = if (active) Color(0xFF3A3936) else Color(0xFF2B2A27)
    val fg = if (active) Color.White else Color(0xFFCFCFCF)
    Box(
        modifier = Modifier
            .background(bg, shape = MaterialTheme.shapes.small)
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        Text(text, color = fg)
    }
}

@Composable
private fun EvalBar(cp: Int, modifier: Modifier = Modifier) {
    // cp — центопешки в диапазоне [-800..800]
    val clamp = max(min(cp, 800), -800)
    val ratio = (clamp + 800) / 1600f // 0..1 (1 = лучше у белых)
    Box(
        modifier
            .background(Color(0xFF2B2A27))
            .padding(vertical = 6.dp)
    ) {
        // белая часть снизу на долю ratio (без нулевого веса!)
        Box(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .fillMaxHeight(fraction = ratio.coerceIn(0.001f, 0.999f))
                .background(Color.White)
        )
        // разделительная линия середины
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .fillMaxWidth()
                .height(2.dp)
                .background(Color.DarkGray)
        )
    }
}
