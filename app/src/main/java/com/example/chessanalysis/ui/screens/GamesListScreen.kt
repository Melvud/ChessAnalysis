package com.example.chessanalysis.ui.screens

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
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

    var showAnalysis by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    var stage by remember { mutableStateOf("Подготовка…") }
    var done by remember { mutableStateOf(0) }
    var total by remember { mutableStateOf(0) }
    var etaMs by remember { mutableStateOf<Long?>(null) }

    // Добавление партии
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
    }

    /** Только добавляем новые из сети в локальное хранилище. */
    suspend fun syncWithRemote() {
        try {
            val lichessList = if (profile.lichessUsername.isNotEmpty())
                com.example.chessanalysis.GameLoaders.loadLichess(profile.lichessUsername)
            else emptyList()

            val chessList = if (profile.chessUsername.isNotEmpty())
                com.example.chessanalysis.GameLoaders.loadChessCom(profile.chessUsername)
            else emptyList()

            // Мердж «только добавление»
            repo.mergeExternal(Provider.LICHESS, lichessList)
            repo.mergeExternal(Provider.CHESSCOM, chessList)
        } catch (_: Throwable) { /* оффлайн/ошибка сети — пропускаем */ }
    }

    LaunchedEffect(profile) {
        isLoading = true
        loadFromLocal()
        syncWithRemote()
        loadFromLocal()
        isLoading = false
    }

    val pullState = rememberPullToRefreshState()

    // Пикер файла .pgn
    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                runCatching {
                    val pgn = context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                        .orEmpty()
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
                    IconButton(onClick = { showAddDialog = true }) {
                        Icon(Icons.Default.Add, contentDescription = "Добавить партию")
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
                                val mySide: Boolean? = guessMySide(profile, game)
                                val userWon = mySide != null && (
                                        (mySide && game.result == "1-0") ||
                                                (!mySide && game.result == "0-1")
                                        )
                                val userLost = mySide != null && (
                                        (mySide && game.result == "0-1") ||
                                                (!mySide && game.result == "1-0")
                                        )

                                val cardColor = when {
                                    userWon -> Color(0xFFDFF0D8)
                                    userLost -> Color(0xFFF2DEDE)
                                    else -> MaterialTheme.colorScheme.surface
                                }

                                Card(
                                    colors = CardDefaults.cardColors(containerColor = cardColor),
                                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 8.dp)
                                        .clickable(enabled = !showAnalysis) {
                                            if (showAnalysis) return@clickable
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
                                                    onOpenReport(report)
                                                } catch (t: Throwable) {
                                                    showAnalysis = false
                                                    Toast.makeText(context, "Ошибка анализа: ${t.message}", Toast.LENGTH_LONG).show()
                                                }
                                            }
                                        }
                                ) {
                                    Column(Modifier.padding(14.dp)) {
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
                                            color = MaterialTheme.colorScheme.onSurface
                                        )

                                        Spacer(Modifier.height(10.dp))

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

                                        if (openingLine.isNotBlank()) {
                                            Spacer(Modifier.height(8.dp))
                                            Text(openingLine, style = MaterialTheme.typography.bodySmall)
                                        }
                                    }
                                }
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

    if (showAddDialog) {
        // 🔧 Вынесли обращения к CompositionLocal из onClick наружу:
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
                        // выбор файла
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
                            // после добавления — перезагрузим список
                            items = repoLocal.getAllHeaders()
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

/* ======================== ВСПОМОГАТЕЛЬНОЕ ======================== */

@Composable
private fun UserBubble(
    name: String,
    size: Dp,
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

/**
 * Пытаемся определить сторону пользователя, чтобы подсветить результат.
 */
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

/** Берём из PGN теги WhiteTitle/BlackTitle, если полный PGN уже есть локально. */
private fun playerWithTitle(name: String?, pgn: String?, isWhite: Boolean): String {
    val base = name.orEmpty()
    if (pgn.isNullOrBlank()) return base
    val tag = if (isWhite) """\[WhiteTitle\s+"([^"]+)"]""" else """\[BlackTitle\s+"([^"]+)"]"""
    val rx = Regex(tag)
    val title = rx.find(pgn)?.groupValues?.getOrNull(1)
    return if (!title.isNullOrBlank()) "$base (${title.uppercase()})" else base
}

/** Возвращает метку режима (bullet/blitz/rapid/…) и строку дебюта (Opening / ECO), если они есть в PGN. */
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

/**
 * Добавляет в локальную БД партию из полного PGN.
 */
private suspend fun addManualGame(
    pgn: String,
    profile: UserProfile,
    repo: com.example.chessanalysis.data.local.GameRepository
) {
    val header = runCatching { PgnChess.headerFromPgn(pgn) }.getOrNull()

    val gh = GameHeader(
        site = Provider.LICHESS, // используем как стабильный ключ-провайдер
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
