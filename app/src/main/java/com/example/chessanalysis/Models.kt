package com.example.chessanalysis

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

// Провайдер игр
@Serializable
enum class Provider {
    LICHESS,
    CHESSCOM
}

// Классы ходов - синхронизированы с сервером
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

// Заголовок партии
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
    val blackElo: Int? = null
)

// Линия оценки
@Serializable
data class LineEval(
    val pv: List<String> = emptyList(),
    val cp: Int? = null,
    val mate: Int? = null,
    val best: String? = null
)

// Оценка позиции
@Serializable
data class PositionEval(
    val fen: String,
    val idx: Int,
    val lines: List<LineEval>
)

// Отчёт по одному ходу
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

// Точность по цветам
@Serializable
data class AccByColor(
    val itera: Double,
    val harmonic: Double,
    val weighted: Double
)

// Сводка точности
@Serializable
data class AccuracySummary(
    val whiteMovesAcc: AccByColor,
    val blackMovesAcc: AccByColor
)

// ACPL
@Serializable
data class Acpl(
    val white: Int,
    val black: Int
)

// Оценка перфоманса по ACPL/рейтингу
@Serializable
data class EstimatedElo(
    val whiteEst: Int? = null,
    val blackEst: Int? = null
)

// Полный отчёт, который отдаёт сервер и который рисует ReportScreen
@Serializable
data class FullReport(
    val header: GameHeader,
    val positions: List<PositionEval>,
    val moves: List<MoveReport>,
    val accuracy: AccuracySummary,
    val acpl: Acpl,
    val estimatedElo: EstimatedElo,
    val analysisLog: List<String> = emptyList()
)