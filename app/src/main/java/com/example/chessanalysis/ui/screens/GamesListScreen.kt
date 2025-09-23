package com.example.chessanalysis.ui.screens

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.chessanalysis.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GamesListScreen(
    provider: Provider,
    username: String,
    games: List<GameHeader>,
    openingFens: Set<String>,
    onBack: () -> Unit,
    onOpenReport: (FullReport) -> Unit
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var items by remember { mutableStateOf(games) }
    var isLoading by remember { mutableStateOf(false) }

    // прогресс анализа
    var showAnalysis by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }      // 0f..1f
    var progressText by remember { mutableStateOf("Анализ...") }

    LaunchedEffect(provider, username) {
        if (items.isNotEmpty()) return@LaunchedEffect
        isLoading = true
        items = try {
            when (provider) {
                Provider.LICHESS -> GameLoaders.loadLichess(username)
                Provider.CHESSCOM -> GameLoaders.loadChessCom(username)
            }
        } catch (_: Throwable) {
            emptyList()
        }
        isLoading = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (provider == Provider.LICHESS) "Lichess: $username" else "Chess.com: $username") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Назад")
                    }
                }
            )
        }
    ) { padding ->
        when {
            isLoading -> {
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            items.isEmpty() -> {
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    Text("Партий не найдено")
                }
            }
            else -> {
                Box(Modifier.fillMaxSize().padding(padding)) {
                    LazyColumn(Modifier.fillMaxSize()) {
                        itemsIndexed(items) { _, g ->
                            ListItem(
                                headlineContent = {
                                    Text(g.opening ?: (g.eco ?: "—"), fontWeight = FontWeight.SemiBold)
                                },
                                overlineContent = {
                                    Text("${g.white ?: "White"} vs ${g.black ?: "Black"} • ${g.result ?: ""}")
                                },
                                supportingContent = { Text(g.date ?: "") },
                                modifier = Modifier.clickable {
                                    scope.launch {
                                        val pgn = g.pgn
                                        if (pgn.isNullOrBlank()) {
                                            Toast.makeText(context, "PGN не найден", Toast.LENGTH_SHORT).show()
                                            return@launch
                                        }

                                        // Показать оверлей прогресса
                                        showAnalysis = true
                                        progress = 0f
                                        progressText = "Подготовка…"

                                        try {
                                            // Используем новую логику из AnalysisLogic:
                                            // один вызов собирает все позиции, метрики, классификацию и лог.
                                            progress = 0.05f
                                            progressText = "Анализ позиций…"
                                            val report = reportFromPgn(header = g, openingFens = openingFens)

                                            // Финализация
                                            progress = 1f
                                            progressText = "Готово"
                                            showAnalysis = false
                                            onOpenReport(report)
                                        } catch (t: Throwable) {
                                            showAnalysis = false
                                            Toast.makeText(
                                                context,
                                                "Ошибка анализа: ${t.message ?: "неизвестно"}",
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                    }
                                }
                            )
                            Divider()
                        }
                    }

                    if (showAnalysis) {
                        // Оверлей прогресса поверх списка
                        Box(
                            Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Surface(
                                tonalElevation = 6.dp,
                                shape = MaterialTheme.shapes.medium
                            ) {
                                Column(
                                    Modifier
                                        .widthIn(min = 280.dp)
                                        .padding(20.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text("Анализ партии", style = MaterialTheme.typography.titleMedium)
                                    Spacer(Modifier.height(12.dp))
                                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                                    Spacer(Modifier.height(8.dp))
                                    Text(progressText)
                                    Spacer(Modifier.height(6.dp))
                                    Text("${(progress * 100).toInt()}%")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
