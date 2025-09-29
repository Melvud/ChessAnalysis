package com.example.chessanalysis.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "game_stats")
data class GameStatsEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val createdAt: Long = System.currentTimeMillis(),

    // Базовые метрики
    val accuracy: Double,            // 0..100
    val performance: Int,            // условно «перфоманс-рейтинг» партии
    val opponentRating: Int,         // рейтинг соперника (если известен, иначе 0)

    // Классификация ходов
    val moveCount: Int,
    val bestCount: Int,
    val brilliantCount: Int,
    val greatCount: Int,
    val goodCount: Int,
    val inaccuracyCount: Int,
    val mistakeCount: Int,
    val blunderCount: Int,

    // Флаг синхронизации с облаком
    val syncedAt: Long? = null,      // timestamp когда ушло в Firestore, null если не синкалось
    val updatedAt: Long = System.currentTimeMillis()
)
