package com.example.chessanalysis

import kotlin.math.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay

/**
 * Формулы и пайплайн «как у lichess + chesskit».
 *
 * Win% от CP: см. WinPercent.scala (константа из оригинала)
 * Move accuracy: формула из lichess AccuracyPercent, с diff в Win% против ходившей стороны.
 * Game accuracy: среднее из (volatility-weighted mean, harmonic mean);
 *                ВАЖНО: веса считаются на ВЕСЬ список Win% (по всем полуходам),
 *                а затем берутся ходы нужной чётности (белых/чёрных).
 * Performance (Elo) из ACPL: калибровка chesskit + якорение реальным рейтингом.
 */
object LichessFormulas {
    /** CP (centipawns) -> Win% (их кривая). */
    fun cpToWinPercent(cp: Double): Double {
        val x = cp.coerceIn(-1000.0, 1000.0)
        // коэффициент из публичного описания lichess
        val mult = -0.00368208
        val winChances = 2.0 / (1.0 + exp(mult * x)) - 1.0
        return 50.0 + 50.0 * winChances
    }

    /** Mate plies -> Win% (как у chesskit/lichess: знак мата -> 100/0). */
    fun mateToWinPercent(mate: Int): Double = if (mate > 0) 100.0 else 0.0

    /**
     * Accuracy одного хода (lichess).
     * raw = 103.1668100711649 * exp(-0.04354415386753951 * loss) - 3.166924740191411
     * acc = clamp(raw + 1, 0..100)
     *
     * loss — ущерб для ХОДЯЩЕЙ стороны в пунктах Win%.
     */
    fun moveAccuracy(winBefore: Double, winAfter: Double, isWhiteMove: Boolean): Double {
        val loss = if (isWhiteMove) {
            max(0.0, winBefore - winAfter)
        } else {
            max(0.0, winAfter - winBefore)
        }
        val raw = 103.1668100711649 * exp(-0.04354415386753951 * loss) - 3.166924740191411
        return (raw + 1.0).coerceIn(0.0, 100.0)
    }

    /** Размер окна для скользящей σ(win%) — аппроксимация зависимости lichess от длины партии. */
    fun windowSizeForPlies(plies: Int): Int {
        // эмпирическая аппроксимация: растёт с длиной партии, но в разумных пределах
        val approx = (plies / 8.0).roundToInt()
        return approx.coerceIn(4, 14)
    }

    /** Скользящая σ(win%) по всему списку Win% (возвращает веса для КАЖДОГО полухода). */
    fun volatilityWeightsAll(winPercents: List<Double>, window: Int): List<Double> {
        if (winPercents.size < 2) return emptyList()
        val out = MutableList(winPercents.size - 1) { 0.0 } // по полуходам
        val n = winPercents.size
        val w = window.coerceAtLeast(2)

        for (i in 1 until n) {
            val start = max(0, i - w)
            val end = min(n - 1, i + w / 2) // чуть заглядываем вперёд как у lichess (сглаживание)
            val seg = winPercents.subList(start, end + 1)
            val mean = seg.average()
            val varSum = seg.fold(0.0) { acc, v -> acc + (v - mean) * (v - mean) }
            val std = if (seg.size > 1) sqrt(varSum / (seg.size - 1)) else 0.0
            out[i - 1] = max(std, 1e-6)
        }
        return out
    }

    /** Гармоническое среднее. */
    fun harmonic(xs: List<Double>): Double {
        val filtered = xs.filter { it > 0.0 }
        if (filtered.isEmpty()) return 0.0
        val inv = filtered.sumOf { 1.0 / it }
        return (filtered.size / inv).coerceIn(0.0, 100.0)
    }

    /** Взвешенное среднее. */
    fun weightedMean(values: List<Double>, weights: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        var ws = 0.0
        var s = 0.0
        val n = min(values.size, weights.size)
        for (i in 0 until n) {
            val w = weights[i]
            ws += w
            s += values[i] * w
        }
        return if (ws > 0) (s / ws).coerceIn(0.0, 100.0) else values.average().coerceIn(0.0, 100.0)
    }

    // === Performance (Estimated Elo) из ACPL — кривая chesskit ===

    fun eloFromAcpl(acpl: Double): Int =
        (3100.0 * exp(-0.01 * acpl)).roundToInt()

    fun expectedAcplFromElo(elo: Int): Double =
        (-100.0 * ln((elo.toDouble() / 3100.0).coerceIn(1e-9, 1.0))).coerceIn(0.0, 1000.0)

    /** Якорение на известный рейтинг (как в chesskit). */
    fun anchoredEloFromGameAcpl(gameAcpl: Double, rating: Int?): Int {
        val base = eloFromAcpl(gameAcpl)
        if (rating == null) return base
        val expAcpl = expectedAcplFromElo(rating)
        val diff = gameAcpl - expAcpl
        val factor = exp(-0.005 * abs(diff))
        val adjustment = if (diff > 0) {
            // Играл хуже ожидаемого
            rating - (rating - base) * (1 - factor)
        } else {
            // Играл лучше ожидаемого
            rating + (base - rating) * (1 - factor)
        }
        return max(0, adjustment.roundToInt())
    }
}

/** Вспомогательные структуры для расчёта. */
object AnalysisLogic {

    data class EvalPoint(
        val cp: Double?,     // в пешках (со стороны белых)
        val mate: Int?,      // мат в N
        val bestCp: Double?, // оценка лучшего хода для ТЕКУЩЕЙ позиции
        val bestMate: Int?
    )

    data class MoveSlice(
        val moveIndex: Int,      // 1..N
        val isWhiteMove: Boolean,
        val winBefore: Double,
        val winAfter: Double,
        val sideCpl: Double
    )

    /** Срезы по полуходам. */
    fun buildSlices(evals: List<EvalPoint>): List<MoveSlice> {
        if (evals.size < 2) return emptyList()
        val win = evals.map { ep ->
            when {
                ep.cp != null -> LichessFormulas.cpToWinPercent(ep.cp * 100) // пешки -> cP
                ep.mate != null -> LichessFormulas.mateToWinPercent(ep.mate)
                else -> 50.0
            }
        }

        val slices = ArrayList<MoveSlice>(evals.size - 1)
        for (i in 1 until evals.size) {
            val isWhite = (i % 2 == 1) // ply #1 — белые
            val before = win[i - 1]
            val after = win[i]

            val epPrev = evals[i - 1]
            val epAfter = evals[i]

            // Оценка сыгранного хода (позиция после)
            val playedCp = when {
                epAfter.cp != null -> epAfter.cp * 100.0
                epAfter.mate != null -> if (epAfter.mate > 0) 100000.0 else -100000.0
                else -> null
            }

            // Оценка лучшего хода из предыдущей позиции
            val bestCp = when {
                epPrev.bestCp != null -> epPrev.bestCp * 100.0
                epPrev.bestMate != null -> if (epPrev.bestMate > 0) 100000.0 else -100000.0
                else -> null
            }

            val cpl: Double = if (bestCp != null && playedCp != null) {
                // CPL с точки зрения ходящей стороны
                val deltaForSide = if (isWhite) (bestCp - playedCp) else (playedCp - bestCp)
                max(0.0, deltaForSide)
            } else {
                // fallback: аппроксимация через Win%
                abs(before - after) * 10.0
            }

            slices += MoveSlice(
                moveIndex = i,
                isWhiteMove = isWhite,
                winBefore = before,
                winAfter = after,
                sideCpl = cpl
            )
        }
        return slices
    }

    /** Accuracy/веса/агрегация — строго по всей последовательности Win% (как у lichess). */
    private fun gameAccElements(evals: List<EvalPoint>): Triple<List<Double>, List<Double>, List<Double>> {
        // Win% для всех позиций
        val win = evals.map { ep ->
            when {
                ep.cp != null -> LichessFormulas.cpToWinPercent(ep.cp * 100)
                ep.mate != null -> LichessFormulas.mateToWinPercent(ep.mate)
                else -> 50.0
            }
        }
        val plies = win.size - 1
        if (plies <= 0) return Triple(emptyList(), emptyList(), emptyList())

        // per-ply accuracy и веса по ВСЕМ полуходам
        val perPlyAcc = MutableList(plies) { 100.0 }
        for (i in 0 until plies) {
            val isWhiteMove = (i % 2 == 0)
            perPlyAcc[i] = LichessFormulas.moveAccuracy(win[i], win[i + 1], isWhiteMove)
        }
        val window = LichessFormulas.windowSizeForPlies(plies)
        val weightsAll = LichessFormulas.volatilityWeightsAll(win, window)
        return Triple(win, perPlyAcc, weightsAll)
    }

    /** Итоговая accuracy для конкретного цвета. */
    private fun playerAccuracy(evals: List<EvalPoint>, forWhite: Boolean): Double {
        val (_, perPlyAcc, weightsAll) = gameAccElements(evals)
        if (perPlyAcc.isEmpty()) return 0.0

        // выбираем ходы нужной чётности
        val vals = ArrayList<Double>()
        val wts = ArrayList<Double>()
        for (i in perPlyAcc.indices) {
            if ((i % 2 == 0) == forWhite) {
                vals += perPlyAcc[i]
                if (i < weightsAll.size) wts += weightsAll[i]
            }
        }

        val wMean = LichessFormulas.weightedMean(vals, wts)
        val hMean = LichessFormulas.harmonic(vals)
        return ((wMean + hMean) / 2.0).coerceIn(0.0, 100.0)
    }

    /** ACPL игрока. */
    private fun playerAcpl(slices: List<MoveSlice>, forWhite: Boolean): Double {
        val playerMoves = slices.filter { it.isWhiteMove == forWhite }
        if (playerMoves.isEmpty()) return 0.0
        return playerMoves.map { it.sideCpl }.average()
    }

    // ---- top-level summary модели из Models.kt
    data class AccByColor(
        val itera: List<Double>,
        val harmonic: Double,
        val weighted: Double
    )
    data class AccuracySummary(val whiteMovesAcc: AccByColor, val blackMovesAcc: AccByColor)
    data class Acpl(val white: Int, val black: Int)
    data class EstimatedElo(val whiteEst: Int?, val blackEst: Int?)

    /** Полная сводка. */
    fun summarize(
        evals: List<EvalPoint>,
        whiteRating: Int? = null,
        blackRating: Int? = null
    ): Triple<AccuracySummary, Acpl, EstimatedElo> {
        val slices = buildSlices(evals)

        // Пер-ply элементы для формирования AccByColor (итеративные значения нужны UI)
        val (win, perPlyAcc, weightsAll) = gameAccElements(evals)

        // Разделяем по чётности
        val whiteIter = perPlyAcc.filterIndexed { i, _ -> i % 2 == 0 }
        val blackIter = perPlyAcc.filterIndexed { i, _ -> i % 2 == 1 }

        val whiteW = weightsAll.filterIndexed { i, _ -> i % 2 == 0 }
        val blackW = weightsAll.filterIndexed { i, _ -> i % 2 == 1 }

        val whiteWeighted = LichessFormulas.weightedMean(whiteIter, whiteW)
        val whiteHarm = LichessFormulas.harmonic(whiteIter)
        val blackWeighted = LichessFormulas.weightedMean(blackIter, blackW)
        val blackHarm = LichessFormulas.harmonic(blackIter)

        // Финальная accuracy = среднее из weighted и harmonic
        val whiteAcc = (whiteWeighted + whiteHarm) / 2.0
        val blackAcc = (blackWeighted + blackHarm) / 2.0

        val acc = AccuracySummary(
            whiteMovesAcc = AccByColor(whiteIter, whiteHarm, whiteWeighted),
            blackMovesAcc = AccByColor(blackIter, blackHarm, blackWeighted)
        )

        val wAcpl = playerAcpl(slices, true).roundToInt()
        val bAcpl = playerAcpl(slices, false).roundToInt()

        val perfWhite = LichessFormulas.anchoredEloFromGameAcpl(wAcpl.toDouble(), whiteRating)
        val perfBlack = LichessFormulas.anchoredEloFromGameAcpl(bAcpl.toDouble(), blackRating)
        val perf = EstimatedElo(
            whiteEst = perfWhite.takeIf { it > 0 },
            blackEst = perfBlack.takeIf { it > 0 }
        )

        return Triple(acc, Acpl(wAcpl, bAcpl), perf)
    }

    // ----------------- PIPELINE для UI: wins / perMove / altDiffs / classify -----------------

    fun computeWinPercents(positions: List<PositionEval>): List<Double> =
        positions.map { pe ->
            val line = pe.lines.first()
            when {
                line.cp != null -> LichessFormulas.cpToWinPercent(line.cp.toDouble())
                line.mate != null -> LichessFormulas.mateToWinPercent(line.mate)
                else -> 50.0
            }
        }

    fun computePerMoveAccuracyFromWins(wins: List<Double>): List<Double> {
        if (wins.size < 2) return emptyList()
        val out = ArrayList<Double>(wins.size - 1)
        for (i in 0 until wins.size - 1) {
            val isWhiteMove = (i % 2 == 0)
            out += LichessFormulas.moveAccuracy(wins[i], wins[i + 1], isWhiteMove)
        }
        return out
    }

    /** Композит: altDiffs + классификация. */
    suspend fun classifyWithAltDiffs(
        moves: List<PgnChess.MoveItem>,
        positions: List<PositionEval>,
        openingFens: Set<String> = emptySet(),
        depth: Int = 10,
        maxCandidates: Int = 4,
        lossThresholdCp: Int = 30,
        throttleMs: Long = 80L
    ): List<MoveReport> {
        val wins = computeWinPercents(positions)
        val perMove = computePerMoveAccuracyFromWins(wins)

        val altDiffs = MoveClassifier.computeAltDiffs(
            moves = moves,
            positions = positions,
            depth = depth,
            maxCandidates = maxCandidates,
            lossThresholdCp = lossThresholdCp,
            throttleMs = throttleMs
        )

        return MoveClassifier.classifyAll(
            moves = moves,
            positions = positions,
            winPercents = wins,
            accPerMove = perMove,
            openingFens = openingFens,
            altDiffs = altDiffs
        )
    }
}

/** Топ-левел отчёт для UI. */
suspend fun reportFromPgn(
    header: GameHeader,
    openingFens: Set<String> = emptySet(),
    depth: Int = 14,
    throttleMs: Long = 40L
): FullReport = withContext(Dispatchers.IO) {
    val pgn = header.pgn
    require(!pgn.isNullOrBlank()) { "PGN отсутствует" }

    val moves: List<PgnChess.MoveItem> = PgnChess.movesWithFens(pgn)
    require(moves.isNotEmpty()) { "Не удалось распарсить ходы PGN" }

    val startFen = moves.first().beforeFen
    val allFens = buildList {
        add(startFen)
        moves.forEach { add(it.afterFen) }
    }

    val positions = ArrayList<PositionEval>(allFens.size)
    val evalPoints = ArrayList<AnalysisLogic.EvalPoint>(allFens.size)

    // Сначала анализируем все позиции и сохраняем результаты
    val rawEvals = ArrayList<StockfishResponse>(allFens.size)
    for ((idx, fen) in allFens.withIndex()) {
        val r = EngineClient.analyzeFen(fen, depth = depth)
        rawEvals.add(r)

        val cpInt: Int? = r.evaluation?.let { (it * 100).roundToInt() }
        val mate = r.mate
        val best = r.bestmove
        val pv = r.continuation?.trim()?.split(" ")?.filter { it.isNotBlank() } ?: emptyList()

        positions += PositionEval(
            fen = fen,
            idx = idx,
            lines = listOf(
                LineEval(
                    pv = pv,
                    cp = cpInt,
                    mate = mate,
                    best = best
                )
            )
        )

        if (throttleMs > 0) delay(throttleMs)
    }

    // Теперь создаем evalPoints с правильными bestCp/bestMate
    for (idx in allFens.indices) {
        val currentEval = rawEvals[idx]

        // Для текущей позиции берем оценку после хода
        val cp = currentEval.evaluation
        val mate = currentEval.mate

        // bestCp и bestMate - это оценка лучшего хода из ЭТОЙ позиции
        // Если есть bestmove, его оценка уже в cp/mate (т.к. движок дает оценку лучшего хода)
        val bestCp = cp
        val bestMate = mate

        evalPoints += AnalysisLogic.EvalPoint(
            cp = cp,
            mate = mate,
            bestCp = bestCp,
            bestMate = bestMate
        )
    }

    val moveReports = AnalysisLogic.classifyWithAltDiffs(
        moves = moves,
        positions = positions,
        openingFens = openingFens,
        depth = 10,
        maxCandidates = 4,
        lossThresholdCp = 30,
        throttleMs = 60L
    )

    val (acc, acpl, perf) = AnalysisLogic.summarize(
        evals = evalPoints,
        whiteRating = header.whiteElo,
        blackRating = header.blackElo
    )

    FullReport(
        header = header,
        positions = positions,
        moves = moveReports,
        accuracy = com.example.chessanalysis.AccuracySummary(
            whiteMovesAcc = com.example.chessanalysis.AccByColor(
                itera = acc.whiteMovesAcc.itera,
                harmonic = acc.whiteMovesAcc.harmonic,
                weighted = acc.whiteMovesAcc.weighted
            ),
            blackMovesAcc = com.example.chessanalysis.AccByColor(
                itera = acc.blackMovesAcc.itera,
                harmonic = acc.blackMovesAcc.harmonic,
                weighted = acc.blackMovesAcc.weighted
            )
        ),
        acpl = Acpl(acpl.white, acpl.black),
        estimatedElo = EstimatedElo(perf.whiteEst, perf.blackEst),
        analysisLog = emptyList()
    )
}