package com.example.chessanalysis.data.repository

import com.example.chessanalysis.data.api.ChessComService
import com.example.chessanalysis.data.api.LichessService
import com.example.chessanalysis.data.api.StockfishOnlineService
import com.example.chessanalysis.data.model.*
import com.example.chessanalysis.engine.StockfishOnlineAnalyzer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.buffer
import okio.source
import org.json.JSONObject
import retrofit2.HttpException
import java.net.SocketTimeoutException

class GameRepository(
    private val lichess: LichessService,
    private val chessCom: ChessComService,
    private val stockfishOnline: StockfishOnlineService
) {

    private val analyzer = StockfishOnlineAnalyzer(stockfishOnline)

    /** Извлечь значение PGN-тега вида [Key "Value"] */
    private fun pgnTag(pgn: String, key: String): String? {
        val re = Regex("\\[$key\\s+\"([^\"]+)\"\\]")
        return re.find(pgn)?.groupValues?.getOrNull(1)
    }

    /** Безопасно достаём ник из Lichess JSON */
    private fun lichessPlayerName(playersObj: JSONObject?, color: String, pgn: String): String {
        val side = playersObj?.optJSONObject(color)
        val fromUser = side?.optJSONObject("user")?.optString("name")?.takeIf { it.isNotBlank() }
        val fromUserId = side?.optString("userId")?.takeIf { it.isNotBlank() }
        val fromName = side?.optString("name")?.takeIf { it.isNotBlank() }
        val fromTag = pgnTag(pgn, if (color == "white") "White" else "Black")
        return fromUser ?: fromUserId ?: fromName ?: fromTag ?: if (color == "white") "White" else "Black"
    }

    // --------------------------
    // ЗАГРУЗКА ПАРТИЙ
    // --------------------------

    suspend fun loadGames(site: ChessSite, username: String, max: Int = 10): List<GameSummary> =
        withContext(Dispatchers.IO) {
            when (site) {
                ChessSite.LICHESS -> {
                    // Lichess Export: NDJSON (по одной партии в строке)
                    val resp = lichess.getGames(
                        username = username,
                        max = max,
                        moves = true,
                        pgnInJson = true,
                        opening = true
                    )
                    val body = resp.body() ?: throw IllegalStateException("Lichess: empty body")
                    val list = mutableListOf<GameSummary>()
                    body.byteStream().source().buffer().use { buf ->
                        while (true) {
                            val line = buf.readUtf8Line() ?: break
                            if (line.isBlank()) continue
                            val obj = JSONObject(line)

                            val pgn = obj.optString("pgn")
                            if (pgn.isBlank()) continue

                            val id = obj.optString("id", System.nanoTime().toString())
                            val players = obj.optJSONObject("players")
                            val white = lichessPlayerName(players, "white", pgn)
                            val black = lichessPlayerName(players, "black", pgn)

                            val result = pgnTag(pgn, "Result")
                                ?: obj.optString("status", null)

                            val tc = obj.optString("speed", null)
                                ?: pgnTag(pgn, "TimeControl")

                            val startMs = obj.optLong("createdAt", 0L).let { if (it == 0L) null else it }
                            val endMs = obj.optLong("lastMoveAt", 0L).let { if (it == 0L) null else it }

                            list += GameSummary(
                                id = id,
                                site = ChessSite.LICHESS,
                                white = white,
                                black = black,
                                result = result,
                                startTime = startMs,
                                endTime = endMs,
                                timeControl = tc,
                                pgn = pgn
                            )
                        }
                    }
                    list
                }

                ChessSite.CHESS_COM -> {
                    val archives = chessCom.getArchives(username).archives
                    val latest = archives.lastOrNull() ?: return@withContext emptyList()
                    val games = chessCom.getArchiveGames(latest).games

                    games.takeLast(max).map { g ->
                        val white = g.white.username ?: pgnTag(g.pgn ?: "", "White") ?: "White"
                        val black = g.black.username ?: pgnTag(g.pgn ?: "", "Black") ?: "Black"
                        val result = g.pgn?.let { pgnTag(it, "Result") } ?: g.white.result ?: g.black.result
                        GameSummary(
                            id = g.url ?: System.nanoTime().toString(),
                            site = ChessSite.CHESS_COM,
                            white = white,
                            black = black,
                            result = result,
                            startTime = g.start_time?.times(1000),
                            endTime = g.end_time?.times(1000),
                            timeControl = g.time_control,
                            pgn = g.pgn.orEmpty()
                        )
                    }
                }
            }
        }

    // --------------------------
    // АНАЛИЗ ПАРТИИ
    // --------------------------

    /**
     * Запускает полный анализ партии через StockfishOnline (v2).
     * depth ограничиваем в [1..15] согласно документации сервиса.
     */
    suspend fun analyzeGame(game: GameSummary, depth: Int = 15): AnalysisResult {
        val d = depth.coerceIn(1, 15)
        try {
            // ВАЖНО: передаём PGN как строку
            return analyzer.analyzeGame(game.pgn, depth = d)
        } catch (e: HttpException) {
            val body = e.response()?.errorBody()?.string().orEmpty()
            throw IllegalStateException("StockfishOnline HTTP ${e.code()} ${e.message()} ${body.take(300)}", e)
        } catch (e: SocketTimeoutException) {
            throw IllegalStateException("StockfishOnline timeout: ${e.message ?: "no details"}", e)
        } catch (e: Throwable) {
            val msg = e.message?.ifBlank { e::class.java.simpleName } ?: e::class.java.simpleName
            throw IllegalStateException("StockfishOnline error: $msg", e)
        }
    }
}
