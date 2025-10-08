package com.github.movesense.net

import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.scalars.ScalarsConverterFactory
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.GET
import retrofit2.http.Path
import com.squareup.moshi.Json
import com.squareup.moshi.Moshi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Chess.com API
 * GET https://api.chess.com/pub/player/{username}
 */
private interface ChessComService {
    @GET("pub/player/{username}")
    suspend fun getUser(@Path("username") username: String): ChessComUserDto
}

private data class ChessComUserDto(
    @Json(name = "avatar") val avatar: String?
)

/**
 * Lichess API — без строгой модели.
 * GET https://lichess.org/api/user/{username}
 * Возвратим true, если такой пользователь существует (200 ОК),
 * а саму ссылку на аватар сформируем через images.lichess.org/avatar/{username}
 */
private interface LichessService {
    @GET("api/user/{username}")
    suspend fun getUserRaw(@Path("username") username: String): String
}

object AvatarRepository {

    private val logging = HttpLoggingInterceptor().setLevel(HttpLoggingInterceptor.Level.BASIC)

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .addInterceptor(logging)
            .build()
    }

    private val moshi: Moshi by lazy { Moshi.Builder().build() }

    private val chessComRetrofit: Retrofit by lazy {
        Retrofit.Builder()
            .baseUrl("https://api.chess.com/")
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .client(httpClient)
            .build()
    }

    private val lichessRetrofit: Retrofit by lazy {
        Retrofit.Builder()
            .baseUrl("https://lichess.org/")
            .addConverterFactory(ScalarsConverterFactory.create())
            .client(httpClient)
            .build()
    }

    private val chessCom by lazy { chessComRetrofit.create(ChessComService::class.java) }
    private val lichess by lazy { lichessRetrofit.create(LichessService::class.java) }

    /**
     * Вернёт прямую ссылку на аватар Chess.com или null.
     */
    suspend fun fetchChessComAvatar(usernameRaw: String): String? = withContext(Dispatchers.IO) {
        val u = usernameRaw.trim().lowercase()
        if (u.isEmpty()) return@withContext null
        return@withContext runCatching { chessCom.getUser(u).avatar }
            .getOrNull()
    }

    /**
     * Вернёт URL на аватар Lichess, если пользователь существует; иначе null.
     * На Lichess ссылка стабильна вида https://images.lichess.org/avatar/{username}
     */
    suspend fun fetchLichessAvatar(usernameRaw: String): String? = withContext(Dispatchers.IO) {
        val u = usernameRaw.trim().lowercase()
        if (u.isEmpty()) return@withContext null
        val ok = runCatching { lichess.getUserRaw(u) }.isSuccess
        return@withContext if (ok) "https://images.lichess.org/avatar/$u" else null
    }
}
