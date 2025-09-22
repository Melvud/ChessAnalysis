package com.example.chessanalysis.engine

import com.example.chessanalysis.data.api.StockfishOnlineService
import com.example.chessanalysis.data.model.*
import com.example.chessanalysis.data.util.BoardState
import com.example.chessanalysis.data.util.ChessParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.ResponseBody
import org.json.JSONObject
import java.io.IOException
import kotlin.math.abs
import kotlin.math.roundToInt

/** Математические функции «как на Lichess/Chess.com». */
object MathEval {
    private const val A = 0.00368208
    fun winPercentFromCp(cp: Int): Double {
        val x = A * cp.toDouble()
        val p = 50 + 50 * (2.0 / (1.0 + kotlin.math.exp(-x)) - 1.0)
        return p.coerceIn(0.0, 100.0)
    }
    private const val K1 = 103.1668
    private const val K2 = 0.04354
    private const val K3 = 3.1669
    fun moveAccuracy(winBefore: Double, winAfter: Double): Double {
        val drop = (winBefore - winAfter).coerceAtLeast(0.0)
        val acc = K1 * kotlin.math.exp(-K2 * drop) - K3
        return acc.coerceIn(0.0, 100.0)
    }
    enum class MoveTag { Best, Excellent, Good, Inaccuracy, Mistake, Blunder }
    fun classifyMove(bestMatch: Boolean, winDrop: Double, mateSwing: Boolean): MoveTag {
        if (bestMatch) return MoveTag.Best
        if (mateSwing) return MoveTag.Blunder
        val d = winDrop
        return when {
            d >= 30.0 -> MoveTag.Blunder
            d >= 20.0 -> MoveTag.Mistake
            d >= 10.0 -> MoveTag.Inaccuracy
            d >= 5.0  -> MoveTag.Good
            else      -> MoveTag.Excellent
        }
    }
}

/** Анализатор партии через онлайн Stockfish. */
class StockfishOnlineAnalyzer(
    private val api: StockfishOnlineService
) {
    private data class Eval(
        val evalPawns: Double?,
        val mate: Int?,
        val bestRaw: String?
    )

    private suspend fun analyzeFen(fen: String, depth: Int): Eval {
        try {
            val resp = api.analyze(fen, depth)
            val body = resp.body()
            if (resp.isSuccessful && body != null) {
                return Eval(body.evaluation, body.mate, body.bestmove)
            }
        } catch (_: Exception) { /* ignore */ }

        val rawResp = api.analyzeRaw(fen, depth)
        val body: ResponseBody = rawResp.body() ?: throw IOException("StockfishOnline: empty body")
        val text = body.string().trim()
        val jsonStr = if (text.startsWith("{")) {
            text
        } else {
            val start = text.indexOf('{')
            val end = text.lastIndexOf('}')
            if (start >= 0 && end > start) text.substring(start, end + 1)
            else throw IOException("StockfishOnline: not a JSON")
        }
        val obj = JSONObject(jsonStr)
        val eval = when {
            obj.has("evaluation") -> obj.optDouble("evaluation")
            obj.has("eval") -> obj.optDouble("eval")
            else -> null
        }
        val mate = when {
            obj.has("mate") -> obj.optInt("mate")
            obj.has("Mate") -> obj.optInt("Mate")
            else -> null
        }.let { if (it == 0) null else it }
        val bestRaw = when {
            obj.has("bestmove") -> obj.optString("bestmove")
            obj.has("bestMove") -> obj.optString("bestMove")
            obj.has("best") -> obj.optString("best")
            else -> null
        }
        return Eval(if (eval != null && !eval.isNaN()) eval else null, mate, bestRaw)
    }

    private fun bestUciFromRaw(bestRaw: String?): String {
        if (bestRaw.isNullOrBlank()) return ""
        val parts = bestRaw.trim().split(Regex("\\s+"))
        val idx = parts.indexOfFirst { it.equals("bestmove", ignoreCase = true) }
        return when {
            idx >= 0 && idx + 1 < parts.size -> parts[idx + 1]
            parts.isNotEmpty() -> parts.first()
            else -> ""
        }
    }

    private fun sideToMoveIsWhite(fen: String): Boolean =
        fen.split(' ').getOrNull(1)?.lowercase() == "w"

    private fun cpWhiteFrom(evalPawns: Double?, mate: Int?): Int =
        if (mate != null) if (mate > 0) 20_000 else -20_000
        else ((evalPawns ?: 0.0) * 100.0).roundToInt()

    private fun winForMoverFromCp(cpWhite: Int, moverIsWhite: Boolean): Double {
        val cpForMover = if (moverIsWhite) cpWhite else -cpWhite
        return MathEval.winPercentFromCp(cpForMover)
    }

    suspend fun analyzeGame(pgn: String, depth: Int = 16): AnalysisResult =
        withContext(Dispatchers.IO) {
            val plies = ChessParser.pgnToPlies(pgn)
            val board = BoardState.initial()
            val moves = ArrayList<MoveAnalysis>(plies.size)
            val perMoveAcc = ArrayList<Double>()
            plies.forEachIndexed { idx, ply ->
                val fenBefore = board.toFEN()
                val moverIsWhite = sideToMoveIsWhite(fenBefore)
                val before = analyzeFen(fenBefore, depth)
                val cpBeforeWhite = cpWhiteFrom(before.evalPawns, before.mate)
                val winBeforeMover = winForMoverFromCp(cpBeforeWhite, moverIsWhite)

                board.applySan(ply.san)
                val fenAfter = board.toFEN()
                val after = analyzeFen(fenAfter, depth)
                val cpAfterWhite = cpWhiteFrom(after.evalPawns, after.mate)
                val winAfterMover = winForMoverFromCp(cpAfterWhite, moverIsWhite)

                val cpBeforeForMover = if (moverIsWhite) cpBeforeWhite else -cpBeforeWhite
                val cpAfterForMover = if (moverIsWhite) cpAfterWhite else -cpAfterWhite
                val deltaPawns = (cpBeforeForMover - cpAfterForMover) / 100.0

                val drop = (winBeforeMover - winAfterMover).coerceAtLeast(0.0)
                val clsTag = MathEval.classifyMove(
                    bestMatch = false,
                    winDrop = drop,
                    mateSwing = false
                )
                val cls: MoveClass = when (clsTag) {
                    MathEval.MoveTag.Best, MathEval.MoveTag.Excellent -> MoveClass.GREAT
                    MathEval.MoveTag.Good -> MoveClass.GOOD
                    MathEval.MoveTag.Inaccuracy -> MoveClass.INACCURACY
                    MathEval.MoveTag.Mistake -> MoveClass.MISTAKE
                    MathEval.MoveTag.Blunder -> MoveClass.BLUNDER
                }

                val acc = MathEval.moveAccuracy(winBeforeMover, winAfterMover)
                perMoveAcc += acc

                moves += MoveAnalysis(
                    moveNumber = idx + 1,
                    san = ply.san,
                    bestMove = bestUciFromRaw(before.bestRaw),
                    evaluation = cpAfterWhite / 100.0,
                    delta = deltaPawns,
                    classification = cls
                )
            }
            val counts = moves.groupingBy { it.classification }.eachCount()
            val accuracy = if (perMoveAcc.isEmpty()) 0.0 else perMoveAcc.average()
            val summary = AnalysisSummary(
                totalMoves = moves.size,
                counts = counts,
                accuracy = accuracy
            )
            AnalysisResult(summary = summary, moves = moves)
        }
}
