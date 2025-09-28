package com.example.chessanalysis.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface GameDao {

    /* -------- Bot games -------- */

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBotGame(entity: BotGameEntity): Long

    @Query("SELECT * FROM bot_games ORDER BY dateIso DESC")
    suspend fun getAllBotGames(): List<BotGameEntity>


    /* -------- External games (lichess / chess.com) -------- */

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertExternalIgnore(entity: ExternalGameEntity): Long

    @Update
    suspend fun updateExternal(entity: ExternalGameEntity)

    @Query("SELECT * FROM external_games ORDER BY dateIso DESC")
    suspend fun getAllExternal(): List<ExternalGameEntity>

    @Query("SELECT * FROM external_games WHERE headerKey = :key LIMIT 1")
    suspend fun getExternalByKey(key: String): ExternalGameEntity?

    @Query("UPDATE external_games SET pgn = :pgn WHERE headerKey = :key")
    suspend fun updateExternalPgnByKey(key: String, pgn: String)


    /* -------- Report cache -------- */

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertReport(entity: ReportCacheEntity)

    @Query("SELECT * FROM report_cache WHERE pgnHash = :hash LIMIT 1")
    suspend fun getReportByHash(hash: String): ReportCacheEntity?
}
