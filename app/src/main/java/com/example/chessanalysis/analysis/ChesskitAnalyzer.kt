package com.example.chessanalysis.analysis

import android.util.Log
import com.example.chessanalysis.data.model.*
import com.example.chessanalysis.opening.OpeningBook
import com.example.chessanalysis.util.MathEval
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Полная портировка логики Chesskit:
 * - классификация по падению win% (Brunder/Mistake/Inaccuracy/Excellent/Best/Splendid/Perfect/Opening/Forced)
 * - ACPL (с учётом обрезания до 1000cp и матов)
 * - Accuracy% (экспон. формула + веса по stddev окна)
 * - Performance (Elo ~ 3100*exp(-0.01*ACPL), с мягкой поправкой от реального рейтинга)
 */
class ChesskitAnalyzer(
    private val engine: StockfishAnalyzer
) {

    companion object {
        private const val TAG = "Analyzer"
        private const val MAX_CP = 1000
        private const val BLUNDER_DROP = 20.0
        private const val MISTAKE_DROP = 10.0
        private const val INACC_DROP = 5.0
        private const val OKAY_DROP = 2.0
    }

    suspend fun analyze(game: GameSummary): AnalysisResult {
        val positions = ChessRebuilder.buildPositions(game.pgn)
        val moves = mutableListOf<MoveAnalysis>()

        var acplWhiteSum = 0
        var acplBlackSum = 0
        var whiteMoves = 0
        var blackMoves = 0

        var accWhiteScores = mutableListOf<Double>()
        var accBlackScores = mutableListOf<Double>()
        val winSeries = mutableListOf<Double>() // для расчёта весов

        var terminatedOnMate = false

        for ((idx, pos) in positions.withIndex()) {
            val fenBefore = pos.fen
            val evalBefore = engine.analyzeFen(fenBefore)
            val fenAfter = pos.fenAfter ?: break

            // Вычисляем win% ДО/ПОСЛЕ строго из cp для хода (как Chesskit)
            val winBefore = MathEval.clamp01to100(
                MathEval.winPercentFromCp(evalBefore.cpWhiteForMover(fenBefore))
            )

            val evalAfter = engine.analyzeFen(fenAfter)
            val winAfter = MathEval.clamp01to100(
                MathEval.winPercentFromCp(evalAfter.cpWhiteForMover(fenAfter))
            )

            // Падение для стороны, сделавшей ход
            val moverIsWhite = fenBefore.contains(" w ")
            val loss = if (moverIsWhite) {
                max(0.0, winBefore - winAfter)
            } else {
                max(0.0, winAfter - winBefore)
            }

            // Классификация по Chesskit
            val cls = classify(loss, pos, evalBefore, evalAfter)

            // ACPL: берём ухудшение в cp для стороны, сделавшей ход
            val cpBeforeWhite = evalBefore.cpWhite.coerceIn(-MAX_CP, MAX_CP)
            val cpAfterWhite = evalAfter.cpWhite.coerceIn(-MAX_CP, MAX_CP)
            val cpLoss = if (moverIsWhite) {
                max(0, cpBeforeWhite - cpAfterWhite)
            } else {
                max(0, cpAfterWhite - cpBeforeWhite)
            }.coerceIn(0, MAX_CP)

            if (moverIsWhite) {
                acplWhiteSum += cpLoss
                whiteMoves++
            } else {
                acplBlackSum += cpLoss
                blackMoves++
            }

            // Очки точности за ход (экспон. формула lichess)
            val accScore = MathEval.accuracyFromWinDrop(loss)
            if (moverIsWhite) accWhiteScores.add(accScore) else accBlackScores.add(accScore)

            winSeries.add(winAfter)

            // Проверка на мат — дальше не считаем
            if (evalAfter.mate != null && abs(evalAfter.mate) < 3) {
                terminatedOnMate = true
                moves += MoveAnalysis(
                    moveNumber = pos.moveNumber,
                    san = pos.san,
                    uci = pos.uci,
                    bestMove = evalBefore.bestMove,
                    classification = cls,
                    dropWinPercent = loss
                )
                break
            }

            moves += MoveAnalysis(
                moveNumber = pos.moveNumber,
                san = pos.san,
                uci = pos.uci,
                bestMove = evalBefore.bestMove,
                classification = cls,
                dropWinPercent = loss
            )
        }

        val acplWhite = if (whiteMoves > 0) acplWhiteSum.toDouble() / whiteMoves else 0.0
        val acplBlack = if (blackMoves > 0) acplBlackSum.toDouble() / blackMoves else 0.0

        val weights = MathEval.computeWeights(winSeries)
        val accWhite = MathEval.aggregateAccuracy(accWhiteScores, weights.filterIndexed { i, _ -> i % 2 == 0 })
        val accBlack = MathEval.aggregateAccuracy(accBlackScores, weights.filterIndexed { i, _ -> i % 2 == 1 })

        val opening = OpeningBook.detect(positions.map { it.san })

        val (perfW, perfB) = MathEval.estimatePerformanceByAcpl(
            whiteElo = game.whiteElo,
            blackElo = game.blackElo,
            acplWhite = acplWhite,
            acplBlack = acplBlack
        )

        val summary = GameSummaryStats(
            accuracyWhite = accWhite,
            accuracyBlack = accBlack,
            acplWhite = acplWhite,
            acplBlack = acplBlack,
            perfWhite = perfW,
            perfBlack = perfB,
            opening = opening,
            counts = moves.groupingBy { it.classification }.eachCount()
        )

        Log.d(TAG, "--- SUMMARY ---")
        Log.d(TAG, "ACPL white=${"%.1f".format(acplWhite)} black=${"%.1f".format(acplBlack)}")
        Log.d(TAG, "ACC white=${"%.1f".format(accWhite)}% black=${"%.1f".format(accBlack)}% total=${"%.1f".format((accWhite+accBlack)/2)}%")
        Log.d(TAG, "PERF (ACPL) white=$perfW black=$perfB; tags W:${game.whiteElo ?: "-"} B:${game.blackElo ?: "-"}")

        return AnalysisResult(moves = moves, summary = summary)
    }

    private fun classify(
        drop: Double,
        pos: PositionNode,
        before: EngineEval,
        after: EngineEval
    ): MoveClass {
        // Открытие?
        OpeningBook.classifyIfOpening(pos)?.let { return MoveClass.OPENING }

        // Лучший?
        if (pos.uci != null && before.bestMove != null && pos.uci == before.bestMove) {
            // Отличить Perfect/Splendid: оставим базовую ветку Chesskit по drop + фильтры
            if (isSplendidSacrifice(pos, before, after)) return MoveClass.SPLENDID
            if (isPerfect(pos, before, after)) return MoveClass.PERFECT
            return MoveClass.BEST
        }

        // Градации по Chesskit (win% для стороны-хода)
        return when {
            drop > BLUNDER_DROP -> MoveClass.BLUNDER
            drop > MISTAKE_DROP -> MoveClass.MISTAKE
            drop > INACC_DROP   -> MoveClass.INACCURACY
            drop > OKAY_DROP    -> MoveClass.OKAY
            else                -> MoveClass.EXCELLENT
        }
    }

    private fun isSplendidSacrifice(pos: PositionNode, before: EngineEval, after: EngineEval): Boolean {
        // Упрощённый детектор жертвы фигуры + отсутствие ухудшения (<=2%)
        val sac = pos.isPieceSacrifice
        if (!sac) return false
        val drop = abs(
            MathEval.winPercentFromCp(before.cpWhiteForMover(pos.fen)) -
                    MathEval.winPercentFromCp(after.cpWhiteForMover(pos.fenAfter ?: pos.fen))
        )
        return drop <= 2.0
    }

    private fun isPerfect(pos: PositionNode, before: EngineEval, after: EngineEval): Boolean {
        // Нет жертвы, но ошибка <=2%, позиция сложная (порог сложности уже в весах Accuracy)
        val drop = abs(
            MathEval.winPercentFromCp(before.cpWhiteForMover(pos.fen)) -
                    MathEval.winPercentFromCp(after.cpWhiteForMover(pos.fenAfter ?: pos.fen))
        )
        return drop <= 2.0 && !pos.isSimpleRecapture
    }

    /** Расширение — cp для стороны-хода (как в Chesskit). */
    private fun EngineEval.cpWhiteForMover(fen: String): Int {
        val whiteToMove = fen.contains(" w ")
        return if (whiteToMove) cpWhite else -cpWhite
    }
}
