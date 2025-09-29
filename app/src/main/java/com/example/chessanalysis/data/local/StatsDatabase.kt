package com.example.chessanalysis.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.example.chessanalysis.data.local.dao.StatsDao
import com.example.chessanalysis.data.local.entity.GameStatsEntity

@Database(
    entities = [GameStatsEntity::class],
    version = 1,
    exportSchema = false
)
abstract class StatsDatabase : RoomDatabase() {
    abstract fun statsDao(): StatsDao

    companion object {
        @Volatile private var INSTANCE: StatsDatabase? = null

        fun get(context: Context): StatsDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    StatsDatabase::class.java,
                    "stats.db"
                ).build().also { INSTANCE = it }
            }
        }
    }
}
