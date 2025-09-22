package com.example.chessanalysis.engine

import com.example.chessanalysis.data.api.ChessApiService
import com.example.chessanalysis.data.model.*
import com.example.chessanalysis.data.util.BoardState
import com.example.chessanalysis.data.util.ChessParser
import com.example.chessanalysis.data.util.MoveClassifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

/** Преобразование оценки в вероятность выигрыша (как на Chess.com). */
object MathEval {
    private const val A = 0.00368208
    fun winPercentFromCp(cp: Int): Double {
        val x = A * cp.toDouble()
        val p = 50 + 50 * (2.0 / (1.0 + kotlin.math.exp(-x)) - 1.0)
        return p.coerceIn(0.0, 100.0)
    }
}

/** Анализ партии посредством chess‑api.com. */
class StockfishOnlineAnalyzer(
    private val chessApi: ChessApiService
) {

    /** DTO внутреннего анализа. */
    private data class Eval(
        val evalPawns: Double?,
        val mate: Int?,
        val bestUci: String?,
        val winChance: Double?
    )

    /** Вызов chess-api для FEN; глубина ограничена 18 по документации. */
    private suspend fun analyzeFen(fen: String, depth: Int): Eval {
        val req = com.example.chessanalysis.data.api.ChessApiRequest(
            fen = fen,
            variants = 1,
            depth = depth.coerceIn(2, 18),
            maxThinkingTime = 1000,
            searchMoves = null
        )
        val resp = chessApi.analyzePosition(req)
        val evalPawns = resp.eval ?: resp.centipawns?.toDouble()?.div(100.0)
        val mate = resp.mate
        val bestMove = resp.move
        val win = resp.winChance
        return Eval(evalPawns = evalPawns, mate = mate, bestUci = bestMove, winChance = win)
    }

    /** Оценка в центепешках для белых с учётом мата. */
    private fun cpWhiteFrom(evalPawns: Double?, mate: Int?): Int =
        if (mate != null) if (mate > 0) 20_000 else -20_000
        else ((evalPawns ?: 0.0) * 100.0).roundToInt()

    /** Анализ PGN, возвращает AnalysisResult. */
    suspend fun analyzeGame(pgn: String, depth: Int = 14): AnalysisResult =
        withContext(Dispatchers.IO) {
            val plies = ChessParser.pgnToPlies(pgn)
            val board = BoardState.initial()
            val moves = ArrayList<MoveAnalysis>(plies.size)
            val deltas = mutableListOf<Double>()
            val perMoveWinAcc = ArrayList<Double>()

            plies.forEachIndexed { idx, ply ->
                val fenBefore = board.toFEN()
                val moverIsWhite = fenBefore.split(' ')[1] == "w"
                val before = analyzeFen(fenBefore, depth)
                val cpBeforeWhite = cpWhiteFrom(before.evalPawns, before.mate)
                val winBefore = before.winChance ?: MathEval.winPercentFromCp(if (moverIsWhite) cpBeforeWhite else -cpBeforeWhite)

                board.applySan(ply.san, moverIsWhite)
                val fenAfter = board.toFEN()
                val after = analyzeFen(fenAfter, depth)
                val cpAfterWhite = cpWhiteFrom(after.evalPawns, after.mate)
                val winAfter = after.winChance ?: MathEval.winPercentFromCp(if (moverIsWhite) cpAfterWhite else -cpAfterWhite)

                val cpBeforeForMover = if (moverIsWhite) cpBeforeWhite else -cpBeforeWhite
                val cpAfterForMover = if (moverIsWhite) cpAfterWhite else -cpAfterWhite
                val deltaPawns = (cpBeforeForMover - cpAfterForMover) / 100.0
                deltas += deltaPawns

                val drop = (winBefore - winAfter).coerceAtLeast(0.0)
                val cls = MoveClassifier.classify(abs(deltaPawns))
                perMoveWinAcc += 100.0 - drop

                moves += MoveAnalysis(
                    moveNumber = idx + 1,
                    san = ply.san,
                    bestMove = before.bestUci,
                    evaluation = cpAfterWhite / 100.0,
                    delta = deltaPawns,
                    classification = cls
                )
            }

            // точность: среднее по win%
            val totalAcc = if (perMoveWinAcc.isEmpty()) 100.0 else perMoveWinAcc.average()
            // точность по цветам (ACPL на основе дельт)
            fun accuracySide(isWhite: Boolean): Double {
                val side = moves.filter { (it.moveNumber % 2 == 1) == isWhite }
                if (side.isEmpty()) return 100.0
                val avgCpLoss = side.sumOf { abs(it.delta) * 100.0 } / side.size
                return (100.0 - avgCpLoss / 3.0).coerceIn(0.0, 100.0)
            }

            // классические метрики для перформанса
            fun scoreByResult(tag: String?, forWhite: Boolean): Double? = when (tag) {
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

            val summaryCounts = moves.groupingBy { it.classification }.eachCount()
            AnalysisResult(
                summary = AnalysisSummary(
                    totalMoves = moves.size,
                    counts = summaryCounts,
                    accuracy = totalAcc,
                    accuracyWhite = accuracySide(true),
                    accuracyBlack = accuracySide(false),
                    perfWhite = null,
                    perfBlack = null
                ),
                moves = moves
            )
        }
}
