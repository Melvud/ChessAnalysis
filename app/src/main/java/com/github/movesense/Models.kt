package com.github.movesense

import android.annotation.SuppressLint
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

@Serializable
enum class Provider {
    LICHESS,
    CHESSCOM,
    BOT,
    MANUAL
}

@Serializable
enum class MoveClass {
    @SerialName("OPENING") OPENING,
    @SerialName("FORCED") FORCED,
    @SerialName("BEST") BEST,
    @SerialName("PERFECT") PERFECT,
    @SerialName("SPLENDID") SPLENDID,
    @SerialName("EXCELLENT") EXCELLENT,
    @SerialName("OKAY") OKAY,
    @SerialName("INACCURACY") INACCURACY,
    @SerialName("MISTAKE") MISTAKE,
    @SerialName("BLUNDER") BLUNDER
}

@SuppressLint("UnsafeOptInUsageError")
@Serializable
data class GameHeader(
    val site: Provider? = null,
    val white: String? = null,
    val black: String? = null,
    val result: String? = null,
    val date: String? = null,
    val eco: String? = null,
    val opening: String? = null,
    val pgn: String? = null,
    val whiteElo: Int? = null,
    val blackElo: Int? = null,
    val sideToView: Boolean? = null
)

@SuppressLint("UnsafeOptInUsageError")
@Serializable
data class LineEval(
    val pv: List<String> = emptyList(),
    val cp: Int? = null,
    val mate: Int? = null,
    val best: String? = null,
    val depth: Int? = null,
    val multiPv: Int? = null
)

@SuppressLint("UnsafeOptInUsageError")
@Serializable
data class PositionEval(
    val fen: String,
    val idx: Int,
    val lines: List<LineEval>,
    val bestMove: String? = null,
    val evaluation: Float? = null
)

@SuppressLint("UnsafeOptInUsageError")
@Serializable
data class MoveReport(
    val san: String,
    val uci: String,
    val beforeFen: String,
    val afterFen: String,
    val winBefore: Double,
    val winAfter: Double,
    val accuracy: Double,
    val classification: MoveClass,
    val tags: List<String> = emptyList()
)

@SuppressLint("UnsafeOptInUsageError")
@Serializable
data class AccByColor(
    val itera: Double,
    val harmonic: Double,
    val weighted: Double
)

@SuppressLint("UnsafeOptInUsageError")
@Serializable
data class AccuracySummary(
    val whiteMovesAcc: AccByColor,
    val blackMovesAcc: AccByColor
)

@SuppressLint("UnsafeOptInUsageError")
@Serializable
data class Acpl(
    val white: Int,
    val black: Int
)

@SuppressLint("UnsafeOptInUsageError")
@Serializable
data class EstimatedElo(
    val whiteEst: Int? = null,
    val blackEst: Int? = null
)

@SuppressLint("UnsafeOptInUsageError")
@Serializable
data class FullReport(
    val header: GameHeader,
    val positions1: List<EngineClient.PositionDTO>,
    val positions: List<PositionEval>,
    val moves: List<MoveReport>,
    val accuracy: AccuracySummary,
    val acpl: Acpl,
    val estimatedElo: EstimatedElo,
    val analysisLog: List<String> = emptyList(),
    val clockData: ClockData? = null  // ← ДОБАВИЛИ
)

@Serializable
data class ClockData(
    val white: List<Int> = emptyList(),  // Сантисекунды
    val black: List<Int> = emptyList()
)