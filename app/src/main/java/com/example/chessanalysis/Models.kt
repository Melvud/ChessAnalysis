package com.example.chessanalysis

import kotlinx.serialization.Serializable

enum class Provider { LICHESS, CHESSCOM }

@Serializable
data class StockfishResponse(
    val success: Boolean,
    val evaluation: Double? = null,
    val mate: Int? = null,
    val bestmove: String? = null,
    val continuation: String? = null,
    val error: String? = null
)

data class GameHeader(
    val site: Provider,
    val white: String?,
    val black: String?,
    val result: String?,
    val date: String?,
    val eco: String?,
    val opening: String?,
    val pgn: String,
    val whiteElo: Int? = null,
    val blackElo: Int? = null
)

data class LineEval(
    val pv: List<String>,
    val cp: Int? = null,
    val mate: Int? = null,
    val best: String? = null
)

data class PositionEval(
    val fen: String,
    val moveIndex: Int,
    val lines: List<LineEval>
)

enum class MoveClass {
    OPENING, FORCED, BEST, PERFECT, SPLENDID,
    EXCELLENT, OKAY, INACCURACY, MISTAKE, BLUNDER
}

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

data class AccuracySummary(
    val whiteMovesAcc: List<Double>,
    val blackMovesAcc: List<Double>,
    val whiteAcc: Double,
    val blackAcc: Double
)

data class Acpl(
    val whiteAcpl: Double,
    val blackAcpl: Double
)

data class EstimatedElo(
    val whiteEst: Int?,
    val blackEst: Int?
)

data class FullReport(
    val header: GameHeader,
    val positions: List<PositionEval>,
    val moves: List<MoveReport>,
    val accuracy: AccuracySummary,
    val acpl: Acpl,
    val estimatedElo: EstimatedElo
)
