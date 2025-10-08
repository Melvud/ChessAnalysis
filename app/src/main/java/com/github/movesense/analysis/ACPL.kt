package com.github.movesense.analysis

import com.github.movesense.PositionEval
import kotlin.math.min
import kotlin.math.round
import kotlin.math.max

data class AcplResult(
    val white: Int,
    val black: Int
)

object ACPL {

    /**
     * Корректный ACPL по нормализованным позициям (cp/mate — из перспективы белых).
     * positions[i] — позиция ПОСЛЕ i-го полухода, positions[0] — старт.
     */
    fun calculateACPLFromPositionEvals(positions: List<PositionEval>): AcplResult {
        if (positions.size < 2) return AcplResult(0, 0)

        var whiteCPL = 0.0
        var blackCPL = 0.0
        var whiteMoves = 0
        var blackMoves = 0

        for (i in 1 until positions.size) {
            val prev = positions[i - 1].lines.firstOrNull()
            val curr = positions[i].lines.firstOrNull()
            if (prev == null || curr == null) continue

            val prevCp = prev.cp ?: (prev.mate?.let { it * 1000 } ?: 0)
            val currCp = curr.cp ?: (curr.mate?.let { it * 1000 } ?: 0)

            val isWhiteMove = (i - 1) % 2 == 0
            if (isWhiteMove) {
                val loss = max(0, prevCp - currCp) // ухудшилось для белых -> потеря белых
                whiteCPL += min(loss.toDouble(), 1000.0)
                whiteMoves++
            } else {
                val loss = max(0, currCp - prevCp) // улучшилось для белых -> потеря чёрных
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
