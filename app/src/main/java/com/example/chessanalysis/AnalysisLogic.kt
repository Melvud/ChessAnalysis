package com.example.chessanalysis

import android.util.Log
import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.move.MoveGenerator
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.pow
/** Простой логгер для debug-вывода. */
object AnalysisLogger {
    private val lines = mutableListOf<String>()
    fun add(msg: String) { lines += msg; Log.d("ChessAnalysis", msg) }
    fun dump(): List<String> = lines.toList()
    fun clear() = lines.clear()
}

/** Перевод оценки в Win% (как в chesskit). */
object WinPercent {
    // логистическая калибровка: 0 cp -> 50%, 100 cp -> ~57%, 300 cp -> ~70%, 500 cp -> ~78%, 1000 cp -> ~94%
    private const val K = 0.004

    fun fromCp(cp: Int?): Double {
        if (cp == null) return 50.0
        return 50.0 + 50.0 * (2.0 / (1.0 + exp(-K * cp)) - 1.0)
    }

    /** Для mate in N делаем близко к 0/100. Чем меньше N, тем ближе к краям. */
    private fun fromMate(m: Int): Double {
        val n = abs(m).coerceAtLeast(1)
        val edge = 100.0 - (5.0 / n.toDouble())  // 99.999..95
        return if (m > 0) edge else 100.0 - edge
    }

    fun fromEval(line: LineEval): Double = when {
        line.mate != null -> fromMate(line.mate!!)
        else -> fromCp(line.cp)
    }
}

/** Получаем Win% для каждой позиции (по первой PV). */
fun winsFromPositions(positions: List<PositionEval>): List<Double> =
    positions.map { WinPercent.fromEval(it.lines.first()) }

/** Accuracy по уменьшению Win% для стороны, которая ходила. */
private fun perMoveAccuracy(winBefore: Double, winAfter: Double, isWhiteMove: Boolean): Double {
    val loss = if (isWhiteMove) max(0.0, winBefore - winAfter) else max(0.0, winAfter - winBefore)
    val acc = 100.0 - 2.5 * loss.pow(0.65) // «чуть агрессивнее» штраф больших просадок
    return acc.coerceIn(0.0, 100.0)
}

private fun harmonic(xs: List<Double>): Double {
    val filtered = xs.filter { it > 0.0 }
    if (filtered.isEmpty()) return 0.0
    val sum = filtered.sumOf { 1.0 / it }
    return filtered.size / sum
}

private fun weighted(xs: List<Double>): Double {
    if (xs.isEmpty()) return 0.0
    var s = 0.0
    var w = 0.0
    xs.forEachIndexed { i, v ->
        val ww = 0.6 + 0.4 * (i / xs.lastIndex.toDouble().coerceAtLeast(1.0)) // конец партии важнее
        s += v * ww
        w += ww
    }
    return if (w == 0.0) 0.0 else s / w
}

private fun round1(x: Double) = (x * 10).toInt() / 10.0

/** ACPL — средняя потеря в cp относительно изменения eval до/после хода. */
private fun acplByColor(positions: List<PositionEval>): Pair<Int, Int> {
    var whiteLoss = 0.0
    var whiteCnt = 0
    var blackLoss = 0.0
    var blackCnt = 0
    fun toCp(e: LineEval): Int = e.cp ?: when {
        e.mate == null -> 0
        e.mate!! > 0 -> 1000
        else -> -1000
    }
    for (i in 0 until positions.size - 1) {
        val before = toCp(positions[i].lines.first())
        val after = toCp(positions[i + 1].lines.first())
        val isWhiteMove = i % 2 == 0
        val loss = if (isWhiteMove) max(0, before - after) else max(0, after - before)
        if (isWhiteMove) { whiteLoss += loss; whiteCnt++ } else { blackLoss += loss; blackCnt++ }
    }
    val w = if (whiteCnt == 0) 0 else (whiteLoss / whiteCnt).toInt()
    val b = if (blackCnt == 0) 0 else (blackLoss / blackCnt).toInt()
    return w to b
}

/** Грубая оценка перфоманса по ACPL (калибровано на типичных партиях). */
private fun baseEloFromAcpl(acpl: Int): Int {
    val a = acpl.coerceAtLeast(1)
    // плавная эмпирическая кривая: сильные играют с малым ACPL
    val elo = (2900 - 1200 * kotlin.math.ln(1 + a / 5.0)).toInt()
    return elo.coerceIn(600, 3000)
}

/** Перенос оценки mate/draw без запроса к движку (важно для последней позиции). */
private fun terminalEvalForFen(fen: String): LineEval? {
    return try {
        val b = Board()
        b.loadFromFen(fen)
        val legal = MoveGenerator.generateLegalMoves(b)
        if (legal.isEmpty()) {
            val check = b.isKingAttacked()
            return if (check) {
                // мат стороне, которая должна ходить
                if (b.sideToMove.name == "WHITE") {
                    LineEval(cp = -100000, mate = -1, pv = emptyList(), best = null)
                } else {
                    LineEval(cp = 100000, mate = +1, pv = emptyList(), best = null)
                }
            } else {
                // пат
                LineEval(cp = 0, mate = null, pv = emptyList(), best = null)
            }
        }
        null
    } catch (_: Exception) {
        null
    }
}

/** Анализ последовательности позиций для PGN. */
suspend fun analyzeGameToPositions(moves: List<PgnChess.MoveItem>): List<PositionEval> {
    if (moves.isEmpty()) return emptyList()
    AnalysisLogger.clear()
    val evals = mutableListOf<PositionEval>()

    suspend fun evalFen(fen: String, idx: Int): PositionEval {
        // Сначала распознаём терминальные позиции (мат/пат) локально.
        terminalEvalForFen(fen)?.let { term ->
            val line = if (abs(term.cp ?: 0) >= 100000) {
                term.copy(cp = if ((term.cp ?: 0) > 0) 1000 else -1000)
            } else term
            return PositionEval(fen, idx, listOf(line))
        }

        val e = EngineClient.analyzeFen(fen, depth = 14)
        val line = LineEval(
            pv = (e.continuation?.split(" ") ?: emptyList()).filter { it.isNotBlank() },
            cp = e.evaluation?.let { (it * 100).toInt() }, // в cp для внутренней модели
            mate = e.mate,
            best = e.bestmove
        )
        AnalysisLogger.add("ENGINE idx=$idx success=${e.success} eval=${e.evaluation} mate=${e.mate} best=${line.best} pv=${line.pv.take(6)}")
        return PositionEval(fen, idx, listOf(line))
    }

    // по всем позициям
    for ((i, m) in moves.withIndex()) {
        evals += evalFen(m.beforeFen, i)
    }
    // последняя позиция после последнего хода
    val lastIdx = moves.size
    evals += evalFen(moves.last().afterFen, lastIdx)

    return evals
}

/** Собираем итоговый отчёт. openingFens — база известных теоретических FEN. */
suspend fun reportFromPgn(header: GameHeader, openingFens: Set<String> = emptySet()): FullReport {
    val moves = PgnChess.movesWithFens(header.pgn)
    val positions = analyzeGameToPositions(moves)

    val wins = winsFromPositions(positions)

    // точности по-ходно
    val perMove = mutableListOf<Double>()
    for (i in 0 until positions.size - 1) {
        perMove += perMoveAccuracy(wins[i], wins[i + 1], isWhiteMove = i % 2 == 0)
        AnalysisLogger.add(
            "ACC move#${i + 1} ${if (i % 2 == 0) "White" else "Black"}: winBefore=${round1(wins[i])} winAfter=${round1(wins[i + 1])} -> acc=${round1(perMove.last())}"
        )
    }

    val whiteAccList = perMove.filterIndexed { i, _ -> i % 2 == 0 }
    val blackAccList = perMove.filterIndexed { i, _ -> i % 2 == 1 }
    val acc = AccuracySummary(
        whiteMovesAcc = AccByColor(
            itera = whiteAccList,
            harmonic = round1(harmonic(whiteAccList)),
            weighted = round1(weighted(whiteAccList))
        ),
        blackMovesAcc = AccByColor(
            itera = blackAccList,
            harmonic = round1(harmonic(blackAccList)),
            weighted = round1(weighted(blackAccList))
        )
    )
    AnalysisLogger.add("ACC white weighted=${acc.whiteMovesAcc.weighted} harmonic=${acc.whiteMovesAcc.harmonic}")
    AnalysisLogger.add("ACC black weighted=${acc.blackMovesAcc.weighted} harmonic=${acc.blackMovesAcc.harmonic}")

    // acpl
    val (wAcpl, bAcpl) = acplByColor(positions)
    AnalysisLogger.add("ACPL white=${wAcpl} black=${bAcpl}")

    val perf = EstimatedElo(
        whiteEst = header.whiteElo?.let { baseEloFromAcpl(wAcpl) - 33 + (it - 1200) / 10 },
        blackEst = header.blackElo?.let { baseEloFromAcpl(bAcpl) - 33 + (it - 1200) / 10 },
    )

    // Классификация ходов
    val moveReports = MoveClassifier.classifyAll(
        moves = moves,
        positions = positions,
        winPercents = wins,
        accPerMove = perMove,
        openingFens = openingFens
    )

    return FullReport(
        header = header,
        positions = positions,
        moves = moveReports,
        accuracy = acc,
        acpl = Acpl(wAcpl, bAcpl),
        estimatedElo = perf,
        analysisLog = AnalysisLogger.dump()
    )
}
