package com.example.chessanalysis

import kotlin.math.*

object MathEx {
    fun clamp(x: Double, lo: Double, hi: Double) = max(lo, min(hi, x))
    fun harmonicMean(xs: List<Double>): Double {
        val s = xs.sumOf { 1.0 / max(it, 1e-9) }
        return if (s == 0.0) 0.0 else xs.size / s
    }
    fun std(xs: List<Double>): Double {
        if (xs.isEmpty()) return 0.0
        val m = xs.average()
        val v = xs.sumOf { (it - m).pow(2) } / xs.size
        return sqrt(v)
    }
    fun weightedMean(values: List<Double>, weights: List<Double>): Double {
        val sw = weights.sum().takeIf { it != 0.0 } ?: return 0.0
        return values.indices.sumOf { values[it] * weights[it] } / sw
    }
}

/** Win% из cp (как в Lichess/Chesskit), с клипом cp в ±1000. */
object WinPercent {
    fun fromCp(cp: Int?): Double {
        val c = MathEx.clamp((cp ?: 0).toDouble(), -1000.0, 1000.0)
        val inner = 2.0 / (1.0 + exp(-0.00368208 * c)) - 1.0
        return 50.0 + 50.0 * inner
    }
    fun fromEval(line: LineEval): Double = when {
        line.mate != null -> if (line.mate!! > 0) 99.9 else 0.1
        else -> fromCp(line.cp)
    }
}

/** Вспомогательное: получаем Win% для каждой позиции (pos[i]). */
fun winsFromPositions(positions: List<PositionEval>): List<Double> =
    positions.map { WinPercent.fromEval(it.lines.first()) }

/** Пер-ход Accuracy из падения Win% для стороны, которая ходила. */
private fun perMoveAccuracy(winBefore: Double, winAfter: Double, isWhiteMove: Boolean): Double {
    val winDiff = if (isWhiteMove) max(0.0, winBefore - winAfter) else max(0.0, winAfter - winBefore)
    val raw = 103.1668100711649 * exp(-0.04354415386753951 * winDiff) - 3.166924740191411
    return MathEx.clamp(raw + 1.0, 0.0, 100.0)
}

/** Веса ходов — std(win%) в скользящем окне размера 2..8. */
private fun volatilityWeights(wins: List<Double>, nMoves: Int): List<Double> {
    val k = MathEx.clamp(ceil(nMoves / 12.0), 2.0, 8.0).toInt()
    val w = MutableList(nMoves) { 0.0 }
    for (i in 0..(wins.size - k)) {
        val slice = wins.subList(i, i + k)
        val vol = MathEx.clamp(MathEx.std(slice), 0.5, 12.0)
        for (j in 0 until k) {
            val idx = i + j
            if (idx < w.size) w[idx] = max(w[idx], vol)
        }
    }
    val fallback = w.filter { it > 0 }.let { if (it.isEmpty()) 1.0 else it.average() }
    return w.map { if (it == 0.0) fallback else it }
}

object AccuracyCalc {
    /** Возвращает списки Acc для белых и чёрных и их итоговые значения. */
    fun compute(positions: List<PositionEval>): AccuracySummary {
        if (positions.size < 2) return AccuracySummary(emptyList(), emptyList(), 0.0, 0.0)
        val wins = winsFromPositions(positions)
        val nMoves = positions.size - 1
        val accAll = (0 until nMoves).map { i ->
            val isWhite = i % 2 == 0
            perMoveAccuracy(wins[i], wins[i + 1], isWhite)
        }
        val whiteMoves = accAll.filterIndexed { i, _ -> i % 2 == 0 }
        val blackMoves = accAll.filterIndexed { i, _ -> i % 2 == 1 }
        val weights = volatilityWeights(wins, nMoves)
        val wWhite = weights.filterIndexed { i, _ -> i % 2 == 0 }
        val wBlack = weights.filterIndexed { i, _ -> i % 2 == 1 }
        val wMeanW = MathEx.weightedMean(whiteMoves, wWhite)
        val wMeanB = MathEx.weightedMean(blackMoves, wBlack)
        val hMeanW = MathEx.harmonicMean(whiteMoves)
        val hMeanB = MathEx.harmonicMean(blackMoves)
        return AccuracySummary(
            whiteMovesAcc = whiteMoves, blackMovesAcc = blackMoves,
            whiteAcc = (wMeanW + hMeanW) / 2.0,
            blackAcc = (wMeanB + hMeanB) / 2.0
        )
    }
}

/** ACPL: учитываем ухудшения для стороны, которая ходила; клип cp в ±1000; мат → ±1000. */
object AcplCalc {
    private fun cpOf(line: LineEval): Int {
        val mate = line.mate
        val cp = line.cp
        return when {
            mate != null -> if (mate > 0) 1000 else -1000
            cp != null -> cp.coerceIn(-1000, 1000)
            else -> 0
        }
    }

    fun compute(positions: List<PositionEval>): Acpl {
        if (positions.size < 2) return Acpl(0.0, 0.0)
        val cp = positions.map { cpOf(it.lines.first()) }
        var wSum = 0.0; var wN = 0
        var bSum = 0.0; var bN = 0
        for (i in 1 until cp.size) {
            val before = cp[i - 1]; val after = cp[i]
            val isWhite = (i - 1) % 2 == 0
            val loss = if (isWhite) max(0.0, (before - after).toDouble())
            else max(0.0, (after - before).toDouble())
            if (isWhite) { wSum += loss.coerceAtMost(1000.0); wN++ }
            else { bSum += loss.coerceAtMost(1000.0); bN++ }
        }
        return Acpl(
            whiteAcpl = if (wN == 0) 0.0 else wSum / wN,
            blackAcpl = if (bN == 0) 0.0 else bSum / bN
        )
    }
}

/** Оценка “перформанса” по ACPL (Chesskit-подход). */
object PerformanceElo {
    private fun eloFromAcpl(acpl: Double): Int =
        (3100.0 * exp(-0.01 * acpl)).roundToInt()

    // Ожидаемый ACPL для данного рейтинга — обратная функция
    private fun expectedAcplForRating(elo: Int): Double {
        //  elo = 3100 * e^(-0.01 * acpl)  =>  acpl = -ln(elo/3100)/0.01
        val ratio = (elo.toDouble() / 3100.0).coerceIn(1e-6, 0.999999)
        return (-ln(ratio)) / 0.01
    }

    fun estimate(acpl: Acpl, whiteRating: Int? = null, blackRating: Int? = null): EstimatedElo {
        fun adjust(acplSide: Double, rating: Int?): Int {
            val base = eloFromAcpl(acplSide)
            if (rating == null) return base
            val expected = expectedAcplForRating(rating)
            val diff = acplSide - expected // >0: сыграл хуже ожидания
            return if (diff > 0) (rating * exp(-0.005 * diff)).roundToInt()
            else (rating / exp(-0.005 * -diff)).roundToInt()
        }
        return EstimatedElo(
            whiteEst = adjust(acpl.whiteAcpl, whiteRating),
            blackEst = adjust(acpl.blackAcpl, blackRating)
        )
    }
}

/** Анализ партии: pos[0] — eval стартовой позиции, затем — после каждого полухода. */
// ...
suspend fun analyzeGameToPositions(moves: List<PgnChess.MoveItem>): List<PositionEval> {
    if (moves.isEmpty()) return emptyList()
    val evals = mutableListOf<PositionEval>()
    // позиция перед 1-м ходом
    run {
        val e = EngineClient.analyzeFen(moves.first().beforeFen)
        val line = LineEval(
            pv = (e.continuation?.split(" ") ?: emptyList()).filter { it.isNotBlank() },
            cp = e.evaluation?.let { (it * 100).toInt() }, // в центопешки
            mate = e.mate,
            best = e.bestmove
        )
        evals += PositionEval(moves.first().beforeFen, 0, listOf(line))
    }
    // после каждого хода
    var idx = 0
    for (m in moves) {
        idx += 1
        val e = EngineClient.analyzeFen(m.afterFen)
        val line = LineEval(
            pv = (e.continuation?.split(" ") ?: emptyList()).filter { it.isNotBlank() },
            cp = e.evaluation?.let { (it * 100).toInt() },
            mate = e.mate,
            best = e.bestmove
        )
        evals += PositionEval(m.afterFen, idx, listOf(line))
    }
    return evals
}
// ...
