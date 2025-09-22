package com.example.chessanalysis.data.api

import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

/**
 * Unified Retrofit service factory with timeouts.
 */
object ApiClient {

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /** Lichess API service */
    val lichessService: LichessService by lazy {
        Retrofit.Builder()
            .baseUrl("https://lichess.org/")
            .client(httpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(LichessService::class.java)
    }

    /** Chess.com API service */
    val chessComService: ChessComService by lazy {
        Retrofit.Builder()
            .baseUrl("https://api.chess.com/")
            .client(httpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ChessComService::class.java)
    }

    /** Stockfish Online service (for single-position analysis) */
    val stockfishOnlineService: StockfishOnlineService by lazy {
        Retrofit.Builder()
            .baseUrl("https://www.stockfish.online/")  // note trailing slash
            .client(httpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(StockfishOnlineService::class.java)
    }

    /** Chess-API.com service (for parallel analysis) */
    val chessApiService: ChessApiService by lazy {
        Retrofit.Builder()
            .baseUrl("https://chess-api.com/")
            .client(httpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ChessApiService::class.java)
    }
}
