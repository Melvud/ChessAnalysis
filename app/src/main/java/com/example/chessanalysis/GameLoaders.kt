package com.example.chessanalysis

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.contentOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

object GameLoaders {
    private const val TAG = "GameLoaders"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    private const val UA = "ChessAnalysis/1.0 (+android; contact: app@example.com)"

    // Утилиты --------------------------------------------------------

    private fun hasMoves(pgn: String): Boolean {
        return Regex("""\b\d+\.\s""").containsMatchIn(pgn)
    }

    private fun parseTags(pgn: String): Map<String, String> {
        val rx = Regex("""\[(\w+)\s+"([^"]*)"\]""")
        return rx.findAll(pgn).associate { it.groupValues[1] to it.groupValues[2] }
    }

    suspend fun ensureFullPgn(header: GameHeader): String = withContext(Dispatchers.IO) {
        val src = header.pgn.orEmpty()
        if (src.isNotBlank() && hasMoves(src)) return@withContext src

        val tags = parseTags(src)
        val siteUrl = tags["Site"].orEmpty()

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

        return@withContext src
    }

    private fun determineUserSide(pgn: String, username: String): Boolean? {
        val tags = parseTags(pgn)
        val white = tags["White"]?.lowercase()
        val black = tags["Black"]?.lowercase()
        val usernameLower = username.lowercase()

        return when {
            white?.contains(usernameLower) == true -> true
            black?.contains(usernameLower) == true -> false
            else -> null
        }
    }

    // Загрузчик Lichess ---------------------------------------------

    suspend fun loadLichess(username: String, max: Int = 20): List<GameHeader> =
        withContext(Dispatchers.IO) {
            Log.d(TAG, "Loading $max latest Lichess games for user: $username")

            // Попытка NDJSON (pgnInJson)
            val ndUrl =
                "https://lichess.org/api/games/user/${username.trim()}?max=$max&perfType=blitz,bullet,rapid,classical&analysed=false&clocks=false&evals=false&opening=true&pgnInJson=true"
            val ndReq = Request.Builder()
                .url(ndUrl)
                .header("Accept", "application/x-ndjson")
                .header("User-Agent", UA)
                .build()

            var ndResult: List<GameHeader>? = null
            client.newCall(ndReq).execute().use { resp ->
                if (resp.isSuccessful) {
                    val ctype = resp.header("Content-Type").orEmpty()
                    val body = resp.body?.string().orEmpty()
                    Log.d(TAG, "Lichess response type: $ctype, body length: ${body.length}")

                    if (body.isNotBlank() && ctype.startsWith("application/x-ndjson")) {
                        val list = mutableListOf<GameHeader>()
                        body.lineSequence().forEach { line ->
                            if (line.isBlank()) return@forEach
                            runCatching {
                                val el = json.parseToJsonElement(line).jsonObject
                                val pgn = el["pgn"]?.jsonPrimitive?.contentOrNull
                                if (!pgn.isNullOrBlank()) {
                                    val header = PgnChess.headerFromPgn(pgn)
                                    val sideToView = determineUserSide(pgn, username)
                                    list += header.copy(
                                        site = Provider.LICHESS,
                                        pgn = pgn,
                                        sideToView = sideToView
                                    )
                                }
                            }.onFailure { e ->
                                Log.e(TAG, "Error parsing Lichess game: ${e.message}")
                            }
                        }
                        Log.d(TAG, "Loaded ${list.size} games from Lichess")
                        if (list.isNotEmpty()) {
                            ndResult = list
                        }
                    }
                }
            }
            if (ndResult != null) return@withContext ndResult!!

            // Fallback: обычный PGN-дамп
            val pgnUrl =
                "https://lichess.org/api/games/user/${username.trim()}?max=$max&moves=true&opening=true"
            val pgnReq = Request.Builder()
                .url(pgnUrl)
                .header("User-Agent", UA)
                .build()

            client.newCall(pgnReq).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful || body.isBlank()) {
                    Log.e(TAG, "Failed to load Lichess games: ${resp.code}")
                    return@use emptyList<GameHeader>()
                }

                val rx = Regex("""(?s)(?=\[Event\b)(.*?)(?=(?:\n\n\[Event\b)|\Z)""")
                val out = mutableListOf<GameHeader>()
                rx.findAll(body).forEach { m ->
                    val pgn = m.groupValues[1].trim()
                    if (pgn.isNotEmpty()) {
                        val header = PgnChess.headerFromPgn(pgn)
                        val sideToView = determineUserSide(pgn, username)
                        out += header.copy(
                            site = Provider.LICHESS,
                            pgn = pgn,
                            sideToView = sideToView
                        )
                    }
                }
                Log.d(TAG, "Loaded ${out.size} games from Lichess (PGN fallback)")
                out
            }
        }

    // Загрузчик Chess.com -------------------------------------------

    suspend fun loadChessCom(username: String, max: Int = 20): List<GameHeader> =
        withContext(Dispatchers.IO) {
            Log.d(TAG, "Loading $max latest Chess.com games for user: $username")

            try {
                val archReq = Request.Builder()
                    .url("https://api.chess.com/pub/player/${username.trim().lowercase()}/games/archives")
                    .header("User-Agent", UA)
                    .build()

                client.newCall(archReq).execute().use { ar ->
                    val archivesBody = ar.body?.string().orEmpty()
                    Log.d(TAG, "Chess.com archives response: ${archivesBody.take(200)}")

                    if (!ar.isSuccessful) {
                        Log.e(TAG, "Failed to load Chess.com archives: ${ar.code}")
                        return@withContext emptyList<GameHeader>()
                    }

                    val archivesJson = runCatching {
                        json.parseToJsonElement(archivesBody).jsonObject
                    }.getOrNull()

                    val archives = archivesJson?.get("archives")?.jsonArray
                    if (archives == null || archives.isEmpty()) {
                        Log.e(TAG, "No archives found for Chess.com user: $username")
                        return@withContext emptyList()
                    }

                    // Берём последние несколько архивов, чтобы точно получить max игр
                    val allGames = mutableListOf<GameHeader>()
                    val archivesToFetch = archives.takeLast(3) // Берём последние 3 месяца

                    Log.d(TAG, "Fetching ${archivesToFetch.size} Chess.com archives")

                    for (archiveElement in archivesToFetch.reversed()) { // От новых к старым
                        val archiveUrl = archiveElement.jsonPrimitive.contentOrNull ?: continue

                        Log.d(TAG, "Fetching archive: $archiveUrl")

                        val monthReq = Request.Builder()
                            .url(archiveUrl)
                            .header("User-Agent", UA)
                            .build()

                        client.newCall(monthReq).execute().use { mr ->
                            val monthBody = mr.body?.string().orEmpty()



                            val monthJson = runCatching {
                                json.parseToJsonElement(monthBody).jsonObject
                            }.getOrNull()

                            val gamesArray = monthJson?.get("games")?.jsonArray


                            Log.d(TAG, "Found ${gamesArray?.size} games in archive")

                            // Добавляем игры из этого архива (от новых к старым)
                            gamesArray?.reversed()?.forEach { gameElement ->
                                if (allGames.size >= max) return@forEach // Достигли лимита

                                runCatching {
                                    val gameObj = gameElement.jsonObject
                                    val pgn = gameObj["pgn"]?.jsonPrimitive?.contentOrNull

                                    if (!pgn.isNullOrBlank()) {
                                        val header = PgnChess.headerFromPgn(pgn)
                                        val sideToView = determineUserSide(pgn, username)
                                        allGames.add(
                                            header.copy(
                                                site = Provider.CHESSCOM,
                                                pgn = pgn,
                                                sideToView = sideToView
                                            )
                                        )
                                    }
                                }.onFailure { e ->
                                    Log.e(TAG, "Error parsing Chess.com game: ${e.message}")
                                }
                            }
                        }

                        // Если уже набрали достаточно игр, можно остановиться
                        if (allGames.size >= max) {
                            Log.d(TAG, "Reached max games limit: $max")
                            break
                        }
                    }

                    Log.d(TAG, "Successfully loaded ${allGames.size} Chess.com games")
                    allGames
                }
            } catch (e: Exception) {
                Log.e(TAG, "Exception loading Chess.com games: ${e.message}", e)
                emptyList()
            }
        }

    // ------------------ Мини-клиент для аватаров ------------------

    suspend fun fetchLichessAvatar(username: String): String? =
        withContext(Dispatchers.IO) {
            if (username.isBlank()) return@withContext null
            val url = "https://lichess.org/api/user/${username.trim()}"
            val req = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .header("User-Agent", UA)
                .build()
            runCatching {
                client.newCall(req).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful || body.isBlank()) return@use null
                    val root = json.parseToJsonElement(body).jsonObject
                    val profile = root["profile"]?.jsonObject
                    val image = profile?.get("image")
                    image?.jsonPrimitive?.contentOrNull
                }
            }.getOrNull()
        }

    suspend fun fetchChessComAvatar(username: String): String? =
        withContext(Dispatchers.IO) {
            if (username.isBlank()) return@withContext null
            val url = "https://api.chess.com/pub/player/${username.trim().lowercase()}"
            val req = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .header("User-Agent", UA)
                .build()
            runCatching {
                client.newCall(req).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful || body.isBlank()) return@use null
                    val root = json.parseToJsonElement(body).jsonObject
                    root["avatar"]?.jsonPrimitive?.contentOrNull
                }
            }.getOrNull()
        }
}