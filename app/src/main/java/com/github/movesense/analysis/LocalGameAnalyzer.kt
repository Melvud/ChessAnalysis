package com.github.movesense.analysis

import com.github.movesense.*
import com.github.movesense.api.LichessApiClient
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

        // Минимальная глубина cloud eval для использования
        private const val MIN_CLOUD_DEPTH = 18

        // КРИТИЧНО: Короткий таймаут - если API не ответил быстро, используем локальный движок
        private const val QUICK_API_TIMEOUT_MS = 3000L  // Максимум 3 секунды на ВСЕ API запросы

        // Таймаут на один запрос
        private const val SINGLE_REQUEST_TIMEOUT_MS = 1500L  // 1.5 секунды на запрос

        // Максимум параллельных запросов
        private const val MAX_PARALLEL_REQUESTS = 5  // Больше параллелизма для скорости
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

        // Собираем все уникальные FEN'ы
        val allFens = mutableSetOf<String>()
        allFens.add(startFen)
        parsed.forEach { allFens.add(it.afterFen) }

        Log.d(TAG, "🚀 Quick API check for ${allFens.size} positions...")

        // КРИТИЧНО: Запускаем API загрузку в фоне НЕ БЛОКИРУЯ основной поток
        // Если API быстрый (< 3 сек) - отлично, используем
        // Если медленный - просто продолжаем с локальным движком
        val apiJob = async(Dispatchers.IO) {
            try {
                withTimeout(QUICK_API_TIMEOUT_MS) {
                    val cloudMap = fetchCloudEvalsQuick(allFens.toList(), multiPv)
                    val openingMap = checkOpeningsQuick(allFens.toList())
                    Pair(cloudMap, openingMap)
                }
            } catch (e: TimeoutCancellationException) {
                Log.w(TAG, "⚠ API timeout, using local engine")
                Pair(emptyMap(), emptyMap())
            } catch (e: Exception) {
                Log.w(TAG, "⚠ API error: ${e.message}, using local engine")
                Pair(emptyMap(), emptyMap())
            }
        }

        // НЕ ЖДЕМ API! Продолжаем с тем что есть в кеше
        val (cloudEvalMap, openingMap) = try {
            // Пытаемся получить результат, но БЕЗ БЛОКИРОВКИ
            if (apiJob.isCompleted) {
                apiJob.await()
            } else {
                // API еще не готов - используем только кеш
                Log.d(TAG, "✓ API not ready, using cache only")
                Pair(
                    allFens.mapNotNull { fen ->
                        LichessApiClient.getCachedCloudEval(fen, multiPv)?.let { fen to it }
                    }.toMap(),
                    allFens.mapNotNull { fen ->
                        LichessApiClient.getCachedOpeningStatus(fen)?.let { fen to it }
                    }.toMap()
                )
            }
        } catch (e: Exception) {
            Pair(emptyMap(), emptyMap())
        }

        Log.d(TAG, "✓ Starting analysis: cloud=${cloudEvalMap.size}, openings=${openingMap.count { it.value }}")

        notify(progressId, 0, total, "evaluating", startedAt, onProgress, null, null, null, null, null, null, null)

        // 1) Evaluate all positions
        val positions = mutableListOf<EngineClient.PositionDTO>()

        // Start position
        val pos0 = evaluatePositionFromCache(
            fen = startFen,
            depth = depth,
            multiPv = multiPv,
            skillLevel = null,
            cloudEvalMap = cloudEvalMap,
            openingMap = openingMap
        )
        positions.add(pos0)

        // МГНОВЕННОЕ отображение стартовой позиции
        val whiteToPlayStart = sideToMoveIsWhite(startFen)
        val topLineStart = pos0.lines.firstOrNull()
        val evalCpStart = topLineStart?.cp?.let { if (!whiteToPlayStart) -it else it }
        val evalMateStart = topLineStart?.mate?.let { m ->
            when {
                m == 0 && !whiteToPlayStart -> 1
                m == 0 && whiteToPlayStart -> -1
                !whiteToPlayStart -> -m
                else -> m
            }
        }

        notify(
            id = progressId,
            done = 0,
            total = total,
            stage = "evaluating",
            startedAt = startedAt,
            onProgress = onProgress,
            etaMs = null,
            fen = startFen,
            san = null,
            cls = null,
            uci = null,
            evalCp = evalCpStart,
            evalMate = evalMateStart
        )

        // Evaluate each move's resulting position
        for (i in 0 until total) {
            val beforeFen = if (i == 0) startFen else parsed[i - 1].afterFen
            val afterFen = parsed[i].afterFen
            val san = parsed[i].san
            val uci = parsed[i].uci

            val posBefore = positions.last()

            val posAfter = evaluatePositionFromCache(
                fen = afterFen,
                depth = depth,
                multiPv = multiPv,
                skillLevel = null,
                cloudEvalMap = cloudEvalMap,
                openingMap = openingMap
            )
            positions.add(posAfter)

            val whiteToPlayAfter = sideToMoveIsWhite(afterFen)
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

        // Отменяем API job если он еще выполняется
        apiJob.cancel()

        notify(progressId, total, total, "postprocess", startedAt, onProgress, 0L, null, null, null, null, null, null)

        // 2) Build PositionEval list
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

        // 3) ACPL calculation
        val acpl = ACPL.calculateACPLFromPositionEvals(positionEvals)

        // 4) Win percentages
        val winPercents = positionEvals.map { pos ->
            val first = pos.lines.firstOrNull()
            if (first != null && (first.cp != null || first.mate != null)) {
                WinPercentage.getPositionWinPercentage(pos)
            } else {
                50.0
            }
        }

        // 5) Per-move accuracy
        val movesAccuracy = Accuracy.perMoveAccFromWin(winPercents)

        // 6) Accuracy weights
        val weightsAcc = getAccuracyWeights(winPercents)

        // 7) Player accuracy
        val whiteAcc = computePlayerAccuracy(movesAccuracy, weightsAcc, "white")
        val blackAcc = computePlayerAccuracy(movesAccuracy, weightsAcc, "black")

        // 8) Move classification
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

    /**
     * БЫСТРАЯ загрузка cloud eval - возвращает только то, что успело загрузиться
     */
    private suspend fun fetchCloudEvalsQuick(
        fens: List<String>,
        multiPv: Int
    ): Map<String, LichessApiClient.CloudEvalResponse> = coroutineScope {
        val result = mutableMapOf<String, LichessApiClient.CloudEvalResponse>()

        // Сначала берем все из кеша
        val fensToFetch = mutableListOf<String>()
        for (fen in fens) {
            val cached = LichessApiClient.getCachedCloudEval(fen, multiPv)
            if (cached != null && cached.depth >= MIN_CLOUD_DEPTH) {
                result[fen] = cached
            } else {
                fensToFetch.add(fen)
            }
        }

        if (fensToFetch.isEmpty()) {
            Log.d(TAG, "✓ All ${result.size} from cache")
            return@coroutineScope result
        }

        // Запускаем параллельные запросы БЕЗ ОЖИДАНИЯ ВСЕХ
        // Используем только то, что успело загрузиться
        val jobs = fensToFetch.chunked(MAX_PARALLEL_REQUESTS).flatMap { chunk ->
            chunk.map { fen ->
                async(Dispatchers.IO) {
                    try {
                        withTimeout(SINGLE_REQUEST_TIMEOUT_MS) {
                            val cloudEval = LichessApiClient.getCloudEval(fen, multiPv)
                            if (cloudEval != null && cloudEval.depth >= MIN_CLOUD_DEPTH) {
                                fen to cloudEval
                            } else {
                                null
                            }
                        }
                    } catch (e: Exception) {
                        null
                    }
                }
            }
        }

        // Собираем ВСЕ результаты за раз (быстро)
        jobs.mapNotNull { it.await() }.forEach { (fen, cloudEval) ->
            result[fen] = cloudEval
        }

        Log.d(TAG, "✓ API: ${result.size}/${fens.size} (${fensToFetch.size - result.size + fens.size - fensToFetch.size} from cache)")
        result
    }

    /**
     * БЫСТРАЯ проверка дебютов
     */
    private suspend fun checkOpeningsQuick(
        fens: List<String>
    ): Map<String, Boolean> = coroutineScope {
        val result = mutableMapOf<String, Boolean>()

        val fensToCheck = mutableListOf<String>()
        for (fen in fens) {
            val cached = LichessApiClient.getCachedOpeningStatus(fen)
            if (cached != null) {
                result[fen] = cached
            } else {
                fensToCheck.add(fen)
            }
        }

        if (fensToCheck.isEmpty()) {
            return@coroutineScope result
        }

        val jobs = fensToCheck.chunked(MAX_PARALLEL_REQUESTS).flatMap { chunk ->
            chunk.map { fen ->
                async(Dispatchers.IO) {
                    try {
                        withTimeout(SINGLE_REQUEST_TIMEOUT_MS) {
                            fen to LichessApiClient.isOpeningPosition(fen)
                        }
                    } catch (e: Exception) {
                        fen to false
                    }
                }
            }
        }

        jobs.forEach { job ->
            val (fen, isOpening) = job.await()
            result[fen] = isOpening
        }

        result
    }

    /**
     * Оценивает позицию используя предзагруженный кеш
     */
    private suspend fun evaluatePositionFromCache(
        fen: String,
        depth: Int,
        multiPv: Int,
        skillLevel: Int?,
        cloudEvalMap: Map<String, LichessApiClient.CloudEvalResponse>,
        openingMap: Map<String, Boolean>
    ): EngineClient.PositionDTO {
        // Проверяем cloud eval в кеше
        val cloudResult = cloudEvalMap[fen]

        // Если есть качественный cloud eval - используем НЕМЕДЛЕННО
        if (cloudResult != null && cloudResult.depth >= MIN_CLOUD_DEPTH && cloudResult.pvs.isNotEmpty()) {
            Log.d(TAG, "✓ Cloud eval: depth=${cloudResult.depth}")
            return cloudEvalToPositionDTO(cloudResult, multiPv)
        }

        // Проверяем дебютность из кеша
        val isOpening = openingMap[fen] ?: false

        // Определяем оптимальную глубину
        val adjustedDepth = when {
            isOpening -> min(depth, 8)  // Дебют - малая глубина
            cloudResult != null && cloudResult.depth >= 12 -> min(depth, 10)  // Есть частичный cloud eval
            else -> depth  // Обычная позиция
        }

        // Запускаем локальный движок
        return EngineClient.evaluateFenDetailed(fen, adjustedDepth, multiPv, skillLevel)
    }

    /**
     * Конвертирует CloudEvalResponse в PositionDTO
     */
    private fun cloudEvalToPositionDTO(
        cloud: LichessApiClient.CloudEvalResponse,
        multiPv: Int
    ): EngineClient.PositionDTO {
        val lines = cloud.pvs.take(multiPv).mapIndexed { index, pv ->
            val moves = pv.moves?.split(" ")?.filter { it.isNotBlank() } ?: emptyList()
            EngineClient.LineDTO(
                pv = moves,
                cp = pv.cp,
                mate = pv.mate,
                depth = cloud.depth,
                multiPv = index + 1
            )
        }

        val bestMove = lines.firstOrNull()?.pv?.firstOrNull()

        return EngineClient.PositionDTO(
            lines = lines.ifEmpty { listOf(EngineClient.LineDTO(pv = emptyList(), cp = 0)) },
            bestMove = bestMove
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
        // Для реалтайм используем ТОЛЬКО кеш - никаких сетевых запросов
        val cloudEvalMapBefore = mutableMapOf<String, LichessApiClient.CloudEvalResponse>()
        val cloudEvalMapAfter = mutableMapOf<String, LichessApiClient.CloudEvalResponse>()
        val openingMapBefore = mutableMapOf<String, Boolean>()
        val openingMapAfter = mutableMapOf<String, Boolean>()

        // Проверяем только кеш
        LichessApiClient.getCachedCloudEval(beforeFen, multiPv)?.let {
            if (it.depth >= MIN_CLOUD_DEPTH) cloudEvalMapBefore[beforeFen] = it
        }
        LichessApiClient.getCachedCloudEval(afterFen, multiPv)?.let {
            if (it.depth >= MIN_CLOUD_DEPTH) cloudEvalMapAfter[afterFen] = it
        }
        LichessApiClient.getCachedOpeningStatus(beforeFen)?.let {
            openingMapBefore[beforeFen] = it
        }
        LichessApiClient.getCachedOpeningStatus(afterFen)?.let {
            openingMapAfter[afterFen] = it
        }

        val posBefore = evaluatePositionFromCache(
            beforeFen, depth, multiPv, skillLevel, cloudEvalMapBefore, openingMapBefore
        )
        val posAfter = evaluatePositionFromCache(
            afterFen, depth, multiPv, skillLevel, cloudEvalMapAfter, openingMapAfter
        )

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