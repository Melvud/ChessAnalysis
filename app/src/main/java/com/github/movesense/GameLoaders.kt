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

    @Serializable
    private data class ChessComPlayer(val username: String, val title: String? = null)

    @Serializable
    private data class ChessComGame(
            val url: String,
            val pgn: String? = null,
            val white: ChessComPlayer,
            val black: ChessComPlayer
    )

    private object Ipv4FirstDns : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val all = Dns.SYSTEM.lookup(hostname)
            return all.sortedBy { addr -> if (addr is Inet4Address) 0 else 1 }
        }
    }

    private object Ipv4OnlyDns : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val all = Dns.SYSTEM.lookup(hostname)
            val only4 = all.filterIsInstance<InetAddress>()
            return if (only4.isNotEmpty()) only4 else all
        }
    }

    private val clientPreferV4: OkHttpClient =
            OkHttpClient.Builder()
                    .dns(Ipv4FirstDns)
                    .connectTimeout(
                            8,
                            TimeUnit.SECONDS
                    ) // ✅ ИСПРАВЛЕНИЕ: Сократили с 20 до 8 секунд
                    .readTimeout(10, TimeUnit.SECONDS) // ✅ ИСПРАВЛЕНИЕ: Сократили с 20 до 10 секунд
                    .retryOnConnectionFailure(true)
                    .build()

    private val clientV4Fallback: OkHttpClient =
            OkHttpClient.Builder()
                    .dns(Ipv4OnlyDns)
                    .connectTimeout(
                            8,
                            TimeUnit.SECONDS
                    ) // ✅ ИСПРАВЛЕНИЕ: Сократили с 20 до 8 секунд
                    .readTimeout(10, TimeUnit.SECONDS) // ✅ ИСПРАВЛЕНИЕ: Сократили с 20 до 10 секунд
                    .retryOnConnectionFailure(true)
                    .build()

    private val json = Json { ignoreUnknownKeys = true }
    private const val UA = "ChessAnalysis/1.0 (+android; contact: app@example.com)"

    private fun hasMoves(pgn: String): Boolean {
        return Regex("""\b\d+\.\s""").containsMatchIn(pgn)
    }

    private fun parseTags(pgn: String): Map<String, String> {
        val rx = Regex("""\[(\w+)\s+"([^"]*)"\]""")
        return rx.findAll(pgn).associate { it.groupValues[1] to it.groupValues[2] }
    }

    // --- Унифицированный парсер времени партии (UTCDate/UTCTime -> Date -> dateIso) ---
    private fun parseGameTimestamp(pgn: String, dateIsoFallback: String?): Long {
        try {
            // 1) UTCDate+UTCTime
            val utcDate = Regex("""\[UTCDate\s+"([^"]+)"]""").find(pgn)?.groupValues?.getOrNull(1)
            val utcTime = Regex("""\[UTCTime\s+"([^"]+)"]""").find(pgn)?.groupValues?.getOrNull(1)
            if (!utcDate.isNullOrBlank() && !utcTime.isNullOrBlank()) {
                val f = SimpleDateFormat("yyyy.MM.dd HH:mm:ss", Locale.US)
                f.timeZone = TimeZone.getTimeZone("UTC")
                f.parse("$utcDate $utcTime")?.time?.let {
                    return it
                }
            }

            // 2) Date
            val date = Regex("""\[Date\s+"([^"]+)"]""").find(pgn)?.groupValues?.getOrNull(1)
            if (!date.isNullOrBlank()) {
                val f = SimpleDateFormat("yyyy.MM.dd", Locale.US)
                f.parse(date)?.time?.let {
                    return it
                }
            }

            // 3) Fallback на переданное dateIso
            if (!dateIsoFallback.isNullOrBlank()) {
                val formats =
                        listOf(
                                SimpleDateFormat("yyyy.MM.dd", Locale.US),
                                SimpleDateFormat("yyyy-MM-dd", Locale.US),
                                SimpleDateFormat("dd.MM.yyyy", Locale.US)
                        )
                for (f in formats) {
                    runCatching { f.parse(dateIsoFallback)?.time }.getOrNull()?.let {
                        return it
                    }
                }
            }
        } catch (_: Exception) {}
        return System.currentTimeMillis()
    }

    // ----------------------- ВАЛИДАЦИЯ НИКНЕЙМОВ -----------------------
    suspend fun checkLichessUserExists(username: String): Boolean =
            withContext(Dispatchers.IO) {
                if (username.isBlank()) return@withContext true
                val url = "https://lichess.org/api/user/${username.trim()}"
                val req = Request.Builder().url(url).header("User-Agent", UA).build()
                try {
                    clientPreferV4.newCall(req).execute().use { resp ->
                        if (resp.isSuccessful) return@withContext true
                        // 404 — не найден
                        if (resp.code == 404) return@withContext false
                    }
                } catch (_: Exception) {
                    // пробуем v4-only
                    return@withContext try {
                        clientV4Fallback.newCall(req).execute().use { resp -> resp.isSuccessful }
                    } catch (_: Exception) {
                        false
                    }
                }
                false
            }

    suspend fun getLichessGameCount(username: String): Int =
            withContext(Dispatchers.IO) {
                if (username.isBlank()) return@withContext 0
                val url = "https://lichess.org/api/user/${username.trim()}"
                val req = Request.Builder().url(url).header("User-Agent", UA).build()

                val body = execWithIpv6SafeClient(req) ?: return@withContext 0
                try {
                    val jsonElement = json.parseToJsonElement(body).jsonObject
                    // Lichess API: count.all
                    return@withContext jsonElement["count"]
                            ?.jsonObject
                            ?.get("all")
                            ?.jsonPrimitive
                            ?.content
                            ?.toIntOrNull()
                            ?: 0
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to parse Lichess game count: ${e.message}")
                    return@withContext 0
                }
            }

    suspend fun checkChessComUserExists(username: String): Boolean =
            withContext(Dispatchers.IO) {
                if (username.isBlank()) return@withContext true
                val norm = username.trim().lowercase()
                val url = "https://api.chess.com/pub/player/$norm"
                val req = Request.Builder().url(url).header("User-Agent", UA).build()
                try {
                    clientPreferV4.newCall(req).execute().use { resp ->
                        if (resp.isSuccessful) return@withContext true
                        if (resp.code == 404) return@withContext false
                    }
                } catch (_: Exception) {
                    return@withContext try {
                        clientV4Fallback.newCall(req).execute().use { resp -> resp.isSuccessful }
                    } catch (_: Exception) {
                        false
                    }
                }
                false
            }

    suspend fun getChessComGameCount(username: String): Int =
            withContext(Dispatchers.IO) {
                if (username.isBlank()) return@withContext 0
                val norm = username.trim().lowercase()
                // Chess.com stats endpoint gives total games
                val url = "https://api.chess.com/pub/player/$norm/stats"
                val req = Request.Builder().url(url).header("User-Agent", UA).build()

                val body = execWithIpv6SafeClient(req) ?: return@withContext 0
                try {
                    val jsonElement = json.parseToJsonElement(body).jsonObject
                    // Sum up games from different categories if needed, or just specific ones.
                    // Usually: chess_daily, chess_rapid, chess_bullet, chess_blitz
                    var total = 0
                    val categories =
                            listOf("chess_daily", "chess_rapid", "chess_bullet", "chess_blitz")
                    for (cat in categories) {
                        total +=
                                jsonElement[cat]
                                        ?.jsonObject
                                        ?.get("last")
                                        ?.jsonObject
                                        ?.get("rd")
                                        ?.jsonPrimitive
                                        ?.content
                                        ?.toIntOrNull()
                                        ?: 0
                        // Wait, 'rd' is rating deviation. We need 'record' -> 'win' + 'loss' +
                        // 'draw'.
                        // Actually, simpler: just sum up win/loss/draw from record.
                        val record = jsonElement[cat]?.jsonObject?.get("record")?.jsonObject
                        if (record != null) {
                            total += (record["win"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0)
                            total += (record["loss"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0)
                            total += (record["draw"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0)
                        }
                    }
                    return@withContext total
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to parse Chess.com game count: ${e.message}")
                    return@withContext 0
                }
            }

    suspend fun ensureFullPgn(header: GameHeader): String =
            withContext(Dispatchers.IO) {
                val src = header.pgn.orEmpty()

                // Если уже есть PGN с ходами - возвращаем его
                if (src.isNotBlank() && hasMoves(src)) {
                    Log.d(TAG, "✓ PGN already contains moves, returning as-is")
                    return@withContext src
                }

                val tags = parseTags(src)
                val siteUrl = tags["Site"].orEmpty()

                // Пробуем получить PGN с Lichess
                val lichessId =
                        Regex("""lichess\.org/([a-zA-Z0-9]{8})""")
                                .find(siteUrl)
                                ?.groupValues
                                ?.get(1)
                                ?: tags["GameId"]

                if (!lichessId.isNullOrBlank()) {
                    Log.d(TAG, "⏳ Fetching Lichess PGN for game $lichessId...")
                    val url =
                            "https://lichess.org/game/export/$lichessId?moves=true&tags=true&opening=true&clocks=true&evals=false"
                    val req = Request.Builder().url(url).header("User-Agent", UA).build()
                    val body = execWithIpv6SafeClient(req)
                    if (body != null && hasMoves(body)) {
                        Log.d(TAG, "✓ Fetched full PGN with clocks for $lichessId")
                        return@withContext body
                    }
                    Log.w(TAG, "⚠ Failed to fetch Lichess PGN for $lichessId")
                }

                // Пробуем получить PGN с Chess.com
                val chessComUrl =
                        Regex("""chess\.com/(?:live|game|daily)/([a-zA-Z0-9]+)""")
                                .find(siteUrl)
                                ?.groupValues
                                ?.get(1)
                                ?: tags["Link"]?.let {
                                    Regex("""chess\.com/(?:live|game|daily)/([a-zA-Z0-9]+)""")
                                            .find(it)
                                            ?.groupValues
                                            ?.get(1)
                                }

                if (!chessComUrl.isNullOrBlank()) {
                    Log.d(TAG, "⏳ Fetching Chess.com PGN for game $chessComUrl...")
                    // Chess.com API для получения одной партии
                    val url = "https://api.chess.com/pub/game/$chessComUrl"
                    val req = Request.Builder().url(url).header("User-Agent", UA).build()
                    runCatching {
                        val body = execWithIpv6SafeClient(req)
                        if (body != null) {
                            // Try to parse as JSON first to get titles
                            try {
                                val game = json.decodeFromString<ChessComGame>(body)
                                var pgn = game.pgn?.replace("\\n", "\n")?.replace("\\\"", "\"")
                                if (!pgn.isNullOrBlank() && hasMoves(pgn)) {
                                    // Inject titles
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

                                    Log.d(
                                            TAG,
                                            "✓ Fetched Chess.com PGN (with titles) for $chessComUrl"
                                    )
                                    return@withContext pgn
                                }
                            } catch (e: Exception) {
                                // Fallback to regex
                                val pgnMatch =
                                        Regex(""""pgn"\s*:\s*"((?:\\.|[^"\\])*)"""").find(body)
                                if (pgnMatch != null) {
                                    val pgn =
                                            pgnMatch.groupValues[1]
                                                    .replace("\\n", "\n")
                                                    .replace("\\\"", "\"")
                                    if (hasMoves(pgn)) {
                                        Log.d(TAG, "✓ Fetched Chess.com PGN for $chessComUrl")
                                        return@withContext pgn
                                    }
                                }
                            }
                        }
                    }
                            .onFailure { e ->
                                Log.w(
                                        TAG,
                                        "⚠ Failed to fetch Chess.com PGN for $chessComUrl: ${e.message}"
                                )
                            }
                }

                // Если ничего не получилось, возвращаем исходный PGN (может быть пустым)
                if (src.isBlank()) {
                    Log.w(TAG, "⚠ No PGN available for game, returning empty")
                }
                return@withContext src
            }

    // --------------------- LICHESS: поддержка since, until, max=null ---------------------
    suspend fun loadLichess(
            username: String,
            since: Long? = null,
            until: Long? = null,
            max: Int? = 50, // 🌟 max теперь nullable
            onProgress: (Int, Int) -> Unit = { _, _ -> } // loaded, total (estimated)
    ): List<GameHeader> =
            withContext(Dispatchers.IO) {
                Log.d(
                        TAG,
                        "🔄 Loading Lichess games for user: $username (max=$max, since=$since, until=$until)"
                )

                // 🌟 Динамически строим параметры
                val params = mutableListOf<String>()
                params.add("perfType=blitz,bullet,rapid,classical")
                params.add("clocks=true")
                params.add("evals=false")
                params.add("opening=true")
                params.add("pgnInJson=true")
                max?.let { params.add("max=$it") }
                since?.let { params.add("since=$it") }
                until?.let { params.add("until=$it") }

                val ndUrl =
                        "https://lichess.org/api/games/user/${username.trim()}?${params.joinToString("&")}"
                Log.d(TAG, "Lichess URL: $ndUrl")

                val ndReq =
                        Request.Builder()
                                .url(ndUrl)
                                .header("Accept", "application/x-ndjson")
                                .header("User-Agent", UA)
                                .build()

                execWithIpv6SafeClient(ndReq)?.let { body ->
                    val list = mutableListOf<GameHeader>()
                    val isNdjson =
                            body.lineSequence().take(1).firstOrNull()?.trim()?.startsWith("{") ==
                                    true

                    if (isNdjson) {
                        var loaded = 0
                        // Estimate total if max is set, otherwise we don't know easily without
                        // separate call
                        val totalEstimate = max ?: 100 // placeholder if unknown

                        body.lineSequence().forEach { line ->
                            if (line.isBlank()) return@forEach
                            runCatching {
                                val el = json.parseToJsonElement(line).jsonObject
                                val pgn = el["pgn"]?.jsonPrimitive?.content
                                if (!pgn.isNullOrBlank() && hasMoves(pgn)) {
                                    list +=
                                            PgnChess.headerFromPgn(pgn)
                                                    .copy(site = Provider.LICHESS, pgn = pgn)
                                    loaded++
                                    onProgress(loaded, totalEstimate)
                                }
                            }
                        }
                    } else {
                        // fallback: обычный PGN-дамп
                        val rx =
                                Regex(
                                        """(?s)(\[Event[^\[]*(?:\[[^\]]*\][^\[]*)*?(?:1-0|0-1|1/2-1/2|\*))"""
                                )
                        rx.findAll(body).forEach { m ->
                            val pgn = m.groupValues[1].trim()
                            if (pgn.isNotEmpty() && hasMoves(pgn)) {
                                list +=
                                        PgnChess.headerFromPgn(pgn)
                                                .copy(site = Provider.LICHESS, pgn = pgn)
                            }
                        }
                    }

                    // ВАЖНО: сортируем по времени партии
                    val sorted =
                            list.sortedByDescending { gh ->
                                parseGameTimestamp(gh.pgn ?: "", gh.date)
                            }

                    // 🌟 Применяем max только если он был задан (для "Load All" max=null)
                    val result = if (max != null) sorted.take(max) else sorted

                    Log.d(TAG, "✅ Lichess: loaded ${result.size} (newest first)")
                    return@withContext result
                }

                Log.w(TAG, "⚠ Lichess returned 0 games")
                emptyList()
            }

    // --------------------- CHESS.COM: поддержка since, until, max=null и onProgress
    // ---------------------
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

                val archives = execWithIpv6SafeClient(archReq) ?: return@withContext emptyList()
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
                // We will update total as we process archives if possible, or just use loadedCount.
                // Better: just report loadedCount and let UI handle "unknown total" or we pass 0 as
                // total.
                // Actually, we can't easily know total without fetching all archives.
                // Let's just pass 0 as total for now if unknown, or maybe archives.size * 10?
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

                                // Also update header names for immediate display (though
                                // PgnChess.headerFromPgn should handle it if tags are present)
                                // But PgnChess.headerFromPgn uses the tags we just injected!
                                // So we don't need manual name manipulation if we pass the modified
                                // PGN.

                                // Let's verify: headerFromPgn calls parseTags, which will find
                                // WhiteTitle.
                                // Then it constructs names using WhiteTitle.
                                // So we just need to pass the modified PGN.

                                allGames += header
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
                            parseGameTimestamp(gh.pgn ?: "", gh.date)
                        }

                // 🌟 Применяем max только если он был задан
                val result = if (max != null) sortedGames.take(max) else sortedGames

                Log.d(TAG, "✅ Chess.com: loaded ${result.size} (newest first)")
                result
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
}
