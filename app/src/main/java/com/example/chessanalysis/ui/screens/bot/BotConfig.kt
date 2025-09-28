package com.example.chessanalysis.ui.screens.bot

enum class BotSide { AUTO, WHITE, BLACK }

data class BotConfig(
    val elo: Int = 1350,
    val side: BotSide = BotSide.AUTO,
    val hints: Boolean = false,
    val showMultiPv: Boolean = false
)
