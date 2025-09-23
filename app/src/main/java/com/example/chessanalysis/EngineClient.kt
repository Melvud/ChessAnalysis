package com.example.chessanalysis

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Клиент движка с приоритетом на StockfishOnline v2 и фолбэком на Lichess Cloud Eval.
 * Возвращаем значения в пешках и mate-in-N (со стороны белых).
 */
object EngineClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    private const val UA = "ChessAnalysis/1.0 (+android; app@example.com)"

    private fun String?.normBest(): String? {
        if (this.isNullOrBlank()) return null
        val t = this.trim()
        // У stockfish.online часто приходит строка в стиле "bestmove e2e4 ponder e7e5"
        return if (t.startsWith("bestmove")) t.split(" ").getOrNull(1) else t
    }

    private data class Parsed(
        val success: Boolean,
        val evaluation: Double? = null,
        val mate: Int? = null,
        val bestmove: String? = null,
        val continuation: String? = null,
        val error: String? = null
    )

    /** Парсер для обоих форматов: stockfish.online v2 и lichess cloud-eval. */
    private fun parseAny(body: String): Parsed {
        // 1) Попытка: lichess cloud eval
        runCatching {
            val root = json.parseToJsonElement(body).jsonObject
            val pv = root["pvs"]?.jsonArray?.firstOrNull()?.jsonObject
            if (pv != null) {
                val cp = pv["cp"]?.jsonPrimitive?.intOrNull
                val mate = pv["mate"]?.jsonPrimitive?.intOrNull
                val moves = pv["moves"]?.jsonPrimitive?.contentOrNull
                val best = root["best"]?.jsonPrimitive?.contentOrNull  // иногда есть
                return Parsed(
                    success = true,
                    evaluation = cp?.div(100.0),
                    mate = mate,
                    bestmove = best,
                    continuation = moves
                )
            }
        }.getOrNull()

        // 2) Попытка: stockfish.online v2 — единый JSON с ключами success/evaluation/mate/bestmove/continuation
        return runCatching {
            val root = json.parseToJsonElement(body).jsonObject
            val success = root["success"]?.jsonPrimitive?.booleanOrNull
            val eval = root["evaluation"]?.jsonPrimitive?.doubleOrNull
                ?: root["eval"]?.jsonPrimitive?.doubleOrNull  // на всякий случай
            val mate = root["mate"]?.jsonPrimitive?.intOrNull
            val bestmove = root["bestmove"]?.jsonPrimitive?.contentOrNull
                ?: root["best"]?.jsonPrimitive?.contentOrNull
            val cont = root["continuation"]?.jsonPrimitive?.contentOrNull
                ?: root["pv"]?.jsonPrimitive?.contentOrNull

            if (success == false) {
                Parsed(false, error = root["error"]?.jsonPrimitive?.contentOrNull ?: "unknown_error")
            } else {
                // Если success отсутствует, считаем успешным, если есть хоть eval или mate
                val ok = success == true || (eval != null || mate != null)
                Parsed(
                    success = ok,
                    evaluation = eval,
                    mate = mate,
                    bestmove = bestmove,
                    continuation = cont
                )
            }
        }.getOrElse { Parsed(false, error = it.message) }
    }

    /** Вызов stockfish.online v2. Depth ограничен 15. */
    private fun requestStockfishOnline(fen: String, depth: Int): StockfishResponse {
        val d = depth.coerceAtMost(15) // v2: max 15
        val url = "https://www.stockfish.online/api/stockfish.php" +
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
                return StockfishResponse(false, error = "http_${resp.code}")
            }
            val p = parseAny(body)
            return if (p.success) {
                StockfishResponse(
                    success = true,
                    evaluation = p.evaluation,   // пешки (1.36 и т.п.)
                    mate = p.mate,
                    bestmove = p.bestmove.normBest(),
                    continuation = p.continuation
                )
            } else {
                StockfishResponse(false, error = p.error ?: "parse_error")
            }
        }
    }

    /** Фолбэк: lichess cloud-eval. */
    private fun requestLichessCloud(fen: String, depth: Int): StockfishResponse {
        val url = "https://lichess.org/api/cloud-eval?multiPv=1&depth=$depth&fen=" +
                URLEncoder.encode(fen, "UTF-8")
        val req = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("User-Agent", UA)
            .build()

        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful || body.isBlank()) {
                return StockfishResponse(false, error = "http_${resp.code}")
            }
            val p = parseAny(body)
            return if (p.success) {
                StockfishResponse(
                    success = true,
                    evaluation = p.evaluation,        // уже в пешках
                    mate = p.mate,
                    bestmove = p.bestmove.normBest(),
                    continuation = p.continuation
                )
            } else {
                StockfishResponse(false, error = p.error ?: "parse_error")
            }
        }
    }

    /**
     * Анализ одной позиции FEN.
     * Сначала пробуем stockfish.online v2, затем — lichess cloud.
     */
    suspend fun analyzeFen(fen: String, depth: Int = 14): StockfishResponse = withContext(Dispatchers.IO) {
        // 1) Основной провайдер — StockfishOnline v2
        val primary = runCatching { requestStockfishOnline(fen, depth) }.getOrElse {
            StockfishResponse(false, error = it.message)
        }
        if (primary.success) return@withContext primary

        // 2) Фолбэк на lichess cloud-eval
        val fallback = runCatching { requestLichessCloud(fen, depth) }.getOrElse {
            StockfishResponse(false, error = it.message)
        }
        fallback
    }
}
