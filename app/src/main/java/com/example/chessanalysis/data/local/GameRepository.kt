package com.example.chessanalysis.data.local

import android.content.Context
import com.example.chessanalysis.FullReport
import com.example.chessanalysis.GameHeader
import com.example.chessanalysis.Provider
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest

class GameRepository(
    private val db: AppDatabase,
    private val json: Json
) {

    /* ===================== BOT ===================== */

    suspend fun insertBotGame(
        pgn: String,
        white: String,
        black: String,
        result: String,
        dateIso: String
    ): String {
        val hash = pgnHash(pgn)
        db.gameDao().insertBotGame(
            BotGameEntity(
                pgnHash = hash,
                pgn = pgn,
                white = white,
                black = black,
                result = result,
                dateIso = dateIso
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


    /* ===================== EXTERNAL (LICHESS/CHESSCOM) ===================== */

    /** Добавляем только новые внешние партии. Ничего не удаляем. Возвращаем число добавленных. */
    suspend fun mergeExternal(provider: Provider, incoming: List<GameHeader>): Int {
        var added = 0
        for (gh in incoming) {
            val key = headerKeyFor(provider, gh)
            val existing = db.gameDao().getExternalByKey(key)
            if (existing == null) {
                val e = ExternalGameEntity(
                    headerKey = key,
                    provider = provider.name,
                    dateIso = gh.date,
                    result = gh.result,
                    white = gh.white,
                    black = gh.black,
                    opening = gh.opening,
                    eco = gh.eco,
                    pgn = gh.pgn // если уже полный — запишется, если усечён — потом дозакачаем
                )
                val rowId = db.gameDao().insertExternalIgnore(e)
                if (rowId != -1L) added++
            } else {
                // мягко обновим только улучшенные поля (например, появился полный PGN)
                if (gh.pgn != null && (existing.pgn == null || existing.pgn!!.length < gh.pgn!!.length)) {
                    db.gameDao().updateExternal(
                        existing.copy(
                            dateIso = gh.date ?: existing.dateIso,
                            result = gh.result ?: existing.result,
                            white = gh.white ?: existing.white,
                            black = gh.black ?: existing.black,
                            opening = gh.opening ?: existing.opening,
                            eco = gh.eco ?: existing.eco,
                            pgn = gh.pgn
                        )
                    )
                }
            }
        }
        return added
    }

    /** Когда получили полный PGN при открытии партии — сохраняем. */
    suspend fun updateExternalPgn(provider: Provider, gh: GameHeader, fullPgn: String) {
        val key = headerKeyFor(provider, gh)
        db.gameDao().updateExternalPgnByKey(key, fullPgn)
    }

    /**
     * Единый источник для экрана со списком: новейшие добавленные — в самом начале.
     * Используем UNION-запрос с сортировкой по addedAt DESC (см. GameDao.getAllForListOrderByAdded()).
     */
    suspend fun getAllHeaders(): List<GameHeader> {
        val rows = db.gameDao().getAllForListOrderByAdded()
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


    /* ===================== REPORT CACHE ===================== */

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
    }


    /* ===================== KEYS / HASH ===================== */

    fun pgnHash(pgn: String): String = sha256Hex(pgn)

    fun headerKeyFor(provider: Provider, gh: GameHeader): String {
        val raw = buildString {
            append(provider.name).append('|')
            append(gh.date ?: "").append('|')
            append(gh.white ?: "").append('|')
            append(gh.black ?: "").append('|')
            append(gh.result ?: "").append('|')
            append(gh.eco ?: "").append('|')
            append(gh.opening ?: "")
        }
        return sha256Hex(raw)
    }

    private fun sha256Hex(s: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(s.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}

/* Утилита для получения инстанса репозитория из Android-кода */
fun Context.gameRepository(json: Json): GameRepository =
    GameRepository(AppDatabase.getInstance(this), json)
