package com.github.movesense.analysis

import android.content.Context
import com.github.movesense.*
import kotlinx.coroutines.*
import java.util.UUID
import android.util.Log
import com.github.movesense.engine.EnginePool
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

        // ПУЛ ДВИЖКОВ - КРИТИЧНО!
        private var enginePool: EnginePool? = null

        fun initializePool(context: Context, poolSize: Int = 3) {
            if (enginePool == null) {
                enginePool = EnginePool.getInstance(context, poolSize)
                Log.i(TAG, "✅ Engine pool initialized with $poolSize workers")
            }
        }

        fun shutdownPool() {
            enginePool?.shutdown()
            enginePool = null
        }
        private val lichessClient = OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()

        private val json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            coerceInputValues = true
        }
    }


    private data class CachedPosition(
        val position: EngineClient.PositionDTO,
        val timestamp: Long = System.currentTimeMillis()
    )

    @Serializable
    data class LichessCloudEval(
        val fen: String? = null,
        val knodes: Int? = null,
        val depth: Int? = null,
        val pvs: List<LichessPv>? = null
    )

    @Serializable
    data class LichessPv(
        val moves: String? = null,
        val cp: Int? = null,
        val mate: Int? = null
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

    // Попытка получить оценку из Lichess Cloud
    private suspend fun tryLichessCloudEval(fen: String, multiPv: Int): EngineClient.PositionDTO? = withContext(Dispatchers.IO) {
        return@withContext try {
            val encodedFen = java.net.URLEncoder.encode(fen, "UTF-8")
            val url = "https://lichess.org/api/cloud-eval?fen=$encodedFen&multiPv=$multiPv"

            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .get()
                .build()

            val response = lichessClient.newCall(request).execute()

            if (!response.isSuccessful) {
                Log.d(TAG, "Lichess API returned ${response.code}")
                return@withContext null
            }

            val body = response.body?.string() ?: return@withContext null

            val cloudEval = json.decodeFromString<LichessCloudEval>(body)

            if (cloudEval.pvs.isNullOrEmpty()) {
                Log.d(TAG, "Lichess: no cloud eval for this position")
                return@withContext null
            }

            // Конвертируем Lichess формат в наш PositionDTO
            val lines = cloudEval.pvs.mapIndexed { idx, pv ->
                val moves = pv.moves?.trim()?.split(" ") ?: emptyList()
                EngineClient.LineDTO(
                    pv = moves,
                    cp = pv.cp,
                    mate = pv.mate,
                    depth = cloudEval.depth,
                    multiPv = idx + 1
                )
            }

            val bestMove = lines.firstOrNull()?.pv?.firstOrNull()

            Log.i(TAG, "✅ Lichess cloud eval: depth=${cloudEval.depth}, knodes=${cloudEval.knodes}")

            EngineClient.PositionDTO(lines, bestMove)

        } catch (e: Exception) {
            Log.d(TAG, "Lichess API error: ${e.message}")
            null
        }
    }

    // ПАКЕТНАЯ загрузка из Lichess Cloud - параллельно!
    private suspend fun batchLoadLichessEvals(
        fens: List<String>,
        multiPv: Int
    ): Map<String, EngineClient.PositionDTO> = coroutineScope {
        Log.i(TAG, "🌐 Batch loading ${fens.size} positions from Lichess Cloud...")

        val results = fens.map { fen ->
            async(Dispatchers.IO) {
                delay((0..100).random().toLong()) // Небольшая рандомизация для rate limit
                fen to tryLichessCloudEval(fen, multiPv)
            }
        }.awaitAll()

        val successCount = results.count { it.second != null }
        Log.i(TAG, "✅ Lichess: loaded $successCount/${fens.size} positions from cloud")

        results.mapNotNull { (fen, result) ->
            result?.let { fen to it }
        }.toMap()
    }

    // Умная оценка позиции: сначала кеш, потом Lichess, потом локально
    private suspend fun evaluatePositionSmart(
        fen: String,
        depth: Int,
        multiPv: Int,
        skillLevel: Int?,
        lichessPreloaded: Map<String, EngineClient.PositionDTO> = emptyMap()
    ): EngineClient.PositionDTO {
        // 0. Проверяем терминальную позицию
        if (isTerminalPosition(fen)) {
            Log.i(TAG, "🏁 Terminal position detected")
            return createTerminalPositionEval(fen)
        }

        // 1. Проверяем кеш
        val cacheKey = getCacheKey(fen, depth)
        fenCache[cacheKey]?.let {
            Log.d(TAG, "💾 Cache hit")
            return it.position
        }

        // 2. Проверяем предзагруженные Lichess данные
        lichessPreloaded[fen]?.let { cloudResult ->
            fenCache[cacheKey] = CachedPosition(cloudResult)
            cleanupCache()
            return cloudResult
        }

        // 3. Пробуем Lichess Cloud (если не было предзагрузки)
        if (skillLevel == null && lichessPreloaded.isEmpty()) {
            tryLichessCloudEval(fen, multiPv)?.let { cloudResult ->
                fenCache[cacheKey] = CachedPosition(cloudResult)
                cleanupCache()
                return cloudResult
            }
        }

        // 4. КРИТИЧНО: Локальный анализ ЧЕРЕЗ ПУЛ!
        Log.d(TAG, "🔧 Pool analysis: depth=$depth")

        val pool = enginePool ?: run {
            // Fallback на старый метод если пул не инициализирован
            Log.w(TAG, "⚠️ Pool not initialized, using fallback")
            return EngineClient.evaluateFenDetailed(fen, depth, multiPv, skillLevel)
        }

        val localResult = pool.analyzePosition(fen, depth, multiPv)

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

        // БАТЧИНГ: Предзагрузка всех позиций из Lichess параллельно
        val allFens = listOf(startFen) + parsed.map { it.afterFen }

        Log.i(TAG, "🚀 Starting batch preload from Lichess for ${allFens.size} positions...")
        val lichessPreloaded = batchLoadLichessEvals(allFens, multiPv)
        Log.i(TAG, "✅ Preload complete: ${lichessPreloaded.size} positions cached")

        notify(progressId, 0, total, "evaluating", startedAt, onProgress, null, null, null, null, null, null, null)

        // 1) Evaluate all positions with adaptive depth and smart caching
        val positions = mutableListOf<EngineClient.PositionDTO>()

        // Start position with adaptive depth
        val startDepth = adaptiveDepth(0, total, depth)
        Log.i(TAG, "🎯 Analyzing start position with depth=$startDepth")
        val pos0 = evaluatePositionSmart(startFen, startDepth, multiPv, null, lichessPreloaded)
        positions.add(pos0)

        // Evaluate each move's resulting position with adaptive depth
        for (i in 0 until total) {
            val beforeFen = if (i == 0) startFen else parsed[i - 1].afterFen
            val afterFen = parsed[i].afterFen
            val san = parsed[i].san
            val uci = parsed[i].uci

            val currentDepth = adaptiveDepth(i + 1, total, depth)

            Log.d(TAG, "📍 Move ${i + 1}/$total: depth=$currentDepth")

            val posBefore = positions.last()
            val posAfter = evaluatePositionSmart(afterFen, currentDepth, multiPv, null, lichessPreloaded)
            positions.add(posAfter)

            // Normalize evaluation
            val whiteToPlayAfter = sideToMoveIsWhite(afterFen)
            val topLine = posAfter.lines.firstOrNull()

            val evalCp = topLine?.cp?.let { if (!whiteToPlayAfter) -it else it }
            val evalMate = topLine?.mate?.let { m ->
                when {
                    m == 0 && !whiteToPlayAfter -> 1  // Чёрные заматованы
                    m == 0 && whiteToPlayAfter -> -1  // Белые заматованы
                    !whiteToPlayAfter -> -m
                    else -> m
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

        // 2) Build PositionEval list with proper inversion
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

        // 5) Per-move accuracy from win percentages
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

        val totalTime = System.currentTimeMillis() - startedAt
        Log.i(TAG, "✅ Analysis complete! Time: ${totalTime}ms, Positions: ${positionEvals.size}, Cache size: ${fenCache.size}, Lichess hits: ${lichessPreloaded.size}")

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
        // Для real-time анализа пробуем параллельно загрузить обе позиции из Lichess
        val lichessData = if (skillLevel == null) {
            batchLoadLichessEvals(listOf(beforeFen, afterFen), multiPv)
        } else {
            emptyMap()
        }

        val posBefore = evaluatePositionSmart(beforeFen, depth, multiPv, skillLevel, lichessData)
        val posAfter = evaluatePositionSmart(afterFen, depth, multiPv, skillLevel, lichessData)

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