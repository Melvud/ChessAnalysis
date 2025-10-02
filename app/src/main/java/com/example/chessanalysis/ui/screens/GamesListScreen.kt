// app/src/main/java/com/example/chessanalysis/ui/screens/GamesListScreen.kt

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
            // Быстрые фильтры
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
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
                        label = { Text(filter.label, fontSize = 11.sp) },
                        leadingIcon = if (selectedFilter == filter) {
                            { Icon(Icons.Default.CheckCircle, null, modifier = Modifier.size(14.dp)) }
                        } else null
                    )
                }
            }

            Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))

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
                                    key = { idx, game ->
                                        "${game.site}_${game.date}_${game.white}_${game.black}_$idx"
                                    }
                                ) { index, game ->
                                    CompactGameCard(
                                        game = game,
                                        profile = profile,
                                        analyzedReport = analyzedGames[repo.pgnHash(game.pgn.orEmpty())],
                                        index = index,
                                        isAnalyzing = showAnalysis,
                                        onClick = {
                                            if (showAnalysis) return@CompactGameCard
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

@Composable
private fun CompactGameCard(
    game: GameHeader,
    profile: UserProfile,
    analyzedReport: FullReport?,
    index: Int,
    isAnalyzing: Boolean,
    onClick: () -> Unit
) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(index * 30L)
        visible = true
    }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(200)) + slideInVertically(tween(300)) { it / 4 }
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

        var pressed by remember { mutableStateOf(false) }
        val scale by animateFloatAsState(
            targetValue = if (pressed) 0.98f else 1f,
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
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp)
                .scale(scale)
                .clickable(enabled = !isAnalyzing) {
                    pressed = true
                    onClick()
                    pressed = false
                }
        ) {
            val siteName = when (game.site) {
                Provider.LICHESS -> "Lichess"
                Provider.CHESSCOM -> "Chess.com"
                Provider.BOT -> "Bot"
                null -> ""
            }
            val (modeLabel, openingLine) = deriveModeAndOpening(game)

            Column(Modifier.padding(10.dp)) {
                // Верхняя строка: инфо + бейдж
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        buildAnnotatedString {
                            withStyle(SpanStyle(fontWeight = FontWeight.Medium, fontSize = 11.sp)) {
                                append(siteName)
                            }
                            if (!game.date.isNullOrBlank()) {
                                append(" • ")
                                append(game.date!!)
                            }
                            if (modeLabel.isNotBlank()) {
                                append(" • ")
                                append(modeLabel)
                            }
                        },
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    if (isAnalyzed) {
                        Box(
                            modifier = Modifier
                                .background(
                                    color = Color(0xFF4CAF50),
                                    shape = RoundedCornerShape(8.dp)
                                )
                                .padding(horizontal = 8.dp, vertical = 3.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(12.dp)
                                )
                                Text(
                                    "Анализировано",
                                    color = Color.White,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(6.dp))

                // Игроки
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        UserBubble(name = game.white ?: "W", size = 22.dp)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = playerWithTitle(game.white, game.pgn, isWhite = true),
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Text(
                        game.result.orEmpty(),
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        ),
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.End
                    ) {
                        Text(
                            text = playerWithTitle(game.black, game.pgn, isWhite = false),
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.End
                        )
                        Spacer(Modifier.width(6.dp))
                        UserBubble(name = game.black ?: "B", size = 22.dp)
                    }
                }

                if (openingLine.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        openingLine,
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Статистика (только для анализированных)
                if (isAnalyzed && analyzedReport != null) {
                    Spacer(Modifier.height(8.dp))
                    Divider(
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f),
                        thickness = 0.5.dp
                    )
                    Spacer(Modifier.height(6.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        // Белые
                        StatColumn(
                            accuracy = analyzedReport.accuracy.whiteMovesAcc.itera,
                            performance = analyzedReport.estimatedElo.whiteEst
                        )

                        Box(
                            modifier = Modifier
                                .width(0.5.dp)
                                .height(40.dp)
                                .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                        )

                        // Черные
                        StatColumn(
                            accuracy = analyzedReport.accuracy.blackMovesAcc.itera,
                            performance = analyzedReport.estimatedElo.blackEst
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatColumn(
    accuracy: Double,
    performance: Int?
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "%.1f%%".format(accuracy),
                style = MaterialTheme.typography.bodySmall.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp
                ),
                color = getAccuracyColor(accuracy)
            )
            if (performance != null) {
                Text(
                    " • %d".format(performance),
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp),
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

private fun getAccuracyColor(accuracy: Double): Color {
    return when {
        accuracy >= 90 -> Color(0xFF4CAF50)
        accuracy >= 80 -> Color(0xFF8BC34A)
        accuracy >= 70 -> Color(0xFFFFC107)
        accuracy >= 60 -> Color(0xFFFF9800)
        else -> Color(0xFFF44336)
    }
}

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
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
            fontWeight = FontWeight.SemiBold
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
    var openingLine = game.opening ?: game.eco ?: ""

    if (!pgn.isNullOrBlank()) {
        val tc = Regex("""\[TimeControl\s+"([^"]+)"]""").find(pgn)?.groupValues?.getOrNull(1)
        mode = tc?.let { mapTimeControlToMode(it) } ?: ""

        if (openingLine.isBlank()) {
            val op = Regex("""\[Opening\s+"([^"]+)"]""").find(pgn)?.groupValues?.getOrNull(1)
            val eco = Regex("""\[ECO\s+"([^"]+)"]""").find(pgn)?.groupValues?.getOrNull(1)
            openingLine = when {
                !op.isNullOrBlank() && !eco.isNullOrBlank() -> "$op ($eco)"
                !op.isNullOrBlank() -> op
                !eco.isNullOrBlank() -> eco
                else -> ""
            }
        }
    }

    return mode to (openingLine ?: "")
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