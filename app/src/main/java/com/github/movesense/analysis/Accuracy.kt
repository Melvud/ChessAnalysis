package com.github.movesense.analysis

import com.github.movesense.PositionEval
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

data class AccuracyResult(
    val white: PlayerAccuracy,
    val black: PlayerAccuracy
)

data class PlayerAccuracy(
    val itera: Double,
    val weighted: Double,
    val harmonic: Double
)

object Accuracy {

    fun computeAccuracy(positions: List<PositionEval>): AccuracyResult {
        val positionsWinPercentage = positions.map {
            WinPercentage.getPositionWinPercentage(it)
        }

        val weights = getAccuracyWeights(positionsWinPercentage)
        val movesAccuracy = getMovesAccuracy(positionsWinPercentage)

        val whiteAccuracy = getPlayerAccuracy(movesAccuracy, weights, "white")
        val blackAccuracy = getPlayerAccuracy(movesAccuracy, weights, "black")

        return AccuracyResult(
            white = whiteAccuracy,
            black = blackAccuracy
        )
    }

    private fun getPlayerAccuracy(
        movesAccuracy: List<Double>,
        weights: List<Double>,
        player: String
    ): PlayerAccuracy {
        val remainder = if (player == "white") 0 else 1
        val playerAccuracies = movesAccuracy.filterIndexed { index, _ ->
            index % 2 == remainder
        }
        val playerWeights = weights.filterIndexed { index, _ ->
            index % 2 == remainder
        }

        val weightedMean = MathUtils.getWeightedMean(playerAccuracies, playerWeights)
        val harmonicMean = MathUtils.getHarmonicMean(playerAccuracies)
        val itera = (weightedMean + harmonicMean) / 2.0

        return PlayerAccuracy(
            itera = itera,
            weighted = weightedMean,
            harmonic = harmonicMean
        )
    }

    private fun getAccuracyWeights(movesWinPercentage: List<Double>): List<Double> {
        val windowSize = MathUtils.ceilsNumber(
            ceil(movesWinPercentage.size / 10.0),
            2.0,
            8.0
        ).toInt()

        val windows = mutableListOf<List<Double>>()
        val halfWindowSize = round(windowSize / 2.0).toInt()

        for (i in 1 until movesWinPercentage.size) {
            val startIdx = i - halfWindowSize
            val endIdx = i + halfWindowSize

            when {
                startIdx < 0 -> {
                    windows.add(movesWinPercentage.take(windowSize))
                }
                endIdx > movesWinPercentage.size -> {
                    windows.add(movesWinPercentage.takeLast(windowSize))
                }
                else -> {
                    windows.add(movesWinPercentage.subList(startIdx, endIdx))
                }
            }
        }

        return windows.map { window ->
            val std = MathUtils.getStandardDeviation(window)
            MathUtils.ceilsNumber(std, 0.5, 12.0)
        }
    }

    private fun getMovesAccuracy(movesWinPercentage: List<Double>): List<Double> {
        return movesWinPercentage.drop(1).mapIndexed { index, winPercent ->
            val lastWinPercent = movesWinPercentage[index]
            val isWhiteMove = index % 2 == 0
            val winDiff = if (isWhiteMove) {
                max(0.0, lastWinPercent - winPercent)
            } else {
                max(0.0, winPercent - lastWinPercent)
            }

            // Source: https://github.com/lichess-org/lila/blob/a320a93b68dabee862b8093b1b2acdfe132b9966/modules/analyse/src/main/AccuracyPercent.scala#L44
            val rawAccuracy = 103.1668100711649 * exp(-0.04354415386753951 * winDiff) - 3.166924740191411

            min(100.0, max(0.0, rawAccuracy + 1.0))
        }
    }

    // Per-move accuracy from win percentages (for FullReport)
    fun perMoveAccFromWin(winPercents: List<Double>): List<Double> {
        return getMovesAccuracy(winPercents)
    }
}