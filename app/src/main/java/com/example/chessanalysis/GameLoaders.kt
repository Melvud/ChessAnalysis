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

    suspend fun loadLichess(username: String, max: Int = 20): List<GameHeader> =
        withContext(Dispatchers.IO) {
            // 1) NDJSON (каждая строка — JSON с полем "pgn")
            val ndUrl =
                "https://lichess.org/api/games/user/${username.trim()}?max=$max&moves=true&opening=true&pgnInJson=true"
            val ndReq = Request.Builder()
                .url(ndUrl)
                .header("User-Agent", UA)
                .header("Accept", "application/x-ndjson")
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

            // 2) Фолбэк: плоский PGN
            val pgnUrl =
                "https://lichess.org/api/games/user/${username.trim()}?max=$max&moves=true&opening=true"
            val pgnReq = Request.Builder()
                .url(pgnUrl)
                .header("User-Agent", UA)
                .build()

            client.newCall(pgnReq).execute().use { resp ->
                if (!resp.isSuccessful) return@use emptyList<GameHeader>()
                val ctypeHeader = resp.header("Content-Type").orEmpty()
                val media = ctypeHeader.toMediaTypeOrNull()
                val ctype = media?.type.orEmpty()

                val body = resp.body?.string().orEmpty()
                if (body.isBlank()) return@use emptyList<GameHeader>()

                // Если вдруг пришёл NDJSON — обработаем как JSON-стрим
                if (ctypeHeader.startsWith("application/x-ndjson")) {
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
                    return@use list
                }

                // Иначе это PGN-дамп: режем регуляркой по партиям
                val rx = Regex("""(?s)(\[(?:.|\n)*?)(?=\n\n\[Event|\z)""", setOf(RegexOption.DOT_MATCHES_ALL))
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

    suspend fun loadChessCom(username: String, max: Int = 20): List<GameHeader> =
        withContext(Dispatchers.IO) {
            val archReq = Request.Builder()
                .url("https://api.chess.com/pub/player/${username.trim().lowercase()}/games/archives")
                .header("User-Agent", UA)
                .build()
            client.newCall(archReq).execute().use { ar ->
                val aj = ar.body?.string().orEmpty()
                val last = Regex(""""(https:[^"]+/[0-9]{4}/[0-9]{2})"""")
                    .findAll(aj).toList().lastOrNull()?.groupValues?.get(1)
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
