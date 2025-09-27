package com.example.chessanalysis

import android.annotation.SuppressLint
import android.util.Log
import kotlinx.coroutines.*
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

// ----- КОНФИГУРАЦИЯ СЕРВЕРА -----
object ServerConfig {
    private const val EMULATOR_URL = "http://10.0.2.2:8080"
    private const val PRODUCTION_URL = "https://your-chess-backend.com" // ЗАМЕНИТЕ НА ВАШ РЕАЛЬНЫЙ URL
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
// КЛЮЧЕВОЕ: explicitNulls=false — не отправляем поля со значением null вообще
private val json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}
private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
private const val UA = "ChessAnalysis/1.5 (+android; local-server)"

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
@Serializable
data class PositionDTO(
    val lines: List<LineDTO> = emptyList(),
    val bestMove: String? = null
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

// ---------- УТИЛИТЫ ----------

/**
 * Нормализация PGN без разрушения структуры:
 *  - убираем BOM;
 *  - CRLF/CR -> LF;
 *  - заменяем «нулевые» рокировки и разные тире/½;
 *  - удаляем часы {[%clk ...]} и NAG ($...);
 *  - ЖЁСТКО гарантируем: теги строго с начала файла и ровно одна пустая строка между тегами и ходами;
 *  - удаляем паразитные управляющие символы (кроме \n, \t);
 *  - завершаем одним '\n'.
 */
private fun normalizePgn(src: String): String {
    var s = src
        .replace("\uFEFF", "")      // BOM
        .replace("\r\n", "\n")
        .replace("\r", "\n")

    // Рокировки/результат
    s = s.replace("0-0-0", "O-O-O").replace("0-0", "O-O")
    s = s.replace("1–0", "1-0").replace("0–1", "0-1")
        .replace("½–½", "1/2-1/2").replace("½-½", "1/2-1/2")

    // Удаляем часы и NAG
    s = s.replace(Regex("""\{\[%clk [^}]+\]\}"""), "")
    s = s.replace(Regex("""\s\$\d+"""), "")

    // Счистить странные управляющие (кроме таба/перевода строки)
    s = buildString(s.length) {
        for (ch in s) {
            if (ch == '\n' || ch == '\t' || ch.code >= 32) append(ch)
        }
    }

    // Теги только с самого начала (\A) — без MULTILINE
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

// ---------- Публичное API ----------

/** Анализ одной позиции. */
suspend fun analyzeFen(fen: String, depth: Int = 14): StockfishResponse =
    withContext(Dispatchers.IO) { requestEvaluatePosition(fen, depth, 3) }

/**
 * Анализ партии по PGN с прогрессом (v1).
 */
suspend fun analyzeGameByPgnWithProgress(
    pgn: String,
    depth: Int = 16,
    multiPv: Int = 3,
    workersNb: Int = 4,
    header: GameHeader? = null,
    onProgress: (ProgressSnapshot) -> Unit
): FullReport = coroutineScope {
    val progressId = UUID.randomUUID().toString()

    Log.d(TAG, "Starting PGN analysis with progressId: $progressId")
    Log.d(TAG, "Server URL: ${ServerConfig.BASE_URL}")

    val poller = launch(Dispatchers.IO) {
        pollProgress(progressId, onProgress)
    }

    try {
        withContext(Dispatchers.IO) {
            pingOrThrow()

            val url = "${ServerConfig.BASE_URL}/api/v1/evaluate/game?progressId=$progressId"

            val normalized = normalizePgn(pgn)

            // Локальная проверка нормализованного PGN (удобнее упасть здесь, чем на сервере)
            runCatching { PgnChess.validatePgn(normalized) }
                .onFailure { e ->
                    throw IllegalArgumentException("Проблема с PGN: ${e.message}", e)
                }

            // ВАЖНО: не включать null-поля внутрь header (explicitNulls=false уже помогает)
            val headerSanitized = header?.copy(pgn = null)

            val payload = json.encodeToString(
                GamePgnRequest(
                    pgn = normalized,
                    depth = depth,
                    multiPv = multiPv,
                    workersNb = workersNb,
                    header = headerSanitized
                )
            )

            Log.d(TAG, "Sending PGN analysis request to: $url")
            Log.d(TAG, "PGN length: ${normalized.length} chars")

            val req = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .header("User-Agent", UA)
                .post(payload.toRequestBody(JSON_MEDIA))
                .build()

            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    Log.e(TAG, "Server error: HTTP ${resp.code}")
                    Log.e(TAG, "Response body: ${body.take(500)}")
                    throw IllegalStateException("HTTP ${resp.code}: ${body.take(300)}")
                }
                val report = json.decodeFromString<FullReport>(body)
                Log.d(TAG, "Analysis complete! Positions: ${report.positions.size}, Moves: ${report.moves.size}")
                Log.d(TAG, "Accuracy: white=${report.accuracy.whiteMovesAcc.weighted}, black=${report.accuracy.blackMovesAcc.weighted}")
                return@withContext report
            }
        }
    } catch (e: Exception) {
        Log.e(TAG, "Analysis failed", e)
        throw e
    } finally {
        poller.cancel()
        poller.join()
    }
}

/** Анализ партии по PGN без прогресса (v1). */
suspend fun analyzeGameByPgn(
    pgn: String,
    depth: Int = 16,
    multiPv: Int = 3,
    workersNb: Int = 4,
    header: GameHeader? = null
): FullReport = withContext(Dispatchers.IO) {
    pingOrThrow()
    val url = "${ServerConfig.BASE_URL}/api/v1/evaluate/game"

    val normalized = normalizePgn(pgn)
    runCatching { PgnChess.validatePgn(normalized) }
        .onFailure { e -> throw IllegalArgumentException("Проблема с PGN: ${e.message}", e) }

    val headerSanitized = header?.copy(pgn = null)

    val payload = json.encodeToString(GamePgnRequest(normalized, depth, multiPv, workersNb,headerSanitized))
    val req = Request.Builder()
        .url(url)
        .header("Accept", "application/json")
        .header("User-Agent", UA)
        .post(payload.toRequestBody(JSON_MEDIA))
        .build()

    client.newCall(req).execute().use { resp ->
        val body = resp.body?.string().orEmpty()
        if (!resp.isSuccessful) {
            throw IllegalStateException("http_${resp.code}: ${body.take(300)}")
        }
        return@use json.decodeFromString<FullReport>(body)
    }
}

// ---------- Внутренние функции ----------

private suspend fun pingOrThrow() = withContext(Dispatchers.IO) {
    val url = "${ServerConfig.BASE_URL}/ping"
    Log.d(TAG, "Pinging server at: $url")
    try {
        val getReq = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .get()
            .build()
        client.newCall(getReq).execute().use { resp ->
            if (resp.isSuccessful) return@withContext
            Log.w(TAG, "Ping failed with code: ${resp.code}")
        }
    } catch (e: Exception) {
        Log.e(TAG, "Network error: ${e.message}")
        throw IllegalStateException(
            "Cannot reach server at ${ServerConfig.BASE_URL}. Check your network connection and server URL.",
            e
        )
    }
    throw IllegalStateException("Server not responding at: ${ServerConfig.BASE_URL}")
}

private fun requestEvaluatePosition(
    fen: String,
    depth: Int,
    multiPv: Int
): StockfishResponse {
    val url = "${ServerConfig.BASE_URL}/api/v1/evaluate/position"
    val bodyStr = json.encodeToString(mapOf("fen" to fen, "depth" to depth, "multiPv" to multiPv))
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
                        onUpdate(snap)
                        if (snap.stage == "done" || (snap.total > 0 && snap.done >= snap.total)) return
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Progress polling error: ${e.message}")
        }
        delay(400)
    }
}
