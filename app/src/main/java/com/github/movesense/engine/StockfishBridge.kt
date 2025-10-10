package com.github.movesense.engine

object StockfishBridge {

    init { System.loadLibrary("stockfish") }

    @Volatile
    private var enginePtr: Long = 0L

    private val initLock = Any()

    // ==== ОРИГИНАЛЬНЫЕ МЕТОДЫ (для обратной совместимости) ====

    private external fun initEngine(): Long
    private external fun runCmd(ptr: Long, cmd: String): String
    private external fun goAsync(ptr: Long, depth: Int)
    private external fun stopSearch(ptr: Long)
    private external fun destroyEngine(ptr: Long)
    private external fun readOutputNative(): String

    fun ensureStarted() {
        if (enginePtr != 0L) return
        synchronized(initLock) {
            if (enginePtr != 0L) return
            val p = initEngine()
            require(p != 0L) { "Failed to init Stockfish" }
            enginePtr = p
            runCmd(p, "uci")
            runCmd(p, "isready")
        }
    }

    fun send(cmd: String): String {
        val p = enginePtr
        require(p != 0L) { "Engine not initialized" }
        return runCmd(p, cmd)
    }

    fun go(depth: Int) {
        val p = enginePtr
        require(p != 0L) { "Engine not initialized" }
        goAsync(p, depth)
    }

    fun stop() {
        val p = enginePtr
        if (p != 0L) stopSearch(p)
    }

    fun shutdown() {
        synchronized(initLock) {
            val p = enginePtr
            if (p != 0L) {
                runCatching { stopSearch(p); runCmd(p, "isready") }
                runCatching { destroyEngine(p) }
                enginePtr = 0L
            }
        }
    }

    fun readOutput(): String = readOutputNative()

    // ==== НОВЫЕ МЕТОДЫ ДЛЯ ПУЛА ====

    fun initEngineInstance(): Long {
        val ptr = initEngine()
        require(ptr != 0L) { "Failed to init engine instance" }
        return ptr
    }

    fun sendToInstance(ptr: Long, cmd: String): String {
        require(ptr != 0L) { "Invalid engine instance" }
        return runCmd(ptr, cmd)
    }

    fun goAsyncInstance(ptr: Long, depth: Int) {
        require(ptr != 0L) { "Invalid engine instance" }
        goAsync(ptr, depth)
    }

    fun stopSearchInstance(ptr: Long) {
        if (ptr != 0L) stopSearch(ptr)
    }

    fun destroyEngineInstance(ptr: Long) {
        if (ptr != 0L) {
            runCatching { stopSearch(ptr); runCmd(ptr, "isready") }
            runCatching { destroyEngine(ptr) }
        }
    }

    fun readOutputFromInstance(ptr: Long): String {
        // Каждый экземпляр имеет свой буфер вывода
        // Но в текущей реализации uci_wrapper.cpp у нас один глобальный буфер
        // Нужно переписать под множественные экземпляры
        return readOutputNative()
    }
}