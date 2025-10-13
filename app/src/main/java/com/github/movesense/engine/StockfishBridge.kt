package com.github.movesense.engine

import android.util.Log
import kotlinx.coroutines.sync.Semaphore
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max

object StockfishBridge {
    private const val TAG = "StockfishBridge"

    init { System.loadLibrary("stockfish") }

    private external fun initEngine(): Long
    private external fun runCmd(ptr: Long, cmd: String): String
    private external fun goAsync(ptr: Long, depth: Int)
    private external fun stopSearch(ptr: Long)
    private external fun destroyEngine(ptr: Long)
    private external fun readOutputNative(): String

    data class EngineInstance(
        val id: Int,
        val ptr: Long,
        val threadsPerInstance: Int
    )

    private val enginePool = ConcurrentLinkedQueue<EngineInstance>()
    private lateinit var poolSemaphore: Semaphore
    private val activeEngines = AtomicInteger(0)
    private val totalCores = Runtime.getRuntime().availableProcessors()
    private val poolLock = Any()

    private var poolSize: Int = 1
    private var threadsPerEngine: Int = totalCores

    init {
        poolSize = 1
        threadsPerEngine = totalCores.coerceAtLeast(1)
        poolSemaphore = Semaphore(poolSize)

        Log.i(TAG, "🚀 Initial config: $poolSize worker × $threadsPerEngine threads ($totalCores cores)")
    }

    /**
     * 🔥 НОВОЕ: Реконфигурация пула с динамическим распределением потоков
     */
    fun reconfigurePool(workersCount: Int, threadsPerWorkerParam: Int = 0) {
        synchronized(poolLock) {
            Log.i(TAG, "🔄 Reconfiguring pool: $workersCount workers requested")

            // Останавливаем текущий пул
            shutdownPoolInternal()

            // Рассчитываем оптимальную конфигурацию
            poolSize = workersCount.coerceIn(1, totalCores)
            threadsPerEngine = if (threadsPerWorkerParam > 0) {
                threadsPerWorkerParam
            } else {
                max(1, totalCores / poolSize)
            }

            // Создаём новый семафор под новый размер пула
            poolSemaphore = Semaphore(poolSize)

            Log.i(TAG, "♻️ New config: $poolSize workers × $threadsPerEngine threads = ${poolSize * threadsPerEngine} total")

            // Инициализируем новый пул
            initializePoolInternal()
        }
    }

    fun initializePool() {
        synchronized(poolLock) {
            if (enginePool.isNotEmpty()) {
                Log.d(TAG, "Pool already initialized")
                return
            }
            initializePoolInternal()
        }
    }

    private fun initializePoolInternal() {
        Log.i(TAG, "Initializing engine pool with $poolSize workers...")

        repeat(poolSize) { id ->
            try {
                val ptr = initEngine()
                require(ptr != 0L) { "Failed to init engine #$id" }

                // КРИТИЧНО: uci команда
                runCmd(ptr, "uci")
                Thread.sleep(100)

                // КРИТИЧНО: Threads - устанавливаем ПЕРВЫМ
                runCmd(ptr, "setoption name Threads value $threadsPerEngine")
                Log.d(TAG, "Engine #$id: Threads = $threadsPerEngine")
                Thread.sleep(100)

                // Hash память
                val hash = (threadsPerEngine * 64).coerceIn(16, 512)
                runCmd(ptr, "setoption name Hash value $hash")
                Log.d(TAG, "Engine #$id: Hash = ${hash}MB")
                Thread.sleep(100)

                // Отключаем Ponder
                runCmd(ptr, "setoption name Ponder value false")
                Thread.sleep(50)

                // Проверяем готовность
                runCmd(ptr, "isready")

                var ready = false
                repeat(40) {
                    Thread.sleep(100)
                    val output = readOutputNative()
                    if (output.contains("readyok")) {
                        ready = true
                        return@repeat
                    }
                }

                if (!ready) {
                    Log.w(TAG, "Engine #$id: readyok timeout, but continuing...")
                }

                val instance = EngineInstance(id, ptr, threadsPerEngine)
                enginePool.offer(instance)
                activeEngines.incrementAndGet()

                Log.i(TAG, "✅ Engine #$id initialized (${threadsPerEngine} threads)")

            } catch (e: Exception) {
                Log.e(TAG, "Failed to init engine #$id", e)
            }
        }

        Log.i(TAG, "✅ Pool ready: ${enginePool.size}/$poolSize engines, ${poolSize * threadsPerEngine} total threads")
    }

    suspend fun acquire(): EngineInstance {
        poolSemaphore.acquire()

        val instance = enginePool.poll()
        if (instance != null) {
            Log.d(TAG, "Acquired engine #${instance.id}")
            return instance
        }

        Log.w(TAG, "Pool exhausted, creating temporary engine")
        val ptr = initEngine()
        runCmd(ptr, "uci")
        Thread.sleep(50)
        runCmd(ptr, "setoption name Threads value $threadsPerEngine")
        Thread.sleep(50)
        runCmd(ptr, "isready")

        return EngineInstance(-1, ptr, threadsPerEngine)
    }

    fun release(instance: EngineInstance) {
        if (instance.id == -1) {
            runCatching { destroyEngine(instance.ptr) }
            poolSemaphore.release()
            return
        }

        runCatching {
            stopSearch(instance.ptr)
            Thread.sleep(50)
            readOutputNative()
            runCmd(instance.ptr, "ucinewgame")
            runCmd(instance.ptr, "isready")
        }

        enginePool.offer(instance)
        poolSemaphore.release()

        Log.d(TAG, "Released engine #${instance.id}")
    }

    fun send(instance: EngineInstance, cmd: String): String {
        return runCmd(instance.ptr, cmd)
    }

    fun go(instance: EngineInstance, depth: Int) {
        goAsync(instance.ptr, depth)
    }

    fun stop(instance: EngineInstance) {
        stopSearch(instance.ptr)
    }

    fun readOutput(): String = readOutputNative()

    fun shutdownPool() {
        synchronized(poolLock) {
            shutdownPoolInternal()
        }
    }

    private fun shutdownPoolInternal() {
        Log.i(TAG, "Shutting down engine pool...")

        while (enginePool.isNotEmpty()) {
            val instance = enginePool.poll()
            if (instance != null) {
                runCatching {
                    stopSearch(instance.ptr)
                    runCmd(instance.ptr, "quit")
                    Thread.sleep(100)
                    destroyEngine(instance.ptr)
                }
            }
        }

        activeEngines.set(0)
        Log.i(TAG, "✅ Pool destroyed")
    }

    fun getPoolStats(): String {
        return "${enginePool.size}/$poolSize available | ${poolSize * threadsPerEngine} threads total"
    }
}