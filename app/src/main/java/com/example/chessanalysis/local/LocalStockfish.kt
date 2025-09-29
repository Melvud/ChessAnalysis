package com.example.chessanalysis.local

import android.content.Context
import android.util.Log
import com.example.chessanalysis.EngineClient
import com.example.chessanalysis.MoveClass
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Вариант 2: запускаем libstockfish.so внутри процесса через наш нативный лаунчер (libsflauncher.so).
 * Никаких exec(), никаких внешних бинарников — чисто JNI.
 *
 * JNI-часть предоставляет функции:
 *  - nativeInit(): Long            — создать движок, вернуть handle
 *  - nativeStart(handle, threads)  — старт потока движка; готов принять UCI
 *  - nativeSend(handle, cmd)       — отправить команду (заканчивать \n не нужно)
 *  - nativeReadLine(handle, timeoutMs): String? — читать строку из stdout движка
 *  - nativeStop(handle)            — остановить и освободить ресурсы
 *
 * Мы поверх этого даём удобные методы evaluateFen(..)/evaluatePositionDetailed(..),
 * которые выдают те же DTO, что и сервер (через EngineClient.PositionDTO и др.).
 */
object LocalStockfish {

    private const val TAG = "LocalStockfish"

    // Загружаем наш лоадер (он динамически тянет libstockfish.so)
    init {
        try {
            System.loadLibrary("sflauncher")
            Log.d(TAG, "libsflauncher loaded")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Failed to load libsflauncher.so: ${e.message}", e)
            throw e
        }
    }

    // ---- JNI ----
    @JvmStatic private external fun nativeInit(): Long
    @JvmStatic private external fun nativeStart(handle: Long, threads: Int)
    @JvmStatic private external fun nativeSend(handle: Long, cmd: String)
    @JvmStatic private external fun nativeReadLine(handle: Long, timeoutMs: Long): String?
    @JvmStatic private external fun nativeStop(handle: Long)

    // ---- Состояние ----
    private var handle: Long = 0L
    private val started = AtomicBoolean(false)

    /**
     * Инициализация (идемпотентная).
     * Передавай applicationContext, чтобы в UI было удобно вызывать.
     */
    fun ensureStarted(context: Context? = null, threads: Int = Runtime.getRuntime().availableProcessors().coerceAtLeast(2)) {
        if (started.get()) return
        synchronized(this) {
            if (started.get()) return
            handle = nativeInit()
            nativeStart(handle, threads)
            // стандартный uci-рукопожатие
            nativeSend(handle, "uci")
            // подождём ID и опции
            drainUciUntil("uciok", 2000L)
            // обязательные опции под твой сервер
            nativeSend(handle, "setoption name Threads value $threads")
            nativeSend(handle, "setoption name MultiPV value 3")
            nativeSend(handle, "isready")
            drainUciUntil("readyok", 2000L)
            started.set(true)
            Log.d(TAG, "Stockfish in-proc started (threads=$threads)")
        }
    }

    private fun drainUciUntil(token: String, timeoutMs: Long) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val line = nativeReadLine(handle, 100)
            if (line != null) {
                if (line.contains(token, ignoreCase = true)) return
            }
        }
        // не падаем — движок всё равно может быть готов
    }

    /**
     * Полный детальный ответ (для /evaluate/position): строки PV, bestmove.
     */
    suspend fun evaluatePositionDetailed(
        fen: String,
        depth: Int,
        multiPv: Int,
        skillLevel: Int? = null,
        context: Context? = null
    ): EngineClient.PositionDTO = withContext(Dispatchers.IO) {
        ensureStarted(context)
        // подстроим MultiPV/SkillLevel если надо
        nativeSend(handle, "setoption name MultiPV value $multiPv")
        if (skillLevel != null) {
            nativeSend(handle, "setoption name Skill Level value $skillLevel")
        }
        nativeSend(handle, "position fen $fen")
        nativeSend(handle, "go depth $depth")

        val lines = mutableListOf<EngineClient.LineDTO>()
        var bestMove: String? = null

        val deadline = System.currentTimeMillis() + 60_000 // safety
        while (System.currentTimeMillis() < deadline) {
            val line = nativeReadLine(handle, 500) ?: continue
            // примеры:
            // info depth 14 seldepth 22 multipv 1 score cp 16 ... pv e2e4 e7e5 ...
            // info depth 14 ... score mate -3 ... pv ...
            // bestmove e2e4 ponder e7e5
            if (line.startsWith("info ")) {
                parseInfoLine(line)?.let { upsertMultiPV(lines, it, multiPv) }
            } else if (line.startsWith("bestmove ")) {
                bestMove = line.substringAfter("bestmove ").substringBefore(' ').trim().ifEmpty { null }
                break
            }
        }
        EngineClient.PositionDTO(lines = lines.take(multiPv), bestMove = bestMove)
    }

    /**
     * Упрощённый ответ (для analyzeFen): компактный JSON c evaluation/mate/bestmove/pv.
     */
    suspend fun evaluateFen(
        fen: String,
        depth: Int,
        multiPv: Int,
        skillLevel: Int? = null,
        context: Context? = null
    ): EngineClient.StockfishResponse = withContext(Dispatchers.IO) {
        val pos = evaluatePositionDetailed(fen, depth, multiPv, skillLevel, context)
        val top = pos.lines.firstOrNull()
        val evaluationPawns: Double? = top?.cp?.let { it / 100.0 }
        EngineClient.StockfishResponse(
            success = true,
            evaluation = evaluationPawns,
            mate = top?.mate,
            bestmove = pos.bestMove,
            continuation = top?.pv?.joinToString(" "),
            error = null
        )
    }

    // --- парсер info ---
    private data class ParsedInfo(
        val multiPv: Int,
        val depth: Int?,
        val cp: Int?,
        val mate: Int?,
        val pv: List<String>
    )

    private fun parseInfoLine(s: String): ParsedInfo? {
        // очень толерантный парсинг
        var mpv = 1
        var depth: Int? = null
        var cp: Int? = null
        var mate: Int? = null
        val tokens = s.split(' ')
        var i = 0
        while (i < tokens.size) {
            when (tokens[i]) {
                "multipv" -> mpv = tokens.getOrNull(i + 1)?.toIntOrNull() ?: mpv
                "depth"   -> depth = tokens.getOrNull(i + 1)?.toIntOrNull() ?: depth
                "score"   -> {
                    when (tokens.getOrNull(i + 1)) {
                        "cp"   -> cp = tokens.getOrNull(i + 2)?.toIntOrNull()
                        "mate" -> mate = tokens.getOrNull(i + 2)?.toIntOrNull()
                    }
                }
            }
            i++
        }
        val pvIdx = tokens.indexOf("pv")
        val pv = if (pvIdx >= 0 && pvIdx + 1 < tokens.size) tokens.drop(pvIdx + 1) else emptyList()
        return ParsedInfo(multiPv = mpv, depth = depth, cp = cp, mate = mate, pv = pv)
    }

    private fun upsertMultiPV(
        lines: MutableList<EngineClient.LineDTO>,
        p: ParsedInfo,
        multiPv: Int
    ) {
        val idx = (p.multiPv - 1).coerceIn(0, multiPv - 1)
        while (lines.size <= idx) lines += EngineClient.LineDTO()
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
                // шанс корректно выйти
                repeat(10) {
                    if (nativeReadLine(handle, 50) == null) return@repeat
                }
            } catch (_: Throwable) {
            } finally {
                nativeStop(handle)
                started.set(false)
                handle = 0L
            }
        }
    }
}
