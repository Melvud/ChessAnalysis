package com.example.chessanalysis.data.api

import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Url

/** Ответ со списком ссылок-архивов вида .../games/YYYY/MM */
data class ArchivesResponse(val archives: List<String>)

data class PlayerDto(
    val username: String,
    val rating: Int?,
    val result: String?
)

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

/**
 * Chess.com Public Data API.
 * Берём список архивов, затем скачиваем последний архив по полной ссылке — так исключаем 404 из-за формата месяца.
 */
interface ChessComService {

    /** Список URL архивов по месяцам. */
    @GET("pub/player/{username}/games/archives")
    suspend fun getArchives(@Path("username") username: String): ArchivesResponse

    /** Загрузка игр напрямую по ссылке из archives. */
    @GET
    suspend fun getArchiveGames(@Url archiveUrl: String): GamesResponse
}
