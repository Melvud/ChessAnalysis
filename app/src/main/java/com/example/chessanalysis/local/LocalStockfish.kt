package com.example.chessanalysis.local

import android.content.Context
import android.util.Log
import com.example.chessanalysis.EngineClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

object LocalStockfish {
    private const val TAG = "LocalStockfish"

    init {
        System.loadLibrary("sflauncher")
        Log.d(TAG, "libsflauncher loaded successfully")
    }

    // JNI (nativeStart возвращает Boolean и принимает preferBuiltin)
    @JvmStatic private external fun nativeInit(): Long
    @JvmStatic private external fun nativeStart(handle: Long, threads: Int, path: String?, preferBuiltin: Boolean): Boolean
    @JvmStatic private external fun nativeSend(handle: Long, cmd: String)
    @JvmStatic private external fun nativeReadLine(handle: Long, timeoutMs: Long): String?
    @JvmStatic private external fun nativeStop(handle: Long)

    private var handle: Long = 0L
    private val started = AtomicBoolean(false)
    private var stockfishPath: String? = null

    // Если собираешь встроенный движок (BUILTIN_STOCKFISH) — ставь true
    private const val PREFER_BUILTIN = true

    fun ensureStarted(
        context: Context? = null,
        threads: Int = Runtime.getRuntime().availableProcessors().coerceAtLeast(2)
    ) {
        if (started.get()) return
        synchronized(this) {
            if (started.get()) return

            if (context != null && stockfishPath == null) {
                extractStockfishBinaryIfPresent(context) // необязательно при встроенном режиме
            }

            handle = nativeInit()
            if (handle == 0L) error("Failed to initialize Stockfish engine")

            val ok = nativeStart(handle, threads, stockfishPath, PREFER_BUILTIN)
            if (!ok) {
                Log.e(TAG, "nativeStart failed: device likely blocks exec() of binaries or path missing")
                throw IllegalStateException("Local engine not available on this device")
            }

            nativeSend(handle, "uci")
            drainUntil("uciok", 5000L)

            nativeSend(handle, "setoption name Threads value $threads")
            nativeSend(handle, "setoption name Hash value 256")
            nativeSend(handle, "setoption name MultiPV value 3")
            nativeSend(handle, "isready")
            drainUntil("readyok", 5000L)

            started.set(true)
            Log.d(TAG, "Stockfish started (threads=$threads)")
        }
    }

    private fun extractStockfishBinaryIfPresent(context: Context) {
        // опционально: если хочешь иметь dev-бинарник для эмулятора
        try {
            val target = File(context.filesDir, "stockfish")
            if (target.exists() && target.canExecute()) {
                stockfishPath = target.absolutePath
                return
            }
            val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: return
            val assetPath = "stockfish/$abi/stockfish"
            context.assets.open(assetPath).use { inp ->
                target.outputStream().use { out -> inp.copyTo(out) }
            }
            target.setExecutable(true)
            stockfishPath = target.absolutePath
        } catch (_: Exception) {
            stockfishPath = null // не критично, если BUILTIN
        }
    }

    private fun drainUntil(token: String, timeoutMs: Long) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val line = nativeReadLine(handle, 150).orEmpty()
            if (line.isNotBlank()) {
                Log.d(TAG, "UCI: $line")
                if (line.contains(token, ignoreCase = true)) return
            }
        }
        Log.w(TAG, "Timeout waiting for $token")
    }

    suspend fun evaluatePositionDetailed(
        fen: String,
        depth: Int,
        multiPv: Int,
        skillLevel: Int? = null,
        context: Context? = null
    ): EngineClient.PositionDTO = withContext(Dispatchers.IO) {
        ensureStarted(context)
        nativeSend(handle, "setoption name MultiPV value $multiPv")
        if (skillLevel != null) nativeSend(handle, "setoption name Skill Level value $skillLevel")
        nativeSend(handle, "position fen $fen")
        nativeSend(handle, "go depth $depth")

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

    private data class Info(val mpv:Int, val depth:Int?, val cp:Int?, val mate:Int?, val pv:List<String>)

    private fun parseInfo(s: String): Info? {
        var mpv=1; var depth:Int?=null; var cp:Int?=null; var mate:Int?=null
        val t = s.split(' ')
        var i=0
        while (i<t.size) {
            when (t[i]) {
                "multipv" -> mpv = t.getOrNull(i+1)?.toIntOrNull() ?: mpv
                "depth"   -> depth = t.getOrNull(i+1)?.toIntOrNull()
                "score"   -> when (t.getOrNull(i+1)) {
                    "cp"   -> cp = t.getOrNull(i+2)?.toIntOrNull()
                    "mate" -> mate = t.getOrNull(i+2)?.toIntOrNull()
                }
            }
            i++
        }
        val pvi = t.indexOf("pv")
        val pv = if (pvi>=0 && pvi+1<t.size) t.drop(pvi+1) else emptyList()
        return Info(mpv, depth, cp, mate, pv)
    }

    private fun upsert(dst: MutableList<EngineClient.LineDTO>, it: Info, multiPv: Int) {
        val idx = (it.mpv-1).coerceIn(0, multiPv-1)
        while (dst.size <= idx) dst.add(EngineClient.LineDTO())
        dst[idx] = EngineClient.LineDTO(pv = it.pv, cp = it.cp, mate = it.mate, depth = it.depth, multiPv = it.mpv)
    }

    fun shutdown() {
        if (!started.get()) return
        synchronized(this) {
            if (!started.get()) return
            try { nativeSend(handle, "quit"); Thread.sleep(100); nativeStop(handle) }
            catch (e: Exception) { Log.e(TAG, "shutdown error", e) }
            finally { started.set(false); handle = 0L }
        }
    }
}
