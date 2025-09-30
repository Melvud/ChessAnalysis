package com.example.chessanalysis.local

import android.content.Context
import android.util.Log
import com.example.chessanalysis.EngineClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

object LocalStockfish {
    private const val TAG = "LocalStockfish"

    init {
        System.loadLibrary("sflauncher")
        Log.d(TAG, "libsflauncher loaded successfully")
    }

    // JNI методы - должны точно соответствовать C++ сигнатурам
    @JvmStatic
    private external fun nativeInit(stockfishPath: String?, preferBuiltin: Boolean, threads: Int): Long

    @JvmStatic
    private external fun nativeStart(handle: Long): Unit

    @JvmStatic
    private external fun nativeWriteLine(handle: Long, cmd: String)

    @JvmStatic
    private external fun nativeReadLine(handle: Long, timeoutMs: Int): String?

    @JvmStatic
    private external fun nativeDrain(handle: Long, timeoutMs: Int)

    @JvmStatic
    private external fun nativeDrainUntil(handle: Long, token: String, timeoutMs: Int)

    private var handle: Long = 0L
    private val started = AtomicBoolean(false)

    fun ensureStarted(
        context: Context? = null,
        threads: Int = Runtime.getRuntime().availableProcessors().coerceAtLeast(2)
    ) {
        if (started.get()) return
        synchronized(this) {
            if (started.get()) return

            // Инициализируем с встроенным движком
            handle = nativeInit(null, true, threads)
            if (handle == 0L) {
                throw IllegalStateException("Failed to initialize native Stockfish")
            }

            try {
                // Запускаем UCI loop
                nativeStart(handle)

                // Инициализация UCI протокола
                nativeWriteLine(handle, "uci")
                nativeDrainUntil(handle, "uciok", 5000)

                // Настройки движка
                nativeWriteLine(handle, "setoption name Threads value $threads")
                nativeWriteLine(handle, "setoption name Hash value 256")
                nativeWriteLine(handle, "setoption name MultiPV value 3")
                nativeWriteLine(handle, "isready")
                nativeDrainUntil(handle, "readyok", 5000)

                started.set(true)
                Log.d(TAG, "Stockfish started successfully (threads=$threads)")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start Stockfish: ${e.message}")
                handle = 0L
                throw IllegalStateException("Local engine not available: ${e.message}")
            }
        }
    }

    suspend fun evaluatePositionDetailed(
        fen: String,
        depth: Int,
        multiPv: Int,
        skillLevel: Int? = null,
        context: Context? = null
    ): EngineClient.PositionDTO = withContext(Dispatchers.IO) {
        ensureStarted(context)

        nativeWriteLine(handle, "setoption name MultiPV value $multiPv")
        if (skillLevel != null) {
            nativeWriteLine(handle, "setoption name Skill Level value $skillLevel")
        }

        nativeWriteLine(handle, "position fen $fen")
        nativeWriteLine(handle, "go depth $depth")

        val lines = mutableListOf<EngineClient.LineDTO>()
        var bestMove: String? = null
        val deadline = System.currentTimeMillis() + 60_000

        while (System.currentTimeMillis() < deadline) {
            val s = nativeReadLine(handle, 500).orEmpty()
            if (s.isBlank()) continue

            if (s.startsWith("info ")) {
                parseInfo(s)?.let { upsert(lines, it, multiPv) }
            } else if (s.startsWith("bestmove ")) {
                bestMove = s.substringAfter("bestmove ").substringBefore(' ').trim()
                break
            }
        }

        EngineClient.PositionDTO(lines = lines.take(multiPv), bestMove = bestMove)
    }

    suspend fun evaluateFen(
        fen: String,
        depth: Int,
        multiPv: Int,
        skillLevel: Int? = null,
        context: Context? = null
    ): EngineClient.StockfishResponse = withContext(Dispatchers.IO) {
        val pos = evaluatePositionDetailed(fen, depth, multiPv, skillLevel, context)
        val top = pos.lines.firstOrNull()
        val evaluationPawns = top?.cp?.let { it / 100.0 }
        EngineClient.StockfishResponse(
            success = true,
            evaluation = evaluationPawns,
            mate = top?.mate,
            bestmove = pos.bestMove,
            continuation = top?.pv?.joinToString(" "),
            error = null
        )
    }

    private data class Info(val mpv: Int, val depth: Int?, val cp: Int?, val mate: Int?, val pv: List<String>)

    private fun parseInfo(s: String): Info? {
        var mpv = 1
        var depth: Int? = null
        var cp: Int? = null
        var mate: Int? = null

        val t = s.split(' ')
        var i = 0
        while (i < t.size) {
            when (t[i]) {
                "multipv" -> mpv = t.getOrNull(i + 1)?.toIntOrNull() ?: mpv
                "depth" -> depth = t.getOrNull(i + 1)?.toIntOrNull()
                "score" -> when (t.getOrNull(i + 1)) {
                    "cp" -> cp = t.getOrNull(i + 2)?.toIntOrNull()
                    "mate" -> mate = t.getOrNull(i + 2)?.toIntOrNull()
                }
            }
            i++
        }

        val pvi = t.indexOf("pv")
        val pv = if (pvi >= 0 && pvi + 1 < t.size) t.drop(pvi + 1) else emptyList()
        return Info(mpv, depth, cp, mate, pv)
    }

    private fun upsert(dst: MutableList<EngineClient.LineDTO>, it: Info, multiPv: Int) {
        val idx = (it.mpv - 1).coerceIn(0, multiPv - 1)
        while (dst.size <= idx) dst.add(EngineClient.LineDTO())
        dst[idx] = EngineClient.LineDTO(
            pv = it.pv,
            cp = it.cp,
            mate = it.mate,
            depth = it.depth,
            multiPv = it.mpv
        )
    }

    fun shutdown() {
        if (!started.get()) return
        synchronized(this) {
            if (!started.get()) return
            try {
                nativeWriteLine(handle, "quit")
                Thread.sleep(100)
                // Нет метода nativeStop в C++, поэтому handle обнуляем после quit
            } catch (e: Exception) {
                Log.e(TAG, "shutdown error", e)
            } finally {
                started.set(false)
                handle = 0L
            }
        }
    }
}