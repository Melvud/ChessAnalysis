// app/src/main/java/com/example/chessanalysis/data/local/AppDatabase.kt

package com.github.movesense.data.local

import android.content.Context
import android.util.Log
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
    version = 6, // УВЕЛИЧИЛИ версию до 6
    exportSchema = false
)
@TypeConverters(EmptyConverters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun gameDao(): GameDao

    companion object {
        private const val TAG = "AppDatabase"

        @Volatile private var INSTANCE: AppDatabase? = null

        // Миграция 2 -> 3
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                Log.d(TAG, "Running migration 2->3")
                try {
                    // Добавляем колонки со значением 0 (будет обновлено позже)
                    database.execSQL(
                        "ALTER TABLE external_games ADD COLUMN gameTimestamp INTEGER NOT NULL DEFAULT 0"
                    )
                    database.execSQL(
                        "ALTER TABLE external_games ADD COLUMN addedTimestamp INTEGER NOT NULL DEFAULT 0"
                    )
                    database.execSQL(
                        "ALTER TABLE bot_games ADD COLUMN gameTimestamp INTEGER NOT NULL DEFAULT 0"
                    )
                    database.execSQL(
                        "ALTER TABLE bot_games ADD COLUMN addedTimestamp INTEGER NOT NULL DEFAULT 0"
                    )

                    // Устанавливаем текущее время для всех существующих записей
                    val now = System.currentTimeMillis()
                    database.execSQL("UPDATE external_games SET gameTimestamp = $now, addedTimestamp = $now")
                    database.execSQL("UPDATE bot_games SET gameTimestamp = $now, addedTimestamp = $now")

                    Log.d(TAG, "Migration 2->3 completed successfully")
                } catch (e: Exception) {
                    Log.e(TAG, "Migration 2->3 failed: ${e.message}", e)
                    throw e
                }
            }
        }

        // Миграция 3 -> 4: пересоздаём таблицы с правильными значениями по умолчанию
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                Log.d(TAG, "Running migration 3->4")
                try {
                    // Обновляем все записи, где timestamp = 0
                    val now = System.currentTimeMillis()
                    database.execSQL("UPDATE external_games SET gameTimestamp = $now WHERE gameTimestamp = 0")
                    database.execSQL("UPDATE external_games SET addedTimestamp = $now WHERE addedTimestamp = 0")
                    database.execSQL("UPDATE bot_games SET gameTimestamp = $now WHERE gameTimestamp = 0")
                    database.execSQL("UPDATE bot_games SET addedTimestamp = $now WHERE addedTimestamp = 0")

                    Log.d(TAG, "Migration 3->4 completed successfully")
                } catch (e: Exception) {
                    Log.e(TAG, "Migration 3->4 failed: ${e.message}", e)
                    throw e
                }
            }
        }

        // Миграция 4 -> 5: добавляем колонку isTest
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(database: SupportSQLiteDatabase) {
                Log.d(TAG, "Running migration 4->5")
                try {
                    database.execSQL(
                        "ALTER TABLE external_games ADD COLUMN isTest INTEGER NOT NULL DEFAULT 0"
                    )
                    Log.d(TAG, "Migration 4->5 completed successfully")
                } catch (e: Exception) {
                    Log.e(TAG, "Migration 4->5 failed: ${e.message}", e)
                    throw e
                }
            }
        }

        fun getInstance(ctx: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    ctx.applicationContext,
                    AppDatabase::class.java,
                    "chessanalysis.db"
                )
                    .addMigrations(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                    .fallbackToDestructiveMigration() // На всякий случай
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}