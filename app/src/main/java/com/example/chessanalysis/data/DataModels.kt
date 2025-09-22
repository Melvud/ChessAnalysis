package com.example.chessanalysis.data.model

import java.io.Serializable

/** Источник партии. */
enum class ChessSite : Serializable { LICHESS, CHESS_COM }

/** Короткая информация о партии. */
data class GameSummary(
    val id: String,
    val site: ChessSite,
    val white: String,
    val black: String,
    val result: String?,
    val startTime: Long?,
    val endTime: Long?,
    val timeControl: String?,
    val pgn: String
) : Serializable

/** Класс ошибки хода. */
enum class MoveClass : Serializable {
    GREAT, GOOD, INACCURACY, MISTAKE, BLUNDER
}

/** Анализ одного хода. */
data class MoveAnalysis(
    val moveNumber: Int,
    val san: String,
    val bestMove: String?,
    val evaluation: Double,
    val delta: Double,
    val classification: MoveClass
) : Serializable

/** Сводка анализа партии. */
data class AnalysisSummary(
    val totalMoves: Int,
    val counts: Map<MoveClass, Int>,
    val accuracy: Double,
    val accuracyWhite: Double,
    val accuracyBlack: Double,
    val perfWhite: Int?,
    val perfBlack: Int?
) : Serializable

/** Результат анализа. */
data class AnalysisResult(
    val summary: AnalysisSummary,
    val moves: List<MoveAnalysis>
) : Serializable
