package com.example.chessanalysis.engine

import android.util.Log
import com.example.chessanalysis.data.api.StockfishOnlineService
import com.example.chessanalysis.data.model.AnalysisResult
import com.example.chessanalysis.data.model.AnalysisSummary
import com.example.chessanalysis.data.model.MoveAnalysis
import com.example.chessanalysis.data.model.MoveClass
import com.example.chessanalysis.data.util.PGNParser
import com.example.chessanalysis.data.util.MoveClassifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.ResponseBody
import org.json.JSONObject
import java.io.IOException
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt

/**
 * Пошаговый анализ партии через Stockfish Online.
 * - delta считается в ПЕШКАХ для делающей стороны (как ждут Summary/Report экраны)
 * - bestMove берём для позиции ДО хода
 * - итоговая точность: 100 - средняя «пенальти» по классам (см. MoveClassifier)
 */
class StockfishOnlineAnalyzer(
    private val api: StockfishOnlineService
) {

    private val TAG = "SFOnlineAnalyzer"

    private data class Eval(
        val pawnsAfter: Double,   // оценка (в пешках, POV белых)
        val bestMoveUci: String   // лучший ход (uci) или "" если нет
    )

    /** Lichess-логистика cp→Win% (0..100) — иногда полезно для логов. */
    private fun winPercentFromPawns(pawns: Double): Double {
        val cp = pawns * 100.0
        return (50.0 + 50.0 * (2.0 / (1.0 + exp(-0.00368208 * cp)) - 1.0))
            .coerceIn(0.0, 100.0)
    }

    /** Достаём UCI из разных форм bestmove. */
    private fun parseBestUci(bestRaw: String?): String {
        if (bestRaw.isNullOrBlank()) return ""
        val re = Regex("""\b([a-h][1-8][a-h][1-8][qrbn]?)\b""", RegexOption.IGNORE_CASE)
        return re.find(bestRaw)?.groupValues?.getOrNull(1)?.lowercase() ?: ""
    }

    private fun pawnsFrom(mate: Int?, evaluation: Double?): Double {
        return when {
            mate != null && mate != 0 -> if (mate > 0) 1000.0 else -1000.0 // «бесконечность»
            evaluation != null        -> evaluation
            else                      -> 0.0
        }
    }

    private suspend fun evalFen(fen: String, depth: Int): Eval = withContext(Dispatchers.IO) {
        val d = depth.coerceIn(1, 15)

        // 1) Пытаемся JSON
        api.analyze(fen, d).let { resp ->
            if (resp.isSuccessful) {
                resp.body()?.let { dto ->
                    return@withContext Eval(
                        pawnsAfter = pawnsFrom(dto.mate, dto.evaluation),
                        bestMoveUci = parseBestUci(dto.bestmove)
                    )
                }
            }
        }

        // 2) Фолбэк: в HTML/text ищем JSON-кусок
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

        return@withContext Eval(
            pawnsAfter = pawnsFrom(mate, eval),
            bestMoveUci = parseBestUci(best)
        )
    }

    /** Кто на ходу по FEN. */
    private fun sideToMoveIsWhite(fen: String): Boolean =
        fen.split(' ').getOrNull(1)?.lowercase() == "w"

    /** Построить результат для UI. */
    private fun buildResult(
        pgn: String,
        positions: List<com.example.chessanalysis.data.util.PositionSnapshot>,
        evalsBefore: List<Double>,
        evalsAfter: List<Double>,
        bestMoves: List<String>
    ): AnalysisResult {
        val moves = ArrayList<MoveAnalysis>(positions.size)

        positions.forEachIndexed { i, pos ->
            val moverIsWhite = (i % 2 == 0) // 0-й полуход делают белые

            val beforePawns = evalsBefore[i]
            val afterPawns  = evalsAfter[i]

            // Пересчёт «оценки для делающей стороны»
            val beforeForMover = if (moverIsWhite) beforePawns else -beforePawns
            val afterForMover  = if (moverIsWhite) afterPawns  else -afterPawns

            val deltaPawns = abs(beforeForMover - afterForMover)

            // Классификация по дельте в пешках (Chess.com-стиль порогов)
            val cls: MoveClass = MoveClassifier.classify(deltaPawns)

            // Логи (пригодятся в Logcat)
            val wB = "%.2f".format(winPercentFromPawns(beforePawns))
            val wA = "%.2f".format(winPercentFromPawns(afterPawns))
            Log.d(TAG, "ply=${i+1} ${if (moverIsWhite) "White" else "Black"} " +
                    "SAN='${pos.san}', evalBefore=${"%.3f".format(beforePawns)} (Win=$wB%), " +
                    "evalAfter=${"%.3f".format(afterPawns)} (Win=$wA%), best='${bestMoves[i]}', " +
                    "Δ=${"%.2f".format(deltaPawns)} pawns, class=$cls"
            )

            moves += MoveAnalysis(
                moveNumber = i + 1,
                san = pos.san.ifBlank { "-" },
                bestMove = bestMoves[i],
                evaluation = afterPawns, // показываем ПОСЛЕ хода
                delta = deltaPawns,
                classification = cls
            )
        }

        val counts = moves.groupingBy { it.classification }.eachCount()
        val accuracy = MoveClassifier.accuracy(moves.map { it.delta })

        val summary = AnalysisSummary(
            totalMoves = moves.size,
            counts = counts,
            accuracy = accuracy
        )
        return AnalysisResult(summary = summary, moves = moves)
    }

    /** Основной анализ по PGN. */
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
