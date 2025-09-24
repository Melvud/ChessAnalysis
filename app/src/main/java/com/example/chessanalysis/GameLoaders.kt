package com.example.chessanalysis

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaTypeOrNull

object GameLoaders {
    private val client = OkHttpClient()
    private val json = Json { ignoreUnknownKeys = true }

    private const val UA = "ChessAnalysis/1.0 (+android; contact: app@example.com)"

    // Утилиты --------------------------------------------------------

    private fun hasMoves(pgn: String): Boolean {
        // простая эвристика: наличие "1." / "2." и т.п.
        return Regex("""\b\d+\.\s""").containsMatchIn(pgn)
    }

    private fun parseTags(pgn: String): Map<String, String> {
        val rx = Regex("""\[(\w+)\s+"([^"]*)"\]""")
        return rx.findAll(pgn).associate { it.groupValues[1] to it.groupValues[2] }
    }

    /**
     * Гарантирует, что на выходе — PGN с ходами.
     * Если в header.pgn только теги, докачиваем по GameId из Lichess.
     * (Для Chess.com пока возвращаем как есть — там наш загрузчик обычно уже отдаёт полный PGN.)
     */
    suspend fun ensureFullPgn(header: GameHeader): String = withContext(Dispatchers.IO) {
        val src = header.pgn.orEmpty()
        if (src.isNotBlank() && hasMoves(src)) return@withContext src

        // Пробуем вытащить из тегов Site/GameId ссылку lichess и скачать полный PGN
        val tags = parseTags(src)
        val siteUrl = tags["Site"].orEmpty()

        // Ищем lichess ID (обычно 8 символов)
        val lichessId = Regex("""lichess\.org/([a-zA-Z0-9]{8})""").find(siteUrl)?.groupValues?.get(1)
            ?: tags["GameId"]

        if (!lichessId.isNullOrBlank()) {
            val url = "https://lichess.org/game/export/$lichessId?moves=true&tags=true&opening=true&clocks=false&evals=false"
            val req = Request.Builder().url(url).header("User-Agent", UA).build()
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (resp.isSuccessful && hasMoves(body)) {
                    return@withContext body
                }
            }
        }

        // Фолбэк — что было
        return@withContext src
    }

    // Загрузчик Lichess ---------------------------------------------

    suspend fun loadLichess(username: String, max: Int = 20): List<GameHeader> =
        withContext(Dispatchers.IO) {
            // 1) Предпочтительно: NDJSON (быстро, удобно)
            val ndUrl =
                "https://lichess.org/api/games/user/${username.trim()}?max=$max&perfType=blitz,bullet,rapid,classical&analysed=false&clocks=false&evals=false&opening=true&pgnInJson=true"
            val ndReq = Request.Builder()
                .url(ndUrl)
                .header("Accept", "application/x-ndjson")
                .header("User-Agent", UA)
                .build()

            client.newCall(ndReq).execute().use { resp ->
                if (resp.isSuccessful) {
                    val ctype = resp.header("Content-Type").orEmpty()
                    val body = resp.body?.string().orEmpty()
                    if (body.isNotBlank() && ctype.startsWith("application/x-ndjson")) {
                        val list = mutableListOf<GameHeader>()
                        body.lineSequence().forEach { line ->
                            if (line.isBlank()) return@forEach
                            runCatching {
                                val el = json.parseToJsonElement(line).jsonObject
                                val pgn = el["pgn"]?.jsonPrimitive?.content
                                if (!pgn.isNullOrBlank()) {
                                    list += PgnChess.headerFromPgn(pgn).copy(site = Provider.LICHESS, pgn = pgn)
                                }
                            }
                        }
                        if (list.isNotEmpty()) return@use list
                    }
                }
            }

            // 2) Фолбэк: плоский PGN-дамп
            val pgnUrl =
                "https://lichess.org/api/games/user/${username.trim()}?max=$max&moves=true&opening=true"
            val pgnReq = Request.Builder()
                .url(pgnUrl)
                .header("User-Agent", UA)
                .build()

            client.newCall(pgnReq).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful || body.isBlank()) return@use emptyList<GameHeader>()

                // Разрезаем ДЕЙСТВИТЕЛЬНО на партии: каждая начинается с [Event ...
                val rx = Regex("""(?s)(?=\[Event\b)(.*?)(?=(?:\n\n\[Event\b)|\Z)""")
                val out = mutableListOf<GameHeader>()
                rx.findAll(body).forEach { m ->
                    val pgn = m.groupValues[1].trim()
                    if (pgn.isNotEmpty()) {
                        out += PgnChess.headerFromPgn(pgn).copy(site = Provider.LICHESS, pgn = pgn)
                    }
                }
                out
            }
        }

    // Загрузчик Chess.com -------------------------------------------

    suspend fun loadChessCom(username: String, max: Int = 20): List<GameHeader> =
        withContext(Dispatchers.IO) {
            val archReq = Request.Builder()
                .url("https://api.chess.com/pub/player/${username.trim().lowercase()}/games/archives")
                .header("User-Agent", UA)
                .build()
            client.newCall(archReq).execute().use { ar ->
                val archives = ar.body?.string().orEmpty()
                val last = Regex(""""(https:[^"]+/\\d{4}/\\d{2})"""")
                    .findAll(archives).map { it.groupValues[1] }.toList().lastOrNull()
                    ?: return@use emptyList<GameHeader>()
                val monthReq = Request.Builder().url(last).header("User-Agent", UA).build()
                client.newCall(monthReq).execute().use { mr ->
                    val month = mr.body?.string().orEmpty()
                    val matches = Regex(""""pgn"\s*:\s*"((?:\\.|[^"\\])*)"""")
                        .findAll(month).toList()
                    matches.takeLast(max).map {
                        val pgn = it.groupValues[1].replace("\\n", "\n").replace("\\\"", "\"")
                        PgnChess.headerFromPgn(pgn).copy(site = Provider.CHESSCOM, pgn = pgn)
                    }
                }
            }
        }
}
