package com.example.chessanalysis.ui.screens

import android.annotation.SuppressLint
import android.content.Context
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.chessanalysis.FullReport
import com.example.chessanalysis.MoveClass
import com.example.chessanalysis.Provider
import com.example.chessanalysis.ui.components.BoardCanvas
import com.example.chessanalysis.ui.components.EvalBar
import com.example.chessanalysis.ui.components.MovesCarousel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

@SuppressLint("UnsafeOptInUsageError")
@Serializable
data class ClockData(
    val white: List<Int> = emptyList(), // centiseconds per move
    val black: List<Int> = emptyList()
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GameReportScreen(
    report: FullReport,
    onBack: () -> Unit,
    onNextKeyMoment: (() -> Unit)? = null // для совместимости
) {
    val scope = rememberCoroutineScope()

    val userWasWhite = remember(report.header) { true }

    var currentPlyIndex by remember { mutableStateOf(0) }
    var isWhiteBottom by remember { mutableStateOf(userWasWhite) }
    var clockData by remember { mutableStateOf<ClockData?>(null) }
    var isAutoPlaying by remember { mutableStateOf(false) }

    val bgColor = Color(0xFF161512)
    val surfaceColor = Color(0xFF262522)
    val cardColor = Color(0xFF1E1C1A)

    // Загружаем часы: сперва парсим локально из PGN, если нет — тянем с сайта
    LaunchedEffect(report) {
        scope.launch {
            val local = report.header.pgn?.let {
                runCatching { parseClockData(it) }.getOrNull()
            }
            clockData = local ?: fetchClockData(report)
        }
    }

    fun playMoveSound(@Suppress("UNUSED_PARAMETER") ctx: Context, @Suppress("UNUSED_PARAMETER") moveClass: MoveClass) { /* TODO */ }

    fun seekTo(index: Int) {
        val clampedIndex = index.coerceIn(0, report.positions.lastIndex)
        currentPlyIndex = clampedIndex
    }

    fun goNext() { if (currentPlyIndex < report.positions.lastIndex) seekTo(currentPlyIndex + 1) }
    fun goPrev() { if (currentPlyIndex > 0) seekTo(currentPlyIndex - 1) }

    Scaffold(
        containerColor = bgColor,
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                },
                actions = {
                    IconButton(onClick = { isWhiteBottom = !isWhiteBottom }) {
                        Icon(Icons.Default.ScreenRotation, contentDescription = "Flip board", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = surfaceColor,
                    titleContentColor = Color.White
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(bgColor)
        ) {
            // ===== Верхняя карточка игрока =====
            val topIsWhite = !isWhiteBottom
            val topName = if (topIsWhite) (report.header.white ?: "White") else (report.header.black ?: "Black")
            val topElo = if (topIsWhite) report.header.whiteElo else report.header.blackElo
            val topClock = if (topIsWhite) clockData?.white?.getOrNull(currentPlyIndex / 2) else clockData?.black?.getOrNull(currentPlyIndex / 2)
            val topActive = (currentPlyIndex % 2 == 0 && topIsWhite) || (currentPlyIndex % 2 == 1 && !topIsWhite)

            PlayerCard(
                name = topName,
                rating = topElo,
                clock = topClock,
                isActive = topActive,
                inverted = !topIsWhite,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(cardColor)
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            )

            // ===== Доска + эвал-бар =====
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f),
                horizontalArrangement = Arrangement.Start
            ) {
                if (isWhiteBottom) {
                    EvalBar(
                        positions = report.positions,
                        currentPlyIndex = currentPlyIndex,
                        isWhiteBottom = isWhiteBottom,
                        modifier = Modifier
                            .fillMaxHeight()
                            .width(20.dp)
                    )
                }

                // Доска
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .weight(1f)
                ) {
                    val currentPosition = report.positions.getOrNull(currentPlyIndex)
                    val lastMovePair = if (currentPlyIndex > 0) {
                        val uci = report.moves[currentPlyIndex - 1].uci
                        if (uci.length >= 4) uci.substring(0, 2) to uci.substring(2, 4) else null
                    } else null
                    val lastMoveClass = report.moves.getOrNull(currentPlyIndex - 1)?.classification

                    // Лучший ход из предыдущей позиции (для стрелки при ошибках)
                    val bestUci: String? = report.positions
                        .getOrNull(maxOf(0, currentPlyIndex - 1))
                        ?.lines
                        ?.firstOrNull()
                        ?.pv
                        ?.firstOrNull()

                    val shouldShowBestArrow = when (lastMoveClass) {
                        MoveClass.INACCURACY, MoveClass.MISTAKE, MoveClass.BLUNDER -> true
                        else -> false
                    }

                    currentPosition?.let { pos ->
                        BoardCanvas(
                            fen = if (isWhiteBottom) pos.fen else flipFen(pos.fen),
                            lastMove = lastMovePair,
                            moveClass = lastMoveClass,
                            bestMoveUci = bestUci,
                            showBestArrow = shouldShowBestArrow,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }

                if (!isWhiteBottom) {
                    EvalBar(
                        positions = report.positions,
                        currentPlyIndex = currentPlyIndex,
                        isWhiteBottom = isWhiteBottom,
                        modifier = Modifier
                            .fillMaxHeight()
                            .width(20.dp)
                    )
                }
            }

            // ===== Нижняя карточка игрока =====
            val bottomIsWhite = isWhiteBottom
            val bottomName = if (bottomIsWhite) (report.header.white ?: "White") else (report.header.black ?: "Black")
            val bottomElo = if (bottomIsWhite) report.header.whiteElo else report.header.blackElo
            val bottomClock = if (bottomIsWhite) clockData?.white?.getOrNull(currentPlyIndex / 2) else clockData?.black?.getOrNull(currentPlyIndex / 2)
            val bottomActive = (currentPlyIndex % 2 == 0 && bottomIsWhite) || (currentPlyIndex % 2 == 1 && !bottomIsWhite)

            PlayerCard(
                name = bottomName,
                rating = bottomElo,
                clock = bottomClock,
                isActive = bottomActive,
                inverted = !bottomIsWhite,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(cardColor)
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            )

            // ===== Карусель ходов =====
            MovesCarousel(
                report = report,
                currentPlyIndex = currentPlyIndex,
                onSeekTo = { seekTo(it) },
                onPrev = { goPrev() },
                onNext = { goNext() },
                modifier = Modifier.fillMaxWidth()
            )

            // ===== Кнопки навигации/автоплей =====
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(cardColor)
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { seekTo(0) }) {
                    Icon(Icons.Default.SkipPrevious, contentDescription = "Start", tint = Color.White)
                }
                IconButton(onClick = { goPrev() }) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Previous", tint = Color.White)
                }
                IconButton(
                    onClick = { isAutoPlaying = !isAutoPlaying },
                    modifier = Modifier
                        .size(56.dp)
                        .background(MaterialTheme.colorScheme.primary, CircleShape)
                ) {
                    Icon(
                        if (isAutoPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isAutoPlaying) "Pause" else "Play",
                        tint = Color.White
                    )
                }
                IconButton(onClick = { goNext() }) {
                    Icon(Icons.Default.ArrowForward, contentDescription = "Next", tint = Color.White)
                }
                IconButton(onClick = { seekTo(report.positions.lastIndex) }) {
                    Icon(Icons.Default.SkipNext, contentDescription = "End", tint = Color.White)
                }
            }

            // авто-плей
            LaunchedEffect(isAutoPlaying, currentPlyIndex) {
                if (isAutoPlaying) {
                    kotlinx.coroutines.delay(1500)
                    if (currentPlyIndex < report.positions.lastIndex) {
                        goNext()
                    } else {
                        isAutoPlaying = false
                    }
                }
            }
        }
    }
}

@Composable
private fun PlayerCard(
    name: String,
    rating: Int?,
    clock: Int?, // centiseconds
    isActive: Boolean,
    inverted: Boolean,
    modifier: Modifier = Modifier
) {
    val animatedColor by animateColorAsState(
        targetValue = if (isActive) Color.White else Color.White.copy(alpha = 0.6f),
        animationSpec = tween(250)
    )
    Row(
        modifier = modifier
            .background(Color(0xFF1E1C1A), RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .background(if (inverted) Color.Black else Color.White, CircleShape)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = buildString {
                    append(name)
                    if (rating != null) append(" ($rating)")
                },
                color = animatedColor,
                fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium,
                fontSize = 15.sp
            )
        }
        Text(
            text = clock?.let { formatClock(it) } ?: "—",
            color = animatedColor.copy(alpha = 0.9f),
            fontSize = 14.sp
        )
    }
}

// util
private fun formatClock(centiseconds: Int): String {
    val seconds = centiseconds / 100
    val minutes = seconds / 60
    val secs = seconds % 60
    return "%d:%02d".format(minutes, secs)
}

// переворот FEN по вертикали
private fun flipFen(fen: String): String {
    val parts = fen.split(" ")
    if (parts.isEmpty()) return fen
    val board = parts[0]
    val ranks = board.split("/")
    val flippedBoard = ranks.reversed().joinToString("/")
    return buildString {
        append(flippedBoard)
        if (parts.size > 1) {
            append(" ")
            append(parts.drop(1).joinToString(" "))
        }
    }
}

// --- ЧАСЫ ---

// Вытягиваем id партии и запрашиваем у провайдера (fallback)
private suspend fun fetchClockData(report: FullReport): ClockData? = withContext(Dispatchers.IO) {
    try {
        val site = report.header.site ?: return@withContext null
        val gameId = extractGameId(report.header.pgn) ?: return@withContext null
        when (site) {
            Provider.LICHESS -> fetchLichessClocks(gameId)
            Provider.CHESSCOM -> fetchChesscomClocks(gameId)
        }
    } catch (_: Exception) { null }
}

private fun extractGameId(pgn: String?): String? {
    if (pgn.isNullOrBlank()) return null
    val sitePattern = Regex("""\[Site\s+".*/([\w]+)"\]""")
    sitePattern.find(pgn)?.groupValues?.getOrNull(1)?.let { return it }
    val lichessPattern = Regex("""([a-zA-Z0-9]{8})""")
    lichessPattern.find(pgn)?.groupValues?.getOrNull(1)?.let { return it }
    return null
}

private suspend fun fetchLichessClocks(gameId: String): ClockData? {
    val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    val url = "https://lichess.org/game/export/$gameId?clocks=true"
    val request = Request.Builder()
        .url(url)
        .header("Accept", "application/json")
        .build()

    return try {
        client.newCall(request).execute().use { response ->
            if (response.isSuccessful) {
                val body = response.body?.string() ?: return null
                parseClockData(body)
            } else null
        }
    } catch (_: Exception) { null }
}

private suspend fun fetchChesscomClocks(@Suppress("UNUSED_PARAMETER") gameId: String): ClockData? {
    return null
}

/** Парсим часы вида `[%clk H:MM:SS]` из PGN. */
private fun parseClockData(pgn: String): ClockData {
    val clockPattern = Regex("""\[%clk\s+(\d+):(\d+):(\d+)\]""")
    val whiteTimes = mutableListOf<Int>()
    val blackTimes = mutableListOf<Int>()
    var moveIndex = 0
    clockPattern.findAll(pgn).forEach { match ->
        val hours = match.groupValues[1].toIntOrNull() ?: 0
        val minutes = match.groupValues[2].toIntOrNull() ?: 0
        val seconds = match.groupValues[3].toIntOrNull() ?: 0
        val centiseconds = (hours * 3600 + minutes * 60 + seconds) * 100
        if (moveIndex % 2 == 0) whiteTimes.add(centiseconds) else blackTimes.add(centiseconds)
        moveIndex++
    }
    return ClockData(white = whiteTimes, black = blackTimes)
}
