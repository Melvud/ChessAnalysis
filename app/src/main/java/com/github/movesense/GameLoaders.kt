package com.github.movesense

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.ConnectException
import java.net.Inet4Address
import java.net.InetAddress
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

object GameLoaders {
    private const val TAG = "GameLoaders"

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

    private val clientPreferV4: OkHttpClient = OkHttpClient.Builder()
        .dns(Ipv4FirstDns)
        .connectTimeout(8, TimeUnit.SECONDS)  // ✅ ИСПРАВЛЕНИЕ: Сократили с 20 до 8 секунд
        .readTimeout(10, TimeUnit.SECONDS)     // ✅ ИСПРАВЛЕНИЕ: Сократили с 20 до 10 секунд
        .retryOnConnectionFailure(true)
        .build()

    private val clientV4Fallback: OkHttpClient = OkHttpClient.Builder()
        .dns(Ipv4OnlyDns)
        .connectTimeout(8, TimeUnit.SECONDS)   // ✅ ИСПРАВЛЕНИЕ: Сократили с 20 до 8 секунд
        .readTimeout(10, TimeUnit.SECONDS)     // ✅ ИСПРАВЛЕНИЕ: Сократили с 20 до 10 секунд
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
                f.parse("$utcDate $utcTime")?.time?.let { return it }
            }

            // 2) Date
            val date = Regex("""\[Date\s+"([^"]+)"]""").find(pgn)?.groupValues?.getOrNull(1)
            if (!date.isNullOrBlank()) {
                val f = SimpleDateFormat("yyyy.MM.dd", Locale.US)
                f.parse(date)?.time?.let { return it }
            }

            // 3) Fallback на переданное dateIso
            if (!dateIsoFallback.isNullOrBlank()) {
                val formats = listOf(
                    SimpleDateFormat("yyyy.MM.dd", Locale.US),
                    SimpleDateFormat("yyyy-MM-dd", Locale.US),
                    SimpleDateFormat("dd.MM.yyyy", Locale.US)
                )
                for (f in formats) {
                    runCatching { f.parse(dateIsoFallback)?.time }.getOrNull()?.let { return it }
                }
            }
        } catch (_: Exception) {
        }
        return System.currentTimeMillis()
    }

    // ----------------------- ВАЛИДАЦИЯ НИКНЕЙМОВ -----------------------
    suspend fun checkLichessUserExists(username: String): Boolean = withContext(Dispatchers.IO) {
        if (username.isBlank()) return@withContext true
        val url = "https://lichess.org/api/user/${username.trim()}"
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .build()
        try {
            clientPreferV4.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) return@withContext true
                // 404 — не найден
                if (resp.code == 404) return@withContext false
            }
        } catch (_: Exception) {
            // пробуем v4-only
            return@withContext try {
                clientV4Fallback.newCall(req).execute().use { resp ->
                    resp.isSuccessful
                }
            } catch (_: Exception) {
                false
            }
        }
        false
    }

    suspend fun checkChessComUserExists(username: String): Boolean = withContext(Dispatchers.IO) {
        if (username.isBlank()) return@withContext true
        val norm = username.trim().lowercase()
        val url = "https://api.chess.com/pub/player/$norm"
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .build()
        try {
            clientPreferV4.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) return@withContext true
                if (resp.code == 404) return@withContext false
            }
        } catch (_: Exception) {
            return@withContext try {
                clientV4Fallback.newCall(req).execute().use { resp ->
                    resp.isSuccessful
                }
            } catch (_: Exception) {
                false
            }
        }
        false
    }

    suspend fun ensureFullPgn(header: GameHeader): String = withContext(Dispatchers.IO) {
        val src = header.pgn.orEmpty()
        if (src.isNotBlank() && hasMoves(src)) return@withContext src

        val tags = parseTags(src)
        val siteUrl = tags["Site"].orEmpty()

        val lichessId = Regex("""lichess\.org/([a-zA-Z0-9]{8})""").find(siteUrl)?.groupValues?.get(1)
            ?: tags["GameId"]

        if (!lichessId.isNullOrBlank()) {
            val url =
                "https://lichess.org/game/export/$lichessId?moves=true&tags=true&opening=true&clocks=true&evals=false"
            val req = Request.Builder().url(url).header("User-Agent", UA).build()
            val body = execWithIpv6SafeClient(req)
            if (body != null && hasMoves(body)) {
                Log.d(TAG, "✓ Fetched full PGN with clocks for $lichessId")
                return@withContext body
            }
        }

        return@withContext src
    }

    // --------------------- LICHESS: поддержка since, until, max=null ---------------------
    suspend fun loadLichess(
        username: String,
        since: Long? = null,
        until: Long? = null,
        max: Int? = 50 // 🌟 max теперь nullable
    ): List<GameHeader> =
        withContext(Dispatchers.IO) {
            Log.d(TAG, "🔄 Loading Lichess games for user: $username (max=$max, since=$since, until=$until)")

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

            val ndUrl = "https://lichess.org/api/games/user/${username.trim()}?${params.joinToString("&")}"
            Log.d(TAG, "Lichess URL: $ndUrl")

            val ndReq = Request.Builder()
                .url(ndUrl)
                .header("Accept", "application/x-ndjson")
                .header("User-Agent", UA)
                .build()

            execWithIpv6SafeClient(ndReq)?.let { body ->
                val list = mutableListOf<GameHeader>()
                val isNdjson =
                    body.lineSequence().take(1).firstOrNull()?.trim()?.startsWith("{") == true

                if (isNdjson) {
                    body.lineSequence().forEach { line ->
                        if (line.isBlank()) return@forEach
                        runCatching {
                            val el = json.parseToJsonElement(line).jsonObject
                            val pgn = el["pgn"]?.jsonPrimitive?.content
                            if (!pgn.isNullOrBlank() && hasMoves(pgn)) {
                                list += PgnChess.headerFromPgn(pgn)
                                    .copy(site = Provider.LICHESS, pgn = pgn)
                            }
                        }
                    }
                } else {
                    // fallback: обычный PGN-дамп
                    val rx =
                        Regex("""(?s)(\[Event[^\[]*(?:\[[^\]]*\][^\[]*)*?(?:1-0|0-1|1/2-1/2|\*))""")
                    rx.findAll(body).forEach { m ->
                        val pgn = m.groupValues[1].trim()
                        if (pgn.isNotEmpty() && hasMoves(pgn)) {
                            list += PgnChess.headerFromPgn(pgn).copy(site = Provider.LICHESS, pgn = pgn)
                        }
                    }
                }

                // ВАЖНО: сортируем по времени партии
                val sorted = list.sortedByDescending { gh ->
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

    // --------------------- CHESS.COM: поддержка since, until, max=null и onProgress ---------------------
    suspend fun loadChessCom(
        username: String,
        since: Long? = null,
        until: Long? = null,
        max: Int? = 50, // 🌟 max nullable
        onProgress: (Float) -> Unit = {} // 🌟 Progress callback
    ): List<GameHeader> =
        withContext(Dispatchers.IO) {
            Log.d(TAG, "🔄 Loading Chess.com games for user: $username (max=$max, since=$since, until=$until)")
            onProgress(0f)

            val archReq = Request.Builder()
                .url("https://api.chess.com/pub/player/${username.trim().lowercase()}/games/archives")
                .header("User-Agent", UA)
                .build()

            val archives = execWithIpv6SafeClient(archReq) ?: return@withContext emptyList()
            val archiveUrls = Regex(""""(https:[^"]+/[0-9]{4}/[0-9]{2})"""")
                .findAll(archives)
                .map { it.groupValues[1] }
                .toList()

            if (archiveUrls.isEmpty()) {
                Log.w(TAG, "⚠ No archives found for $username")
                return@withContext emptyList()
            }

            // 🌟 Фильтрация архивов по дате
            val calSince = since?.let { Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = it } }
            val calUntil = until?.let { Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = it } }

            val filteredArchiveUrls = archiveUrls.filter { url ->
                val match = Regex(""".*/(\d{4})/(\d{2})$""").find(url) ?: return@filter true
                val year = match.groupValues[1].toIntOrNull() ?: 0
                val month = match.groupValues[2].toIntOrNull() ?: 0

                if (calSince != null) {
                    val sinceYear = calSince.get(Calendar.YEAR)
                    val sinceMonth = calSince.get(Calendar.MONTH) + 1 // Calendar.MONTH 0-based
                    if (year < sinceYear || (year == sinceYear && month < sinceMonth)) return@filter false
                }
                if (calUntil != null) {
                    val untilYear = calUntil.get(Calendar.YEAR)
                    val untilMonth = calUntil.get(Calendar.MONTH) + 1 // Calendar.MONTH 0-based
                    if (year > untilYear || (year == untilYear && month > untilMonth)) return@filter false
                }
                true
            }

            // 🌟 Если max задан (загрузка по умолчанию) и нет дат, берем 3 мес.
            //    Иначе (Load All или по датам) - берем ВСЕ отфильтрованные.
            val archivesToFetch = if (max != null && since == null && until == null) {
                filteredArchiveUrls.takeLast(3)
            } else {
                filteredArchiveUrls
            }.reversed() // Качаем с новых

            if (archivesToFetch.isEmpty()) {
                Log.w(TAG, "No archives found to fetch for $username with given filters.")
                onProgress(1f)
                return@withContext emptyList()
            }

            val allGames = mutableListOf<GameHeader>()

            for ((index, archiveUrl) in archivesToFetch.withIndex()) {
                val monthReq = Request.Builder().url(archiveUrl).header("User-Agent", UA).build()
                val month = execWithIpv6SafeClient(monthReq) ?: continue

                val matches = Regex(""""pgn"\s*:\s*"((?:\\.|[^"\\])*)"""").findAll(month).toList()
                matches.forEach {
                    val pgn = it.groupValues[1].replace("\\n", "\n").replace("\\\"", "\"")
                    if (hasMoves(pgn)) {
                        allGames += PgnChess.headerFromPgn(pgn).copy(site = Provider.CHESSCOM, pgn = pgn)
                    }
                }
                // 🌟 Обновляем прогресс
                onProgress((index + 1).toFloat() / archivesToFetch.size)
            }

            // Ключевое изменение: сортируем по времени партии
            val sortedGames = allGames.sortedByDescending { gh ->
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