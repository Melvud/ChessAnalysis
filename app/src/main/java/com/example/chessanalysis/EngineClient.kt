package com.example.chessanalysis

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.add
import kotlinx.serialization.json.put
import kotlinx.serialization.json.encodeToJsonElement
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import java.util.UUID
import java.util.concurrent.TimeUnit

// ----- БАЗОВЫЙ URL: эмулятор -> хост -----
private const val BASE = "http://10.0.2.2:8080"

// ----- Логирование HTTP -----
private const val TAG = "EngineClient"
private val httpLogger = HttpLoggingInterceptor { msg -> Log.d("HTTP", msg) }
    .apply { level = HttpLoggingInterceptor.Level.BODY }

// ----- OkHttp с длинными таймаутами -----
private val client = OkHttpClient.Builder()
    .addInterceptor(httpLogger)
    .connectTimeout(10, TimeUnit.SECONDS)
    .writeTimeout(5, TimeUnit.MINUTES)
    .readTimeout(5, TimeUnit.MINUTES)
    .callTimeout(0, TimeUnit.SECONDS)
    .retryOnConnectionFailure(true)
    .build()

// ----- JSON -----
private val json = Json { ignoreUnknownKeys = true }
private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
private const val UA = "ChessAnalysis/1.5 (+android; local-server)"

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
data class StockfishResponse(
    val success: Boolean,
    val evaluation: Double? = null,
    val mate: Int? = null,
    val bestmove: String? = null,
    val continuation: String? = null,
    val error: String? = null
)

@Serializable
data class GameFullRequest(
    val pgn: String,
    val depth: Int = 14,
    val multiPv: Int = 3
)

@Serializable
data class GameByFensRequest(
    val fens: List<String>,
    val uciMoves: List<String>?,
    val depth: Int,
    val multiPv: Int,
    val header: GameHeader? = null
)

@Serializable
data class ProgressSnapshot(
    val id: String,
    val total: Int,
    val done: Int,
    val percent: Double? = null,
    val etaMs: Long? = null,
    val stage: String? = null,     // queued | preparing | evaluating | postprocess | done
    val startedAt: Long? = null,
    val updatedAt: Long? = null
)

// ---------- Публичное API ----------

/** Анализ одной позиции. */
suspend fun analyzeFen(fen: String, depth: Int = 14): StockfishResponse =
    withContext(Dispatchers.IO) { requestEvaluatePosition(fen, depth, 3) }

/** Анализ партии по FEN/UCIs с реальным прогрессом. */
suspend fun analyzeGameByFensWithProgress(
    fens: List<String>,
    uciMoves: List<String>?,
    depth: Int = 14,
    multiPv: Int = 3,
    header: GameHeader? = null,
    onProgress: (ProgressSnapshot) -> Unit
): FullReport = coroutineScope {
    pingOrThrow()

    val progressId = UUID.randomUUID().toString()

    // Поллер прогресса
    val poller = launch(Dispatchers.IO) {
        pollProgress(progressId, onProgress)
    }

    try {
        withContext(Dispatchers.IO) {
            val url = "$BASE/api/v1/evaluate/game/by-fens?" +
                    "progressId=$progressId" +
                    "&depth=$depth" +
                    "&multiPv=$multiPv"

            val requestBody = buildJsonObject {
                putJsonArray("fens") {
                    fens.forEach { add(it) }
                }
                uciMoves?.let {
                    putJsonArray("uciMoves") {
                        it.forEach { uciMove -> add(uciMove) }
                    }
                }
                header?.let { h ->
                    put("header", json.encodeToJsonElement(h))
                }
            }

            val payload = requestBody.toString()
            val req = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .header("User-Agent", UA)
                .header("Content-Type", "application/json")
                .post(payload.toRequestBody(JSON_MEDIA))
                .build()

            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    throw IllegalStateException("HTTP ${resp.code}: ${body.take(300)}")
                }
                return@withContext json.decodeFromString<FullReport>(body)
            }
        }
    } finally {
        poller.cancel()
        poller.join()
    }
}

/** Анализ без прогресса (на IO). */
suspend fun analyzeGameByFens(
    fens: List<String>,
    uciMoves: List<String>?,
    depth: Int = 14,
    multiPv: Int = 3,
    header: GameHeader? = null
): FullReport = withContext(Dispatchers.IO) {
    pingOrThrow()
    val url = "$BASE/api/v1/evaluate/game/by-fens"
    val payload = json.encodeToString(GameByFensRequest(fens, uciMoves, depth, multiPv, header))
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

// ---------- Внутрянка ----------

/** Пинг одного адреса BASE. Выполняется на IO. */
private suspend fun pingOrThrow() = withContext(Dispatchers.IO) {
    // GET /ping
    runCatching {
        val getReq = Request.Builder()
            .url("$BASE/ping")
            .header("User-Agent", UA)
            .get()
            .build()
        client.newCall(getReq).execute().use { resp ->
            if (resp.isSuccessful) {
                Log.d(TAG, "Ping OK (GET) $BASE")
                return@withContext
            } else {
                Log.w(TAG, "Ping GET failed $BASE: ${resp.code}")
            }
        }
    }.onFailure { e -> Log.w(TAG, "Ping GET error $BASE: ${e.message}") }

    // POST /ping
    runCatching {
        val postReq = Request.Builder()
            .url("$BASE/ping")
            .header("User-Agent", UA)
            .post("{\"ping\":true}".toRequestBody(JSON_MEDIA))
            .build()
        client.newCall(postReq).execute().use { resp ->
            if (resp.isSuccessful) {
                Log.d(TAG, "Ping OK (POST) $BASE")
                return@withContext
            } else {
                Log.w(TAG, "Ping POST failed $BASE: ${resp.code}")
            }
        }
    }.onFailure { e -> Log.w(TAG, "Ping POST error $BASE: ${e.message}") }

    throw IllegalStateException("Server not reachable on: $BASE")
}

private fun requestEvaluatePosition(
    fen: String,
    depth: Int,
    multiPv: Int
): StockfishResponse {
    val url = "$BASE/api/v1/evaluate/position"
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

/** Поллинг прогресса: /api/v1/progress/{id} (запускать на IO, см. вызов выше). */
private suspend fun pollProgress(
    progressId: String,
    onUpdate: (ProgressSnapshot) -> Unit
) {
    while (currentCoroutineContext().isActive) {
        try {
            val req = Request.Builder()
                .url("$BASE/api/v1/progress/$progressId")
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
        } catch (_: Throwable) {
            // игнорируем и пробуем снова
        }
        delay(400)
    }
}
