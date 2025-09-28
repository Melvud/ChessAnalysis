package com.example.chessanalysis.ui.screens.bot

enum class BotSide { AUTO, WHITE, BLACK }

data class BotConfig(
    val elo: Int = 1350,
    val side: BotSide = BotSide.AUTO,
    val hints: Boolean = false,
    /** Показывать top-N линий (используется для панели линий — как и раньше) */
    val showMultiPv: Boolean = false,
    /** Отдельный тумблер: показывать вертикальную шкалу оценки */
    val showEvalBar: Boolean = false,
    /** Разрешить кнопку «Вернуть ход» на экране партии */
    val allowUndo: Boolean = false
)
