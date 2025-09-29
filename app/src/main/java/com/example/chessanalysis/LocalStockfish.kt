package com.example.chessanalysis.local

import android.content.Context
import android.os.Build
import android.util.Log
import com.example.chessanalysis.EngineClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

/**
 * Минимальная реализация UCI-движка Stockfish для локального анализа.
 *
 * Этот класс инкапсулирует запуск нативного бинарника Stockfish, отправку
 * UCI-команд и парсинг вывода для получения оценок и лучшего хода.
 */
object LocalStockfish {

    private const val TAG = "LocalStockfish"

    // Позволяет явным вызовом задать путь к бинарю (например, из MainActivity).
    @Volatile private var customPath: String? = null
    fun setCustomBinaryPath(path: String?) {
        customPath = path?.takeIf { it.isNotBlank() }
    }

    /**
     * Определяет путь к бинарнику Stockfish, проверяя ряд «обычных» мест.
     * Возвращает абсолютный путь или имя команды (как последний шанс).
     */
    private fun resolveBinaryPath(context: Context?): String? {
        val tried = mutableListOf<String>()

        // 0) Явно заданный путь из кода
        customPath?.let { p ->
            if (File(p).exists()) return p.also { Log.d(TAG, "use customPath: $it") }
            tried += "customPath=$p"
        }

        // 1) java system property
        val sysProp = System.getProperty("STOCKFISH_PATH")
        if (!sysProp.isNullOrBlank()) {
            if (File(sysProp).exists()) return sysProp.also { Log.d(TAG, "use sysProp STOCKFISH_PATH: $it") }
            tried += "System.getProperty(STOCKFISH_PATH)=$sysProp"
        }

        // 2) /data/local/tmp/stockfish (быстрые проверки через ADB push)
        val tmp = "/data/local/tmp/stockfish"
        if (File(tmp).exists()) return tmp.also { Log.d(TAG, "use $it") }
        tried += tmp

        // 3) filesDir/engines/<abi>/stockfish (копирование из assets)
        if (context != null) {
            val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
            val inFiles = File(context.filesDir, "engines/$abi/stockfish")
            if (inFiles.exists()) return inFiles.absolutePath.also { Log.d(TAG, "use $it") }
            tried += inFiles.absolutePath
        } else {
            tried += "filesDir/engines/<abi>/stockfish (no context)"
        }

        // 4) nativeLibraryDir/libstockfish.so (вариант B через jniLibs)
        if (context != null) {
            val libDir = context.applicationInfo.nativeLibraryDir
            val so = File(libDir, "libstockfish.so")
            if (so.exists()) return so.absolutePath.also { Log.d(TAG, "use $it") }
            tried += so.absolutePath
        } else {
            tried += "nativeLibraryDir/libstockfish.so (no context)"
        }

        // 5) env variables
        listOf(System.getenv("STOCKFISH_BIN"), System.getenv("STOCKFISH_PATH"))
            .filterNotNull()
            .filter { it.isNotBlank() }
            .forEach { envPath ->
                if (File(envPath).exists()) return envPath.also { Log.d(TAG, "use env $it") }
                tried += "env=$envPath"
            }

        // 6) Последний шанс: команда stockfish из PATH (обычно не работает на Android)
        Log.w(TAG, "Stockfish not found in common locations, fallback to `stockfish` in PATH")
        Log.w(TAG, "Tried: ${tried.joinToString()}")
        return "stockfish"
    }

    private fun spawnProcess(binPath: String): Triple<Process, BufferedReader, BufferedWriter> {
        val proc = ProcessBuilder(binPath)
            .redirectErrorStream(true)
            .start()
        val reader = BufferedReader(InputStreamReader(proc.inputStream))
        val writer = BufferedWriter(OutputStreamWriter(proc.outputStream))
        return Triple(proc, reader, writer)
    }

    private fun send(writer: BufferedWriter, cmd: String) {
        writer.write(cmd)
        writer.write("\n")
        writer.flush()
    }

    /**
     * Готовит подробную оценку позиции (все линии), аналог ответа сервера /evaluate/position.
     */
    suspend fun evaluatePositionDetailed(
        fen: String,
        depth: Int = 14,
        multiPv: Int = 3,
        skillLevel: Int? = null,
        context: Context? = null
    ): EngineClient.PositionDTO = withContext(Dispatchers.IO) {
        val binPath = resolveBinaryPath(context)
        require(!binPath.isNullOrBlank()) { "Не найден бинарник Stockfish (binPath is null/blank)" }

        val (proc, reader, writer) = spawnProcess(binPath!!)
        try {
            // init UCI
            send(writer, "uci")
            while (true) {
                val line = reader.readLine() ?: break
                if (line.contains("uciok")) break
            }
            // options
            send(writer, "setoption name MultiPV value $multiPv")
            if (skillLevel != null) {
                send(writer, "setoption name UCI_LimitStrength value false")
                send(writer, "setoption name Skill Level value ${skillLevel.coerceIn(0, 20)}")
            }
            // ready
            send(writer, "isready")
            while (true) {
                val line = reader.readLine() ?: break
                if (line.contains("readyok")) break
            }
            // position & go
            send(writer, "position fen $fen")
            send(writer, "go depth $depth")

            val linesMap = mutableMapOf<Int, LineDTO>()
            var bestMove: String? = null
            val depthPattern = Pattern.compile("\\bdepth\\s+(\\d+)")
            val multiPvPattern = Pattern.compile("\\bmultipv\\s+(\\d+)")
            val cpPattern = Pattern.compile("\\bscore\\s+cp\\s+(-?\\d+)")
            val matePattern = Pattern.compile("\\bscore\\s+mate\\s+(-?\\d+)")
            val pvPattern = Pattern.compile("\\bpv\\s+(.+)")

            while (true) {
                val line = reader.readLine() ?: break
                if (line.startsWith("bestmove")) {
                    val parts = line.split(" ")
                    if (parts.size >= 2) bestMove = parts[1]
                    break
                }
                if (!line.startsWith("info")) continue
                val depthMatcher = depthPattern.matcher(line)
                val multiPvMatcher = multiPvPattern.matcher(line)
                val cpMatcher = cpPattern.matcher(line)
                val mateMatcher = matePattern.matcher(line)
                val pvMatcher = pvPattern.matcher(line)
                if (!multiPvMatcher.find()) continue
                val multipv = try { multiPvMatcher.group(1).toInt() } catch (_: Exception) { 1 }
                val depthVal = if (depthMatcher.find()) try { depthMatcher.group(1).toInt() } catch (_: Exception) { null } else null
                val cpVal = if (cpMatcher.find()) try { cpMatcher.group(1).toInt() } catch (_: Exception) { null } else null
                val mateVal = if (mateMatcher.find()) try { mateMatcher.group(1).toInt() } catch (_: Exception) { null } else null
                val pvVal = if (pvMatcher.find()) pvMatcher.group(1).trim().split(" ") else emptyList()
                if (pvVal.isNotEmpty()) {
                    linesMap[multipv] = LineDTO(
                        pv = pvVal,
                        cp = cpVal,
                        mate = mateVal,
                        depth = depthVal,
                        multiPv = multipv
                    )
                }
            }
            val sortedLines = linesMap.toSortedMap().values.toList()
            return@withContext EngineClient.PositionDTO(
                lines = sortedLines.map { line -> EngineClient.LineDTO(
                    pv = line.pv,
                    cp = line.cp,
                    mate = line.mate,
                    depth = line.depth,
                    multiPv = line.multiPv
                ) },
                bestMove = bestMove
            )
        } finally {
            try {
                writer.write("quit\n")
                writer.flush()
            } catch (_: Exception) {}
            proc.destroy()
            proc.waitFor(100, TimeUnit.MILLISECONDS)
        }
    }

    /**
     * Упрощённая оценка позиции — возвращает число пешек (или ±30 при мате).
     */
    suspend fun evaluatePosition(
        fen: String,
        depth: Int = 14,
        multiPv: Int = 3,
        context: Context? = null
    ): Float = withContext(Dispatchers.IO) {
        val d = evaluatePositionDetailed(fen, depth, multiPv, null, context)
        val first = d.lines.firstOrNull()
        when {
            first?.mate != null -> if (first.mate!! > 0) 30f else -30f
            first?.cp != null -> first.cp!!.toFloat() / 100f
            else -> 0f
        }
    }

    /**
     * Обёртка для EngineClient.analyzeFen: формирует StockfishResponse, как у сервера.
     */
    suspend fun evaluateFen(
        fen: String,
        depth: Int = 14,
        multiPv: Int = 3,
        skillLevel: Int? = null,
        context: Context? = null
    ): EngineClient.StockfishResponse = withContext(Dispatchers.IO) {
        runCatching {
            val dto = evaluatePositionDetailed(
                fen = fen,
                depth = depth,
                multiPv = multiPv,
                skillLevel = skillLevel,
                context = context
            )
            val top = dto.lines.firstOrNull()
            val evaluationPawns: Double? = top?.cp?.let { it / 100.0 }
            EngineClient.StockfishResponse(
                success = true,
                evaluation = evaluationPawns,
                mate = top?.mate,
                bestmove = dto.bestMove,
                continuation = top?.pv?.joinToString(" "),
                error = null
            )
        }.getOrElse { e ->
            EngineClient.StockfishResponse(
                success = false,
                error = e.message ?: "unknown_error"
            )
        }
    }

    data class LineDTO(
        val pv: List<String>,
        val cp: Int?,
        val mate: Int?,
        val depth: Int?,
        val multiPv: Int
    )
}
