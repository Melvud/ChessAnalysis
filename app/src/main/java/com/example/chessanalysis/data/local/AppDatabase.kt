package com.example.chessanalysis.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [
        BotGameEntity::class,
        ExternalGameEntity::class,
        ReportCacheEntity::class
    ],
    version = 2,                 // ↑ повысили версию после добавления external_games
    exportSchema = false
)
@TypeConverters(EmptyConverters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun gameDao(): GameDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        fun getInstance(ctx: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    ctx.applicationContext,
                    AppDatabase::class.java,
                    "chessanalysis.db"
                )
                    .fallbackToDestructiveMigration() // быстро и безболезненно для разработки
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
