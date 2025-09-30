package com.example.chessanalysis.local

import kotlin.math.*

object EstimateElo {
    data class Est(val white: Int?, val black: Int?)

    fun computeEstimatedElo(positions: List<com.example.chessanalysis.EngineClient.PositionDTO>, whiteElo: Int?, blackElo: Int?): Est {
        if (positions.size < 2) return Est(null, null)
        val (whiteCpl, blackCpl) = getPlayersAverageCpl(positions)
        val w = getEloFromRatingAndCpl(whiteCpl, whiteElo ?: blackElo)
        val b = getEloFromRatingAndCpl(blackCpl, blackElo ?: whiteElo)
        return Est(w.roundToInt(), b.roundToInt())
    }

    private fun getPositionCp(p: com.example.chessanalysis.EngineClient.PositionDTO): Int {
        val line = p.lines.first()
        if (line.cp != null) return Mathx.ceilsNumber(line.cp.toDouble(), -1000.0, 1000.0).toInt()
        if (line.mate != null) return Mathx.ceilsNumber(if (line.mate > 0) Double.POSITIVE_INFINITY else Double.NEGATIVE_INFINITY, -1000.0, 1000.0).toInt()
        error("No cp or mate in line")
    }

    private fun getPlayersAverageCpl(positions: List<com.example.chessanalysis.EngineClient.PositionDTO>): Pair<Double,Double> {
        var prevCp = getPositionCp(positions.first())
        var whiteCpl = 0.0
        var blackCpl = 0.0
        positions.drop(1).forEachIndexed { idx, p ->
            val cp = getPositionCp(p)
            if (idx % 2 == 0) whiteCpl += if (cp > prevCp) 0.0 else min((prevCp - cp).toDouble(), 1000.0)
            else blackCpl += if (cp < prevCp) 0.0 else min((cp - prevCp).toDouble(), 1000.0)
            prevCp = cp
        }
        val whiteAvg = whiteCpl / kotlin.math.ceil((positions.size - 1) / 2.0)
        val blackAvg = blackCpl / kotlin.math.floor((positions.size - 1) / 2.0)
        return whiteAvg to blackAvg
    }

    private fun getEloFromAverageCpl(avgCpl: Double) = 3100 * exp(-0.01 * avgCpl)
    private fun getAverageCplFromElo(elo: Double) = -100 * ln(min(elo, 3100.0) / 3100.0)
    private fun getEloFromRatingAndCpl(gameCpl: Double, rating: Int?): Double {
        val eloFromCpl = getEloFromAverageCpl(gameCpl)
        rating ?: return eloFromCpl
        val expectedCpl = getAverageCplFromElo(rating.toDouble())
        val diff = gameCpl - expectedCpl
        if (diff == 0.0) return eloFromCpl
        return if (diff > 0) rating * exp(-0.005 * diff) else rating / exp(-0.005 * -diff)
    }
}
