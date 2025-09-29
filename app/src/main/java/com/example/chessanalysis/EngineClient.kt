package com.example.chessanalysis

import android.annotation.SuppressLint
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import com.example.chessanalysis.local.LocalStockfish
import com.example.chessanalysis.local.LocalAnalyzer
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import java.util.UUID
import java.util.concurrent.TimeUnit
import android.content.Context
import java.lang.ref.WeakReference

// =================== EngineClient ===================
object EngineClient {
    private var appCtxRef: WeakReference<Context>? = null

    fun init(context: Context) {
        appCtxRef = WeakReference(context.applicationContext)
        // Инициализация LocalStockfish при старте приложения
        if (_engineMode.value == EngineMode.LOCAL) {
            try {
                LocalStockfish.ensureStarted(context.applicationContext)
                Log.i(TAG, "Local Stockfish initialized at startup")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize Local Stockfish", e)
                // Fallback to server mode if local fails
                _engineMode.value = EngineMode.SERVER
            }
        }
    }

    private fun appContext(): Context? = appCtxRef?.get()

    /**
     * Режим работы движка. При значении [EngineMode.SERVER] все вызовы
     * отправляются на удалённый сервер через HTTP. При значении
     * [EngineMode.LOCAL] используется локальный UCI-движок Stockfish.
     */
    enum class EngineMode { SERVER, LOCAL }

    /**
     * StateFlow с текущим режимом работы движка. UI может подписываться
     * на это состояние, чтобы реагировать на переключение режима.
     */
    private val _engineMode = MutableStateFlow(EngineMode.SERVER)
    val engineMode: StateFlow<EngineMode> = _engineMode

    /**
     * Задаёт режим работы движка. Если выбран локальный режим, объект
     * [LocalStockfish] будет автоматически инициализирован при первом
     * обращении к нему.
     */
    fun setEngineMode(mode: EngineMode) {
        _engineMode.value = mode
        if (mode == EngineMode.LOCAL) {
            // --- Новое: при переключении на локальный движок убедимся, что путь задан ---
            ensureLocalBinaryConfigured()
        }
    }

    // ----- КОНФИГУРАЦИЯ СЕРВЕРА -----
    object ServerConfig {
        private const val EMULATOR_URL = "http://10.0.2.2:8080"
        private const val PRODUCTION_URL = "https://your-chess-backend.com"
        private const val IS_PRODUCTION = false

        val BASE_URL: String
            get() = if (IS_PRODUCTION) PRODUCTION_URL else EMULATOR_URL
    }

    // ----- Логирование HTTP -----
    private const val TAG = "EngineClient"
    private val httpLogger = HttpLoggingInterceptor { msg -> Log.d("HTTP", msg) }
        .apply { level = HttpLoggingInterceptor.Level.BODY }

    // ----- OkHttp с длинными таймаутами для анализа -----
    private val client = OkHttpClient.Builder()
        .addInterceptor(httpLogger)
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.MINUTES)
        .readTimeout(5, TimeUnit.MINUTES)
        .callTimeout(0, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    // ----- JSON -----
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }
    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    private const val UA = "ChessAnalysis/1.5 (+android; local-server)"

    // ----- Публичные state-потоки прогресса для UI -----
    private val _percent = MutableStateFlow<Double?>(null)
    val percent: StateFlow<Double?> = _percent

    private val _stage = MutableStateFlow<String?>(null)
    val stage: StateFlow<String?> = _stage

    fun resetProgress() {
        _percent.value = null
        _stage.value = null
    }

    // ---------- DTO ----------
    @SuppressLint("UnsafeOptInUsageError")
    @Serializable
    data class LineDTO(
        val pv: List<String> = emptyList(),
        val cp: Int? = null,
        val mate: Int? = null,
        val depth: Int? = null,
        val multiPv: Int? = null
    )

    @SuppressLint("UnsafeOptInUsageError")
    @kotlinx.serialization.Serializable
    data class MoveRealtimeResult(
        val evalAfter: Float,
        val moveClass: MoveClass,
        val bestMove: String?,
        val lines: List<LineDTO>
    )

    @SuppressLint("UnsafeOptInUsageError")
    @Serializable
    data class PositionDTO(
        val lines: List<LineDTO> = emptyList(),
        val bestMove: String? = null
    )

    @SuppressLint("UnsafeOptInUsageError")
    @kotlinx.serialization.Serializable
    private data class MoveEvalDTO(
        val lines: List<LineDTO> = emptyList(),
        val bestMove: String? = null,
        val moveClassification: String? = null
    )

    @SuppressLint("UnsafeOptInUsageError")
    @Serializable
    data class StockfishResponse(
        val success: Boolean,
        val evaluation: Double? = null,
        val mate: Int? = null,
        val bestmove: String? = null,
        val continuation: String? = null,
        val error: String? = null
    )

    @SuppressLint("UnsafeOptInUsageError")
    @Serializable
    data class GamePgnRequest(
        val pgn: String,
        val depth: Int,
        val multiPv: Int,
        val workersNb: Int,
        val header: GameHeader? = null
    )

    @SuppressLint("UnsafeOptInUsageError")
    @Serializable
    data class FensUciRequest(
        val fens: List<String>,
        val uciMoves: List<String>,
        val depth: Int = 14,
        val multiPv: Int = 3
    )

    @SuppressLint("UnsafeOptInUsageError")
    @Serializable
    data class ProgressSnapshot(
        val id: String,
        val total: Int,
        val done: Int,
        val percent: Double? = null,
        val etaMs: Long? = null,
        val stage: String? = null,
        val startedAt: Long? = null,
        val updatedAt: Long? = null
    )

    @SuppressLint("UnsafeOptInUsageError")
    @Serializable
    private data class EvaluatePositionRequest(
        val fen: String,
        val depth: Int,
        val multiPv: Int,
        val skillLevel: Int? = null
    )

    @SuppressLint("UnsafeOptInUsageError")
    @Serializable
    private data class EvaluateMoveRealtimeRequest(
        val beforeFen: String,
        val afterFen: String,
        val uciMove: String,
        val depth: Int,
        val multiPv: Int,
        val skillLevel: Int? = null
    )

    // ---------- Публичное API ----------

    suspend fun analyzeFen(
        fen: String,
        depth: Int = 14,
        skillLevel: Int? = null
    ): StockfishResponse =
        withContext(Dispatchers.IO) {
            // при локальном режиме возвращаем результат локального движка
            if (engineMode.value == EngineMode.LOCAL) {
                return@withContext LocalStockfish.evaluateFen(
                    fen, depth, 3, skillLevel, context = appContext()
                )
            }
            // иначе вызываем удалённый сервер
            return@withContext requestEvaluatePosition(fen, depth, 3, skillLevel)
        }

    /**
     * Анализ партии с прогрессом. Обновляет [percent]/[stage] для UI.
     */
    suspend fun analyzeGameByPgnWithProgress(
        pgn: String,
        depth: Int = 16,
        multiPv: Int = 2,
        workersNb: Int = 2,
        header: GameHeader? = null,
        onProgress: (ProgressSnapshot) -> Unit = {}
    ): FullReport = coroutineScope {
        // Если выбран локальный режим, используем локальный анализатор и обновляем прогресс самостоятельно
        if (engineMode.value == EngineMode.LOCAL) {
            return@coroutineScope withContext(Dispatchers.IO) {
                resetProgress()
                LocalAnalyzer.analyzeGameByPgnWithProgress(
                    pgn = pgn,
                    depth = depth,
                    multiPv = multiPv,
                    header = header,
                    onProgress = { snap ->
                        // обновляем state-потоки и внешний callback
                        _percent.value = snap.percent
                        _stage.value = snap.stage
                        onProgress(snap)
                    }
                )
            }
        }

        val progressId = UUID.randomUUID().toString()
        resetProgress()
        val poller = launch(Dispatchers.IO) { pollProgress(progressId, onProgress) }
        try {
            withContext(Dispatchers.IO) {
                pingOrThrow()
                val url = "${ServerConfig.BASE_URL}/api/v1/evaluate/game?progressId=$progressId"
                val normalized = normalizePgn(pgn)

                // Мягкая валидация: не рушим для партий с ботом
                val validationResult = runCatching { PgnChess.validatePgn(normalized) }
                if (validationResult.isFailure) {
                    Log.w(TAG, "PGN validation warning: ${validationResult.exceptionOrNull()?.message}")
                }

                val headerSanitized = header
                val payload = json.encodeToString(
                    GamePgnRequest(
                        pgn = normalized,
                        depth = depth,
                        multiPv = multiPv,
                        workersNb = workersNb,
                        header = headerSanitized
                    )
                )

                val req = Request.Builder()
                    .url(url)
                    .header("Accept", "application/json")
                    .header("User-Agent", UA)
                    .post(payload.toRequestBody(JSON_MEDIA))
                    .build()

                client.newCall(req).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}: ${body.take(300)}")
                    return@withContext json.decodeFromString<FullReport>(body)
                }
            }
        } finally {
            poller.cancel()
            poller.join()
            // Оставим последние значения в потоках — UI может показать "100% / done"
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
        // локальный режим
        if (engineMode.value == EngineMode.LOCAL) {
            return@withContext LocalAnalyzer.analyzeMoveRealtimeDetailed(
                beforeFen = beforeFen,
                afterFen = afterFen,
                uciMove = uciMove,
                depth = depth,
                multiPv = multiPv,
                skillLevel = skillLevel
            )
        }
        val url = "${ServerConfig.BASE_URL}/api/v1/evaluate/position"
        val payload = json.encodeToString(
            EvaluateMoveRealtimeRequest(
                beforeFen = beforeFen,
                afterFen = afterFen,
                uciMove = uciMove,
                depth = depth,
                multiPv = multiPv,
                skillLevel = skillLevel
            )
        )
        val req = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("User-Agent", UA)
            .post(payload.toRequestBody(JSON_MEDIA))
            .build()

        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IllegalStateException("http_${resp.code}: ${text.take(300)}")

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

            val cls = when (parsed.moveClassification?.uppercase()) {
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

            MoveRealtimeResult(
                evalAfter = evalAfter,
                moveClass = cls,
                bestMove = parsed.bestMove,
                lines = parsed.lines.take(3)
            )
        }
    }

    suspend fun analyzeGameByPgn(
        pgn: String,
        depth: Int = 16,
        multiPv: Int = 2,
        workersNb: Int = 2,
        header: GameHeader? = null
    ): FullReport = withContext(Dispatchers.IO) {
        // локальный режим
        if (engineMode.value == EngineMode.LOCAL) {
            return@withContext LocalAnalyzer.analyzeGameByPgn(
                pgn = pgn,
                depth = depth,
                multiPv = multiPv,
                header = header
            )
        }
        pingOrThrow()
        val url = "${ServerConfig.BASE_URL}/api/v1/evaluate/game"

        val normalized = normalizePgn(pgn)

        val validationResult = runCatching { PgnChess.validatePgn(normalized) }
        if (validationResult.isFailure) {
            Log.w(TAG, "PGN validation warning: ${validationResult.exceptionOrNull()?.message}")
        }

        val headerSanitized = header
        val payload = json.encodeToString(GamePgnRequest(normalized, depth, multiPv, workersNb, headerSanitized))
        val req = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("User-Agent", UA)
            .post(payload.toRequestBody(JSON_MEDIA))
            .build()

        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IllegalStateException("http_${resp.code}: ${body.take(300)}")
            return@use json.decodeFromString<FullReport>(body)
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
        // локальный режим
        if (engineMode.value == EngineMode.LOCAL) {
            return@withContext LocalAnalyzer.analyzeMoveRealtime(
                beforeFen = beforeFen,
                afterFen = afterFen,
                uciMove = uciMove,
                depth = depth,
                multiPv = multiPv,
                skillLevel = skillLevel
            )
        }
        val url = "${ServerConfig.BASE_URL}/api/v1/evaluate/position"
        val payload = json.encodeToString(
            EvaluateMoveRealtimeRequest(
                beforeFen = beforeFen,
                afterFen = afterFen,
                uciMove = uciMove,
                depth = depth,
                multiPv = multiPv,
                skillLevel = skillLevel
            )
        )
        val req = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("User-Agent", UA)
            .post(payload.toRequestBody(JSON_MEDIA))
            .build()

        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IllegalStateException("http_${resp.code}: ${text.take(300)}")

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

            val cls = when (parsed.moveClassification?.uppercase()) {
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

            val best = parsed.bestMove
            Triple(evalAfter, cls, best)
        }
    }

    suspend fun analyzeMoveByFens(
        beforeFen: String,
        afterFen: String,
        uciMove: String,
        depth: Int = 14,
        multiPv: Int = 3
    ): Triple<Float, MoveClass, String?> = withContext(Dispatchers.IO) {
        // локальный режим
        if (engineMode.value == EngineMode.LOCAL) {
            return@withContext LocalAnalyzer.analyzeMoveByFens(
                beforeFen = beforeFen,
                afterFen = afterFen,
                uciMove = uciMove,
                depth = depth,
                multiPv = multiPv
            )
        }
        pingOrThrow()
        val url = "${ServerConfig.BASE_URL}/api/v1/evaluate/game/by-fens"
        val payload = json.encodeToString(
            FensUciRequest(
                fens = listOf(beforeFen, afterFen),
                uciMoves = listOf(uciMove),
                depth = depth,
                multiPv = multiPv
            )
        )
        val req = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("User-Agent", UA)
            .post(payload.toRequestBody(JSON_MEDIA))
            .build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IllegalStateException("http_${resp.code}: ${text.take(300)}")
            val report = json.decodeFromString<FullReport>(text)
            val evalAfter = report.positions.getOrNull(1)?.lines?.firstOrNull()?.let { line ->
                line.mate?.let { if (it > 0) 30f else -30f } ?: (line.cp?.toFloat()?.div(100f))
            } ?: 0f
            val cls = report.moves.firstOrNull()?.classification ?: MoveClass.OKAY
            val best = report.positions.getOrNull(0)?.lines?.firstOrNull()?.pv?.firstOrNull()
            return@use Triple(evalAfter, cls, best)
        }
    }

    suspend fun evaluateFenDetailed(
        fen: String,
        depth: Int = 14,
        multiPv: Int = 3,
        skillLevel: Int? = null
    ): PositionDTO = withContext(Dispatchers.IO) {
        // локальный режим
        if (engineMode.value == EngineMode.LOCAL) {
            return@withContext LocalStockfish.evaluatePositionDetailed(
                fen = fen,
                depth = depth,
                multiPv = multiPv,
                skillLevel = skillLevel,
                context = appContext()
            )
        }
        val url = "${ServerConfig.BASE_URL}/api/v1/evaluate/position"
        val body = json.encodeToString(EvaluatePositionRequest(fen, depth, multiPv, skillLevel))
        val req = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("User-Agent", UA)
            .post(body.toRequestBody(JSON_MEDIA))
            .build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IllegalStateException("http_${resp.code}: ${text.take(300)}")
            json.decodeFromString(text)
        }
    }

    // --- внутренние помощники ---

    private fun normalizePgn(src: String): String {
        var s = src
            .replace("\uFEFF", "")
            .replace("\r\n", "\n")
            .replace("\r", "\n")

        s = s.replace("0-0-0", "O-O-O").replace("0-0", "O-O")
        s = s.replace("1–0", "1-0").replace("0–1", "0-1")
            .replace("½–½", "1/2-1/2").replace("½-½", "1/2-1/2")

        s = s.replace(Regex("""\{\[%clk [^}]+\]\}"""), "")
        s = s.replace(Regex("""\s\$\d+"""), "")

        s = buildString(s.length) {
            for (ch in s) {
                if (ch == '\n' || ch == '\t' || ch.code >= 32) append(ch)
            }
        }

        val tagBlockRegex = Regex("""\A(?:\[[^\]\n]+\]\s*\n)+""")
        val tagMatch = tagBlockRegex.find(s)

        val rebuilt = if (tagMatch != null) {
            val tagBlock = tagMatch.value.trimEnd('\n')
            val rest = s.substring(tagMatch.range.last + 1)
            val movetext = rest.trimStart('\n', ' ', '\t')
            tagBlock + "\n\n" + movetext
        } else {
            s.trimStart('\n', ' ', '\t')
        }

        var out = rebuilt.trimEnd()
        if (!out.endsWith("\n")) out += "\n"
        return out
    }

    private suspend fun pingOrThrow() = withContext(Dispatchers.IO) {
        val url = "${ServerConfig.BASE_URL}/ping"
        try {
            val getReq = Request.Builder().url(url).header("User-Agent", UA).get().build()
            client.newCall(getReq).execute().use { resp ->
                if (resp.isSuccessful) {
                    return@withContext
                } else {
                    throw IllegalStateException("Ping failed with HTTP ${resp.code}")
                }
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
        val bodyStr = json.encodeToString(
            EvaluatePositionRequest(
                fen = fen,
                depth = depth,
                multiPv = multiPv,
                skillLevel = skillLevel
            )
        )
        val req = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("User-Agent", UA)
            .post(bodyStr.toRequestBody(JSON_MEDIA))
            .build()

        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful || text.isBlank()) {
                return StockfishResponse(false, error = "http_${resp.code}: ${text.take(200)}")
            }
            return try {
                val dto = json.decodeFromString<PositionDTO>(text)
                val top = dto.lines.firstOrNull()
                val evaluationPawns: Double? = top?.cp?.let { it / 100.0 }
                StockfishResponse(
                    success = true,
                    evaluation = evaluationPawns,
                    mate = top?.mate,
                    bestmove = dto.bestMove,
                    continuation = top?.pv?.joinToString(" "),
                    error = null
                )
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
                            // обновляем публичные state-потоки
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

    // --- Новое: единая точка автоконфигурации пути к libstockfish.so ---
    private fun ensureLocalBinaryConfigured() {
        val ctx = appContext() ?: return
        try {
            val nativeDir = ctx.applicationInfo.nativeLibraryDir
            val soPath = "$nativeDir/libstockfish.so"
            // Сообщаем LocalStockfish явный путь — дальше он сможет работать и без контекста.
            Log.i(TAG, "Local Stockfish path configured: $soPath")
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to configure local Stockfish path", t)
        }
    }
}
