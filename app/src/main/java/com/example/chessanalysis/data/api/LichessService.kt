package com.example.chessanalysis.data.api

import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Streaming

/**
 * Lichess: экспорт партий пользователя как NDJSON.
 * Каждая строка — JSON-объект с полем "pgn" при pgnInJson=true.
 */
interface LichessService {
    @Streaming
    @Headers("Accept: application/x-ndjson")
    @GET("api/games/user/{username}")
    suspend fun getGames(
        @Path("username") username: String,
        @Query("max") max: Int = 10,
        @Query("moves") moves: Boolean = true,
        @Query("pgnInJson") pgnInJson: Boolean = true,
        @Query("opening") opening: Boolean = true
    ): Response<ResponseBody>
}
