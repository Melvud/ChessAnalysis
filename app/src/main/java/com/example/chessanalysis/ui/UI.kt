@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.example.chessanalysis.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.example.chessanalysis.R
import com.example.chessanalysis.data.api.ApiClient
import com.example.chessanalysis.data.model.*
import com.example.chessanalysis.data.util.BoardState
import com.example.chessanalysis.repository.AnalysisViewModel
import com.example.chessanalysis.repository.GameListViewModel
import com.example.chessanalysis.repository.GameRepository
import com.example.chessanalysis.ui.theme.ChessAnalyzerTheme
import com.example.chessanalysis.ui.theme.EvalBarVertical
import com.example.chessanalysis.ui.theme.classIconRes
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt
/* --- Навигация --- */
sealed class Screen(val route: String) {
    object Login : Screen("login")
    object GameList : Screen("games/{site}/{username}")
    object Summary : Screen("summary")
    object Report : Screen("report")
}

@Composable
fun AppNavGraph(navController: NavHostController) {
    NavHost(navController, startDestination = Screen.Login.route) {
        composable(Screen.Login.route) {
            LoginScreen { site, username ->
                navController.navigate("games/${site.name}/${username}")
            }
        }

        composable(Screen.GameList.route) { backStackEntry ->
            val siteStr = backStackEntry.arguments?.getString("site")
            val user = backStackEntry.arguments?.getString("username")
            val site = siteStr?.let { runCatching { ChessSite.valueOf(it) }.getOrNull() }
            if (site == null || user.isNullOrBlank()) {
                navController.popBackStack(); return@composable
            }
            GameListScreen(site, user) { game ->
                navController.currentBackStackEntry
                    ?.savedStateHandle
                    ?.set("selectedGame", game)
                navController.navigate(Screen.Summary.route)
            }
        }

        composable(Screen.Summary.route) {
            val game: GameSummary? = navController.previousBackStackEntry
                ?.savedStateHandle
                ?.get<GameSummary>("selectedGame")
            if (game == null) {
                navController.popBackStack(); return@composable
            }
            AnalysisSummaryScreen(
                game = game,
                onViewReport = { navController.navigate(Screen.Report.route) },
                onBack = { navController.popBackStack() }
            )
        }

        composable(Screen.Report.route) {
            // Используем тот же AnalysisViewModel, чтобы подписаться на анализ
            val game: GameSummary? = navController.getBackStackEntry(Screen.Summary.route)
                .savedStateHandle
                .get<GameSummary>("selectedGame")
            if (game == null) {
                navController.popBackStack(); return@composable
            }
            ReportHost(game) { navController.popBackStack() }
        }
    }
}

/* --- Экран входа --- */
@Composable
fun LoginScreen(onLogin: (ChessSite, String) -> Unit) {
    var username by remember { mutableStateOf("") }
    var site by remember { mutableStateOf(ChessSite.LICHESS) }
    Box(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.Center)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Вход", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilterChip(
                    selected = site == ChessSite.LICHESS,
                    onClick = { site = ChessSite.LICHESS },
                    label = { Text("Lichess") }
                )
                Spacer(Modifier.width(12.dp))
                FilterChip(
                    selected = site == ChessSite.CHESS_COM,
                    onClick = { site = ChessSite.CHESS_COM },
                    label = { Text("Chess.com") }
                )
            }
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                label = { Text("Никнейм") },
                singleLine = true
            )
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = { onLogin(site, username.trim()) },
                enabled = username.isNotBlank()
            ) { Text("Загрузить партии") }
        }
    }
}

/* --- Экран со списком партий --- */
@Composable
fun GameListScreen(
    site: ChessSite,
    username: String,
    onGameSelected: (GameSummary) -> Unit
) {
    val vm: GameListViewModel = viewModel(factory = viewModelFactory {
        initializer {
            GameListViewModel(
                GameRepository(
                    ApiClient.lichessService,
                    ApiClient.chessComService,
                    ApiClient.stockfishOnlineService,
                    ApiClient.chessApiService
                )
            )
        }
    })
    val games by vm.games.collectAsState()
    val loading by vm.loading.collectAsState()
    val error by vm.error.collectAsState()
    LaunchedEffect(site, username) { vm.loadGames(site, username) }
    Scaffold(topBar = { TopAppBar(title = { Text("Ваши партии") }) }) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                error != null -> Text("Ошибка: $error", Modifier.align(Alignment.Center))
                games.isEmpty() -> Text("Ничего не найдено", Modifier.align(Alignment.Center))
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(games) { g ->
                        ElevatedCard(
                            modifier = Modifier.fillMaxWidth().clickable { onGameSelected(g) }
                        ) {
                            Column(Modifier.padding(12.dp)) {
                                Text("${g.white} — ${g.black}", style = MaterialTheme.typography.titleMedium)
                                g.result?.let { Text("Результат: $it") }
                                g.timeControl?.let { Text("Контроль: $it") }
                            }
                        }
                    }
                }
            }
        }
    }
}

/* --- Сводка анализа --- */
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
                    ApiClient.stockfishOnlineService,
                    ApiClient.chessApiService
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
                result == null -> Text("Анализируем партию…", Modifier.align(Alignment.Center))
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
    else -> Buckets.BLUN
}
private fun accuracySide(moves: List<MoveAnalysis>, isWhite: Boolean): Double {
    val sideMoves = moves.filter { (it.moveNumber % 2 == 1) == isWhite }
    if (sideMoves.isEmpty()) return 100.0
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
private fun parseElos(pgn: String): Pair<Int?, Int?> {
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
    val (wElo, bElo) = parseElos(game.pgn)
    val perfW = performanceOneGame(bElo, scoreByResult(game.result, true))
    val perfB = performanceOneGame(wElo, scoreByResult(game.result, false))
    val cntW = countsForSide(result.moves, true)
    val cntB = countsForSide(result.moves, false)
    val accW = result.summary.accuracyWhite
    val accB = result.summary.accuracyBlack
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
                    Text("Точность", style = MaterialTheme.typography.labelMedium)
                    Text("${"%.1f".format(accuracy)}%", style = MaterialTheme.typography.headlineLarge)
                }
                Column(Modifier.weight(1f)) {
                    Text("Перфоманс", style = MaterialTheme.typography.labelMedium)
                    Text(performance?.toString() ?: "—", style = MaterialTheme.typography.headlineLarge)
                }
            }
            Divider()
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Лучшие/Отлично"); Text((counts[Buckets.GREAT] ?: 0).toString())
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Хорошо"); Text((counts[Buckets.GOOD] ?: 0).toString())
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Нечётко"); Text((counts[Buckets.INA] ?: 0).toString())
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Ошибка"); Text((counts[Buckets.MIST] ?: 0).toString())
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Зевок"); Text((counts[Buckets.BLUN] ?: 0).toString())
            }
        }
    }
}

/* --- Экран отчёта. Здесь отслеживаем состояние анализа. --- */
@Composable
fun ReportHost(game: GameSummary, onBack: () -> Unit) {
    val vm: AnalysisViewModel = viewModel(factory = viewModelFactory {
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
    val result by vm.analysisResult.collectAsState()
    val loading by vm.loading.collectAsState()
    val error by vm.error.collectAsState()
    LaunchedEffect(game.id) { if (result == null) vm.analyze(game) }
    when {
        loading -> ReportAwaitScreen(onBack)
        error != null -> {
            Scaffold(topBar = {
                TopAppBar(
                    title = { Text("Детальный отчёт") },
                    navigationIcon = { TextButton(onClick = onBack) { Text("Назад") } }
                )
            }) { p -> Box(Modifier.padding(p).fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Ошибка: $error")
            }}
        }
        result == null -> ReportAwaitScreen(onBack)
        else -> ReportScreen(result!!, onBack)
    }
}

/* --- Ожидание отчёта --- */
@Composable
fun ReportAwaitScreen(onBack: () -> Unit) {
    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Детальный отчёт") },
            navigationIcon = { TextButton(onClick = onBack) { Text("Назад") } }
        )
    }) { p ->
        Box(Modifier.padding(p).fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
    }
}

/* --- Детальный отчёт: доска и список ходов. --- */
@Composable
fun ReportScreen(result: AnalysisResult, onBack: () -> Unit) {
    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Детальный отчёт") },
            navigationIcon = { TextButton(onClick = onBack) { Text("Назад") } }
        )
    }) { padding ->
        ReportContent(result, Modifier.padding(padding))
    }
}

private fun bucketOf(absDelta: Double): Buckets = when {
    absDelta < 0.05 -> Buckets.GREAT
    absDelta < 0.20 -> Buckets.GOOD
    absDelta < 0.60 -> Buckets.INA
    absDelta < 1.60 -> Buckets.MIST
    else -> Buckets.BLUN
}

@Composable
private fun ReportContent(result: AnalysisResult, modifier: Modifier = Modifier) {
    val moves = result.moves
    val context = LocalContext.current
    // Звуки ходов
    val moveSound = remember { android.media.MediaPlayer.create(context, R.raw.move) }
    val captureSound = remember { android.media.MediaPlayer.create(context, R.raw.capture) }
    val errorSound = remember { android.media.MediaPlayer.create(context, R.raw.error) }
    DisposableEffect(Unit) {
        onDispose {
            moveSound.release()
            captureSound.release()
            errorSound.release()
        }
    }

    // Строим последовательность досок
    val boards = remember(moves) {
        val seq = mutableListOf(BoardState.initial())
        var b = seq.first()
        moves.forEach { m ->
            val next = b.copy()
            val isWhite = (m.moveNumber % 2 == 1)
            next.applySan(m.san, isWhite)
            seq += next
            b = next
        }
        seq
    }

    var index by remember { mutableStateOf(moves.lastIndex) }
    Column(
        modifier.fillMaxSize().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (index in moves.indices) {
            val m = moves[index]
            val cls = when (bucketOf(abs(m.delta))) {
                Buckets.GREAT -> MoveClass.GREAT
                Buckets.GOOD  -> MoveClass.GOOD
                Buckets.INA   -> MoveClass.INACCURACY
                Buckets.MIST  -> MoveClass.MISTAKE
                Buckets.BLUN  -> MoveClass.BLUNDER
            }
            Banner(m, cls)
        }
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            val size = 320.dp
            if (index >= 0) {
                val prev = boards[index]
                val cur = boards[index + 1]
                val (from, to) = findDiff(prev, cur)
                ChessBoard(
                    board = cur,
                    lastFrom = from,
                    lastTo = to,
                    moveClass = when (bucketOf(abs(moves[index].delta))) {
                        Buckets.GREAT -> MoveClass.GREAT
                        Buckets.GOOD  -> MoveClass.GOOD
                        Buckets.INA   -> MoveClass.INACCURACY
                        Buckets.MIST  -> MoveClass.MISTAKE
                        Buckets.BLUN  -> MoveClass.BLUNDER
                    },
                    boardSize = size
                )
            } else {
                ChessBoard(board = boards.first(), lastFrom = null, lastTo = null, moveClass = null, boardSize = 320.dp)
            }
        }
        // Список ходов
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            moves.forEachIndexed { i, m ->
                val bucket = bucketOf(abs(m.delta))
                AssistChip(
                    onClick = {
                        index = i
                        // звук
                        when (bucket) {
                            Buckets.BLUN, Buckets.MIST -> errorSound.start()
                            else -> if (m.san.contains("x")) captureSound.start() else moveSound.start()
                        }
                    },
                    label = { Text(m.san) },
                    leadingIcon = {
                        Icon(
                            painter = painterResource(id = classIconRes(
                                when (bucket) {
                                    Buckets.GREAT -> MoveClass.GREAT
                                    Buckets.GOOD  -> MoveClass.GOOD
                                    Buckets.INA   -> MoveClass.INACCURACY
                                    Buckets.MIST  -> MoveClass.MISTAKE
                                    Buckets.BLUN  -> MoveClass.BLUNDER
                                }
                            )),
                            contentDescription = null
                        )
                    },
                    colors = AssistChipDefaults.assistChipColors(
                        containerColor = chipColor(bucket, selected = i == index),
                        labelColor = Color.Black,
                        leadingIconContentColor = Color.Black
                    )
                )
            }
        }
    }
}

@Composable
private fun Banner(m: MoveAnalysis, cls: MoveClass) {
    val title = when (cls) {
        MoveClass.GREAT      -> "Best / Excellent"
        MoveClass.GOOD       -> "Good"
        MoveClass.INACCURACY -> "Inaccuracy"
        MoveClass.MISTAKE    -> "Mistake"
        MoveClass.BLUNDER    -> "Blunder"
    }
    val color = when (cls) {
        MoveClass.GREAT      -> Color(0xFF66BB6A)
        MoveClass.GOOD       -> Color(0xFF43A047)
        MoveClass.INACCURACY -> Color(0xFFFFC107)
        MoveClass.MISTAKE    -> Color(0xFFFF9800)
        MoveClass.BLUNDER    -> Color(0xFFE53935)
    }
    Surface(color = color.copy(alpha = 0.15f)) {
        Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(painter = painterResource(id = classIconRes(cls)), contentDescription = null, tint = color)
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text("Лучший: ${m.bestMove ?: "—"}")
            }
            Text("Δ ${"%.2f".format(abs(m.delta))}", color = color, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun ChessBoard(
    board: BoardState,
    lastFrom: Pair<Int, Int>?,
    lastTo: Pair<Int, Int>?,
    moveClass: MoveClass?,
    modifier: Modifier = Modifier,
    boardSize: Dp = 320.dp
) {
    val light = Color(0xFFEEEED2)
    val dark = Color(0xFF769656)
    val overlay = when (moveClass) {
        MoveClass.GREAT      -> Color(0xFF00C853).copy(alpha = 0.30f)
        MoveClass.GOOD       -> Color(0xFF4CAF50).copy(alpha = 0.30f)
        MoveClass.INACCURACY -> Color(0xFFFFC107).copy(alpha = 0.30f)
        MoveClass.MISTAKE    -> Color(0xFFFF9800).copy(alpha = 0.30f)
        MoveClass.BLUNDER    -> Color(0xFFE53935).copy(alpha = 0.30f)
        null                 -> Color.Transparent
    }
    Box(
        modifier
            .size(boardSize)
            .background(Color.Transparent),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val cell = min(size.width, size.height) / 8f
            for (r in 0..7) {
                for (c in 0..7) {
                    val col = if ((r + c) % 2 == 0) light else dark
                    drawRect(
                        color = col,
                        topLeft = Offset(c * cell, (7 - r) * cell),
                        size = Size(cell, cell)
                    )
                }
            }
            fun drawOverlay(rc: Pair<Int,Int>?) {
                if (rc == null) return
                val (r,c) = rc
                drawRect(
                    color = overlay,
                    topLeft = Offset(c * cell, (7 - r) * cell),
                    size = Size(cell, cell),
                    style = Fill
                )
                drawRect(
                    color = overlay.copy(alpha = 0.8f),
                    topLeft = Offset(c * cell, (7 - r) * cell),
                    size = Size(cell, cell),
                    style = Stroke(width = 2f)
                )
            }
            drawOverlay(lastFrom); drawOverlay(lastTo)
        }
        val glyphStyle = androidx.compose.ui.text.TextStyle(fontSize = (boardSize.value / 10f).sp)
        Column(Modifier.fillMaxSize()) {
            for (rank in 7 downTo 0) {
                Row(Modifier.weight(1f)) {
                    for (file in 0..7) {
                        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                            val ch = board.at(rank, file)
                            val g = glyphFor(ch)
                            if (g != null) {
                                val isWhite = ch.isUpperCase()
                                Text(
                                    text = g.toString(),
                                    style = glyphStyle,
                                    color = if (isWhite) Color.Black else Color(0xFF111111)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun glyphFor(ch: Char): Char? = when (ch) {
    'K' -> '♔'; 'Q' -> '♕'; 'R' -> '♖'; 'B' -> '♗'; 'N' -> '♘'; 'P' -> '♙'
    'k' -> '♚'; 'q' -> '♛'; 'r' -> '♜'; 'b' -> '♝'; 'n' -> '♞'; 'p' -> '♟'
    else -> null
}

private fun chipColor(b: Buckets, selected: Boolean): Color = when (b) {
    Buckets.GREAT -> if (selected) Color(0xFFB9F6CA) else Color(0x80B9F6CA)
    Buckets.GOOD  -> if (selected) Color(0xFFA5D6A7) else Color(0x80A5D6A7)
    Buckets.INA   -> if (selected) Color(0xFFFFECB3) else Color(0x80FFECB3)
    Buckets.MIST  -> if (selected) Color(0xFFFFCC80) else Color(0x80FFCC80)
    Buckets.BLUN  -> if (selected) Color(0xFFEF9A9A) else Color(0x80EF9A9A)
}

/** Найти различия между двумя досками. */
private fun findDiff(a: BoardState, b: BoardState): Pair<Pair<Int,Int>?, Pair<Int,Int>?> {
    var from: Pair<Int,Int>? = null; var to: Pair<Int,Int>? = null
    for (r in 0..7) for (c in 0..7) {
        val x = a.at(r,c); val y = b.at(r,c)
        if (x != y) {
            if (x != '.' && y == '.') from = r to c
            if (x == '.' && y != '.') to = r to c
        }
    }
    return from to to
}
