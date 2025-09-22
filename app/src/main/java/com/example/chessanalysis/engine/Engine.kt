package com.example.chessanalysis.engine

import android.util.Log
import com.example.chessanalysis.data.api.ChessApiService
import com.example.chessanalysis.data.model.*
import com.example.chessanalysis.data.util.BoardState
import com.example.chessanalysis.data.util.ChessParser
import com.example.chessanalysis.data.util.MoveClassifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Преобразование оценки в вероятность выигрыша (как на Chess.com). */
object MathEval {
    private const val A = 0.00368208
    fun winPercentFromCp(cp: Int): Double {
        val x = A * cp.toDouble()
        val p = 50 + 50 * (2.0 / (1.0 + kotlin.math.exp(-x)) - 1.0)
        return p.coerceIn(0.0, 100.0)
    }
    fun clamp01to100(x: Double): Double = when {
        x.isNaN() -> 50.0
        x.isInfinite() -> if (x > 0) 100.0 else 0.0
        else -> x.coerceIn(0.0, 100.0)
    }
}

/** Анализ партии через chess-api.com. */
class StockfishOnlineAnalyzer(
    private val chessApi: ChessApiService
) {

    private data class Eval(
        val evalPawns: Double?,
        val mate: Int?,
        val bestUci: String?,
        val winChance: Double?
    )

    private suspend fun analyzeFen(fen: String, depth: Int): Eval {
        val req = com.example.chessanalysis.data.api.ChessApiRequest(
            fen = fen,
            variants = 1,
            depth = depth.coerceIn(2, 18),
            maxThinkingTime = 1000,
            searchMoves = null
        )
        val r = chessApi.analyzePosition(req)
        val evalPawns = r.eval ?: r.centipawns?.toDouble()?.div(100.0)
        return Eval(
            evalPawns = evalPawns,
            mate = r.mate,
            bestUci = r.move,
            winChance = r.winChance
        )
    }

    /** Оценка позиции в центпешках для белых (с учётом мата). */
    private fun cpWhiteFrom(evalPawns: Double?, mate: Int?): Int =
        if (mate != null) {
            if (mate > 0) 20_000 else -20_000
        } else ((evalPawns ?: 0.0) * 100.0).roundToInt()

    /** Главный анализ PGN. */
    suspend fun analyzeGame(pgn: String, depth: Int = 14): AnalysisResult =
        withContext(Dispatchers.IO) {
            val TAG = "Analyzer"
            Log.d(TAG, "=== NEW GAME ANALYSIS === depth=$depth")

            val plies = ChessParser.pgnToPlies(pgn)
            val board = BoardState.initial()
            val moves = ArrayList<MoveAnalysis>(plies.size)
            val perMoveWinAcc = ArrayList<Double>()
            val lossesCpWhite = ArrayList<Double>() // для ACPL/логов

            plies.forEachIndexed { idx, ply ->
                val moveNum = idx + 1
                val fenBefore = board.toFEN()
                val moverIsWhite = fenBefore.split(' ')[1] == "w"

                val before = analyzeFen(fenBefore, depth)
                val cpBeforeWhite = cpWhiteFrom(before.evalPawns, before.mate)
                val winBefore = MathEval.clamp01to100(
                    before.winChance ?: MathEval.winPercentFromCp(if (moverIsWhite) cpBeforeWhite else -cpBeforeWhite)
                )

                board.applySan(ply.san, moverIsWhite)
                val fenAfter = board.toFEN()
                val after = analyzeFen(fenAfter, depth)
                val cpAfterWhite = cpWhiteFrom(after.evalPawns, after.mate)
                val winAfter = MathEval.clamp01to100(
                    after.winChance ?: MathEval.winPercentFromCp(if (moverIsWhite) cpAfterWhite else -cpAfterWhite)
                )

                // Оценки относительно делающей ходы стороны
                val cpBeforeForMover = if (moverIsWhite) cpBeforeWhite else -cpBeforeWhite
                val cpAfterForMover  = if (moverIsWhite) cpAfterWhite  else -cpAfterWhite

                // Потеря (а НЕ модуль качелей). Только ухудшения считаются.
                val lossCp   = max(0.0, (cpBeforeForMover - cpAfterForMover).toDouble())
                val lossPawns = lossCp / 100.0
                lossesCpWhite += if (moverIsWhite) lossCp else 0.0

                // Падение вероятности победы (0..100)
                val dropWin = max(0.0, winBefore - winAfter)
                perMoveWinAcc += (100.0 - dropWin)

                val cls = MoveClassifier.classifyLoss(lossPawns)

                moves += MoveAnalysis(
                    moveNumber = moveNum,
                    san = ply.san,
                    bestMove = before.bestUci,          // подсказка для баннера
                    evaluation = cpAfterWhite / 100.0,  // в пешках, для белых
                    delta = lossPawns,                   // теперь delta == ПОТЕРЯ в пешках
                    classification = cls
                )

                Log.d(TAG,
                    "Move #$moveNum ${if (moverIsWhite) "WHITE" else "BLACK"} ${ply.san} | " +
                            "cpW: $cpBeforeWhite -> $cpAfterWhite | " +
                            "cp(mover): $cpBeforeForMover -> $cpAfterForMover | " +
                            "win%: ${"%.2f".format(winBefore)} -> ${"%.2f".format(winAfter)} | " +
                            "lossPawns=${"%.3f".format(lossPawns)} class=$cls best=${before.bestUci}"
                )
            }

            // Глобальная точность = смесь win% и ACPL (как на Chess.com)
            val avgWinAcc = if (perMoveWinAcc.isEmpty()) 100.0 else perMoveWinAcc.average()
            fun sideAcpl(isWhite: Boolean): Double {
                val sideMoves = moves.filter { (it.moveNumber % 2 == 1) == isWhite }
                return if (sideMoves.isEmpty()) 0.0
                else sideMoves.sumOf { it.delta * 100.0 } / sideMoves.size
            }
            fun sideAccuracy(isWhite: Boolean): Double {
                val acpl = sideAcpl(isWhite)
                val accFromAcpl = (100.0 - acpl / 3.0).coerceIn(0.0, 100.0)
                // смешиваем с удержанным win% (берём среднее по ходам стороны)
                val sideWin = moves.filter { (it.moveNumber % 2 == 1) == isWhite }
                    .mapIndexed { i, _ -> perMoveWinAcc.getOrNull(i * 2 + if (isWhite) 0 else 1) ?: 100.0 }
                val sideWinAvg = if (sideWin.isEmpty()) 100.0 else sideWin.average()
                return (accFromAcpl * 0.5 + sideWinAvg * 0.5).coerceIn(0.0, 100.0)
            }

            val accWhite = sideAccuracy(true)
            val accBlack = sideAccuracy(false)
            val totalAcc = (accWhite + accBlack) / 2.0

            // Перфоманс из PGN-тегов (как на Chess.com: оппонентЭло ± 400*(score-0.5))
            fun tagInt(key: String): Int? =
                Regex("\\[$key\\s+\"(\\d+)\"\\]").find(pgn)?.groupValues?.getOrNull(1)?.toIntOrNull()
            val whiteElo = tagInt("WhiteElo")
            val blackElo = tagInt("BlackElo")
            val resultTag = Regex("\\[Result\\s+\"(.*?)\"\\]").find(pgn)?.groupValues?.getOrNull(1)
            fun score(forWhite: Boolean) = when (resultTag) {
                "1-0" -> if (forWhite) 1.0 else 0.0
                "0-1" -> if (forWhite) 0.0 else 1.0
                "1/2-1/2" -> 0.5
                else -> null
            }
            fun performance(opponentElo: Int?, score: Double?): Int? {
                if (opponentElo == null || score == null) return null
                val delta = (400.0 * (score - 0.5)).roundToInt()
                return (opponentElo + delta).coerceIn(500, 3200)
            }
            val perfW = performance(blackElo, score(true))
            val perfB = performance(whiteElo, score(false))

            val summaryCounts = moves.groupingBy { it.classification }.eachCount()

            Log.d(TAG, "--- SUMMARY ---")
            Log.d(TAG, "ACPL white=${"%.1f".format(sideAcpl(true))} black=${"%.1f".format(sideAcpl(false))}")
            Log.d(TAG, "ACC white=${"%.1f".format(accWhite)}% black=${"%.1f".format(accBlack)}% total=${"%.1f".format(totalAcc)}%")
            Log.d(TAG, "PERF white=${perfW ?: "-"} black=${perfB ?: "-"}; result=$resultTag; tags W:$whiteElo B:$blackElo")
            Log.d(TAG, "Counts: $summaryCounts")
            Log.d(TAG, "=== END GAME ANALYSIS ===")

            AnalysisResult(
                summary = AnalysisSummary(
                    totalMoves = moves.size,
                    counts = summaryCounts,
                    accuracy = totalAcc,
                    accuracyWhite = accWhite,
                    accuracyBlack = accBlack,
                    perfWhite = perfW,
                    perfBlack = perfB
                ),
                moves = moves
            )
        }
}
