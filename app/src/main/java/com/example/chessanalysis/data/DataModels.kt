package com.example.chessanalysis.data.model

import java.io.Serializable

enum class ChessSite : Serializable { LICHESS, CHESS_COM }

/** Короткое описание партии */
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

enum class MoveClass : Serializable {
    GREAT, GOOD, INACCURACY, MISTAKE, BLUNDER
}

/** Анализ одного полухода */
data class MoveAnalysis(
    val moveNumber: Int,
    val san: String,
    val bestMove: String,
    val evaluation: Double,
    val delta: Double,
    val classification: MoveClass
) : Serializable

data class AnalysisSummary(
    val totalMoves: Int,
    val counts: Map<MoveClass, Int>,
    val accuracy: Double
) : Serializable

data class AnalysisResult(
    val summary: AnalysisSummary,
    val moves: List<MoveAnalysis>
) : Serializable
