package com.example.chessanalysis.ui

import android.content.res.AssetManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.chessanalysis.*
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private sealed class Screen {
    object Login : Screen()
    data class Games(val list: List<GameHeader>, val provider: Provider, val username: String) : Screen()
    object Loading : Screen()
    data class Report(val report: FullReport, val provider: Provider, val username: String) : Screen()
}

@Composable
fun AppRoot(mod: Modifier = Modifier) {
    var screen by remember { mutableStateOf<Screen>(Screen.Login) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val openingFens by remember { mutableStateOf(loadOpeningsFens(ctx.assets)) }

    when (val s = screen) {
        is Screen.Login -> LoginScreen { provider, username ->
            scope.launch {
                screen = Screen.Loading
                val games = when (provider) {
                    Provider.LICHESS -> GameLoaders.loadLichess(username)
                    Provider.CHESSCOM -> GameLoaders.loadChessCom(username)
                }
                screen = Screen.Games(games, provider, username)
            }
        }

        is Screen.Games -> GamesScreen(
            provider = s.provider,
            username = s.username,
            games = s.list,
            onBack = { screen = Screen.Login },
            onOpen = { g ->
                scope.launch {
                    screen = Screen.Loading
                    val moves = PgnChess.movesWithFens(g.pgn)
                    val positions = analyzeGameToPositions(moves)
                    if (positions.isEmpty()) {
                        // Не удалось построить позиции — вернёмся к списку с сообщением
                        screen = Screen.Games(s.list, s.provider, s.username)
                        return@launch
                    }
                    val acc = AccuracyCalc.compute(positions)
                    val acpl = AcplCalc.compute(positions)
                    val est = PerformanceElo.estimate(acpl, g.whiteElo, g.blackElo)
                    val classified = MoveClassifier.classifyAll(g, positions, moves, openingFens)
                    val report = FullReport(g, positions, classified, acc, acpl, est)
                    screen = Screen.Report(report, s.provider, s.username)
                }
            }
        )

        Screen.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }

        is Screen.Report -> ReportScreen(
            report = s.report,
            onBack = {
                // Вернуться к списку для того же логина
                scope.launch {
                    val games = when (s.provider) {
                        Provider.LICHESS -> GameLoaders.loadLichess(s.username)
                        Provider.CHESSCOM -> GameLoaders.loadChessCom(s.username)
                    }
                    screen = Screen.Games(games, s.provider, s.username)
                }
            }
        )
    }
}

@Composable
private fun LoginScreen(onLoad: (Provider, String) -> Unit) {
    var provider by remember { mutableStateOf(Provider.LICHESS) }
    var username by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Chess Analysis", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = provider == Provider.LICHESS, onClick = { provider = Provider.LICHESS }, label = { Text("Lichess") })
            FilterChip(selected = provider == Provider.CHESSCOM, onClick = { provider = Provider.CHESSCOM }, label = { Text("Chess.com") })
        }
        OutlinedTextField(value = username, onValueChange = { username = it.trim() }, label = { Text("Логин") }, singleLine = true)
        Button(enabled = username.isNotBlank(), onClick = { onLoad(provider, username) }) { Text("Загрузить последние партии") }
    }
}

@Composable
private fun GamesScreen(
    provider: Provider,
    username: String,
    games: List<GameHeader>,
    onBack: () -> Unit,
    onOpen: (GameHeader) -> Unit
) {
    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Партии: $username (${provider.name.lowercase()})", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            TextButton(onClick = onBack) { Text("Назад") }
        }
        if (games.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Партии не найдены") }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                itemsIndexed(games, key = { idx, _ -> idx }) { _, g ->
                    GameCard(g) { onOpen(g) }
                }
            }
        }
    }
}

@Composable
private fun GameCard(h: GameHeader, onOpen: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable { onOpen() }.padding(12.dp)
    ) {
        val w = listOfNotNull(h.white, h.whiteElo?.let { "($it)" }).joinToString(" ")
        val b = listOfNotNull(h.black, h.blackElo?.let { "($it)" }).joinToString(" ")
        Text("${w.ifBlank { "—" }} vs ${b.ifBlank { "—" }}", fontWeight = FontWeight.Bold)
        val metaL = listOfNotNull(h.date, h.result).filter { it.isNotBlank() }.joinToString("   ")
        if (metaL.isNotBlank()) Text(metaL)
        val metaR = listOfNotNull(h.opening, h.eco).filter { it.isNotBlank() }.joinToString(" — ")
        if (metaR.isNotBlank()) Text(metaR)
        Text("Источник: ${h.site}")
    }
}

@Composable
private fun ReportScreen(report: FullReport, onBack: () -> Unit) {
    // Защита от пустых позиций
    if (report.positions.isEmpty()) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Не удалось построить позиции для отчёта.")
            Spacer(Modifier.height(8.dp))
            Button(onClick = onBack) { Text("Назад") }
        }
        return
    }

    var ply by remember { mutableStateOf(0) }
    val maxPly = (report.positions.size - 1).coerceAtLeast(0)
    if (ply > maxPly) ply = maxPly

    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Text("Отчёт по партии", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            TextButton(onClick = onBack) { Text("Назад") }
        }
        Text("Accuracy: White ${"%.1f".format(report.accuracy.whiteAcc)}% • Black ${"%.1f".format(report.accuracy.blackAcc)}%")
        Text("ACPL: White ${"%.1f".format(report.acpl.whiteAcpl)} • Black ${"%.1f".format(report.acpl.blackAcpl)}")
        Text("Estimated Elo: White ${report.estimatedElo.whiteEst ?: "-"} • Black ${report.estimatedElo.blackEst ?: "-"}")

        ChessBoard(fen = report.positions[ply].fen, label = "Позиция ${ply}/${maxPly}")

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = ply > 0, onClick = { ply = 0 }) { Text("|<") }
            Button(enabled = ply > 0, onClick = { ply-- }) { Text("<") }
            Button(enabled = ply < maxPly, onClick = { ply++ }) { Text(">") }
            Button(enabled = ply < maxPly, onClick = { ply = maxPly }) { Text(">|") }
        }

        Divider()

        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.weight(1f)) {
            itemsIndexed(report.moves, key = { idx, _ -> idx }) { idx, m ->
                val tags = if (m.tags.isEmpty()) "" else " [" + m.tags.joinToString(", ") + "]"
                Text("${(idx+1).toString().padStart(3)}. ${m.san.padEnd(8)}  —  ${m.classification}${tags}  •  Acc ${"%.1f".format(m.accuracy)}%")
            }
        }
    }
}

/** === Вспомогалки: openings.json + отрисовка доски === */

private fun loadOpeningsFens(assets: AssetManager): Set<String> {
    return try {
        val txt = assets.open("openings.json").bufferedReader().use { it.readText() }
        val arr = Json.parseToJsonElement(txt) as JsonArray
        arr.mapNotNull { it.jsonObject["fen"]?.jsonPrimitive?.content }.toSet()
    } catch (e: Exception) { emptySet() }
}

@Composable
private fun ChessBoard(fen: String, label: String) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, fontWeight = FontWeight.Medium)
        val board = parseFenToBoardArray(fen)
        val light = Color(0xFFE0CBA8)
        val dark = Color(0xFF7A9456)
        Column(Modifier.size(320.dp)) {
            for (rank in 7 downTo 0) {
                Row(Modifier.weight(1f)) {
                    for (file in 0..7) {
                        val squareColor = if ((rank + file) % 2 == 0) light else dark
                        Box(
                            Modifier.weight(1f).fillMaxHeight()
                                .background(squareColor)
                                .border(0.5.dp, Color.Black.copy(alpha=0.05f))
                        ) {
                            val piece = board[rank][file]
                            if (piece != null) {
                                val path = "file:///android_asset/fresca/${piece}.svg"
                                AsyncImage(model = path, contentDescription = piece, modifier = Modifier.fillMaxSize().padding(2.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun parseFenToBoardArray(fen: String): Array<Array<String?>> {
    val board = Array(8) { arrayOfNulls<String>(8) }
    val rows = fen.split(" ").first().split("/")
    for (r in 0 until 8) {
        var c = 0
        for (ch in rows[r]) {
            if (ch.isDigit()) c += (ch.code - '0'.code)
            else {
                val isWhite = ch.isUpperCase()
                val name = when (ch.lowercaseChar()) {
                    'k' -> "K"; 'q' -> "Q"; 'r' -> "R"; 'b' -> "B"; 'n' -> "N"; 'p' -> "P"
                    else -> null
                }
                board[7 - r][c] = (if (isWhite) "w" else "b") + name
                c++
            }
        }
    }
    return board
}
