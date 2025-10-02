// app/src/main/java/com/example/chessanalysis/data/local/GameDao.kt

package com.example.chessanalysis.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface GameDao {

    @Insert
    suspend fun insertBotGame(entity: BotGameEntity): Long

    @Query("SELECT * FROM bot_games ORDER BY id DESC")
    suspend fun getAllBotGames(): List<BotGameEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertExternalIgnore(entity: ExternalGameEntity): Long

    @Update
    suspend fun updateExternal(entity: ExternalGameEntity)

    @Query("SELECT * FROM external_games WHERE headerKey = :key LIMIT 1")
    suspend fun getExternalByKey(key: String): ExternalGameEntity?

    @Query("UPDATE external_games SET pgn = :pgn WHERE headerKey = :key")
    suspend fun updateExternalPgnByKey(key: String, pgn: String)

    @Query("SELECT * FROM external_games ORDER BY rowid DESC")
    suspend fun getAllExternal(): List<ExternalGameEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertReport(entity: ReportCacheEntity)

    @Query("SELECT * FROM report_cache WHERE pgnHash = :hash LIMIT 1")
    suspend fun getReportByHash(hash: String): ReportCacheEntity?

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
        ORDER BY gameTimestamp DESC
        """
    )
    suspend fun getAllForListByGameTime(): List<ListRow>
}