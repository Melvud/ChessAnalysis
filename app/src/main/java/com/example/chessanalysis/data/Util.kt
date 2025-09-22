package com.example.chessanalysis.util

import com.example.chessanalysis.net.StockfishApiV2
import kotlin.math.*

object AnalyticsLog { const val TAG = "Analyzer" }

object AnalyticsMath {
    /** cp из ответа Stockfish v2; если mate != null — даем saturate к +/-1000 */
    fun cpFrom(e: StockfishApiV2.Eval): Int {
        val cp = e.cp ?: if (e.mate != null) {
            // направление: mate >0 в пользу белых, <0 — в пользу черных
            if (e.mate!! > 0) 1000 else -1000
        } else 0
        return cp.coerceIn(-1000, 1000)
    }

    /** Логистическая функция из Chesskit/Lichess для win% */
    fun winPercent(cpForMover: Int): Double {
        // В Chesskit используется коэффициент ≈0.00368208
        val x = cpForMover.coerceIn(-1000, 1000).toDouble()
        val p = 1.0 / (1.0 + exp(-0.00368208 * x))
        return p * 100.0
    }

    /** Очки точности для одного хода по drop (в процентах), по формуле Lichess (Chesskit). */
    private fun accuracyForDrop(drop: Double): Double {
        // accuracy_move = 103.1668 * e^( -0.043544 * drop ) - 3.1669 + 1
        val v = 103.1668 * exp(-0.043544 * drop) - 3.1669 + 1.0
        return v.coerceIn(0.0, 100.0)
    }

    /** Итоговая точность игрока: среднее по ходам (можно включить взвешивание окном — при необходимости). */
    fun accuracyPercent(drops: List<Double>): Double {
        if (drops.isEmpty()) return 0.0
        val perMove = drops.map { accuracyForDrop(it) }
        // Простое среднее (если захотите — добавим harmonic + weights)
        return perMove.average()
    }

    /** Оценка перформанса по ACPL. Если известен реальный рейтинг — лёгкая корректировка Chesskit. */
    fun estimateEloFromAcpl(acpl: Double, realElo: Int?): Int? {
        // базовая связь Lichess/Chesskit:
        val base = (3100.0 * exp(-0.01 * acpl)).roundToInt()
        if (realElo == null) return base
        // поправка Chesskit: избыток/недостаток ACPL относительно ожидаемого — ~0.5% от разницы (упрощённо)
        val expectedFromReal = (3100.0 * exp(-0.01 * expectedAcplForElo(realElo))).roundToInt()
        val diff = base - expectedFromReal
        val adjusted = (realElo + diff * 0.5).roundToInt()
        return adjusted.coerceIn(600, 3300)
    }

    private fun expectedAcplForElo(elo: Int): Double {
        // инвертируем формулу по минимуму: acpl ≈ -ln(elo/3100)/0.01
        val ratio = (elo.toDouble() / 3100.0).coerceIn(0.1, 1.0)
        return -ln(ratio) / 0.01
    }
}
object FenTools {
    fun sideToMove(fen: String): Char {
        // ...разберите поле 2 FEN ("w"/"b")
        val parts = fen.split(" ")
        return parts.getOrNull(1)?.firstOrNull() ?: 'w'
    }

    fun applyUciSafe(fen: String, uci: String): String? {
        // TODO: заменить на вашу реальную реализацию хода по FEN.
        // Временно вернём тот же fen, чтобы не падать.
        return try { fen /* заменить на реальный next FEN */ } catch (_: Throwable) { null }
    }

    /** Простейшая оценка материала в центопешках (без позиционных факторов). */
    fun materialCount(fen: String): Int {
        val pieceValues = mapOf(
            'P' to 100, 'N' to 300, 'B' to 300, 'R' to 500, 'Q' to 900,
            'p' to -100, 'n' to -300, 'b' to -300, 'r' to -500, 'q' to -900
        )
        val board = fen.substringBefore(' ')
        var sum = 0
        for (ch in board) {
            sum += pieceValues[ch] ?: 0
        }
        return sum
    }
}
