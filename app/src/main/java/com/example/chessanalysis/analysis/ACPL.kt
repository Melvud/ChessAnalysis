package com.example.chessanalysis.analysis

import com.example.chessanalysis.EngineClient
import com.example.chessanalysis.PositionEval
import kotlin.math.min
import kotlin.math.round
import kotlin.math.max

data class AcplResult(
    val white: Int,
    val black: Int
)

object ACPL {

    /**
     * ❗Старый метод: считает ACPL по "сырым" позициям движка (оценка из точки зрения стороны, КОТОРАЯ ходит).
     * Оставлен для обратной совместимости, но для корректности используйте calculateACPLFromPositionEvals().
     */
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
                val loss = max(0, prevCP - currCP)
                whiteCPL += min(loss.toDouble(), 1000.0)
                whiteMoves++
            } else {
                val loss = max(0, currCP - prevCP)
                blackCPL += min(loss.toDouble(), 1000.0)
                blackMoves++
            }
        }

        return AcplResult(
            white = if (whiteMoves > 0) round(whiteCPL / whiteMoves).toInt() else 0,
            black = if (blackMoves > 0) round(blackCPL / blackMoves).toInt() else 0
        )
    }

    /**
     * ✅ Новый корректный метод: считает ACPL по нормализованным PositionEval,
     * где ВСЕ оценки уже приведены к точке зрения белых (cp>0 — лучше белым; mate>0 — мат в пользу белых).
     * Это устраняет инверсию знаков после хода чёрных и неверное засчитывание мата.
     */
    fun calculateACPLFromPositionEvals(positions: List<PositionEval>): AcplResult {
        if (positions.size < 2) return AcplResult(white = 0, black = 0)

        var whiteCPL = 0.0
        var blackCPL = 0.0
        var whiteMoves = 0
        var blackMoves = 0

        for (i in 1 until positions.size) {
            val prev = positions[i - 1].lines.firstOrNull()
            val curr = positions[i].lines.firstOrNull()
            if (prev == null || curr == null) continue

            // Нормализованные значения (cp или mate*1000), уже из точки зрения белых
            val prevCpNorm = prev.cp ?: (prev.mate?.let { it * 1000 } ?: 0)
            val currCpNorm = curr.cp ?: (curr.mate?.let { it * 1000 } ?: 0)

            val isWhiteMove = (i - 1) % 2 == 0
            if (isWhiteMove) {
                val loss = max(0, prevCpNorm - currCpNorm) // ухудшилось для белых -> потеря белых
                whiteCPL += min(loss.toDouble(), 1000.0)
                whiteMoves++
            } else {
                val loss = max(0, currCpNorm - prevCpNorm) // улучшилось для белых -> ухудшилось для чёрных
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
