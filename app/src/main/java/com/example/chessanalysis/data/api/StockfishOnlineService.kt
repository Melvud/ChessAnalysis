package com.example.chessanalysis.data.api

import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.Query

/**
 * Документация: https://stockfish.online/docs.php
 * Основной эндпоинт: GET /api/stockfish.php?fen=...&depth=...
 * Возвращает поля: success, evaluation, mate, bestmove, continuation
 */
interface StockfishOnlineService {

    @Headers("Accept: application/json")
    @GET("api/stockfish.php")
    suspend fun analyze(
        @Query("fen") fen: String,
        @Query("depth") depth: Int = 15
    ): StockfishOnlineResponse
}

/** Сетевой DTO полностью соответствующий документации. */
data class StockfishOnlineResponse(
    val success: Any? = null,          // Может прийти true / "true"
    val evaluation: Double? = null,    // В пешках; положительно за белых
    val mate: Int? = null,             // null либо число ходов до мата
    val bestmove: String? = null,      // "bestmove e2e4 ponder ..."
    val continuation: String? = null   // строка продолжения анализа (необяз.)
) {
    fun isOk(): Boolean {
        return when (success) {
            is Boolean -> success
            is String  -> success.equals("true", ignoreCase = true)
            else       -> evaluation != null || mate != null || bestmove != null
        }
    }
}
