package com.example.chessanalysis.local

import kotlin.math.*

object Accuracy {

    fun getAccuracyWeights(movesWinPercentage: List<Double>): List<Double> {
        val windowSize = Mathx.ceilsNumber(kotlin.math.ceil(movesWinPercentage.size / 10.0), 2.0, 8.0)
        val windows = mutableListOf<List<Double>>()
        val half = round(windowSize / 2.0).toInt()
        for (i in 1 until movesWinPercentage.size) {
            val start = i - half
            val end = i + half
            windows += when {
                start < 0 -> movesWinPercentage.subList(0, min(windowSize.toInt(), movesWinPercentage.size))
                end > movesWinPercentage.size -> movesWinPercentage.subList(max(0, movesWinPercentage.size - windowSize.toInt()), movesWinPercentage.size)
                else -> movesWinPercentage.subList(start, end)
            }
        }
        return windows.map { w -> Mathx.ceilsNumber(Mathx.stddev(w), 0.5, 12.0) }
    }

    fun getMovesAccuracy(movesWinPercentage: List<Double>): List<Double> {
        if (movesWinPercentage.size < 2) return emptyList()
        val out = ArrayList<Double>(movesWinPercentage.size - 1)
        for (i in 1 until movesWinPercentage.size) {
            val last = movesWinPercentage[i - 1]
            val cur = movesWinPercentage[i]
            val isWhiteMove = (i - 1) % 2 == 0
            val winDiff = if (isWhiteMove) max(0.0, last - cur) else max(0.0, cur - last)
            val raw = 103.1668100711649 * exp(-0.04354415386753951 * winDiff) - 3.166924740191411
            out += min(100.0, max(0.0, raw + 1.0))
        }
        return out
    }

    fun getPlayerAccuracy(movesAccuracy: List<Double>, weights: List<Double>, player: String): Double {
        val remainder = if (player.lowercase() == "white") 0 else 1
        val list = movesAccuracy.filterIndexed { idx, _ -> idx % 2 == remainder }
        val ws = weights.filterIndexed { idx, _ -> idx % 2 == remainder }
        val weighted = Mathx.weightedMean(list, ws)
        val harmonic = Mathx.harmonicMean(list)
        return (weighted + harmonic) / 2.0
    }

    fun perMoveAccFromWin(winPercents: List<Double>): List<Double> {
        val out = ArrayList<Double>(max(0, winPercents.size - 1))
        for (i in 1 until winPercents.size) {
            val isWhiteMove = (i - 1) % 2 == 0
            val loss = if (isWhiteMove) max(0.0, winPercents[i - 1] - winPercents[i]) else max(0.0, winPercents[i] - winPercents[i - 1])
            val raw = 103.1668100711649 * kotlin.math.exp(-0.04354415386753951 * loss) - 3.166924740191411
            out += min(100.0, max(0.0, raw + 1.0))
        }
        return out
    }

    data class Acpl(val white: Int, val black: Int)

    fun calculateACPL(positions: List<com.example.chessanalysis.EngineClient.PositionDTO>): Acpl {
        var whiteCPL = 0
        var blackCPL = 0
        var whiteMoves = 0
        var blackMoves = 0
        for (i in 1 until positions.size) {
            val prev = positions[i - 1].lines.firstOrNull()
            val cur = positions[i].lines.firstOrNull()
            if (prev == null || cur == null) continue
            val prevCP = prev.cp ?: (prev.mate?.times(1000) ?: 0)
            val currCP = cur.cp ?: (cur.mate?.times(1000) ?: 0)
            val isWhiteMove = (i - 1) % 2 == 0
            if (isWhiteMove) {
                val loss = max(0, prevCP - currCP)
                whiteCPL += min(loss, 1000); whiteMoves++
            } else {
                val loss = max(0, currCP - prevCP)
                blackCPL += min(loss, 1000); blackMoves++
            }
        }
        return Acpl(
            white = if (whiteMoves > 0) (whiteCPL.toDouble() / whiteMoves).roundToInt() else 0,
            black = if (blackMoves > 0) (blackCPL.toDouble() / blackMoves).roundToInt() else 0
        )
    }
}
