package com.example.chessanalysis.data.repository

import android.util.Log
import com.example.chessanalysis.data.model.*
import com.example.chessanalysis.engine.StockfishOnlineAnalyzer
import com.example.chessanalysis.data.api.StockfishOnlineService
import com.example.chessanalysis.data.util.MoveClassifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max

/**
 * Репозиторий анализирует игру: для каждого ply берёт оценку ДО и ПОСЛЕ хода
 * у Stockfish Online, считает потери в win%, классифицирует, собирает сводку.
 *
 * ВАЖНО: здесь мы больше НЕ используем старый SFOnlineAnalyzer, который
 * пытался найти "eval" в ответе и всегда падал в 0.0. Мы бьём прямо в
 * официальный JSON (evaluation/mate/bestmove/continuation).
 */
class GameRepository(
    private val stockfish: StockfishOnlineService
) {

    private val cache = ConcurrentHashMap<String, Double>() // fen -> evaluation (pawns, white POV)

    suspend fun analyzeGame(
        parsed: List<PlyData>,           // отдаёт ваш PGNParser (ply, san, fenBefore, fenAfter)
        depth: Int = 15,
        whiteElo: Int? = null,
        blackElo: Int? = null,
        result: String? = null
    ): AnalysisResult = withContext(Dispatchers.IO) {

        if (parsed.isEmpty()) {
            return@withContext AnalysisResult(emptyList(), AnalysisSummary(emptyMap(), 0.0, 0.0, 0.0, null, null))
        }

        // Сначала — параллельно вытащим оценки для всех уникальных FEN
        val fens = parsed.flatMap { listOf(it.fenBefore, it.fenAfter) }.toSet()
        coroutineScope {
            fens.map { fen ->
                async {
                    cache.computeIfAbsent(fen) {
                        fetchEvalPawns(fen, depth)
                    }
                }
            }.awaitAll()
        }

        // Затем — соберём по ходам.
        val moves = parsed.map { plyData ->
            val moverIsWhite = plyData.ply % 2 == 1
            val evalBefore = cache[plyData.fenBefore] ?: 0.0
            val evalAfter = cache[plyData.fenAfter] ?: 0.0

            val winBefore = pawnsToWinPctForMover(evalBefore, moverIsWhite)
            val winAfter  = pawnsToWinPctForMover(evalAfter, moverIsWhite)
            val lossWin   = max(0.0, winBefore - winAfter)

            val base = MoveAnalysis(
                ply = plyData.ply,
                san = plyData.san,
                fenBefore = plyData.fenBefore,
                fenAfter = plyData.fenAfter,
                evalBeforePawns = evalBefore,
                evalAfterPawns = evalAfter,
                winBefore = winBefore,
                winAfter = winAfter,
                lossWinPct = lossWin,
                bestMove = null // можно заполнить из continuation при желании
            )
            MoveClassifier.classify(base)
        }

        val counts = moves.groupingBy { it.moveClass }.eachCount()
        val acc = MoveClassifier.accuracy(moves)

        val summary = AnalysisSummary(
            counts = counts,
            accuracyTotal = acc.total,
            accuracyWhite = acc.white,
            accuracyBlack = acc.black,
            whitePerfVs = whiteElo?.let { elo -> perf(elo, scoreFromResult(result, true)) },
            blackPerfVs = blackElo?.let { elo -> perf(elo, scoreFromResult(result, false)) }
        )

        AnalysisResult(moves, summary)
    }

    // --- helpers ---

    private suspend fun fetchEvalPawns(fen: String, depth: Int): Double {
        return try {
            val res: StockfishOnlineResponse = stockfish.analyze(fen = fen, depth = depth)
            // Приоритизируем "mate": считаем как «бесконечная» оценка, но ограничим величину,
            // чтобы не ломать проценты. Знак определяем по тому, за кого «мат»:
            // если mate > 0, то мат в пользу текущей стороны (здесь считаем POV белых,
            // так что ориентируемся на четвертую часть FEN: " w " / " b ").
            val sideIsWhite = fen.contains(" w ")
            when {
                res.evaluation != null -> res.evaluation
                res.mate != null -> {
                    val sign = if (sideIsWhite) 1.0 else -1.0
                    val m = res.mate
                    // Чем меньше до мата, тем больше по модулю; 10 ходов ~ ±10 пешек.
                    val mag = (12 - (m ?: 0)).coerceAtLeast(8).toDouble()
                    sign * mag
                }
                else -> 0.0
            }
        } catch (t: Throwable) {
            Log.w("StockfishOnline", "fetchEval failed for fen='$fen': ${t.message}")
            0.0
        }
    }

    private fun scoreFromResult(result: String?, white: Boolean): Double {
        return when (result) {
            "1-0" -> if (white) 1.0 else 0.0
            "0-1" -> if (white) 0.0 else 1.0
            "1/2-1/2", "1/2" -> 0.5
            else -> 0.5 // если неизвестно — нейтрально
        }
    }

    // Очень грубая прикидка performance rating (как в ваших логах)
    private fun perf(opponentElo: Int, score: Double): Int {
        val delta = when {
            score >= 0.99 -> 800
            score >= 0.75 -> 400
            score >= 0.50 -> 0
            score >= 0.25 -> -400
            else          -> -800
        }
        return opponentElo + delta
    }
}

/** Минимальные данные по одному полуходу от существующего PGNParser. */
data class PlyData(
    val ply: Int,
    val san: String,
    val fenBefore: String,
    val fenAfter: String
)
