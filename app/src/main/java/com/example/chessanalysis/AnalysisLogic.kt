package com.example.chessanalysis

import android.util.Log
import kotlin.math.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlin.math.roundToInt
private const val TAG = "AnalysisDebug"
private const val DEBUG_ANALYSIS = true

/** Debug helper: logs to Logcat and also appends to the on-screen log (if provided). */
private fun dbg(sink: MutableList<String>?, msg: String) {
    if (DEBUG_ANALYSIS) Log.d(TAG, msg)
    sink?.add(msg)
}

/** Mathematical helpers + mappings kept close to lichess conventions. */
object LichessFormulas {

    // --- Win% from CP (relative to mover) ---
    // Logistic mapping parameters (close to lichess-like calibration)
    private const val K_CP = 0.00368208  // slope
    fun cpToWinPercent(cp: Double): Double {
        val p = 1.0 / (1.0 + exp(-K_CP * cp))
        return (p * 100.0).coerceIn(0.0, 100.0)
    }

    // --- Win% from mate (relative to mover) ---
    fun mateToWinPercent(mateForWhite: Int, isForMoverWhite: Boolean): Double {
        // Engine mate sign is relative to White. Re-sign for mover POV.
        val signed = if (isForMoverWhite) mateForWhite else -mateForWhite
        return if (signed > 0) 100.0 else 0.0
    }

    /** Single-move accuracy function from loss in Win% (lichess fit). */
    fun moveAccuracyFromLoss(loss: Double): Double {
        val nonNeg = max(0.0, loss)
        val raw = 103.1668100711649 * exp(-0.04354415386753951 * nonNeg) - 3.166924740191411
        return (raw + 1.0).coerceIn(0.0, 100.0)
    }

    /** Dynamic window for volatility based on plies. */
    fun windowSizeForPlies(plies: Int): Int = (plies / 8.0).roundToInt().coerceIn(4, 14)

    fun ceilToStep(value: Double, step: Double, min: Double, max: Double): Double {
        val v = ceil(value / step) * step
        return v.coerceIn(min, max)
    }

    /** Volatility weights per ply, computed on Win%(mover), then discretized to step=0.5 in [1..12]. */
    fun volatilityWeightsAll(
        winPercents: List<Double>,
        window: Int,
        sink: MutableList<String>? = null
    ): List<Double> {
        if (winPercents.size < 2) return emptyList()
        dbg(sink, "— Volatility weights: window=$window, points=${winPercents.size}")
        val out = MutableList(winPercents.size - 1) { 1.0 }
        val n = winPercents.size
        val w = window.coerceAtLeast(2)

        for (i in 1 until n) {
            // Early indices: enforce minimal weight = 1 to avoid overpull.
            if (i < w) {
                out[i - 1] = 1.0
                dbg(sink, "   σ[$i] early -> weight=1.0")
                continue
            }
            val start = max(0, i - w)
            val end = min(n - 1, i + w / 2)
            val seg = winPercents.subList(start, end + 1)
            val mean = seg.average()
            val varSum = seg.fold(0.0) { acc, v -> acc + (v - mean) * (v - mean) }
            val std = if (seg.size > 1) sqrt(varSum / (seg.size - 1)) else 0.0
            val weight = ceilToStep(std, 0.5, 1.0, 12.0)
            out[i - 1] = weight
            dbg(sink, "   σ[$i] for window [$start..$end] mean=%.2f std=%.3f -> weight=%.1f"
                .format(mean, std, weight))
        }
        return out
    }

    /** Harmonic mean on [0..100] values (ignoring zeros). */
    fun harmonic(xs: List<Double>): Double {
        val filtered = xs.filter { it > 0.0 }
        if (filtered.isEmpty()) return 0.0
        val inv = filtered.sumOf { 1.0 / it }
        return (filtered.size / inv).coerceIn(0.0, 100.0)
    }

    /** Weighted mean with clamp to [0..100]; if total weight is 0, fallback to simple average. */
    fun weightedMean(values: List<Double>, weights: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        var ws = 0.0
        var s = 0.0
        val n = min(values.size, weights.size)
        for (i in 0 until n) {
            val w = max(0.0, weights[i])
            ws += w
            s += values[i] * w
        }
        val mean = if (ws > 0) s / ws else values.average()
        return mean.coerceIn(0.0, 100.0)
    }

    // --- Performance (Estimated Elo) from ACPL: simple chesskit-like calibration ---
    fun eloFromAcpl(acpl: Double): Int = (3100.0 * exp(-0.01 * acpl)).roundToInt()
    fun expectedAcplFromElo(elo: Int): Double =
        (-100.0 * ln((elo.toDouble() / 3100.0).coerceIn(1e-9, 1.0))).coerceIn(0.0, 1000.0)

    /** Anchor performance to known rating; clip ACPL into a sane band first. */
    fun anchoredEloFromGameAcpl(gameAcpl: Double, rating: Int?, sink: MutableList<String>? = null): Int {
        val clipped = gameAcpl.coerceIn(4.0, 350.0)
        val base = eloFromAcpl(clipped)
        if (rating == null) {
            dbg(sink, "— Performance no-anchor: ACPL=%.1f (clip=%.1f) -> %d".format(gameAcpl, clipped, base))
            return base
        }
        val expAcpl = expectedAcplFromElo(rating)
        val diff = clipped - expAcpl
        val factor = exp(-0.005 * abs(diff))
        val adjustment = if (diff > 0) {
            rating - (rating - base) * (1 - factor)
        } else {
            rating + (base - rating) * (1 - factor)
        }
        val out = max(0, adjustment.roundToInt())
        dbg(sink, "— Performance anchored: rating=$rating, expACPL=%.1f, gameACPL=%.1f (clip=%.1f), base=%d, out=%d"
            .format(expAcpl, gameAcpl, clipped, base, out))
        return out
    }
}

private fun firstMateIndex(positions: List<PositionEval>): Int {
    val idx = positions.indexOfFirst { it.lines.firstOrNull()?.mate != null }
    return if (idx >= 0) idx else positions.size - 1
}

/** Win% for the mover (side to move) at each ply. */
private fun winPercentsForMover(positions: List<PositionEval>, sink: MutableList<String>?): List<Double> {
    val out = ArrayList<Double>(positions.size)
    dbg(sink, "== Win%% for mover ==")
    positions.forEachIndexed { i, pos ->
        val fen = pos.fen
        val moverWhite = fen.contains(" w ")
        val line = pos.lines.firstOrNull()
        val cp = line?.cp
        val mate = line?.mate
        val winForMover = when {
            cp != null -> {
                val cpForMover = if (moverWhite) cp.toDouble() else -cp.toDouble()
                LichessFormulas.cpToWinPercent(cpForMover)
            }
            mate != null -> LichessFormulas.mateToWinPercent(mate, moverWhite)
            else -> 50.0
        }
        dbg(sink, "pos#%02d [%s] mover=%s, cp=%s, mate=%s -> Win%%(mover)=%.2f"
            .format(i, fen, if (moverWhite) "W" else "B", cp?.toString() ?: "-", mate?.toString() ?: "-", winForMover))
        out += winForMover
    }
    return out
}

/** CPL per ply, capped to 500 to stabilize ACPL. */
private fun cplPerMove(positions: List<PositionEval>, sink: MutableList<String>?): List<Double> {
    if (positions.size < 2) return emptyList()
    val last = firstMateIndex(positions)
    val out = ArrayList<Double>(min(positions.size - 1, last))
    val CPL_CAP = 500.0
    dbg(sink, "== CPL per move ==")
    for (i in 0 until min(positions.size - 1, last)) {
        val isWhite = positions[i].fen.contains(" w ")
        val before = positions[i].lines.firstOrNull()
        val after = positions[i + 1].lines.firstOrNull()
        val bestCp = when {
            before?.cp != null -> before.cp!!.toDouble()
            before?.mate != null -> if (before.mate!! > 0) 1000.0 else -1000.0
            else -> null
        }
        val playedCp = when {
            after?.cp != null -> after.cp!!.toDouble()
            after?.mate != null -> if (after.mate!! > 0) 1000.0 else -1000.0
            else -> null
        }
        val cpl = if (bestCp != null && playedCp != null) {
            val deltaForMover = if (isWhite) (bestCp - playedCp) else (playedCp - bestCp)
            max(0.0, deltaForMover).coerceAtMost(CPL_CAP)
        } else 0.0
        dbg(sink, "ply#%02d mover=%s bestCp=%s playedCp=%s -> CPL=%.1f"
            .format(i + 1, if (isWhite) "W" else "B",
                bestCp?.let { "%.0f".format(it) } ?: "-", playedCp?.let { "%.0f".format(it) } ?: "-", cpl))
        out += cpl
    }
    return out
}

/** Per-move accuracy + weights + flags (isWhite). Cuts everything after first mate. */
private fun perMoveAccAndWeights(
    positions: List<PositionEval>,
    sink: MutableList<String>?
): Triple<List<Double>, List<Double>, List<Boolean>> {

    val cutIdx = firstMateIndex(positions)
    val allWin = winPercentsForMover(positions, sink)
    val winForMover = allWin.subList(0, min(allWin.size, cutIdx + 1))
    val plies = winForMover.size - 1
    if (plies <= 0) return Triple(emptyList(), emptyList(), emptyList())

    dbg(sink, "== Per-move accuracy (lichess) ==")
    val perMoveAcc = ArrayList<Double>(plies)
    val isWhiteFlags = ArrayList<Boolean>(plies)
    for (i in 0 until plies) {
        val isWhite = positions[i].fen.contains(" w ")
        val before = winForMover[i]                  // Win% для текущего ходящего
        val afterSame = 100.0 - winForMover[i + 1]   // Win% того же игрока ПОСЛЕ хода
        val loss = max(0.0, before - afterSame)      // одинаково для белых и чёрных
        val acc = LichessFormulas.moveAccuracyFromLoss(loss)
        dbg(sink, "ply#%02d mover=%s: WinBefore=%.2f WinAfterSame=%.2f loss=%.2f -> acc=%.2f"
            .format(i + 1, if (isWhite) "W" else "B", before, afterSame, loss, acc))
        perMoveAcc += acc
        isWhiteFlags += isWhite
        dbg(sink, "ply#%02d mover=%s: WinBefore=%.2f WinAfter=%.2f loss=%.2f -> acc=%.2f"
            .format(i + 1, if (isWhite) "W" else "B", before, afterSame, loss, acc))
    }

    val window = LichessFormulas.windowSizeForPlies(plies)
    val weightsAll = LichessFormulas.volatilityWeightsAll(winForMover, window, sink)
    return Triple(perMoveAcc, weightsAll, isWhiteFlags)
}

/** Summarize accuracy/ACPL/performance by color with volatility weights aligned to move indices. */
fun summarize(
    positions: List<PositionEval>,
    whiteRating: Int?,
    blackRating: Int?,
    sink: MutableList<String>? = null
): Triple<AccuracySummary, Acpl, EstimatedElo> {

    val (perMoveAccAll, weightsAll, isWhiteFlags) = perMoveAccAndWeights(positions, sink)

    // Split by color (align indices for weights)
    val whiteIdx = isWhiteFlags.indices.filter { isWhiteFlags[it] }
    val blackIdx = isWhiteFlags.indices.filter { !isWhiteFlags[it] }
    val whiteAcc = whiteIdx.map { perMoveAccAll[it] }
    val blackAcc = blackIdx.map { perMoveAccAll[it] }
    val whiteW = whiteIdx.map { weightsAll[it] }
    val blackW = blackIdx.map { weightsAll[it] }

    val whiteWeighted = LichessFormulas.weightedMean(whiteAcc, whiteW)
    val blackWeighted = LichessFormulas.weightedMean(blackAcc, blackW)
    val whiteHarm = LichessFormulas.harmonic(whiteAcc)
    val blackHarm = LichessFormulas.harmonic(blackAcc)

    dbg(sink, "White itera=[${whiteAcc.joinToString { "%.2f".format(it) }}]")
    dbg(sink, "Black itera=[${blackAcc.joinToString { "%.2f".format(it) }}]")
    dbg(sink, "White weighted=%.2f, harmonic=%.2f".format(whiteWeighted, whiteHarm))
    dbg(sink, "Black weighted=%.2f, harmonic=%.2f".format(blackWeighted, blackHarm))

    val accSummary = AccuracySummary(
        whiteMovesAcc = AccByColor(itera = whiteAcc, weighted = whiteWeighted, harmonic = whiteHarm),
        blackMovesAcc = AccByColor(itera = blackAcc, weighted = blackWeighted, harmonic = blackHarm)
    )

    // ACPL
    val cpl = cplPerMove(positions, sink)
    val whiteAcplD = whiteIdx.map { cpl.getOrElse(it) { 0.0 } }.let { if (it.isEmpty()) 0.0 else it.average() }
    val blackAcplD = blackIdx.map { cpl.getOrElse(it) { 0.0 } }.let { if (it.isEmpty()) 0.0 else it.average() }

    dbg(sink, "== ACPL ==")
    dbg(sink, "White CPLs=[${whiteIdx.joinToString { "%.1f".format(cpl.getOrElse(it) { 0.0 }) }}] -> ACPL=%.1f".format(whiteAcplD))
    dbg(sink, "Black CPLs=[${blackIdx.joinToString { "%.1f".format(cpl.getOrElse(it) { 0.0 }) }}] -> ACPL=%.1f".format(blackAcplD))
    val acplPair = Acpl(white = whiteAcplD.roundToInt(), black = blackAcplD.roundToInt())

    // Performance
    dbg(sink, "== Performance (anchored) ==")
    val perfWhite = LichessFormulas.anchoredEloFromGameAcpl(whiteAcplD, whiteRating, sink)
    val perfBlack = LichessFormulas.anchoredEloFromGameAcpl(blackAcplD, blackRating, sink)
    val perf = EstimatedElo(perfWhite, perfBlack)

    return Triple(accSummary, acplPair, perf)
}

/** Bridge to the classifier (with cut at first mate already applied inside helper paths). */
suspend fun classifyWithAltDiffs(
    moves: List<PgnChess.MoveItem>,
    positions: List<PositionEval>,
    openingFens: Set<String>,
    depth: Int,
    maxCandidates: Int,
    lossThresholdCp: Int,
    throttleMs: Long,
    sink: MutableList<String>? = null
): List<MoveReport> {

    val cutIdx = firstMateIndex(positions)
    val positionsCapped = positions.subList(0, min(positions.size, cutIdx + 1))
    val movesToUse = min(moves.size, max(0, positionsCapped.size - 1))
    val movesCapped = moves.subList(0, movesToUse)
    val (perMoveAcc, _, _) = perMoveAccAndWeights(positionsCapped, sink)
    val winPercentsForGraph = winPercentsForMover(positionsCapped, sink)

    val altDiffs = MoveClassifier.computeAltDiffs(
        moves = movesCapped,
        positions = positionsCapped,
        depth = depth,
        maxCandidates = maxCandidates,
        lossThresholdCp = lossThresholdCp,
        throttleMs = throttleMs,
        sink = sink
    )

    return MoveClassifier.classifyAll(
        moves = movesCapped,
        positions = positionsCapped,
        winPercents = winPercentsForGraph,
        accPerMove = perMoveAcc,
        openingFens = openingFens,
        altDiffs = altDiffs,
        sink = sink
    )
}

suspend fun buildReportFromPgn(
    header: GameHeader,
    openingFens: Set<String> = emptySet(),
    depth: Int = 14,
    throttleMs: Long = 40L
): FullReport = withContext(Dispatchers.IO) {

    val logLines = mutableListOf<String>()
    dbg(logLines, "===== НАЧАЛО ОТЧЁТА =====")
    dbg(logLines, "Рейтинги: White=${header.whiteElo} Black=${header.blackElo}")
    val pgn = header.pgn ?: error("PGN отсутствует")

    val moves: List<PgnChess.MoveItem> = PgnChess.movesWithFens(pgn)
    require(moves.isNotEmpty()) { "Не удалось распарсить ходы PGN" }
    dbg(logLines, "Разобрали PGN: ${moves.size} полуход(ов)")

    val startFen = moves.first().beforeFen
    val allFens = buildList {
        add(startFen)
        moves.forEach { add(it.afterFen) }
    }
    dbg(logLines, "Всего позиций для анализа: ${allFens.size}")

    val positions = ArrayList<PositionEval>(allFens.size)
    for ((idx, fen) in allFens.withIndex()) {
        val r = EngineClient.analyzeFen(fen, depth = depth)
        val cpCentipawns: Int? = r.evaluation?.let { (it * 100).roundToInt() } // eval «за белых»
        val mateForWhite: Int?  = r.mate
        val pv = r.continuation?.trim()?.split(" ")?.filter { it.isNotBlank() } ?: emptyList()

        dbg(logLines, "SF#${idx} FEN=$fen eval=${r.evaluation} (cp=${cpCentipawns}) mate=${r.mate} best=${r.bestmove} pv=${pv.take(10)}")

        positions += PositionEval(
            fen = fen,
            idx = idx,
            lines = listOf(LineEval(pv = pv, cp = cpCentipawns, mate = mateForWhite, best = r.bestmove))
        )

        if (throttleMs > 0) delay(throttleMs)
    }

    // Классификация + altDiffs (учитывает отсечку на мате внутри)
    val moveReports = classifyWithAltDiffs(
        moves = moves,
        positions = positions,
        openingFens = openingFens,
        depth = 10,
        maxCandidates = 4,
        lossThresholdCp = 30,
        throttleMs = 60L,
        sink = logLines
    )

    // Итоговые метрики (отсечка на мате, корректные веса/accuracy/ACPL)
    val (acc, acpl, perf) = summarize(
        positions = positions,
        whiteRating = header.whiteElo,
        blackRating = header.blackElo,
        sink = logLines
    )

    dbg(logLines, "== ИТОГ ==")
    dbg(logLines, "White: weighted=%.2f harmonic=%.2f ACPL=%d perf=%s"
        .format(acc.whiteMovesAcc.weighted, acc.whiteMovesAcc.harmonic, acpl.white, perf.whiteEst?.toString() ?: "-"))
    dbg(logLines, "Black: weighted=%.2f harmonic=%.2f ACPL=%d perf=%s"
        .format(acc.blackMovesAcc.weighted, acc.blackMovesAcc.harmonic, acpl.black, perf.blackEst?.toString() ?: "-"))
    dbg(logLines, "===== КОНЕЦ ОТЧЁТА =====")

    FullReport(
        header = header,
        positions = positions,
        moves = moveReports,
        accuracy = AccuracySummary(
            whiteMovesAcc = AccByColor(
                itera = acc.whiteMovesAcc.itera,
                harmonic = acc.whiteMovesAcc.harmonic,
                weighted = acc.whiteMovesAcc.weighted
            ),
            blackMovesAcc = AccByColor(
                itera = acc.blackMovesAcc.itera,
                harmonic = acc.blackMovesAcc.harmonic,
                weighted = acc.blackMovesAcc.weighted
            )
        ),
        acpl = Acpl(acpl.white, acpl.black),
        estimatedElo = EstimatedElo(perf.whiteEst, perf.blackEst),
        analysisLog = logLines.toList()
    )
}