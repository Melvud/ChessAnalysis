package com.example.chessanalysis.data.api

import com.google.gson.annotations.SerializedName
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.Query

/**
 * Документация: https://stockfish.online/docs.php
 * Корректный публичный эндпоинт: /api/stockfish.php
 * Пример: https://stockfish.online/api/stockfish.php?fen=...&depth=12
 */
interface StockfishOnlineService {

    @Headers("Accept: application/json")
    @GET("api/stockfish.php")
    suspend fun analyze(
        // Важно: FEN может содержать пробелы/слэши, Retrofit их закодирует корректно.
        @Query("fen", encoded = true) fen: String,
        @Query("depth") depth: Int
    ): Response<StockfishV2Response>

    /**
     * Фолбэк: тот же запрос, но читаем как сырой текст.
     * Нужен на случай, когда сервер отдаёт text/plain/HTML вместо JSON.
     */
    @GET("api/stockfish.php")
    suspend fun analyzeRaw(
        @Query("fen", encoded = true) fen: String,
        @Query("depth") depth: Int
    ): Response<ResponseBody>
}

/** Мини-DTO под v2 JSON. Поля названы как в доке. */
data class StockfishV2Response(
    val success: Boolean = true,
    /** Оценка в пешках за белых (Double). Может отсутствовать при мете. */
    val evaluation: Double? = null,
    /** Мат в N (положит. — за белых, отрицат. — за чёрных). */
    val mate: Int? = null,
    /** Строка bestmove: "bestmove e2e4 ponder e7e5" или просто "e2e4". */
    @SerializedName("bestmove") val bestmove: String? = null
)
