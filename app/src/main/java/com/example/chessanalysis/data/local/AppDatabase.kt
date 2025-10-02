// app/src/main/java/com/example/chessanalysis/data/local/AppDatabase.kt

package com.example.chessanalysis.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        BotGameEntity::class,
        ExternalGameEntity::class,
        ReportCacheEntity::class
    ],
    version = 3, // Увеличили версию
    exportSchema = false
)
@TypeConverters(EmptyConverters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun gameDao(): GameDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Добавляем новые колонки с дефолтными значениями
                database.execSQL(
                    "ALTER TABLE external_games ADD COLUMN gameTimestamp INTEGER NOT NULL DEFAULT ${System.currentTimeMillis()}"
                )
                database.execSQL(
                    "ALTER TABLE external_games ADD COLUMN addedTimestamp INTEGER NOT NULL DEFAULT ${System.currentTimeMillis()}"
                )
                database.execSQL(
                    "ALTER TABLE bot_games ADD COLUMN gameTimestamp INTEGER NOT NULL DEFAULT ${System.currentTimeMillis()}"
                )
                database.execSQL(
                    "ALTER TABLE bot_games ADD COLUMN addedTimestamp INTEGER NOT NULL DEFAULT ${System.currentTimeMillis()}"
                )
            }
        }

        fun getInstance(ctx: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    ctx.applicationContext,
                    AppDatabase::class.java,
                    "chessanalysis.db"
                )
                    .addMigrations(MIGRATION_2_3)
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}