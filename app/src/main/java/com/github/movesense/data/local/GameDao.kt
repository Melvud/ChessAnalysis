// app/src/main/java/com/example/chessanalysis/data/local/GameDao.kt

package com.github.movesense.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface GameDao {

    // --------------- BOT GAMES ---------------
    @Insert
    suspend fun insertBotGame(entity: BotGameEntity): Long

    @Query("SELECT * FROM bot_games ORDER BY gameTimestamp DESC, addedTimestamp DESC")
    suspend fun getAllBotGames(): List<BotGameEntity>

    // --------------- EXTERNAL GAMES ---------------
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertExternalIgnore(entity: ExternalGameEntity): Long

    @Update
    suspend fun updateExternal(entity: ExternalGameEntity)

    @Query("SELECT * FROM external_games WHERE headerKey = :key LIMIT 1")
    suspend fun getExternalByKey(key: String): ExternalGameEntity?

    @Query("UPDATE external_games SET pgn = :pgn WHERE headerKey = :key")
    suspend fun updateExternalPgnByKey(key: String, pgn: String)

    @Query("SELECT * FROM external_games ORDER BY gameTimestamp DESC, addedTimestamp DESC")
    suspend fun getAllExternal(): List<ExternalGameEntity>

    // --------------- REPORT CACHE ---------------
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertReport(entity: ReportCacheEntity)

    @Query("SELECT * FROM report_cache WHERE pgnHash = :hash LIMIT 1")
    suspend fun getReportByHash(hash: String): ReportCacheEntity?

    // --------------- COMBINED LIST (НОВЫЕ СВЕРХУ) ---------------
    data class ListRow(
        val provider: String,
        val dateIso: String?,
        val result: String?,
        val white: String?,
        val black: String?,
        val opening: String?,
        val eco: String?,
        val pgn: String?,
        val gameTimestamp: Long,
        val addedTimestamp: Long
    )

    /**
     * Объединяет external_games и bot_games.
     * Сортировка:
     * 1) gameTimestamp DESC — новые партии сверху (по времени игры)
     * 2) addedTimestamp DESC — если время партии одинаковое, недавно добавленные выше
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
            gameTimestamp AS gameTimestamp,
            addedTimestamp AS addedTimestamp
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
            gameTimestamp AS gameTimestamp,
            addedTimestamp AS addedTimestamp
        FROM bot_games
        
        ORDER BY gameTimestamp DESC, addedTimestamp DESC
        """
    )
    suspend fun getAllForListByGameTime(): List<ListRow>
}