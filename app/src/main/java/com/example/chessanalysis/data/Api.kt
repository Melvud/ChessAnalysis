package com.example.chessanalysis.data

import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import kotlin.math.roundToInt

/**
 * Единственный HTTP-клиент для запроса движка Stockfish Online v2.
 * Никаких вызовов chess-api больше нет.
 */
object StockfishApiV2 {

    private const val TAG = "StockfishApiV2"
    // Документация/пример: https://stockfish.online/api/s/v2.php  (v2, all-in-one)
    private const val ENDPOINT = "https://stockfish.online/api/s/v2.php"

    data class Eval(
        val ok: Boolean,
        /** оценка в центопешках (относительно белых), если нет mate */
        val cp: Int?,
        /** число ходов до мата (>0, если в пользу стороны, положительное как у Stockfish) */
        val mate: Int?,
        /** строка вида "bestmove e2e4 ponder ...", если есть */
        val rawBest: String?,
        /** principal variation как строка UCI через пробел, если есть */
        val continuation: String?,
        val depth: Int?
    ) {
        /** UCI лучшего хода, например "e2e4" */
        val bestMoveUci: String?
            get() = rawBest?.split(" ")?.getOrNull(1)
    }

    /**
     * Запрос оценки позиции. depth ∈ [6,15] (сайт v2 ограничивает 15).
     * Возвращаемое cp уже усечено в диапазон [-1000, 1000] для устойчивости метрик.
     */
    fun evaluateFen(fen: String, depth: Int = 14): Eval {
        val capped = depth.coerceIn(6, 15)
        val qs = "fen=${URLEncoder.encode(fen, "UTF-8")}&depth=$capped"
        val url = "$ENDPOINT?$qs"
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 12000
            readTimeout = 15000
            requestMethod = "GET"
        }
        return try {
            val code = conn.responseCode
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            if (code !in 200..299) {
                Log.w(TAG, "HTTP $code: $body")
                Eval(false, null, null, null, null, null)
            } else {
                // Пример ответа (см. источники):
                // { "success":true, "evaluation":1.36, "mate":null,
                //   "bestmove":"bestmove b7b6 ponder f3e5", "continuation":"b7b6 f3e5 ...", "depth":14 }
                val j = JSONObject(body)
                val ok = j.optBoolean("success", false)
                val evalPawns = when {
                    j.has("evaluation") && !j.isNull("evaluation") -> j.optDouble("evaluation")
                    j.has("eval") && !j.isNull("eval") -> j.optDouble("eval")
                    else -> Double.NaN
                }
                val cp: Int? = if (evalPawns.isNaN()) null else {
                    // evaluation приходит в пешках → в центопешки
                    (evalPawns * 100.0).roundToInt().coerceIn(-1000, 1000)
                }
                val mate: Int? = if (j.isNull("mate")) null else j.optInt("mate")
                Eval(
                    ok = ok,
                    cp = cp,
                    mate = mate,
                    rawBest = j.optString("bestmove", null),
                    continuation = j.optString("continuation", null),
                    depth = j.optInt("depth", 0).takeIf { it > 0 }
                )
            }
        } catch (t: Throwable) {
            Log.e(TAG, "evaluateFen failed", t)
            Eval(false, null, null, null, null, null)
        } finally {
            conn.disconnect()
        }
    }
}
