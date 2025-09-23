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

    // ===== helpers for loss (cp) =====
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

    // ===== simple tags =====
    private fun isPerfect(lossCp: Int) = lossCp == 0
    private fun isBest(lossCp: Int) = lossCp in 1..30

    // ===== mixed classification (Win% loss dominates, cp refines; altDiff may tighten) =====
    private fun worsen(cls: MoveClass): MoveClass = when (cls) {
        MoveClass.EXCELLENT -> MoveClass.OKAY
        MoveClass.OKAY -> MoveClass.INACCURACY
        MoveClass.INACCURACY -> MoveClass.MISTAKE
        MoveClass.MISTAKE -> MoveClass.BLUNDER
        else -> cls
    }

    private fun classifyMixed(lossWin: Double, lossCp: Int, altDiffCp: Int?): MoveClass {
        var cls = when {
            lossWin <= 1.5 || (lossWin <= 3.0 && lossCp <= 30)   -> MoveClass.EXCELLENT
            lossWin <= 4.5 || (lossWin <= 7.0 && lossCp <= 60)   -> MoveClass.OKAY
            lossWin <= 9.0  || lossCp <= 100                     -> MoveClass.INACCURACY
            lossWin <= 13.5 || lossCp <= 200                     -> MoveClass.MISTAKE
            else                                                 -> MoveClass.BLUNDER
        }
        if (altDiffCp != null && altDiffCp >= 250 && cls < MoveClass.MISTAKE && lossWin > 3.0) {
            cls = worsen(cls)
        }
        return cls
    }

    // ===== main classification =====
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
            val winBefore = winPercents[i]                    // Win% текущего ходящего ДО хода
            val winAfterSame = 100.0 - winPercents[i + 1]     // Win% того же игрока ПОСЛЕ хода
            val lossWin = max(0.0, winBefore - winAfterSame)

            val before = positions[i].lines.firstOrNull()
            val after = positions[i + 1].lines.firstOrNull()
            val lossCp = lossCp(before ?: LineEval(), after ?: LineEval(), isWhite)

            val alt = altDiffs.getOrNull(i)

            // Opening / best/perfect tags
            val tags = mutableListOf<String>()
            if (openingFens.contains(moves[i].beforeFen)) tags += "OPENING"
            if (isPerfect(lossCp)) tags += "PERFECT" else if (isBest(lossCp)) tags += "BEST"

            // Mixed classification
            val klass = classifyMixed(lossWin, lossCp, alt)

            val acc = if (i < accPerMove.size) accPerMove[i] else 100.0

            dbg(sink, "ply#%02d %s  winBefore=%.2f winAfterSame=%.2f lossWin=%.2f lossCp=%d altDiff=%s -> %s"
                .format(
                    i + 1,
                    if (isWhite) "W" else "B",
                    winBefore, winAfterSame, lossWin, lossCp, alt?.toString() ?: "-",
                    klass.name
                )
            )

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

    // -------------------- Расчёт altDiff (best − secondBest) --------------------
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
        dbg(sink, "== Поиск альтернатив (altDiffs) depth=$depth, maxCandidates=$maxCandidates ==")

        for (i in moves.indices) {
            val isWhiteMove = positions[i].fen.contains(" w ")
            val before = positions[i].lines.firstOrNull()
            val after = positions[i + 1].lines.firstOrNull()
            val loss = lossCp(before ?: LineEval(), after ?: LineEval(), isWhiteMove)
            val fenBefore = moves[i].beforeFen

            // Пропускаем перебор альтернатив, если уже приличная потеря в cp
            if (loss >= lossThresholdCp) {
                dbg(sink, "ply#%02d alt skip: lossCp=%d ≥ %d".format(i + 1, loss, lossThresholdCp))
                continue
            }

            val b = Board().apply { loadFromFen(fenBefore) }
            val legal = MoveGenerator.generateLegalMoves(b).mapNotNull { it.toUci() }
            val bestUci = before?.best
            val alternatives = legal.filter { it != bestUci }
            if (alternatives.isEmpty()) {
                dbg(sink, "ply#%02d альтернатив нет (legal=%d)".format(i + 1, legal.size))
                continue
            }

            var bestCp = Int.MIN_VALUE
            var secondCp = Int.MIN_VALUE

            // Оценка «лучшего по движку из корня» (если есть)
            before?.cp?.let { rootCp ->
                bestCp = if (b.sideToMove == Side.WHITE) rootCp else -rootCp
            }

            val takeN = min(maxCandidates, alternatives.size)
            dbg(sink, "ply#%02d альтернативы: всего=%d, проверим=%d (кроме best=%s)"
                .format(i + 1, alternatives.size, takeN, bestUci ?: "-"))

            for (altUci in alternatives.take(takeN)) {
                val altFen = try {
                    val bb = Board().apply { loadFromFen(fenBefore) }
                    bb.doMove(uciToMove(bb, altUci))
                    bb.fen
                } catch (_: Exception) {
                    dbg(sink, "   alt=$altUci — недопустимый ход")
                    continue
                }

                val e = EngineClient.analyzeFen(altFen, depth = depth) // uses your HTTP client
                val altCp = when {
                    e.evaluation != null -> (e.evaluation * 100).toInt()
                    e.mate != null       -> if (e.mate > 0) 1000 else -1000
                    else -> null
                } ?: continue

                val scoreForRootSide = if (b.sideToMove == Side.WHITE) altCp else -altCp
                if (scoreForRootSide > bestCp) {
                    secondCp = bestCp
                    bestCp = scoreForRootSide
                } else if (scoreForRootSide > secondCp) {
                    secondCp = scoreForRootSide
                }
                dbg(sink, "   alt=$altUci -> altCp=$altCp (forRootSide=$scoreForRootSide)")
                delay(throttleMs)
            }

            if (bestCp != Int.MIN_VALUE && secondCp != Int.MIN_VALUE) {
                val diff = (bestCp - secondCp).coerceAtLeast(0)
                result[i] = diff
                dbg(sink, "ply#%02d altDiff=%d (best=%d, second=%d)".format(i + 1, diff, bestCp, secondCp))
            } else {
                dbg(sink, "ply#%02d altDiff не вычислен (bestCp=$bestCp, secondCp=$secondCp)".format(i + 1))
            }
        }
        return result
    }

    // --- UCI helpers ---
    private fun Move.toUci(): String? {
        val from = this.from?.value() ?: return null
        val to = this.to?.value() ?: return null
        val promo = when (this.promotion) {
            Piece.WHITE_QUEEN,  Piece.BLACK_QUEEN  -> "q"
            Piece.WHITE_ROOK,   Piece.BLACK_ROOK   -> "r"
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
            'r' -> if (board.sideToMove == Side.WHITE) Piece.WHITE_ROOK  else Piece.BLACK_ROOK
            'b' -> if (board.sideToMove == Side.WHITE) Piece.WHITE_BISHOP else Piece.BLACK_BISHOP
            'n' -> if (board.sideToMove == Side.WHITE) Piece.WHITE_KNIGHT else Piece.BLACK_KNIGHT
            else -> null
        } else null
        return Move(from, to, promo)
    }
}
