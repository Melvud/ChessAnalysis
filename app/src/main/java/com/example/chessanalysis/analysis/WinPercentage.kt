package com.example.chessanalysis.analysis

import com.example.chessanalysis.LineEval
import com.example.chessanalysis.PositionEval
import kotlin.math.exp

object WinPercentage {

    fun getPositionWinPercentage(position: PositionEval): Double {
        return getLineWinPercentage(position.lines.firstOrNull() ?: return 50.0)
    }

    fun getLineWinPercentage(line: LineEval): Double {
        return when {
            line.cp != null -> getWinPercentageFromCp(line.cp)
            line.mate != null -> getWinPercentageFromMate(line.mate)
            else -> throw IllegalStateException("No cp or mate in line")
        }
    }

    private fun getWinPercentageFromMate(mate: Int): Double {
        return if (mate > 0) 100.0 else 0.0
    }

    // Source: https://github.com/lichess-org/lila/blob/a320a93b68dabee862b8093b1b2acdfe132b9966/modules/analyse/src/main/WinPercent.scala#L27
    private fun getWinPercentageFromCp(cp: Int): Double {
        val cpCeiled = MathUtils.ceilsNumber(cp.toDouble(), -1000.0, 1000.0)
        val MULTIPLIER = -0.00368208 // Source: https://github.com/lichess-org/lila/pull/11148
        val winChances = 2.0 / (1.0 + exp(MULTIPLIER * cpCeiled)) - 1.0
        return 50.0 + 50.0 * winChances
    }
}