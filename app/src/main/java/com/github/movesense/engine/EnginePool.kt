package com.github.movesense.engine

import android.util.Log
import com.github.movesense.EngineClient
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Semaphore
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * Пул нативных Stockfish движков для параллельного анализа позиций
 *
 * КРИТИЧЕСКАЯ ОПТИМИЗАЦИЯ: Позволяет анализировать 3-4 позиции одновременно!
 */
class EnginePool(
    private val context: android.content.Context,
    private val poolSize: Int = 3 // 3-4 движка параллельно
) {
    companion object {
        private const val TAG = "EnginePool"

        @Volatile
        private var INSTANCE: EnginePool? = null

        fun getInstance(context: android.content.Context, size: Int = 3): EnginePool {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: EnginePool(context.applicationContext, size).also {
                    INSTANCE = it
                }
            }
        }

        fun destroyInstance() {
            synchronized(this) {
                INSTANCE?.shutdown()
                INSTANCE = null
            }
        }
    }

    private data class EngineWorker(
        val id: Int,
        val ptr: Long,
        var isBusy: Boolean = false
    )

    private data class AnalysisRequest(
        val fen: String,
        val depth: Int,
        val multiPv: Int,
        val result: CompletableDeferred<EngineClient.PositionDTO>
    )

    private val workers = mutableListOf<EngineWorker>()
    private val requestChannel = Channel<AnalysisRequest>(Channel.UNLIMITED)
    private val semaphore = Semaphore(poolSize)
    private val activeAnalyses = AtomicInteger(0)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Volatile
    private var isInitialized = false

    init {
        Log.i(TAG, "🏊 Initializing engine pool with $poolSize workers")

        // Копируем NNUE синхронно
        runBlocking(Dispatchers.IO) {
            copyNNUEFromAssets(context)
        }

        // Создаем workers
        scope.launch {
            initializeWorkers()
        }
    }

    private suspend fun initializeWorkers() = withContext(Dispatchers.IO) {
        val cores = Runtime.getRuntime().availableProcessors()
        val threadsPerWorker = (cores / poolSize).coerceAtLeast(1)
        val hashPerWorker = calculateHashPerWorker()

        Log.i(TAG, "📊 Device: $cores cores, ${threadsPerWorker} threads/worker, ${hashPerWorker}MB hash/worker")

        repeat(poolSize) { workerId ->
            try {
                val ptr = StockfishBridge.initEngineInstance()
                require(ptr != 0L) { "Failed to init worker #$workerId" }

                val worker = EngineWorker(id = workerId, ptr = ptr)
                workers.add(worker)

                // Настройка движка
                configureEngine(ptr, workerId, threadsPerWorker, hashPerWorker)

                // Запускаем обработчик запросов для этого worker
                scope.launch {
                    processRequests(worker)
                }

                Log.i(TAG, "✅ Worker #$workerId ready (ptr=$ptr)")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Failed to create worker #$workerId", e)
            }
        }

        isInitialized = true
        Log.i(TAG, "🎉 Engine pool fully initialized with ${workers.size} workers")
    }

    private fun calculateHashPerWorker(): Int {
        val runtime = Runtime.getRuntime()
        val maxMemory = runtime.maxMemory() / (1024 * 1024) // MB

        // Выделяем 25% памяти для движков, делим на количество workers
        val totalHashBudget = (maxMemory * 0.25).toInt().coerceIn(256, 4096)
        val hashPerWorker = (totalHashBudget / poolSize).coerceAtLeast(64).coerceAtMost(1024)

        Log.d(TAG, "💾 Memory budget: totalHash=${totalHashBudget}MB, perWorker=${hashPerWorker}MB")
        return hashPerWorker
    }

    private suspend fun configureEngine(
        ptr: Long,
        workerId: Int,
        threads: Int,
        hashMB: Int
    ) = withContext(Dispatchers.IO) {
        try {
            StockfishBridge.sendToInstance(ptr, "uci")
            delay(50)

            StockfishBridge.sendToInstance(ptr, "setoption name Threads value $threads")
            StockfishBridge.sendToInstance(ptr, "setoption name Hash value $hashMB")
            StockfishBridge.sendToInstance(ptr, "setoption name Ponder value false")

            // КРИТИЧНО: для многопоточности
            StockfishBridge.sendToInstance(ptr, "setoption name NumaPolicy value system")

            // NNUE
            val nnueDir = File(context.filesDir, "nnue")
            val nnueFile = nnueDir.listFiles()?.firstOrNull { it.extension.equals("nnue", true) }
            if (nnueFile != null && nnueFile.exists()) {
                StockfishBridge.sendToInstance(ptr, "setoption name EvalFile value ${nnueFile.absolutePath}")
                Log.d(TAG, "Worker #$workerId: NNUE loaded (${nnueFile.name})")
            } else {
                Log.w(TAG, "⚠️ Worker #$workerId: NO NNUE FILE!")
            }

            StockfishBridge.sendToInstance(ptr, "isready")
            delay(100)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to configure worker #$workerId", e)
            throw e
        }
    }

    private suspend fun processRequests(worker: EngineWorker) {
        Log.d(TAG, "👷 Worker #${worker.id} started processing")

        for (request in requestChannel) {
            try {
                semaphore.acquire()
                worker.isBusy = true
                activeAnalyses.incrementAndGet()

                Log.d(TAG, "🔧 Worker #${worker.id} analyzing (active: ${activeAnalyses.get()}/$poolSize)")

                val result = analyzePosition(worker, request.fen, request.depth, request.multiPv)
                request.result.complete(result)

            } catch (e: Exception) {
                Log.e(TAG, "❌ Worker #${worker.id} error: ${e.message}", e)
                request.result.completeExceptionally(e)
            } finally {
                worker.isBusy = false
                activeAnalyses.decrementAndGet()
                semaphore.release()
            }
        }
    }

    private suspend fun analyzePosition(
        worker: EngineWorker,
        fen: String,
        depth: Int,
        multiPv: Int
    ): EngineClient.PositionDTO = withContext(Dispatchers.IO) {
        val ptr = worker.ptr

        // Очищаем буфер вывода
        StockfishBridge.readOutputFromInstance(ptr)

        // Отправляем команды
        StockfishBridge.sendToInstance(ptr, "stop")
        StockfishBridge.sendToInstance(ptr, "ucinewgame")
        StockfishBridge.sendToInstance(ptr, "setoption name MultiPV value $multiPv")
        StockfishBridge.sendToInstance(ptr, "isready")
        delay(10)

        StockfishBridge.sendToInstance(ptr, "position fen $fen")
        StockfishBridge.goAsyncInstance(ptr, depth)

        // Ждем результат
        pumpUntilBestmove(ptr, worker.id, depth)
    }

    private suspend fun pumpUntilBestmove(
        ptr: Long,
        workerId: Int,
        wantedDepth: Int,
        timeoutMs: Long = 120_000
    ): EngineClient.PositionDTO {
        val start = System.currentTimeMillis()
        val buffer = StringBuilder()

        val rxInfo = Regex("""^info\s+.*\bdepth\s+\d+.*""", RegexOption.MULTILINE)
        val rxDepth = Regex("""\bdepth\s+(\d+)""")
        val rxMultiPv = Regex("""\bmultipv\s+(\d+)""")
        val rxScoreCp = Regex("""\bscore\s+cp\s+(-?\d+)""")
        val rxScoreMate = Regex("""\bscore\s+mate\s+(-?\d+)""")
        val rxPv = Regex("""\bpv\s+(.+)$""")
        val rxBestmove = Regex("""^bestmove\s+(\S+)""", RegexOption.MULTILINE)

        data class AccLine(
            var depth: Int? = null,
            var cp: Int? = null,
            var mate: Int? = null,
            var pv: List<String> = emptyList()
        )

        val acc = mutableMapOf<Int, AccLine>()
        var bestMove: String? = null
        var lastDepth = 0
        var emptyCount = 0

        while (System.currentTimeMillis() - start < timeoutMs) {
            val chunk = StockfishBridge.readOutputFromInstance(ptr)

            if (chunk.isNotEmpty()) {
                emptyCount = 0
                buffer.append(chunk)

                // Парсим построчно
                chunk.lineSequence().forEach { line ->
                    when {
                        rxInfo.matches(line) -> {
                            val mp = rxMultiPv.find(line)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 1
                            val slot = acc.getOrPut(mp) { AccLine() }

                            slot.depth = rxDepth.find(line)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: slot.depth

                            val mMate = rxScoreMate.find(line)?.groupValues?.getOrNull(1)?.toIntOrNull()
                            val mCp = rxScoreCp.find(line)?.groupValues?.getOrNull(1)?.toIntOrNull()

                            if (mMate != null) {
                                slot.mate = mMate
                                slot.cp = null
                            } else if (mCp != null) {
                                slot.cp = mCp
                                slot.mate = null
                            }

                            rxPv.find(line)?.groupValues?.getOrNull(1)?.let { pv ->
                                slot.pv = pv.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
                            }

                            lastDepth = slot.depth ?: lastDepth
                        }

                        line.startsWith("bestmove") -> {
                            bestMove = rxBestmove.find(line)?.groupValues?.getOrNull(1)?.let { bm ->
                                if (bm != "(none)" && bm != "0000") bm else null
                            }
                        }
                    }
                }

                if (bestMove != null) {
                    Log.d(TAG, "✅ Worker #$workerId: bestmove=$bestMove, depth=$lastDepth")
                    break
                }

                // Проверка на терминальную позицию
                if (buffer.contains("bestmove (none)")) {
                    Log.d(TAG, "🏁 Worker #$workerId: terminal position")
                    bestMove = null
                    break
                }

                // Останавливаем при достижении глубины
                if (wantedDepth > 0 && lastDepth >= wantedDepth) {
                    StockfishBridge.stopSearchInstance(ptr)
                }
            } else {
                emptyCount++
                delay(if (emptyCount < 5) 20 else if (emptyCount < 20) 50 else 100)
            }
        }

        // Финальный парсинг
        val lines = acc.entries.sortedBy { it.key }.map { (mp, a) ->
            EngineClient.LineDTO(a.pv, a.cp, a.mate, a.depth, mp)
        }

        val safeLines = if (lines.isEmpty()) {
            listOf(EngineClient.LineDTO(pv = emptyList(), cp = null, mate = 0, depth = wantedDepth, multiPv = 1))
        } else {
            lines
        }

        return EngineClient.PositionDTO(
            lines = safeLines,
            bestMove = bestMove ?: safeLines.firstOrNull()?.pv?.firstOrNull()
        )
    }

    /**
     * ПУБЛИЧНЫЙ API: Анализировать позицию через пул
     * Автоматически использует свободный worker
     */
    suspend fun analyzePosition(
        fen: String,
        depth: Int,
        multiPv: Int = 3
    ): EngineClient.PositionDTO {
        // Ждем инициализации пула
        var attempts = 0
        while (!isInitialized && attempts < 100) {
            delay(100)
            attempts++
        }

        if (!isInitialized) {
            throw IllegalStateException("Engine pool not initialized after ${attempts * 100}ms")
        }

        val result = CompletableDeferred<EngineClient.PositionDTO>()
        val request = AnalysisRequest(fen, depth, multiPv, result)

        requestChannel.send(request)

        return result.await()
    }

    /**
     * Пакетный анализ позиций параллельно
     */
    suspend fun analyzePositionsBatch(
        positions: List<Triple<String, Int, Int>> // (fen, depth, multiPv)
    ): List<EngineClient.PositionDTO> = coroutineScope {
        Log.i(TAG, "📦 Batch analysis: ${positions.size} positions")

        positions.map { (fen, depth, multiPv) ->
            async {
                analyzePosition(fen, depth, multiPv)
            }
        }.awaitAll()
    }

    /**
     * Получить статистику пула
     */
    fun getPoolStats(): PoolStats {
        val busyCount = workers.count { it.isBusy }
        // isEmpty доступно для Channel; false означает, что в канале уже есть элементы (очередь)
        val queued = !requestChannel.isEmpty
        return PoolStats(
            totalWorkers = workers.size,
            busyWorkers = busyCount,
            freeWorkers = workers.size - busyCount,
            activeAnalyses = activeAnalyses.get(),
            queuedRequests = queued
        )
    }

    data class PoolStats(
        val totalWorkers: Int,
        val busyWorkers: Int,
        val freeWorkers: Int,
        val activeAnalyses: Int,
        val queuedRequests: Boolean
    )

    fun shutdown() {
        Log.i(TAG, "🛑 Shutting down engine pool...")

        requestChannel.close()

        workers.forEach { worker ->
            try {
                StockfishBridge.destroyEngineInstance(worker.ptr)
                Log.d(TAG, "Worker #${worker.id} destroyed")
            } catch (e: Exception) {
                Log.e(TAG, "Error destroying worker #${worker.id}", e)
            }
        }

        workers.clear()
        scope.cancel()
        isInitialized = false

        Log.i(TAG, "✅ Engine pool shut down")
    }

    private suspend fun copyNNUEFromAssets(ctx: android.content.Context) = withContext(Dispatchers.IO) {
        try {
            val nnueDir = File(ctx.filesDir, "nnue")
            if (!nnueDir.exists()) {
                nnueDir.mkdirs()
                Log.d(TAG, "Created NNUE directory: ${nnueDir.absolutePath}")
            }

            // Проверяем, уже скопировано ли
            if (nnueDir.listFiles()?.any { it.extension == "nnue" } == true) {
                Log.d(TAG, "✅ NNUE already copied to filesDir")
                return@withContext
            }

            // Копируем все .nnue файлы из assets/nnue/
            val assetManager = ctx.assets
            val nnueFiles = assetManager.list("nnue")?.filter { it.endsWith(".nnue") } ?: emptyList()

            if (nnueFiles.isEmpty()) {
                Log.w(TAG, "⚠️ No .nnue files found in assets/nnue/")
                return@withContext
            }

            nnueFiles.forEach { filename ->
                val outputFile = File(nnueDir, filename)
                assetManager.open("nnue/$filename").use { input ->
                    outputFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                Log.i(TAG, "✅ Copied: $filename (${outputFile.length() / 1024}KB)")
            }

            Log.i(TAG, "✅ Copied ${nnueFiles.size} NNUE file(s) to ${nnueDir.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to copy NNUE from assets", e)
        }
    }
}
