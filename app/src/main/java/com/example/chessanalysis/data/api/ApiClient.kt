package com.example.chessanalysis.data.api

import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

/**
 * Единая фабрика Retrofit-сервисов с тайм-аутами.
 */
object ApiClient {

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /** Lichess */
    val lichessService: LichessService by lazy {
        Retrofit.Builder()
            .baseUrl("https://lichess.org/")
            .client(httpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(LichessService::class.java)
    }

    /** Chess.com */
    val chessComService: ChessComService by lazy {
        Retrofit.Builder()
            .baseUrl("https://api.chess.com/")
            .client(httpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ChessComService::class.java)
    }

    /** Stockfish Online (обрати внимание на www. и закрывающий слэш) */
    val stockfishOnlineService: StockfishOnlineService by lazy {
        Retrofit.Builder()
            .baseUrl("https://www.stockfish.online/") // важен закрывающий слэш
            .client(httpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(StockfishOnlineService::class.java)
    }
}
