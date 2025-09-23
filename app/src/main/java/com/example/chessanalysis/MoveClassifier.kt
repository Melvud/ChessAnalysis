package com.example.chessanalysis

import kotlin.math.max
import kotlin.math.abs

object MoveClassifier {

    // Пороги Chesskit-стиля по потере Win%
    private const val EPS_EXCELLENT = 2.0
    private const val EPS_OKAY_MIN  = 2.0
    private const val EPS_OKAY_MAX  = 5.0
    private const val EPS_INACC_MIN = 5.0
    private const val EPS_INACC_MAX = 10.0
    private const val EPS_MIST_MIN  = 10.0
    private const val EPS_MIST_MAX  = 20.0

    private fun isPieceSacrifice(beforeCp: Int?, afterCp: Int?): Boolean {
        if (beforeCp == null || afterCp == null) return false
        // Жертва: падение собственной оценки ≥ ~150 cp (материалная отдача) — простая эвристика
        return (afterCp - beforeCp) <= -150
    }

    /** Классификация по Chesskit-логике, с передачей базы дебютов (множество FEN). */
    fun classifyAll(
        header: GameHeader,
        positions: List<PositionEval>,
        moves: List<PgnChess.MoveItem>,
        openingFens: Set<String>
    ): List<MoveReport> {

        val wins = winsFromPositions(positions)
        val acc = AccuracyCalc.compute(positions) // потребуем для acc accuracy
        val accAll = mutableListOf<Double>().apply {
            addAll(acc.whiteMovesAcc.zip(acc.blackMovesAcc) { w, b -> listOf(w,b) }.flatten()
                .let { if (it.isEmpty()) emptyList() else it }) // аккуратно, но проще возьмём ещё раз ниже
        }
        // Пересоберём плоский список точностей в порядке ходов:
        val flatAcc = mutableListOf<Double>()
        val wIt = acc.whiteMovesAcc.iterator()
        val bIt = acc.blackMovesAcc.iterator()
        for (i in moves.indices) flatAcc += if (i % 2 == 0) (if (wIt.hasNext()) wIt.next() else 100.0)
        else (if (bIt.hasNext()) bIt.next() else 100.0)

        val out = mutableListOf<MoveReport>()
        for (i in moves.indices) {
            val mi = moves[i]
            val winBefore = wins[i]
            val winAfter  = wins[i + 1]
            val isWhiteMove = i % 2 == 0
            val loss = if (isWhiteMove) max(0.0, winBefore - winAfter)
            else max(0.0, winAfter - winBefore)

            val before = positions[i].lines.first()
            val after  = positions[i + 1].lines.first()

            // Категория OPENING: если позиция после хода == любой FEN из базы дебютов (ранняя стадия)
            val isOpening = (i < 20) && openingFens.contains(mi.afterFen)

            // FORCED: если ровно один легальный ход
            val isForced = PgnChess.legalCount(mi.beforeFen) == 1

            // BEST: сделан ход == bestmove движка в позиции ДО хода
            val isBest = before.best != null && before.best.equals(mi.uci, ignoreCase = true)

            // SPLENDID (Brilliant): жертва фигуры, при этом loss ≤ 2% и позиция не уходит в очевидный “-+”
            val splendid = isPieceSacrifice(before.cp, after.cp) && loss <= 2.0 && winAfter >= 35.0

            // PERFECT (Great): потеря ≤ 2% и ход не простая ответная “recapture”
            val prevUci = if (i == 0) null else moves[i - 1].uci
            val perfect = !splendid && loss <= EPS_EXCELLENT && !PgnChess.isSimpleRecapture(prevUci, mi.uci)

            val cls = when {
                isOpening -> MoveClass.OPENING
                isForced  -> MoveClass.FORCED
                isBest    -> MoveClass.BEST
                splendid  -> MoveClass.SPLENDID
                perfect   -> MoveClass.PERFECT
                loss <= EPS_EXCELLENT -> MoveClass.EXCELLENT
                loss <= EPS_OKAY_MAX  -> MoveClass.OKAY
                loss <= EPS_INACC_MAX -> MoveClass.INACCURACY
                loss <= EPS_MIST_MAX  -> MoveClass.MISTAKE
                else -> MoveClass.BLUNDER
            }

            val tags = buildList {
                if (isOpening) add("Opening")
                if (isForced) add("Forced")
                if (isBest) add("Best")
                if (splendid) add("Brilliant")
                if (perfect) add("Great")
            }

            out += MoveReport(
                san = mi.san, uci = mi.uci,
                beforeFen = mi.beforeFen, afterFen = mi.afterFen,
                winBefore = winBefore, winAfter = winAfter,
                accuracy = flatAcc.getOrElse(i) { 100.0 },
                classification = cls, tags = tags
            )
        }
        return out
    }
}
