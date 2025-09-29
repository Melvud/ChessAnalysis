package com.example.chessanalysis.ui.screens

import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.chessanalysis.*
import com.example.chessanalysis.EngineClient.analyzeGameByPgnWithProgress
import com.example.chessanalysis.data.local.gameRepository
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlin.math.max

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GamesListScreen(
    profile: com.example.chessanalysis.ui.UserProfile,
    games: List<GameHeader>,
    openingFens: Set<String>,
    onOpenProfile: () -> Unit,
    onOpenReport: (FullReport) -> Unit
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val json = remember { Json { ignoreUnknownKeys = true; explicitNulls = false } }
    val repo = remember { context.gameRepository(json) }

    var items by remember { mutableStateOf<List<GameHeader>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }

    var showAnalysis by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
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

    suspend fun loadFromLocal() {
        items = repo.getAllHeaders()
    }

    /** Только добавляем новые из сети в локальное хранилище. */
    suspend fun syncWithRemote() {
        try {
            val lichessList = if (profile.lichessUsername.isNotEmpty()) GameLoaders.loadLichess(profile.lichessUsername) else emptyList()
            val chessList = if (profile.chessUsername.isNotEmpty()) GameLoaders.loadChessCom(profile.chessUsername) else emptyList()

            // Мердж «только добавление»
            repo.mergeExternal(Provider.LICHESS, lichessList)
            repo.mergeExternal(Provider.CHESSCOM, chessList)
        } catch (_: Throwable) { /* молча — оффлайн/ошибка сети */ }
    }

    LaunchedEffect(profile) {
        isLoading = true
        // 1) показываем всё, что уже сохранено локально
        loadFromLocal()
        // 2) синкаем с сетью (добавляем новые), затем перечитываем локалку
        syncWithRemote()
        loadFromLocal()
        isLoading = false
    }

    val pullState = rememberPullToRefreshState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Список партий") },
                actions = {
                    IconButton(onClick = onOpenProfile) {
                        Icon(Icons.Default.Person, contentDescription = "Профиль")
                    }
                }
            )
        }
    ) { padding ->
        PullToRefreshBox(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            isRefreshing = isLoading,
            onRefresh = {
                scope.launch {
                    isLoading = true
                    // при обновлении — та же логика
                    loadFromLocal()
                    syncWithRemote()
                    loadFromLocal()
                    isLoading = false
                }
            },
            state = pullState
        ) {
            Box(Modifier.fillMaxSize()) {
                when {
                    isLoading && items.isEmpty() -> {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }
                    items.isEmpty() -> {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("Партий не найдено")
                        }
                    }
                    else -> {
                        LazyColumn(Modifier.fillMaxSize()) {
                            items(items) { game ->
                                val userWon = (game.sideToView == true && game.result == "1-0") ||
                                        (game.sideToView == false && game.result == "0-1")
                                val userLost = (game.sideToView == true && game.result == "0-1") ||
                                        (game.sideToView == false && game.result == "1-0")
                                val cardColor = when {
                                    userWon -> Color(0xFFDFF0D8)
                                    userLost -> Color(0xFFF2DEDE)
                                    else -> MaterialTheme.colorScheme.surface
                                }

                                Card(
                                    colors = CardDefaults.cardColors(containerColor = cardColor),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 6.dp)
                                        .clickable(enabled = !showAnalysis) {
                                            if (showAnalysis) return@clickable
                                            scope.launch {
                                                try {
                                                    // Берём полный PGN: если его нет в локалке у external — дозапишем.
                                                    val fullPgn = GameLoaders.ensureFullPgn(game).ifBlank { game.pgn.orEmpty() }
                                                    if (fullPgn.isBlank()) {
                                                        Toast.makeText(context, "PGN не найден", Toast.LENGTH_SHORT).show()
                                                        return@launch
                                                    }

                                                    // Если это внешняя партия (не BOT) — сохраняем полный PGN в локалку,
                                                    // чтобы в будущем был мгновенный доступ и стабильный ключ к кешу.
                                                    if (game.site == Provider.LICHESS || game.site == Provider.CHESSCOM) {
                                                        repo.updateExternalPgn(game.site, game, fullPgn)
                                                    }

                                                    // 1) Пытаемся взять кэш отчёта
                                                    val cached = repo.getCachedReport(fullPgn)
                                                    if (cached != null) {
                                                        onOpenReport(cached)
                                                        return@launch
                                                    }

                                                    // 2) Иначе — анализ + кэширование
                                                    showAnalysis = true
                                                    stage = "Ожидание сервера…"
                                                    progress = 0.05f
                                                    done = 0
                                                    total = 0
                                                    etaMs = null

                                                    val header = runCatching { PgnChess.headerFromPgn(fullPgn) }.getOrNull()
                                                    val report = analyzeGameByPgnWithProgress(
                                                        pgn = fullPgn,
                                                        depth = 15,
                                                        multiPv = 3,
                                                        header = header
                                                    ) { snap ->
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
                                                        if (snap.total > 0) {
                                                            progress = (snap.done.toFloat() / snap.total.toFloat()).coerceIn(0f, 1f)
                                                        }
                                                        etaMs = snap.etaMs
                                                    }

                                                    // Сохраняем отчёт в кэш
                                                    repo.saveReport(fullPgn, report)
                                                    showAnalysis = false
                                                    onOpenReport(report)
                                                } catch (t: Throwable) {
                                                    showAnalysis = false
                                                    Toast.makeText(context, "Ошибка анализа: ${t.message}", Toast.LENGTH_LONG).show()
                                                }
                                            }
                                        }
                                ) {
                                    Column(Modifier.padding(12.dp)) {
                                        val siteName = when (game.site) {
                                            Provider.LICHESS -> "Lichess"
                                            Provider.CHESSCOM -> "Chess.com"
                                            Provider.BOT -> "Bot"          // если у тебя есть локальные партии с ботом
                                            null -> ""                     // ← добавь это
                                        }

                                        Text(siteName, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)

                                        Spacer(Modifier.height(8.dp))

                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                InitialAvatar(name = game.white ?: "W", size = 28.dp)
                                                Spacer(Modifier.width(8.dp))
                                                Text(game.white ?: "White", style = MaterialTheme.typography.bodyMedium)
                                            }
                                            Text(game.result.orEmpty(), style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text(game.black ?: "Black", style = MaterialTheme.typography.bodyMedium)
                                                Spacer(Modifier.width(8.dp))
                                                InitialAvatar(name = game.black ?: "B", size = 28.dp)
                                            }
                                        }

                                        val openingName = game.opening ?: game.eco ?: ""
                                        if (openingName.isNotEmpty()) {
                                            Spacer(Modifier.height(6.dp))
                                            Text(openingName, style = MaterialTheme.typography.bodySmall)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                if (showAnalysis) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Surface(tonalElevation = 6.dp, shape = MaterialTheme.shapes.medium) {
                            Column(
                                modifier = Modifier.widthIn(min = 300.dp).padding(20.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text("Анализ партии", style = MaterialTheme.typography.titleMedium)
                                Spacer(Modifier.height(12.dp))
                                LinearProgressIndicator(
                                    progress = progress.coerceIn(0f, 1f),
                                    modifier = Modifier.fillMaxWidth().height(8.dp),
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Spacer(Modifier.height(8.dp))
                                Text(buildString {
                                    append(stage)
                                    if (total > 0) append("  •  $done/$total позиций")
                                    append("  •  ETA: ${formatEta(etaMs)}")
                                })
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InitialAvatar(
    name: String,
    size: Dp,
    bg: Color = Color(0xFF6D5E4A),
    fg: Color = Color(0xFFF5F3EF)
) {
    val initial = name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
    Box(
        modifier = Modifier.size(size).background(bg, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Text(initial, color = fg, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
    }
}
