package com.example.chessanalysis.analysis

import com.example.chessanalysis.PositionEval
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.min

data class EstimatedEloResult(
    val white: Int?,
    val black: Int?
)

object EstimateElo {

    fun computeEstimatedElo(
        positions: List<PositionEval>,
        whiteElo: Int?,
        blackElo: Int?
    ): EstimatedEloResult {
        if (positions.size < 2) {
            return EstimatedEloResult(null, null)
        }

        val (whiteCpl, blackCpl) = getPlayersAverageCpl(positions)

        val whiteEstimatedElo = getEloFromRatingAndCpl(whiteCpl, whiteElo ?: blackElo)
        val blackEstimatedElo = getEloFromRatingAndCpl(blackCpl, blackElo ?: whiteElo)

        return EstimatedEloResult(
            white = whiteEstimatedElo?.toInt(),
            black = blackEstimatedElo?.toInt()
        )
    }

    private fun getPositionCp(position: PositionEval): Int {
        val line = position.lines.firstOrNull()
            ?: throw IllegalStateException("No lines in position")

        return when {
            line.cp != null -> MathUtils.ceilsNumber(line.cp.toDouble(), -1000.0, 1000.0).toInt()
            line.mate != null -> {
                val value = line.mate * Double.POSITIVE_INFINITY
                MathUtils.ceilsNumber(value, -1000.0, 1000.0).toInt()
            }
            else -> throw IllegalStateException("No cp or mate in line")
        }
    }

    private fun getPlayersAverageCpl(positions: List<PositionEval>): Pair<Double, Double> {
        var previousCp = getPositionCp(positions[0])

        var whiteCpl = 0.0
        var blackCpl = 0.0

        positions.drop(1).forEachIndexed { index, position ->
            val cp = getPositionCp(position)

            if (index % 2 == 0) {
                whiteCpl += if (cp > previousCp) 0.0 else min((previousCp - cp).toDouble(), 1000.0)
            } else {
                blackCpl += if (cp < previousCp) 0.0 else min((cp - previousCp).toDouble(), 1000.0)
            }

            previousCp = cp
        }

        val whiteAvg = whiteCpl / ceil((positions.size - 1) / 2.0)
        val blackAvg = blackCpl / floor((positions.size - 1) / 2.0)

        return Pair(whiteAvg, blackAvg)
    }

    // Source: https://lichess.org/forum/general-chess-discussion/how-to-estimate-your-elo-for-a-game-using-acpl-and-what-it-realistically-means
    private fun getEloFromAverageCpl(averageCpl: Double): Double {
        return 3100.0 * exp(-0.01 * averageCpl)
    }

    private fun getAverageCplFromElo(elo: Double): Double {
        return -100.0 * ln(min(elo, 3100.0) / 3100.0)
    }

    private fun getEloFromRatingAndCpl(gameCpl: Double, rating: Int?): Double? {
        val eloFromCpl = getEloFromAverageCpl(gameCpl)
        if (rating == null) return eloFromCpl

        val expectedCpl = getAverageCplFromElo(rating.toDouble())
        val cplDiff = gameCpl - expectedCpl

        if (cplDiff == 0.0) return eloFromCpl

        return if (cplDiff > 0) {
            rating * exp(-0.005 * cplDiff)
        } else {
            rating / exp(-0.005 * -cplDiff)
        }
    }
}