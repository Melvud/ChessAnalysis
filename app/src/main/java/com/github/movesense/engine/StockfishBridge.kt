package com.github.movesense.engine

object StockfishBridge {

    init { System.loadLibrary("stockfish") }

    @Volatile
    private var enginePtr: Long = 0L

    private val initLock = Any()

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
            // НЕ НАСТРАИВАЕМ ЗДЕСЬ - настройка в NativeUciEngine.ensureStarted()
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
}