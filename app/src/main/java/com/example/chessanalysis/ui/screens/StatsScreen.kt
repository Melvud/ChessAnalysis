package com.example.chessanalysis.ui.screens

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.chessanalysis.data.repo.StatsRepository
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberBottomAxis
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberStartAxis
import com.patrykandpatrick.vico.compose.cartesian.chart.rememberCartesianChart
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.marker.rememberDefaultCartesianMarker
import com.patrykandpatrick.vico.core.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.core.cartesian.data.MutableCartesianChartModel
import com.patrykandpatrick.vico.core.cartesian.data.lineSeries
import kotlinx.coroutines.flow.map

/**
 * Экран статистики.
 * Исправления:
 *  - Импорт Divider из material3 (без .helper).
 *  - Переписаны графики на актуальный API Vico 2.1.x:
 *      CartesianChartModelProducer + rememberLineCartesianLayer + rememberBottomAxis/rememberStartAxis.
 *  - Безопасные вызовы при totals == null.
 */
@Composable
fun StatsScreen(
    repository: StatsRepository,
    onBack: () -> Unit,
) {
    val scroll = rememberScrollState()

    // totalsFlow -> сводная статистика (nullable)
    val totalsFlow = remember(repository) { repository.observeTotals() }
    val totals by totalsFlow.collectAsState(initial = null)

    // Последние N партий для графиков
    val lastGamesFlow = remember(repository) { repository.observeLastN(10) }
    val lastGames by lastGamesFlow.collectAsState(initial = emptyList())

    // продюсер модели для Vico
    val perfProducer = remember { CartesianChartModelProducer() }
    val accProducer = remember { CartesianChartModelProducer() }

    // прогоняем данные в продюсеры
    LaunchedEffect(lastGames) {
        perfProducer.runTransaction {
            buildPerformance(this, lastGames.map { it.performance.toFloat() })
        }
        accProducer.runTransaction {
            buildAccuracy(this, lastGames.map { it.accuracy.toFloat() })
        }
    }

    val enough = lastGames.size >= 10

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "Статистика",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold
        )

        Crossfade(targetState = enough, label = "gate") { ok ->
            if (!ok) {
                GateOverlay(
                    text = "Нужно проанализировать хотя бы 10 партий,\nчтобы открыть статистику."
                )
            } else {
                SummaryCards(
                    accuracy = totals?.avgAccuracy ?: 0.0,
                    performance = totals?.avgPerformance ?: 0.0,
                    bestRate = totals?.bestMoveRate ?: 0.0,
                    brilliantCount = totals?.brilliantCount ?: 0,
                    greatRate = totals?.greatMoveRate ?: 0.0,
                    oppAvgRating = totals?.opponentsAvgRating ?: 0
                )

                ChartCard(
                    title = "Перформанс (последние 10)",
                    producer = perfProducer
                )

                ChartCard(
                    title = "Точность (последние 10)",
                    producer = accProducer
                )
            }
        }
    }
}

private fun MutableCartesianChartModel.buildPerformance(
    scope: MutableCartesianChartModel,
    values: List<Float>
) {
    lineSeries {
        series(values)
    }
}

private fun MutableCartesianChartModel.buildAccuracy(
    scope: MutableCartesianChartModel,
    values: List<Float>
) {
    lineSeries {
        series(values)
    }
}

@Composable
private fun SummaryCards(
    accuracy: Double,
    performance: Double,
    bestRate: Double,
    brilliantCount: Int,
    greatRate: Double,
    oppAvgRating: Int
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            RowStat("Средняя точность", "${accuracy.toInt()}%")
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            RowStat("Средний перформанс", performance.toInt().toString())
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            RowStat("Сильнейшие ходы", "${bestRate.toInt()}%")
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            RowStat("Замечательные ходы", "${greatRate.toInt()}%")
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            RowStat("Блестящих ходов", brilliantCount.toString())
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            RowStat("Средний рейтинг соперников", oppAvgRating.toString())
        }
    }
}

@Composable
private fun RowStat(label: String, value: String) {
    Column(Modifier.fillMaxWidth()) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        Text(text = value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun ChartCard(
    title: String,
    producer: CartesianChartModelProducer
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.fillMaxWidth()) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(16.dp)
            )
            val chart = rememberCartesianChart(
                rememberLineCartesianLayer(),
                startAxis = rememberStartAxis(),
                bottomAxis = rememberBottomAxis()
            )
            val marker = rememberDefaultCartesianMarker()

            Box(Modifier.fillMaxWidth().height(220.dp)) {
                CartesianChartHost(
                    chart = chart,
                    modelProducer = producer,
                    marker = marker,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    runInitialAnimation = true
                )
            }
        }
    }
}

@Composable
private fun GateOverlay(text: String) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(PaddingValues(20.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
