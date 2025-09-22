package com.example.chessanalysis.data.repository

import com.example.chessanalysis.data.api.ChessApiService
import com.example.chessanalysis.data.api.ChessComService
import com.example.chessanalysis.data.api.LichessService
import com.example.chessanalysis.data.api.StockfishOnlineService
import com.example.chessanalysis.data.model.*
import com.example.chessanalysis.engine.StockfishOnlineAnalyzer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import retrofit2.HttpException
import java.net.SocketTimeoutException

class GameRepository(
    private val lichess: LichessService,
    private val chessCom: ChessComService,
    private val stockfishOnline: StockfishOnlineService,
    private val chessApi: ChessApiService
) {
    private val analyzer = StockfishOnlineAnalyzer(stockfishOnline, chessApi)

    // ... (Loading games from Lichess/Chess.com remains unchanged)

    /** Analyze a game and return the AnalysisResult (with summary and moves). */
    suspend fun analyzeGame(game: GameSummary, depth: Int = 15): AnalysisResult {
        val d = depth.coerceIn(1, 15)
        return try {
            // Pass PGN to analyzer (the PGN includes move list and headers)
            analyzer.analyzeGame(game.pgn, depth = d)
        } catch (e: HttpException) {
            val body = e.response()?.errorBody()?.string().orEmpty()
            throw IllegalStateException("Analysis HTTP ${e.code()} ${e.message()} ${body.take(300)}", e)
        } catch (e: SocketTimeoutException) {
            throw IllegalStateException("Analysis timeout: ${e.message ?: "no details"}", e)
        } catch (e: Throwable) {
            val msg = e.message?.ifBlank { e::class.java.simpleName } ?: e::class.java.simpleName
            throw IllegalStateException("Analysis error: $msg", e)
        }
    }
}
