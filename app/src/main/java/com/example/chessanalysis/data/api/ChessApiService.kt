package com.example.chessanalysis.data.api

import retrofit2.http.Body
import retrofit2.http.Headers
import retrofit2.http.POST

data class ChessApiRequest(val fen: String, val depth: Int = 12)
data class ChessApiResponse(
    val eval: Double?,
    val mate: Int?,
    val bestMove: String?,
    val winChance: Double?
)

interface ChessApiService {
    @Headers("Content-Type: application/json")
    @POST("v1")
    suspend fun evaluatePosition(@Body req: ChessApiRequest): ChessApiResponse
}
