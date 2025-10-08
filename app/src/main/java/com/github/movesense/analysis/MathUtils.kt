package com.github.movesense.analysis

import kotlin.math.pow
import kotlin.math.sqrt

object MathUtils {

    fun ceilsNumber(number: Double, min: Double, max: Double): Double {
        return when {
            number > max -> max
            number < min -> min
            else -> number
        }
    }

    fun getHarmonicMean(array: List<Double>): Double {
        if (array.isEmpty()) return 0.0
        val sum = array.sumOf { 1.0 / it }
        return array.size / sum
    }

    fun getStandardDeviation(array: List<Double>): Double {
        if (array.isEmpty()) return 0.0
        val n = array.size
        val mean = array.sum() / n
        return sqrt(array.sumOf { (it - mean).pow(2) } / n)
    }

    fun getWeightedMean(array: List<Double>, weights: List<Double>): Double {
        if (array.size > weights.size) {
            throw IllegalArgumentException("Weights array is too short")
        }

        val weightedSum = array.mapIndexed { index, value ->
            value * weights[index]
        }.sum()

        val weightSum = weights.take(array.size).sum()

        return weightedSum / weightSum
    }
}