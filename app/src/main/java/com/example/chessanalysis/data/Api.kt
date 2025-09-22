package com.example.chessanalysis.data.api

import com.example.chessanalysis.data.model.ChessSite
import com.google.gson.annotations.SerializedName
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.Path
import retrofit2.http.POST
import retrofit2.http.Query
import retrofit2.http.Streaming
import retrofit2.http.Url
import java.util.concurrent.TimeUnit

/** Сервис Lichess: выдаёт партии в NDJSON. */
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

/** Chess.com: список архивов и сами партии. */
data class ArchivesResponse(val archives: List<String>)
data class PlayerDto(val username: String, val rating: Int?, val result: String?)
data class ChessComGameDto(
    val url: String?,
    val pgn: String,
    val end_time: Long?,
    val start_time: Long?,
    val time_control: String?,
    val white: PlayerDto,
    val black: PlayerDto
)
data class GamesResponse(val games: List<ChessComGameDto>)

interface ChessComService {
    @GET("pub/player/{username}/games/archives")
    suspend fun getArchives(@Path("username") username: String): ArchivesResponse

    @GET
    suspend fun getArchiveGames(@Url archiveUrl: String): GamesResponse
}

/** Старый Stockfish для совместимости: можно оставить, но не используется. */
interface StockfishOnlineService {
    @Headers("Accept: application/json")
    @GET("api/stockfish.php")
    suspend fun analyze(
        @Query("fen", encoded = true) fen: String,
        @Query("depth") depth: Int
    ): Response<StockfishV2Response>

    @GET("api/stockfish.php")
    suspend fun analyzeRaw(
        @Query("fen", encoded = true) fen: String,
        @Query("depth") depth: Int
    ): Response<ResponseBody>
}

data class StockfishV2Response(
    val success: Boolean = true,
    val evaluation: Double? = null,
    val mate: Int? = null,
    @SerializedName("bestmove") val bestmove: String? = null
)

/** API chess-api.com: POST /v1. */
data class ChessApiRequest(
    val fen: String,
    val variants: Int = 1,
    val depth: Int = 12,
    @SerializedName("maxThinkingTime") val maxThinkingTime: Int = 50,
    @SerializedName("searchmoves") val searchMoves: String? = null
)

data class ChessApiResponse(
    val text: String?,
    val eval: Double?,
    val centipawns: Int?,
    val move: String?,
    val fen: String?,
    val depth: Int?,
    val winChance: Double?,
    val mate: Int?
)

interface ChessApiService {
    @Headers("Content-Type: application/json")
    @POST("v1")
    suspend fun analyzePosition(@Body request: ChessApiRequest): ChessApiResponse
}

/** Фабрика Retrofit-сервисов. */
object ApiClient {
    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    val lichessService: LichessService by lazy {
        Retrofit.Builder()
            .baseUrl("https://lichess.org/")
            .client(httpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(LichessService::class.java)
    }

    val chessComService: ChessComService by lazy {
        Retrofit.Builder()
            .baseUrl("https://api.chess.com/")
            .client(httpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ChessComService::class.java)
    }

    val stockfishOnlineService: StockfishOnlineService by lazy {
        Retrofit.Builder()
            .baseUrl("https://www.stockfish.online/")
            .client(httpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(StockfishOnlineService::class.java)
    }

    val chessApiService: ChessApiService by lazy {
        Retrofit.Builder()
            .baseUrl("https://chess-api.com/")
            .client(httpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ChessApiService::class.java)
    }
}
