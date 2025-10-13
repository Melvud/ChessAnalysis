package com.github.movesense.analysis

import com.github.movesense.*
import kotlinx.coroutines.*
import java.util.UUID
import android.util.Log
import com.github.movesense.engine.StockfishBridge
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class LocalGameAnalyzer(
    private val progressHook: (id: String, percent: Double?, stage: String?) -> Unit = { _, _, _ -> }
) {

    companion object {
        private const val TAG = "LocalGameAnalyzer"
        private val fenCache = ConcurrentHashMap<String, CachedPosition>()
        private const val MAX_CACHE_SIZE = 5000
    }

    private data class CachedPosition(
        val position: EngineClient.PositionDTO,
        val timestamp: Long = System.currentTimeMillis()
    )

    private fun isTerminalPosition(fen: String): Boolean {
        return try {
            val legalMoves = PgnChess.getLegalMoves(fen)
            legalMoves.isEmpty()
        } catch (e: Exception) {
            false
        }
    }

    private fun createTerminalPositionEval(fen: String): EngineClient.PositionDTO {
        val lines = listOf(
            EngineClient.LineDTO(
                pv = emptyList(),
                cp = null,
                mate = 0,
                depth = 0,
                multiPv = 1
            )
        )
        return EngineClient.PositionDTO(lines, null)
    }

    private fun getCacheKey(fen: String, depth: Int): String = "$fen|$depth"

    private fun cleanupCache() {
        if (fenCache.size > MAX_CACHE_SIZE) {
            val now = System.currentTimeMillis()
            val toRemove = fenCache.entries
                .filter { now - it.value.timestamp > 3600_000 }
                .take(MAX_CACHE_SIZE / 2)
                .map { it.key }
            toRemove.forEach { fenCache.remove(it) }
            Log.d(TAG, "🧹 Cache cleaned: removed ${toRemove.size} old entries")
        }
    }

    private suspend fun evaluatePositionSmart(
        fen: String,
        depth: Int,
        multiPv: Int,
        skillLevel: Int?
    ): EngineClient.PositionDTO {
        val (isTerminal, cloudEval) = coroutineScope {
            val terminalJob = async(Dispatchers.Default) {
                isTerminalPosition(fen)
            }
            val cloudJob = async(Dispatchers.IO) {
                CloudEvalCache.getEval(fen)
            }

            val cloud = withTimeoutOrNull(500) { cloudJob.await() }
            terminalJob.await() to cloud
        }

        if (isTerminal) {
            Log.i(TAG, "🏁 Terminal position")
            return createTerminalPositionEval(fen)
        }

        if (cloudEval != null) {
            val cloudDepth = cloudEval.lines.firstOrNull()?.depth ?: 0
            if (cloudDepth >= depth - 2) {
                Log.d(TAG, "☁️ Using cloud eval (depth=$cloudDepth)")
                return cloudEval
            }
        }

        val cacheKey = getCacheKey(fen, depth)
        fenCache[cacheKey]?.let {
            Log.d(TAG, "💾 Local cache hit")
            return it.position
        }

        Log.d(TAG, "🔧 Local analysis: depth=$depth")
        val localResult = EngineClient.evaluateFenDetailed(fen, depth, multiPv, skillLevel)

        fenCache[cacheKey] = CachedPosition(localResult)
        cleanupCache()

        return localResult
    }

    fun calculateSmartDepth(
        moveNumber: Int,
        totalMoves: Int,
        isOpening: Boolean,
        lastEval: EngineClient.PositionDTO?,
        isTactical: Boolean,
        pieceCount: Int
    ): Int {
        var baseDepth = when {
            isOpening -> 12
            pieceCount <= 10 -> 16
            pieceCount <= 20 -> 14
            else -> 12
        }

        if (isTactical) baseDepth += 2

        val progress = moveNumber.toDouble() / totalMoves
        if (progress > 0.7) baseDepth += 1

        return baseDepth.coerceIn(10, 20)
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

        val cores = Runtime.getRuntime().availableProcessors()
        val optimalWorkers = workersNb.coerceIn(1, cores)
        val threadsPerWorker = max(1, cores / optimalWorkers)

        Log.i(TAG, "🚀 Analysis config: $optimalWorkers workers × $threadsPerWorker threads/worker (total: ${optimalWorkers * threadsPerWorker} threads)")

        if (EngineClient.engineMode.value == EngineClient.EngineMode.NATIVE) {
            StockfishBridge.reconfigurePool(optimalWorkers, threadsPerWorker)
            delay(200) // Даём время на реконфигурацию
            Log.i(TAG, "♻️ ${StockfishBridge.getPoolStats()}")
        }

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

        val isStartOpening = Openings.findOpening(startFen.split(" ")[0]) != null
        val startDepth = if (isStartOpening) 10 else 14

        val pos0 = evaluatePositionSmart(startFen, startDepth, multiPv, null)

        val allFens = parsed.map { it.afterFen }

        // 🔥 КРИТИЧНО: Потокобезопасный массив результатов
        val positions = Array<EngineClient.PositionDTO?>(total + 1) { null }
        positions[0] = pos0

        val processedCount = AtomicInteger(0)

        notify(progressId, 0, total, "evaluating", startedAt, onProgress, null, null, null, null, null, null, null)

        // 🔥 КРИТИЧНО: Разбиваем на chunks строго по количеству воркеров
        val chunkSize = (total.toDouble() / optimalWorkers).toInt().coerceAtLeast(1)

        Log.i(TAG, "📦 Splitting $total positions into ${allFens.chunked(chunkSize).size} chunks of ~$chunkSize each")

        // 🔥 ПАРАЛЛЕЛЬНАЯ ОБРАБОТКА
        allFens.chunked(chunkSize).mapIndexed { workerIndex, chunk ->
            async(Dispatchers.IO) {
                val workerId = workerIndex + 1
                Log.i(TAG, "⚙️ Worker $workerId started: ${chunk.size} positions")

                chunk.forEachIndexed { localIndex, fen ->
                    val globalIndex = workerIndex * chunkSize + localIndex

                    try {
                        val currentFenBoard = fen.split(" ")[0]
                        val isOpening = Openings.findOpening(currentFenBoard) != null
                        val pieceCount = currentFenBoard.count { it.isLetter() }

                        val san = parsed[globalIndex].san
                        val isTactical = san.contains("x") || san.contains("+") || san.contains("#")

                        val currentDepth = calculateSmartDepth(
                            moveNumber = globalIndex + 1,
                            totalMoves = total,
                            isOpening = isOpening,
                            lastEval = null,
                            isTactical = isTactical,
                            pieceCount = pieceCount
                        )

                        val posAfter = evaluatePositionSmart(fen, currentDepth, multiPv, null)

                        positions[globalIndex + 1] = posAfter

                        val doneNow = processedCount.incrementAndGet()
                        val elapsed = System.currentTimeMillis() - startedAt
                        val eta = if (doneNow > 0) {
                            val perMove = elapsed / doneNow.toDouble()
                            ((total - doneNow) * perMove).toLong()
                        } else null

                        // 🆕 Для многопоточного режима НЕ отправляем FEN/UCI
                        if (optimalWorkers == 1) {
                            val whiteToPlayAfter = fen.split(" ").getOrNull(1) == "w"
                            val topLine = posAfter.lines.firstOrNull()

                            val evalCp = topLine?.cp?.let { if (!whiteToPlayAfter) -it else it }
                            val evalMate = topLine?.mate?.let { m ->
                                when {
                                    m == 0 && !whiteToPlayAfter -> 1
                                    m == 0 && whiteToPlayAfter -> -1
                                    !whiteToPlayAfter -> -m
                                    else -> m
                                }
                            }

                            notify(
                                id = progressId,
                                done = doneNow,
                                total = total,
                                stage = "evaluating",
                                startedAt = startedAt,
                                onProgress = onProgress,
                                etaMs = eta,
                                fen = fen,
                                san = san,
                                cls = null,
                                uci = parsed[globalIndex].uci,
                                evalCp = evalCp,
                                evalMate = evalMate
                            )
                        } else {
                            notify(
                                id = progressId,
                                done = doneNow,
                                total = total,
                                stage = "evaluating",
                                startedAt = startedAt,
                                onProgress = onProgress,
                                etaMs = eta,
                                fen = null,
                                san = null,
                                cls = null,
                                uci = null,
                                evalCp = null,
                                evalMate = null
                            )
                        }

                        if (doneNow % 10 == 0 || doneNow == total) {
                            Log.d(TAG, "✅ Worker $workerId: ${doneNow}/$total (${(doneNow * 100.0 / total).toInt()}%)")
                        }

                    } catch (e: Exception) {
                        Log.e(TAG, "❌ Worker $workerId failed at position $globalIndex", e)
                    }
                }

                Log.i(TAG, "🏁 Worker $workerId finished: ${chunk.size} positions")
            }
        }.awaitAll()

        notify(progressId, total, total, "postprocess", startedAt, onProgress, 0L, null, null, null, null, null, null)

        val positionsList = positions.filterNotNull().toList()

        val fens = listOf(startFen) + parsed.map { it.afterFen }
        val uciMoves = parsed.map { it.uci }

        val positionEvals: List<PositionEval> = positionsList.mapIndexed { idx, pos ->
            normalizeToWhitePOV(
                fen = fens[idx],
                pos = pos,
                idx = idx,
                isLast = idx == positionsList.lastIndex
            )
        }

        val acpl = ACPL.calculateACPLFromPositionEvals(positionEvals)
        val winPercents = positionEvals.map { pos ->
            val first = pos.lines.firstOrNull()
            if (first != null && (first.cp != null || first.mate != null)) {
                WinPercentage.getPositionWinPercentage(pos)
            } else {
                50.0
            }
        }

        val movesAccuracy = Accuracy.perMoveAccFromWin(winPercents)
        val weightsAcc = getAccuracyWeights(winPercents)
        val whiteAcc = computePlayerAccuracy(movesAccuracy, weightsAcc, "white")
        val blackAcc = computePlayerAccuracy(movesAccuracy, weightsAcc, "black")

        val classifiedPositions = MoveClassification.getMovesClassification(
            positionEvals,
            uciMoves,
            fens
        )

        val moves = buildMoveReports(
            classifiedPositions,
            fens,
            uciMoves,
            winPercents,
            movesAccuracy,
            parsed.map { it.san }
        )

        val tagsHeader = PgnChess.headerFromPgn(pgn)
        val hdr = header ?: tagsHeader
        val est = EstimateElo.computeEstimatedElo(positionEvals, hdr.whiteElo?.plus(200), hdr.blackElo?.plus(200))

        notify(progressId, total, total, "done", startedAt, onProgress, 0L, null, null, null, null, null, null)

        val totalTime = System.currentTimeMillis() - startedAt
        Log.i(TAG, "🎉 Parallel analysis complete! Time: ${totalTime}ms, Workers: $optimalWorkers, Speed: ${(total * 1000.0 / totalTime).toInt()} pos/sec")

        FullReport(
            header = hdr,
            positions1 = positionsList.map { pos ->
                EngineClient.PositionDTO(
                    lines = pos.lines.map { line ->
                        EngineClient.LineDTO(line.pv, line.cp, line.mate, line.depth, line.multiPv)
                    },
                    bestMove = pos.bestMove
                )
            },
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
        val posBefore = evaluatePositionSmart(beforeFen, depth, multiPv, skillLevel)
        val posAfter = evaluatePositionSmart(afterFen, depth, multiPv, skillLevel)

        val whiteToPlayAfter = afterFen.split(" ").getOrNull(1) == "w"
        val topLine = posAfter.lines.firstOrNull()

        val cpAfter = if (whiteToPlayAfter) topLine?.cp else topLine?.cp?.let { -it }
        val mateAfter = topLine?.mate?.let { m ->
            when {
                m == 0 && whiteToPlayAfter -> -1
                m == 0 && !whiteToPlayAfter -> 1
                whiteToPlayAfter -> m
                else -> -m
            }
        }

        val evalAfter = when {
            mateAfter != null -> if (mateAfter > 0) 30f else -30f
            cpAfter != null -> cpAfter.toFloat() / 100f
            else -> 0f
        }

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
                    m == 0 && !whiteToPlay -> 1
                    m == 0 && whiteToPlay -> -1
                    !whiteToPlay -> -m
                    else -> m
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