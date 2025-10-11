package com.github.movesense.engine

import android.util.Log

object StockfishBridge {

    private const val TAG = "StockfishBridge"

    init {
        try {
            System.loadLibrary("stockfish")
            Log.i(TAG, "✅ libstockfish.so loaded successfully")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to load libstockfish.so", e)
            throw e
        }
    }

    // ==== JNI Native методы ====

    @JvmStatic
    private external fun initEngine(): Long

    @JvmStatic
    private external fun runCmd(ptr: Long, cmd: String): String

    @JvmStatic
    private external fun goAsync(ptr: Long, depth: Int)

    @JvmStatic
    private external fun stopSearch(ptr: Long)

    @JvmStatic
    private external fun destroyEngine(ptr: Long)

    @JvmStatic
    private external fun readOutputNative(ptr: Long): String

    // ==== Главный единственный экземпляр (для обратной совместимости) ====

    @Volatile
    private var mainEnginePtr: Long = 0L

    private val mainEngineLock = Any()

    fun ensureStarted() {
        if (mainEnginePtr != 0L) return
        synchronized(mainEngineLock) {
            if (mainEnginePtr != 0L) return
            val ptr = initEngine()
            require(ptr != 0L) { "Failed to initialize main Stockfish engine" }
            mainEnginePtr = ptr
            Log.i(TAG, "✅ Main engine initialized: ptr=$ptr")

            // Базовая инициализация
            runCmd(ptr, "uci")
            runCmd(ptr, "isready")
        }
    }

    fun send(cmd: String): String {
        val ptr = mainEnginePtr
        require(ptr != 0L) { "Main engine not initialized" }
        return runCmd(ptr, cmd)
    }

    fun go(depth: Int) {
        val ptr = mainEnginePtr
        require(ptr != 0L) { "Main engine not initialized" }
        goAsync(ptr, depth)
    }

    fun stop() {
        val ptr = mainEnginePtr
        if (ptr != 0L) {
            stopSearch(ptr)
        }
    }

    fun shutdown() {
        synchronized(mainEngineLock) {
            val ptr = mainEnginePtr
            if (ptr != 0L) {
                runCatching {
                    stopSearch(ptr)
                    runCmd(ptr, "isready")
                }
                runCatching { destroyEngine(ptr) }
                mainEnginePtr = 0L
                Log.i(TAG, "Main engine destroyed")
            }
        }
    }

    fun readOutput(): String {
        val ptr = mainEnginePtr
        require(ptr != 0L) { "Main engine not initialized" }
        return readOutputNative(ptr)
    }

    // ==== API для пула движков ====

    /**
     * Создать новый независимый экземпляр движка
     * @return ptr - указатель на экземпляр (handle)
     */
    fun initEngineInstance(): Long {
        val ptr = initEngine()
        require(ptr != 0L) { "Failed to initialize engine instance" }
        Log.d(TAG, "✅ Engine instance created: ptr=$ptr")
        return ptr
    }

    /**
     * Отправить команду конкретному экземпляру
     */
    fun sendToInstance(ptr: Long, cmd: String): String {
        require(ptr != 0L) { "Invalid engine instance ptr" }
        return runCmd(ptr, cmd)
    }

    /**
     * Запустить поиск для конкретного экземпляра
     */
    fun goAsyncInstance(ptr: Long, depth: Int) {
        require(ptr != 0L) { "Invalid engine instance ptr" }
        goAsync(ptr, depth)
    }

    /**
     * Остановить поиск для конкретного экземпляра
     */
    fun stopSearchInstance(ptr: Long) {
        if (ptr != 0L) {
            stopSearch(ptr)
        }
    }

    /**
     * Уничтожить конкретный экземпляр
     */
    fun destroyEngineInstance(ptr: Long) {
        if (ptr != 0L) {
            runCatching {
                stopSearch(ptr)
                runCmd(ptr, "isready")
            }
            runCatching { destroyEngine(ptr) }
            Log.d(TAG, "Engine instance destroyed: ptr=$ptr")
        }
    }

    /**
     * Прочитать вывод конкретного экземпляра
     */
    fun readOutputFromInstance(ptr: Long): String {
        require(ptr != 0L) { "Invalid engine instance ptr" }
        return readOutputNative(ptr)
    }
}