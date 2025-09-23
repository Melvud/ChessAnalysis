package com.example.chessanalysis

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Клиент движка: ТОЛЬКО Stockfish Online v2.
 * Эндпоинт: https://stockfish.online/api/s/v2.php
 * Ожидаемые поля ответа: success / evaluation / mate / bestmove / continuation
 * (возможен вариант, когда эти поля лежат внутри корня или внутри data{}).
 */
object EngineClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    private const val UA = "ChessAnalysis/1.2 (+android; app@example.com)"

    private fun String?.normBest(): String? {
        if (this.isNullOrBlank()) return null
        val t = this.trim()
        // иногда приходит "bestmove e2e4 ponder e7e5"
        return if (t.startsWith("bestmove")) t.split(" ").getOrNull(1) else t
    }

    private data class Parsed(
        val success: Boolean,
        val evaluation: Double? = null,   // пешки (1.36 и т.п.)
        val mate: Int? = null,            // +N/-N со стороны белых
        val bestmove: String? = null,     // UCI
        val continuation: String? = null, // pv строкой
        val error: String? = null
    )

    /** Извлечь известные поля как с корня, так и из data{...}. */
    private fun extract(obj: JsonObject): Parsed {
        fun pick(o: JsonObject): Parsed {
            val success = o["success"]?.jsonPrimitive?.booleanOrNull
            val eval = o["evaluation"]?.jsonPrimitive?.doubleOrNull
                ?: o["eval"]?.jsonPrimitive?.doubleOrNull
            val mate = o["mate"]?.jsonPrimitive?.intOrNull
            val bestmove = o["bestmove"]?.jsonPrimitive?.contentOrNull
                ?: o["best"]?.jsonPrimitive?.contentOrNull
            val cont = o["continuation"]?.jsonPrimitive?.contentOrNull
                ?: o["pv"]?.jsonPrimitive?.contentOrNull
            val err = o["error"]?.jsonPrimitive?.contentOrNull
                ?: o["message"]?.jsonPrimitive?.contentOrNull

            return if (success == false) {
                Parsed(false, error = err ?: "engine_error")
            } else {
                val ok = (success == true) || (eval != null || mate != null || !bestmove.isNullOrBlank() || !cont.isNullOrBlank())
                Parsed(
                    success = ok,
                    evaluation = eval,
                    mate = mate,
                    bestmove = bestmove,
                    continuation = cont,
                    error = if (ok) null else err
                )
            }
        }

        // сначала пробуем корень; если нет — заглянем в data{...}
        val rootParsed = pick(obj)
        if (rootParsed.success || rootParsed.evaluation != null || rootParsed.mate != null || rootParsed.bestmove != null || rootParsed.continuation != null) {
            return rootParsed
        }
        val data = obj["data"]?.jsonObject
        return if (data != null) pick(data) else rootParsed
    }

    /** Разбор произвольного тела ответа v2. */
    private fun parseV2(body: String): Parsed {
        return runCatching {
            val element = json.parseToJsonElement(body)
            when (element) {
                is JsonObject -> extract(element)
                else -> Parsed(false, error = "non_json_root")
            }
        }.getOrElse { Parsed(false, error = "json_parse_failed: ${it.message}") }
    }

    /** Вызов stockfish.online v2: https://stockfish.online/api/s/v2.php */
    private fun requestStockfishV2(fen: String, depth: Int): StockfishResponse {
        val d = depth.coerceAtMost(15) // безопасный максимум
        val url = "https://stockfish.online/api/s/v2.php" +
                "?fen=${URLEncoder.encode(fen, "UTF-8")}" +
                "&depth=$d"
        val req = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("User-Agent", UA)
            .build()

        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful || body.isBlank()) {
                val tail = body.take(160).replace("\n", " ")
                return StockfishResponse(false, error = "http_${resp.code}: $tail")
            }
            val p = parseV2(body)
            return if (p.success) {
                StockfishResponse(
                    success = true,
                    evaluation = p.evaluation,
                    mate = p.mate,
                    bestmove = p.bestmove.normBest(),
                    continuation = p.continuation
                )
            } else {
                val tail = body.take(200).replace("\n", " ")
                StockfishResponse(false, error = p.error ?: "engine_error: $tail")
            }
        }
    }

    // -------------------- Публичное API --------------------

    suspend fun analyzeFen(fen: String, depth: Int = 14): StockfishResponse = withContext(Dispatchers.IO) {
        analyzeFenStrict(fen, depth)
    }

    /** Ретраи с бэкоффом и понижением depth, без изменения логики остального кода. */
    private suspend fun analyzeFenStrict(fen: String, depth: Int, retries: Int = 3): StockfishResponse {
        var d = depth
        var delayMs = 300L
        repeat(retries) {
            val r = runCatching { requestStockfishV2(fen, d) }.getOrElse {
                StockfishResponse(false, error = "request_failed: ${it.message}")
            }
            if (r.success && (r.evaluation != null || r.mate != null || !r.bestmove.isNullOrBlank() || !r.continuation.isNullOrBlank())) return r
            if (d > 10) d -= 2
            delay(delayMs)
            delayMs = (delayMs * 2).coerceAtMost(2000L)
        }
        return runCatching { requestStockfishV2(fen, d) }.getOrElse {
            StockfishResponse(false, error = "request_failed_final: ${it.message}")
        }
    }
}

