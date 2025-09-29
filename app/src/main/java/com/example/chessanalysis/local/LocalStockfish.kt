package com.example.chessanalysis.local

import android.content.Context
import android.util.Log
import com.example.chessanalysis.EngineClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

object LocalStockfish {

    private const val TAG = "LocalStockfish"

    init {
        try {
            System.loadLibrary("sflauncher")
            Log.d(TAG, "libsflauncher loaded successfully")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Failed to load libsflauncher.so: ${e.message}", e)
            throw e
        }
    }

    // JNI methods
    @JvmStatic private external fun nativeInit(): Long
    @JvmStatic private external fun nativeStart(handle: Long, threads: Int)
    @JvmStatic private external fun nativeSend(handle: Long, cmd: String)
    @JvmStatic private external fun nativeReadLine(handle: Long, timeoutMs: Long): String?
    @JvmStatic private external fun nativeStop(handle: Long)

    private var handle: Long = 0L
    private val started = AtomicBoolean(false)
    private var stockfishPath: String? = null

    fun ensureStarted(
        context: Context? = null,
        threads: Int = Runtime.getRuntime().availableProcessors().coerceAtLeast(2)
    ) {
        if (started.get()) return
        synchronized(this) {
            if (started.get()) return

            // Extract stockfish binary if needed
            if (context != null && stockfishPath == null) {
                extractStockfishBinary(context)
            }

            handle = nativeInit()
            if (handle == 0L) {
                throw RuntimeException("Failed to initialize Stockfish engine")
            }

            nativeStart(handle, threads)

            // UCI handshake
            nativeSend(handle, "uci")
            drainUciUntil("uciok", 5000L)

            // Configure engine
            nativeSend(handle, "setoption name Threads value $threads")
            nativeSend(handle, "setoption name Hash value 256")
            nativeSend(handle, "setoption name MultiPV value 3")
            nativeSend(handle, "isready")
            drainUciUntil("readyok", 5000L)

            started.set(true)
            Log.d(TAG, "Stockfish started successfully (threads=$threads)")
        }
    }

    private fun extractStockfishBinary(context: Context) {
        try {
            val targetFile = File(context.filesDir, "stockfish")

            // Check if already extracted
            if (targetFile.exists() && targetFile.canExecute()) {
                stockfishPath = targetFile.absolutePath
                Log.d(TAG, "Stockfish binary already exists at: $stockfishPath")
                return
            }

            // Extract from assets
            val abi = android.os.Build.SUPPORTED_ABIS[0]
            val assetPath = "stockfish/$abi/stockfish"

            context.assets.open(assetPath).use { input ->
                targetFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }

            // Make executable
            targetFile.setExecutable(true)
            stockfishPath = targetFile.absolutePath
            Log.d(TAG, "Extracted Stockfish binary to: $stockfishPath")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract Stockfish binary", e)
            // Fallback to system paths
            stockfishPath = "/data/local/tmp/stockfish"
        }
    }

    private fun drainUciUntil(token: String, timeoutMs: Long) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val line = nativeReadLine(handle, 100)
            if (line != null) {
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

        // Configure options
        nativeSend(handle, "setoption name MultiPV value $multiPv")
        if (skillLevel != null) {
            nativeSend(handle, "setoption name Skill Level value $skillLevel")
        }

        // Set position and analyze
        nativeSend(handle, "position fen $fen")
        nativeSend(handle, "go depth $depth")

        val lines = mutableListOf<EngineClient.LineDTO>()
        var bestMove: String? = null

        val deadline = System.currentTimeMillis() + 60_000
        while (System.currentTimeMillis() < deadline) {
            val line = nativeReadLine(handle, 500) ?: continue
            Log.d(TAG, "Engine: $line")

            if (line.startsWith("info ")) {
                parseInfoLine(line)?.let { info ->
                    upsertMultiPV(lines, info, multiPv)
                }
            } else if (line.startsWith("bestmove ")) {
                bestMove = line.substringAfter("bestmove ").substringBefore(' ').trim()
                break
            }
        }

        EngineClient.PositionDTO(
            lines = lines.take(multiPv),
            bestMove = bestMove
        )
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

    private data class ParsedInfo(
        val multiPv: Int,
        val depth: Int?,
        val cp: Int?,
        val mate: Int?,
        val pv: List<String>
    )

    private fun parseInfoLine(s: String): ParsedInfo? {
        var mpv = 1
        var depth: Int? = null
        var cp: Int? = null
        var mate: Int? = null
        val tokens = s.split(' ')

        var i = 0
        while (i < tokens.size) {
            when (tokens[i]) {
                "multipv" -> mpv = tokens.getOrNull(i + 1)?.toIntOrNull() ?: mpv
                "depth" -> depth = tokens.getOrNull(i + 1)?.toIntOrNull()
                "score" -> {
                    when (tokens.getOrNull(i + 1)) {
                        "cp" -> cp = tokens.getOrNull(i + 2)?.toIntOrNull()
                        "mate" -> mate = tokens.getOrNull(i + 2)?.toIntOrNull()
                    }
                }
            }
            i++
        }

        val pvIdx = tokens.indexOf("pv")
        val pv = if (pvIdx >= 0 && pvIdx + 1 < tokens.size) {
            tokens.drop(pvIdx + 1)
        } else {
            emptyList()
        }

        return ParsedInfo(multiPv = mpv, depth = depth, cp = cp, mate = mate, pv = pv)
    }

    private fun upsertMultiPV(
        lines: MutableList<EngineClient.LineDTO>,
        p: ParsedInfo,
        multiPv: Int
    ) {
        val idx = (p.multiPv - 1).coerceIn(0, multiPv - 1)
        while (lines.size <= idx) {
            lines.add(EngineClient.LineDTO())
        }
        lines[idx] = EngineClient.LineDTO(
            pv = p.pv,
            cp = p.cp,
            mate = p.mate,
            depth = p.depth,
            multiPv = p.multiPv
        )
    }

    fun shutdown() {
        if (!started.get()) return
        synchronized(this) {
            if (!started.get()) return
            try {
                nativeSend(handle, "quit")
                Thread.sleep(100)
                nativeStop(handle)
            } catch (e: Exception) {
                Log.e(TAG, "Error shutting down", e)
            } finally {
                started.set(false)
                handle = 0L
            }
        }
    }
}