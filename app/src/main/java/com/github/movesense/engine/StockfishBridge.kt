package com.github.movesense.engine

/**
 * Тонкая JNI-обёртка над knightvision-stockfish (lib "stockfish").
 * Методы и сигнатуры соответствуют их uci_wrapper.cpp.
 *
 * Библиотека "stockfish" должна собираться через CMake (как у Knightvision)
 * и располагаться в APK (jniLibs) для нужных ABI.
 */
object StockfishBridge {

    init {
        // Имя lib — ровно "stockfish" (как в CMake add_library(stockfish SHARED ...))
        System.loadLibrary("stockfish")
    }

    /** Указатель на инстанс движка в нативной части. */
    private var enginePtr: Long = 0L

    // --- Native API (из uci_wrapper.cpp) ---
    private external fun initEngine(): Long
    private external fun runCmd(ptr: Long, cmd: String): String
    private external fun goBlocking(ptr: Long, depth: Int): String
    external fun validFen(fen: String): Boolean

    /**
     * Инициализация движка (одноразовая); опционально задаём путь к NNUE.
     * Вызывает UCI и подготавливает инстанс.
     */
    @Synchronized
    fun ensureStarted(evalFilePath: String? = null) {
        if (enginePtr == 0L) {
            enginePtr = initEngine()
            // UCI-инициализация
            runCmd(enginePtr, "uci")
        }
        if (!evalFilePath.isNullOrBlank()) {
            runCmd(enginePtr, "setoption name EvalFile value $evalFilePath")
        }
    }

    /** Отправить произвольную UCI-команду и получить полный вывод. */
    fun send(cmd: String): String {
        check(enginePtr != 0L) { "StockfishBridge not initialized. Call ensureStarted() first." }
        return runCmd(enginePtr, cmd)
    }

    /** Выполнить поиск до depth и вернуть весь консольный вывод (info/bestmove). */
    fun go(depth: Int): String {
        check(enginePtr != 0L) { "StockfishBridge not initialized. Call ensureStarted() first." }
        return goBlocking(enginePtr, depth)
    }
}
