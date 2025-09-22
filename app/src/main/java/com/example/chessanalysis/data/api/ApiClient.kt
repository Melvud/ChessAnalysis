package com.example.chessanalysis.data.api

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object ApiClient {

    // Документы и продакшн-база у stockfish.online без "www"
    private const val BASE_URL = "https://stockfish.online/"

    private val gson: Gson by lazy {
        // lenient = true — чтобы не падать, если сервер прислал строки вместо bool и т.п.
        GsonBuilder()
            .setLenient()
            .serializeNulls()
            .create()
    }

    private val httpClient: OkHttpClient by lazy {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .addInterceptor(logging)
            .build()
    }

    val stockfish: StockfishOnlineService by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .client(httpClient)
            .build()
            .create(StockfishOnlineService::class.java)
    }
}
