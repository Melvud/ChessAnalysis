package com.example.chessanalysis.ui.screens

import android.media.MediaPlayer
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.chessanalysis.EngineClient.analyzeGameByPgnWithProgress
import com.example.chessanalysis.FullReport
import com.example.chessanalysis.GameHeader
import com.example.chessanalysis.MoveClass
import com.example.chessanalysis.PgnChess
import com.example.chessanalysis.Provider
import com.example.chessanalysis.R
import com.example.chessanalysis.data.local.gameRepository
import com.example.chessanalysis.ui.UserProfile
import com.example.chessanalysis.ui.components.BoardCanvas
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

private const val TAG = "GamesListScreen"

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
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

    // Live ожидание анализа + мини-звуки
    var showAnalysis by remember { mutableStateOf(false) }
    var liveFen by remember { mutableStateOf<String?>(null) }
    var liveUciMove by remember { mutableStateOf<String?>(null) }
    var liveMoveClass by remember { mutableStateOf<String?>(null) }

    // для звуков: предыдущая позиция (до текущего хода) и последний озвученный UCI
    var prevFenForSound by remember { mutableStateOf<String?>(null) }
    var lastSoundedUci by remember { mutableStateOf<String?>(null) }

    // Добавление PGN
    var showAddDialog by remember { mutableStateOf(false) }
    var pastedPgn by remember { mutableStateOf("") }

    // Лист перезапуска анализа
    var showReAnalyzeSheet by remember { mutableStateOf(false) }
    var reAnalyzeDepth by remember { mutableStateOf(16) }
    var reAnalyzeMultiPv by remember { mutableStateOf(3) }
    var reAnalyzeTargetPgn by remember { mutableStateOf<String?>(null) }

    // ===== Вспомогательные: звук и определение взятия по FEN+UCI =====
    fun playMoveSound(cls: MoveClass?, isCapture: Boolean) {
        val resId = when {
            cls == MoveClass.INACCURACY || cls == MoveClass.MISTAKE || cls == MoveClass.BLUNDER -> R.raw.error
            isCapture -> R.raw.capture
            else -> R.raw.move
        }
        runCatching {
            MediaPlayer.create(context, resId)?.apply {
                setOnCompletionListener { it.release() }
                start()
            }
        }
    }

    fun pieceAtFen(fen: String, square: String): Char? {
        // fen: "pieces side castling ep halfmove fullmove"
        val fields = fen.split(" ")
        if (fields.isEmpty()) return null
        val board = fields[0]
        // строки board разделены '/', сверху (8) вниз (1)
        val ranks = board.split("/")
        if (ranks.size != 8) return null
        val fileChar = square[0] - 'a' // 0..7
        val rankIdxFromTop = 8 - (square[1] - '0') // 0..7
        if (fileChar !in 0..7 || rankIdxFromTop !in 0..7) return null
        val rank = ranks[rankIdxFromTop]
        var col = 0
        for (ch in rank) {
            if (ch.isDigit()) {
                col += (ch.code - '0'.code)
            } else {
                if (col == fileChar) return ch
                col++
            }
        }
        return null // пустая клетка
    }

    fun isCapture(prevFen: String?, uci: String): Boolean {
        if (prevFen.isNullOrBlank() || uci.length < 4) return false
        val from = uci.substring(0, 2)
        val to = uci.substring(2, 4)
        val pieceFrom = pieceAtFen(prevFen, from)
        val pieceTo = pieceAtFen(prevFen, to)
        // обычное взятие: на клетке назначения кто-то был
        if (pieceTo != null) return true
        // эн-пассант: пешка идёт по диагонали, но целевая пуста
        val isPawn =
            pieceFrom != null && (pieceFrom == 'P' || pieceFrom == 'p')
        val fromFile = from[0]
        val toFile = to[0]
        return isPawn && fromFile != toFile
    }

    // ===== Загрузка всех партий из локального хранилища
    suspend fun loadFromLocal() {
        Log.d(TAG, "loadFromLocal: starting...")
        items = repo.getAllHeaders()
        Log.d(TAG, "loadFromLocal: loaded ${items.size} games")

        val analyzed = mutableMapOf<String, FullReport>()
        items.forEach { game ->
            game.pgn?.let { pgn ->
                repo.getCachedReport(pgn)?.let { report ->
                    analyzed[repo.pgnHash(pgn)] = report
                }
            }
        }
        analyzedGames = analyzed
        Log.d(TAG, "loadFromLocal: ${analyzed.size} games have cached analysis")
    }

    suspend fun syncWithRemote() {
        try {
            Log.d(TAG, "syncWithRemote: starting...")
            var addedCount = 0

            if (profile.lichessUsername.isNotEmpty()) {
                Log.d(TAG, "Fetching Lichess games for: ${profile.lichessUsername}")
                val lichessList = com.example.chessanalysis.GameLoaders.loadLichess(
                    profile.lichessUsername,
                    max = 20
                )
                Log.d(TAG, "Lichess returned ${lichessList.size} games")
                val added = repo.mergeExternal(Provider.LICHESS, lichessList)
                addedCount += added
                Log.d(TAG, "Added $added new Lichess games")
            }

            if (profile.chessUsername.isNotEmpty()) {
                Log.d(TAG, "Fetching Chess.com games for: ${profile.chessUsername}")
                val chessList = com.example.chessanalysis.GameLoaders.loadChessCom(
                    profile.chessUsername,
                    max = 20
                )
                Log.d(TAG, "Chess.com returned ${chessList.size} games")
                val added = repo.mergeExternal(Provider.CHESSCOM, chessList)
                addedCount += added
                Log.d(TAG, "Added $added new Chess.com games")
            }

            Log.d(TAG, "syncWithRemote: total added = $addedCount")

            if (addedCount > 0) {
                Toast.makeText(context, "Добавлено новых партий: $addedCount", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Throwable) {
            Log.e(TAG, "syncWithRemote failed: ${e.message}", e)
            Toast.makeText(context, "Ошибка синхронизации: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // При первом запуске
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

    fun startAnalysis(fullPgn: String, depth: Int, multiPv: Int) {
        if (showAnalysis) return
        scope.launch {
            try {
                showAnalysis = true
                // сбрасываем состояние мини-звуков и лайва
                liveFen = null
                liveUciMove = null
                liveMoveClass = null
                prevFenForSound = null
                lastSoundedUci = null

                val header = runCatching { PgnChess.headerFromPgn(fullPgn) }.getOrNull()

                val report = analyzeGameByPgnWithProgress(
                    pgn = fullPgn,
                    depth = depth,
                    multiPv = multiPv,
                    header = header
                ) { snap ->
                    val newFen = snap.fen
                    val newUci = snap.currentUci
                    val cls = snap.currentClass?.let { runCatching { MoveClass.valueOf(it) }.getOrNull() }

                    // Озвучиваем ход единожды
                    if (!newUci.isNullOrBlank() && newUci != lastSoundedUci) {
                        val captureNow = isCapture(prevFenForSound, newUci)
                        playMoveSound(cls, captureNow)
                        lastSoundedUci = newUci
                    }

                    // Обновляем prevFen на позицию ПОСЛЕ хода — она будет "до" для следующего
                    prevFenForSound = newFen ?: prevFenForSound

                    // Обновляем UI мини-экрана
                    liveFen = newFen ?: liveFen
                    liveUciMove = newUci ?: liveUciMove
                    liveMoveClass = snap.currentClass ?: liveMoveClass
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
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            PullToRefreshBox(
                modifier = Modifier.fillMaxSize(),
                isRefreshing = isLoading,
                onRefresh = {
                    scope.launch {
                        isLoading = true
                        syncWithRemote()
                        loadFromLocal()
                        isLoading = false
                    }
                },
                state = pullState
            ) {
                when {
                    isLoading && items.isEmpty() -> {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }
                    items.isEmpty() && !isLoading -> {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("Партий не найдено", style = MaterialTheme.typography.titleMedium)
                        }
                    }
                    else -> {
                        LazyColumn(Modifier.fillMaxSize()) {
                            itemsIndexed(
                                items,
                                key = { _, g ->
                                    val hashPart = (g.pgn?.length ?: 0).toString()
                                    "${g.site}|${g.date}|${g.white}|${g.black}|${g.result}|$hashPart"
                                }
                            ) { index, game ->
                                val analyzedReport = analyzedGames[repo.pgnHash(game.pgn.orEmpty())]
                                CompactGameCard(
                                    game = game,
                                    profile = profile,
                                    analyzedReport = analyzedReport,
                                    index = index,
                                    isAnalyzing = showAnalysis,
                                    onClick = {
                                        if (showAnalysis) return@CompactGameCard
                                        scope.launch {
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
                                            if (cached != null) onOpenReport(cached)
                                            else startAnalysis(fullPgn, depth = 16, multiPv = 3)
                                        }
                                    },
                                    onLongPress = {
                                        if (analyzedReport != null) {
                                            reAnalyzeTargetPgn = game.pgn
                                            reAnalyzeDepth = 16
                                            reAnalyzeMultiPv = 3
                                            showReAnalyzeSheet = true
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }

            // Мини-оверлей анализа с доской и звуками (звуки триггерятся в колбэке выше)
            if (showAnalysis) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.7f)),
                    contentAlignment = Alignment.Center
                ) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
                        modifier = Modifier
                            .padding(32.dp)
                            .size(280.dp)
                    ) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            if (!liveFen.isNullOrBlank()) {
                                val lastMovePair = if (!liveUciMove.isNullOrBlank() && liveUciMove!!.length >= 4) {
                                    liveUciMove!!.substring(0, 2) to liveUciMove!!.substring(2, 4)
                                } else null

                                val moveClassEnum = liveMoveClass?.let {
                                    runCatching { MoveClass.valueOf(it) }.getOrNull()
                                }

                                BoardCanvas(
                                    fen = liveFen!!,
                                    lastMove = lastMovePair,
                                    moveClass = moveClassEnum,
                                    bestMoveUci = null,
                                    showBestArrow = false,
                                    isWhiteBottom = true,
                                    selectedSquare = null,
                                    legalMoves = emptySet(),
                                    onSquareClick = null,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(8.dp)
                                )
                            } else {
                                CircularProgressIndicator()
                            }
                        }
                    }
                }
            }
        }
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
                    }) { Text("Выбрать файл .pgn") }
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
            dismissButton = { TextButton(onClick = { showAddDialog = false }) { Text("Отмена") } }
        )
    }

    if (showReAnalyzeSheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showReAnalyzeSheet = false },
            sheetState = sheetState
        ) {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Text("Анализировать заново", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(12.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Depth", modifier = Modifier.width(80.dp))
                    OutlinedTextField(
                        value = reAnalyzeDepth.toString(),
                        onValueChange = { s ->
                            reAnalyzeDepth = s.filter { it.isDigit() }.toIntOrNull()?.coerceIn(6, 40) ?: reAnalyzeDepth
                        },
                        singleLine = true,
                        modifier = Modifier.width(120.dp)
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("multiPV", modifier = Modifier.width(80.dp))
                    OutlinedTextField(
                        value = reAnalyzeMultiPv.toString(),
                        onValueChange = { s ->
                            reAnalyzeMultiPv = s.filter { it.isDigit() }.toIntOrNull()?.coerceIn(1, 5) ?: reAnalyzeMultiPv
                        },
                        singleLine = true,
                        modifier = Modifier.width(120.dp)
                    )
                }

                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = {
                        val pgn = reAnalyzeTargetPgn
                        if (!pgn.isNullOrBlank()) {
                            showReAnalyzeSheet = false
                            startAnalysis(pgn, depth = reAnalyzeDepth, multiPv = reAnalyzeMultiPv)
                        } else {
                            showReAnalyzeSheet = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Анализировать заново") }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

/* ===== Карточка ===== */

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CompactGameCard(
    game: GameHeader,
    profile: UserProfile,
    analyzedReport: FullReport?,
    index: Int,
    isAnalyzing: Boolean,
    onClick: () -> Unit,
    onLongPress: () -> Unit
) {
    val mySide: Boolean? = guessMySide(profile, game)
    val userWon = mySide != null && ((mySide && game.result == "1-0") || (!mySide && game.result == "0-1"))
    val userLost = mySide != null && ((mySide && game.result == "0-1") || (!mySide && game.result == "1-0"))
    val isAnalyzed = analyzedReport != null

    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.98f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "scale"
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
            .height(148.dp)
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .scale(scale)
            .combinedClickable(
                enabled = !isAnalyzing,
                onClick = { pressed = true; onClick(); pressed = false },
                onLongClick = { if (isAnalyzed) onLongPress() }
            )
    ) {
        val siteName = when (game.site) {
            Provider.LICHESS -> "Lichess"
            Provider.CHESSCOM -> "Chess.com"
            Provider.BOT -> "Bot"
            null -> ""
        }
        val (modeLabel, openingLine) = deriveModeAndOpening(game)

        Column(Modifier.padding(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(fontWeight = FontWeight.Medium, fontSize = 11.sp)) { append(siteName) }
                        if (!game.date.isNullOrBlank()) { append(" • "); append(game.date!!) }
                        if (modeLabel.isNotBlank()) { append(" • "); append(modeLabel) }
                    },
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (isAnalyzed) {
                    Badge(containerColor = Color(0xFF4CAF50), contentColor = Color.White) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color.White, modifier = Modifier.size(12.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Анализировано", fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }

            Spacer(Modifier.height(6.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
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
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold, fontSize = 12.sp),
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

            Spacer(Modifier.height(6.dp))
            Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(8.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    StatColumn(
                        accuracy = analyzedReport?.accuracy?.whiteMovesAcc?.itera,
                        performance = analyzedReport?.estimatedElo?.whiteEst
                    )
                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .height(24.dp)
                            .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
                    )
                    StatColumn(
                        accuracy = analyzedReport?.accuracy?.blackMovesAcc?.itera,
                        performance = analyzedReport?.estimatedElo?.blackEst
                    )
                }
            }
        }
    }
}

/* ===== Вспомогательное ===== */

@Composable
private fun StatColumn(accuracy: Double?, performance: Int?) {
    val accText = if (accuracy != null) "%.1f%%".format(accuracy) else "—"
    val perfText = performance?.toString() ?: "—"
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            accText,
            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold, fontSize = 14.sp),
            color = if (accuracy != null) getAccuracyColor(accuracy) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
        Text(
            " • $perfText",
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
            color = if (performance != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
    }
}

private fun getAccuracyColor(accuracy: Double): Color = when {
    accuracy >= 90 -> Color(0xFF2E7D32)
    accuracy >= 80 -> Color(0xFF558B2F)
    accuracy >= 70 -> Color(0xFFF9A825)
    accuracy >= 60 -> Color(0xFFEF6C00)
    else -> Color(0xFFC62828)
}

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
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
            fontWeight = FontWeight.SemiBold
        )
    }
}

private fun guessMySide(profile: UserProfile, game: GameHeader): Boolean? {
    val me = listOf(profile.nickname.trim(), profile.lichessUsername.trim(), profile.chessUsername.trim())
        .filter { it.isNotBlank() }.map { it.lowercase() }
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
