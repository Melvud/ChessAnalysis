@file:Suppress("RECEIVER_NULLABILITY_MISMATCH_BASED_ON_JAVA_ANNOTATIONS")

package com.example.chessanalysis

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.example.chessanalysis.engine.EngineWebView
import com.example.chessanalysis.analysis.LocalGameAnalyzer
import com.example.chessanalysis.analysis.Openings
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
import java.util.regex.Pattern

object EngineClient {

    private const val TAG = "EngineClient"

    // ====== Режим работы движка ======
    enum class EngineMode { SERVER, LOCAL }

    private val _engineMode = MutableStateFlow(EngineMode.SERVER)
    val engineMode: StateFlow<EngineMode> = _engineMode

    // ====== Персист настроек движка ======
    private const val PREFS_NAME = "engine_prefs"
    private const val KEY_ENGINE_MODE = "engine_mode"
    private var prefs: SharedPreferences? = null

    fun setAndroidContext(ctx: Context) {
        Log.d(TAG, "Setting Android context")
        val appContext = ctx.applicationContext
        LocalEngine.setContext(appContext)

        // Инициализация openings для классификации
        Openings.init(appContext)

        // Инициализация префсов и восстановление режима
        if (prefs == null) {
            prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val saved = when (prefs?.getString(KEY_ENGINE_MODE, EngineMode.LOCAL.name)) {
                EngineMode.SERVER.name -> EngineMode.SERVER
                else -> EngineMode.LOCAL // по умолчанию — локальный
            }
            _engineMode.value = saved
            if (saved == EngineMode.LOCAL) {
                // Поднимаем локальный движок, если нужно
                runCatching { LocalEngine.ensureStarted() }
                    .onFailure { e -> Log.e(TAG, "Failed to start local engine on restore", e) }
            } else {
                LocalEngine.stop()
            }
        }
    }

    suspend fun setEngineMode(mode: EngineMode) = withContext(Dispatchers.Main) {
        Log.d(TAG, "Setting engine mode to: $mode")
        _engineMode.value = mode

        // Сохраняем выбор
        prefs?.edit()?.putString(KEY_ENGINE_MODE, mode.name)?.apply()

        if (mode == EngineMode.LOCAL) {
            runCatching { LocalEngine.ensureStarted() }
                .onFailure { e ->
                    Log.e(TAG, "Failed to start local engine", e)
                    throw e
                }
        } else {
            LocalEngine.stop()
        }
    }

    // ----- КОНФИГУРАЦИЯ СЕРВЕРА -----
    object ServerConfig {
        private const val EMULATOR_URL = "http://10.0.2.2:8080"
        private const val PRODUCTION_URL = "https://your-chess-backend.com"
        private const val IS_PRODUCTION = false
        val BASE_URL: String get() = if (IS_PRODUCTION) PRODUCTION_URL else EMULATOR_URL
    }

    // ----- Логирование HTTP -----
    private val httpLogger = HttpLoggingInterceptor { msg -> Log.d("HTTP", msg) }
        .apply { level = HttpLoggingInterceptor.Level.BODY }

    // ----- OkHttp с длинными таймаутами -----
    private val client = OkHttpClient.Builder()
        .addInterceptor(httpLogger)
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.MINUTES)
        .readTimeout(5, TimeUnit.MINUTES)
        .callTimeout(0, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    // ----- JSON -----
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    private const val UA = "ChessAnalysis/1.5 (+android; local-server)"

    // ----- Публичные state-потоки прогресса -----
    private val _percent = MutableStateFlow<Double?>(null)
    val percent: StateFlow<Double?> = _percent

    private val _stage = MutableStateFlow<String?>(null)
    val stage: StateFlow<String?> = _stage

    fun resetProgress() {
        _percent.value = null
        _stage.value = null
    }

    // ---------- DTO ----------

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

        // ---- Новые поля для «живой» доски ----
        val fen: String? = null,
        val currentSan: String? = null,
        val currentClass: String? = null
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

    // ================= Публичное API =================

    suspend fun analyzeFen(
        fen: String,
        depth: Int = 14,
        skillLevel: Int? = null
    ): StockfishResponse = withContext(Dispatchers.IO) {
        if (engineMode.value == EngineMode.LOCAL) {
            val pos = LocalEngine.evaluateFenDetailedLocal(fen, depth, 3, skillLevel)
            val top = pos.lines.firstOrNull()
            val eval = top?.cp?.let { it / 100.0 }
            StockfishResponse(true, eval, top?.mate, pos.bestMove, top?.pv?.joinToString(" "))
        } else {
            requestEvaluatePosition(fen, depth, 3, skillLevel)
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
        if (engineMode.value == EngineMode.LOCAL) {
            val analyzer = LocalGameAnalyzer { _, percent, stage ->
                _percent.value = percent
                _stage.value = stage
            }
            return@coroutineScope analyzer.evaluateGameByPgnWithProgress(
                pgn = pgn,
                depth = depth,
                multiPv = multiPv,
                workersNb = workersNb,
                header = header
            ) { snap -> onProgress(snap) }
        }

        val progressId = UUID.randomUUID().toString()
        resetProgress()
        val poller = launch(Dispatchers.IO) { pollProgress(progressId, onProgress) }
        try {
            withContext(Dispatchers.IO) {
                pingOrThrow()
                val url = "${ServerConfig.BASE_URL}/api/v1/evaluate/game?progressId=$progressId"
                val normalized = normalizePgn(pgn)
                val payload = json.encodeToString(GamePgnRequest(normalized, depth, multiPv, workersNb, header))
                val req = Request.Builder().url(url).header("Accept", "application/json").header("User-Agent", UA)
                    .post(payload.toRequestBody(JSON_MEDIA)).build()
                client.newCall(req).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) error("HTTP ${resp.code}: ${body.take(300)}")
                    json.decodeFromString<FullReport>(body)
                }
            }
        } finally {
            poller.cancel()
            poller.join()
        }
    }

    suspend fun analyzeGameByPgn(
        pgn: String,
        depth: Int = 16,
        multiPv: Int = 3,
        workersNb: Int = 2,
        header: GameHeader? = null
    ): FullReport = withContext(Dispatchers.IO) {
        if (engineMode.value == EngineMode.LOCAL) {
            val analyzer = LocalGameAnalyzer { _, percent, stage ->
                _percent.value = percent
                _stage.value = stage
            }
            return@withContext analyzer.evaluateGameByPgn(pgn, depth, multiPv, workersNb, header)
        }

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

    suspend fun analyzeMoveRealtimeDetailed(
        beforeFen: String,
        afterFen: String,
        uciMove: String,
        depth: Int = 18,
        multiPv: Int = 3,
        skillLevel: Int? = null
    ): MoveRealtimeResult = withContext(Dispatchers.IO) {
        if (engineMode.value == EngineMode.LOCAL) {
            val analyzer = LocalGameAnalyzer()
            return@withContext analyzer.analyzeMoveRealtimeDetailed(
                beforeFen, afterFen, uciMove, depth, multiPv, skillLevel
            )
        }

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

            val top = parsed.lines.firstOrNull()
            val evalAfter: Float = when {
                top?.mate != null -> if (top.mate!! > 0) 30f else -30f
                top?.cp != null -> top.cp!! / 100f
                else -> 0f
            }

            val cls = parseMoveClass(parsed.moveClassification)

            MoveRealtimeResult(evalAfter, cls, parsed.bestMove, parsed.lines.take(3))
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
        if (engineMode.value == EngineMode.LOCAL) {
            val detailed = analyzeMoveRealtimeDetailed(beforeFen, afterFen, uciMove, depth, multiPv, skillLevel)
            return@withContext Triple(detailed.evalAfter, detailed.moveClass, detailed.bestMove)
        }

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
                top?.cp != null -> top.cp!! / 100f
                else -> 0f
            }

            val cls = parseMoveClass(parsed.moveClassification)

            Triple(evalAfter, cls, parsed.bestMove)
        }
    }

    suspend fun analyzeMoveByFens(
        beforeFen: String,
        afterFen: String,
        uciMove: String,
        depth: Int = 14,
        multiPv: Int = 3
    ): Triple<Float, MoveClass, String?> = withContext(Dispatchers.IO) {
        if (engineMode.value == EngineMode.LOCAL) {
            // Используем analyzeMoveRealtimeDetailed для полной классификации
            val result = analyzeMoveRealtimeDetailed(beforeFen, afterFen, uciMove, depth, multiPv, null)
            return@withContext Triple(result.evalAfter, result.moveClass, result.bestMove)
        }

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

    suspend fun evaluateFenDetailed(
        fen: String,
        depth: Int = 14,
        multiPv: Int = 3,
        skillLevel: Int? = null
    ): PositionDTO = withContext(Dispatchers.IO) {
        if (engineMode.value == EngineMode.LOCAL) {
            LocalEngine.evaluateFenDetailedLocal(fen, depth, multiPv, skillLevel)
        } else {
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

    // ================= Вспомогательные =================

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

    // ====== Локальная реализация через WebView + UCI ======
    @SuppressLint("StaticFieldLeak")
    private object LocalEngine {
        private const val LOCAL_TAG = "LocalEngine"

        private var appCtx: Context? = null
        private var web: EngineWebView? = null
        private val started = AtomicBoolean(false)
        private val engineReady = AtomicBoolean(false)

        fun setContext(ctx: Context) {
            appCtx = ctx
        }

        fun ensureStarted() {
            if (started.get()) return

            val ctx = appCtx ?: throw IllegalStateException(
                "EngineClient: context is not set. Call setAndroidContext(context) before LOCAL mode."
            )

            engineReady.set(false)

            web = EngineWebView(ctx) { line ->
                when {
                    line == "ENGINE_READY" -> engineReady.set(true)
                    line.startsWith("info string") -> { /* игнорируем */ }
                    else -> {
                        synchronized(listeners) {
                            for (l in listeners) {
                                try {
                                    l(line)
                                } catch (e: Exception) {
                                    Log.e(LOCAL_TAG, "Listener error", e)
                                }
                            }
                        }
                    }
                }
            }.also {
                it.start()
            }

            started.set(true)

            runBlocking {
                var attempts = 0
                while (!engineReady.get() && attempts < 50) {
                    delay(100)
                    attempts++
                }
                if (!engineReady.get()) {
                    Log.e(LOCAL_TAG, "Engine failed to initialize!")
                }
            }
        }

        fun stop() {
            started.set(false)
            engineReady.set(false)
            runCatching { web?.stop() }
            web = null
            synchronized(listeners) { listeners.clear() }
        }

        private val listeners = mutableListOf<(String) -> Unit>()

        private fun addListener(l: (String) -> Unit) {
            synchronized(listeners) { listeners.add(l) }
        }

        private fun removeListener(l: (String) -> Unit) {
            synchronized(listeners) { listeners.remove(l) }
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

        private suspend fun send(cmd: String) {
            withContext(Dispatchers.Main) {
                val w = web ?: throw IllegalStateException("Local engine is not started")
                w.send(cmd)
            }
        }

        suspend fun evaluateFenDetailedLocal(
            fen: String,
            depth: Int,
            multiPv: Int,
            skillLevel: Int?
        ): PositionDTO = withTimeout(120_000) {
            ensureStarted()

            if (!engineReady.get()) {
                throw IllegalStateException("Engine not ready!")
            }

            val acc = mutableMapOf<Int, AccLine>()
            var bestMove: String? = null
            val done = CompletableDeferred<Unit>()

            val listener: (String) -> Unit = { line ->
                when {
                    rxInfo.matcher(line).matches() -> {
                        val mMp = rxMultiPv.matcher(line)
                        val mp = if (mMp.find()) mMp.group(1).toIntOrNull() ?: 1 else 1
                        val slot = acc.getOrPut(mp) { AccLine() }

                        val mDepth = rxDepth.matcher(line)
                        if (mDepth.find()) slot.depth = mDepth.group(1).toIntOrNull()

                        val mMate = rxScoreMate.matcher(line)
                        val mCp = rxScoreCp.matcher(line)
                        slot.mate = if (mMate.find()) mMate.group(1).toIntOrNull() else null
                        slot.cp = if (slot.mate == null && mCp.find()) mCp.group(1).toIntOrNull() else slot.cp

                        val mPv = rxPv.matcher(line)
                        if (mPv.find()) {
                            val pvStr = mPv.group(1)
                            slot.pv = pvStr.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
                        }
                    }
                    line.startsWith("bestmove") -> {
                        val parts = line.split("\\s+".toRegex())
                        if (parts.size >= 2) {
                            bestMove = parts[1]
                        }
                        done.complete(Unit)
                    }
                }
            }

            addListener(listener)

            try {
                send("uci")
                delay(100)
                send("isready")
                delay(100)
                send("ucinewgame")
                delay(50)

                if (skillLevel != null) {
                    send("setoption name Skill Level value $skillLevel")
                    delay(50)
                }

                if (multiPv > 1) {
                    send("setoption name MultiPV value $multiPv")
                    delay(50)
                }

                send("position fen $fen")
                delay(50)
                send("go depth $depth")

                done.await()

            } finally {
                removeListener(listener)
                runCatching { send("stop") }
            }

            val lines = acc.entries
                .sortedBy { it.key }
                .map { (mp, a) ->
                    LineDTO(
                        pv = a.pv,
                        cp = a.cp,
                        mate = a.mate,
                        depth = a.depth,
                        multiPv = mp
                    )
                }

            PositionDTO(
                lines = lines.ifEmpty { listOf(LineDTO(pv = emptyList(), cp = 0)) },
                bestMove = bestMove ?: lines.firstOrNull()?.pv?.firstOrNull()
            )
        }
    }
}
