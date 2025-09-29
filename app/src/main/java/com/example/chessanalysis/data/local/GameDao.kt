package com.example.chessanalysis.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface GameDao {

    /* ===================== BOT ===================== */

    @Insert
    suspend fun insertBotGame(entity: BotGameEntity): Long

    // Сами по себе боты теперь нам не нужны — используем общий UNION-запрос ниже.
    @Query("SELECT * FROM bot_games ORDER BY id DESC")
    suspend fun getAllBotGames(): List<BotGameEntity>


    /* ===================== EXTERNAL ===================== */

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertExternalIgnore(entity: ExternalGameEntity): Long

    @Update
    suspend fun updateExternal(entity: ExternalGameEntity)

    @Query("SELECT * FROM external_games WHERE headerKey = :key LIMIT 1")
    suspend fun getExternalByKey(key: String): ExternalGameEntity?

    @Query("UPDATE external_games SET pgn = :pgn WHERE headerKey = :key")
    suspend fun updateExternalPgnByKey(key: String, pgn: String)

    // Как и для ботов — отдельная выборка может пригодиться, но для списка используем UNION.
    @Query("SELECT * FROM external_games ORDER BY rowid DESC")
    suspend fun getAllExternal(): List<ExternalGameEntity>


    /* ===================== REPORT CACHE ===================== */

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertReport(entity: ReportCacheEntity)

    @Query("SELECT * FROM report_cache WHERE pgnHash = :hash LIMIT 1")
    suspend fun getReportByHash(hash: String): ReportCacheEntity?


    /* ===================== ОБЪЕДИНЁННАЯ ЛЕНТА ===================== */

    /**
     * Универсальная строка для общей ленты партий.
     * provider: 'LICHESS' | 'CHESSCOM' | 'BOT'
     * addedAt: порядок добавления (rowid/id), DESC = новейшие сверху.
     */
    data class ListRow(
        val provider: String,
        val dateIso: String?,
        val result: String?,
        val white: String?,
        val black: String?,
        val opening: String?,
        val eco: String?,
        val pgn: String?,
        val addedAt: Long
    )

    /**
     * Берём все внешние и все бот-партии, добавляем техническое поле addedAt и сортируем так,
     * чтобы самые новые (по факту вставки в БД) были первыми.
     *
     * Важно: типы и порядок столбцов в обоих SELECT должны совпадать.
     */
    @Query(
        """
        SELECT 
            provider AS provider,
            dateIso  AS dateIso,
            result   AS result,
            white    AS white,
            black    AS black,
            opening  AS opening,
            eco      AS eco,
            pgn      AS pgn,
            rowid    AS addedAt
        FROM external_games
        UNION ALL
        SELECT 
            'BOT'    AS provider,
            dateIso  AS dateIso,
            result   AS result,
            white    AS white,
            black    AS black,
            NULL     AS opening,
            NULL     AS eco,
            pgn      AS pgn,
            id       AS addedAt
        FROM bot_games
        ORDER BY addedAt DESC
        """
    )
    suspend fun getAllForListOrderByAdded(): List<ListRow>
}
