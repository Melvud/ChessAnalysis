package com.github.movesense.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.github.movesense.AccByColor
import com.github.movesense.FullReport
import com.github.movesense.MoveClass
import com.github.movesense.net.AvatarRepository
import kotlin.collections.get
import com.github.movesense.R

private val ScreenBg = Color(0xFF121212)
private val CardBg   = Color(0xFF2B2A27)
private val DividerC = Color(0xFF3A3936)
private val ChessComGreen = Color(0xFF59C156)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportScreen(
    report: FullReport,
    onBack: () -> Unit,
    onOpenBoard: (() -> Unit)? = null
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.game_report), color = Color.White) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.Default.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                            tint = Color.White
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = ScreenBg,
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White
                )
            )
        },
        containerColor = ScreenBg
    ) { padding ->
        val scroll = rememberScrollState()
        Column(
            Modifier
                .fillMaxSize()
                .background(ScreenBg)
                .verticalScroll(scroll)
                .padding(padding)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 1) Перфоманс
            Card(
                colors = CardDefaults.cardColors(containerColor = CardBg),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                    Text(
                        stringResource(R.string.performance_evaluation),
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                report.header.white ?: stringResource(R.string.white),
                                color = Color.LightGray,
                                style = MaterialTheme.typography.bodySmall
                            )
                            Spacer(Modifier.height(4.dp))
                            StatBox(value = report.estimatedElo.whiteEst?.toString() ?: "—", dark = false)
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                report.header.black ?: stringResource(R.string.black),
                                color = Color.LightGray,
                                style = MaterialTheme.typography.bodySmall
                            )
                            Spacer(Modifier.height(4.dp))
                            StatBox(value = report.estimatedElo.blackEst?.toString() ?: "—", dark = true)
                        }
                    }
                }
            }

            // 2) Точность игроков + АВАТАРЫ
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(CardBg, RoundedCornerShape(12.dp))
                    .padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                PlayerColumnWithAvatar(
                    name = report.header.white ?: stringResource(R.string.white),
                    acc = report.accuracy.whiteMovesAcc,
                    providerHint = guessProvider(report),
                    inverted = false
                )
                Spacer(Modifier.width(16.dp))
                PlayerColumnWithAvatar(
                    name = report.header.black ?: stringResource(R.string.black),
                    acc = report.accuracy.blackMovesAcc,
                    providerHint = guessProvider(report),
                    inverted = true
                )
            }

            // 3) Классификация ходов
            ClassificationTable(report)

            // 4) График
            EvalSparkline(report)

            // 5) Кнопка
            Spacer(Modifier.height(4.dp))
            Button(
                onClick = { onOpenBoard?.invoke() ?: onBack() },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = ChessComGreen,
                    contentColor = Color.White
                )
            ) {
                Text(
                    if (onOpenBoard == null) {
                        stringResource(R.string.return_back)
                    } else {
                        stringResource(R.string.view_report)
                    }
                )
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}

/* ================= Аватарка с фолбэком-инициалом ================= */

private enum class ProviderHint { LICHESS, CHESSCOM, UNKNOWN }

@Composable
private fun PlayerColumnWithAvatar(
    name: String,
    acc: AccByColor,
    providerHint: ProviderHint,
    inverted: Boolean
) {
    val context = LocalContext.current
    var avatarUrl by remember(name, providerHint) { mutableStateOf<String?>(null) }
    var loadFailed by remember(name, providerHint) { mutableStateOf(false) }

    // Подгружаем URL аватара
    LaunchedEffect(name, providerHint) {
        val u = name.trim()
        if (u.isBlank()) {
            avatarUrl = null
            return@LaunchedEffect
        }
        avatarUrl = when (providerHint) {
            ProviderHint.CHESSCOM -> AvatarRepository.fetchChessComAvatar(u)
            ProviderHint.LICHESS  -> AvatarRepository.fetchLichessAvatar(u)
            ProviderHint.UNKNOWN  -> null
        }
        loadFailed = false
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        if (avatarUrl.isNullOrBlank() || loadFailed) {
            InitialAvatar(name = name, size = 48.dp)
        } else {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(avatarUrl)
                    .crossfade(true)
                    .build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(48.dp),
                onError = { loadFailed = true },
                onLoading = { /* no-op */ },
                onSuccess = { loadFailed = false },
                placeholder = painterResource(R.drawable.opening)
            )
        }

        Spacer(Modifier.height(8.dp))
        Text(name, color = Color.LightGray)
        Spacer(Modifier.height(8.dp))

        // Только itera
        StatBox(value = String.format("%.1f%%", acc.itera), dark = inverted)
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
        modifier = Modifier
            .size(size)
            .background(bg, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = initial,
            color = fg,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
        )
    }
}

/* ================= Остальные блоки ================= */

@Composable
private fun StatBox(value: String, dark: Boolean = false) {
    val bg = if (dark) Color(0xFF3A3936) else Color(0xFFEDEDED)
    val fg = if (dark) Color.White else Color(0xFF2B2A27)
    Box(
        modifier = Modifier
            .width(110.dp)
            .height(56.dp)
            .background(bg, RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            value,
            color = fg,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun ClassificationTable(report: FullReport) {
    val context = LocalContext.current
    val rows = listOf(
        Triple(stringResource(R.string.move_splendid), R.drawable.splendid, MoveClass.SPLENDID),
        Triple(stringResource(R.string.move_perfect), R.drawable.perfect, MoveClass.PERFECT),
        Triple(stringResource(R.string.move_best), R.drawable.best, MoveClass.BEST),
        Triple(stringResource(R.string.move_excellent), R.drawable.excellent, MoveClass.EXCELLENT),
        Triple(stringResource(R.string.move_okay), R.drawable.okay, MoveClass.OKAY),
        Triple(stringResource(R.string.move_opening), R.drawable.opening, MoveClass.OPENING),
        Triple(stringResource(R.string.move_inaccuracy), R.drawable.inaccuracy, MoveClass.INACCURACY),
        Triple(stringResource(R.string.move_mistake), R.drawable.mistake, MoveClass.MISTAKE),
        Triple(stringResource(R.string.move_forced), R.drawable.forced, MoveClass.FORCED),
        Triple(stringResource(R.string.move_blunder), R.drawable.blunder, MoveClass.BLUNDER)
    )

    val whiteMovesMap = mutableMapOf<MoveClass, Int>().withDefault { 0 }
    val blackMovesMap = mutableMapOf<MoveClass, Int>().withDefault { 0 }
    report.moves.forEachIndexed { i, move ->
        val target = if (i % 2 == 0) whiteMovesMap else blackMovesMap
        target[move.classification] = target.getValue(move.classification) + 1
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = CardBg),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(stringResource(R.string.move_classification), color = Color.LightGray)
                Row {
                    Text(" ", modifier = Modifier.width(36.dp))
                    Spacer(Modifier.width(24.dp))
                    Text(" ", modifier = Modifier.width(36.dp))
                }
            }
            HorizontalDivider(Modifier, DividerDefaults.Thickness, color = DividerC)

            rows.forEach { (label, icon, moveClass) ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Image(painterResource(icon), null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(label, color = Color.White)
                    }
                    Text(
                        whiteMovesMap.getValue(moveClass).toString(),
                        color = ChessComGreen,
                        textAlign = TextAlign.End,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.width(36.dp)
                    )
                    Spacer(Modifier.width(24.dp))
                    Text(
                        blackMovesMap.getValue(moveClass).toString(),
                        color = ChessComGreen,
                        textAlign = TextAlign.End,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.width(36.dp)
                    )
                }
                HorizontalDivider(Modifier, DividerDefaults.Thickness, color = DividerC)
            }
        }
    }
}

@Composable
private fun EvalSparkline(report: FullReport) {
    val raw = report.positions.mapNotNull { pos ->
        val l = pos.lines.firstOrNull()
        when {
            l?.cp != null -> l.cp.toFloat()
            l?.mate != null -> if (l.mate!! > 0) 3000f else -3000f
            else -> null
        }
    }
    if (raw.isEmpty()) return

    val cap = 800f
    val clamped = raw.map { it.coerceIn(-cap, cap) }
    val smooth = if (clamped.size < 4) clamped else buildList {
        val w = floatArrayOf(0.2f, 0.6f, 0.2f)
        add(clamped.first())
        for (i in 1 until clamped.lastIndex) {
            add(clamped[i - 1] * w[0] + clamped[i] * w[1] + clamped[i + 1] * w[2])
        }
        add(clamped.last())
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = CardBg),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .height(120.dp)
    ) {
        Canvas(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 10.dp, vertical = 12.dp)
        ) {
            val w = size.width
            val h = size.height
            val midY = h * 0.5f

            drawLine(
                color = Color(0xFF5E5E5E),
                start = Offset(0f, midY),
                end = Offset(w, midY),
                strokeWidth = 1f
            )

            fun to01(cp: Float) = (cp + cap) / (2f * cap)
            val stepX = if (smooth.size <= 1) 0f else w / (smooth.size - 1)
            val pts = smooth.mapIndexed { i, v ->
                val x = i * stepX
                val y = h * (1f - to01(v))
                Offset(x, y)
            }
            if (pts.isEmpty()) return@Canvas

            val strokeWidth = 1.7.dp.toPx()
            val linePath = Path()
            val fillPath = Path()

            fillPath.moveTo(0f, h)
            fillPath.lineTo(pts.first().x, pts.first().y)

            linePath.moveTo(pts.first().x, pts.first().y)
            for (i in 1 until pts.size) {
                val p0 = pts[(i - 1).coerceAtLeast(0)]
                val p1 = pts[i]
                val p_1 = pts[(i - 2).coerceAtLeast(0)]
                val p2 = pts[(i + 1).coerceAtMost(pts.lastIndex)]
                val t = 0.2f
                val c1x = p0.x + (p1.x - p_1.x) * t
                val c1y = p0.y + (p1.y - p_1.y) * t
                val c2x = p1.x - (p2.x - p0.x) * t
                val c2y = p1.y - (p2.y - p0.y) * t

                linePath.cubicTo(c1x, c1y, c2x, c2y, p1.x, p1.y)
                fillPath.cubicTo(c1x, c1y, c2x, c2y, p1.x, p1.y)
            }

            fillPath.lineTo(pts.last().x, h)
            fillPath.close()

            drawPath(fillPath, color = Color.White)
            drawPath(
                path = linePath,
                color = Color(0xFFBDBDBD),
                style = Stroke(width = strokeWidth)
            )
        }
    }
}

/* ================= helpers ================= */

private fun guessProvider(report: FullReport): ProviderHint {
    val pgn = report.header.pgn ?: ""
    val siteTag = Regex("""\[Site\s+"([^"]+)"]""").find(pgn)?.groupValues?.get(1)?.lowercase()
    return when {
        siteTag?.contains("lichess") == true -> ProviderHint.LICHESS
        siteTag?.contains("chess.com") == true -> ProviderHint.CHESSCOM
        else -> ProviderHint.UNKNOWN
    }
}