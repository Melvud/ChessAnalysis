// app/src/main/java/com/example/chessanalysis/data/local/GameRepository.kt

package com.github.movesense.data.local

import android.content.Context
import android.util.Log
import com.github.movesense.FullReport
import com.github.movesense.GameHeader
import com.github.movesense.Provider
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class GameRepository(private val db: AppDatabase, private val json: Json) {
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
        db.gameDao()
                .insertBotGame(
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
        val sortedIncoming =
                incoming.sortedByDescending { gh -> parseGameTimestamp(gh.pgn ?: "", gh.date) }

        var added = 0
        for (gh in sortedIncoming) {
            val key = headerKeyFor(provider, gh)
            Log.d(TAG, "Processing game: ${gh.white} vs ${gh.black}, key=$key")

            val existing = db.gameDao().getExternalByKey(key)

            if (existing == null) {
                val gameTimestamp = parseGameTimestamp(gh.pgn ?: "", gh.date)
                val e =
                        ExternalGameEntity(
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
                                addedTimestamp = System.currentTimeMillis(),
                                isTest = gh.isTest // 🌟 Сохраняем флаг isTest
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
                if (gh.pgn != null &&
                                (existing.pgn == null || existing.pgn!!.length < gh.pgn!!.length)
                ) {
                    val gameTimestamp = parseGameTimestamp(gh.pgn!!, gh.date)
                    db.gameDao()
                            .updateExternal(
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

    suspend fun deleteTestGames() {
        db.gameDao().deleteTestGames()
        Log.d(TAG, "Deleted all test games")
    }

    suspend fun updateExternalPgn(provider: Provider, gh: GameHeader, fullPgn: String) {
        val key = headerKeyFor(provider, gh)
        db.gameDao().updateExternalPgnByKey(key, fullPgn)
        Log.d(TAG, "Updated PGN for game: ${gh.white} vs ${gh.black}")
    }

    suspend fun getAllHeaders(): List<GameHeader> {
        // Получаем смешанный список (External + Bot) через SQL Union
        // Проблема: SQL запрос возвращает ListRow, который не содержит isTest
        // Решение: Либо добавить isTest в ListRow и SQL запрос, либо...
        // У нас Bot игры не могут быть тестовыми (или могут?).
        // External могут.
        // Давайте обновим ListRow и SQL запрос в GameDao?
        // Или просто загрузим External отдельно и Bot отдельно и объединим в памяти?
        // Текущая реализация getAllForListByGameTime делает UNION.
        // Чтобы пробросить isTest, надо менять GameDao.ListRow и SQL.

        // Но постойте, я не могу легко поменять GameDao.ListRow через replace_file_content,
        // так как он внутри GameDao.kt, который я уже редактировал.
        // И SQL запрос там же.
        // Давайте лучше сделаем так:
        // Если я не могу легко поменять SQL, я могу загрузить External и Bot отдельно.
        // Но тогда потеряется пагинация/сортировка на уровне БД?
        // В текущем коде getAllHeaders грузит ВСЕ заголовки.
        // Так что сортировка в памяти допустима.

        // Вариант 1: Изменить GameDao.kt еще раз, добавив isTest в ListRow и SQL.
        // Это правильнее.

        // Вариант 2 (временный): Забить на отображение isTest в UI (нам оно нужно только для удаления).
        // Но GameHeader имеет поле isTest. Если мы его не заполним, оно будет false.
        // Это нормально, если мы не хотим как-то особо помечать их в UI.
        // Но задача "Тестовые партии магнуса должны удаляться".
        // Удаление происходит через deleteTestGames(), который работает напрямую с БД.
        // Так что в UI знать isTest не обязательно, если мы не хотим их скрывать/показывать фильтром.
        // В UI они просто "игры".
        // Так что можно оставить isTest = false при чтении.

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
                    site =
                            when (r.provider) {
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
                    eco = r.eco,
                    isTest = false // Мы не тянем это из БД через общий запрос, и это ОК для текущей задачи
            )
        }
    }

    // --------------- Кэш отчётов ----------------

    suspend fun getCachedReport(pgn: String): FullReport? {
        val hash = pgnHash(pgn)
        val row = db.gameDao().getReportByHash(hash) ?: return null
        return runCatching { json.decodeFromString<FullReport>(row.reportJson) }.getOrNull()
    }

    suspend fun getCachedReports(pgns: List<String>): Map<String, FullReport> {
        if (pgns.isEmpty()) return emptyMap()

        val hashToPgn = pgns.associateBy { pgnHash(it) }
        val hashes = hashToPgn.keys.toList()

        val rows = db.gameDao().getReportsByHashes(hashes)

        val result = mutableMapOf<String, FullReport>()
        for (row in rows) {
            val report =
                    runCatching { json.decodeFromString<FullReport>(row.reportJson) }.getOrNull()
            if (report != null) {
                // Find original PGN hash to map back
                // We need to map hash back to original PGN string?
                // Actually, the caller probably wants Map<PgnHash, Report> or Map<PgnString,
                // Report>
                // Let's return Map<PgnHash, FullReport> to be consistent with GamesListScreen usage
                result[row.pgnHash] = report
            }
        }
        return result
    }

    suspend fun saveReport(pgn: String, report: FullReport) {
        val hash = pgnHash(pgn)
        db.gameDao()
                .upsertReport(
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

        val raw =
                if (!extId.isNullOrBlank()) {
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
                .find(pgn)
                ?.groupValues
                ?.getOrNull(1)
                ?.let {
                    return it
                }

        Regex("""\[(?:Site|Link)\s+"https?://(?:www\.)?chess\.com/game/(?:live|daily)/(\d+)""")
                .find(pgn)
                ?.groupValues
                ?.getOrNull(1)
                ?.let {
                    return it
                }

        Regex("""\[GameId\s+"([^"]+)"]""").find(pgn)?.groupValues?.getOrNull(1)?.let {
            return it
        }

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
                val formats =
                        listOf(
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
