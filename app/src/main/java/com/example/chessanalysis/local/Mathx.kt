package com.example.chessanalysis.local

import kotlin.math.*

object Mathx {
    fun ceilsNumber(v: Double, lo: Double, hi: Double): Double = max(lo, min(hi, v))
    fun stddev(window: List<Double>): Double {
        if (window.isEmpty()) return 0.0
        val m = window.average()
        val s2 = window.map { (it - m) * (it - m) }.average()
        return sqrt(s2)
    }
    fun weightedMean(values: List<Double>, weights: List<Double>): Double {
        if (values.isEmpty() || weights.isEmpty()) return 0.0
        val n = min(values.size, weights.size)
        var sw = 0.0; var swv = 0.0
        for (i in 0 until n) { sw += weights[i]; swv += weights[i] * values[i] }
        return if (sw == 0.0) 0.0 else swv / sw
    }
    fun harmonicMean(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        var sum = 0.0
        for (v in values) sum += 1.0 / max(1e-9, v)
        return values.size / sum
    }
}
