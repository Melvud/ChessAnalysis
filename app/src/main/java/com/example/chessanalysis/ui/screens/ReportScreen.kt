package com.example.chessanalysis.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.chessanalysis.AccByColor
import com.example.chessanalysis.FullReport
import com.example.chessanalysis.MoveClass
import com.example.chessanalysis.R
import kotlin.math.max
import kotlin.math.min

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportScreen(
    report: FullReport,
    onBack: () -> Unit,
    onOpenBoard: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Отчёт о партии") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null) } },
                actions = {
                    IconButton(onClick = { /* search */ }) { Icon(Icons.Default.Search, null) }
                    IconButton(onClick = { /* settings */ }) { Icon(Icons.Default.Settings, null) }
                }
            )
        },
        bottomBar = {
            // Фиксированная кнопка внизу экрана — всегда видна
            Surface(tonalElevation = 3.dp) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(12.dp)
                ) {
                    Button(
                        onClick = onOpenBoard,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Смотреть отчёт")
                    }
                }
            }
        }
    ) { padding ->
        val scroll = rememberScrollState()
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                .padding(padding)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            EvalSparkline(report)

            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF2B2A27), RoundedCornerShape(12.dp))
                    .padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                PlayerColumn(
                    name = report.header.white ?: "White",
                    acc = report.accuracy.whiteMovesAcc,
                    acpl = report.acpl.white,
                    inverted = false
                )
                Spacer(Modifier.width(16.dp))
                PlayerColumn(
                    name = report.header.black ?: "Black",
                    acc = report.accuracy.blackMovesAcc,
                    acpl = report.acpl.black,
                    inverted = true
                )
            }

            ClassificationTable(report)

            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF2B2A27)),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    StatBox(value = report.estimatedElo.whiteEst?.toString() ?: "—")
                    StatBox(value = report.estimatedElo.blackEst?.toString() ?: "—", dark = true)
                }
            }

            // Дополнительный нижний отступ, чтобы контент не прятался под bottomBar
            Spacer(Modifier.height(80.dp))
        }
    }
}

@Composable
private fun PlayerColumn(name: String, acc: AccByColor, acpl: Int, inverted: Boolean) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Image(
            painter = painterResource(R.drawable.opening),
            contentDescription = null,
            modifier = Modifier.size(48.dp)
        )
        Spacer(Modifier.height(8.dp))
        Text(name, color = Color.LightGray)
        Spacer(Modifier.height(8.dp))

        // Основное большое число — взвешенная точность
        StatBox(
            value = String.format("%.1f%%", acc.weighted),
            dark = inverted
        )

        // Подпись мелким шрифтом: гармоническая + ACPL
        Spacer(Modifier.height(6.dp))
        Text(
            text = String.format("harm=%.1f%%  ACPL=%d", acc.harmonic, acpl),
            color = Color(0xFFBDBDBD),
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun StatBox(value: String, dark: Boolean = false) {
    val bg = if (dark) Color(0xFF3A3936) else Color(0xFFEDEDED)
    val fg = if (dark) Color.White else Color(0xFF2B2A27)
    Box(
        modifier = Modifier
            .width(110.dp)
            .height(56.dp)
            .background(bg, RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            value,
            color = fg,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun ClassificationTable(report: FullReport) {
    val rows = listOf(
        Triple("Блестящий ход", R.drawable.splendid, MoveClass.SPLENDID),
        Triple("Замечательный", R.drawable.perfect, MoveClass.PERFECT),
        Triple("Лучшие", R.drawable.best, MoveClass.BEST),
        Triple("Отлично", R.drawable.excellent, MoveClass.EXCELLENT),
        Triple("Хорошо", R.drawable.okay, MoveClass.OKAY),
        Triple("Теоретический ход", R.drawable.opening, MoveClass.OPENING),
        Triple("Неточность", R.drawable.inaccuracy, MoveClass.INACCURACY),
        Triple("Ошибка", R.drawable.mistake, MoveClass.MISTAKE),
        Triple("Упущенная возмож...", R.drawable.forced, MoveClass.FORCED),
        Triple("Зевок", R.drawable.blunder, MoveClass.BLUNDER)
    )

    val w = mutableMapOf<MoveClass, Int>().withDefault { 0 }
    val b = mutableMapOf<MoveClass, Int>().withDefault { 0 }
    report.moves.forEachIndexed { i, m ->
        val map = if (i % 2 == 0) w else b
        map[m.classification] = map.getValue(m.classification) + 1
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF2B2A27)),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Шахматисты", color = Color.LightGray)
                Row {
                    Text(" ", modifier = Modifier.width(36.dp))
                    Spacer(Modifier.width(24.dp))
                    Text(" ", modifier = Modifier.width(36.dp))
                }
            }
            Divider(color = Color(0xFF3A3936))

            rows.forEach { (label, icon, cls) ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Image(painterResource(icon), null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(label, color = Color.White)
                    }
                    Text(
                        w.getValue(cls).toString(),
                        color = Color(0xFF59C156),
                        textAlign = TextAlign.End,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.width(36.dp)
                    )
                    Spacer(Modifier.width(24.dp))
                    Text(
                        b.getValue(cls).toString(),
                        color = Color(0xFF59C156),
                        textAlign = TextAlign.End,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.width(36.dp)
                    )
                }
                Divider(color = Color(0xFF3A3936))
            }
        }
    }
}

@Composable
private fun EvalSparkline(report: FullReport) {
    val values = report.positions.mapNotNull { it.lines.firstOrNull()?.cp?.toFloat() }
    if (values.isEmpty()) return
    val minCp = -800f
    val maxCp = 800f
    val norm = values.map { v -> max(min(v, maxCp), minCp) }

    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF2B2A27)),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .height(84.dp)
    ) {
        Canvas(Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 16.dp)) {
            val w = size.width
            val h = size.height
            val path = Path()
            val stepX = if (norm.size <= 1) 0f else w / (norm.size - 1)
            norm.forEachIndexed { i, v ->
                val y = h * (1f - (v - minCp) / (maxCp - minCp))
                val x = i * stepX
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, color = Color(0xFFBDBDBD))
        }
    }
}
