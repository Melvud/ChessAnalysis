package com.example.chessanalysis.ui.screens

import android.util.Log
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
import kotlin.math.max

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

    // --- прогресс анализа ---
    var showAnalysis by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }             // 0..1
    var stage by remember { mutableStateOf("Подготовка…") }
    var done by remember { mutableStateOf(0) }
    var total by remember { mutableStateOf(0) }
    var etaMs by remember { mutableStateOf<Long?>(null) }

    fun formatEta(ms: Long?): String {
        if (ms == null) return "—"
        val sec = max(0, (ms / 1000).toInt())
        val mm = sec / 60
        val ss = sec % 60
        return "%d:%02d".format(mm, ss)
    }

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
                                headlineContent = { Text(g.opening ?: (g.eco ?: "—"), fontWeight = FontWeight.SemiBold) },
                                overlineContent = { Text("${g.white ?: "White"} vs ${g.black ?: "Black"} • ${g.result ?: ""}") },
                                supportingContent = { Text(g.date ?: "") },
                                modifier = Modifier.clickable {
                                    scope.launch {
                                        Log.d("GamesListScreen", "Starting analysis...")
                                        try {
                                            val pgn = g.pgn
                                            if (pgn.isNullOrBlank()) {
                                                Toast.makeText(context, "PGN не найден", Toast.LENGTH_SHORT).show()
                                                return@launch
                                            }

                                            // Запускаем анализ PGN прямо на сервере
                                            showAnalysis = true
                                            stage = "Ожидание сервера…"
                                            progress = 0.05f
                                            done = 0
                                            total = 0
                                            etaMs = null

                                            val header = try {
                                                PgnChess.headerFromPgn(pgn)
                                            } catch (_: Throwable) {
                                                null
                                            }

                                            Log.d("GamesListScreen", "Starting server analysis...")
                                            val report = try {
                                                analyzeGameByPgnWithProgress(
                                                    pgn = pgn,
                                                    depth = 15,
                                                    multiPv = 3,
                                                    header = header
                                                ) { snap ->
                                                    // Апдейты прогресса приходят с сервера
                                                    total = snap.total
                                                    done = snap.done
                                                    stage = when (snap.stage) {
                                                        "queued"      -> "В очереди…"
                                                        "preparing"   -> "Подготовка…"
                                                        "evaluating"  -> "Анализ позиций…"
                                                        "postprocess" -> "Постобработка…"
                                                        "done"        -> "Готово"
                                                        else          -> "Анализ…"
                                                    }
                                                    progress = if (snap.total > 0)
                                                        snap.done.toFloat() / snap.total.toFloat()
                                                    else progress
                                                    etaMs = snap.etaMs

                                                    Log.d("GamesListScreen", "Progress: $done/$total - $stage")
                                                }
                                            } catch (e: Exception) {
                                                Log.e("GamesListScreen", "Analysis error", e)
                                                throw e
                                            }

                                            Log.d("GamesListScreen", "Analysis complete! Report positions: ${report.positions.size}, moves: ${report.moves.size}")
                                            Log.d("GamesListScreen", "Accuracy: white=${report.accuracy.whiteMovesAcc.weighted}, black=${report.accuracy.blackMovesAcc.weighted}")

                                            showAnalysis = false

                                            // Открываем отчёт
                                            Log.d("GamesListScreen", "Opening report screen...")
                                            onOpenReport(report)

                                        } catch (t: Throwable) {
                                            Log.e("GamesListScreen", "Error during analysis", t)
                                            showAnalysis = false
                                            Toast.makeText(
                                                context,
                                                "Ошибка анализа: ${t.message ?: "неизвестно"}",
                                                Toast.LENGTH_LONG
                                            ).show()
                                        }
                                    }
                                }
                            )
                            Divider()
                        }
                    }

                    if (showAnalysis) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Surface(tonalElevation = 6.dp, shape = MaterialTheme.shapes.medium) {
                                Column(
                                    Modifier.widthIn(min = 300.dp).padding(20.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text("Анализ партии", style = MaterialTheme.typography.titleMedium)
                                    Spacer(Modifier.height(12.dp))
                                    LinearProgressIndicator(
                                        progress = { progress.coerceIn(0f, 1f) },
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    Spacer(Modifier.height(8.dp))
                                    Text(
                                        text = buildString {
                                            append(stage)
                                            if (total > 0) {
                                                append("  •  ")
                                                append("$done/$total позиций")
                                            }
                                            append("  •  ETA: ${formatEta(etaMs)}")
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
