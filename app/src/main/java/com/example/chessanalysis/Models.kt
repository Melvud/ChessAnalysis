package com.example.chessanalysis

import kotlinx.serialization.Serializable

enum class Provider { LICHESS, CHESSCOM }

/** Ответ от движка/облака. */
@Serializable
data class StockfishResponse(
    val success: Boolean,
    val evaluation: Double? = null,   // оценка в пешках (с точки зрения белых)
    val mate: Int? = null,            // +N / -N — мат в N ходов (положительное — за белых)
    val bestmove: String? = null,     // лучший ход, в UCI
    val continuation: String? = null, // pv/продолжение через пробел
    val error: String? = null
)

/** Краткая шапка партии. */
data class GameHeader(
    val site: Provider,
    val white: String? = null,
    val black: String? = null,
    val result: String? = null,
    val date: String? = null,
    val eco: String? = null,
    val opening: String? = null,
    val pgn: String,
    val whiteElo: Int? = null,
    val blackElo: Int? = null,
)

/** Оценка одной линии в позиции. */
data class LineEval(
    val pv: List<String> = emptyList(),
    val cp: Int? = null,     // centipawns relative to White
    val mate: Int? = null,   // +/- mate in N (sign is from White point of view)
    val best: String? = null // best move suggested by engine, UCI
)

/** Оценка позиции. */
data class PositionEval(
    val fen: String,
    val idx: Int,                 // 0 = старт перед 1-м ходом, 1 = после 1-го хода и т.д.
    val lines: List<LineEval>
)

/** Классы ходов (иконки лежат в drawable). */
enum class MoveClass {
    OPENING,     // теоретический
    FORCED,      // вынужденный
    BEST,        // лучший
    PERFECT,     // «лучший+» (нулевая/микро-потеря)
    SPLENDID,    // блестящий
    EXCELLENT,   // отличный
    OKAY,        // хороший
    INACCURACY,  // неточность
    MISTAKE,     // ошибка
    BLUNDER      // зевок
}

/** Отчёт по конкретному ходу. */
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

/** Пер-цветная детализация точности. */
data class AccByColor(
    val itera: List<Double>, // по-ходно
    val harmonic: Double,    // гармоническое среднее
    val weighted: Double     // взвешенное среднее
)

/** Сводная точность. */
data class AccuracySummary(
    val whiteMovesAcc: AccByColor,
    val blackMovesAcc: AccByColor
)

/** Средняя потеря в центропешках. */
data class Acpl(
    val white: Int,
    val black: Int
)

/** Оценка перфоманса по сравнению с известным рейтингом. */
data class EstimatedElo(
    val whiteEst: Int?,
    val blackEst: Int?
)

/** Полный отчёт для экрана. */
data class FullReport(
    val header: GameHeader,
    val positions: List<PositionEval>,
    val moves: List<MoveReport>,
    val accuracy: AccuracySummary,
    val acpl: Acpl,
    val estimatedElo: EstimatedElo,
    val analysisLog: List<String> = emptyList()
)
