package com.example.chessanalysis.ui.screens

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.chessanalysis.EngineClient.analyzeGameByPgnWithProgress
import com.example.chessanalysis.FullReport
import com.example.chessanalysis.GameHeader
import com.example.chessanalysis.PgnChess
import com.example.chessanalysis.Provider
import com.example.chessanalysis.data.local.gameRepository
import com.example.chessanalysis.ui.UserProfile
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlin.math.max

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GamesListScreen(
    profile: UserProfile,
    games: List<GameHeader>,
    openingFens: Set<String>,
    onOpenReport: (FullReport) -> Unit
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val json = remember { Json { ignoreUnknownKeys = true; explicitNulls = false } }
    val repo = remember { context.gameRepository(json) }

    var items by remember { mutableStateOf<List<GameHeader>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var analyzedGames by remember { mutableStateOf<Map<String, FullReport>>(emptyMap()) }

    var selectedFilter by remember { mutableStateOf(GameFilter.ALL) }
    var showFilterDialog by remember { mutableStateOf(false) }

    var showAnalysis by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    var stage by remember { mutableStateOf("Подготовка…") }
    var done by remember { mutableStateOf(0) }
    var total by remember { mutableStateOf(0) }
    var etaMs by remember { mutableStateOf<Long?>(null) }

    var showAddDialog by remember { mutableStateOf(false) }
    var pastedPgn by remember { mutableStateOf("") }

    fun formatEta(ms: Long?): String {
        if (ms == null) return "—"
        val sec = max(0, (ms / 1000).toInt())
        val mm = sec / 60
        val ss = sec % 60
        return "%d:%02d".format(mm, ss)
    }

    suspend fun loadFromLocal() {
        items = repo.getAllHeaders()
        val analyzed = mutableMapOf<String, FullReport>()
        items.forEach { game ->
            game.pgn?.let { pgn ->
                repo.getCachedReport(pgn)?.let { report ->
                    val hash = repo.pgnHash(pgn)
                    analyzed[hash] = report
                }
            }
        }
        analyzedGames = analyzed
    }

    suspend fun syncWithRemote() {
        try {
            val lichessList = if (profile.lichessUsername.isNotEmpty())
                com.example.chessanalysis.GameLoaders.loadLichess(profile.lichessUsername)
            else emptyList()

            val chessList = if (profile.chessUsername.isNotEmpty())
                com.example.chessanalysis.GameLoaders.loadChessCom(profile.chessUsername)
            else emptyList()

            repo.mergeExternal(Provider.LICHESS, lichessList)
            repo.mergeExternal(Provider.CHESSCOM, chessList)
        } catch (_: Throwable) { }
    }

    // Фильтрация
    val filteredItems = remember(items, analyzedGames, selectedFilter, profile) {
        filterGames(items, analyzedGames, selectedFilter, profile, repo)
    }

    LaunchedEffect(profile) {
        isLoading = true
        loadFromLocal()
        syncWithRemote()
        loadFromLocal()
        isLoading = false
    }

    val pullState = rememberPullToRefreshState()

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                runCatching {
                    val pgn = context.contentResolver.openInputStream(uri)?.use {
                        it.readBytes().toString(Charsets.UTF_8)
                    }.orEmpty()
                    if (pgn.isBlank()) error("Файл пустой или не PGN.")
                    addManualGame(pgn, profile, repo)
                    loadFromLocal()
                    Toast.makeText(context, "Партия добавлена", Toast.LENGTH_SHORT).show()
                }.onFailure {
                    Toast.makeText(context, "Не удалось добавить партию: ${it.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Список партий") },
                actions = {
                    IconButton(onClick = { showFilterDialog = true }) {
                        Badge(
                            containerColor = if (selectedFilter != GameFilter.ALL)
                                MaterialTheme.colorScheme.primary
                            else Color.Transparent
                        ) {
                            Icon(Icons.Default.FilterList, contentDescription = "Фильтры")
                        }
                    }
                    IconButton(onClick = { showAddDialog = true }) {
                        Icon(Icons.Default.Add, contentDescription = "Добавить партию")
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            // Горизонтальная полоса быстрых фильтров
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(listOf(
                    GameFilter.ALL,
                    GameFilter.ANALYZED,
                    GameFilter.WINS,
                    GameFilter.LOSSES,
                    GameFilter.HIGH_ACCURACY
                )) { filter ->
                    FilterChip(
                        selected = selectedFilter == filter,
                        onClick = { selectedFilter = filter },
                        label = { Text(filter.label, fontSize = 12.sp) },
                        leadingIcon = if (selectedFilter == filter) {
                            { Icon(Icons.Default.CheckCircle, null, modifier = Modifier.size(16.dp)) }
                        } else null
                    )
                }
            }

            Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))

            PullToRefreshBox(
                modifier = Modifier.fillMaxSize(),
                isRefreshing = isLoading,
                onRefresh = {
                    scope.launch {
                        isLoading = true
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
                        filteredItems.isEmpty() && !isLoading -> {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        "Партий не найдено",
                                        style = MaterialTheme.typography.titleMedium
                                    )
                                    if (selectedFilter != GameFilter.ALL) {
                                        Spacer(Modifier.height(8.dp))
                                        TextButton(onClick = { selectedFilter = GameFilter.ALL }) {
                                            Text("Сбросить фильтр")
                                        }
                                    }
                                }
                            }
                        }
                        else -> {
                            LazyColumn(Modifier.fillMaxSize()) {
                                itemsIndexed(
                                    filteredItems,
                                    key = { _, game ->
                                        "${game.site}_${game.date}_${game.white}_${game.black}"
                                    }
                                ) { index, game ->
                                    AnimatedGameCard(
                                        game = game,
                                        profile = profile,
                                        analyzedReport = analyzedGames[repo.pgnHash(game.pgn.orEmpty())],
                                        index = index,
                                        isAnalyzing = showAnalysis,
                                        onClick = {
                                            if (showAnalysis) return@AnimatedGameCard
                                            scope.launch {
                                                try {
                                                    val fullPgn = com.example.chessanalysis.GameLoaders
                                                        .ensureFullPgn(game)
                                                        .ifBlank { game.pgn.orEmpty() }

                                                    if (fullPgn.isBlank()) {
                                                        Toast.makeText(context, "PGN не найден", Toast.LENGTH_SHORT).show()
                                                        return@launch
                                                    }

                                                    if (game.site == Provider.LICHESS || game.site == Provider.CHESSCOM) {
                                                        repo.updateExternalPgn(game.site, game, fullPgn)
                                                    }

                                                    val cached = repo.getCachedReport(fullPgn)
                                                    if (cached != null) {
                                                        onOpenReport(cached)
                                                        return@launch
                                                    }

                                                    showAnalysis = true
                                                    stage = "Ожидание сервера…"
                                                    progress = 0.05f
                                                    done = 0
                                                    total = 0
                                                    etaMs = null

                                                    val header = runCatching { PgnChess.headerFromPgn(fullPgn) }.getOrNull()
                                                    val report = analyzeGameByPgnWithProgress(
                                                        pgn = fullPgn,
                                                        depth = 16,
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

                                                    repo.saveReport(fullPgn, report)
                                                    showAnalysis = false
                                                    loadFromLocal()
                                                    onOpenReport(report)
                                                } catch (t: Throwable) {
                                                    showAnalysis = false
                                                    Toast.makeText(context, "Ошибка анализа: ${t.message}", Toast.LENGTH_LONG).show()
                                                }
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }

                    if (showAnalysis) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Card(
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
                                modifier = Modifier
                                    .padding(24.dp)
                                    .fillMaxWidth()
                                    .heightIn(min = 180.dp)
                            ) {
                                Column(
                                    modifier = Modifier.padding(20.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text("Анализ партии", style = MaterialTheme.typography.titleMedium)
                                    Spacer(Modifier.height(12.dp))
                                    LinearProgressIndicator(
                                        progress = { progress.coerceIn(0f, 1f) },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(8.dp),
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(Modifier.height(8.dp))
                                    Text(
                                        buildAnnotatedString {
                                            append(stage)
                                            if (total > 0) {
                                                append("  •  $done/$total позиций")
                                            }
                                            append("  •  ETA: ${formatEta(etaMs)}")
                                        },
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Диалог фильтров
    if (showFilterDialog) {
        AlertDialog(
            onDismissRequest = { showFilterDialog = false },
            title = { Text("Фильтры партий") },
            text = {
                LazyColumn {
                    items(GameFilter.values()) { filter ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selectedFilter = filter
                                    showFilterDialog = false
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = selectedFilter == filter,
                                onClick = {
                                    selectedFilter = filter
                                    showFilterDialog = false
                                }
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(filter.label)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showFilterDialog = false }) {
                    Text("Закрыть")
                }
            }
        )
    }

    // Диалог добавления партии
    if (showAddDialog) {
        val contextLocal = LocalContext.current
        val repoLocal = repo
        val profileLocal = profile
        val scopeLocal = scope

        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("Добавить партию") },
            text = {
                Column {
                    Text("Вставьте PGN полностью или выберите файл .pgn")
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = pastedPgn,
                        onValueChange = { pastedPgn = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 120.dp),
                        placeholder = { Text("Вставьте PGN сюда…") }
                    )
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = {
                        filePicker.launch(arrayOf("application/x-chess-pgn", "text/plain", "text/*"))
                    }) {
                        Text("Выбрать файл .pgn")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    scopeLocal.launch {
                        if (pastedPgn.isBlank()) {
                            Toast.makeText(contextLocal, "PGN пустой", Toast.LENGTH_SHORT).show()
                            return@launch
                        }
                        runCatching {
                            addManualGame(pgn = pastedPgn, profile = profileLocal, repo = repoLocal)
                            loadFromLocal()
                        }.onSuccess {
                            pastedPgn = ""
                            showAddDialog = false
                            Toast.makeText(contextLocal, "Партия добавлена", Toast.LENGTH_SHORT).show()
                        }.onFailure {
                            Toast.makeText(contextLocal, "Ошибка: ${it.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                }) { Text("Сохранить") }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) { Text("Отмена") }
            }
        )
    }
}

private fun filterGames(
    games: List<GameHeader>,
    analyzedGames: Map<String, FullReport>,
    filter: GameFilter,
    profile: UserProfile,
    repo: com.example.chessanalysis.data.local.GameRepository
): List<GameHeader> {
    return when (filter) {
        GameFilter.ALL -> games

        GameFilter.ANALYZED -> games.filter { game ->
            analyzedGames.containsKey(repo.pgnHash(game.pgn.orEmpty()))
        }

        GameFilter.NOT_ANALYZED -> games.filter { game ->
            !analyzedGames.containsKey(repo.pgnHash(game.pgn.orEmpty()))
        }

        GameFilter.WINS -> games.filter { game ->
            val mySide = guessMySide(profile, game)
            mySide != null && (
                    (mySide && game.result == "1-0") ||
                            (!mySide && game.result == "0-1")
                    )
        }

        GameFilter.LOSSES -> games.filter { game ->
            val mySide = guessMySide(profile, game)
            mySide != null && (
                    (mySide && game.result == "0-1") ||
                            (!mySide && game.result == "1-0")
                    )
        }

        GameFilter.DRAWS -> games.filter { game ->
            game.result == "1/2-1/2"
        }

        GameFilter.MANUAL -> games.filter { game ->
            game.site == Provider.LICHESS &&
                    !game.pgn.orEmpty().contains("lichess.org/") &&
                    !game.pgn.orEmpty().contains("chess.com/")
        }

        GameFilter.HIGH_ACCURACY -> games.filter { game ->
            val report = analyzedGames[repo.pgnHash(game.pgn.orEmpty())]
            if (report == null) return@filter false
            val mySide = guessMySide(profile, game)
            val myAccuracy = if (mySide == true) {
                report.accuracy.whiteMovesAcc.itera
            } else if (mySide == false) {
                report.accuracy.blackMovesAcc.itera
            } else null
            myAccuracy != null && myAccuracy > 85.0
        }

        GameFilter.LOW_ACCURACY -> games.filter { game ->
            val report = analyzedGames[repo.pgnHash(game.pgn.orEmpty())]
            if (report == null) return@filter false
            val mySide = guessMySide(profile, game)
            val myAccuracy = if (mySide == true) {
                report.accuracy.whiteMovesAcc.itera
            } else if (mySide == false) {
                report.accuracy.blackMovesAcc.itera
            } else null
            myAccuracy != null && myAccuracy < 70.0
        }

        GameFilter.HIGH_PERFORMANCE -> games.filter { game ->
            val report = analyzedGames[repo.pgnHash(game.pgn.orEmpty())]
            if (report == null) return@filter false
            val mySide = guessMySide(profile, game)
            val myPerformance = if (mySide == true) {
                report.estimatedElo.whiteEst
            } else if (mySide == false) {
                report.estimatedElo.blackEst
            } else null
            myPerformance != null && myPerformance > 2000
        }

        GameFilter.OLDEST -> games.sortedBy {
            it.date?.let { date ->
                try {
                    java.text.SimpleDateFormat("yyyy.MM.dd", java.util.Locale.US).parse(date)?.time
                } catch (_: Exception) { null }
            }
        }

        GameFilter.NEWEST -> games.sortedByDescending {
            it.date?.let { date ->
                try {
                    java.text.SimpleDateFormat("yyyy.MM.dd", java.util.Locale.US).parse(date)?.time
                } catch (_: Exception) { null }
            }
        }
    }
}

// Остальной код AnimatedGameCard, AnimatedAnalyzedBadge и т.д. остается без изменений
// (используйте код из предыдущего ответа)

@Composable
private fun AnimatedGameCard(
    game: GameHeader,
    profile: UserProfile,
    analyzedReport: FullReport?,
    index: Int,
    isAnalyzing: Boolean,
    onClick: () -> Unit
) {
    // Анимация появления карточки
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(index * 50L)
        visible = true
    }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(300)) + slideInVertically(tween(400)) { it / 4 }
    ) {
        val mySide: Boolean? = guessMySide(profile, game)
        val userWon = mySide != null && (
                (mySide && game.result == "1-0") ||
                        (!mySide && game.result == "0-1")
                )
        val userLost = mySide != null && (
                (mySide && game.result == "0-1") ||
                        (!mySide && game.result == "1-0")
                )

        val isAnalyzed = analyzedReport != null

        // Анимация масштаба при клике
        var pressed by remember { mutableStateOf(false) }
        val scale by animateFloatAsState(
            targetValue = if (pressed) 0.97f else 1f,
            animationSpec = spring(stiffness = Spring.StiffnessMedium)
        )

        Card(
            colors = CardDefaults.cardColors(
                containerColor = when {
                    userWon -> Color(0xFFDFF0D8)
                    userLost -> Color(0xFFF2DEDE)
                    else -> MaterialTheme.colorScheme.surface
                }
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .scale(scale)
                .clickable(enabled = !isAnalyzing) {
                    pressed = true
                    onClick()
                    pressed = false
                }
        ) {
            Box {
                Column(Modifier.padding(14.dp)) {
                    // Верхняя строка с информацией о партии
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val siteName = when (game.site) {
                            Provider.LICHESS -> "Lichess"
                            Provider.CHESSCOM -> "Chess.com"
                            Provider.BOT -> "Bot"
                            null -> ""
                        }

                        val (modeLabel, openingLine) = deriveModeAndOpening(game)

                        Text(
                            buildAnnotatedString {
                                withStyle(SpanStyle(fontWeight = FontWeight.Medium)) {
                                    append(siteName)
                                }
                                if (!game.date.isNullOrBlank()) {
                                    append("  •  ")
                                    append(game.date!!)
                                }
                                if (modeLabel.isNotBlank()) {
                                    append("  •  ")
                                    append(modeLabel)
                                }
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )

                        // Индикатор анализа
                        if (isAnalyzed) {
                            AnimatedAnalyzedBadge()
                        }
                    }

                    Spacer(Modifier.height(10.dp))

                    // Игроки
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            UserBubble(name = game.white ?: "W", size = 28.dp)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = playerWithTitle(game.white, game.pgn, isWhite = true),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                        Text(
                            game.result.orEmpty(),
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = playerWithTitle(game.black, game.pgn, isWhite = false),
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Spacer(Modifier.width(8.dp))
                            UserBubble(name = game.black ?: "B", size = 28.dp)
                        }
                    }

                    // Статистика анализа
                    if (isAnalyzed && analyzedReport != null) {
                        Spacer(Modifier.height(12.dp))
                        Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                        Spacer(Modifier.height(12.dp))

                        AnimatedAnalysisStats(
                            whiteAccuracy = analyzedReport.accuracy.whiteMovesAcc.itera,
                            blackAccuracy = analyzedReport.accuracy.blackMovesAcc.itera,
                            whitePerformance = analyzedReport.estimatedElo.whiteEst,
                            blackPerformance = analyzedReport.estimatedElo.blackEst
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AnimatedAnalyzedBadge() {
    val infiniteTransition = rememberInfiniteTransition(label = "badge")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.7f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha"
    )

    Box(
        modifier = Modifier
            .background(
                brush = Brush.horizontalGradient(
                    colors = listOf(
                        Color(0xFF4CAF50).copy(alpha = alpha),
                        Color(0xFF66BB6A).copy(alpha = alpha)
                    )
                ),
                shape = RoundedCornerShape(12.dp)
            )
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                Icons.Default.CheckCircle,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(16.dp)
            )
            Text(
                "Анализ",
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun AnimatedAnalysisStats(
    whiteAccuracy: Double,
    blackAccuracy: Double,
    whitePerformance: Int?,
    blackPerformance: Int?
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        // Белые
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.weight(1f)
        ) {
            Text(
                "Точность",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
            Spacer(Modifier.height(4.dp))
            AnimatedCounter(
                value = whiteAccuracy,
                suffix = "%",
                color = getAccuracyColor(whiteAccuracy)
            )

            if (whitePerformance != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Перформанс",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
                Spacer(Modifier.height(4.dp))
                AnimatedCounter(
                    value = whitePerformance.toDouble(),
                    suffix = "",
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        // Разделитель
        Box(
            modifier = Modifier
                .width(1.dp)
                .height(80.dp)
                .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
        )

        // Черные
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.weight(1f)
        ) {
            Text(
                "Точность",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
            Spacer(Modifier.height(4.dp))
            AnimatedCounter(
                value = blackAccuracy,
                suffix = "%",
                color = getAccuracyColor(blackAccuracy)
            )

            if (blackPerformance != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Перформанс",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
                Spacer(Modifier.height(4.dp))
                AnimatedCounter(
                    value = blackPerformance.toDouble(),
                    suffix = "",
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
private fun AnimatedCounter(
    value: Double,
    suffix: String,
    color: Color
) {
    var displayValue by remember { mutableStateOf(0.0) }

    LaunchedEffect(value) {
        val start = displayValue
        val duration = 800
        val startTime = System.currentTimeMillis()

        while (displayValue != value) {
            val elapsed = System.currentTimeMillis() - startTime
            val progress = (elapsed.toFloat() / duration).coerceIn(0f, 1f)

            displayValue = start + (value - start) * progress

            if (progress >= 1f) {
                displayValue = value
                break
            }

            kotlinx.coroutines.delay(16)
        }
    }

    Text(
        text = if (suffix == "%") {
            "%.1f$suffix".format(displayValue)
        } else {
            "%.0f$suffix".format(displayValue)
        },
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = color
    )
}

private fun getAccuracyColor(accuracy: Double): Color {
    return when {
        accuracy >= 90 -> Color(0xFF4CAF50) // Зеленый
        accuracy >= 80 -> Color(0xFF8BC34A) // Светло-зеленый
        accuracy >= 70 -> Color(0xFFFFC107) // Желтый
        accuracy >= 60 -> Color(0xFFFF9800) // Оранжевый
        else -> Color(0xFFF44336) // Красный
    }
}

/* ======================== ВСПОМОГАТЕЛЬНОЕ ======================== */

@Composable
private fun UserBubble(
    name: String,
    size: androidx.compose.ui.unit.Dp,
    bg: Color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
    fg: Color = MaterialTheme.colorScheme.primary
) {
    val letter = name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(bg),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = letter,
            color = fg,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold
        )
    }
}

private fun guessMySide(profile: UserProfile, game: GameHeader): Boolean? {
    val me = listOf(
        profile.nickname.trim(),
        profile.lichessUsername.trim(),
        profile.chessUsername.trim()
    ).filter { it.isNotBlank() }
        .map { it.lowercase() }

    val w = game.white?.trim()?.lowercase()
    val b = game.black?.trim()?.lowercase()

    return when {
        w != null && me.any { it == w } -> true
        b != null && me.any { it == b } -> false
        else -> null
    }
}

private fun playerWithTitle(name: String?, pgn: String?, isWhite: Boolean): String {
    val base = name.orEmpty()
    if (pgn.isNullOrBlank()) return base
    val tag = if (isWhite) """\[WhiteTitle\s+"([^"]+)"]""" else """\[BlackTitle\s+"([^"]+)"]"""
    val rx = Regex(tag)
    val title = rx.find(pgn)?.groupValues?.getOrNull(1)
    return if (!title.isNullOrBlank()) "$base (${title.uppercase()})" else base
}

private fun deriveModeAndOpening(game: GameHeader): Pair<String, String> {
    val pgn = game.pgn
    var mode = ""
    var openingStr = game.opening ?: game.eco ?: ""

    if (!pgn.isNullOrBlank()) {
        val tc = Regex("""\[TimeControl\s+"([^"]+)"]""").find(pgn)?.groupValues?.getOrNull(1)
        mode = tc?.let { mapTimeControlToMode(it) } ?: ""

        if (openingStr.isBlank()) {
            val op = Regex("""\[Opening\s+"([^"]+)"]""").find(pgn)?.groupValues?.getOrNull(1)
            val eco = Regex("""\[ECO\s+"([^"]+)"]""").find(pgn)?.groupValues?.getOrNull(1)
            openingStr = when {
                !op.isNullOrBlank() && !eco.isNullOrBlank() -> "$op ($eco)"
                !op.isNullOrBlank() -> op
                !eco.isNullOrBlank() -> eco
                else -> ""
            }
        }
    }

    return mode to (openingStr ?: "")
}

private fun mapTimeControlToMode(tc: String): String {
    val main = tc.substringBefore('+', tc).toIntOrNull() ?: return ""
    return when {
        main <= 60 -> "bullet"
        main <= 300 -> "blitz"
        main <= 1500 -> "rapid"
        else -> "classical"
    }
}

private suspend fun addManualGame(
    pgn: String,
    profile: UserProfile,
    repo: com.example.chessanalysis.data.local.GameRepository
) {
    val header = runCatching { PgnChess.headerFromPgn(pgn) }.getOrNull()

    val gh = GameHeader(
        site = Provider.LICHESS,
        pgn = pgn,
        white = header?.white ?: Regex("""\[White\s+"([^"]+)"]""").find(pgn)?.groupValues?.getOrNull(1),
        black = header?.black ?: Regex("""\[Black\s+"([^"]+)"]""").find(pgn)?.groupValues?.getOrNull(1),
        result = header?.result ?: Regex("""\[Result\s+"([^"]+)"]""").find(pgn)?.groupValues?.getOrNull(1),
        date = header?.date ?: Regex("""\[Date\s+"([^"]+)"]""").find(pgn)?.groupValues?.getOrNull(1),
        sideToView = guessMySide(
            profile,
            header ?: GameHeader(
                site = null,
                pgn = pgn,
                white = null,
                black = null,
                result = null,
                date = null,
                sideToView = null,
                opening = null,
                eco = null
            )
        ),
        opening = header?.opening,
        eco = header?.eco
    )

    repo.mergeExternal(Provider.LICHESS, listOf(gh))
}