package com.example.chessanalysis.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.chessanalysis.data.local.entity.GameStatsEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface StatsDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: GameStatsEntity)

    @Update
    suspend fun update(item: GameStatsEntity)

    @Query("SELECT COUNT(*) FROM game_stats")
    fun countFlow(): Flow<Int>

    @Query("SELECT * FROM game_stats ORDER BY createdAt DESC LIMIT 10")
    fun last10Flow(): Flow<List<GameStatsEntity>>

    @Query("SELECT * FROM game_stats ORDER BY createdAt DESC LIMIT 10")
    suspend fun last10(): List<GameStatsEntity>

    @Query("SELECT AVG(accuracy) FROM game_stats")
    fun avgAccuracyFlow(): Flow<Double?>

    @Query("SELECT AVG(performance) FROM game_stats")
    fun avgPerformanceFlow(): Flow<Double?>

    @Query("""
        SELECT SUM(bestCount) as best, SUM(brilliantCount) as br, SUM(greatCount) as gr, 
               SUM(goodCount) as good, SUM(inaccuracyCount) as ina, 
               SUM(mistakeCount) as mis, SUM(blunderCount) as bl, SUM(moveCount) as total
        FROM game_stats
    """)
    fun totalsFlow(): Flow<Totals?>

    @Query("SELECT * FROM game_stats WHERE syncedAt IS NULL ORDER BY createdAt ASC LIMIT 100")
    suspend fun pendingForSync(): List<GameStatsEntity>

    data class Totals(
        val best: Long?,
        val br: Long?,
        val gr: Long?,
        val good: Long?,
        val ina: Long?,
        val mis: Long?,
        val bl: Long?,
        val total: Long?
    )
}
