package com.example.chessanalysis.local

import kotlin.math.E
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

object WinPercentage {
    fun getPositionWinPercentage(cp: Int?, mate: Int?): Int {
        return getLineWinPercentage(cp, mate)
    }

    fun getLineWinPercentage(cp: Int?, mate: Int?): Int {
        if (cp != null) return getWinPercentageFromCp(cp)
        if (mate != null) return if (mate > 0) 100 else 0
        error("No cp or mate in line")
    }

    private fun getWinPercentageFromCp(cp: Int): Int {
        val cpCeiled = Mathx.ceilsNumber(cp.toDouble(), -1000.0, 1000.0)
        val MULT = -0.00368208
        val winChances = 2 / (1 + exp(MULT * cpCeiled)) - 1
        return (50 + 50 * winChances).toInt()
    }
}
