// app/src/main/java/com/example/chessanalysis/data/local/GameRepository.kt

package com.github.movesense.data.local

import android.content.Context
import android.util.Log
import com.github.movesense.FullReport
import com.github.movesense.GameHeader
import com.github.movesense.Provider
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.*

class GameRepository(
    private val db: AppDatabase,
    private val json: Json
) {
    companion object {
        private const val TAG = "GameRepository"
    }

    // ---------------- BOT-игры ----------------

    suspend fun insertBotGame(
        pgn: String,
        white: String,
        black: String,
        result: String,
        dateIso: String
    ): String {
        val hash = pgnHash(pgn)
        val timestamp = parseGameTimestamp(pgn, dateIso)
        Log.d(TAG, "Inserting bot game: $white vs $black, timestamp=$timestamp")
        db.gameDao().insertBotGame(
            BotGameEntity(
                pgnHash = hash,
                pgn = pgn,
                white = white,
                black = black,
                result = result,
                dateIso = dateIso,
                gameTimestamp = timestamp,
                addedTimestamp = System.currentTimeMillis()
            )
        )
        return hash
    }

    suspend fun getBotGamesAsHeaders(): List<GameHeader> =
        db.gameDao().getAllBotGames().map { e ->
            GameHeader(
                site = Provider.BOT,
                pgn = e.pgn,
                white = e.white,
                black = e.black,
                result = e.result,
                date = e.dateIso,
                sideToView = null,
                opening = null,
                eco = null
            )
        }

    // --------------- Внешние игры (Lichess/Chess.com) ----------------

    suspend fun mergeExternal(provider: Provider, incoming: List<GameHeader>): Int {
        Log.d(TAG, "mergeExternal: provider=$provider, incoming size=${incoming.size}")

        // СОРТИРУЕМ ВХОДЯЩИЕ ИГРЫ ОТ НОВЫХ К СТАРЫМ
        val sortedIncoming = incoming.sortedByDescending { gh ->
            parseGameTimestamp(gh.pgn ?: "", gh.date)
        }

        var added = 0
        for (gh in sortedIncoming) {
            val key = headerKeyFor(provider, gh)
            Log.d(TAG, "Processing game: ${gh.white} vs ${gh.black}, key=$key")

            val existing = db.gameDao().getExternalByKey(key)

            if (existing == null) {
                val gameTimestamp = parseGameTimestamp(gh.pgn ?: "", gh.date)
                val e = ExternalGameEntity(
                    headerKey = key,
                    provider = provider.name,
                    dateIso = gh.date,
                    result = gh.result,
                    white = gh.white,
                    black = gh.black,
                    opening = gh.opening,
                    eco = gh.eco,
                    pgn = gh.pgn,
                    gameTimestamp = gameTimestamp,
                    addedTimestamp = System.currentTimeMillis()
                )
                val rowId = db.gameDao().insertExternalIgnore(e)
                if (rowId != -1L) {
                    added++
                    Log.d(TAG, "✓ Added new game: ${gh.white} vs ${gh.black}, date=${gh.date}")
                } else {
                    Log.w(TAG, "⚠ Failed to insert game (duplicate?): ${gh.white} vs ${gh.black}")
                }
            } else {
                // Обновляем до более полного PGN, если он короче/пустой в БД
                if (gh.pgn != null && (existing.pgn == null || existing.pgn!!.length < gh.pgn!!.length)) {
                    val gameTimestamp = parseGameTimestamp(gh.pgn!!, gh.date)
                    db.gameDao().updateExternal(
                        existing.copy(
                            dateIso = gh.date ?: existing.dateIso,
                            result = gh.result ?: existing.result,
                            white = gh.white ?: existing.white,
                            black = gh.black ?: existing.black,
                            opening = gh.opening ?: existing.opening,
                            eco = gh.eco ?: existing.eco,
                            pgn = gh.pgn,
                            gameTimestamp = gameTimestamp
                        )
                    )
                    Log.d(TAG, "✓ Updated existing game PGN: ${gh.white} vs ${gh.black}")
                } else {
                    Log.d(TAG, "⏭ Game already exists (skipped): ${gh.white} vs ${gh.black}")
                }
            }
        }

        Log.d(TAG, "mergeExternal: added $added new games")
        return added
    }

    // 🌟 НОВЫЙ МЕТОД 🌟
    suspend fun getNewestGameTimestamp(provider: Provider): Long? {
        return db.gameDao().getNewestGameTimestamp(provider.name)
    }

    suspend fun updateExternalPgn(provider: Provider, gh: GameHeader, fullPgn: String) {
        val key = headerKeyFor(provider, gh)
        db.gameDao().updateExternalPgnByKey(key, fullPgn)
        Log.d(TAG, "Updated PGN for game: ${gh.white} vs ${gh.black}")
    }

    suspend fun getAllHeaders(): List<GameHeader> {
        val rows = db.gameDao().getAllForListByGameTime()
        Log.d(TAG, "getAllHeaders: loaded ${rows.size} games from DB")

        if (rows.isEmpty()) {
            Log.w(TAG, "No games found in database!")
            val externalCount = db.gameDao().getAllExternal().size
            val botCount = db.gameDao().getAllBotGames().size
            Log.d(TAG, "Direct query shows: external=$externalCount, bot=$botCount")
        }

        return rows.map { r ->
            GameHeader(
                site = when (r.provider) {
                    Provider.LICHESS.name -> Provider.LICHESS
                    Provider.CHESSCOM.name -> Provider.CHESSCOM
                    Provider.BOT.name -> Provider.BOT
                    else -> Provider.LICHESS
                },
                pgn = r.pgn,
                white = r.white,
                black = r.black,
                result = r.result,
                date = r.dateIso,
                sideToView = null,
                opening = r.opening,
                eco = r.eco
            )
        }
    }

    // --------------- Кэш отчётов ----------------

    suspend fun getCachedReport(pgn: String): FullReport? {
        val hash = pgnHash(pgn)
        val row = db.gameDao().getReportByHash(hash) ?: return null
        return runCatching { json.decodeFromString<FullReport>(row.reportJson) }.getOrNull()
    }

    suspend fun saveReport(pgn: String, report: FullReport) {
        val hash = pgnHash(pgn)
        db.gameDao().upsertReport(
            ReportCacheEntity(
                pgnHash = hash,
                reportJson = json.encodeToString(report),
                createdAtMillis = System.currentTimeMillis()
            )
        )
        Log.d(TAG, "Saved analysis report for game")
    }

    // --------------- Ключи и хэши ----------------

    fun pgnHash(pgn: String): String = sha256Hex(pgn)

    fun headerKeyFor(provider: Provider, gh: GameHeader): String {
        val extId = gh.pgn?.let { extractExternalIdFromPgn(it) }

        val raw = if (!extId.isNullOrBlank()) {
            "${provider.name}|id:$extId"
        } else {
            buildString {
                append(provider.name).append('|')
                append(gh.date ?: "").append('|')
                append(gh.white?.trim()?.lowercase() ?: "").append('|')
                append(gh.black?.trim()?.lowercase() ?: "").append('|')
                append(gh.result ?: "")
            }
        }

        val key = sha256Hex(raw)
        Log.d(TAG, "Generated key: $key for ${gh.white} vs ${gh.black}")
        return key
    }

    private fun sha256Hex(s: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(s.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun extractExternalIdFromPgn(pgn: String): String? {
        Regex("""\[(?:Site|Link)\s+"[^"]*lichess\.org/([a-zA-Z0-9]{8})""")
            .find(pgn)?.groupValues?.getOrNull(1)?.let { return it }

        Regex("""\[(?:Site|Link)\s+"https?://(?:www\.)?chess\.com/game/(?:live|daily)/(\d+)""")
            .find(pgn)?.groupValues?.getOrNull(1)?.let { return it }

        Regex("""\[GameId\s+"([^"]+)"]""")
            .find(pgn)?.groupValues?.getOrNull(1)?.let { return it }

        return null
    }

    private fun parseGameTimestamp(pgn: String, dateIso: String?): Long {
        try {
            // Попытка UTCDate/UTCTime
            val utcDateMatch = Regex("""\[UTCDate\s+"([^"]+)"]""").find(pgn)
            val utcTimeMatch = Regex("""\[UTCTime\s+"([^"]+)"]""").find(pgn)

            if (utcDateMatch != null && utcTimeMatch != null) {
                val date = utcDateMatch.groupValues[1]
                val time = utcTimeMatch.groupValues[1]
                val format = SimpleDateFormat("yyyy.MM.dd HH:mm:ss", Locale.US)
                format.timeZone = TimeZone.getTimeZone("UTC")
                return format.parse("$date $time")?.time ?: System.currentTimeMillis()
            }

            // Fallback на Date
            val dateMatch = Regex("""\[Date\s+"([^"]+)"]""").find(pgn)
            if (dateMatch != null) {
                val date = dateMatch.groupValues[1]
                val format = SimpleDateFormat("yyyy.MM.dd", Locale.US)
                return format.parse(date)?.time ?: System.currentTimeMillis()
            }

            // Fallback на переданный dateIso
            if (!dateIso.isNullOrBlank()) {
                val formats = listOf(
                    SimpleDateFormat("yyyy.MM.dd", Locale.US),
                    SimpleDateFormat("yyyy-MM-dd", Locale.US),
                    SimpleDateFormat("dd.MM.yyyy", Locale.US)
                )
                for (format in formats) {
                    try {
                        return format.parse(dateIso)?.time ?: continue
                    } catch (_: Exception) {
                        continue
                    }
                }
            }
        } catch (_: Exception) {
            // Игнорируем ошибки парсинга
        }
        return System.currentTimeMillis()
    }
}

fun Context.gameRepository(json: Json): GameRepository =
    GameRepository(AppDatabase.getInstance(this), json)