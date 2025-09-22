package com.example.chessanalysis.engine

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

object MathEval {

    /** CP → Win% (lichess logistic) */
    fun winPercentFromCp(cp: Int): Double {
        return 50.0 + 50.0 * (2.0 / (1.0 + exp(-0.00368208 * cp)) - 1.0)
    }

    /**
     * Accuracy of a single move (lichess-like):
     * if winAfter >= winBefore → 100,
     * else a*exp(-k*diff)+b + 1 (uncertainty bonus).
     */
    fun moveAccuracy(winBefore: Double, winAfter: Double): Double {
        if (winAfter >= winBefore) return 100.0
        val diff = winBefore - winAfter
        val a = 103.1668100711649
        val k = 0.04354415386753951
        val b = -3.166924740191411
        val raw = a * exp(-k * diff) + b + 1.0
        return min(100.0, max(0.0, raw))
    }

    /**
     * Game accuracy per color (lichess-like): sliding windows stddev weights,
     * weighted mean + harmonic mean / 2.
     *
     * @param cps evaluations (centipawns) for positions BEFORE each move (length == number of plies + 1)
     * @param startToMove 'w' or 'b'
     */
    fun gameAccuracy(cps: List<Int>, startToMove: Char = 'w'): Pair<Double?, Double?> {
        if (cps.size < 2) return null to null

        val wins = cps.map { winPercentFromCp(it) }
        val used = cps.dropLast(1) // for each move we compare i -> i+1
        val windowSize = (used.size / 10).coerceIn(2, 8)

        // Build windows as in lichess approach
        val windows = mutableListOf<List<Double>>()
        val first = wins.take(windowSize)
        val requiredPrepend = (used.size - (wins.size - windowSize)).coerceAtLeast(0)
        repeat(requiredPrepend) { windows += first }
        for (i in 0..wins.size - windowSize) {
            windows += wins.slice(i until i + windowSize)
        }

        // weights = stddev in [0.5, 12]
        val weights = windows.map { vals ->
            val mean = vals.average()
            val variance = vals.map { (it - mean) * (it - mean) }.average()
            val std = sqrt(variance)
            std.coerceIn(0.5, 12.0)
        }

        data class M(val acc: Double, val w: Double, val color: String)
        val moves = mutableListOf<M>()
        for (i in used.indices) {
            val acc = moveAccuracy(wins[i], wins[i + 1])
            val weight = weights[i]
            val color = if (startToMove == 'w') {
                if (i % 2 == 0) "white" else "black"
            } else {
                if (i % 2 == 0) "black" else "white"
            }
            moves += M(acc, weight, color)
        }

        fun combine(forColor: String): Double? {
            val subset = moves.filter { it.color == forColor }
            if (subset.isEmpty()) return null
            val wSum = subset.sumOf { it.w }
            val weightedMean = subset.sumOf { it.acc * it.w } / wSum
            val harmonic = subset.size / subset.sumOf { if (it.acc > 0.0) 1.0 / it.acc else 0.0 }
            return (weightedMean + harmonic) / 2.0
        }

        return combine("white") to combine("black")
    }

    /** One-game performance (linear): dp = 800*p - 400; perf = opp + dp */
    fun performance(score: Double, opponentRating: Int): Int {
        val dp = 800.0 * score - 400.0
        return (opponentRating + dp).toInt()
    }

    /** Accuracy → Elo (polynomial approx; clamp 0..3500) */
    fun ratingFromAccuracy(accuracy: Double): Int {
        val a = 2.05
        val b = 12.9
        val c = -0.256
        val d = 0.00401
        val acc = accuracy.coerceIn(0.0, 100.0)
        val r = a + b * acc + c * acc * acc + d * acc.pow(3.0)
        return r.coerceIn(0.0, 3500.0).toInt()
    }
}
