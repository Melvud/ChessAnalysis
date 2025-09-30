package com.example.chessanalysis.local

import com.example.chessanalysis.MoveClass
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Классификация по дельте оценки (в пешках) между лучшим и сыгранным.
 * Пороговые значения синхронизированы с серверной логикой (типичные):
 *   0.00  → BEST / PERFECT
 *   ≤0.20 → EXCELLENT
 *   ≤0.50 → OKAY
 *   ≤1.00 → INACCURACY
 *   ≤2.00 → MISTAKE
 *   >2.00 → BLUNDER
 * Если мат — отдаём FORCED/BLUNDER в зависимости от знака.
 */
object MoveClassifier {

    data class EvalPair(
        val best: Float?,          // лучшая оценка движка (cp в пешках)
        val played: Float?,        // оценка позиции после сыгранного
        val bestIsMate: Int? = null,   // мат в N (положит. = мат за белых, отриц. за чёрных)
        val playedIsMate: Int? = null
    )

    fun classify(pair: EvalPair): MoveClass {
        // матовые ветки
        if (pair.playedIsMate != null) {
            // если мат в твою пользу — PERFECT/ FORCED, иначе BLUNDER
            return if (pair.playedIsMate > 0) MoveClass.FORCED else MoveClass.BLUNDER
        }
        if (pair.bestIsMate != null) {
            // упустил мат — BLUNDER
            return MoveClass.BLUNDER
        }

        val best = pair.best ?: return MoveClass.OKAY
        val played = pair.played ?: return MoveClass.OKAY

        val delta = abs(best - played)

        return when {
            delta == 0f -> MoveClass.BEST
            delta <= 0.20f -> MoveClass.EXCELLENT
            delta <= 0.50f -> MoveClass.OKAY
            delta <= 1.00f -> MoveClass.INACCURACY
            delta <= 2.00f -> MoveClass.MISTAKE
            else -> MoveClass.BLUNDER
        }
    }
}
