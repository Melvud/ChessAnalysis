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

        // 1) Evaluate all positions (including start)
        val positions = mutableListOf<EngineClient.PositionDTO>()

        // Start position
        val pos0 = EngineClient.evaluateFenDetailed(startFen, depth, multiPv, null)
        positions.add(pos0)

        notify(progressId, 0, total, "evaluating", startedAt, onProgress, null, null, null, null, null, null, null)

        // Evaluate each move's resulting position
        for (i in 0 until total) {
            val beforeFen = if (i == 0) startFen else parsed[i - 1].afterFen
            val afterFen = parsed[i].afterFen
            val san = parsed[i].san
            val uci = parsed[i].uci

            val posBefore = positions.last()
            val posAfter = EngineClient.evaluateFenDetailed(afterFen, depth, multiPv, null)
            positions.add(posAfter)

            // Нормализуем топ-линию для ПОСЛЕ-хода по её FEN
            val whiteToPlayAfter = sideToMoveIsWhite(afterFen)
            val topLine = posAfter.lines.firstOrNull()

            // КРИТИЧЕСКОЕ ИСПРАВЛЕНИЕ: правильная обработка мата
            val evalCp = topLine?.cp?.let { if (!whiteToPlayAfter) -it else it }
            val evalMate = topLine?.mate?.let { m ->
                when {
                    // mate: 0 означает, что сторона ХОД КОТОРОЙ уже заматована
                    m == 0 && !whiteToPlayAfter -> 1  // Чёрные заматованы → белые выиграли (+M1)
                    m == 0 && whiteToPlayAfter -> -1  // Белые заматованы → чёрные выиграли (-M1)
                    !whiteToPlayAfter -> -m  // Обычная инверсия для перспективы чёрных
                    else -> m  // Перспектива белых без изменений
                }
            }

            Log.d(TAG, "Position after move $i: whiteToPlay=$whiteToPlayAfter, raw(cp=${topLine?.cp}, mate=${topLine?.mate}) -> norm(cp=$evalCp, mate=$evalMate)")

            val cls = classifyMoveUsingMoveClassifier(
                beforeFen = beforeFen,
                afterFen = afterFen,
                posBefore = posBefore,
                posAfter = posAfter,
                uciMove = uci
            )

            val doneNow = i + 1
            val elapsed = System.currentTimeMillis() - startedAt
            val eta = if (doneNow > 0) {
                val perMove = elapsed / doneNow.toDouble()
                ((total - doneNow) * perMove).toLong()
            } else null

            notify(
                id = progressId,
                done = doneNow,
                total = total,
                stage = "evaluating",
                startedAt = startedAt,
                onProgress = onProgress,
                etaMs = eta,
                fen = afterFen,
                san = san,
                cls = cls.name,
                uci = uci,
                evalCp = evalCp,
                evalMate = evalMate
            )
        }

        notify(progressId, total, total, "postprocess", startedAt, onProgress, 0L, null, null, null, null, null, null)

        // 2) Build PositionEval list с ПРАВИЛЬНОЙ инверсией (индекс позиции = номер полухода)
        val fens = listOf(startFen) + parsed.map { it.afterFen }
        val uciMoves = parsed.map { it.uci }

        val positionEvals: List<PositionEval> = positions.mapIndexed { idx, pos ->
            val currentFen = fens[idx]
            normalizeToWhitePOV(
                fen = currentFen,
                pos = pos,
                idx = idx,
                isLast = idx == positions.lastIndex
            )
        }

        // 3) ACPL calculation — ИСПОЛЬЗУЕМ НОРМАЛИЗОВАННЫЕ PositionEval
        val acpl = ACPL.calculateACPLFromPositionEvals(positionEvals)

        // 4) Win percentages — приоритет mate над cp внутри WinPercentage
        val winPercents = positionEvals.map { pos ->
            val first = pos.lines.firstOrNull()
            if (first != null && (first.cp != null || first.mate != null)) {
                WinPercentage.getPositionWinPercentage(pos)
            } else {
                50.0
            }
        }

        // 5) Per-move accuracy from win percentages
        val movesAccuracy = Accuracy.perMoveAccFromWin(winPercents)

        // 6) Accuracy weights
        val weightsAcc = getAccuracyWeights(winPercents)

        // 7) Player accuracy
        val whiteAcc = computePlayerAccuracy(movesAccuracy, weightsAcc, "white")
        val blackAcc = computePlayerAccuracy(movesAccuracy, weightsAcc, "black")

        // 8) Move classification (по нормализованным позициям)
        val classifiedPositions = MoveClassification.getMovesClassification(
            positionEvals,
            uciMoves,
            fens
        )

        // 9) Build move reports
        val moves = buildMoveReports(
            classifiedPositions,
            fens,
            uciMoves,
            winPercents,
            movesAccuracy,
            parsed.map { it.san }
        )

        // 10) Estimated Elo
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

        // КРИТИЧЕСКОЕ ИСПРАВЛЕНИЕ: Stockfish дает оценку со стороны того, кто ходит
        val whiteToPlayAfter = afterFen.split(" ").getOrNull(1) == "w"

        val topLine = posAfter.lines.firstOrNull()

        // Нормализуем к перспективе белых (+ = белые выигрывают, - = чёрные выигрывают)
        val cpAfter = if (whiteToPlayAfter) topLine?.cp else topLine?.cp?.let { -it }
        val mateAfter = topLine?.mate?.let { m ->
            when {
                m == 0 && whiteToPlayAfter -> -1  // Белые заматованы
                m == 0 && !whiteToPlayAfter -> 1  // Чёрные заматованы
                whiteToPlayAfter -> m  // Ход белых: без инверсии
                else -> -m  // Ход чёрных: инвертируем
            }
        }

        val evalAfter = when {
            mateAfter != null -> if (mateAfter > 0) 30f else -30f
            cpAfter != null -> cpAfter.toFloat() / 100f
            else -> 0f
        }

        // Нормализуем все линии к перспективе белых
        val linesAfter = posAfter.lines.map { line ->
            val normalizedCp = if (whiteToPlayAfter) line.cp else line.cp?.let { -it }
            val normalizedMate = line.mate?.let { m ->
                when {
                    m == 0 && whiteToPlayAfter -> -1
                    m == 0 && !whiteToPlayAfter -> 1
                    whiteToPlayAfter -> m
                    else -> -m
                }
            }
            EngineClient.LineDTO(
                pv = line.pv,
                cp = normalizedCp,
                mate = normalizedMate,
                depth = line.depth,
                multiPv = line.multiPv
            )
        }

        // Нормализуем позицию ПЕРЕД ходом для классификации
        val whiteToPlayBefore = beforeFen.split(" ").getOrNull(1) == "w"

        val posEvalBefore = PositionEval(
            fen = beforeFen,
            idx = 0,
            lines = posBefore.lines.map { line ->
                val cp = if (whiteToPlayBefore) line.cp else line.cp?.let { -it }
                val mate = line.mate?.let { m ->
                    when {
                        m == 0 && whiteToPlayBefore -> -1
                        m == 0 && !whiteToPlayBefore -> 1
                        whiteToPlayBefore -> m
                        else -> -m
                    }
                }
                LineEval(pv = line.pv, cp = cp, mate = mate, depth = line.depth, best = line.pv.firstOrNull())
            },
            bestMove = posBefore.bestMove
        )

        val posEvalAfter = PositionEval(
            fen = afterFen,
            idx = 1,
            lines = posAfter.lines.map { line ->
                val cp = if (whiteToPlayAfter) line.cp else line.cp?.let { -it }
                val mate = line.mate?.let { m ->
                    when {
                        m == 0 && whiteToPlayAfter -> -1
                        m == 0 && !whiteToPlayAfter -> 1
                        whiteToPlayAfter -> m
                        else -> -m
                    }
                }
                LineEval(pv = line.pv, cp = cp, mate = mate, depth = line.depth, best = null)
            },
            bestMove = null
        )

        val classified = MoveClassification.getMovesClassification(
            listOf(posEvalBefore, posEvalAfter),
            listOf(uciMove),
            listOf(beforeFen, afterFen)
        )

        val cls = classified.getOrNull(1)?.moveClassification ?: MoveClass.OKAY

        EngineClient.MoveRealtimeResult(
            evalAfter = evalAfter,
            moveClass = cls,
            bestMove = posBefore.bestMove,
            lines = linesAfter.take(3)
        )
    }

    private fun classifyMoveUsingMoveClassifier(
        beforeFen: String,
        afterFen: String,
        posBefore: EngineClient.PositionDTO,
        posAfter: EngineClient.PositionDTO,
        uciMove: String
    ): MoveClass {
        return try {
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

    // ====== ДОПОЛНИТЕЛЬНЫЕ ВСПОМОГАТЕЛЬНЫЕ ФУНКЦИИ ======

    private fun sideToMoveIsWhite(fen: String): Boolean =
        fen.split(" ").getOrNull(1) == "w"

    /**
     * Нормализует весь PositionDTO в белую перспективу по КОНКРЕТНОМУ FEN позиции:
     * если ход чёрных — инвертируем знаки у cp и mate для всех линий.
     * КРИТИЧЕСКОЕ ИСПРАВЛЕНИЕ: правильная обработка mate: 0
     */
    private fun normalizeToWhitePOV(
        fen: String,
        pos: EngineClient.PositionDTO,
        idx: Int,
        isLast: Boolean
    ): PositionEval {
        val whiteToPlay = sideToMoveIsWhite(fen)

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