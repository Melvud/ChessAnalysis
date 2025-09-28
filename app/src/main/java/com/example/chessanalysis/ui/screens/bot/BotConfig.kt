package com.example.chessanalysis.ui.screens.bot

import kotlinx.serialization.Serializable

@Serializable
enum class BotSide { AUTO, WHITE, BLACK }

@Serializable
data class BotConfig(
    val elo: Int = 1350,
    val side: BotSide = BotSide.AUTO,
    val hints: Boolean = false,
    val showMultiPv: Boolean = false,
    val showEvalBar: Boolean = false,
    val allowUndo: Boolean = false
)
