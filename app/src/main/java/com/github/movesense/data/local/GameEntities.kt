// app/src/main/java/com/example/chessanalysis/data/local/GameEntities.kt

package com.github.movesense.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "bot_games")
data class BotGameEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val pgnHash: String,
    val pgn: String,
    val dateIso: String,
    val result: String,
    val white: String,
    val black: String,
    val gameTimestamp: Long = System.currentTimeMillis(), // Время игры
    val addedTimestamp: Long = System.currentTimeMillis() // Время добавления
)

@Entity(tableName = "external_games")
data class ExternalGameEntity(
    @PrimaryKey val headerKey: String,
    val provider: String,
    val dateIso: String?,
    val result: String?,
    val white: String?,
    val black: String?,
    val opening: String?,
    val eco: String?,
    val pgn: String?,
    val gameTimestamp: Long = System.currentTimeMillis(), // Время игры из PGN
    val addedTimestamp: Long = System.currentTimeMillis(), // Время добавления в БД
    val isTest: Boolean = false // Флаг тестовой игры
)

@Entity(tableName = "report_cache")
data class ReportCacheEntity(
    @PrimaryKey val pgnHash: String,
    val reportJson: String,
    val createdAtMillis: Long
)