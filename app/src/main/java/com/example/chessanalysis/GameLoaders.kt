package com.example.chessanalysis

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.contentOrNull
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.TimeUnit

object GameLoaders {
    private const val TAG = "GameLoaders"

    /**
     * Проблема из лога: попытка TLS к lichess.org по IPv6, после чего:
     * EHOSTUNREACH / "No route to host" → SSL handshake aborted → java.net.ConnectException.
     *
     * Решение:
     * 1) DNS, который **ставит IPv4-адреса первыми** (Happy Eyeballs у OkHttp сработает корректно).
     * 2) Фолбэк-клиент, который **полностью отфильтровывает AAAA** при повторе — на случай, если
     *    в резолве пришёл только AAAA или стек/маршрут IPv6 у устройства нестабилен.
     */
    private object Ipv4FirstDns : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val all = Dns.SYSTEM.lookup(hostname)
            // Ставим IPv4 первыми — это ускорит успешное соединение при битом IPv6.
            return all.sortedBy { addr -> if (addr is Inet4Address) 0 else 1 }
        }
    }

    private object Ipv4OnlyDns : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val all = Dns.SYSTEM.lookup(hostname)
            val only4 = all.filterIsInstance<Inet4Address>()
            // Если вдруг нет A-записей — вернём оригинальный список (пусть OkHttp сам разберётся).
            return if (only4.isNotEmpty()) only4 else all
        }
    }

    private val clientPreferV4: OkHttpClient = OkHttpClient.Builder()
        .dns(Ipv4FirstDns)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val clientV4Fallback: OkHttpClient = OkHttpClient.Builder()
        .dns(Ipv4OnlyDns)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    private const val UA = "ChessAnalysis/1.0 (+android; contact: app@example.com)"

    // --------------------------- Утилиты ---------------------------

    private fun hasMoves(pgn: String): Boolean {
        return Regex("""\b\d+\.\s""").containsMatchIn(pgn)
    }

    private fun parseTags(pgn: String): Map<String, String> {
        val rx = Regex("""\[(\w+)\s+"([^"]*)"\]""")
        return rx.findAll(pgn).associate { it.groupValues[1] to it.groupValues[2] }
    }

    /**
     * Дозагружает полный PGN по lichessId из тэгов, если в header.pgn нет ходов.
     * Клиент с IPv4-фолбэком.
     */
    suspend fun ensureFullPgn(header: GameHeader): String = withContext(Dispatchers.IO) {
        val src = header.pgn.orEmpty()
        if (src.isNotBlank() && hasMoves(src)) return@withContext src

        val tags = parseTags(src)
        val siteUrl = tags["Site"].orEmpty()

        val lichessId = Regex("""lichess\.org/([a-zA-Z0-9]{8})""").find(siteUrl)?.groupValues?.get(1)
            ?: tags["GameId"]

        if (!lichessId.isNullOrBlank()) {
            val url =
                "https://lichess.org/game/export/$lichessId?moves=true&tags=true&opening=true&clocks=false&evals=false"
            val req = Request.Builder().url(url).header("User-Agent", UA).build()
            val body = execWithIpv6SafeClient(req)
            if (body != null && hasMoves(body)) {
                return@withContext body
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

    // --------------------- Загрузчик Lichess ----------------------

    suspend fun loadLichess(username: String, max: Int = 20): List<GameHeader> =
        withContext(Dispatchers.IO) {
            Log.d(TAG, "Loading Lichess games for user: $username")

            // Попытка NDJSON (pgnInJson)
            val ndUrl =
                "https://lichess.org/api/games/user/${username.trim()}?max=$max&perfType=blitz,bullet,rapid,classical&analysed=false&clocks=false&evals=false&opening=true&pgnInJson=true"
            val ndReq = Request.Builder()
                .url(ndUrl)
                .header("Accept", "application/x-ndjson")
                .header("User-Agent", UA)
                .build()

            execWithIpv6SafeClient(ndReq)?.let { body ->
                val list = mutableListOf<GameHeader>()
                // Если это NDJSON
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
                    if (list.isNotEmpty()) return@withContext list
                } else {
                    // Фолбэк: это PGN-дамп — режем регуляркой по партиям
                    val rx = Regex("""(?s)(\[(?:.|\n)*?)(?=\n\n\[Event|\z)""", setOf(RegexOption.DOT_MATCHES_ALL))
                    rx.findAll(body).forEach { m ->
                        val pgn = m.groupValues[1].trim()
                        if (pgn.isNotEmpty()) {
                            list += PgnChess.headerFromPgn(pgn).copy(site = Provider.LICHESS, pgn = pgn)
                        }
                    }
                    if (list.isNotEmpty()) return@withContext list
                }
            }

            // Ничего не пришло
            emptyList()
        }

    // --------------------- Загрузчик Chess.com --------------------

    suspend fun loadChessCom(username: String, max: Int = 20): List<GameHeader> =
        withContext(Dispatchers.IO) {
            val archReq = Request.Builder()
                .url("https://api.chess.com/pub/player/${username.trim().lowercase()}/games/archives")
                .header("User-Agent", UA)
                .build()

            val archives = execWithIpv6SafeClient(archReq) ?: return@withContext emptyList()
            val last = Regex(""""(https:[^"]+/[0-9]{4}/[0-9]{2})"""")
                .findAll(archives).toList().lastOrNull()?.groupValues?.get(1)
                ?: return@withContext emptyList<GameHeader>()

            val monthReq = Request.Builder().url(last).header("User-Agent", UA).build()
            val month = execWithIpv6SafeClient(monthReq) ?: return@withContext emptyList()
            val matches = Regex(""""pgn"\s*:\s*"((?:\\.|[^"\\])*)"""")
                .findAll(month).toList()

            matches.takeLast(max).map {
                val pgn = it.groupValues[1].replace("\\n", "\n").replace("\\\"", "\"")
                PgnChess.headerFromPgn(pgn).copy(site = Provider.CHESSCOM, pgn = pgn)
            }
        }

    // ---------------------- Вспомогательная сеть ------------------

    /**
     * Выполняет запрос с предпочтением IPv4 и единообразным фолбэком
     * на «только IPv4», если видим типичные ошибки IPv6-маршрутизации.
     */
    private fun execWithIpv6SafeClient(req: Request): String? {
        // 1) Пробуем клиент с IPv4-первыми адресами
        try {
            clientPreferV4.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (resp.isSuccessful && body.isNotBlank()) return body
                // даже при неуспехе вернём тело — вызывающий решит, подходит ли оно
                if (body.isNotBlank()) return body
            }
        } catch (e: Exception) {
            if (!isIpv6RouteIssue(e)) {
                Log.w(TAG, "HTTP error (preferV4): ${e.message}")
                return null
            }
            Log.w(TAG, "IPv6 route issue detected, retry with IPv4-only… (${e.javaClass.simpleName})")
        }

        // 2) Повтор «только IPv4»
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

    /**
     * Эвристика: распознаём типичные тексты исключений при проблемах IPv6.
     */
    private fun isIpv6RouteIssue(e: Throwable): Boolean {
        val msg = (e.message ?: "").lowercase()
        return ("ehostunreach" in msg) ||
                ("no route to host" in msg) ||
                ("connection reset by peer" in msg) ||
                (e is java.net.ConnectException)
    }
}
