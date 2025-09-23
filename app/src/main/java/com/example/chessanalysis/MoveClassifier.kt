package com.example.chessanalysis

import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.delay
import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.Square
import com.github.bhlangonijr.chesslib.move.Move
import com.github.bhlangonijr.chesslib.move.MoveGenerator
import com.github.bhlangonijr.chesslib.Piece
import com.github.bhlangonijr.chesslib.Side

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

    /** Онли-мув (вынужденный): если все альтернативы ведут к большой потере (Chesskit-подход). */
    private fun isForced(loss: Int, altDiff: Int?): Boolean {
        if (altDiff == null) return false
        // лучший ход почти без потерь, а следующая альтернатива сильно хуже
        return loss < 30 && altDiff > 250
    }

    /** Блестящий (splendid/brilliant): жертва/тактика при малой потере и большой разнице с альтернативами. */
    private fun isSplendid(loss: Int, altDiff: Int?, after: LineEval): Boolean {
        val pv = after.pv
        val sac = pv.take(6).any { it.contains("x") } // грубый индикатор тактики/жертвы
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
     * positions[i] — позиция ДО хода i, positions[i+1] — ПОСЛЕ.
     *
     * altDiffs — (опционально) разница «лучшая − вторая лучшая» в cP ДО хода.
     * Если null — поведение как раньше (консервативно).
     */
    fun classifyAll(
        moves: List<PgnChess.MoveItem>,
        positions: List<PositionEval>,
        winPercents: List<Double>,
        accPerMove: List<Double>,
        openingFens: Set<String> = emptySet(),
        altDiffs: List<Int?>? = null
    ): List<MoveReport> {
        val out = mutableListOf<MoveReport>()

        fun altAt(i: Int): Int? = altDiffs?.getOrNull(i)

        for (i in moves.indices) {
            val isWhite = i % 2 == 0
            val before = positions[i].lines.first()
            val after = positions[i + 1].lines.first()
            val loss = lossCp(before, after, isWhite)
            val alt = altAt(i)

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

    // -------------------- Дополнительно: расчёт altDiff «как в Chesskit» --------------------

    /**
     * Посчитать altDiff (best − secondBest) в cP ДО каждого хода.
     * Делаем ограниченное число вызовов движка на глубине depth для альтернативных ходов,
     * чтобы не упереться в лимиты.
     */
    suspend fun computeAltDiffs(
        moves: List<PgnChess.MoveItem>,
        positions: List<PositionEval>,
        depth: Int = 10,
        maxCandidates: Int = 4,
        lossThresholdCp: Int = 30,
        throttleMs: Long = 80L
    ): List<Int?> {
        if (moves.isEmpty()) return emptyList()
        val result = MutableList<Int?>(moves.size) { null }

        for (i in moves.indices) {
            val isWhite = i % 2 == 0
            val before = positions[i].lines.first()
            val after = positions[i + 1].lines.first()
            val loss = lossCp(before, after, isWhite)

            if (loss >= lossThresholdCp) continue

            val fenBefore = moves[i].beforeFen
            val bestUci = before.best

            val b = Board().apply { loadFromFen(fenBefore) }
            val legal = MoveGenerator.generateLegalMoves(b).map { it.toUci() }

            val alternatives = legal.filter { it != null && it != bestUci }.map { it!! }
            if (alternatives.isEmpty()) continue

            var bestCp = Int.MIN_VALUE
            var secondCp = Int.MIN_VALUE

            val bestCpFromRoot = before.cp
            if (bestCpFromRoot != null) {
                // оценка для стороны на ходу:
                bestCp = if (isWhite) bestCpFromRoot else -bestCpFromRoot
            }

            val takeN = min(maxCandidates, alternatives.size)
            for (altUci in alternatives.take(takeN)) {
                val altFen = try {
                    val bb = Board().apply { loadFromFen(fenBefore) }
                    bb.doMove(uciToMove(bb, altUci))
                    bb.fen
                } catch (_: Exception) {
                    continue
                }

                val e = EngineClient.analyzeFen(altFen, depth = depth)
                val altCp = when {
                    e.evaluation != null -> (e.evaluation * 100).toInt()
                    e.mate != null -> if (e.mate > 0) 100000 else -100000
                    else -> null
                } ?: continue

                val scoreForRootSide =
                    if (b.sideToMove == Side.WHITE) altCp else -altCp

                if (scoreForRootSide > bestCp) {
                    secondCp = bestCp
                    bestCp = scoreForRootSide
                } else if (scoreForRootSide > secondCp) {
                    secondCp = scoreForRootSide
                }

                delay(throttleMs)
            }

            if (bestCp != Int.MIN_VALUE && secondCp != Int.MIN_VALUE) {
                result[i] = (bestCp - secondCp).coerceAtLeast(0)
            }
        }

        return result
    }

    // --- Вспомогательное: UCI конверсия для chesslib ---

    private fun Move.toUci(): String? {
        val from = this.from?.value() ?: return null
        val to = this.to?.value() ?: return null
        val promo = when (this.promotion) {
            Piece.WHITE_QUEEN, Piece.BLACK_QUEEN -> "q"
            Piece.WHITE_ROOK, Piece.BLACK_ROOK -> "r"
            Piece.WHITE_BISHOP, Piece.BLACK_BISHOP -> "b"
            Piece.WHITE_KNIGHT, Piece.BLACK_KNIGHT -> "n"
            else -> ""
        }
        return from.lowercase() + to.lowercase() + promo
    }

    private fun uciToMove(board: Board, uci: String): Move {
        val from = Square.valueOf(uci.substring(0, 2).uppercase())
        val to = Square.valueOf(uci.substring(2, 4).uppercase())
        val promo = if (uci.length > 4) when (uci[4]) {
            'q' -> if (board.sideToMove == Side.WHITE) Piece.WHITE_QUEEN else Piece.BLACK_QUEEN
            'r' -> if (board.sideToMove == Side.WHITE) Piece.WHITE_ROOK else Piece.BLACK_ROOK
            'b' -> if (board.sideToMove == Side.WHITE) Piece.WHITE_BISHOP else Piece.BLACK_BISHOP
            'n' -> if (board.sideToMove == Side.WHITE) Piece.WHITE_KNIGHT else Piece.BLACK_KNIGHT
            else -> null
        } else null
        return Move(from, to, promo)
    }
}
