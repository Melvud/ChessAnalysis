package com.github.movesense.engine

import android.content.Context
import android.util.Log
import com.github.movesense.EngineClient
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Semaphore
import java.util.concurrent.atomic.AtomicInteger

/**
 * Пул нативных Stockfish движков для параллельного анализа позиций
 */
class EnginePool(
    private val context: Context,
    private val poolSize: Int = 4
) {
    companion object {
        private const val TAG = "EnginePool"
    }

    private data class EngineInstance(
        val id: Int,
        val bridgePtr: Long,
        var isBusy: Boolean = false
    )

    private val engines = mutableListOf<EngineInstance>()
    private val semaphore = Semaphore(poolSize)
    private val activeAnalyses = AtomicInteger(0)

    @Volatile
    private var isInitialized = false

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    suspend fun initialize() = withContext(Dispatchers.IO) {
        if (isInitialized) {
            Log.d(TAG, "Pool already initialized")
            return@withContext
        }

        val cores = Runtime.getRuntime().availableProcessors()
        val threadsPerEngine = (cores / poolSize).coerceAtLeast(1)

        Log.i(TAG, "🏊 Initializing engine pool: size=$poolSize, threads per engine=$threadsPerEngine")

        // Копируем NNUE файл если нужно
        copyNNUEFromAssets(context)

        // Создаем пул движков
        repeat(poolSize) { index ->
            val ptr = initSingleEngine(index, threadsPerEngine)
            engines.add(EngineInstance(id = index, bridgePtr = ptr))
            Log.i(TAG, "✅ Engine #$index initialized (ptr=$ptr)")
        }

        isInitialized = true
        Log.i(TAG, "🎉 Engine pool ready! $poolSize engines with $threadsPerEngine threads each")
    }

    private suspend fun initSingleEngine(id: Int, threads: Int): Long = withContext(Dispatchers.IO) {
        // Создаем отдельный экземпляр через JNI
        val ptr = StockfishBridge.initEngineInstance()
        require(ptr != 0L) { "Failed to init engine #$id" }

        // Настраиваем движок
        StockfishBridge.sendToInstance(ptr, "uci")
        delay(50)
        StockfishBridge.sendToInstance(ptr, "setoption name Threads value $threads")

        // Hash делим между движками
        val hashPerEngine = (256 / poolSize).coerceAtLeast(16)
        StockfishBridge.sendToInstance(ptr, "setoption name Hash value $hashPerEngine")

        StockfishBridge.sendToInstance(ptr, "setoption name Ponder value false")

        // NNUE
        val nnueDir = java.io.File(context.filesDir, "nnue")
        val nnueFile = nnueDir.listFiles()?.firstOrNull { it.extension.equals("nnue", true) }
        if (nnueFile != null) {
            StockfishBridge.sendToInstance(ptr, "setoption name EvalFile value ${nnueFile.absolutePath}")
            Log.d(TAG, "Engine #$id: NNUE loaded")
        }

        StockfishBridge.sendToInstance(ptr, "isready")
        delay(100)

        ptr
    }

    /**
     * Анализирует позицию, используя свободный движок из пула
     */
    suspend fun evaluateFen(
        fen: String,
        depth: Int,
        multiPv: Int
    ): EngineClient.PositionDTO = coroutineScope {
        semaphore.acquire()
        activeAnalyses.incrementAndGet()

        try {
            val engine = engines.first { !it.isBusy }
            engine.isBusy = true

            Log.d(TAG, "🔧 Engine #${engine.id} analyzing (active: ${activeAnalyses.get()}/$poolSize)")

            val result = analyzeWithEngine(engine, fen, depth, multiPv)

            engine.isBusy = false
            result
        } finally {
            activeAnalyses.decrementAndGet()
            semaphore.release()
        }
    }

    private suspend fun analyzeWithEngine(
        engine: EngineInstance,
        fen: String,
        depth: Int,
        multiPv: Int
    ): EngineClient.PositionDTO = withContext(Dispatchers.IO) {
        val ptr = engine.bridgePtr

        // Очищаем вывод
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
        pumpUntilBestmove(ptr, depth)
    }

    private suspend fun pumpUntilBestmove(
        ptr: Long,
        wantedDepth: Int,
        timeoutMs: Long = 120_000
    ): EngineClient.PositionDTO {
        val start = System.currentTimeMillis()
        val sb = StringBuilder()
        var best: String? = null
        var lastDepth = 0

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

        while (System.currentTimeMillis() - start < timeoutMs) {
            val chunk = StockfishBridge.readOutputFromInstance(ptr)

            if (chunk.isNotEmpty()) {
                sb.append(chunk)

                // Парсим
                val acc = mutableMapOf<Int, AccLine>()
                sb.toString().lineSequence().forEach { line ->
                    when {
                        rxInfo.matches(line) -> {
                            val mp = rxMultiPv.find(line)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 1
                            val slot = acc.getOrPut(mp) { AccLine() }
                            slot.depth = rxDepth.find(line)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: slot.depth
                            val mMate = rxScoreMate.find(line)?.groupValues?.getOrNull(1)?.toIntOrNull()
                            val mCp = rxScoreCp.find(line)?.groupValues?.getOrNull(1)?.toIntOrNull()
                            if (mMate != null) { slot.mate = mMate; slot.cp = null }
                            else if (mCp != null) { slot.cp = mCp; slot.mate = null }
                            rxPv.find(line)?.groupValues?.getOrNull(1)?.let { pv ->
                                slot.pv = pv.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
                            }
                        }
                        line.startsWith("bestmove") -> {
                            best = rxBestmove.find(line)?.groupValues?.getOrNull(1)?.let { bm ->
                                if (bm != "(none)" && bm != "0000") bm else null
                            }
                        }
                    }
                }

                if (acc.isNotEmpty()) {
                    lastDepth = acc.values.maxOfOrNull { it.depth ?: 0 } ?: 0
                }

                if (best != null) break
                if (wantedDepth > 0 && lastDepth >= wantedDepth) {
                    StockfishBridge.stopSearchInstance(ptr)
                }
            } else {
                delay(20)
            }
        }

        // Финальный парсинг
        val acc = mutableMapOf<Int, AccLine>()
        sb.toString().lineSequence().forEach { line ->
            when {
                rxInfo.matches(line) -> {
                    val mp = rxMultiPv.find(line)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 1
                    val slot = acc.getOrPut(mp) { AccLine() }
                    slot.depth = rxDepth.find(line)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: slot.depth
                    val mMate = rxScoreMate.find(line)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    val mCp = rxScoreCp.find(line)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    if (mMate != null) { slot.mate = mMate; slot.cp = null }
                    else if (mCp != null) { slot.cp = mCp; slot.mate = null }
                    rxPv.find(line)?.groupValues?.getOrNull(1)?.let { pv ->
                        slot.pv = pv.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
                    }
                }
            }
        }

        val lines = acc.entries.sortedBy { it.key }.map { (mp, a) ->
            EngineClient.LineDTO(a.pv, a.cp, a.mate, a.depth, mp)
        }

        return EngineClient.PositionDTO(
            lines.ifEmpty { listOf(EngineClient.LineDTO(pv = emptyList(), cp = 0)) },
            best ?: lines.firstOrNull()?.pv?.firstOrNull()
        )
    }

    fun shutdown() {
        Log.i(TAG, "🛑 Shutting down engine pool...")
        engines.forEach { engine ->
            try {
                StockfishBridge.destroyEngineInstance(engine.bridgePtr)
                Log.d(TAG, "Engine #${engine.id} destroyed")
            } catch (e: Exception) {
                Log.e(TAG, "Error destroying engine #${engine.id}", e)
            }
        }
        engines.clear()
        scope.cancel()
        isInitialized = false
    }

    private suspend fun copyNNUEFromAssets(ctx: Context) = withContext(Dispatchers.IO) {
        try {
            val nnueDir = java.io.File(ctx.filesDir, "nnue")
            if (!nnueDir.exists()) nnueDir.mkdirs()

            if (nnueDir.listFiles()?.any { it.extension == "nnue" } == true) {
                return@withContext
            }

            val assetManager = ctx.assets
            val nnueFiles = assetManager.list("nnue")?.filter { it.endsWith(".nnue") } ?: emptyList()

            nnueFiles.forEach { filename ->
                val outputFile = java.io.File(nnueDir, filename)
                assetManager.open("nnue/$filename").use { input ->
                    outputFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy NNUE", e)
        }
    }
}