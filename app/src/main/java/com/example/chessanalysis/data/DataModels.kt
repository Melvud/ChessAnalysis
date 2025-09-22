package com.example.chessanalysis.data.model

data class GameSummary(
    val id: String,
    val white: String,
    val black: String,
    val whiteElo: Int?,
    val blackElo: Int?,
    val result: String?,
    val timeControl: String?,
    val pgnMoves: List<String>,
    val startFen: String = "rn.../pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1" // замените на ваши данные
)

data class ParsedGame(
    val startFen: String,
    val moves: List<Ply>, // каждый полуход
    val whiteElo: Int?,
    val blackElo: Int?,
    val opening: OpeningMatch?
) {
    companion object {
        fun fromSummary(s: GameSummary): ParsedGame {
            return ParsedGame(
                startFen = "startpos_fen_or_from_pgn", // вставьте вашу реализацию
                moves = s.pgnMoves.mapIndexed { i, san -> Ply(moveNumber = (i+2)/2, san = san, uci = "" /* uci из вашего парсера */) },
                whiteElo = s.whiteElo,
                blackElo = s.blackElo,
                opening = null
            )
        }
    }
}

data class Ply(
    val moveNumber: Int,
    val san: String,
    val uci: String
)

enum class MoveClass {
    OPENING, BEST, SPLENDID, PERFECT, EXCELLENT, OKAY, INACCURACY, MISTAKE, BLUNDER, GOOD, GREAT
}

data class MoveAnalysis(
    val moveNumber: Int,
    val uci: String,
    val san: String,
    val bestMove: String?,
    val scoreCpBefore: Int,
    val scoreCpAfter: Int,
    val winBefore: Double,
    val winAfter: Double,
    val classification: MoveClass
)

data class OpeningMatch(val eco: String, val name: String, val plyRange: IntRange)

data class SummaryBlock(
    val acplWhite: Double,
    val acplBlack: Double,
    val accuracyWhite: Double,
    val accuracyBlack: Double,
    val perfWhite: Int?,
    val perfBlack: Int?,
    val opening: OpeningMatch?,
    val counts: Map<MoveClass, Int>
)

data class AnalysisResult(
    val moves: List<MoveAnalysis>,
    val summary: SummaryBlock
)
