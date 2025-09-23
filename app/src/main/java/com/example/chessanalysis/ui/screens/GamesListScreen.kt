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
import kotlinx.coroutines.delay
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

    // Подгрузка списка партий при входе на экран
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

    // Автоинкремент прогресса, пока showAnalysis = true.
    // Держим прогресс в коридоре [0.05 .. 0.92], чтобы оставлять «пространство» для завершения (1.0).
    LaunchedEffect(showAnalysis) {
        if (showAnalysis) {
            // “раскачка”, чтобы избежать залипания на 0
            if (progress < 0.05f) progress = 0.05f
            var tick = 0
            while (showAnalysis) {
                delay(120)
                tick++

                // Плавный рост; чуть замедляем по мере приближения к верхней границе
                val cap = 0.92f
                val step = when {
                    progress < 0.25f -> 0.015f
                    progress < 0.5f  -> 0.010f
                    progress < 0.75f -> 0.006f
                    else             -> 0.003f
                }
                progress = (progress + step).coerceAtMost(cap)

                // Подсказки статуса для UX
                progressText = when {
                    progress < 0.15f -> "Подготовка…"
                    progress < 0.35f -> "Анализ позиций…"
                    progress < 0.6f  -> "Классификация ходов…"
                    progress < 0.8f  -> "Расчёт точности и ACPL…"
                    else             -> "Формирование отчёта…"
                }
            }
        }
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
                Box(
                    Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }
            items.isEmpty() -> {
                Box(
                    Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center
                ) {
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
                                        progress = 0.0f
                                        progressText = "Подготовка…"

                                        try {
                                            // Небольшой стартовый апдейт,
                                            // дальше LaunchedEffect(showAnalysis) начнёт плавный автораст
                                            progress = 0.08f
                                            progressText = "Анализ позиций…"

                                            // Полный анализ партии -> формирование отчёта
                                            // ВАЖНО: передаём именно текущий заголовок g
                                            val report = buildReportFromPgn(
                                                header = g,
                                                openingFens = openingFens,
                                                depth = 14,
                                                throttleMs = 40L
                                            )

                                            // Завершение: добиваем до 100% и закрываем оверлей
                                            progressText = "Готово"
                                            progress = 1f
                                            delay(120) // короткая задержка, чтобы пользователь увидел 100%
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
                                    LinearProgressIndicator(
                                        progress = { progress },
                                        modifier = Modifier.fillMaxWidth()
                                    )
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
