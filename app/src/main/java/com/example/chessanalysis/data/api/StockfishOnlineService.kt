package com.example.chessanalysis.engine

import com.example.chessanalysis.data.api.ChessApiService
import com.example.chessanalysis.data.api.ChessApiResponse
import com.example.chessanalysis.data.api.StockfishOnlineService
import com.example.chessanalysis.data.model.AnalysisResult
import com.example.chessanalysis.data.model.AnalysisSummary
import com.example.chessanalysis.data.model.MoveAnalysis
import com.example.chessanalysis.data.model.MoveClass
import com.example.chessanalysis.data.model.classifyByLossWinPct
import com.example.chessanalysis.data.util.PGNParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * Game analysis using Stockfish (online) and Chess-API for parallel evaluation.
 * - Combines Lichess and Chess.com move classifications into one unified scheme.
 * - Uses win-percentage based evaluation for move classification.
 * - Leverages chess-api.com for parallel position analysis to speed up results.
 */
class StockfishOnlineAnalyzer(
    private val stockfishApi: StockfishOnlineService,
    private val chessApi: ChessApiService
) {
    /** Convert mate/score to evaluation in pawns (using ±1000 for mate scenarios). */
    private fun pawnsFrom(mate: Int?, eval: Double?): Double {
        return when {
            mate != null && mate != 0 -> if (mate > 0) 1000.0 else -1000.0
            eval != null -> eval
            else -> 0.0
        }
    }

    /** Analyze a PGN game and return the result with move-by-move analysis and summary. */
    suspend fun analyzeGame(pgn: String, depth: Int = 15): AnalysisResult = withContext(Dispatchers.IO) {
        val parsedGame = PGNParser.parseGame(pgn)
        val positions = parsedGame.positions
        if (positions.isEmpty()) {
            // Return empty analysis if no moves
            return@withContext AnalysisResult(
                moves = emptyList(),
                summary = AnalysisSummary(
                    counts = emptyMap(),
                    accuracyTotal = 100.0,
                    accuracyWhite = 100.0,
                    accuracyBlack = 100.0,
                    whitePerfVs = null,
                    blackPerfVs = null
                )
            )
        }
        val d = depth.coerceIn(1, 15)

        // 1. Parallel evaluation of all positions (before and after each move) using ChessApi
        val evalBeforeJobs = positions.map { pos ->
            async { chessApi.evaluatePosition(/** Compose request for position before move */
                ChessApiRequest(fen = pos.fenBefore, depth = d)) }
        }
        val evalAfterJobs = positions.map { pos ->
            async { chessApi.evaluatePosition(ChessApiRequest(fen = pos.fenAfter, depth = d)) }
        }
        val evalBeforeList: List<ChessApiResponse> = evalBeforeJobs.awaitAll()
        val evalAfterList: List<ChessApiResponse> = evalAfterJobs.awaitAll()

        // 2. Build MoveAnalysis list for each half-move
        val moveAnalyses = positions.mapIndexed { i, pos ->
            val moverIsWhite = (i % 2 == 0)  // White moves on ply 1,3,... (i=0 means White)
            val beforeDto = evalBeforeList[i]
            val afterDto = evalAfterList[i]

            // Convert evaluations to pawn units and get best move from before-position
            val evalBefore = pawnsFrom(beforeDto.mate, beforeDto.eval)
            val evalAfter = pawnsFrom(afterDto.mate, afterDto.eval)
            val bestMoveUci = beforeDto.bestMove ?: ""

            // Compute win probability for the mover (logistic model centered at 0.0 -> 50%)
            val eMoverBefore = if (moverIsWhite) evalBefore else -evalBefore
            val eMoverAfter  = if (moverIsWhite) evalAfter  else -evalAfter
            val winBefore = 100.0 * (1.0 / (1.0 + exp(-0.73 * eMoverBefore)))
            val winAfter  = 100.0 * (1.0 / (1.0 + exp(-0.73 * eMoverAfter)))

            // Loss in win% for the move (clamped to 0 if the move improved or maintained advantage)
            val lossWinPct = if (winAfter >= winBefore) 0.0 else (winBefore - winAfter)

            // Classify move by loss in win% (unified MoveClass categories)
            val moveClass: MoveClass = classifyByLossWinPct(lossWinPct)

            MoveAnalysis(
                ply = i + 1,
                san = if (pos.san.isBlank()) "-" else pos.san,
                fenBefore = pos.fenBefore,
                fenAfter = pos.fenAfter,
                evalBeforePawns = evalBefore,
                evalAfterPawns = evalAfter,
                winBefore = winBefore,
                winAfter = winAfter,
                lossWinPct = lossWinPct,
                bestMove = if (bestMoveUci.isBlank()) null else bestMoveUci,
                moveClass = moveClass
            )
        }

        // 3. Compute aggregated statistics for summary
        // Counts of moves by classification (overall)
        val counts: Map<MoveClass, Int> = moveAnalyses.groupingBy { it.moveClass }.eachCount()

        // Accuracy metrics: 100% minus average loss in win% (for total and each side)
        fun average(xs: List<Double>): Double = if (xs.isEmpty()) 0.0 else xs.sum() / xs.size
        val totalAcc = (100.0 - average(moveAnalyses.map { it.lossWinPct })).coerceIn(0.0, 100.0)
        val whiteMoves = moveAnalyses.filter { it.ply % 2 == 1 }
        val blackMoves = moveAnalyses.filter { it.ply % 2 == 0 }
        val whiteAcc = (100.0 - average(whiteMoves.map { it.lossWinPct })).coerceIn(0.0, 100.0)
        val blackAcc = (100.0 - average(blackMoves.map { it.lossWinPct })).coerceIn(0.0, 100.0)

        // Performance rating: compute for each side if ratings and result are available
        // Parse ratings from PGN tags
        fun tagValue(key: String): Int? {
            val regex = Regex("\\[$key\\s+\"(\\d+)\"\\]")
            return regex.find(pgn)?.groupValues?.get(1)?.toIntOrNull()
        }
        val whiteElo = tagValue("WhiteElo")
        val blackElo = tagValue("BlackElo")

        // Parse result from PGN tags (e.g., "1-0", "0-1", "1/2-1/2")
        val resultTag = Regex("\\[Result\\s+\"(.+?)\"\\]").find(pgn)?.groupValues?.get(1)
        fun scoreFor(result: String?, forWhite: Boolean): Double? = when(result) {
            "1-0" -> if (forWhite) 1.0 else 0.0
            "0-1" -> if (forWhite) 0.0 else 1.0
            "1/2-1/2", "1/2-½", "½-1/2" -> 0.5  // handle half-point notation variants
            else -> null
        }
        val whiteScore = scoreFor(resultTag, forWhite = true)
        val blackScore = scoreFor(resultTag, forWhite = false)

        var perfWhite: Int? = null
        var perfBlack: Int? = null
        if (blackElo != null && whiteScore != null) {
            // Performance = opponent rating + (800 * score - 400), clamp to realistic range
            val delta = (800.0 * whiteScore - 400.0).toInt()
            perfWhite = (blackElo + delta).coerceIn(500, 3200)
        }
        if (whiteElo != null && blackScore != null) {
            val delta = (800.0 * blackScore - 400.0).toInt()
            perfBlack = (whiteElo + delta).coerceIn(500, 3200)
        }

        // 4. Build analysis summary
        val summary = AnalysisSummary(
            counts = counts,
            accuracyTotal = totalAcc,
            accuracyWhite = whiteAcc,
            accuracyBlack = blackAcc,
            whitePerfVs = perfWhite,
            blackPerfVs = perfBlack
        )

        return@withContext AnalysisResult(moves = moveAnalyses, summary = summary)
    }
}
