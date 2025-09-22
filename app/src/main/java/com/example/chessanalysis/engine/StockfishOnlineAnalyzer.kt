package com.example.chessanalysis.engine

import android.util.Log
import com.example.chessanalysis.data.api.StockfishOnlineService
import com.example.chessanalysis.data.model.AnalysisResult
import com.example.chessanalysis.data.model.AnalysisSummary
import com.example.chessanalysis.data.model.MoveAnalysis
import com.example.chessanalysis.data.model.MoveClass
import com.example.chessanalysis.data.util.MoveClassifier
import com.example.chessanalysis.data.util.PGNParser
import com.example.chessanalysis.data.util.PositionSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.ResponseBody
import org.json.JSONObject
import java.io.IOException
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt

/**
 * Анализ партии через Stockfish Online с логированием каждого шага.
 * Классификация и точность считаются по ПОТЕРЕ Win% (в процентных пунктах).
 */
class StockfishOnlineAnalyzer(
    private val api: StockfishOnlineService
) {

    private val TAG = "SFOnlineAnalyzer"

    private data class Eval(
        val pawnsAfter: Double,  // оценка (в пешках, POV белых) — без округления!
        val bestMoveUci: String  // лучший ход (uci) или "" если нет
    )

    // --- Логистическая регрессия (lichess) CP -> Win% ---
    private fun winPercentFromPawns(pawns: Double): Double {
        val cp = pawns * 100.0
        val w = 50.0 + 50.0 * (2.0 / (1.0 + exp(-0.00368208 * cp)) - 1.0)
        return w.coerceIn(0.0, 100.0)
    }

    private fun parseBestUci(bestRaw: String?): String {
        if (bestRaw.isNullOrBlank()) return ""
        val re = Regex("""\b([a-h][1-8][a-h][1-8][qrbn]?)\b""", RegexOption.IGNORE_CASE)
        return re.find(bestRaw)?.groupValues?.getOrNull(1)?.lowercase() ?: ""
    }

    private fun pawnsFrom(mate: Int?, evaluation: Double?): Double {
        return when {
            mate != null && mate != 0 -> if (mate > 0) 1000.0 else -1000.0 // виртуальная бесконечность
            evaluation != null        -> evaluation                         // ВАЖНО: без округления
            else                      -> 0.0
        }
    }

    private suspend fun evalFen(fen: String, depth: Int): Eval = withContext(Dispatchers.IO) {
        val d = depth.coerceIn(1, 15)
        // 1) JSON-ответ
        val jsonResp = api.analyze(fen, d)
        if (jsonResp.isSuccessful) {
            jsonResp.body()?.let { dto ->
                val pawns = pawnsFrom(dto.mate, dto.evaluation)
                if (dto.evaluation == null && dto.mate == null) {
                    Log.w(TAG, "No eval fields in JSON for fen='${fen.take(40)}...' depth=$d -> using 0.0")
                }
                return@withContext Eval(
                    pawnsAfter = pawns,
                    bestMoveUci = parseBestUci(dto.bestmove)
                )
            }
        }
        // 2) Фолбэк: text/plain/HTML с JSON внутри
        val rawResp = api.analyzeRaw(fen, d)
        val body: ResponseBody = rawResp.body() ?: throw IOException("StockfishOnline: empty body")
        val text = body.string().trim()
        val obj = run {
            val s = if (text.startsWith("{")) text else {
                val b = text.indexOf('{'); val e = text.lastIndexOf('}')
                if (b >= 0 && e > b) text.substring(b, e + 1) else throw IOException("Malformed response")
            }
            JSONObject(s)
        }
        val eval = obj.optDouble("evaluation", Double.NaN).takeIf { !it.isNaN() }
        val mate = obj.optInt("mate", 0).takeIf { it != 0 }
        val best = obj.optString("bestmove", null)

        if (eval == null && mate == null) {
            Log.w(TAG, "Fallback: no eval/mate for fen='${fen.take(40)}...' depth=$d -> using 0.0")
        }

        return@withContext Eval(
            pawnsAfter = pawnsFrom(mate, eval),
            bestMoveUci = parseBestUci(best)
        )
    }

    // --- PGN-теги для логов перфоманса ---
    private fun tagString(pgn: String, key: String): String? {
        val re = Regex("\\[$key\\s+\"([^\"]+)\"\\]")
        return re.find(pgn)?.groupValues?.getOrNull(1)
    }
    private fun tagInt(pgn: String, key: String): Int? = tagString(pgn, key)?.toIntOrNull()

    private fun buildResult(
        pgn: String,
        positions: List<PositionSnapshot>,
        evalsBefore: List<Double>,
        evalsAfter: List<Double>,
        bestMoves: List<String>
    ): AnalysisResult {
        val moves = ArrayList<MoveAnalysis>(positions.size)
        val winLosses = ArrayList<Double>(positions.size)

        Log.d(TAG, "----- ANALYSIS START -----")
        Log.d(TAG, "PGN tags: White='${tagString(pgn, "White")}', Black='${tagString(pgn, "Black")}', " +
                "WhiteElo='${tagString(pgn, "WhiteElo")}', BlackElo='${tagString(pgn, "BlackElo")}', Result='${tagString(pgn, "Result")}'")
        Log.d(TAG, "Parsed plies: ${positions.size}. First SANs: ${positions.take(10).joinToString(" ") { it.san }}")

        positions.forEachIndexed { i, pos ->
            val moverIsWhite = (i % 2 == 0) // 0-й полуход — белые

            val beforePawns = evalsBefore[i]
            val afterPawns  = evalsAfter[i]

            val winBefore = winPercentFromPawns(beforePawns)
            val winAfter  = winPercentFromPawns(afterPawns)

            // ПОТЕРЯ для сделавшей ход стороны (в п.п.)
            val rawLoss = if (moverIsWhite) (winBefore - winAfter) else (winAfter - winBefore)
            val lossWin = rawLoss.coerceAtLeast(0.0)

            val cls = MoveClassifier.classifyWinLoss(lossWin)

            // ЛОГ по полуходу
            Log.d(TAG,
                "ply=${i + 1} ${if (moverIsWhite) "White" else "Black"} " +
                        "SAN='${pos.san}', FEN_before='${pos.fenBefore.take(50)}...', " +
                        "evalBeforePawns=${"%.3f".format(beforePawns)}, winBefore=${"%.2f".format(winBefore)}%, " +
                        "FEN_after='${pos.fenAfter.take(50)}...', " +
                        "evalAfterPawns=${"%.3f".format(afterPawns)}, winAfter=${"%.2f".format(winAfter)}%, " +
                        "bestMove='${bestMoves[i]}', lossWin=${"%.2f".format(lossWin)} p.p., class=$cls"
            )

            moves += MoveAnalysis(
                moveNumber     = i + 1,
                san            = pos.san.ifBlank { "-" },
                bestMove       = bestMoves[i],        // String, не null
                evaluation     = afterPawns,          // показываем в пешках
                delta          = lossWin,             // ΔWin (в п.п.)
                classification = cls
            )
            winLosses += lossWin
        }

        val counts: Map<MoveClass, Int> = moves.groupingBy { it.classification }.eachCount()
        val accuracy = MoveClassifier.accuracyFromWinLoss(winLosses)

        // Точность по цветам для отладки
        val whiteLosses = winLosses.filterIndexed { idx, _ -> idx % 2 == 0 }
        val blackLosses = winLosses.filterIndexed { idx, _ -> idx % 2 == 1 }
        val whiteAcc = MoveClassifier.accuracyFromWinLoss(whiteLosses)
        val blackAcc = MoveClassifier.accuracyFromWinLoss(blackLosses)

        Log.d(TAG, "Counts by class: $counts")
        Log.d(TAG, "Accuracy total=${"%.2f".format(accuracy)}%, white=${"%.2f".format(whiteAcc)}%, black=${"%.2f".format(blackAcc)}%")

        // Перфоманс — если есть теги в PGN
        val whiteElo = tagInt(pgn, "WhiteElo")
        val blackElo = tagInt(pgn, "BlackElo")
        val resultTag = tagString(pgn, "Result")
        val wp: Double? = when (resultTag) {
            "1-0" -> 1.0
            "0-1" -> 0.0
            "1/2-1/2", "½-½" -> 0.5
            else -> null
        }
        val bp = wp?.let { 1.0 - it }

        if (wp != null && blackElo != null) {
            val wPerf = MathEval.performance(wp, blackElo)
            Log.d(TAG, "White performance (score=$wp vs $blackElo) => $wPerf")
        } else {
            Log.d(TAG, "White performance: skipped (wp=$wp, blackElo=$blackElo)")
        }
        if (bp != null && whiteElo != null) {
            val bPerf = MathEval.performance(bp, whiteElo)
            Log.d(TAG, "Black performance (score=$bp vs $whiteElo) => $bPerf")
        } else {
            Log.d(TAG, "Black performance: skipped (bp=$bp, whiteElo=$whiteElo)")
        }

        Log.d(TAG, "----- ANALYSIS END (totalMoves=${moves.size}) -----")

        val summary = AnalysisSummary(
            totalMoves = moves.size,
            counts = counts,
            accuracy = accuracy
        )
        return AnalysisResult(summary = summary, moves = moves)
    }

    /** Основной анализ по PGN. Пишет подробные логи. */
    suspend fun analyzeGame(pgn: String, depth: Int = 15): AnalysisResult = withContext(Dispatchers.IO) {
        val parsed = PGNParser.parseGame(pgn)

        if (parsed.positions.isEmpty()) {
            Log.w(TAG, "PGN parsed, but positions are empty.")
            return@withContext AnalysisResult(
                summary = AnalysisSummary(0, emptyMap(), 100.0),
                moves = emptyList()
            )
        }

        val evalsBefore = ArrayList<Double>(parsed.positions.size)
        val evalsAfter  = ArrayList<Double>(parsed.positions.size)
        val bestMoves   = ArrayList<String>(parsed.positions.size)

        for (pos in parsed.positions) {
            val before = evalFen(pos.fenBefore, depth)   // bestmove для позиции ДО хода
            val after  = evalFen(pos.fenAfter,  depth)   // оценка ПОСЛЕ хода
            evalsBefore += before.pawnsAfter
            evalsAfter  += after.pawnsAfter
            bestMoves   += before.bestMoveUci
        }

        return@withContext buildResult(
            pgn = pgn,
            positions = parsed.positions,
            evalsBefore = evalsBefore,
            evalsAfter = evalsAfter,
            bestMoves = bestMoves
        )
    }
}
