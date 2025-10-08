package com.github.movesense.ui.screens

import android.annotation.SuppressLint
import android.media.MediaPlayer
import android.util.Log
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.rememberAsyncImagePainter
import coil.decode.SvgDecoder
import coil.request.ImageRequest
import com.github.movesense.*
import com.github.movesense.EngineClient.analyzeMoveRealtime
import com.github.movesense.EngineClient.evaluateFenDetailedStreaming
import com.github.movesense.ui.components.BoardCanvas
import com.github.movesense.ui.components.EvalBar
import com.github.movesense.ui.components.MovesCarousel
import com.github.bhlangonijr.chesslib.*
import com.github.bhlangonijr.chesslib.move.Move
import com.github.bhlangonijr.chesslib.move.MoveGenerator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import kotlin.collections.get
import kotlin.math.abs
import kotlin.math.max
import com.github.movesense.R

private const val TAG = "GameReportScreen"

private data class PositionLinesState(
    val lines: List<LineEval>,
    val isAnalyzing: Boolean,
    val depth: Int,
    val multiPv: Int,
    val isFromReport: Boolean = false // NEW: флаг что линии из отчета (уже нормализованы)
)

private data class PvToken(
    val iconAsset: String,
    val toSquare: String,
    val capture: Boolean,
    val promoSuffix: String = ""
)

private fun pieceAssetName(p: Piece): String {
    val pref = if (p.pieceSide == Side.WHITE) "w" else "b"
    val name = when (p.pieceType) {
        PieceType.KING   -> "K"
        PieceType.QUEEN  -> "Q"
        PieceType.ROOK   -> "R"
        PieceType.BISHOP -> "B"
        PieceType.KNIGHT -> "N"
        else -> "P"
    }
    return "$pref$name.svg"
}

@Composable
private fun PieceAssetIcon(name: String, size: Dp) {
    val ctx = LocalContext.current
    val painter = rememberAsyncImagePainter(
        ImageRequest.Builder(ctx)
            .data("file:///android_asset/fresca/$name")
            .decoderFactory(SvgDecoder.Factory())
            .build()
    )
    Image(painter = painter, contentDescription = null, modifier = Modifier.size(size))
}

private fun findLegalMove(board: Board, uci: String): Move? {
    if (uci.length < 4) return null
    val from = Square.fromValue(uci.substring(0, 2).uppercase())
    val to   = Square.fromValue(uci.substring(2, 4).uppercase())
    val promoChar = if (uci.length > 4) uci[4].lowercaseChar() else null
    val legal = MoveGenerator.generateLegalMoves(board)
    return legal.firstOrNull { m ->
        m.from == from && m.to == to &&
                (promoChar == null || when (m.promotion?.pieceType) {
                    PieceType.QUEEN  -> promoChar == 'q'
                    PieceType.ROOK   -> promoChar == 'r'
                    PieceType.BISHOP -> promoChar == 'b'
                    PieceType.KNIGHT -> promoChar == 'n'
                    null -> false
                    else -> false
                })
    } ?: legal.firstOrNull { it.from == from && it.to == to }
}

private fun buildIconTokens(fen: String, pv: List<String>): List<PvToken> {
    val b = Board().apply { loadFromFen(fen) }
    val out = mutableListOf<PvToken>()
    for (uci in pv) {
        val legal = findLegalMove(b, uci) ?: break
        val mover = b.getPiece(legal.from)
        val dst   = b.getPiece(legal.to)
        val capture = dst != Piece.NONE ||
                (mover.pieceType == PieceType.PAWN && legal.from.file != legal.to.file)
        val promoSuffix = when (legal.promotion?.pieceType) {
            PieceType.QUEEN  -> "=Q"
            PieceType.ROOK   -> "=R"
            PieceType.BISHOP -> "=B"
            PieceType.KNIGHT -> "=N"
            else -> ""
        }
        out += PvToken(
            iconAsset = pieceAssetName(mover),
            toSquare = legal.to.toString().lowercase(),
            capture = capture,
            promoSuffix = promoSuffix
        )
        b.doMove(legal)
    }
    return out
}

@Composable
private fun EvalChip(line: LineEval, modifier: Modifier = Modifier) {
    val txt = when {
        line.mate != null -> if (line.mate!! > 0) "M${abs(line.mate!!)}" else "M-${abs(line.mate!!)}"
        line.cp != null   -> String.format("%+.2f", line.cp!! / 100f)
        else -> "—"
    }

    val backgroundColor = when {
        line.mate != null -> if (line.mate!! > 0) Color.White else Color(0xFF1C1C1C)
        line.cp != null -> if (line.cp!! > 0) Color.White else Color(0xFF1C1C1C)
        else -> Color(0xFF2F2F2F)
    }

    val textColor = when {
        line.mate != null -> if (line.mate!! > 0) Color.Black else Color.White
        line.cp != null -> if (line.cp!! > 0) Color.Black else Color.White
        else -> Color.White
    }

    Box(
        modifier = modifier
            .background(backgroundColor, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(txt, color = textColor, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun PvRow(
    baseFen: String,
    line: LineEval,
    onClickMoveAtIndex: (idx: Int) -> Unit,
    modifier: Modifier = Modifier
) {
    if (line.pv.isEmpty()) return

    val tokens = remember(baseFen, line.pv) { buildIconTokens(baseFen, line.pv) }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        EvalChip(line)
        Spacer(Modifier.width(10.dp))
        Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
            tokens.forEachIndexed { i, t ->
                Row(
                    modifier = Modifier
                        .clickable { onClickMoveAtIndex(i) }
                        .padding(end = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    PieceAssetIcon(t.iconAsset, 20.dp)
                    Spacer(Modifier.width(4.dp))
                    val suffix = buildString {
                        append(t.toSquare)
                        if (t.capture) append("x")
                        append(t.promoSuffix)
                    }
                    Text(suffix, color = Color.White.copy(alpha = 0.9f), fontSize = 16.sp)
                }
            }
        }
    }
}

@Composable
private fun AnalysisSettingsPanel(
    targetDepth: Int,
    onDepthChange: (Int) -> Unit,
    targetMultiPv: Int,
    onMultiPvChange: (Int) -> Unit,
    isBusy: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var isExpanded by remember { mutableStateOf(false) }
    val rotationAngle by animateFloatAsState(
        targetValue = if (isExpanded) 180f else 0f,
        animationSpec = tween(300),
        label = "chevron"
    )

    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1C1A)),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { isExpanded = !isExpanded }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Settings, contentDescription = null, tint = Color.White)
                Spacer(Modifier.width(12.dp))
                Text(
                    stringResource(R.string.analysis_settings),
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                if (isBusy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = MaterialTheme.colorScheme.primary,
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.width(8.dp))
                } else {
                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF4CAF50))
                    Spacer(Modifier.width(8.dp))
                }
                Icon(
                    Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.7f),
                    modifier = Modifier.rotate(rotationAngle)
                )
            }

            AnimatedVisibility(
                visible = isExpanded,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, bottom = 16.dp)
                ) {
                    HorizontalDivider(color = Color.White.copy(alpha = 0.1f), thickness = 1.dp)
                    Spacer(Modifier.height(16.dp))

                    Text(
                        context.getString(R.string.depth, targetDepth),
                        color = Color.White.copy(alpha = 0.9f),
                        fontWeight = FontWeight.Medium,
                        fontSize = 14.sp
                    )
                    Spacer(Modifier.height(8.dp))
                    Slider(
                        value = targetDepth.toFloat(),
                        onValueChange = { onDepthChange(it.toInt().coerceIn(6, 40)) },
                        valueRange = 6f..40f,
                        steps = (40 - 6) - 1,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(Modifier.height(16.dp))

                    Text(
                        context.getString(R.string.multipv, targetMultiPv),
                        color = Color.White.copy(alpha = 0.9f),
                        fontWeight = FontWeight.Medium,
                        fontSize = 14.sp
                    )
                    Spacer(Modifier.height(8.dp))
                    Slider(
                        value = targetMultiPv.toFloat(),
                        onValueChange = { onMultiPvChange(it.toInt().coerceIn(1, 5)) },
                        valueRange = 1f..5f,
                        steps = (5 - 1) - 1,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(Modifier.height(12.dp))
                    Text(
                        stringResource(R.string.settings_note),
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 12.sp,
                        lineHeight = 16.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun EngineLinesPanel(
    baseFen: String,
    lines: List<LineEval>,
    onClickMoveInLine: (lineIdx: Int, moveIdx: Int) -> Unit,
    isBusy: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var isExpanded by remember { mutableStateOf(true) }
    val rotationAngle by animateFloatAsState(
        targetValue = if (isExpanded) 180f else 0f,
        animationSpec = tween(300),
        label = "chevron"
    )

    val validLines = remember(lines) {
        lines.filter { it.pv.isNotEmpty() }
    }

    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1C1A)),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { isExpanded = !isExpanded }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Search, contentDescription = null, tint = Color.White)
                Spacer(Modifier.width(12.dp))
                Text(
                    stringResource(R.string.engine_lines),
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                if (isBusy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = MaterialTheme.colorScheme.primary,
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.width(8.dp))
                } else if (validLines.isNotEmpty()) {
                    Badge(
                        containerColor = Color(0xFF4CAF50).copy(alpha = 0.2f),
                        contentColor = Color(0xFF4CAF50)
                    ) {
                        Text("${validLines.size}", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.width(8.dp))
                }
                Icon(
                    Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.7f),
                    modifier = Modifier.rotate(rotationAngle)
                )
            }

            AnimatedVisibility(
                visible = isExpanded,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, bottom = 16.dp)
                ) {
                    HorizontalDivider(color = Color.White.copy(alpha = 0.1f), thickness = 1.dp)
                    Spacer(Modifier.height(12.dp))

                    when {
                        validLines.isNotEmpty() -> {
                            validLines.forEachIndexed { li, line ->
                                PvRow(
                                    baseFen = baseFen,
                                    line = line,
                                    onClickMoveAtIndex = { mi -> onClickMoveInLine(li, mi) }
                                )
                            }
                        }
                        isBusy -> {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 16.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    color = MaterialTheme.colorScheme.primary,
                                    strokeWidth = 2.dp
                                )
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    stringResource(R.string.calculating),
                                    color = Color.White.copy(alpha = 0.8f)
                                )
                            }
                        }
                        else -> {
                            Text(
                                stringResource(R.string.no_lines),
                                color = Color.White.copy(alpha = 0.6f),
                                fontSize = 14.sp,
                                modifier = Modifier.padding(vertical = 16.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

fun extractGameId(pgn: String?): String? {
    if (pgn.isNullOrBlank()) return null
    val sitePattern = Regex("""\[Site\s+".*/([\w]+)"\]""")
    sitePattern.find(pgn)?.groupValues?.getOrNull(1)?.let { return it }
    val lichessPattern = Regex("""([a-zA-Z0-9]{8})""")
    lichessPattern.find(pgn)?.groupValues?.getOrNull(1)?.let { return it }
    return null
}

fun parseClockData(pgn: String): ClockData {
    Log.d(TAG, "parseClockData: parsing PGN for clocks...")
    val clockPattern = Regex("""\[%clk\s+(?:(\d+):)?(\d{1,2}):(\d{1,2})(?:\.(\d+))?\]""", RegexOption.IGNORE_CASE)
    val whiteTimes = mutableListOf<Int>()
    val blackTimes = mutableListOf<Int>()
    var plyIndex = 0

    clockPattern.findAll(pgn).forEach { m ->
        val hours = m.groups[1]?.value?.toIntOrNull() ?: 0
        val minutes = m.groups[2]?.value?.toIntOrNull() ?: 0
        val seconds = m.groups[3]?.value?.toIntOrNull() ?: 0
        val cs = (hours * 3600 + minutes * 60 + seconds) * 100

        if (plyIndex % 2 == 0) {
            whiteTimes.add(cs)
            Log.d(TAG, "White ply $plyIndex: ${formatClock(cs)}")
        } else {
            blackTimes.add(cs)
            Log.d(TAG, "Black ply $plyIndex: ${formatClock(cs)}")
        }
        plyIndex++
    }

    Log.d(TAG, "parseClockData: found ${whiteTimes.size} white clocks, ${blackTimes.size} black clocks")
    return ClockData(white = whiteTimes, black = blackTimes)
}

private suspend fun fetchLichessClocks(gameId: String): ClockData? = withContext(Dispatchers.IO) {
    Log.d(TAG, "fetchLichessClocks: fetching for gameId=$gameId")
    val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()
    val url = "https://lichess.org/game/export/$gameId?clocks=true&moves=false&tags=false"
    val request = Request.Builder().url(url).header("Accept", "application/x-chess-pgn").build()
    try {
        client.newCall(request).execute().use { response ->
            if (response.isSuccessful) {
                val body = response.body?.string() ?: return@withContext null
                Log.d(TAG, "fetchLichessClocks: got response, parsing...")
                parseClockData(body)
            } else {
                Log.w(TAG, "fetchLichessClocks: failed with code ${response.code}")
                null
            }
        }
    } catch (e: Exception) {
        Log.e(TAG, "fetchLichessClocks: error", e)
        null
    }
}

@SuppressLint("UnrememberedMutableState")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GameReportScreen(
    report: FullReport,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current

    var isWhiteBottom by remember { mutableStateOf(report.header.sideToView ?: true) }
    var currentPlyIndex by remember { mutableStateOf(0) }
    var isAutoPlaying by remember { mutableStateOf(false) }

    var clockData by remember { mutableStateOf(report.clockData) }

    var variationActive by remember { mutableStateOf(false) }
    var variationFen by remember { mutableStateOf<String?>(null) }
    var variationEval by remember { mutableStateOf<Float?>(null) }
    var variationBestUci by remember { mutableStateOf<String?>(null) }
    var variationMoveClass by remember { mutableStateOf<MoveClass?>(null) }
    var variationLastMove by remember { mutableStateOf<Pair<String, String>?>(null) }
    var selectedSquare by remember { mutableStateOf<String?>(null) }
    var legalTargets by remember { mutableStateOf<Set<String>>(emptySet()) }
    var isAnalyzing by remember { mutableStateOf(false) }

    val linesStateMap = remember { mutableStateMapOf<String, PositionLinesState>() }

    var displayedLines by remember { mutableStateOf<List<LineEval>>(emptyList()) }
    var isAnalysisRunning by remember { mutableStateOf(false) }

    val positionSettings = remember { mutableStateMapOf<Int, Pair<Int, Int>>() }

    val defaultDepth = remember {
        report.positions.firstOrNull()?.lines?.firstOrNull()?.depth ?: 16
    }
    val defaultMultiPv = remember { 3 }

    var targetDepth by remember { mutableStateOf(defaultDepth) }
    var targetMultiPv by remember { mutableStateOf(defaultMultiPv) }

    var analysisJob by remember { mutableStateOf<Job?>(null) }

    var analysisVersion by remember { mutableStateOf(0) }

    val bgColor = Color(0xFF161512)
    val surfaceColor = Color(0xFF262522)
    val cardColor = Color(0xFF1E1C1A)

    // Функция нормализации линий к белой перспективе (только для НОВЫХ линий от движка)
    fun normalizeLinesToWhitePOV(lines: List<EngineClient.LineDTO>, fen: String): List<EngineClient.LineDTO> {
        val whiteToPlay = fen.split(" ").getOrNull(1) == "w"

        return lines.map { line ->
            val normalizedCp = if (whiteToPlay) line.cp else line.cp?.let { -it }
            val normalizedMate = line.mate?.let { m ->
                when {
                    m == 0 && whiteToPlay -> -1
                    m == 0 && !whiteToPlay -> 1
                    whiteToPlay -> m
                    else -> -m
                }
            }
            EngineClient.LineDTO(
                pv = line.pv,
                cp = normalizedCp,
                mate = normalizedMate,
                depth = line.depth,
                multiPv = line.multiPv
            )
        }
    }

    LaunchedEffect(report) {
        Log.d(TAG, "🔄 Initializing lines from report...")
        report.positions.forEachIndexed { index, posEval ->
            if (posEval.lines.isNotEmpty()) {
                val key = "${posEval.fen}-${posEval.lines.firstOrNull()?.depth ?: defaultDepth}-${report.positions.firstOrNull()?.lines?.size ?: defaultMultiPv}"
                linesStateMap[key] = PositionLinesState(
                    lines = posEval.lines,
                    isAnalyzing = false,
                    depth = posEval.lines.firstOrNull()?.depth ?: defaultDepth,
                    multiPv = posEval.lines.size,
                    isFromReport = true // ПОМЕЧАЕМ что линии из отчета
                )
                Log.d(TAG, "✅ Initialized position $index with ${posEval.lines.size} lines (from report, already normalized)")
            }
        }

        val initialLines = report.positions.getOrNull(0)?.lines ?: emptyList()
        displayedLines = initialLines
        Log.d(TAG, "✅ Set initial displayed lines: ${initialLines.size}")
    }

    LaunchedEffect(report) {
        Log.d(TAG, "🕐 LaunchedEffect: checking clocks...")

        if (report.clockData != null &&
            (report.clockData!!.white.isNotEmpty() || report.clockData!!.black.isNotEmpty())) {
            Log.d(TAG, "✅ Using clocks from report: white=${report.clockData!!.white.size}, black=${report.clockData!!.black.size}")
            clockData = report.clockData
            return@LaunchedEffect
        }

        val pgn = report.header.pgn
        if (pgn.isNullOrBlank()) {
            Log.w(TAG, "⚠ No PGN in report, can't extract clocks")
            return@LaunchedEffect
        }

        Log.d(TAG, "🔍 Trying to parse clocks from PGN...")
        val parsed = parseClockData(pgn)

        if (parsed.white.isNotEmpty() || parsed.black.isNotEmpty()) {
            Log.d(TAG, "✅ Parsed clocks from PGN: white=${parsed.white.size}, black=${parsed.black.size}")
            clockData = parsed
            return@LaunchedEffect
        }

        val gameId = extractGameId(pgn)
        if (gameId != null && report.header.site == Provider.LICHESS) {
            Log.d(TAG, "🔍 Trying to fetch clocks from Lichess API for game: $gameId")
            val fetched = fetchLichessClocks(gameId)
            if (fetched != null && (fetched.white.isNotEmpty() || fetched.black.isNotEmpty())) {
                Log.d(TAG, "✅ Fetched clocks from Lichess API: white=${fetched.white.size}, black=${fetched.black.size}")
                clockData = fetched
            } else {
                Log.w(TAG, "⚠ Failed to fetch clocks from Lichess API")
            }
        } else {
            Log.d(TAG, "ℹ Not a Lichess game or no gameId found")
        }
    }

    val currentFen by derivedStateOf {
        if (variationActive) {
            variationFen ?: report.positions.getOrNull(currentPlyIndex)?.fen ?: ""
        } else {
            report.positions.getOrNull(currentPlyIndex)?.fen ?: ""
        }
    }

    val baseFenForPanel by derivedStateOf { currentFen }

    LaunchedEffect(currentPlyIndex, variationActive) {
        if (!variationActive) {
            val saved = positionSettings[currentPlyIndex]
            if (saved != null) {
                targetDepth = saved.first
                targetMultiPv = saved.second
            } else {
                targetDepth = defaultDepth
                targetMultiPv = defaultMultiPv
            }

            // КРИТИЧЕСКОЕ ИСПРАВЛЕНИЕ: НЕ нормализуем линии из отчета - они УЖЕ нормализованы
            val reportLines = report.positions.getOrNull(currentPlyIndex)?.lines ?: emptyList()
            if (reportLines.isNotEmpty()) {
                displayedLines = reportLines
                Log.d(TAG, "📋 Instantly displayed ${reportLines.size} lines from report for ply $currentPlyIndex (already normalized)")
            }
        }
    }

    fun startAnalysis(fen: String, depth: Int, multiPv: Int) {
        if (fen.isBlank()) {
            Log.w(TAG, "⚠ Cannot analyze: empty FEN")
            return
        }

        val stateKey = "$fen-$depth-$multiPv"

        val cached = linesStateMap[stateKey]
        if (cached != null && !cached.isAnalyzing) {
            // КРИТИЧЕСКОЕ ИСПРАВЛЕНИЕ: если линии из отчета - НЕ нормализуем их снова!
            if (cached.isFromReport) {
                displayedLines = cached.lines
                isAnalysisRunning = false
                Log.d(TAG, "✅ Using cached analysis from report: ${cached.lines.size} lines (already normalized)")
                return
            } else {
                // Линии от нового анализа - нормализуем
                val normalizedLines = normalizeLinesToWhitePOV(cached.lines.map { lineEval ->
                    EngineClient.LineDTO(
                        pv = lineEval.pv,
                        cp = lineEval.cp,
                        mate = lineEval.mate,
                        depth = lineEval.depth,
                        multiPv = lineEval.multiPv ?: 1
                    )
                }, fen)

                displayedLines = normalizedLines.map { dto ->
                    LineEval(
                        pv = dto.pv,
                        cp = dto.cp,
                        mate = dto.mate,
                        depth = dto.depth,
                        best = dto.pv.firstOrNull(),
                        multiPv = dto.multiPv
                    )
                }

                isAnalysisRunning = false
                Log.d(TAG, "✅ Using cached analysis (new): ${cached.lines.size} lines (normalized)")
                return
            }
        }

        analysisJob?.cancel()

        isAnalysisRunning = true
        Log.d(TAG, "🔍 Starting analysis for: $stateKey")

        analysisJob = scope.launch {
            try {
                val baseLines = displayedLines.toList()
                var hasUpdates = false

                val finalResult = evaluateFenDetailedStreaming(
                    fen = fen,
                    depth = depth,
                    multiPv = multiPv,
                    skillLevel = null
                ) { linesDto ->
                    val validLines = linesDto.filter { l ->
                        l.pv.isNotEmpty() && (l.cp != null || l.mate != null)
                    }

                    if (validLines.isNotEmpty()) {
                        // НОВЫЕ линии от движка - нормализуем
                        val normalizedLines = normalizeLinesToWhitePOV(validLines, fen)

                        val newLines = normalizedLines.map { l ->
                            LineEval(
                                pv = l.pv,
                                cp = l.cp,
                                mate = l.mate,
                                best = l.pv.firstOrNull(),
                                depth = l.depth,
                                multiPv = l.multiPv
                            )
                        }.take(multiPv)

                        if (newLines != displayedLines) {
                            displayedLines = newLines
                            hasUpdates = true
                            Log.d(TAG, "📊 Updated lines (normalized): ${newLines.size}, depth=${validLines.firstOrNull()?.depth}")
                        }
                    }
                }

                val finalValidLines = finalResult.lines.filter { l ->
                    l.pv.isNotEmpty() && (l.cp != null || l.mate != null)
                }

                if (finalValidLines.isNotEmpty()) {
                    // НОВЫЕ финальные линии - нормализуем
                    val normalizedFinalLines = normalizeLinesToWhitePOV(finalValidLines, fen)

                    val finalLines = normalizedFinalLines.map { l ->
                        LineEval(
                            pv = l.pv,
                            cp = l.cp,
                            mate = l.mate,
                            best = l.pv.firstOrNull(),
                            depth = l.depth,
                            multiPv = l.multiPv
                        )
                    }.take(multiPv)

                    displayedLines = finalLines

                    linesStateMap[stateKey] = PositionLinesState(
                        lines = finalLines,
                        isAnalyzing = false,
                        depth = depth,
                        multiPv = multiPv,
                        isFromReport = false // Это новый анализ, не из отчета
                    )

                    Log.d(TAG, "✅ Analysis complete: ${finalLines.size} lines cached (normalized)")
                } else if (!hasUpdates) {
                    displayedLines = baseLines
                    Log.w(TAG, "⚠ No updates, restored base lines: ${baseLines.size}")
                }

            } catch (e: Exception) {
                if (e !is CancellationException) {
                    Log.e(TAG, "❌ Analysis error: ${e.message}", e)
                } else {
                    Log.d(TAG, "🚫 Analysis cancelled")
                }
            } finally {
                isAnalysisRunning = false
            }
        }
    }

    LaunchedEffect(currentFen, targetDepth, targetMultiPv, variationActive, analysisVersion) {
        if (currentFen.isNotBlank()) {
            delay(100)
            startAnalysis(currentFen, targetDepth, targetMultiPv)
        }
    }

    fun evalOfPosition(pos: PositionEval?): Float {
        val line = pos?.lines?.firstOrNull()
        return when {
            line?.cp != null -> line.cp / 100f
            line?.mate != null -> if (line.mate > 0) 30f else -30f
            else -> 0f
        }
    }

    fun playMoveSound(cls: MoveClass?, isCapture: Boolean) {
        val resId = when {
            cls == MoveClass.INACCURACY || cls == MoveClass.MISTAKE || cls == MoveClass.BLUNDER ->
                R.raw.error
            isCapture -> R.raw.capture
            else -> R.raw.move
        }
        try {
            MediaPlayer.create(ctx, resId)?.apply {
                setOnCompletionListener { it.release() }
                start()
            }
        } catch (_: Exception) {}
    }

    fun handleSquareClick(square: String) {
        if (isAnalyzing) return

        val baseFen = if (variationActive) variationFen
        else report.positions.getOrNull(currentPlyIndex)?.fen
        val boardFenNow = baseFen ?: return

        val board = Board().apply { loadFromFen(boardFenNow) }

        if (selectedSquare != null && selectedSquare.equals(square, ignoreCase = true)) {
            selectedSquare = null
            legalTargets = emptySet()
            return
        }

        if (selectedSquare == null) {
            selectedSquare = square.lowercase()
            val all = MoveGenerator.generateLegalMoves(board)
            legalTargets = all.filter { it.from.toString().equals(selectedSquare, true) }
                .map { it.to.toString().lowercase() }
                .toSet()
            if (legalTargets.isEmpty()) selectedSquare = null
            return
        }

        val from = selectedSquare!!.lowercase()
        val to   = square.lowercase()
        run {
            val all = MoveGenerator.generateLegalMoves(board)
            val hasMovesForNew = all.any { it.from.toString().equals(to, true) }
            val isDirectMove = all.any { it.from.toString().equals(from, true) && it.to.toString().equals(to, true) }
            if (hasMovesForNew && !isDirectMove) {
                selectedSquare = to
                legalTargets = all.filter { it.from.toString().equals(selectedSquare, true) }
                    .map { it.to.toString().lowercase() }
                    .toSet()
                return
            }
        }

        val legalMoves = MoveGenerator.generateLegalMoves(board)
        val move = legalMoves.firstOrNull { it.from.toString().equals(from, true) && it.to.toString().equals(to, true) }
            ?: legalMoves.firstOrNull { it.toString().equals(from + to, true) }
        if (move == null) {
            selectedSquare = null
            legalTargets = emptySet()
            return
        }

        val beforeFen = board.fen
        val captured = board.getPiece(move.to) != Piece.NONE
        board.doMove(move)
        val afterFen = board.fen
        val uciMove = buildString {
            append(from).append(to)
            move.promotion?.pieceType?.let {
                append(
                    when (it) {
                        PieceType.QUEEN  -> "q"
                        PieceType.ROOK   -> "r"
                        PieceType.BISHOP -> "b"
                        PieceType.KNIGHT -> "n"
                        else -> ""
                    }
                )
            }
        }

        variationActive = true
        variationFen = afterFen
        variationLastMove = from to to
        isAutoPlaying = false
        selectedSquare = null
        legalTargets = emptySet()
        isAnalyzing = true

        scope.launch {
            try {
                val (newEval, moveClass, bestMove) = analyzeMoveRealtime(
                    beforeFen = beforeFen,
                    afterFen = afterFen,
                    uciMove = uciMove,
                    depth = targetDepth,
                    multiPv = targetMultiPv
                )
                variationEval = newEval
                variationMoveClass = moveClass
                variationBestUci = bestMove
                playMoveSound(moveClass, captured)

                Log.d(TAG, "✓ Move analyzed, variation active")

                analysisVersion++

            } catch (e: Exception) {
                Log.e(TAG, "Error in handleSquareClick", e)
                variationEval = evalOfPosition(report.positions.getOrNull(currentPlyIndex))
                variationMoveClass = MoveClass.OKAY
                variationBestUci = null
            } finally {
                isAnalyzing = false
            }
        }
    }

    fun onClickPvMove(lineIdx: Int, moveIdx: Int) {
        val baseFen = if (variationActive) variationFen
        else report.positions.getOrNull(currentPlyIndex)?.fen
        val line = displayedLines.getOrNull(lineIdx) ?: return
        val fen0 = baseFen ?: return
        val pv = line.pv
        if (moveIdx !in pv.indices) return

        val b = Board().apply { loadFromFen(fen0) }
        for (i in 0 until moveIdx) {
            val m = findLegalMove(b, pv[i]) ?: return
            b.doMove(m)
        }
        val before = b.fen
        val move = findLegalMove(b, pv[moveIdx]) ?: return
        val from = move.from.toString().lowercase()
        val to   = move.to.toString().lowercase()
        val uci = buildString {
            append(from).append(to)
            move.promotion?.pieceType?.let {
                append(
                    when (it) {
                        PieceType.QUEEN  -> "q"
                        PieceType.ROOK   -> "r"
                        PieceType.BISHOP -> "b"
                        PieceType.KNIGHT -> "n"
                        else -> ""
                    }
                )
            }
        }
        val captured = b.getPiece(move.to) != Piece.NONE
        b.doMove(move)
        val after = b.fen

        variationActive = true
        variationFen = after
        variationLastMove = from to to
        isAutoPlaying = false
        selectedSquare = null
        legalTargets = emptySet()
        isAnalyzing = true

        scope.launch {
            try {
                val (newEval, moveClass, bestMove) = analyzeMoveRealtime(
                    beforeFen = before,
                    afterFen = after,
                    uciMove = uci,
                    depth = targetDepth,
                    multiPv = targetMultiPv
                )
                variationEval = newEval
                variationMoveClass = moveClass
                variationBestUci = bestMove
                playMoveSound(moveClass, captured)

                Log.d(TAG, "✓ PV move analyzed")

                analysisVersion++

            } catch (e: Exception) {
                Log.e(TAG, "Error in onClickPvMove", e)
                variationEval = evalOfPosition(report.positions.getOrNull(currentPlyIndex))
                variationMoveClass = MoveClass.OKAY
                variationBestUci = null
            } finally {
                isAnalyzing = false
            }
        }
    }

    fun exitVariation() {
        if (!variationActive) return
        variationActive = false
        variationFen = null
        variationEval = null
        variationBestUci = null
        variationMoveClass = null
        variationLastMove = null
        selectedSquare = null
        legalTargets = emptySet()

        // КРИТИЧЕСКОЕ ИСПРАВЛЕНИЕ: НЕ нормализуем линии из отчета
        val reportLines = report.positions.getOrNull(currentPlyIndex)?.lines ?: emptyList()
        if (reportLines.isNotEmpty()) {
            displayedLines = reportLines
            Log.d(TAG, "✓ Restored ${reportLines.size} lines from report (already normalized)")
        }

        Log.d(TAG, "✓ Exited variation")
    }

    fun seekTo(index: Int) {
        variationActive = false
        selectedSquare = null
        legalTargets = emptySet()
        isAnalyzing = false
        currentPlyIndex = index.coerceIn(0, report.positions.lastIndex)

        if (currentPlyIndex > 0) {
            val mv = report.moves.getOrNull(currentPlyIndex - 1)
            playMoveSound(mv?.classification, mv?.san?.contains('x') == true)
        }
    }

    fun goNext() { if (!isAnalyzing && currentPlyIndex < report.positions.lastIndex) seekTo(currentPlyIndex + 1) }
    fun goPrev() { if (!isAnalyzing && currentPlyIndex > 0) seekTo(currentPlyIndex - 1) }

    Scaffold(
        containerColor = bgColor,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.game_analysis)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.Default.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                            tint = Color.White
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = { if (!isAnalyzing) isWhiteBottom = !isWhiteBottom },
                        enabled = !isAnalyzing
                    ) {
                        Icon(
                            Icons.Default.ScreenRotation,
                            contentDescription = stringResource(R.string.flip_board),
                            tint = if (isAnalyzing) Color.Gray else Color.White
                        )
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
                .verticalScroll(rememberScrollState())
        ) {
            val topIsWhite = !isWhiteBottom
            val topName = if (topIsWhite) {
                report.header.white ?: stringResource(R.string.white)
            } else {
                report.header.black ?: stringResource(R.string.black)
            }
            val topElo = if (topIsWhite) report.header.whiteElo else report.header.blackElo

            val topClock = if (topIsWhite) {
                val moveIndex = currentPlyIndex / 2
                clockData?.white?.getOrNull(moveIndex)
            } else {
                val moveIndex = if (currentPlyIndex > 0) (currentPlyIndex - 1) / 2 else 0
                clockData?.black?.getOrNull(moveIndex)
            }

            val topActive = if (!variationActive) {
                (currentPlyIndex % 2 == 0 && topIsWhite) || (currentPlyIndex % 2 == 1 && !topIsWhite)
            } else false

            PlayerCard(
                name = topName,
                rating = topElo,
                clock = topClock,
                isActive = topActive,
                inverted = !topIsWhite,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(cardColor)
                    .padding(horizontal = 12.dp, vertical = 4.dp)
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
            ) {
                val evalPositions: List<PositionEval>
                val evalIndex: Int
                if (variationActive && variationEval != null) {
                    val evalCp = (variationEval!! * 100).toInt()
                    val fakeLine = LineEval(pv = emptyList(), cp = evalCp, mate = null, best = null)
                    val baseFen = variationFen ?: report.positions.getOrNull(currentPlyIndex)?.fen.orEmpty()
                    val fakePos = PositionEval(fen = baseFen, idx = 0, lines = listOf(fakeLine))
                    evalPositions = listOf(fakePos)
                    evalIndex = 0
                } else {
                    evalPositions = report.positions
                    evalIndex = currentPlyIndex
                }

                EvalBar(
                    positions = evalPositions,
                    currentPlyIndex = evalIndex,
                    isWhiteBottom = isWhiteBottom,
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(20.dp)
                )

                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .weight(1f)
                ) {
                    val currentPosition = report.positions.getOrNull(currentPlyIndex)

                    val lastMovePairActual = if (currentPlyIndex > 0 && !variationActive) {
                        val uci = report.moves[currentPlyIndex - 1].uci
                        if (uci.length >= 4) uci.substring(0, 2) to uci.substring(2, 4) else null
                    } else null

                    val lastMoveClassActual = if (!variationActive) {
                        report.moves.getOrNull(currentPlyIndex - 1)?.classification
                    } else null

                    val bestUciActual: String? = if (!variationActive) {
                        report.positions
                            .getOrNull(max(0, currentPlyIndex - 1))
                            ?.lines?.firstOrNull()
                            ?.pv?.firstOrNull()
                    } else null

                    val boardFen = if (variationActive) variationFen else currentPosition?.fen
                    val lastMovePair = if (variationActive) variationLastMove else lastMovePairActual
                    val moveClass = if (variationActive) variationMoveClass else lastMoveClassActual
                    val bestUci = if (variationActive) variationBestUci else bestUciActual

                    val showBestArrow = when (moveClass) {
                        MoveClass.INACCURACY, MoveClass.MISTAKE, MoveClass.BLUNDER -> true
                        else -> false
                    }

                    boardFen?.let { fen ->
                        BoardCanvas(
                            fen = fen,
                            lastMove = lastMovePair,
                            moveClass = moveClass,
                            bestMoveUci = bestUci,
                            showBestArrow = showBestArrow,
                            isWhiteBottom = isWhiteBottom,
                            selectedSquare = selectedSquare,
                            legalMoves = legalTargets,
                            onSquareClick = { handleSquareClick(it) },
                            modifier = Modifier.fillMaxSize()
                        )
                    }

                    if (isAnalyzing) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.3f)),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(48.dp),
                                color = MaterialTheme.colorScheme.primary,
                                strokeWidth = 3.dp
                            )
                        }
                    }
                }
            }

            val bottomIsWhite = isWhiteBottom
            val bottomName = if (bottomIsWhite) {
                report.header.white ?: stringResource(R.string.white)
            } else {
                report.header.black ?: stringResource(R.string.black)
            }
            val bottomElo = if (bottomIsWhite) report.header.whiteElo else report.header.blackElo

            val bottomClock = if (bottomIsWhite) {
                val moveIndex = currentPlyIndex / 2
                clockData?.white?.getOrNull(moveIndex)
            } else {
                val moveIndex = if (currentPlyIndex > 0) (currentPlyIndex - 1) / 2 else 0
                clockData?.black?.getOrNull(moveIndex)
            }

            val bottomActive = if (!variationActive) {
                (currentPlyIndex % 2 == 0 && bottomIsWhite) || (currentPlyIndex % 2 == 1 && !bottomIsWhite)
            } else false

            PlayerCard(
                name = bottomName,
                rating = bottomElo,
                clock = bottomClock,
                isActive = bottomActive,
                inverted = !bottomIsWhite,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(cardColor)
                    .padding(horizontal = 12.dp, vertical = 4.dp)
            )

            MovesCarousel(
                report = report,
                currentPlyIndex = currentPlyIndex,
                onSeekTo = { if (!isAnalyzing) seekTo(it) },
                onPrev = { if (!isAnalyzing) goPrev() },
                onNext = { if (!isAnalyzing) goNext() },
                modifier = Modifier.fillMaxWidth()
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(cardColor)
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { if (!isAnalyzing) seekTo(0) }, enabled = !isAnalyzing) {
                    Icon(
                        Icons.Default.SkipPrevious,
                        contentDescription = stringResource(R.string.to_start),
                        tint = if (isAnalyzing) Color.Gray else Color.White
                    )
                }
                IconButton(onClick = { goPrev() }, enabled = !isAnalyzing) {
                    Icon(
                        Icons.Default.ArrowBack,
                        contentDescription = stringResource(R.string.previous),
                        tint = if (isAnalyzing) Color.Gray else Color.White
                    )
                }
                IconButton(
                    onClick = {
                        if (!isAnalyzing) {
                            isAutoPlaying = !isAutoPlaying
                            variationActive = false
                            selectedSquare = null
                        }
                    },
                    enabled = !isAnalyzing,
                    modifier = Modifier
                        .size(56.dp)
                        .background(
                            if (isAnalyzing) Color.Gray else MaterialTheme.colorScheme.primary,
                            CircleShape
                        )
                ) {
                    Icon(
                        if (isAutoPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isAutoPlaying) {
                            stringResource(R.string.pause)
                        } else {
                            stringResource(R.string.play)
                        },
                        tint = Color.White
                    )
                }
                IconButton(onClick = { goNext() }, enabled = !isAnalyzing) {
                    Icon(
                        Icons.Default.ArrowForward,
                        contentDescription = stringResource(R.string.next),
                        tint = if (isAnalyzing) Color.Gray else Color.White
                    )
                }
                IconButton(
                    onClick = { if (!isAnalyzing) seekTo(report.positions.lastIndex) },
                    enabled = !isAnalyzing
                ) {
                    Icon(
                        Icons.Default.SkipNext,
                        contentDescription = stringResource(R.string.to_end),
                        tint = if (isAnalyzing) Color.Gray else Color.White
                    )
                }
            }

            if (variationActive) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFFF9800).copy(alpha = 0.2f)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.variation_mode),
                                color = Color(0xFFFF9800),
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                            Text(
                                text = stringResource(R.string.variation_hint),
                                color = Color.White.copy(alpha = 0.7f),
                                fontSize = 12.sp
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        OutlinedButton(
                            onClick = { if (!isAnalyzing) exitVariation() },
                            enabled = !isAnalyzing,
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = Color(0xFFFF9800)
                            )
                        ) {
                            Text(stringResource(R.string.exit_variation))
                        }
                    }
                }
            }

            EngineLinesPanel(
                baseFen = baseFenForPanel,
                lines = displayedLines,
                onClickMoveInLine = ::onClickPvMove,
                isBusy = isAnalysisRunning,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            )

            AnalysisSettingsPanel(
                targetDepth = targetDepth,
                onDepthChange = {
                    targetDepth = it
                    if (!variationActive) {
                        positionSettings[currentPlyIndex] = Pair(targetDepth, targetMultiPv)
                    }
                    analysisVersion++
                },
                targetMultiPv = targetMultiPv,
                onMultiPvChange = {
                    targetMultiPv = it
                    if (!variationActive) {
                        positionSettings[currentPlyIndex] = Pair(targetDepth, targetMultiPv)
                    }
                    analysisVersion++
                },
                isBusy = isAnalysisRunning,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            )

            Spacer(Modifier.height(64.dp))

            LaunchedEffect(isAutoPlaying, currentPlyIndex, isAnalyzing) {
                if (isAutoPlaying && !isAnalyzing) {
                    delay(1500)
                    if (currentPlyIndex < report.positions.lastIndex) goNext() else isAutoPlaying = false
                }
            }
        }
    }
}

@Composable
private fun PlayerCard(
    name: String,
    rating: Int?,
    clock: Int?,
    isActive: Boolean,
    inverted: Boolean,
    modifier: Modifier = Modifier
) {
    val animatedColor by animateColorAsState(
        targetValue = if (isActive) Color.White else Color.White.copy(alpha = 0.6f),
        animationSpec = tween(250),
        label = "playerCardColor"
    )
    Row(
        modifier = modifier
            .background(Color(0xFF1E1C1A), RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .background(if (inverted) Color.Black else Color.White, CircleShape)
            )
            Spacer(Modifier.width(10.dp))
            InitialAvatar(name = name, size = 32.dp)
            Spacer(Modifier.width(10.dp))
            Column {
                Text(
                    text = name,
                    color = animatedColor,
                    fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium,
                    fontSize = 16.sp
                )
                if (rating != null) {
                    Text(
                        text = "($rating)",
                        color = animatedColor.copy(alpha = 0.8f),
                        fontSize = 13.sp
                    )
                }
            }
        }

        if (clock != null) {
            Text(
                text = formatClock(clock),
                color = animatedColor.copy(alpha = 0.9f),
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

private fun formatClock(centiseconds: Int): String {
    val totalSeconds = centiseconds / 100
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val secs = totalSeconds % 60

    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, secs)
    } else {
        "%d:%02d".format(minutes, secs)
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
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            fontSize = 16.sp
        )
    }
}