package com.example.chessanalysis.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Партии против бота, чтобы показывать их в общем списке.
 * (оставляем, как ранее — id авто, pgnHash для полезного ключа)
 */
@Entity(tableName = "bot_games")
data class BotGameEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val pgnHash: String,          // SHA-256 от полного PGN
    val pgn: String,
    val dateIso: String,          // ISO-8601
    val result: String,           // "1-0" | "0-1" | "1/2-1/2" | "*"
    val white: String,
    val black: String
)

/**
 * Внешние партии (Lichess / Chess.com) — храним минимальные данные для списка.
 * Уникальный ключ headerKey вычисляем из стабильных полей заголовка.
 */
@Entity(tableName = "external_games")
data class ExternalGameEntity(
    @PrimaryKey val headerKey: String, // уникальный ключ партии (см. GameRepository.headerKeyFor)
    val provider: String,              // "LICHESS" | "CHESSCOM"
    val dateIso: String?,              // может быть пустым, но стараемся хранить
    val result: String?,
    val white: String?,
    val black: String?,
    val opening: String?,
    val eco: String?,
    val pgn: String?                   // полный PGN можно дозаписать позже
)

/**
 * Кэш отчётов (FullReport) по ключу pgnHash.
 */
@Entity(tableName = "report_cache")
data class ReportCacheEntity(
    @PrimaryKey val pgnHash: String, // SHA-256 от полного PGN
    val reportJson: String,          // сериализованный FullReport
    val createdAtMillis: Long
)
