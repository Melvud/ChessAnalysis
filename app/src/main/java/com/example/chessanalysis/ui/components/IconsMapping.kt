package com.example.chessanalysis.ui.components

import androidx.compose.ui.graphics.Color
import com.example.chessanalysis.MoveClass
import com.example.chessanalysis.R

/**
 * Ресурсы значков и базовые цвета контейнеров под разные классы хода.
 * Здесь же можно централизованно подкручивать оттенки.
 */
data class MoveBadgeRes(
    val iconRes: Int,
    val container: Color
)

fun moveClassBadgeRes(mc: MoveClass): MoveBadgeRes = when (mc) {
    MoveClass.SPLENDID   -> MoveBadgeRes(R.drawable.splendid,   Color(0xFF1B5E20)) // тёмно-зелёный
    MoveClass.PERFECT    -> MoveBadgeRes(R.drawable.perfect,    Color(0xFF2E7D32))
    MoveClass.BEST       -> MoveBadgeRes(R.drawable.best,       Color(0xFF388E3C))
    MoveClass.EXCELLENT  -> MoveBadgeRes(R.drawable.excellent,  Color(0xFF43A047))
    MoveClass.OKAY       -> MoveBadgeRes(R.drawable.okay,       Color(0xFF689F38))
    MoveClass.FORCED     -> MoveBadgeRes(R.drawable.forced,     Color(0xFF607D8B)) // серо-синий
    MoveClass.OPENING    -> MoveBadgeRes(R.drawable.opening,    Color(0xFF5D4037)) // коричневый
    MoveClass.INACCURACY -> MoveBadgeRes(R.drawable.inaccuracy, Color(0xFFFFEB3B)) // жёлтый
    MoveClass.MISTAKE    -> MoveBadgeRes(R.drawable.mistake,    Color(0xFFFF9800)) // оранжевый
    MoveClass.BLUNDER    -> MoveBadgeRes(R.drawable.blunder,    Color(0xFFF44336)) // красный
}
