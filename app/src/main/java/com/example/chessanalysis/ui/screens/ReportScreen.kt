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
    onOpenBoard: (() -> Unit)? = null
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
            Surface(tonalElevation = 3.dp) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(12.dp)
                ) {
                    Button(
                        onClick = { onOpenBoard?.invoke() ?: onBack() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(if (onOpenBoard == null) "Вернуться" else "Смотреть отчёт")
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
            // График оценок
            EvalSparkline(report)

            // Карточка с точностью игроков
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

            // Таблица классификаций ходов
            ClassificationTable(report)

            // Карточка с оценкой перформанса (Elo)
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF2B2A27)),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(12.dp)
                ) {
                    Text(
                        "Оценка перформанса",
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                report.header.white ?: "White",
                                color = Color.LightGray,
                                style = MaterialTheme.typography.bodySmall
                            )
                            Spacer(Modifier.height(4.dp))
                            StatBox(
                                value = report.estimatedElo.whiteEst?.toString() ?: "—",
                                dark = false
                            )
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                report.header.black ?: "Black",
                                color = Color.LightGray,
                                style = MaterialTheme.typography.bodySmall
                            )
                            Spacer(Modifier.height(4.dp))
                            StatBox(
                                value = report.estimatedElo.blackEst?.toString() ?: "—",
                                dark = true
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(80.dp))
        }
    }
}

@Composable
private fun PlayerColumn(name: String, acc: AccByColor, acpl: Int, inverted: Boolean) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        // Аватар игрока
        Image(
            painter = painterResource(R.drawable.opening),
            contentDescription = null,
            modifier = Modifier.size(48.dp)
        )
        Spacer(Modifier.height(8.dp))

        // Имя игрока
        Text(name, color = Color.LightGray)
        Spacer(Modifier.height(8.dp))

        // Главная точность - используем готовое значение itera с сервера
        StatBox(
            value = String.format("%.1f%%", acc.itera),
            dark = inverted
        )

        Spacer(Modifier.height(6.dp))

        // Дополнительная информация
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
        Triple("Блестящий ход!!", R.drawable.splendid, MoveClass.SPLENDID),
        Triple("Замечательный!", R.drawable.perfect, MoveClass.PERFECT),
        Triple("Лучший", R.drawable.best, MoveClass.BEST),
        Triple("Отличный", R.drawable.excellent, MoveClass.EXCELLENT),
        Triple("Хороший", R.drawable.okay, MoveClass.OKAY),
        Triple("Теория", R.drawable.opening, MoveClass.OPENING),
        Triple("Неточность", R.drawable.inaccuracy, MoveClass.INACCURACY),
        Triple("Ошибка", R.drawable.mistake, MoveClass.MISTAKE),
        Triple("Единственный", R.drawable.forced, MoveClass.FORCED),
        Triple("Зевок", R.drawable.blunder, MoveClass.BLUNDER)
    )

    // Подсчет ходов по классификациям для каждого цвета
    val whiteMovesMap = mutableMapOf<MoveClass, Int>().withDefault { 0 }
    val blackMovesMap = mutableMapOf<MoveClass, Int>().withDefault { 0 }

    report.moves.forEachIndexed { index, move ->
        val targetMap = if (index % 2 == 0) whiteMovesMap else blackMovesMap
        targetMap[move.classification] = targetMap.getValue(move.classification) + 1
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF2B2A27)),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.fillMaxWidth()) {
            // Заголовок таблицы
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

            // Строки таблицы
            rows.forEach { (label, icon, moveClass) ->
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
                        whiteMovesMap.getValue(moveClass).toString(),
                        color = Color(0xFF59C156),
                        textAlign = TextAlign.End,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.width(36.dp)
                    )
                    Spacer(Modifier.width(24.dp))
                    Text(
                        blackMovesMap.getValue(moveClass).toString(),
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
    // 1) вытаскиваем числовые оценки
    val raw = report.positions.mapNotNull { pos ->
        val l = pos.lines.firstOrNull()
        when {
            l?.cp != null -> l.cp.toFloat()
            l?.mate != null -> if (l.mate!! > 0) 3000f else -3000f
            else -> null
        }
    }
    if (raw.isEmpty()) return

    // 2) ограничиваем пики и слегка сглаживаем, чтобы линия была плавной
    val cap = 800f
    val clamped = raw.map { it.coerceIn(-cap, cap) }
    val smooth = if (clamped.size < 4) clamped else buildList {
        val w = floatArrayOf(0.2f, 0.6f, 0.2f)
        add(clamped.first())
        for (i in 1 until clamped.lastIndex) {
            add(clamped[i - 1] * w[0] + clamped[i] * w[1] + clamped[i + 1] * w[2])
        }
        add(clamped.last())
    }

    // 3) рисуем
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF2B2A27)),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .height(120.dp)
    ) {
        Canvas(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 10.dp, vertical = 12.dp)
        ) {
            val w = size.width
            val h = size.height
            val midY = h * 0.5f

            // ось 0.0 по центру (как на примере)
            drawLine(
                color = Color(0xFF5E5E5E),
                start = androidx.compose.ui.geometry.Offset(0f, midY),
                end = androidx.compose.ui.geometry.Offset(w, midY),
                strokeWidth = 1f
            )

            // преобразуем cp -> [0..1] -> y
            fun to01(cp: Float) = (cp + cap) / (2f * cap)

            val stepX = if (smooth.size <= 1) 0f else w / (smooth.size - 1)
            val pts = smooth.mapIndexed { i, v ->
                val x = i * stepX
                val y = h * (1f - to01(v))
                androidx.compose.ui.geometry.Offset(x, y)
            }
            if (pts.isEmpty()) return@Canvas

            // вспомогательная: строим одновременно ПУТЬ ЛИНИИ и ПУТЬ ЗАЛИВКИ ДО НИЗА
            val strokeWidth = 1.7.dp.toPx()
            val linePath = Path()
            val fillPath = Path()

            // fill: снизу-влево -> по кривой -> снизу-вправо -> закрыть
            fillPath.moveTo(0f, h)
            fillPath.lineTo(pts.first().x, pts.first().y)

            // Catmull–Rom -> кубические Безье (плавность как у вторй картинки)
            linePath.moveTo(pts.first().x, pts.first().y)
            for (i in 1 until pts.size) {
                val p0 = pts[(i - 1).coerceAtLeast(0)]
                val p1 = pts[i]
                val p_1 = pts[(i - 2).coerceAtLeast(0)]
                val p2 = pts[(i + 1).coerceAtMost(pts.lastIndex)]
                val t = 0.2f
                val c1x = p0.x + (p1.x - p_1.x) * t
                val c1y = p0.y + (p1.y - p_1.y) * t
                val c2x = p1.x - (p2.x - p0.x) * t
                val c2y = p1.y - (p2.y - p0.y) * t

                linePath.cubicTo(c1x, c1y, c2x, c2y, p1.x, p1.y)
                fillPath.cubicTo(c1x, c1y, c2x, c2y, p1.x, p1.y)
            }

            // замыкаем заливку к низу
            fillPath.lineTo(pts.last().x, h)
            fillPath.close()

            // Белая область — ВСЕГДА от линии до низа (точно как на второй картинке)
            drawPath(fillPath, color = Color.White)

            // Тонкая обводка самой кривой поверх (делает границу читаемой на тёмном фоне)
            drawPath(
                path = linePath,
                color = Color(0xFFBDBDBD),
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = strokeWidth)
            )
        }
    }
}
