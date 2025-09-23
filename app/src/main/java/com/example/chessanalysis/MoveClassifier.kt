package com.example.chessanalysis

import kotlin.math.max

object MoveClassifier {

    private fun toCp(le: LineEval): Int = le.cp ?: when {
        le.mate == null -> 0
        le.mate > 0 -> 1000
        else -> -1000
    }

    private fun lossCp(before: LineEval, after: LineEval, whiteMove: Boolean): Int {
        val b = toCp(before)
        val a = toCp(after)
        return if (whiteMove) max(0, b - a) else max(0, a - b)
    }

    /** Онли-мув (вынужденный): если все альтернативы ведут к огромной потере. */
    private fun isForced(loss: Int, altDiff: Int?): Boolean {
        if (altDiff == null) return false
        return loss < 30 && altDiff > 250 // только один безопасный вариант
    }

    /** Блестящий: жертва фигуры / серьёзная кратковременная потеря в cp, но так требует движок. */
    private fun isSplendid(loss: Int, altDiff: Int?, after: LineEval): Boolean {
        val pv = after.pv
        val sac = pv.take(6).any { it.contains("x") } // есть взятие в начале варианта
        return (sac && loss in 80..200) || (altDiff != null && altDiff > 300 && loss < 60)
    }

    private fun isPerfect(loss: Int) = loss <= 5
    private fun isBest(loss: Int) = loss <= 15
    private fun isExcellent(loss: Int) = loss <= 35
    private fun isOkay(loss: Int) = loss <= 60
    private fun isInaccuracy(loss: Int) = loss <= 120
    private fun isMistake(loss: Int) = loss <= 250
    private fun isBlunder(loss: Int) = loss > 250

    /**
     * Классифицирует список ходов.
     * @param moves   список ходов с FEN до/после.
     * @param positions  список оценённых позиций (та же длина + 1).
     * @param winPercents win% для позиций.
     * @param accPerMove точность по-ходно (для информации; можно пустой).
     * @param openingFens база теоретических позиций.
     */
    fun classifyAll(
        moves: List<PgnChess.MoveItem>,
        positions: List<PositionEval>,
        winPercents: List<Double>,
        accPerMove: List<Double>,
        openingFens: Set<String> = emptySet()
    ): List<MoveReport> {
        val out = mutableListOf<MoveReport>()

        fun idxToAltDiff(@Suppress("UNUSED_PARAMETER") idx: Int, @Suppress("UNUSED_PARAMETER") whiteMove: Boolean): Int? {
            // Сейчас у движка берём одну PV. Если понадобится — можно расширить EngineClient до multiPv=2
            // и здесь вычислять разницу «лучший vs второй лучший». Пока используем консервативную эвристику.
            return 200
        }

        for (i in moves.indices) {
            val isWhite = i % 2 == 0
            val before = positions[i].lines.first()
            val after = positions[i + 1].lines.first()
            val loss = lossCp(before, after, isWhite)
            val alt = idxToAltDiff(i, isWhite)

            val klass: MoveClass
            val tags = mutableListOf<String>()

            val isOpening = openingFens.contains(moves[i].beforeFen)
            if (isOpening) {
                klass = MoveClass.OPENING
                tags += "book"
            } else if (isForced(loss, alt)) {
                klass = MoveClass.FORCED
                tags += "forced"
            } else if (isSplendid(loss, alt, after)) {
                klass = MoveClass.SPLENDID
                tags += "sacrifice"
            } else when {
                isPerfect(loss) -> klass = MoveClass.PERFECT
                isBest(loss) -> klass = MoveClass.BEST
                isExcellent(loss) -> klass = MoveClass.EXCELLENT
                isOkay(loss) -> klass = MoveClass.OKAY
                isInaccuracy(loss) -> klass = MoveClass.INACCURACY
                isMistake(loss) -> klass = MoveClass.MISTAKE
                else -> klass = MoveClass.BLUNDER
            }

            val acc = if (i < accPerMove.size) accPerMove[i] else 100.0
            out += MoveReport(
                san = moves[i].san,
                uci = moves[i].uci,
                beforeFen = moves[i].beforeFen,
                afterFen = moves[i].afterFen,
                winBefore = winPercents[i],
                winAfter = winPercents[i + 1],
                accuracy = acc,
                classification = klass,
                tags = tags
            )
        }
        return out
    }
}
