package com.example.chessanalysis.analysis

import com.example.chessanalysis.EngineClient
import kotlin.math.min
import kotlin.math.round

data class AcplResult(
    val white: Int,
    val black: Int
)

object ACPL {

    fun calculateACPL(positions: List<EngineClient.PositionDTO>): AcplResult {
        var whiteCPL = 0.0
        var blackCPL = 0.0
        var whiteMoves = 0
        var blackMoves = 0

        for (i in 1 until positions.size) {
            val prevPos = positions[i - 1]
            val currPos = positions[i]

            val prevEval = prevPos.lines.firstOrNull() ?: continue
            val currEval = currPos.lines.firstOrNull() ?: continue

            val prevCP = prevEval.cp ?: (prevEval.mate?.let { it * 1000 } ?: 0)
            val currCP = currEval.cp ?: (currEval.mate?.let { it * 1000 } ?: 0)

            val isWhiteMove = (i - 1) % 2 == 0

            if (isWhiteMove) {
                val loss = kotlin.math.max(0, prevCP - currCP)
                whiteCPL += min(loss.toDouble(), 1000.0)
                whiteMoves++
            } else {
                val loss = kotlin.math.max(0, currCP - prevCP)
                blackCPL += min(loss.toDouble(), 1000.0)
                blackMoves++
            }
        }

        return AcplResult(
            white = if (whiteMoves > 0) round(whiteCPL / whiteMoves).toInt() else 0,
            black = if (blackMoves > 0) round(blackCPL / blackMoves).toInt() else 0
        )
    }
}