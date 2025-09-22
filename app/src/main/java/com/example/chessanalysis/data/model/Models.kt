package com.example.chessanalysis.data.model

import java.io.Serializable
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
enum class ChessSite : Serializable { LICHESS, CHESS_COM }

/**
 * Короткое описание партии, передаётся между экранами и кладётся в SavedStateHandle.
 */
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

enum class MoveClass : Serializable { GREAT, GOOD, INACCURACY, MISTAKE, BLUNDER }

data class MoveAnalysis(
    val ply: Int,
    val san: String,
    val fenBefore: String,
    val fenAfter: String,
    val evalBeforePawns: Double,  // оценка позиции ДО хода (в пешках, "за белых")
    val evalAfterPawns: Double,   // оценка позиции ПОСЛЕ сделанного хода (за белых)
    val winBefore: Double,        // вероятность победы ходящей стороны ДО (0..100)
    val winAfter: Double,         // вероятность победы после хода (0..100)
    val lossWinPct: Double,       // потеря в п.п. для ходящей стороны
    val bestMove: String? = null,
    val moveClass: MoveClass = MoveClass.GREAT
) : Serializable

data class AnalysisSummary(
    val counts: Map<MoveClass, Int>,
    val accuracyTotal: Double,
    val accuracyWhite: Double,
    val accuracyBlack: Double,
    val whitePerfVs: Int?,
    val blackPerfVs: Int?
) : Serializable


data class AnalysisResult(
    val moves: List<MoveAnalysis>,
    val summary: AnalysisSummary
) : Serializable


/** Преобразование оценки (в пешках за белых) в win% для стороны, делающей ход. */
fun pawnsToWinPctForMover(evalWhitePov: Double, moverIsWhite: Boolean): Double {
    // Логистическая аппроксимация; центр 0.0 -> 50%
    // Коэффициент 0.73 хорошо «садится» под быстрые глубины 12..18
    val e = if (moverIsWhite) evalWhitePov else -evalWhitePov
    val p = 1.0 / (1.0 + exp(-0.73 * e))
    return p * 100.0
}

/** Классификация по величине потерянного преимущества (в п.п. win%). */
fun classifyByLossWinPct(lossWinPct: Double): MoveClass =
    when {
        lossWinPct < 1.0  -> MoveClass.GREAT
        lossWinPct < 3.0  -> MoveClass.GOOD
        lossWinPct < 8.0  -> MoveClass.INACCURACY
        lossWinPct < 15.0 -> MoveClass.MISTAKE
        else              -> MoveClass.BLUNDER
    }
