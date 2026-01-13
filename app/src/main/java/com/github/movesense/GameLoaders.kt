package com.github.movesense

import android.util.Log
import java.net.ConnectException
import java.net.Inet4Address
import java.net.InetAddress
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request

object GameLoaders {
    private const val TAG = "GameLoaders"
    private const val UA = "MoveSense/1.0"

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        isLenient = true
    }

    // --------------------- HTTP CLIENTS (IPv4/IPv6 fallback) ---------------------
    private val clientPreferV4 =
            OkHttpClient.Builder()
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .readTimeout(60, TimeUnit.SECONDS)
                    .dns(
                            object : Dns {
                                override fun lookup(hostname: String): List<InetAddress> {
                                    return try {
                                        val all = Dns.SYSTEM.lookup(hostname)
                                        val v4 = all.filterIsInstance<Inet4Address>()
                                        val v6 = all.filter { it !is Inet4Address }
                                        v4 + v6 // Prefer IPv4
                                    } catch (e: Exception) {
                                        Dns.SYSTEM.lookup(hostname)
                                    }
                                }
                            }
                    )
                    .build()

    private val clientV4Fallback =
            OkHttpClient.Builder()
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .readTimeout(60, TimeUnit.SECONDS)
                    .dns(
                            object : Dns {
                                override fun lookup(hostname: String): List<InetAddress> {
                                    val all = Dns.SYSTEM.lookup(hostname)
                                    return all.filterIsInstance<Inet4Address>().ifEmpty { all }
                                }
                            }
                    )
                    .build()

    @Serializable
    private data class ChessComPlayer(val username: String, val title: String? = null)

    @Serializable
    private data class ChessComGame(
            val url: String,
            val pgn: String? = null,
            val white: ChessComPlayer,
            val black: ChessComPlayer,
            val rules: String? = null
    )

    // --------------------- LICHESS ---------------------
    suspend fun loadLichess(
            username: String,
            since: Long? = null,
            until: Long? = null,
            max: Int? = 50,
            onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): List<GameHeader> =
            withContext(Dispatchers.IO) {
                Log.d(TAG, "🔄 Loading Lichess games for user: $username")
                onProgress(0, 0)

                val urlBuilder = StringBuilder("https://lichess.org/api/games/user/$username")
                urlBuilder.append("?pgnInJson=true&clocks=false&evals=false&opening=true")
                if (max != null) urlBuilder.append("&max=$max")
                if (since != null) urlBuilder.append("&since=$since")
                if (until != null) urlBuilder.append("&until=$until")

                val request =
                        Request.Builder()
                                .url(urlBuilder.toString())
                                .header("Accept", "application/x-ndjson")
                                .header("User-Agent", UA)
                                .build()

                val responseBody = execWithIpv6SafeClient(request) ?: return@withContext emptyList()

                val games = mutableListOf<GameHeader>()
                val lines = responseBody.lines()
                val totalLines = lines.size // Rough estimate
                
                lines.forEachIndexed { index, line ->
                    if (line.isNotBlank()) {
                        try {
                            val jsonElement = json.parseToJsonElement(line).jsonObject
                            val pgn = jsonElement["pgn"]?.jsonPrimitive?.content
                            val variant = jsonElement["variant"]?.jsonPrimitive?.content

                            // Filter standard chess
                            if (pgn != null && (variant == "standard" || variant == null)) {
                                if (hasMoves(pgn)) {
                                    val header =
                                            PgnChess.headerFromPgn(pgn)
                                                    .copy(site = Provider.LICHESS, pgn = pgn)
                                    games.add(header)
                                }
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to parse Lichess game line: ${e.message}")
                        }
                    }
                    if (index % 10 == 0) onProgress(games.size, totalLines)
                }
                
                Log.d(TAG, "✅ Lichess: loaded ${games.size}")
                games
            }

    suspend fun getLichessGameCount(username: String): Int = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("https://lichess.org/api/user/$username")
                .header("User-Agent", UA)
                .build()
            
            val body = execWithIpv6SafeClient(request) ?: return@withContext 0
            val jsonElement = json.parseToJsonElement(body).jsonObject
            val count = jsonElement["count"]?.jsonObject?.get("all")?.jsonPrimitive?.content?.toIntOrNull()
            count ?: 0
        } catch (e: Exception) {
            Log.e(TAG, "Error getting Lichess game count", e)
            0
        }
    }

    // --------------------- CHESS.COM ---------------------
    suspend fun loadChessCom(
            username: String,
            since: Long? = null,
            until: Long? = null,
            max: Int? = 50, // 🌟 max nullable
            onProgress: (Int, Int) -> Unit = { _, _ -> } // loaded, total
    ): List<GameHeader> =
            withContext(Dispatchers.IO) {
                Log.d(
                        TAG,
                        "🔄 Loading Chess.com games for user: $username (max=$max, since=$since, until=$until)"
                )
                onProgress(0, 0)

                val archReq =
                        Request.Builder()
                                .url(
                                        "https://api.chess.com/pub/player/${username.trim().lowercase()}/games/archives"
                                )
                                .header("User-Agent", UA)
                                .build()

                var archives: String? = null
                var attempts = 0
                while (archives == null && attempts < 3) {
                    if (attempts > 0) {
                        Log.w(TAG, "Retry fetching archives for $username (attempt ${attempts + 1})")
                        delay(1000)
                    }
                    archives = execWithIpv6SafeClient(archReq)
                    attempts++
                }

                if (archives == null) {
                     Log.e(TAG, "Failed to fetch archives for $username after 3 attempts")
                     return@withContext emptyList()
                }

                val archiveUrls =
                        Regex(""""(https:[^"]+/[0-9]{4}/[0-9]{2})"""")
                                .findAll(archives)
                                .map { it.groupValues[1] }
                                .toList()

                if (archiveUrls.isEmpty()) {
                    Log.w(TAG, "⚠ No archives found for $username")
                    return@withContext emptyList()
                }

                // 🌟 Фильтрация архивов по дате
                val calSince =
                        since?.let {
                            Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
                                timeInMillis = it
                            }
                        }
                val calUntil =
                        until?.let {
                            Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
                                timeInMillis = it
                            }
                        }

                val filteredArchiveUrls =
                        archiveUrls.filter { url ->
                            val match =
                                    Regex(""".*/(\d{4})/(\d{2})$""").find(url) ?: return@filter true
                            val year = match.groupValues[1].toIntOrNull() ?: 0
                            val month = match.groupValues[2].toIntOrNull() ?: 0

                            if (calSince != null) {
                                val sinceYear = calSince.get(Calendar.YEAR)
                                val sinceMonth =
                                        calSince.get(Calendar.MONTH) + 1 // Calendar.MONTH 0-based
                                if (year < sinceYear || (year == sinceYear && month < sinceMonth))
                                        return@filter false
                            }
                            if (calUntil != null) {
                                val untilYear = calUntil.get(Calendar.YEAR)
                                val untilMonth =
                                        calUntil.get(Calendar.MONTH) + 1 // Calendar.MONTH 0-based
                                if (year > untilYear || (year == untilYear && month > untilMonth))
                                        return@filter false
                            }
                            true
                        }

                // 🌟 Если max задан (загрузка по умолчанию) и нет дат, берем 3 мес.
                //    Иначе (Load All или по датам) - берем ВСЕ отфильтрованные.
                val archivesToFetch =
                        if (max != null && since == null && until == null) {
                                    filteredArchiveUrls.takeLast(3)
                                } else {
                                    filteredArchiveUrls
                                }
                                .reversed() // Качаем с новых

                if (archivesToFetch.isEmpty()) {
                    Log.w(TAG, "No archives found to fetch for $username with given filters.")
                    onProgress(0, 0)
                    return@withContext emptyList()
                }

                val allGames = mutableListOf<GameHeader>()
                var loadedCount: Int
                // Estimate total: archives * avg games? Hard to say.
                val estimatedTotal = archivesToFetch.size * 20 // Rough guess

                for (archiveUrl in archivesToFetch) {
                    val monthReq =
                            Request.Builder().url(archiveUrl).header("User-Agent", UA).build()
                    val month = execWithIpv6SafeClient(monthReq) ?: continue

                    runCatching {
                        val gamesWrapper = json.parseToJsonElement(month).jsonObject
                        val gamesArray = gamesWrapper["games"]?.jsonArray
                        gamesArray?.forEach { element ->
                            val game = json.decodeFromJsonElement<ChessComGame>(element)

                            // 🌟 FILTER: Only allow standard chess (exclude chess960, etc.)
                            if (game.rules == "chess") {
                                var pgn = game.pgn?.replace("\\n", "\n")?.replace("\\\"", "\"")
                                if (!pgn.isNullOrBlank() && hasMoves(pgn)) {
                                    // Inject titles into PGN
                                    val whiteTitle = game.white.title
                                    val blackTitle = game.black.title
                                    val extraTags = StringBuilder()
                                    if (!whiteTitle.isNullOrBlank())
                                            extraTags.append("[WhiteTitle \"$whiteTitle\"]\n")
                                    if (!blackTitle.isNullOrBlank())
                                            extraTags.append("[BlackTitle \"$blackTitle\"]\n")

                                    if (extraTags.isNotEmpty()) {
                                        pgn = extraTags.toString() + pgn
                                    }

                                    var header =
                                            PgnChess.headerFromPgn(pgn)
                                                    .copy(site = Provider.CHESSCOM, pgn = pgn)

                                    allGames += header
                                }
                            }
                        }
                    }
                            .onFailure { e ->
                                Log.w(TAG, "Failed to parse Chess.com JSON: ${e.message}")
                                // Fallback to regex if JSON parsing fails
                                val matches =
                                        Regex(""""pgn"\s*:\s*"((?:\\.|[^"\\])*)"""")
                                                .findAll(month)
                                                .toList()
                                matches.forEach {
                                    val pgn =
                                            it.groupValues[1]
                                                    .replace("\\n", "\n")
                                                    .replace("\\\"", "\"")
                                    if (hasMoves(pgn)) {
                                        allGames +=
                                                PgnChess.headerFromPgn(pgn)
                                                        .copy(site = Provider.CHESSCOM, pgn = pgn)
                                    }
                                }
                            }

                    // 🌟 Обновляем прогресс
                    loadedCount = allGames.size
                    onProgress(loadedCount, estimatedTotal)
                }

                // Ключевое изменение: сортируем по времени партии
                val sortedGames =
                        allGames.sortedByDescending { gh ->
                            parseGameTimestamp(gh.pgn ?: "", gh.date ?: "")
                        }

                // 🌟 Применяем max только если он был задан
                val result = if (max != null) sortedGames.take(max) else sortedGames

                Log.d(TAG, "✅ Chess.com: loaded ${result.size} (newest first)")
                result
            }

    suspend fun getChessComGameCount(username: String): Int = withContext(Dispatchers.IO) {
        // Chess.com doesn't give a simple total count easily without summing archives.
        // But we can try to fetch archives and sum them up? No, archives list doesn't have counts.
        // We'll just return 0 or try to estimate?
        // Actually, let's just return 0 for now as it's hard to get cheap total.
        // Or we can fetch the stats endpoint: https://api.chess.com/pub/player/{username}/stats
        try {
             val request = Request.Builder()
                .url("https://api.chess.com/pub/player/${username.trim().lowercase()}/stats")
                .header("User-Agent", UA)
                .build()
            
            val body = execWithIpv6SafeClient(request) ?: return@withContext 0
            val jsonElement = json.parseToJsonElement(body).jsonObject
            
            // Sum up standard chess games
            val chessDaily = jsonElement["chess_daily"]?.jsonObject?.get("record")?.jsonObject
            val chessRapid = jsonElement["chess_rapid"]?.jsonObject?.get("record")?.jsonObject
            val chessBullet = jsonElement["chess_bullet"]?.jsonObject?.get("record")?.jsonObject
            val chessBlitz = jsonElement["chess_blitz"]?.jsonObject?.get("record")?.jsonObject

            fun getCount(obj: kotlinx.serialization.json.JsonObject?): Int {
                if (obj == null) return 0
                val win = obj["win"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                val loss = obj["loss"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                val draw = obj["draw"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                return win + loss + draw
            }

            getCount(chessDaily) + getCount(chessRapid) + getCount(chessBullet) + getCount(chessBlitz)
        } catch (e: Exception) {
            Log.e(TAG, "Error getting Chess.com game count", e)
            0
        }
    }

    private fun execWithIpv6SafeClient(req: Request): String? {
        try {
            clientPreferV4.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orElseBlank()
                if (resp.isSuccessful && body.isNotBlank()) return body
                if (body.isNotBlank()) return body
            }
        } catch (e: Exception) {
            if (!isIpv6RouteIssue(e)) {
                Log.w(TAG, "HTTP error (preferV4): ${e.message}")
                return null
            }
            Log.w(TAG, "IPv6 issue, retry with v4-only… (${e.javaClass.simpleName})")
        }

        return try {
            clientV4Fallback.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orElseBlank()
                if (resp.isSuccessful && body.isNotBlank()) body else body.ifBlank { null }
            }
        } catch (e: Exception) {
            Log.w(TAG, "HTTP error (v4only): ${e.message}")
            null
        }
    }

    private fun isIpv6RouteIssue(e: Throwable): Boolean {
        val msg = (e.message ?: "").lowercase()
        return ("ehostunreach" in msg) ||
                ("no route to host" in msg) ||
                ("connection reset by peer" in msg) ||
                (e is ConnectException)
    }

    private fun String?.orElseBlank(): String = this ?: ""

    private fun hasMoves(pgn: String): Boolean {
        // Simple check: does it have "1."?
        return pgn.contains("1.")
    }

    private fun parseGameTimestamp(pgn: String, dateStr: String): Long {
        // Try to parse [UTCDate "yyyy.MM.dd"] and [UTCTime "HH:mm:ss"]
        try {
            val dateMatch = Regex("""\[UTCDate "(\d{4}\.\d{2}\.\d{2})"\]""").find(pgn)
            val timeMatch = Regex("""\[UTCTime "(\d{2}:\d{2}:\d{2})"\]""").find(pgn)

            if (dateMatch != null && timeMatch != null) {
                val dateTimeStr = "${dateMatch.groupValues[1]} ${timeMatch.groupValues[1]}"
                val sdf = SimpleDateFormat("yyyy.MM.dd HH:mm:ss", Locale.US)
                sdf.timeZone = TimeZone.getTimeZone("UTC")
                return sdf.parse(dateTimeStr)?.time ?: 0L
            }
        } catch (e: Exception) {
            // ignore
        }
        
        // Fallback to dateStr (yyyy.MM.dd)
        try {
            val sdf = SimpleDateFormat("yyyy.MM.dd", Locale.US)
            sdf.timeZone = TimeZone.getTimeZone("UTC")
            return sdf.parse(dateStr)?.time ?: 0L
        } catch (e: Exception) {
            return 0L
        }
    }
}
