@file:OptIn(ExperimentalMaterial3Api::class)

package com.example.chessanalysis.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import coil.compose.AsyncImage
import coil.decode.SvgDecoder
import coil.request.ImageRequest
import com.example.chessanalysis.data.model.*
import com.example.chessanalysis.opening.OpeningBook
import com.example.chessanalysis.repository.AnalysisViewModel
import com.example.chessanalysis.repository.GameListViewModel

/* Палитра доски */
private val Light = Color(0xFFECECDC)
private val Dark = Color(0xFF769656)

/* ===== Навигация ===== */

@Composable
fun AppNav(
    listVm: GameListViewModel,
    analysisVm: AnalysisViewModel
) {
    val nav = rememberNavController()
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { OpeningBook.ensureLoaded(ctx) }

    NavHost(navController = nav, startDestination = "login") {

        composable("login") {
            LoginScreen(
                onGo = { site, username ->
                    nav.navigate("games/${site.name}/${username}") { launchSingleTop = true }
                }
            )
        }

        composable(
            route = "games/{site}/{username}",
            arguments = listOf(
                navArgument("site") { type = NavType.StringType },
                navArgument("username") { type = NavType.StringType }
            )
        ) { back ->
            val siteStr = back.arguments?.getString("site")!!
            val username = back.arguments?.getString("username")!!
            val site = ChessSite.valueOf(siteStr)

            GameListScreen(
                vm = listVm,
                site = site,
                username = username,
                onOpen = { game -> nav.navigate("summary/${game.id}") { launchSingleTop = true } },
                onBack = { nav.popBackStack() }
            )
        }

        composable(
            route = "summary/{id}",
            arguments = listOf(navArgument("id") { type = NavType.StringType })
        ) { back ->
            val id = back.arguments?.getString("id")!!
            val game = listVm.games.collectAsState().value.first { it.id == id }
            SummaryScreen(
                game = game,
                vm = analysisVm,
                onDetail = { nav.navigate("detail/$id") { launchSingleTop = true } },
                onBack = { nav.popBackStack() }
            )
        }

        composable(
            route = "detail/{id}",
            arguments = listOf(navArgument("id") { type = NavType.StringType })
        ) { back ->
            val id = back.arguments?.getString("id")!!
            val game = listVm.games.collectAsState().value.first { it.id == id }
            DetailScreen(game = game, vm = analysisVm) { nav.popBackStack() }
        }
    }
}

/* ===== Экран логина / старта ===== */

@Composable
private fun LoginScreen(onGo: (ChessSite, String) -> Unit) {
    var username by remember { mutableStateOf("") }
    var site by remember { mutableStateOf(ChessSite.LICHESS) }

    Scaffold(topBar = { TopAppBar(title = { Text("Шахматный анализатор") }) }) { pad ->
        Column(
            modifier = Modifier.padding(pad).fillMaxSize().padding(20.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Введите ник и выберите сайт", fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = username,
                onValueChange = { username = it.trim() },
                label = { Text("Ник (Lichess / Chess.com)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = site == ChessSite.LICHESS,
                    onClick = { site = ChessSite.LICHESS },
                    label = { Text("Lichess") }
                )
                FilterChip(
                    selected = site == ChessSite.CHESS_COM,
                    onClick = { site = ChessSite.CHESS_COM },
                    label = { Text("Chess.com") }
                )
            }
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = { if (username.isNotBlank()) onGo(site, username) },
                enabled = username.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) { Text("Загрузить партии") }
        }
    }
}

/* ===== Список партий ===== */

@Composable
private fun GameListScreen(
    vm: GameListViewModel,
    site: ChessSite,
    username: String,
    onOpen: (GameSummary) -> Unit,
    onBack: () -> Unit
) {
    val games by vm.games.collectAsState()
    val loading by vm.loading.collectAsState()
    val err by vm.error.collectAsState()

    // Загружаем при входе на экран
    LaunchedEffect(site, username) { vm.loadGames(site, username) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("${site.name.lowercase().replaceFirstChar { it.titlecase() }} • $username") }) }
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            if (loading) CircularProgressIndicator(Modifier.align(Alignment.Center))
            if (err != null) Text(err ?: "", color = Color.Red, modifier = Modifier.align(Alignment.Center))
            LazyColumn(Modifier.fillMaxSize().padding(12.dp)) {
                itemsIndexed(games) { _, g ->
                    Card(
                        Modifier.fillMaxWidth().padding(vertical = 6.dp).clickable { onOpen(g) },
                        shape = RoundedCornerShape(16)
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text("${g.white} — ${g.black}", fontWeight = FontWeight.Bold)
                            Text("Result: ${g.result ?: "-"}  •  TC: ${g.timeControl ?: "-"}")
                        }
                    }
                }
            }
        }
    }
}

/* ===== Сводка партии ===== */

@Composable
private fun SummaryScreen(
    game: GameSummary,
    vm: AnalysisViewModel,
    onDetail: () -> Unit,
    onBack: () -> Unit
) {
    val res by vm.analysisResult.collectAsState()
    val loading by vm.loading.collectAsState()
    val err by vm.error.collectAsState()

    LaunchedEffect(game.id) { vm.analyze(game) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Отчёт о партии") }) },
        bottomBar = {
            Button(
                onClick = onDetail,
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                enabled = res != null && !loading
            ) { Text("Смотреть детальный отчёт") }
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().padding(16.dp)) {
            if (loading) { CircularProgressIndicator() }
            err?.let { Text(it, color = Color.Red) }
            res?.let { r ->
                Row(Modifier.fillMaxWidth()) {
                    StatCard(
                        name = game.white,
                        acc = r.summary.accuracyWhite,
                        acpl = r.summary.acplWhite,
                        perf = r.summary.perfWhite,
                        modifier = Modifier.weight(1f)
                    )
                    StatCard(
                        name = game.black,
                        acc = r.summary.accuracyBlack,
                        acpl = r.summary.acplBlack,
                        perf = r.summary.perfBlack,
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(Modifier.height(8.dp))
                r.summary.opening?.let { Text("Дебют: ${it.eco} • ${it.name}") }
                Spacer(Modifier.height(8.dp))
                Counts(r.summary.counts)
            }
        }
    }
}

@Composable
private fun StatCard(name: String, acc: Double, acpl: Double, perf: Int?, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.padding(4.dp),
        shape = RoundedCornerShape(16)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(name, fontWeight = FontWeight.Bold)
            Text("Точность: ${"%.1f".format(acc)}%")
            Text("ACPL: ${"%.1f".format(acpl)}")
            Text("Перфоманс: ${perf ?: "-"}")
        }
    }
}

@Composable
private fun Counts(map: Map<MoveClass, Int>) {
    val order = listOf(
        MoveClass.SPLENDID to "Блестящий ход",
        MoveClass.PERFECT to "Замечательный",
        MoveClass.BEST to "Лучшие",
        MoveClass.EXCELLENT to "Отлично",
        MoveClass.OKAY to "Хорошо",
        MoveClass.OPENING to "Теоретический ход",
        MoveClass.INACCURACY to "Неточность",
        MoveClass.MISTAKE to "Ошибка",
        MoveClass.BLUNDER to "Зевок"
    )
    Column {
        order.forEach { (k, title) ->
            val v = map[k] ?: 0
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(title); Text("$v")
            }
        }
    }
}

/* ===== Детальный отчёт с доской (SVG ассеты) ===== */

@Composable
private fun DetailScreen(game: GameSummary, vm: AnalysisViewModel, onBack: () -> Unit) {
    val res by vm.analysisResult.collectAsState()
    Scaffold(
        topBar = { TopAppBar(title = { Text("Отчёт о партии") }) }
    ) { pad ->
        if (res == null) {
            Box(Modifier.padding(pad).fillMaxSize()) {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            }
            return@Scaffold
        }
        val moves = res!!.moves
        var index by remember { mutableStateOf(0) }
        val board = remember(index) { ReconstructBoard(moves, index) }

        Column(Modifier.padding(pad).fillMaxSize()) {
            ChessBoard(board)
            Spacer(Modifier.height(8.dp))
            LazyColumn(Modifier.weight(1f)) {
                itemsIndexed(moves) { i, m ->
                    Row(
                        Modifier.fillMaxWidth().clickable { index = i }.padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("${m.moveNumber}. ${m.san}")
                        Text(
                            text = when (m.classification) {
                                MoveClass.SPLENDID -> "Блестящий"
                                MoveClass.PERFECT -> "Замечательный"
                                MoveClass.BEST -> "Лучший"
                                MoveClass.EXCELLENT, MoveClass.GREAT -> "Отлично"
                                MoveClass.OKAY, MoveClass.GOOD -> "Хорошо"
                                MoveClass.OPENING -> "Теория"
                                MoveClass.INACCURACY -> "Неточность"
                                MoveClass.MISTAKE -> "Ошибка"
                                MoveClass.BLUNDER -> "Зевок"
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ChessBoard(board: Array<CharArray>, cell: Dp = 44.dp) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
    ) {
        for (r in 7 downTo 0) {
            Row(Modifier.fillMaxWidth().height(cell)) {
                for (f in 0..7) {
                    val dark = (r + f) % 2 == 1
                    Box(
                        Modifier
                            .width(cell).height(cell)
                            .background(if (dark) Dark else Light),
                        contentAlignment = Alignment.Center
                    ) {
                        val p = board[r][f]
                        if (p != '.') PieceSvg(p)
                    }
                }
            }
        }
    }
}

@Composable
private fun PieceSvg(ch: Char) {
    val name = when (ch) {
        'K' -> "wK.svg"; 'Q' -> "wQ.svg"; 'R' -> "wR.svg"; 'B' -> "wB.svg"; 'N' -> "wN.svg"; 'P' -> "wP.svg"
        'k' -> "bK.svg"; 'q' -> "bQ.svg"; 'r' -> "bR.svg"; 'b' -> "bB.svg"; 'n' -> "bN.svg"; 'p' -> "bP.svg"
        else -> null
    } ?: return
    val ctx = LocalContext.current
    val req = ImageRequest.Builder(ctx)
        .data("file:///android_asset/fresca/$name")
        .decoderFactory(SvgDecoder.Factory())
        .build()
    AsyncImage(model = req, contentDescription = null, contentScale = ContentScale.Fit)
}

/* Восстановление позиции после N полуходов (минимальная заглушка).
   Для 100% синхронизации рекомендую добавить uci в MoveAnalysis. */
private fun ReconstructBoard(moves: List<MoveAnalysis>, idx: Int): Array<CharArray> {
    val b = Array(8) { CharArray(8) { '.' } }
    b[0] = charArrayOf('R','N','B','Q','K','B','N','R')
    b[1] = charArrayOf('P','P','P','P','P','P','P','P')
    b[6] = charArrayOf('p','p','p','p','p','p','p','p')
    b[7] = charArrayOf('r','n','b','q','k','b','n','r')
    return b
}
