@file:Suppress("RECEIVER_NULLABILITY_MISMATCH_BASED_ON_JAVA_ANNOTATIONS")

package com.github.movesense

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.github.movesense.engine.EngineWebView
import com.github.movesense.engine.StockfishBridge
import com.github.movesense.analysis.LocalGameAnalyzer
import com.github.movesense.analysis.Openings
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.regex.Pattern
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

@SuppressLint("StaticFieldLeak")
object EngineClient {

    private const val TAG = "EngineClient"

    /** ТРИ режима: сервер, локальный WebView и нативный (JNI) UCI. */
    enum class EngineMode { SERVER, LOCAL, NATIVE }

    private val _engineMode = MutableStateFlow(EngineMode.SERVER)
    val engineMode: StateFlow<EngineMode> = _engineMode

    private const val PREFS_NAME = "engine_prefs"
    private const val KEY_ENGINE_MODE = "engine_mode"
    private var prefs: SharedPreferences? = null

    @SuppressLint("StaticFieldLeak")
    private var appCtx: Context? = null

    fun setAndroidContext(ctx: Context) {
        Log.d(TAG, "Setting Android context")
        appCtx = ctx.applicationContext

        Openings.init(appCtx!!)

        if (prefs == null) {
            prefs = appCtx!!.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val saved = when (prefs?.getString(KEY_ENGINE_MODE, EngineMode.LOCAL.name)) {
                EngineMode.SERVER.name -> EngineMode.SERVER
                EngineMode.NATIVE.name -> EngineMode.NATIVE
                else -> EngineMode.LOCAL
            }
            _engineMode.value = saved

            when (saved) {
                EngineMode.LOCAL -> runCatching { WebLocalEngine.ensureStarted(appCtx!!) }
                    .onFailure { e -> Log.e(TAG, "Failed to start LOCAL Web engine on restore", e) }
                EngineMode.NATIVE -> runCatching { NativeUciEngine.ensureStarted(appCtx!!) }
                    .onFailure { e -> Log.e(TAG, "Failed to start NATIVE engine on restore", e) }
                EngineMode.SERVER -> {
                    WebLocalEngine.stop()
                    NativeUciEngine.stop()
                }
            }
        }
    }

    /** Переключение режима в рантайме. */
    suspend fun setEngineMode(mode: EngineMode) = withContext(Dispatchers.Main) {
        Log.d(TAG, "Setting engine mode to: $mode")
        _engineMode.value = mode
        prefs?.edit()?.putString(KEY_ENGINE_MODE, mode.name)?.apply()

        val ctx = appCtx ?: return@withContext

        when (mode) {
            EngineMode.LOCAL -> {
                NativeUciEngine.stop()
                runCatching { WebLocalEngine.ensureStarted(ctx) }
                    .onFailure { e ->
                        Log.e(TAG, "Failed to start LOCAL Web engine", e)
                        throw e
                    }
            }
            EngineMode.NATIVE -> {
                WebLocalEngine.stop()
                runCatching { NativeUciEngine.ensureStarted(ctx) }
                    .onFailure { e ->
                        Log.e(TAG, "Failed to start NATIVE engine", e)
                        throw e
                    }
            }
            EngineMode.SERVER -> {
                WebLocalEngine.stop()
                NativeUciEngine.stop()
            }
        }
    }

    object ServerConfig {
        private const val EMULATOR_URL = "http://10.0.2.2:8080"
        private const val PRODUCTION_URL = "https://your-chess-backend.com"
        private const val IS_PRODUCTION = false
        val BASE_URL: String get() = if (IS_PRODUCTION) PRODUCTION_URL else EMULATOR_URL
    }

    private val httpLogger = HttpLoggingInterceptor { msg -> Log.d("HTTP", msg) }
        .apply { level = HttpLoggingInterceptor.Level.BODY }

    private val client = OkHttpClient.Builder()
        .addInterceptor(httpLogger)
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.MINUTES)
        .readTimeout(5, TimeUnit.MINUTES)
        .callTimeout(0, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    private const val UA = "ChessAnalysis/1.5 (+android; local-server)"

    private val _percent = MutableStateFlow<Double?>(null)
    val percent: StateFlow<Double?> = _percent

    private val _stage = MutableStateFlow<String?>(null)
    val stage: StateFlow<String?> = _stage

    fun resetProgress() {
        _percent.value = null
        _stage.value = null
    }

    // ==== DTO ====

    @Serializable
    data class LineDTO(
        val pv: List<String> = emptyList(),
        val cp: Int? = null,
        val mate: Int? = null,
        val depth: Int? = null,
        val multiPv: Int? = null
    )

    @Serializable
    data class PositionDTO(
        val lines: List<LineDTO> = emptyList(),
        val bestMove: String? = null
    )

    @Serializable
    data class MoveRealtimeResult(
        val evalAfter: Float,
        val moveClass: MoveClass,
        val bestMove: String?,
        val lines: List<LineDTO>
    )

    @Serializable
    private data class MoveEvalDTO(
        val lines: List<LineDTO> = emptyList(),
        val bestMove: String? = null,
        val moveClassification: String? = null
    )

    @Serializable
    data class StockfishResponse(
        val success: Boolean,
        val evaluation: Double? = null,
        val mate: Int? = null,
        val bestmove: String? = null,
        val continuation: String? = null,
        val error: String? = null
    )

    @Serializable
    data class GamePgnRequest(
        val pgn: String,
        val depth: Int,
        val multiPv: Int,
        val workersNb: Int,
        val header: GameHeader? = null
    )

    @Serializable
    data class FensUciRequest(
        val fens: List<String>,
        val uciMoves: List<String>,
        val depth: Int = 14,
        val multiPv: Int = 3
    )

    @Serializable
    data class ProgressSnapshot(
        val id: String,
        val total: Int,
        val done: Int,
        val percent: Double? = null,
        val etaMs: Long? = null,
        val stage: String? = null,
        val startedAt: Long? = null,
        val updatedAt: Long? = null,
        val fen: String? = null,
        val currentSan: String? = null,
        val currentClass: String? = null,
        val currentUci: String? = null,
        val evalCp: Int? = null,
        val evalMate: Int? = null
    )

    @Serializable
    private data class EvaluatePositionRequest(
        val fen: String,
        val depth: Int,
        val multiPv: Int,
        val skillLevel: Int? = null
    )

    @Serializable
    private data class EvaluateMoveRealtimeRequest(
        val beforeFen: String,
        val afterFen: String,
        val uciMove: String,
        val depth: Int,
        val multiPv: Int,
        val skillLevel: Int? = null
    )

    // ==== ПУБЛИЧНЫЕ API ====

    suspend fun analyzeFen(
        fen: String,
        depth: Int = 14,
        skillLevel: Int? = null
    ): StockfishResponse = withContext(Dispatchers.IO) {
        when (engineMode.value) {
            EngineMode.LOCAL -> {
                val pos = WebLocalEngine.evaluateFenDetailedLocal(fen, depth, 3, skillLevel)
                val top = pos.lines.firstOrNull()
                val eval = top?.cp?.let { it / 100.0 }
                StockfishResponse(true, eval, top?.mate, pos.bestMove, top?.pv?.joinToString(" "))
            }
            EngineMode.NATIVE -> {
                val pos = NativeUciEngine.evaluateFenDetailedLocal(fen, depth, 3, skillLevel)
                val top = pos.lines.firstOrNull()
                val eval = top?.cp?.let { it / 100.0 }
                StockfishResponse(true, eval, top?.mate, pos.bestMove, top?.pv?.joinToString(" "))
            }
            EngineMode.SERVER -> requestEvaluatePosition(fen, depth, 3, skillLevel)
        }
    }

    suspend fun analyzeGameByPgnWithProgress(
        pgn: String,
        depth: Int = 16,
        multiPv: Int = 3,
        workersNb: Int = 2,
        header: GameHeader? = null,
        onProgress: (ProgressSnapshot) -> Unit = {}
    ): FullReport = coroutineScope {
        when (engineMode.value) {
            EngineMode.LOCAL -> {
                val analyzer = LocalGameAnalyzer { _, percent, stage ->
                    _percent.value = percent; _stage.value = stage
                }
                val clockData = parseClockDataFromPgn(pgn)
                val report = analyzer.evaluateGameByPgnWithProgress(
                    pgn, depth, multiPv, workersNb, header, onProgress
                )
                report.copy(clockData = clockData)
            }
            EngineMode.NATIVE -> {
                val analyzer = LocalGameAnalyzer { _, percent, stage ->
                    _percent.value = percent; _stage.value = stage
                }
                val clockData = parseClockDataFromPgn(pgn)
                val report = analyzer.evaluateGameByPgnWithProgress(
                    pgn, depth, multiPv, workersNb, header, onProgress
                )
                report.copy(clockData = clockData)
            }
            EngineMode.SERVER -> {
                val progressId = UUID.randomUUID().toString()
                resetProgress()
                val poller = launch(Dispatchers.IO) { pollProgress(progressId, onProgress) }
                try {
                    withContext(Dispatchers.IO) {
                        pingOrThrow()
                        val url = "${ServerConfig.BASE_URL}/api/v1/evaluate/game?progressId=$progressId"
                        val clockData = parseClockDataFromPgn(pgn)
                        val normalized = normalizePgn(pgn)
                        val payload = json.encodeToString(GamePgnRequest(normalized, depth, multiPv, workersNb, header))
                        val req = Request.Builder().url(url).header("Accept", "application/json").header("User-Agent", UA)
                            .post(payload.toRequestBody(JSON_MEDIA)).build()
                        client.newCall(req).execute().use { resp ->
                            val body = resp.body?.string().orEmpty()
                            if (!resp.isSuccessful) error("HTTP ${resp.code}: ${body.take(300)}")
                            val report = json.decodeFromString<FullReport>(body)
                            report.copy(clockData = clockData)
                        }
                    }
                } finally {
                    poller.cancel(); poller.join()
                }
            }
        }
    }

    suspend fun analyzeGameByPgn(
        pgn: String,
        depth: Int = 16,
        multiPv: Int = 3,
        workersNb: Int = 2,
        header: GameHeader? = null
    ): FullReport = withContext(Dispatchers.IO) {
        when (engineMode.value) {
            EngineMode.LOCAL, EngineMode.NATIVE -> {
                val analyzer = LocalGameAnalyzer { _, percent, stage ->
                    _percent.value = percent; _stage.value = stage
                }
                analyzer.evaluateGameByPgn(pgn, depth, multiPv, workersNb, header)
            }
            EngineMode.SERVER -> {
                pingOrThrow()
                val url = "${ServerConfig.BASE_URL}/api/v1/evaluate/game"
                val payload = json.encodeToString(GamePgnRequest(normalizePgn(pgn), depth, multiPv, workersNb, header))
                val req = Request.Builder().url(url).header("Accept", "application/json").header("User-Agent", UA)
                    .post(payload.toRequestBody(JSON_MEDIA)).build()
                client.newCall(req).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) error("http_${resp.code}: ${body.take(300)}")
                    json.decodeFromString(body)
                }
            }
        }
    }

    suspend fun analyzeMoveRealtimeDetailed(
        beforeFen: String,
        afterFen: String,
        uciMove: String,
        depth: Int = 18,
        multiPv: Int = 3,
        skillLevel: Int? = null
    ): MoveRealtimeResult = withContext(Dispatchers.IO) {
        when (engineMode.value) {
            EngineMode.LOCAL -> {
                val analyzer = LocalGameAnalyzer()
                analyzer.analyzeMoveRealtimeDetailed(beforeFen, afterFen, uciMove, depth, multiPv, skillLevel)
            }
            EngineMode.NATIVE -> {
                val analyzer = LocalGameAnalyzer()
                analyzer.analyzeMoveRealtimeDetailed(beforeFen, afterFen, uciMove, depth, multiPv, skillLevel)
            }
            EngineMode.SERVER -> {
                val url = "${ServerConfig.BASE_URL}/api/v1/evaluate/position"
                val payload = json.encodeToString(
                    EvaluateMoveRealtimeRequest(beforeFen, afterFen, uciMove, depth, multiPv, skillLevel)
                )
                val req = Request.Builder().url(url).header("Accept", "application/json").header("User-Agent", UA)
                    .post(payload.toRequestBody(JSON_MEDIA)).build()

                client.newCall(req).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) error("http_${resp.code}: ${text.take(300)}")

                    val parsed = runCatching { json.decodeFromString<MoveEvalDTO>(text) }.getOrNull()
                        ?: run {
                            val pos = json.decodeFromString<PositionDTO>(text)
                            MoveEvalDTO(lines = pos.lines, bestMove = pos.bestMove, moveClassification = null)
                        }

                    val whiteToPlayAfter = afterFen.split(" ").getOrNull(1) == "w"
                    val top = parsed.lines.firstOrNull()

                    val evalAfter: Float = when {
                        top?.mate != null -> if (whiteToPlayAfter) (if (top.mate!! > 0) 30f else -30f) else (if (top.mate!! > 0) -30f else 30f)
                        top?.cp != null -> (if (whiteToPlayAfter) top.cp!! else -top.cp!!).toFloat() / 100f
                        else -> 0f
                    }

                    val linesAfter = parsed.lines.map { line ->
                        val normalizedCp = if (whiteToPlayAfter) line.cp else line.cp?.let { -it }
                        val normalizedMate = line.mate?.let { m -> if (whiteToPlayAfter) m else -m }
                        LineDTO(
                            pv = line.pv,
                            cp = normalizedCp,
                            mate = normalizedMate,
                            depth = line.depth,
                            multiPv = line.multiPv
                        )
                    }

                    val cls = parseMoveClass(parsed.moveClassification)
                    MoveRealtimeResult(evalAfter, cls, parsed.bestMove, linesAfter.take(3))
                }
            }
        }
    }

    suspend fun analyzeMoveRealtime(
        beforeFen: String,
        afterFen: String,
        uciMove: String,
        depth: Int = 14,
        multiPv: Int = 3,
        skillLevel: Int? = null
    ): Triple<Float, MoveClass, String?> = withContext(Dispatchers.IO) {
        when (engineMode.value) {
            EngineMode.LOCAL, EngineMode.NATIVE -> {
                val detailed = analyzeMoveRealtimeDetailed(beforeFen, afterFen, uciMove, depth, multiPv, skillLevel)
                Triple(detailed.evalAfter, detailed.moveClass, detailed.bestMove)
            }
            EngineMode.SERVER -> {
                val url = "${ServerConfig.BASE_URL}/api/v1/evaluate/position"
                val payload = json.encodeToString(
                    EvaluateMoveRealtimeRequest(beforeFen, afterFen, uciMove, depth, multiPv, skillLevel)
                )
                val req = Request.Builder()
                    .url(url)
                    .header("Accept", "application/json")
                    .header("User-Agent", UA)
                    .post(payload.toRequestBody(JSON_MEDIA))
                    .build()

                client.newCall(req).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) error("http_${resp.code}: ${text.take(300)}")

                    val parsed = runCatching { json.decodeFromString<MoveEvalDTO>(text) }.getOrNull()
                        ?: run {
                            val pos = json.decodeFromString<PositionDTO>(text)
                            MoveEvalDTO(lines = pos.lines, bestMove = pos.bestMove, moveClassification = null)
                        }

                    val top = parsed.lines.firstOrNull()
                    val evalAfter: Float = when {
                        top?.mate != null -> if (top.mate!! > 0) 30f else -30f
                        top?.cp != null -> top.cp!!.toFloat() / 100f
                        else -> 0f
                    }

                    val cls = parseMoveClass(parsed.moveClassification)
                    Triple(evalAfter, cls, parsed.bestMove)
                }
            }
        }
    }

    suspend fun analyzeMoveByFens(
        beforeFen: String,
        afterFen: String,
        uciMove: String,
        depth: Int = 14,
        multiPv: Int = 3
    ): Triple<Float, MoveClass, String?> = withContext(Dispatchers.IO) {
        when (engineMode.value) {
            EngineMode.LOCAL, EngineMode.NATIVE -> {
                val result = analyzeMoveRealtimeDetailed(beforeFen, afterFen, uciMove, depth, multiPv, null)
                Triple(result.evalAfter, result.moveClass, result.bestMove)
            }
            EngineMode.SERVER -> {
                pingOrThrow()
                val url = "${ServerConfig.BASE_URL}/api/v1/evaluate/game/by-fens"
                val payload = json.encodeToString(
                    FensUciRequest(listOf(beforeFen, afterFen), listOf(uciMove), depth, multiPv)
                )
                val req = Request.Builder().url(url).header("Accept", "application/json").header("User-Agent", UA)
                    .post(payload.toRequestBody(JSON_MEDIA)).build()
                client.newCall(req).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) error("http_${resp.code}: ${text.take(300)}")
                    val report = json.decodeFromString<FullReport>(text)
                    val evalAfter = report.positions.getOrNull(1)?.lines?.firstOrNull()?.let { line ->
                        line.mate?.let { if (it > 0) 30f else -30f } ?: (line.cp?.toFloat()?.div(100f))
                    } ?: 0f
                    val cls = report.moves.firstOrNull()?.classification ?: MoveClass.OKAY
                    val best = report.positions.getOrNull(0)?.lines?.firstOrNull()?.pv?.firstOrNull()
                    Triple(evalAfter, cls, best)
                }
            }
        }
    }

    suspend fun evaluateFenDetailedStreaming(
        fen: String,
        depth: Int = 14,
        multiPv: Int = 3,
        skillLevel: Int? = null,
        onUpdate: (List<LineDTO>) -> Unit
    ): PositionDTO = withContext(Dispatchers.IO) {
        when (engineMode.value) {
            EngineMode.LOCAL -> WebLocalEngine.evaluateFenDetailedStreamingLocal(
                fen, depth, multiPv, skillLevel, onUpdate
            )
            EngineMode.NATIVE -> NativeUciEngine.evaluateFenDetailedStreamingLocal(
                fen, depth, multiPv, skillLevel, onUpdate
            )
            EngineMode.SERVER -> {
                val pos = evaluateFenDetailed(fen, depth, multiPv, skillLevel)
                onUpdate(pos.lines)
                pos
            }
        }
    }

    suspend fun evaluateFenDetailed(
        fen: String,
        depth: Int = 14,
        multiPv: Int = 3,
        skillLevel: Int? = null
    ): PositionDTO = withContext(Dispatchers.IO) {
        when (engineMode.value) {
            EngineMode.LOCAL -> WebLocalEngine.evaluateFenDetailedLocal(fen, depth, multiPv, skillLevel)
            EngineMode.NATIVE -> NativeUciEngine.evaluateFenDetailedLocal(fen, depth, multiPv, skillLevel)
            EngineMode.SERVER -> {
                val url = "${ServerConfig.BASE_URL}/api/v1/evaluate/position"
                val body = json.encodeToString(EvaluatePositionRequest(fen, depth, multiPv, skillLevel))
                val req = Request.Builder().url(url).header("Accept", "application/json").header("User-Agent", UA)
                    .post(body.toRequestBody(JSON_MEDIA)).build()
                client.newCall(req).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) error("http_${resp.code}: ${text.take(300)}")
                    json.decodeFromString(text)
                }
            }
        }
    }

    // ==== ВСПОМОГАТЕЛЬНОЕ ====

    private fun parseMoveClass(classification: String?): MoveClass {
        return when (classification?.uppercase()) {
            "BEST" -> MoveClass.BEST
            "PERFECT" -> MoveClass.PERFECT
            "SPLENDID" -> MoveClass.SPLENDID
            "EXCELLENT" -> MoveClass.EXCELLENT
            "FORCED" -> MoveClass.FORCED
            "OPENING" -> MoveClass.OPENING
            "OKAY", "GOOD" -> MoveClass.OKAY
            "INACCURACY" -> MoveClass.INACCURACY
            "MISTAKE" -> MoveClass.MISTAKE
            "BLUNDER" -> MoveClass.BLUNDER
            else -> MoveClass.OKAY
        }
    }

    private fun normalizePgn(src: String): String {
        var s = src.replace("\uFEFF", "").replace("\r\n", "\n").replace("\r", "\n")
        s = s.replace("0-0-0", "O-O-O").replace("0-0", "O-O")
        s = s.replace("1–0", "1-0").replace("0–1", "0-1")
        s = s.replace("½–½", "1/2-1/2").replace("½-½", "1/2-1/2")
        s = s.replace(Regex("""\{\[%clk [^}]+\]\}"""), "")
        s = s.replace(Regex("""\s\$\d+"""), "")
        s = buildString(s.length) { for (ch in s) if (ch == '\n' || ch == '\t' || ch.code >= 32) append(ch) }
        val tagBlockRegex = Regex("""\A(?:\[[^\]\n]+\]\s*\n)+""")
        val tagMatch = tagBlockRegex.find(s)
        val rebuilt = if (tagMatch != null) {
            val tagBlock = tagMatch.value.trimEnd('\n')
            val rest = s.substring(tagMatch.range.last + 1)
            val movetext = rest.trimStart('\n', ' ', '\t')
            tagBlock + "\n\n" + movetext
        } else s.trimStart('\n', ' ', '\t')
        var out = rebuilt.trimEnd()
        if (!out.endsWith("\n")) out += "\n"
        return out
    }

    private suspend fun pingOrThrow() = withContext(Dispatchers.IO) {
        val url = "${ServerConfig.BASE_URL}/ping"
        try {
            val getReq = Request.Builder().url(url).header("User-Agent", UA).get().build()
            client.newCall(getReq).execute().use { resp ->
                if (!resp.isSuccessful) error("Ping failed ${resp.code}")
            }
        } catch (e: Exception) {
            throw IllegalStateException("Cannot reach server at ${ServerConfig.BASE_URL}", e)
        }
    }

    private fun requestEvaluatePosition(
        fen: String,
        depth: Int,
        multiPv: Int,
        skillLevel: Int? = null
    ): StockfishResponse {
        val url = "${ServerConfig.BASE_URL}/api/v1/evaluate/position"
        val bodyStr = json.encodeToString(EvaluatePositionRequest(fen, depth, multiPv, skillLevel))
        val req = Request.Builder().url(url).header("Accept", "application/json").header("User-Agent", UA)
            .post(bodyStr.toRequestBody(JSON_MEDIA)).build()

        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful || text.isBlank()) {
                return StockfishResponse(false, error = "http_${resp.code}: ${text.take(200)}")
            }
            return try {
                val dto = json.decodeFromString<PositionDTO>(text)
                val top = dto.lines.firstOrNull()
                val evaluationPawns: Double? = top?.cp?.let { it / 100.0 }
                StockfishResponse(true, evaluationPawns, top?.mate, dto.bestMove, top?.pv?.joinToString(" "))
            } catch (e: Exception) {
                StockfishResponse(false, error = "json_parse_failed: ${e.message}")
            }
        }
    }

    private suspend fun pollProgress(
        progressId: String,
        onUpdate: (ProgressSnapshot) -> Unit
    ) {
        while (currentCoroutineContext().isActive) {
            try {
                val req = Request.Builder()
                    .url("${ServerConfig.BASE_URL}/api/v1/progress/$progressId")
                    .header("Accept", "application/json")
                    .header("User-Agent", UA)
                    .get()
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val body = resp.body?.string().orEmpty()
                        if (body.isNotBlank()) {
                            val snap = json.decodeFromString<ProgressSnapshot>(body)
                            _percent.value = snap.percent
                            _stage.value = snap.stage
                            onUpdate(snap)
                            if (snap.stage == "done" || (snap.total > 0 && snap.done >= snap.total)) return
                        }
                    }
                }
            } catch (_: Exception) { }
            delay(400)
        }
    }

    // ===========================================================
    //               ЛОКАЛЬНЫЙ WEBVIEW-ДВИЖОК (как было)
    // ===========================================================
    private object WebLocalEngine {
        private const val LOCAL_TAG = "WebLocalEngine"

        private var web: EngineWebView? = null
        private val started = AtomicBoolean(false)
        private val engineReady = AtomicBoolean(false)
        private val analysisCounter = AtomicInteger(0)

        private val engineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        private val engineMutex = Mutex()
        private var currentAnalysisJob: Job? = null

        private val listeners = mutableListOf<(String) -> Unit>()

        private fun addListener(l: (String) -> Unit) {
            synchronized(listeners) { listeners.add(l) }
        }
        private fun removeListener(l: (String) -> Unit) {
            synchronized(listeners) { listeners.remove(l) }
        }

        fun ensureStarted(ctx: Context) {
            if (started.getAndSet(true)) {
                Log.d(LOCAL_TAG, "Engine already started")
                return
            }
            web = EngineWebView.getInstance(ctx) { line ->
                when {
                    line == "ENGINE_READY" -> {
                        engineReady.set(true)
                        web?.markInitialized()
                        Log.d(LOCAL_TAG, "✓ ENGINE_READY received")
                    }
                    else -> {
                        synchronized(listeners) {
                            listeners.forEach { l ->
                                try { l(line) } catch (_: Exception) {}
                            }
                        }
                    }
                }
            }
            web?.start()
            engineScope.launch {
                var attempts = 0
                while (!engineReady.get() && attempts < 200) { delay(100); attempts++ }
                if (!engineReady.get()) Log.e(LOCAL_TAG, "Init timeout")
            }
        }

        fun stop() {
            Log.d(LOCAL_TAG, "stop(): WebView stays alive (no-op)")
        }

        private suspend fun send(cmd: String) = withContext(Dispatchers.Main) {
            val w = web ?: throw IllegalStateException("Local Web engine is not started")
            w.send(cmd)
        }

        private suspend fun waitForReady(timeoutMs: Long = 20000): Boolean {
            val startTime = System.currentTimeMillis()
            while (!engineReady.get()) {
                if (System.currentTimeMillis() - startTime > timeoutMs) return false
                delay(100)
            }
            return true
        }

        private suspend fun sendAndWaitReady(cmd: String, timeoutMs: Long = 3000): Boolean {
            val readySignal = CompletableDeferred<Unit>()
            val readyListener: (String) -> Unit = { line -> if (line == "readyok") readySignal.complete(Unit) }
            addListener(readyListener)
            return try {
                send(cmd)
                withTimeout(timeoutMs) { readySignal.await() }
                true
            } catch (_: TimeoutCancellationException) {
                false
            } finally { removeListener(readyListener) }
        }

        private val rxInfo = Pattern.compile("""^info\s+.*\bdepth\s+\d+.*""")
        private val rxDepth = Pattern.compile("""\bdepth\s+(\d+)""")
        private val rxMultiPv = Pattern.compile("""\bmultipv\s+(\d+)""")
        private val rxScoreCp = Pattern.compile("""\bscore\s+cp\s+(-?\d+)""")
        private val rxScoreMate = Pattern.compile("""\bscore\s+mate\s+(-?\d+)""")
        private val rxPv = Pattern.compile("""\bpv\s+(.+)$""")

        private data class AccLine(
            var depth: Int? = null,
            var cp: Int? = null,
            var mate: Int? = null,
            var pv: List<String> = emptyList()
        )

        suspend fun evaluateFenDetailedLocal(
            fen: String,
            depth: Int,
            multiPv: Int,
            skillLevel: Int?
        ): PositionDTO = withTimeout(120_000) {
            currentAnalysisJob?.cancel()
            val analysisId = analysisCounter.incrementAndGet()
            engineMutex.withLock {
                if (!waitForReady()) throw IllegalStateException("Engine not ready!")
                val acc = mutableMapOf<Int, AccLine>()
                var bestMove: String? = null
                val done = CompletableDeferred<Unit>()
                val listener: (String) -> Unit = { line ->
                    when {
                        rxInfo.matcher(line).matches() -> {
                            val mp = rxMultiPv.matcher(line).let { if (it.find()) it.group(1).toIntOrNull() ?: 1 else 1 }
                            val slot = acc.getOrPut(mp) { AccLine() }
                            rxDepth.matcher(line).apply { if (find()) slot.depth = group(1).toIntOrNull() }
                            val mMate = rxScoreMate.matcher(line)
                            val mCp = rxScoreCp.matcher(line)
                            slot.mate = if (mMate.find()) mMate.group(1).toIntOrNull() else null
                            slot.cp = if (slot.mate == null && mCp.find()) mCp.group(1).toIntOrNull() else slot.cp
                            rxPv.matcher(line).apply {
                                if (find()) slot.pv = group(1).trim().split(Regex("\\s+")).filter { it.isNotBlank() }
                            }
                        }
                        line.startsWith("bestmove") -> {
                            val parts = line.split("\\s+".toRegex())
                            if (parts.size >= 2) bestMove = parts[1]
                            done.complete(Unit)
                        }
                    }
                }
                addListener(listener)
                try {
                    send("stop"); delay(150)
                    sendAndWaitReady("isready")
                    send("ucinewgame"); delay(100)
                    sendAndWaitReady("isready")
                    if (skillLevel != null) { send("setoption name Skill Level value $skillLevel"); delay(50) }
                    if (multiPv > 1) { send("setoption name MultiPV value $multiPv"); delay(50) }
                    sendAndWaitReady("isready")
                    send("position fen $fen"); delay(100)
                    send("go depth $depth")
                    withTimeout(110_000) { done.await() }
                } finally { removeListener(listener) }
                val lines = acc.entries.sortedBy { it.key }.map { (mp, a) ->
                    LineDTO(a.pv, a.cp, a.mate, a.depth, mp)
                }
                PositionDTO(lines.ifEmpty { listOf(LineDTO(pv = emptyList(), cp = 0)) }, bestMove ?: lines.firstOrNull()?.pv?.firstOrNull())
            }
        }

        suspend fun evaluateFenDetailedStreamingLocal(
            fen: String,
            depth: Int,
            multiPv: Int,
            skillLevel: Int?,
            onUpdate: (List<LineDTO>) -> Unit
        ): PositionDTO = coroutineScope {
            currentAnalysisJob?.cancel()
            val finalResult = CompletableDeferred<PositionDTO>()
            currentAnalysisJob = launch {
                engineMutex.withLock {
                    if (!waitForReady()) throw IllegalStateException("Engine not ready!")
                    val acc = mutableMapOf<Int, AccLine>()
                    var bestMove: String? = null
                    val done = CompletableDeferred<Unit>()
                    fun emit() {
                        val snapshot = acc.entries.sortedBy { it.key }.map { (mp, a) ->
                            LineDTO(a.pv, a.cp, a.mate, a.depth, mp)
                        }
                        if (snapshot.isNotEmpty()) onUpdate(snapshot)
                    }
                    var reached = 0
                    val listener: (String) -> Unit = { line ->
                        when {
                            rxInfo.matcher(line).matches() -> {
                                val mp = rxMultiPv.matcher(line).let { if (it.find()) it.group(1).toIntOrNull() ?: 1 else 1 }
                                val slot = acc.getOrPut(mp) { AccLine() }
                                rxDepth.matcher(line).apply { if (find()) { slot.depth = group(1).toIntOrNull(); reached = maxOf(reached, slot.depth ?: 0) } }
                                val mMate = rxScoreMate.matcher(line); val mCp = rxScoreCp.matcher(line)
                                slot.mate = if (mMate.find()) mMate.group(1).toIntOrNull() else null
                                slot.cp = if (slot.mate == null && mCp.find()) mCp.group(1).toIntOrNull() else slot.cp
                                rxPv.matcher(line).apply { if (find()) slot.pv = group(1).trim().split(Regex("\\s+")).filter { it.isNotBlank() } }
                                emit(); if (reached >= depth && acc.isNotEmpty()) done.complete(Unit)
                            }
                            line.startsWith("bestmove") -> {
                                val parts = line.split("\\s+".toRegex()); if (parts.size >= 2) bestMove = parts[1]
                                emit(); done.complete(Unit)
                            }
                        }
                    }
                    addListener(listener)
                    try {
                        send("stop"); delay(150)
                        sendAndWaitReady("isready")
                        send("ucinewgame"); delay(100)
                        sendAndWaitReady("isready")
                        if (skillLevel != null) { send("setoption name Skill Level value $skillLevel"); delay(50) }
                        send("setoption name MultiPV value ${multiPv.coerceAtLeast(1)}"); delay(50)
                        sendAndWaitReady("isready")
                        send("position fen $fen"); delay(100)
                        send("go depth $depth")
                        withTimeout(170_000) { done.await() }
                        val lines = acc.entries.sortedBy { it.key }.map { (mp, a) ->
                            LineDTO(a.pv, a.cp, a.mate, a.depth, mp)
                        }
                        finalResult.complete(
                            PositionDTO(lines.ifEmpty { listOf(LineDTO(pv = emptyList(), cp = 0)) }, bestMove ?: lines.firstOrNull()?.pv?.firstOrNull())
                        )
                    } catch (e: Exception) {
                        finalResult.completeExceptionally(e); throw e
                    } finally { removeListener(listener) }
                }
            }
            try { finalResult.await() } catch (_: CancellationException) {
                PositionDTO(listOf(LineDTO(pv = emptyList(), cp = 0)), null)
            }
        }
    }

    // ===========================================================
    //                     НАТИВНЫЙ ДВИЖОК (JNI)
    // ===========================================================
    private object NativeUciEngine {
        private const val NATIVE_TAG = "NativeUciEngine"

        private val started = AtomicBoolean(false)
        private val engineMutex = Mutex()
        private val analysisCounter = AtomicInteger(0)
        private var currentAnalysisJob: Job? = null

        // Регексы для разбора info/bestmove
        private val rxInfo = Pattern.compile("""^info\s+.*\bdepth\s+\d+.*""")
        private val rxDepth = Pattern.compile("""\bdepth\s+(\d+)""")
        private val rxMultiPv = Pattern.compile("""\bmultipv\s+(\d+)""")
        private val rxScoreCp = Pattern.compile("""\bscore\s+cp\s+(-?\d+)""")
        private val rxScoreMate = Pattern.compile("""\bscore\s+mate\s+(-?\d+)""")
        private val rxPv = Pattern.compile("""\bpv\s+(.+)$""")

        private data class AccLine(
            var depth: Int? = null,
            var cp: Int? = null,
            var mate: Int? = null,
            var pv: List<String> = emptyList()
        )

        fun ensureStarted(ctx: Context) {
            if (started.getAndSet(true)) return
            Log.d(NATIVE_TAG, "Native engine (JNI) starting...")

            val assetDir = "nnue"
            val assetManager = ctx.assets
            val evalPath: String? = try {
                val names = assetManager.list(assetDir)?.filter { it.endsWith(".nnue") }.orEmpty()
                if (names.isEmpty()) null else {
                    val chosen = names.sorted().last()
                    val dstDir = File(ctx.filesDir, assetDir).apply { mkdirs() }
                    val dst = File(dstDir, chosen)
                    if (!dst.exists()) {
                        assetManager.open("$assetDir/$chosen").use { input ->
                            dst.outputStream().use { output -> input.copyTo(output) }
                        }
                    }
                    dst.absolutePath
                }
            } catch (t: Throwable) {
                Log.w(NATIVE_TAG, "Failed to prepare NNUE from assets: ${t.message}")
                null
            }

            StockfishBridge.ensureStarted(evalPath)

            runCatching { send("isready") }
            Log.d(NATIVE_TAG, "Native engine (JNI) started; EvalFile=${evalPath ?: "none"}")
        }


        fun stop() {
            // У knightvision API нет "quit"; lib живёт в процессе.
            // Можно ничего не делать — при смене режима просто перестаём её звать.
            started.set(false)
        }

        private fun send(cmd: String): String = StockfishBridge.send(cmd)
        private fun go(depth: Int): String = StockfishBridge.go(depth)

        /** Быстрая проверка готовности: isready -> "readyok" в ответе. */
        private fun isReady(): Boolean = send("isready").lineSequence().any { it.trim() == "readyok" }

        private suspend fun waitForReady(timeoutMs: Long = 20_000): Boolean = withContext(Dispatchers.IO) {
            val start = System.currentTimeMillis()
            while (!isReady()) {
                if (System.currentTimeMillis() - start > timeoutMs) return@withContext false
                delay(100)
            }
            true
        }

        suspend fun evaluateFenDetailedLocal(
            fen: String,
            depth: Int,
            multiPv: Int,
            skillLevel: Int?
        ): PositionDTO = withTimeout(120_000) {
            currentAnalysisJob?.cancel()
            val ctx = appCtx ?: throw IllegalStateException("Context not set")
            ensureStarted(ctx)
            engineMutex.withLock {
                if (!waitForReady()) throw IllegalStateException("Native engine not ready")

                // Настройка
                if (skillLevel != null) send("setoption name Skill Level value $skillLevel")
                if (multiPv > 1)      send("setoption name MultiPV value $multiPv")
                send("ucinewgame")
                send("position fen $fen")

                // Запуск поиска
                val output = go(depth)

                // Парс всего вывода (info/bestmove)
                val acc = mutableMapOf<Int, AccLine>()
                var bestMove: String? = null
                output.lineSequence().forEach { line ->
                    when {
                        rxInfo.matcher(line).matches() -> {
                            val mp = rxMultiPv.matcher(line).let { if (it.find()) it.group(1).toIntOrNull() ?: 1 else 1 }
                            val slot = acc.getOrPut(mp) { AccLine() }
                            rxDepth.matcher(line).apply { if (find()) slot.depth = group(1).toIntOrNull() }
                            val mMate = rxScoreMate.matcher(line); val mCp = rxScoreCp.matcher(line)
                            slot.mate = if (mMate.find()) mMate.group(1).toIntOrNull() else null
                            slot.cp   = if (slot.mate == null && mCp.find()) mCp.group(1).toIntOrNull() else slot.cp
                            rxPv.matcher(line).apply {
                                if (find()) slot.pv = group(1).trim().split(Regex("\\s+")).filter { it.isNotBlank() }
                            }
                        }
                        line.startsWith("bestmove") -> {
                            val parts = line.split(Regex("\\s+"))
                            if (parts.size >= 2) bestMove = parts[1]
                        }
                    }
                }

                val lines = acc.entries.sortedBy { it.key }.map { (mp, a) ->
                    LineDTO(a.pv, a.cp, a.mate, a.depth, mp)
                }
                PositionDTO(lines.ifEmpty { listOf(LineDTO(pv = emptyList(), cp = 0)) }, bestMove ?: lines.firstOrNull()?.pv?.firstOrNull())
            }
        }

        /**
         * "Стриминговая" версия — имитируем поток, ломая поиск на итерации по глубине.
         * Чтобы не дергать движок слишком часто, растём крупными шагами.
         */
        suspend fun evaluateFenDetailedStreamingLocal(
            fen: String,
            depth: Int,
            multiPv: Int,
            skillLevel: Int?,
            onUpdate: (List<LineDTO>) -> Unit
        ): PositionDTO = coroutineScope {
            currentAnalysisJob?.cancel()
            val ctx = appCtx ?: throw IllegalStateException("Context not set")
            ensureStarted(ctx)
            val finalResult = CompletableDeferred<PositionDTO>()
            currentAnalysisJob = launch(Dispatchers.IO) {
                engineMutex.withLock {
                    if (!waitForReady()) throw IllegalStateException("Native engine not ready")

                    if (skillLevel != null) send("setoption name Skill Level value $skillLevel")
                    send("setoption name MultiPV value ${multiPv.coerceAtLeast(1)}")
                    send("ucinewgame")
                    send("position fen $fen")

                    val acc = mutableMapOf<Int, AccLine>()
                    var bestMove: String? = null

                    // шаг по глубине: 4, 8, ..., depth
                    val step = maxOf(1, depth / 6)
                    var d = step
                    while (d < depth) {
                        val out = go(d)
                        parseOutput(out, acc).also { bm -> if (bm != null) bestMove = bm }
                        val snapshot = acc.entries.sortedBy { it.key }.map { (mp, a) ->
                            LineDTO(a.pv, a.cp, a.mate, a.depth, mp)
                        }
                        if (snapshot.isNotEmpty()) onUpdate(snapshot)
                        d += step
                    }
                    // финальный прогон до целевой глубины
                    val finalOut = go(depth)
                    parseOutput(finalOut, acc).also { bm -> if (bm != null) bestMove = bm }
                    val lines = acc.entries.sortedBy { it.key }.map { (mp, a) ->
                        LineDTO(a.pv, a.cp, a.mate, a.depth, mp)
                    }
                    finalResult.complete(
                        PositionDTO(lines.ifEmpty { listOf(LineDTO(pv = emptyList(), cp = 0)) }, bestMove ?: lines.firstOrNull()?.pv?.firstOrNull())
                    )
                }
            }
            try { finalResult.await() } catch (_: CancellationException) {
                PositionDTO(listOf(LineDTO(pv = emptyList(), cp = 0)), null)
            }
        }

        private fun parseOutput(out: String, acc: MutableMap<Int, AccLine>): String? {
            var best: String? = null
            out.lineSequence().forEach { line ->
                when {
                    rxInfo.matcher(line).matches() -> {
                        val mp = rxMultiPv.matcher(line).let { if (it.find()) it.group(1).toIntOrNull() ?: 1 else 1 }
                        val slot = acc.getOrPut(mp) { AccLine() }
                        rxDepth.matcher(line).apply { if (find()) slot.depth = group(1).toIntOrNull() }
                        val mMate = rxScoreMate.matcher(line); val mCp = rxScoreCp.matcher(line)
                        slot.mate = if (mMate.find()) mMate.group(1).toIntOrNull() else null
                        slot.cp   = if (slot.mate == null && mCp.find()) mCp.group(1).toIntOrNull() else slot.cp
                        rxPv.matcher(line).apply {
                            if (find()) slot.pv = group(1).trim().split(Regex("\\s+")).filter { it.isNotBlank() }
                        }
                    }
                    line.startsWith("bestmove") -> {
                        val parts = line.split(Regex("\\s+"))
                        if (parts.size >= 2) best = parts[1]
                    }
                }
            }
            return best
        }
    }

    // ==== Вспомогательные вещи общего уровня ====

    private fun parseClockDataFromPgn(pgn: String): ClockData {
        val clockPattern = Regex("""\[%clk\s+(?:(\d+):)?(\d{1,2}):(\d{1,2})(?:\.(\d+))?\]""", RegexOption.IGNORE_CASE)
        val whiteTimes = mutableListOf<Int>()
        val blackTimes = mutableListOf<Int>()
        var plyIndex = 0
        clockPattern.findAll(pgn).forEach { match ->
            val hours = match.groups[1]?.value?.toIntOrNull() ?: 0
            val minutes = match.groups[2]?.value?.toIntOrNull() ?: 0
            val seconds = match.groups[3]?.value?.toIntOrNull() ?: 0
            val centiseconds = (hours * 3600 + minutes * 60 + seconds) * 100
            if (plyIndex % 2 == 0) whiteTimes.add(centiseconds) else blackTimes.add(centiseconds)
            plyIndex++
        }
        return ClockData(white = whiteTimes, black = blackTimes)
    }
}
