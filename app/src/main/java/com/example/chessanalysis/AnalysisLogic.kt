package com.example.chessanalysis

import android.util.Log
import kotlin.math.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay

private const val TAG = "AnalysisDebug"
private const val DEBUG_ANALYSIS = true

private fun dbg(sink: MutableList<String>?, msg: String) {
    if (DEBUG_ANALYSIS) Log.d(TAG, msg)
    sink?.add(msg)
}

object LichessFormulas {

    // Точная формула Lichess для Win% из сентипешек
    fun cpToWinPercent(cp: Double): Double {
        // Официальная формула Lichess: 50 + 50 * (2 / (1 + exp(-0.00368208 * cp)) - 1)
        val k = -0.00368208
        val p = 50 + 50 * (2 / (1 + exp(k * cp)) - 1)
        return p.coerceIn(0.0, 100.0)
    }

    // Win% из мата
    fun mateToWinPercent(mateForWhite: Int, isForMoverWhite: Boolean): Double {
        val signed = if (isForMoverWhite) mateForWhite else -mateForWhite
        return if (signed > 0) 100.0 else 0.0
    }

    // Формула точности хода от Lichess
    fun moveAccuracyFromLoss(loss: Double): Double {
        val accuracy = 103.1668 * exp(-0.04354 * loss) - 3.1669
        return accuracy.coerceIn(0.0, 100.0)
    }

    // Размер окна волатильности
    fun windowSizeForPlies(plies: Int): Int {
        return max(6, plies / 8)
    }

    // Округление волатильности к шагу
    fun ceilToStep(value: Double, step: Double, min: Double, max: Double): Double {
        val v = ceil(value / step) * step
        return v.coerceIn(min, max)
    }

    // Расчет весов волатильности для всех ходов
    fun volatilityWeightsAll(
        winPercents: List<Double>,
        window: Int,
        sink: MutableList<String>? = null
    ): List<Double> {
        if (winPercents.size < 2) return emptyList()

        dbg(sink, "— Volatility weights: window=$window, points=${winPercents.size}")
        val weights = MutableList(winPercents.size - 1) { 1.0 }

        for (i in 1 until winPercents.size) {
            val startIdx = max(0, i - window / 2)
            val endIdx = min(winPercents.size - 1, i + window / 2)

            if (endIdx - startIdx < 2) {
                weights[i - 1] = 1.0
                continue
            }

            // Вычисляем стандартное отклонение в окне
            val windowValues = winPercents.subList(startIdx, endIdx + 1)
            val mean = windowValues.average()
            val variance = windowValues.fold(0.0) { acc, v ->
                acc + (v - mean) * (v - mean)
            } / windowValues.size
            val std = sqrt(variance)

            // Преобразуем в вес (как в Lichess)
            val weight = ceilToStep(std / 10.0, 0.5, 0.5, 12.0)
            weights[i - 1] = weight

            dbg(sink, "   σ[$i] std=%.3f -> weight=%.1f".format(std, weight))
        }

        return weights
    }

    // Гармоническое среднее
    fun harmonic(xs: List<Double>): Double {
        val filtered = xs.filter { it > 0.0 }
        if (filtered.isEmpty()) return 0.0
        val inv = filtered.sumOf { 1.0 / it }
        return (filtered.size / inv).coerceIn(0.0, 100.0)
    }

    // Взвешенное среднее
    fun weightedMean(values: List<Double>, weights: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        var weightSum = 0.0
        var sum = 0.0
        val n = min(values.size, weights.size)

        for (i in 0 until n) {
            val w = 1.0 / max(0.5, weights[i]) // Инвертируем вес (меньший вес = больше важности)
            weightSum += w
            sum += values[i] * w
        }

        return if (weightSum > 0) (sum / weightSum).coerceIn(0.0, 100.0) else values.average()
    }

    // Оценка перфоманса из ACPL
    fun eloFromAcpl(acpl: Double): Int {
        // Формула приближения от Lichess
        return (-3.68 * acpl * acpl + 2764).roundToInt().coerceIn(400, 3000)
    }

    fun expectedAcplFromElo(elo: Int): Double {
        // Обратная формула
        val acpl = sqrt(max(0.0, (2764 - elo) / 3.68))
        return acpl.coerceIn(0.0, 350.0)
    }

    fun anchoredEloFromGameAcpl(gameAcpl: Double, rating: Int?, sink: MutableList<String>? = null): Int {
        val clipped = gameAcpl.coerceIn(0.0, 200.0)
        val base = eloFromAcpl(clipped)

        if (rating == null || rating == 0) {
            dbg(sink, "— Performance no-anchor: ACPL=%.1f -> %d".format(clipped, base))
            return base
        }

        // Мягкое привязывание к известному рейтингу
        val weight = 0.7 // Вес оценки движка
        val anchored = (base * weight + rating * (1 - weight)).roundToInt()

        dbg(sink, "— Performance anchored: rating=$rating, ACPL=%.1f, base=%d, anchored=%d"
            .format(clipped, base, anchored))

        return anchored.coerceIn(max(400, rating - 500), min(3000, rating + 500))
    }
}

// Находим первый мат в позициях
private fun firstMateIndex(positions: List<PositionEval>): Int {
    val idx = positions.indexOfFirst { it.lines.firstOrNull()?.mate != null }
    return if (idx >= 0) idx else positions.size - 1
}

// Расчет Win% для ходящего в каждой позиции
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

        dbg(sink, "pos#%02d [mover=%s] cp=%s mate=%s -> Win%%=%.2f"
            .format(i, if (moverWhite) "W" else "B", cp?.toString() ?: "-", mate?.toString() ?: "-", winForMover))

        out += winForMover
    }

    return out
}

// Расчет CPL по ходам
private fun cplPerMove(positions: List<PositionEval>, sink: MutableList<String>?): List<Double> {
    if (positions.size < 2) return emptyList()

    val last = firstMateIndex(positions)
    val out = ArrayList<Double>()
    val CPL_CAP = 300.0

    dbg(sink, "== CPL per move ==")

    for (i in 0 until min(positions.size - 1, last)) {
        val isWhite = positions[i].fen.contains(" w ")
        val before = positions[i].lines.firstOrNull()
        val after = positions[i + 1].lines.firstOrNull()

        val bestCp = when {
            before?.cp != null -> before.cp.toDouble()
            before?.mate != null -> if (before.mate > 0) 1000.0 else -1000.0
            else -> null
        }

        val playedCp = when {
            after?.cp != null -> after.cp.toDouble()
            after?.mate != null -> if (after.mate > 0) 1000.0 else -1000.0
            else -> null
        }

        val cpl = if (bestCp != null && playedCp != null) {
            val deltaForWhite = bestCp - playedCp
            val deltaForMover = if (isWhite) deltaForWhite else -deltaForWhite
            max(0.0, deltaForMover).coerceAtMost(CPL_CAP)
        } else 0.0

        dbg(sink, "ply#%02d mover=%s bestCp=%s playedCp=%s -> CPL=%.1f"
            .format(i + 1, if (isWhite) "W" else "B",
                bestCp?.toString() ?: "-", playedCp?.toString() ?: "-", cpl))

        out += cpl
    }

    return out
}

// Расчет точности по ходам с весами
private fun perMoveAccAndWeights(
    positions: List<PositionEval>,
    sink: MutableList<String>?
): Triple<List<Double>, List<Double>, List<Boolean>> {

    val cutIdx = firstMateIndex(positions)
    val allWin = winPercentsForMover(positions, sink)
    val winForMover = allWin.subList(0, min(allWin.size, cutIdx + 1))
    val plies = winForMover.size - 1

    if (plies <= 0) return Triple(emptyList(), emptyList(), emptyList())

    dbg(sink, "== Per-move accuracy ==")
    val perMoveAcc = ArrayList<Double>()
    val isWhiteFlags = ArrayList<Boolean>()

    for (i in 0 until plies) {
        val isWhite = positions[i].fen.contains(" w ")
        val before = winForMover[i]
        val afterSame = 100.0 - winForMover[i + 1]
        val loss = max(0.0, before - afterSame)
        val acc = LichessFormulas.moveAccuracyFromLoss(loss)

        perMoveAcc += acc
        isWhiteFlags += isWhite

        dbg(sink, "ply#%02d mover=%s: WinBefore=%.2f WinAfter=%.2f loss=%.2f -> acc=%.2f"
            .format(i + 1, if (isWhite) "W" else "B", before, afterSame, loss, acc))
    }

    val window = LichessFormulas.windowSizeForPlies(plies)
    val weightsAll = LichessFormulas.volatilityWeightsAll(winForMover, window, sink)

    return Triple(perMoveAcc, weightsAll, isWhiteFlags)
}

// Итоговая статистика
fun summarize(
    positions: List<PositionEval>,
    whiteRating: Int?,
    blackRating: Int?,
    sink: MutableList<String>? = null
): Triple<AccuracySummary, Acpl, EstimatedElo> {

    val (perMoveAccAll, weightsAll, isWhiteFlags) = perMoveAccAndWeights(positions, sink)

    // Разделяем по цветам
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

    dbg(sink, "White: weighted=%.2f harmonic=%.2f".format(whiteWeighted, whiteHarm))
    dbg(sink, "Black: weighted=%.2f harmonic=%.2f".format(blackWeighted, blackHarm))

    val accSummary = AccuracySummary(
        whiteMovesAcc = AccByColor(itera = whiteAcc, weighted = whiteWeighted, harmonic = whiteHarm),
        blackMovesAcc = AccByColor(itera = blackAcc, weighted = blackWeighted, harmonic = blackHarm)
    )

    // ACPL
    val cpl = cplPerMove(positions, sink)
    val whiteAcpl = if (whiteIdx.isEmpty()) 0.0 else
        whiteIdx.mapNotNull { cpl.getOrNull(it) }.average()
    val blackAcpl = if (blackIdx.isEmpty()) 0.0 else
        blackIdx.mapNotNull { cpl.getOrNull(it) }.average()

    dbg(sink, "ACPL: White=%.1f Black=%.1f".format(whiteAcpl, blackAcpl))

    val acplPair = Acpl(white = whiteAcpl.roundToInt(), black = blackAcpl.roundToInt())

    // Performance (с учетом рейтингов если есть)
    val perfWhite = if (whiteAcpl > 0)
        LichessFormulas.anchoredEloFromGameAcpl(whiteAcpl, whiteRating, sink) else null
    val perfBlack = if (blackAcpl > 0)
        LichessFormulas.anchoredEloFromGameAcpl(blackAcpl, blackRating, sink) else null

    val perf = EstimatedElo(perfWhite, perfBlack)

    return Triple(accSummary, acplPair, perf)
}

// Классификация с altDiffs
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

// Полный анализ PGN
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
        val cpCentipawns: Int? = r.evaluation?.let { (it * 100).roundToInt() }
        val mateForWhite: Int? = r.mate
        val pv = r.continuation?.trim()?.split(" ")?.filter { it.isNotBlank() } ?: emptyList()

        dbg(logLines, "SF#${idx} cp=${cpCentipawns} mate=${r.mate} best=${r.bestmove}")

        positions += PositionEval(
            fen = fen,
            idx = idx,
            lines = listOf(LineEval(pv = pv, cp = cpCentipawns, mate = mateForWhite, best = r.bestmove))
        )

        if (throttleMs > 0) delay(throttleMs)
    }

    // Классификация ходов
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

    // Итоговые метрики
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
        accuracy = acc,
        acpl = acpl,
        estimatedElo = perf,
        analysisLog = logLines.toList()
    )
}