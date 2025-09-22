@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.example.chessanalysis.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.chessanalysis.data.model.*
import com.example.chessanalysis.viewmodel.AnalysisViewModel
import com.example.chessanalysis.data.api.ApiClient
import com.example.chessanalysis.data.repository.GameRepository
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun AnalysisSummaryScreen(
    game: GameSummary,
    onViewReport: (AnalysisResult) -> Unit,
    onBack: () -> Unit
) {
    val vm: AnalysisViewModel = viewModel(factory = viewModelFactory {
        initializer {
            AnalysisViewModel(
                GameRepository(
                    ApiClient.lichessService,
                    ApiClient.chessComService,
                    ApiClient.stockfishOnlineService
                )
            )
        }
    })

    val result by vm.analysisResult.collectAsState()
    val loading by vm.loading.collectAsState()
    val error by vm.error.collectAsState()

    LaunchedEffect(game.id) { vm.analyze(game) }

    Scaffold(topBar = { TopAppBar(title = { Text("Сводка анализа") }) }) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                error != null -> Text("Ошибка: $error", Modifier.align(Alignment.Center))
                result == null -> Text("Выполняется анализ…", Modifier.align(Alignment.Center))
                else -> SummaryContent(game, result!!, onViewReport, onBack)
            }
        }
    }
}

private enum class Buckets { GREAT, GOOD, INA, MIST, BLUN }
private fun bucket(absDelta: Double): Buckets = when {
    absDelta < 0.05 -> Buckets.GREAT
    absDelta < 0.20 -> Buckets.GOOD
    absDelta < 0.60 -> Buckets.INA
    absDelta < 1.60 -> Buckets.MIST
    else            -> Buckets.BLUN
}

private fun accuracyForSide(moves: List<MoveAnalysis>, isWhite: Boolean): Double {
    val sideMoves = moves.filter { (it.moveNumber % 2 == 1) == isWhite }
    if (sideMoves.isEmpty()) return 0.0
    val acpl = sideMoves.sumOf { abs(it.delta) * 100.0 } / sideMoves.size
    return (100.0 - acpl / 3.0).coerceIn(0.0, 100.0)
}

private fun countsForSide(moves: List<MoveAnalysis>, isWhite: Boolean): Map<Buckets, Int> {
    val map = mutableMapOf(
        Buckets.GREAT to 0, Buckets.GOOD to 0, Buckets.INA to 0,
        Buckets.MIST to 0, Buckets.BLUN to 0
    )
    moves.filter { (it.moveNumber % 2 == 1) == isWhite }.forEach { m ->
        val b = bucket(abs(m.delta))
        map[b] = map.getValue(b) + 1
    }
    return map
}

private fun parseElosFromPgn(pgn: String): Pair<Int?, Int?> {
    fun tagInt(key: String): Int? =
        Regex("\\[$key\\s+\"(\\d+)\"\\]").find(pgn)?.groupValues?.getOrNull(1)?.toIntOrNull()
    return tagInt("WhiteElo") to tagInt("BlackElo")
}

private fun scoreByResult(tag: String?, forWhite: Boolean): Double? = when (tag) {
    "1-0" -> if (forWhite) 1.0 else 0.0
    "0-1" -> if (forWhite) 0.0 else 1.0
    "1/2-1/2" -> 0.5
    else -> null
}

private fun performanceOneGame(opponentElo: Int?, score: Double?): Int? {
    if (opponentElo == null || score == null) return null
    val delta = (400.0 * (score - 0.5)).roundToInt()
    return (opponentElo + delta).coerceIn(500, 3200)
}

@Composable
private fun SummaryContent(
    game: GameSummary,
    result: AnalysisResult,
    onViewReport: (AnalysisResult) -> Unit,
    onBack: () -> Unit
) {
    val (wElo, bElo) = parseElosFromPgn(game.pgn)
    val perfW = performanceOneGame(bElo, scoreByResult(game.result, true))
    val perfB = performanceOneGame(wElo, scoreByResult(game.result, false))

    val accW = accuracyForSide(result.moves, true)
    val accB = accuracyForSide(result.moves, false)
    val cntW = countsForSide(result.moves, true)
    val cntB = countsForSide(result.moves, false)

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("${game.white} — ${game.black}", style = MaterialTheme.typography.headlineSmall)

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SideCard(Modifier.weight(1f), game.white, accW, perfW, cntW)
            SideCard(Modifier.weight(1f), game.black, accB, perfB, cntB)
        }

        Button(onClick = { onViewReport(result) }, modifier = Modifier.fillMaxWidth()) {
            Text("Смотреть детальный отчёт")
        }
        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Назад") }
    }
}

@Composable
private fun SideCard(
    modifier: Modifier,
    name: String,
    accuracy: Double,
    performance: Int?,
    counts: Map<Buckets, Int>
) {
    ElevatedCard(modifier) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("Accuracy", style = MaterialTheme.typography.labelMedium)
                    Text("${"%.1f".format(accuracy)}%", style = MaterialTheme.typography.headlineLarge)
                }
                Column(Modifier.weight(1f)) {
                    Text("Performance", style = MaterialTheme.typography.labelMedium)
                    Text(performance?.toString() ?: "—", style = MaterialTheme.typography.headlineLarge)
                }
            }

            Divider()

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Best / Excellent"); Text((counts[Buckets.GREAT] ?: 0).toString())
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Good"); Text((counts[Buckets.GOOD] ?: 0).toString())
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Inaccuracy"); Text((counts[Buckets.INA] ?: 0).toString())
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Mistake"); Text((counts[Buckets.MIST] ?: 0).toString())
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Blunder"); Text((counts[Buckets.BLUN] ?: 0).toString())
            }
        }
    }
}
