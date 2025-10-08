package com.github.movesense.analysis

import com.github.movesense.LineEval
import com.github.movesense.PositionEval
import kotlin.math.exp

object WinPercentage {

    fun getPositionWinPercentage(position: PositionEval): Double {
        return getLineWinPercentage(position.lines.firstOrNull() ?: return 50.0)
    }

    fun getLineWinPercentage(line: LineEval): Double {
        // Мат имеет приоритет — он однозначно определяет исход
        line.mate?.let { return getWinPercentageFromMate(it) }
        line.cp?.let { return getWinPercentageFromCp(it) }
        return 50.0
    }

    private fun getWinPercentageFromMate(mate: Int): Double {
        // Нормализовано к белым: mate>0 — выигрыш белых, <0 — чёрных
        return if (mate > 0) 100.0 else 0.0
    }

    // Логистическая аппроксимация (ограничиваем по модулю до 1000cp)
    private fun getWinPercentageFromCp(cp: Int): Double {
        val cpCeiled = MathUtils.ceilsNumber(cp.toDouble(), -1000.0, 1000.0)
        val MULT = -0.00368208
        val winChances = 2.0 / (1.0 + exp(MULT * cpCeiled)) - 1.0
        return 50.0 + 50.0 * winChances
    }
}
