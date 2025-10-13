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
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class LocalGameAnalyzer(
    private val progressHook: (id: String, percent: Double?, stage: String?) -> Unit = { _, _, _ -> }
) {

    companion object {
        private const val TAG = "LocalGameAnalyzer"

        // Кеш для FEN оценок
        private val fenCache = ConcurrentHashMap<String, CachedPosition>()
        private const val MAX_CACHE_SIZE = 5000
    }

    private data class CachedPosition(
        val position: EngineClient.PositionDTO,
        val timestamp: Long = System.currentTimeMillis()
    )

    // Проверка, является ли позиция терминальной (мат/пат)
    private fun isTerminalPosition(fen: String): Boolean {
        return try {
            // Используем PgnChess для проверки
            val legalMoves = PgnChess.getLegalMoves(fen)
            legalMoves.isEmpty()
        } catch (e: Exception) {
            false
        }
    }

    // Создать пустую оценку для терминальной позиции
    private fun createTerminalPositionEval(fen: String): EngineClient.PositionDTO {
        // Для мата/пата возвращаем mate 0
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

    // Адаптивная глубина в зависимости от стадии партии
    private fun adaptiveDepth(moveNumber: Int, totalMoves: Int, baseDepth: Int): Int {
        return when {
            moveNumber < 10 -> (baseDepth - 4).coerceAtLeast(10)  // Дебют: быстрее
            moveNumber > totalMoves - 10 -> (baseDepth - 2).coerceAtLeast(12)  // Эндшпиль: средне
            else -> baseDepth  // Миттельшпиль: полная глубина
        }
    }

    // Получить кеш-ключ
    private fun getCacheKey(fen: String, depth: Int): String = "$fen|$depth"

    // Очистка старого кеша
    private fun cleanupCache() {
        if (fenCache.size > MAX_CACHE_SIZE) {
            val now = System.currentTimeMillis()
            val toRemove = fenCache.entries
                .filter { now - it.value.timestamp > 3600_000 } // Старше 1 часа
                .take(MAX_CACHE_SIZE / 2)
                .map { it.key }
            toRemove.forEach { fenCache.remove(it) }
            Log.d(TAG, "🧹 Cache cleaned: removed ${toRemove.size} old entries")
        }
    }

    // Умная оценка позиции: только кеш и локальный анализ
    private suspend fun evaluatePositionSmart(
        fen: String,
        depth: Int,
        multiPv: Int,
        skillLevel: Int?
    ): EngineClient.PositionDTO {
        // 0. Проверяем, не является ли позиция терминальной
        if (isTerminalPosition(fen)) {
            Log.i(TAG, "🏁 Terminal position detected (mate/stalemate), skipping analysis")
            return createTerminalPositionEval(fen)
        }

        // 1. Проверяем кеш
        val cacheKey = getCacheKey(fen, depth)
        fenCache[cacheKey]?.let {
            Log.d(TAG, "💾 Cache hit for position")
            return it.position
        }

        // 2. Локальный анализ
        Log.d(TAG, "🔧 Local analysis: depth=$depth")
        val localResult = EngineClient.evaluateFenDetailed(fen, depth, multiPv, skillLevel)

        // Кешируем результат
        fenCache[cacheKey] = CachedPosition(localResult)
        cleanupCache()

        return localResult
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
        notify(progressId, 0, total, "evaluating", startedAt, onProgress, null, null, null, null, null, null, null)

        val positions = mutableListOf<EngineClient.PositionDTO>()
        var lastEval: Float? = null

        // ОПТИМИЗАЦИЯ: Анализируем стартовую позицию с пониженной глубиной для дебюта
        val isStartOpening = Openings.findOpening(startFen.split(" ")[0]) != null
        val startDepth = if (isStartOpening) 10 else calculateSmartDepth(0, total, isStartOpening, null, false, 32)

        Log.i(TAG, "🎯 Analyzing start position with depth=$startDepth")
        val pos0 = evaluatePositionSmart(startFen, startDepth, multiPv, null)
        positions.add(pos0)

        // Evaluate each move's resulting position with smart adaptive depth
        for (i in 0 until total) {
            val beforeFen = if (i == 0) startFen else parsed[i - 1].afterFen
            val afterFen = parsed[i].afterFen
            val san = parsed[i].san
            val uci = parsed[i].uci

            // ОПТИМИЗАЦИЯ: Умная адаптивная глубина
            val currentFenBoard = afterFen.split(" ")[0]
            val isOpening = Openings.findOpening(currentFenBoard) != null
            val pieceCount = currentFenBoard.count { it.isLetter() }
            val isTactical = san.contains("x") || san.contains("+") || san.contains("#")

            val currentDepth = calculateSmartDepth(
                moveNumber = i + 1,
                totalMoves = total,
                isOpening = isOpening,
                lastEval = lastEval,
                isTactical = isTactical,
                pieceCount = pieceCount
            )

            Log.d(TAG, "📍 Move ${i + 1}/$total: depth=$currentDepth (opening=$isOpening, tactical=$isTactical, pieces=$pieceCount)")

            val posBefore = positions.last()
            val posAfter = evaluatePositionSmart(afterFen, currentDepth, multiPv, null)
            positions.add(posAfter)

            // Update last eval for next iteration
            val whiteToPlayAfter = sideToMoveIsWhite(afterFen)
            val topLine = posAfter.lines.firstOrNull()

            lastEval = when {
                topLine?.mate != null -> if (topLine.mate!! > 0) 30f else -30f
                topLine?.cp != null -> (if (whiteToPlayAfter) topLine.cp!! else -topLine.cp!!).toFloat() / 100f
                else -> 0f
            }

            val evalCp = topLine?.cp?.let { if (!whiteToPlayAfter) -it else it }
            val evalMate = topLine?.mate?.let { m ->
                when {
                    m == 0 && !whiteToPlayAfter -> 1
                    m == 0 && whiteToPlayAfter -> -1
                    !whiteToPlayAfter -> -m
                    else -> m
                }
            }

            Log.d(TAG, "Position after move $i: whiteToPlay=$whiteToPlayAfter, eval=${lastEval}, depth=$currentDepth")

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

        // Build PositionEval list with proper inversion
        val fens = listOf(startFen) + parsed.map { it.afterFen }
        val uciMoves = parsed.map { it.uci }

        val positionEvals: List<PositionEval> = positions.mapIndexed { idx, pos ->
            normalizeToWhitePOV(
                fen = fens[idx],
                pos = pos,
                idx = idx,
                isLast = idx == positions.lastIndex
            )
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
            fens
        )

        // Build move reports
        val moves = buildMoveReports(
            classifiedPositions,
            fens,
            uciMoves,
            winPercents,
            movesAccuracy,
            parsed.map { it.san }
        )

        // Estimated Elo
        val tagsHeader = PgnChess.headerFromPgn(pgn)
        val hdr = header ?: tagsHeader
        val est = EstimateElo.computeEstimatedElo(positionEvals, hdr.whiteElo?.plus(200), hdr.blackElo?.plus(200))

        notify(progressId, total, total, "done", startedAt, onProgress, 0L, null, null, null, null, null, null)

        val totalTime = System.currentTimeMillis() - startedAt
        Log.i(TAG, "✅ Analysis complete! Time: ${totalTime}ms, Positions: ${positionEvals.size}")

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
                    m == 0 && !whiteToPlay -> 1  // Чёрные заматованы
                    m == 0 && whiteToPlay -> -1  // Белые заматованы
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