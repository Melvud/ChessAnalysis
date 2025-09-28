package com.example.chessanalysis.ui.screens

import kotlinx.serialization.Serializable

@Serializable
data class BotConfig(
    val skill: Int,           // 1..20 — НАСТОЯЩИЙ SKILL
    val side: BotSide,        // WHITE / BLACK / RANDOM
    val hints: Boolean,       // зелёная стрелка
    val showLines: Boolean,   // панель топ-3
    val multiPv: Int = 3
)
@Serializable
enum class BotSide { WHITE, BLACK, RANDOM }
