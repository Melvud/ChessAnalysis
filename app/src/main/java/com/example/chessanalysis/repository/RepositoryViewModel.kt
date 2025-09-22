package com.example.chessanalysis.repository

import com.example.chessanalysis.data.api.ChessApiService
import com.example.chessanalysis.data.api.ChessComService
import com.example.chessanalysis.data.api.LichessService
import com.example.chessanalysis.data.api.StockfishOnlineService
import com.example.chessanalysis.data.model.*
import com.example.chessanalysis.engine.StockfishOnlineAnalyzer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okio.buffer
import okio.source
import org.json.JSONObject
import retrofit2.HttpException
import java.net.SocketTimeoutException
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlin.math.roundToInt

/** Репозиторий: отвечает за загрузку партий и анализ PGN. */
class GameRepository(
    private val lichess: LichessService,
    private val chessCom: ChessComService,
    private val stockfishOnline: StockfishOnlineService,
    private val chessApi: ChessApiService
) {
    private val analyzer = StockfishOnlineAnalyzer(chessApi)

    /** Получить PGN-тег. */
    private fun pgnTag(pgn: String, key: String): String? {
        val re = Regex("\\[$key\\s+\"([^\"]+)\"\\]")
        return re.find(pgn)?.groupValues?.getOrNull(1)
    }

    /** Читаем имя игрока на Lichess. */
    private fun lichessPlayerName(playersObj: JSONObject?, color: String, pgn: String): String {
        val side = playersObj?.optJSONObject(color)
        val fromUser = side?.optJSONObject("user")?.optString("name")?.takeIf { it.isNotBlank() }
        val fromUserId = side?.optString("userId")?.takeIf { it.isNotBlank() }
        val fromName = side?.optString("name")?.takeIf { it.isNotBlank() }
        val fromTag = pgnTag(pgn, if (color == "white") "White" else "Black")
        return fromUser ?: fromUserId ?: fromName ?: fromTag ?: if (color == "white") "White" else "Black"
    }

    /** Загрузить партии с сервиса site. */
    suspend fun loadGames(site: ChessSite, username: String, max: Int = 10): List<GameSummary> =
        withContext(Dispatchers.IO) {
            when (site) {
                ChessSite.LICHESS -> {
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
                            val result = pgnTag(pgn, "Result") ?: obj.optString("status", null)
                            val tc = obj.optString("speed", null) ?: pgnTag(pgn, "TimeControl")
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

    /** Анализировать одну партию (глубина ограничена 18). */
    suspend fun analyzeGame(game: GameSummary, depth: Int = 14): AnalysisResult {
        val d = depth.coerceIn(2, 18)
        try {
            return analyzer.analyzeGame(game.pgn, d)
        } catch (e: HttpException) {
            val body = e.response()?.errorBody()?.string().orEmpty()
            throw IllegalStateException("HTTP ${e.code()} ${e.message()} ${body.take(300)}", e)
        } catch (e: SocketTimeoutException) {
            throw IllegalStateException("Timeout: ${e.message ?: "no details"}", e)
        } catch (e: Throwable) {
            val msg = e.message?.ifBlank { e::class.java.simpleName } ?: e::class.java.simpleName
            throw IllegalStateException("Error: $msg", e)
        }
    }
}

/** ViewModel списка партий. */
class GameListViewModel(private val repository: GameRepository) : ViewModel() {
    private val _games = MutableStateFlow<List<GameSummary>>(emptyList())
    val games: StateFlow<List<GameSummary>> = _games
    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    fun loadGames(site: ChessSite, username: String) {
        viewModelScope.launch {
            _loading.value = true
            _error.value = null
            try {
                _games.value = repository.loadGames(site, username)
            } catch (e: Exception) {
                _error.value = e.message
            } finally {
                _loading.value = false
            }
        }
    }
}

/** ViewModel анализа одной партии. */
class AnalysisViewModel(private val repository: GameRepository) : ViewModel() {
    private val _analysisResult = MutableStateFlow<AnalysisResult?>(null)
    val analysisResult: StateFlow<AnalysisResult?> = _analysisResult
    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    fun analyze(game: GameSummary, depth: Int = 14) {
        viewModelScope.launch {
            _loading.value = true
            _error.value = null
            try {
                _analysisResult.value = repository.analyzeGame(game, depth)
            } catch (e: Exception) {
                _error.value = e.message
            } finally {
                _loading.value = false
            }
        }
    }
}
