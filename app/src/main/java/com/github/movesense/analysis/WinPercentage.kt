package com.github.movesense.analysis

import com.github.movesense.LineEval
import com.github.movesense.PositionEval
import kotlin.math.exp

object WinPercentage {

    fun getPositionWinPercentage(position: PositionEval): Double {
        return getLineWinPercentage(position.lines.firstOrNull() ?: return 50.0)
    }

    fun getLineWinPercentage(line: LineEval): Double {
        line.mate?.let { return getWinPercentageFromMate(it) }
        line.cp?.let { return getWinPercentageFromCp(it) }
        return 50.0
    }

    private fun getWinPercentageFromMate(mate: Int): Double {
        return if (mate >= 0) 100.0 else 0.0
    }

    private fun getWinPercentageFromCp(cp: Int): Double {
        val cpClamped = cp.coerceIn(-1000, 1000)
        val multiplier = -0.00368208
        val winChances = 2.0 / (1.0 + exp(multiplier * cpClamped)) - 1.0
        return 50.0 + 50.0 * winChances
    }
}