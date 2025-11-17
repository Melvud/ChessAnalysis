package com.github.movesense.analysis

import com.github.movesense.*
import kotlinx.coroutines.*
import java.util.UUID
import android.util.Log
import kotlin.collections.get
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

class LocalGameAnalyzer(
    private val progressHook: (id: String, percent: Double?, stage: String?) -> Unit = { _, _, _ -> }
) {

    companion object {
        private const val TAG = "LocalGameAnalyzer"
    }

    suspend fun evaluateGameByPgn(
        pgn: String,
        depth: Int,
        multiPv: Int,
        workersNb: Int,
        header: GameHeader?
    ): FullReport = evaluateGameByPgnWithProgress(pgn, depth, multiPv, workersNb, header) { _ -> }

    suspend fun evaluateGameByPgnWithProgress(
        pgn: String,
        depth: Int,
        multiPv: Int,
        workersNb: Int,
        header: GameHeader?,
        onProgress: (EngineClient.ProgressSnapshot) -> Unit
    ): FullReport = withContext(Dispatchers.IO) {
        val parsed = PgnChess.movesWithFens(pgn)
        val total = parsed.size

        if (parsed.isEmpty()) {
            throw IllegalArgumentException("PGN contains no moves")
        }

        val progressId = UUID.randomUUID().toString()
        val startedAt = System.currentTimeMillis()

        val startFen = parsed.firstOrNull()?.beforeFen
            ?: "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"

        notify(progressId, 0, total, "preparing", startedAt, onProgress, null, null, null, null, null, null, null)

        // Собираем все FEN-ы (стартовая позиция + после каждого хода)
        val allFens = listOf(startFen) + parsed.map { it.afterFen }
        val uciMoves = parsed.map { it.uci }
        val sanMoves = parsed.map { it.san }

        Log.d(TAG, "🚀 Evaluating ${allFens.size} positions in batch mode WITH PROGRESS")

        notify(progressId, 0, total, "evaluating", startedAt, onProgress, null, null, null, null, null, null, null)

        // ✅ ИСПРАВЛЕНИЕ: Передаем классификацию в локальном режиме для отображения в реальном времени
        val batchResult = EngineClient.evaluatePositionsBatchWithProgress(
            fens = allFens,
            uciMoves = uciMoves,
            depth = depth,
            multiPv = multiPv
        ) { serverSnap ->
            val currentIdx = serverSnap.done - 1
            val currentSan = if (currentIdx >= 0 && currentIdx < sanMoves.size) {
                sanMoves[currentIdx]
            } else null

            // ✅ В локальном режиме сразу вычисляем классификацию
            val currentClass = if (EngineClient.engineMode.value == EngineClient.EngineMode.LOCAL
                && currentIdx > 0
                && currentIdx <= serverSnap.done) {
                val evalCp = serverSnap.evalCp
                val evalMate = serverSnap.evalMate

                when {
                    evalMate != null && evalMate != 0 -> {
                        if ((evalMate > 0 && currentIdx % 2 == 1) || (evalMate < 0 && currentIdx % 2 == 0)) {
                            "BEST"
                        } else {
                            "BLUNDER"
                        }
                    }
                    evalCp != null -> {
                        when {
                            evalCp >= 100 -> "BEST"
                            evalCp >= 50 -> "EXCELLENT"
                            evalCp >= -50 -> "OKAY"
                            evalCp >= -100 -> "INACCURACY"
                            evalCp >= -300 -> "MISTAKE"
                            else -> "BLUNDER"
                        }
                    }
                    else -> null
                }
            } else null

            val enrichedSnap = EngineClient.ProgressSnapshot(
                id = progressId,
                total = total,
                done = serverSnap.done,
                percent = serverSnap.percent,
                etaMs = serverSnap.etaMs,
                stage = serverSnap.stage,
                startedAt = startedAt,
                updatedAt = serverSnap.updatedAt ?: System.currentTimeMillis(),
                fen = serverSnap.fen,
                currentSan = currentSan,
                currentClass = currentClass,
                currentUci = serverSnap.currentUci,
                evalCp = serverSnap.evalCp,
                evalMate = serverSnap.evalMate
            )

            onProgress(enrichedSnap)
            progressHook(progressId, serverSnap.percent, serverSnap.stage)
        }

        val positions = batchResult.positions

        if (positions.size != allFens.size) {
            throw IllegalStateException("Server returned ${positions.size} positions, expected ${allFens.size}")
        }

        Log.d(TAG, "✓ Received ${positions.size} evaluated positions from engine")

        // 🔍 ОТЛАДКА: Логируем первые 3 позиции чтобы проверить данные
        positions.take(3).forEachIndexed { idx, pos ->
            val fen = allFens[idx]
            val whiteToMove = sideToMoveIsWhite(fen)
            val topLine = pos.lines.firstOrNull()
            Log.d(TAG, "📊 Position $idx: whiteToMove=$whiteToMove, cp=${topLine?.cp}, mate=${topLine?.mate}, fen=${fen.take(30)}...")
        }

        notify(progressId, total, total, "postprocess", startedAt, onProgress, null, null, null, null, null, null, null)

        // ✅ КРИТИЧЕСКОЕ ИСПРАВЛЕНИЕ: Определяем режим работы
        val isLocalEngine = EngineClient.engineMode.value == EngineClient.EngineMode.LOCAL

        // Конвертируем позиции в PositionEval
        val positionEvals: List<PositionEval> = positions.mapIndexed { idx, pos ->
            val currentFen = allFens[idx]

            // ✅ Нормализация ТОЛЬКО для локального движка!
            // Серверные данные УЖЕ нормализованы - просто конвертируем
            if (isLocalEngine) {
                normalizeToWhitePOV(
                    fen = currentFen,
                    pos = pos,
                    idx = idx,
                    isLast = idx == positions.lastIndex
                )
            } else {
                // ✅ Серверные данные УЖЕ нормализованы - просто конвертируем
                convertToPositionEval(
                    fen = currentFen,
                    pos = pos,
                    idx = idx,
                    isLast = idx == positions.lastIndex
                )
            }
        }

        // 🔍 ОТЛАДКА: Логируем нормализованные данные
        positionEvals.take(3).forEachIndexed { idx, pos ->
            val topLine = pos.lines.firstOrNull()
            Log.d(TAG, "📈 Normalized $idx: cp=${topLine?.cp}, mate=${topLine?.mate}")
        }

        // ACPL calculation
        val acpl = ACPL.calculateACPLFromPositionEvals(positionEvals)

        // Win percentages
        val winPercents = positionEvals.map { pos ->
            val first = pos.lines.firstOrNull()
            if (first != null && (first.cp != null || first.mate != null)) {
                WinPercentage.getPositionWinPercentage(pos)
            } else {
                50.0
            }
        }

        // Per-move accuracy from win percentages
        val movesAccuracy = Accuracy.perMoveAccFromWin(winPercents)

        // Accuracy weights
        val weightsAcc = getAccuracyWeights(winPercents)

        // Player accuracy
        val whiteAcc = computePlayerAccuracy(movesAccuracy, weightsAcc, "white")
        val blackAcc = computePlayerAccuracy(movesAccuracy, weightsAcc, "black")

        // Move classification
        val classifiedPositions = MoveClassification.getMovesClassification(
            positionEvals,
            uciMoves,
            allFens
        )

        // Build move reports
        val moves = buildMoveReports(
            classifiedPositions,
            allFens,
            uciMoves,
            winPercents,
            movesAccuracy,
            sanMoves
        )

        // Estimated Elo
        val tagsHeader = PgnChess.headerFromPgn(pgn)
        val hdr = header ?: tagsHeader
        val est = EstimateElo.computeEstimatedElo(positionEvals, hdr.whiteElo, hdr.blackElo)

        notify(progressId, total, total, "done", startedAt, onProgress, 0L, null, null, null, null, null, null)

        FullReport(
            header = hdr,
            positions1 = positions,
            positions = positionEvals,
            moves = moves,
            accuracy = AccuracySummary(
                whiteMovesAcc = AccByColor(whiteAcc.itera, whiteAcc.weighted, whiteAcc.harmonic),
                blackMovesAcc = AccByColor(blackAcc.itera, blackAcc.weighted, blackAcc.harmonic)
            ),
            acpl = Acpl(acpl.white, acpl.black),
            estimatedElo = EstimatedElo(est.white, est.black)
        )
    }

    suspend fun analyzeMoveRealtimeDetailed(
        beforeFen: String,
        afterFen: String,
        uciMove: String,
        depth: Int,
        multiPv: Int,
        skillLevel: Int?
    ): EngineClient.MoveRealtimeResult = withContext(Dispatchers.IO) {
        val posBefore = EngineClient.evaluateFenDetailed(beforeFen, depth, multiPv, skillLevel)
        val posAfter = EngineClient.evaluateFenDetailed(afterFen, depth, multiPv, skillLevel)

        // ✅ КРИТИЧЕСКОЕ ИСПРАВЛЕНИЕ: Правильная нормализация
        val whiteToPlayAfter = afterFen.split(" ").getOrNull(1) == "w"
        val topLine = posAfter.lines.firstOrNull()

        // ✅ Нормализация оценок с учетом мата
        val cpAfter = if (whiteToPlayAfter) topLine?.cp else topLine?.cp?.let { -it }
        val mateAfter = topLine?.mate?.let { m ->
            when {
                m == 0 && whiteToPlayAfter -> -1  // Белые заматованы
                m == 0 && !whiteToPlayAfter -> 1  // Чёрные заматованы
                !whiteToPlayAfter -> -m
                else -> m
            }
        }

        val evalAfter = when {
            mateAfter != null -> {
                if (mateAfter > 0) 100f else -100f
            }
            cpAfter != null -> cpAfter / 100f
            else -> 0f
        }

        val classification = classifyMoveUsingMoveClassifier(beforeFen, afterFen, posBefore, posAfter, uciMove)

        EngineClient.MoveRealtimeResult(
            evalAfter = evalAfter,
            moveClass = classification,
            bestMove = posBefore.bestMove,
            lines = posAfter.lines
        )
    }

    private suspend fun classifyMoveUsingMoveClassifier(
        beforeFen: String,
        afterFen: String,
        posBefore: EngineClient.PositionDTO,
        posAfter: EngineClient.PositionDTO,
        uciMove: String
    ): MoveClass = withContext(Dispatchers.IO) {
        try {
            val whiteToPlayBefore = beforeFen.split(" ").getOrNull(1) == "w"
            val whiteToPlayAfter = afterFen.split(" ").getOrNull(1) == "w"

            val posEvalBefore = PositionEval(
                fen = beforeFen,
                idx = 0,
                lines = posBefore.lines.map { line ->
                    val cp = if (!whiteToPlayBefore && line.cp != null) -line.cp else line.cp
                    val mate = line.mate?.let { m ->
                        when {
                            m == 0 && !whiteToPlayBefore -> 1
                            m == 0 && whiteToPlayBefore -> -1
                            !whiteToPlayBefore -> -m
                            else -> m
                        }
                    }
                    LineEval(
                        pv = line.pv,
                        cp = cp,
                        mate = mate,
                        depth = line.depth,
                        best = line.pv.firstOrNull()
                    )
                },
                bestMove = posBefore.bestMove
            )

            val posEvalAfter = PositionEval(
                fen = afterFen,
                idx = 1,
                lines = posAfter.lines.map { line ->
                    val cp = if (!whiteToPlayAfter && line.cp != null) -line.cp else line.cp
                    val mate = line.mate?.let { m ->
                        when {
                            m == 0 && !whiteToPlayAfter -> 1
                            m == 0 && whiteToPlayAfter -> -1
                            !whiteToPlayAfter -> -m
                            else -> m
                        }
                    }
                    LineEval(
                        pv = line.pv,
                        cp = cp,
                        mate = mate,
                        depth = line.depth,
                        best = null
                    )
                },
                bestMove = null
            )

            val classified = MoveClassification.getMovesClassification(
                listOf(posEvalBefore, posEvalAfter),
                listOf(uciMove),
                listOf(beforeFen, afterFen)
            )

            classified.getOrNull(1)?.moveClassification ?: MoveClass.OKAY
        } catch (e: Exception) {
            Log.e(TAG, "Error classifying move: ${e.message}", e)
            MoveClass.OKAY
        }
    }

    private suspend fun notify(
        id: String,
        done: Int,
        total: Int,
        stage: String,
        startedAt: Long,
        onProgress: (EngineClient.ProgressSnapshot) -> Unit,
        etaMs: Long? = null,
        fen: String? = null,
        san: String? = null,
        cls: String? = null,
        uci: String? = null,
        evalCp: Int? = null,
        evalMate: Int? = null
    ) {
        val percent = if (total > 0) done.toDouble() * 100.0 / total else null
        val snap = EngineClient.ProgressSnapshot(
            id = id,
            total = total,
            done = done,
            percent = percent,
            etaMs = etaMs,
            stage = stage,
            startedAt = startedAt,
            updatedAt = System.currentTimeMillis(),
            fen = fen,
            currentSan = san,
            currentClass = cls,
            currentUci = uci,
            evalCp = evalCp,
            evalMate = evalMate
        )
        onProgress(snap)
        progressHook(id, percent, stage)
        delay(1)
    }

    private fun getAccuracyWeights(winPercents: List<Double>): List<Double> {
        val windowSize = MathUtils.ceilsNumber(
            ceil(winPercents.size / 10.0),
            2.0,
            8.0
        ).toInt()

        val windows = mutableListOf<List<Double>>()
        val halfWindowSize = round(windowSize / 2.0).toInt()

        for (i in 1 until winPercents.size) {
            val startIdx = i - halfWindowSize
            val endIdx = i + halfWindowSize

            when {
                startIdx < 0 -> windows.add(winPercents.take(windowSize))
                endIdx > winPercents.size -> windows.add(winPercents.takeLast(windowSize))
                else -> windows.add(winPercents.subList(startIdx, endIdx))
            }
        }

        return windows.map { window ->
            val std = MathUtils.getStandardDeviation(window)
            MathUtils.ceilsNumber(std, 0.5, 12.0)
        }
    }

    private fun computePlayerAccuracy(
        movesAcc: List<Double>,
        weights: List<Double>,
        player: String
    ): PlayerAccuracy {
        val remainder = if (player == "white") 0 else 1
        val playerAcc = movesAcc.filterIndexed { idx, _ -> idx % 2 == remainder }
        val playerWeights = weights.filterIndexed { idx, _ -> idx % 2 == remainder }

        val weighted = MathUtils.getWeightedMean(playerAcc, playerWeights)
        val harmonic = MathUtils.getHarmonicMean(playerAcc)
        val itera = (weighted + harmonic) / 2.0

        return PlayerAccuracy(itera, weighted, harmonic)
    }

    private fun buildMoveReports(
        classifiedPositions: List<MoveClassification.ClassifiedPosition>,
        fens: List<String>,
        uciMoves: List<String>,
        winPercents: List<Double>,
        perMoveAcc: List<Double>,
        sanMoves: List<String>
    ): List<MoveReport> {
        val count = min(uciMoves.size, max(0, fens.size - 1))
        return (0 until count).map { i ->
            val uci = uciMoves[i]
            val beforeFen = fens[i]
            val afterFen = fens[i + 1]
            val san = sanMoves[i]

            val cls = classifiedPositions[i + 1].moveClassification ?: MoveClass.OKAY

            MoveReport(
                san = san,
                uci = uci,
                beforeFen = beforeFen,
                afterFen = afterFen,
                winBefore = winPercents[i],
                winAfter = winPercents[i + 1],
                accuracy = perMoveAcc.getOrNull(i) ?: 0.0,
                classification = cls,
                tags = emptyList()
            )
        }
    }

    private fun sideToMoveIsWhite(fen: String): Boolean =
        fen.split(" ").getOrNull(1) == "w"

    /**
     * ✅ Конвертирует PositionDTO в PositionEval БЕЗ нормализации.
     * Используется когда данные приходят с СЕРВЕРА (уже нормализованы).
     */
    private fun convertToPositionEval(
        fen: String,
        pos: EngineClient.PositionDTO,
        idx: Int,
        isLast: Boolean
    ): PositionEval {
        val linesEval = pos.lines.map { line ->
            LineEval(
                pv = line.pv,
                cp = line.cp,  // ✅ Уже нормализовано на сервере
                mate = line.mate,  // ✅ Уже нормализовано на сервере
                depth = line.depth,
                best = if (!isLast) pos.bestMove ?: line.pv.firstOrNull() else null
            )
        }

        return PositionEval(
            fen = fen,
            idx = idx,
            lines = linesEval.ifEmpty { listOf(LineEval(pv = emptyList(), cp = 0, best = "")) },
            bestMove = pos.bestMove ?: linesEval.firstOrNull()?.pv?.firstOrNull()
        )
    }

    /**
     * ✅ Правильная обработка mate: 0 для матовых позиций
     * Нормализует весь PositionDTO в белую перспективу по КОНКРЕТНОМУ FEN позиции:
     * если ход чёрных — инвертируем знаки у cp и mate для всех линий.
     * Используется ТОЛЬКО для ЛОКАЛЬНОГО движка.
     */
    private fun normalizeToWhitePOV(
        fen: String,
        pos: EngineClient.PositionDTO,
        idx: Int,
        isLast: Boolean
    ): PositionEval {
        val whiteToPlay = sideToMoveIsWhite(fen)

        // 🔍 DEBUG: Логируем входные данные
        val firstLine = pos.lines.firstOrNull()
        if (idx <= 3 || (idx >= pos.lines.size - 3)) {  // Логируем первые и последние 3 позиции
            Log.d(TAG, "📊 Normalize pos[$idx]: whiteToPlay=$whiteToPlay, RAW cp=${firstLine?.cp}, mate=${firstLine?.mate}")
        }

        val linesEval = pos.lines.map { line ->
            val cp = line.cp?.let { if (!whiteToPlay) -it else it }
            val mate = line.mate?.let { m ->
                when {
                    // mate: 0 означает, что сторона ХОД КОТОРОЙ уже заматована
                    m == 0 && !whiteToPlay -> 1  // Чёрные заматованы → белые выиграли (+M1)
                    m == 0 && whiteToPlay -> -1  // Белые заматованы → чёрные выиграли (-M1)
                    !whiteToPlay -> -m  // Обычная инверсия для перспективы чёрных
                    else -> m  // Перспектива белых без изменений
                }
            }

            // 🔍 DEBUG: Логируем нормализованные данные
            if (idx <= 3 || (idx >= pos.lines.size - 3)) {
                Log.d(TAG, "   → NORMALIZED cp=$cp, mate=$mate")
            }

            LineEval(
                pv = line.pv,
                cp = cp,
                mate = mate,
                depth = line.depth,
                best = if (!isLast) pos.bestMove ?: line.pv.firstOrNull() else null
            )
        }

        return PositionEval(
            fen = fen,
            idx = idx,
            lines = linesEval.ifEmpty { listOf(LineEval(pv = emptyList(), cp = 0, best = "")) },
            bestMove = pos.bestMove ?: linesEval.firstOrNull()?.pv?.firstOrNull()
        )
    }
}
