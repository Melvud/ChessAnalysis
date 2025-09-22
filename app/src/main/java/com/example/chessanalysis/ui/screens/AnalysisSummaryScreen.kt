package com.example.chessanalysis.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.chessanalysis.data.api.ApiClient
import com.example.chessanalysis.data.model.AnalysisResult
import com.example.chessanalysis.data.model.GameSummary
import com.example.chessanalysis.data.model.MoveClass
import com.example.chessanalysis.data.repository.GameRepository
import com.example.chessanalysis.viewmodel.AnalysisViewModel

@Composable
fun AnalysisSummaryScreen(
    game: GameSummary,
    onViewReport: (AnalysisResult) -> Unit,
    onBack: () -> Unit
) {
    val viewModel: AnalysisViewModel = viewModel(factory = viewModelFactory {
        initializer {
            AnalysisViewModel(
                GameRepository(
                    ApiClient.lichessService,
                    ApiClient.chessComService,
                    ApiClient.stockfishOnlineService,
                    ApiClient.chessApiService
                )
            )
        }
    })

    val result by viewModel.analysisResult.collectAsState()
    val loading by viewModel.loading.collectAsState()
    val error by viewModel.error.collectAsState()

    // Start analysis when screen appears
    LaunchedEffect(game.id) {
        viewModel.analyze(game)
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Сводка анализа") }) }
    ) { paddingValues ->
        Box(Modifier.padding(paddingValues).fillMaxSize()) {
            when {
                loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                error != null -> Text("Ошибка: $error", Modifier.align(Alignment.Center))
                result == null -> Text("Выполняется анализ…", Modifier.align(Alignment.Center))
                else -> SummaryContent(game, result!!, onViewReport, onBack)
            }
        }
    }
}

@Composable
private fun SummaryContent(
    game: GameSummary,
    result: AnalysisResult,
    onViewReport: (AnalysisResult) -> Unit,
    onBack: () -> Unit
) {
    // Extract summary and move list from analysis result
    val summary = result.summary
    val moves = result.moves

    // Accuracy and performance from summary
    val accWhite = summary.accuracyWhite
    val accBlack = summary.accuracyBlack
    val perfWhite = summary.whitePerfVs
    val perfBlack = summary.blackPerfVs

    // Counts of move classifications for each side
    val countsWhite: Map<MoveClass, Int> = moves.filter { it.ply % 2 == 1 }.groupingBy { it.moveClass }.eachCount()
    val countsBlack: Map<MoveClass, Int> = moves.filter { it.ply % 2 == 0 }.groupingBy { it.moveClass }.eachCount()

    Column(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Match title style to Chess.com: players names in bold
        Text("${game.white} — ${game.black}", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)

        // Side-by-side summary cards for White and Black
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SideSummaryCard(
                modifier = Modifier.weight(1f),
                name = game.white,
                accuracy = accWhite,
                performance = perfWhite,
                counts = countsWhite
            )
            SideSummaryCard(
                modifier = Modifier.weight(1f),
                name = game.black,
                accuracy = accBlack,
                performance = perfBlack,
                counts = countsBlack
            )
        }

        Button(
            onClick = { onViewReport(result) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Смотреть детальный отчёт")
        }
        OutlinedButton(
            onClick = onBack,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Назад")
        }
    }
}

@Composable
private fun SideSummaryCard(
    modifier: Modifier = Modifier,
    name: String,
    accuracy: Double,
    performance: Int?,
    counts: Map<MoveClass, Int>
) {
    ElevatedCard(modifier) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
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
            // List move counts by category for this side
            Text("Best / Excellent: ${counts[MoveClass.GREAT] ?: 0}")
            Text("Good: ${counts[MoveClass.GOOD] ?: 0}")
            Text("Inaccuracy: ${counts[MoveClass.INACCURACY] ?: 0}")
            Text("Mistake: ${counts[MoveClass.MISTAKE] ?: 0}")
            Text("Blunder: ${counts[MoveClass.BLUNDER] ?: 0}")
        }
    }
}
