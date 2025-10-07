package com.example.chessanalysis

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.Inet4Address
import java.net.InetAddress
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
            val only4 = all.filterIsInstance<Inet4Address>()
            return if (only4.isNotEmpty()) only4 else all
        }
    }

    private val clientPreferV4: OkHttpClient = OkHttpClient.Builder()
        .dns(Ipv4FirstDns)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val clientV4Fallback: OkHttpClient = OkHttpClient.Builder()
        .dns(Ipv4OnlyDns)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
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

    suspend fun ensureFullPgn(header: GameHeader): String = withContext(Dispatchers.IO) {
        val src = header.pgn.orEmpty()
        if (src.isNotBlank() && hasMoves(src)) return@withContext src

        val tags = parseTags(src)
        val siteUrl = tags["Site"].orEmpty()

        val lichessId = Regex("""lichess\.org/([a-zA-Z0-9]{8})""").find(siteUrl)?.groupValues?.get(1)
            ?: tags["GameId"]

        if (!lichessId.isNullOrBlank()) {
            val url = "https://lichess.org/game/export/$lichessId?moves=true&tags=true&opening=true&clocks=true&evals=false"
            val req = Request.Builder().url(url).header("User-Agent", UA).build()
            val body = execWithIpv6SafeClient(req)
            if (body != null && hasMoves(body)) {
                Log.d(TAG, "✓ Fetched full PGN with clocks for $lichessId")
                return@withContext body
            }
        }

        return@withContext src
    }

    // --------------------- LICHESS: УВЕЛИЧИЛИ ДО 50 ---------------------
    suspend fun loadLichess(username: String, max: Int = 50): List<GameHeader> =
        withContext(Dispatchers.IO) {
            Log.d(TAG, "🔄 Loading Lichess games for user: $username (max=$max)")

            val ndUrl = "https://lichess.org/api/games/user/${username.trim()}?max=$max&perfType=blitz,bullet,rapid,classical&analysed=false&clocks=true&evals=false&opening=true&pgnInJson=true"
            val ndReq = Request.Builder()
                .url(ndUrl)
                .header("Accept", "application/x-ndjson")
                .header("User-Agent", UA)
                .build()

            execWithIpv6SafeClient(ndReq)?.let { body ->
                val list = mutableListOf<GameHeader>()
                val isNdjson = body.lineSequence().take(1).firstOrNull()?.trim()?.startsWith("{") == true

                if (isNdjson) {
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
                    Log.d(TAG, "✅ Lichess NDJSON: loaded ${list.size} games")
                    if (list.isNotEmpty()) return@withContext list
                } else {
                    val rx = Regex("""(?s)(\[Event[^\[]*(?:\[[^\]]*\][^\[]*)*?1-0|0-1|1/2-1/2|\*)""")
                    rx.findAll(body).forEach { m ->
                        val pgn = m.groupValues[1].trim()
                        if (pgn.isNotEmpty() && hasMoves(pgn)) {
                            list += PgnChess.headerFromPgn(pgn).copy(site = Provider.LICHESS, pgn = pgn)
                        }
                    }
                    Log.d(TAG, "✅ Lichess PGN dump: loaded ${list.size} games")
                    if (list.isNotEmpty()) return@withContext list
                }
            }

            Log.w(TAG, "⚠ Lichess returned 0 games")
            emptyList()
        }

    // --------------------- CHESS.COM: УВЕЛИЧИЛИ ДО 50 ---------------------
    suspend fun loadChessCom(username: String, max: Int = 50): List<GameHeader> =
        withContext(Dispatchers.IO) {
            Log.d(TAG, "🔄 Loading Chess.com games for user: $username (max=$max)")

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

            // Берем последние 3 месяца (чтобы точно получить 50 партий)
            val lastArchives = archiveUrls.takeLast(3)
            val allGames = mutableListOf<GameHeader>()

            for (archiveUrl in lastArchives.reversed()) {
                val monthReq = Request.Builder().url(archiveUrl).header("User-Agent", UA).build()
                val month = execWithIpv6SafeClient(monthReq) ?: continue

                val matches = Regex(""""pgn"\s*:\s*"((?:\\.|[^"\\])*)"""").findAll(month).toList()
                matches.forEach {
                    val pgn = it.groupValues[1].replace("\\n", "\n").replace("\\\"", "\"")
                    if (hasMoves(pgn)) {
                        allGames += PgnChess.headerFromPgn(pgn).copy(site = Provider.CHESSCOM, pgn = pgn)
                    }
                }

                if (allGames.size >= max) break
            }

            val result = allGames.takeLast(max)
            Log.d(TAG, "✅ Chess.com: loaded ${result.size} games")
            result
        }

    private fun execWithIpv6SafeClient(req: Request): String? {
        try {
            clientPreferV4.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
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
                val body = resp.body?.string().orEmpty()
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
                (e is java.net.ConnectException)
    }
}