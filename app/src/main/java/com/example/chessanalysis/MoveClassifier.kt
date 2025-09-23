package com.example.chessanalysis

import android.util.Log
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.delay
import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.Square
import com.github.bhlangonijr.chesslib.move.Move
import com.github.bhlangonijr.chesslib.move.MoveGenerator
import com.github.bhlangonijr.chesslib.Piece
import com.github.bhlangonijr.chesslib.Side

private const val TAG = "AnalysisDebug"

private fun dbg(sink: MutableList<String>?, msg: String) {
    Log.d(TAG, msg)
    sink?.add(msg)
}

object MoveClassifier {

    // Конвертация в сентипешки
    private fun toCp(le: LineEval): Int = le.cp ?: when {
        le.mate == null -> 0
        le.mate > 0 -> 1000
        else -> -1000
    }

    // Потеря в сентипешках
    private fun lossCp(before: LineEval, after: LineEval, whiteMove: Boolean): Int {
        val b = toCp(before)
        val a = toCp(after)
        return if (whiteMove) max(0, b - a) else max(0, a - b)
    }

    // Классификация хода по Lichess/ChessKit стандартам
    private fun classifyMove(
        lossWin: Double,
        lossCp: Int,
        isForced: Boolean,
        isOpening: Boolean,
        altDiffCp: Int?
    ): MoveClass {
        // Специальные случаи
        if (isOpening) return MoveClass.OPENING
        if (isForced) return MoveClass.FORCED

        // Блестящий ход (sacrifice с выигрышем или сложный тактический ход)
        if (altDiffCp != null && altDiffCp > 200 && lossCp == 0 && lossWin < 1.0) {
            return MoveClass.SPLENDID
        }

        // Классификация по потере Win% (стандарты Lichess)
        return when {
            lossWin < 0.5 && lossCp == 0 -> MoveClass.PERFECT
            lossWin < 1.0 && lossCp <= 20 -> MoveClass.BEST
            lossWin < 2.0 -> MoveClass.EXCELLENT
            lossWin < 5.0 -> MoveClass.OKAY
            lossWin < 10.0 -> MoveClass.INACCURACY
            lossWin < 20.0 -> MoveClass.MISTAKE
            else -> MoveClass.BLUNDER
        }
    }

    // Проверка вынужденного хода
    private fun isForced(fen: String): Boolean {
        val board = Board()
        board.loadFromFen(fen)
        val legalMoves = MoveGenerator.generateLegalMoves(board)
        return legalMoves.size <= 1
    }

    // Основная классификация всех ходов
    fun classifyAll(
        moves: List<PgnChess.MoveItem>,
        positions: List<PositionEval>,
        winPercents: List<Double>,
        accPerMove: List<Double>,
        openingFens: Set<String> = emptySet(),
        altDiffs: List<Int?> = emptyList(),
        sink: MutableList<String>? = null
    ): List<MoveReport> {

        val n = min(moves.size, min(positions.size - 1, winPercents.size - 1))
        val out = ArrayList<MoveReport>(n)

        dbg(sink, "== Classify moves ==")

        for (i in 0 until n) {
            val isWhite = positions[i].fen.contains(" w ")
            val winBefore = winPercents[i]
            val winAfterSame = 100.0 - winPercents[i + 1]
            val lossWin = max(0.0, winBefore - winAfterSame)

            val before = positions[i].lines.firstOrNull()
            val after = positions[i + 1].lines.firstOrNull()
            val lossCp = lossCp(before ?: LineEval(), after ?: LineEval(), isWhite)

            val alt = altDiffs.getOrNull(i)
            val isOpening = openingFens.contains(moves[i].beforeFen)
            val isForced = isForced(moves[i].beforeFen)

            // Теги
            val tags = mutableListOf<String>()
            if (isOpening) tags += "OPENING"
            if (isForced) tags += "FORCED"
            if (lossCp == 0) tags += "PERFECT"
            if (alt != null && alt > 200) tags += "BRILLIANT"

            // Классификация
            val klass = classifyMove(lossWin, lossCp, isForced, isOpening, alt)

            val acc = if (i < accPerMove.size) accPerMove[i] else 100.0

            dbg(sink, "ply#%02d %s winLoss=%.2f cpLoss=%d altDiff=%s -> %s"
                .format(i + 1, if (isWhite) "W" else "B",
                    lossWin, lossCp, alt?.toString() ?: "-", klass.name))

            out += MoveReport(
                san = moves[i].san,
                uci = moves[i].uci,
                beforeFen = moves[i].beforeFen,
                afterFen = moves[i].afterFen,
                winBefore = winBefore,
                winAfter = winAfterSame,
                accuracy = acc,
                classification = klass,
                tags = tags
            )
        }

        return out
    }

    // Расчет altDiff для определения блестящих ходов
    suspend fun computeAltDiffs(
        moves: List<PgnChess.MoveItem>,
        positions: List<PositionEval>,
        depth: Int = 10,
        maxCandidates: Int = 4,
        lossThresholdCp: Int = 30,
        throttleMs: Long = 80L,
        sink: MutableList<String>? = null
    ): List<Int?> {
        if (moves.isEmpty()) return emptyList()

        val result = MutableList<Int?>(moves.size) { null }
        dbg(sink, "== Computing alternative diffs ==")

        for (i in moves.indices) {
            if (i >= positions.size - 1) break

            val isWhiteMove = positions[i].fen.contains(" w ")
            val before = positions[i].lines.firstOrNull()
            val after = positions[i + 1].lines.firstOrNull()

            if (before == null || after == null) continue

            val loss = lossCp(before, after, isWhiteMove)

            // Пропускаем если потеря слишком большая
            if (loss >= lossThresholdCp) {
                dbg(sink, "ply#%02d skip: loss=%d >= %d".format(i + 1, loss, lossThresholdCp))
                continue
            }

            val fenBefore = moves[i].beforeFen
            val b = Board()

            try {
                b.loadFromFen(fenBefore)
            } catch (e: Exception) {
                dbg(sink, "ply#%02d invalid FEN".format(i + 1))
                continue
            }

            val legal = MoveGenerator.generateLegalMoves(b).mapNotNull { it.toUci() }
            val bestUci = before.best ?: moves[i].uci
            val alternatives = legal.filter { it != bestUci }

            if (alternatives.isEmpty()) {
                dbg(sink, "ply#%02d no alternatives".format(i + 1))
                continue
            }

            var bestCp = Int.MIN_VALUE
            var secondCp = Int.MIN_VALUE

            // Оценка движка для лучшего хода
            before.cp?.let { rootCp ->
                bestCp = if (b.sideToMove == Side.WHITE) rootCp else -rootCp
            }

            val takeN = min(maxCandidates, alternatives.size)
            dbg(sink, "ply#%02d checking %d alternatives".format(i + 1, takeN))

            for (altUci in alternatives.take(takeN)) {
                val altFen = try {
                    val bb = Board()
                    bb.loadFromFen(fenBefore)
                    bb.doMove(uciToMove(bb, altUci))
                    bb.fen
                } catch (_: Exception) {
                    dbg(sink, "   alt=$altUci invalid move")
                    continue
                }

                val e = EngineClient.analyzeFen(altFen, depth = depth)
                val altCp = when {
                    e.evaluation != null -> (e.evaluation * 100).toInt()
                    e.mate != null -> if (e.mate > 0) 1000 else -1000
                    else -> null
                } ?: continue

                // Переводим в перспективу ходящего
                val scoreForMover = if (b.sideToMove == Side.WHITE) -altCp else altCp

                if (scoreForMover > bestCp) {
                    secondCp = bestCp
                    bestCp = scoreForMover
                } else if (scoreForMover > secondCp) {
                    secondCp = scoreForMover
                }

                dbg(sink, "   alt=$altUci -> score=$scoreForMover")
                delay(throttleMs)
            }

            if (bestCp != Int.MIN_VALUE && secondCp != Int.MIN_VALUE) {
                val diff = (bestCp - secondCp).coerceAtLeast(0)
                result[i] = diff
                dbg(sink, "ply#%02d altDiff=%d".format(i + 1, diff))
            }
        }

        return result
    }

    // UCI helpers
    private fun Move.toUci(): String? {
        val from = this.from?.value() ?: return null
        val to = this.to?.value() ?: return null
        val promo = when (this.promotion) {
            Piece.WHITE_QUEEN, Piece.BLACK_QUEEN -> "q"
            Piece.WHITE_ROOK, Piece.BLACK_ROOK -> "r"
            Piece.WHITE_BISHOP, Piece.BLACK_BISHOP -> "b"
            Piece.WHITE_KNIGHT, Piece.BLACK_KNIGHT -> "n"
            else -> null
        }
        return from.lowercase() + to.lowercase() + (promo ?: "")
    }

    private fun uciToMove(board: Board, uci: String): Move {
        val from = Square.fromValue(uci.substring(0, 2).uppercase())
        val to = Square.fromValue(uci.substring(2, 4).uppercase())
        val promo = if (uci.length >= 5) when (uci[4].lowercaseChar()) {
            'q' -> if (board.sideToMove == Side.WHITE) Piece.WHITE_QUEEN else Piece.BLACK_QUEEN
            'r' -> if (board.sideToMove == Side.WHITE) Piece.WHITE_ROOK else Piece.BLACK_ROOK
            'b' -> if (board.sideToMove == Side.WHITE) Piece.WHITE_BISHOP else Piece.BLACK_BISHOP
            'n' -> if (board.sideToMove == Side.WHITE) Piece.WHITE_KNIGHT else Piece.BLACK_KNIGHT
            else -> null
        } else null
        return Move(from, to, promo)
    }
}