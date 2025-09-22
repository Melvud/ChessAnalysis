package com.example.chessanalysis.data.util

import com.example.chessanalysis.data.model.MoveAnalysis
import com.example.chessanalysis.data.model.MoveClass
import com.example.chessanalysis.data.model.classifyByLossWinPct

/** Подсчёт точности и сводной статистики. */
object MoveClassifier {

    data class Accuracy(
        val total: Double,
        val white: Double,
        val black: Double
    )

    fun classify(m: MoveAnalysis): MoveAnalysis {
        val cls = classifyByLossWinPct(m.lossWinPct)
        return m.copy(moveClass = cls)
    }

    fun accuracy(moves: List<MoveAnalysis>): Accuracy {
        fun avg(xs: List<Double>) = if (xs.isEmpty()) 0.0 else xs.sum() / xs.size
        // Точность = 100 - средняя потеря (обрезаем 0..100)
        val total = 100.0 - avg(moves.map { it.lossWinPct }).coerceIn(0.0, 100.0)
        val whiteMoves = moves.filter { it.ply % 2 == 1 }
        val blackMoves = moves.filter { it.ply % 2 == 0 }
        val white = 100.0 - avg(whiteMoves.map { it.lossWinPct }).coerceIn(0.0, 100.0)
        val black = 100.0 - avg(blackMoves.map { it.lossWinPct }).coerceIn(0.0, 100.0)
        return Accuracy(total, white, black)
    }
}
