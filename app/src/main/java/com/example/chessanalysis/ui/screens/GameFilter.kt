// app/src/main/java/com/example/chessanalysis/ui/screens/GameFilter.kt

package com.example.chessanalysis.ui.screens

enum class GameFilter(val label: String) {
    ALL("Все партии"),
    ANALYZED("Анализированные"),
    NOT_ANALYZED("Не анализированные"),
    WINS("Победы"),
    LOSSES("Поражения"),
    DRAWS("Ничьи"),
    MANUAL("Добавленные вручную"),
    HIGH_ACCURACY("Высокая точность (>85%)"),
    LOW_ACCURACY("Низкая точность (<70%)"),
    HIGH_PERFORMANCE("Высокий перформанс (>2000)"),
    OLDEST("Самые старые"),
    NEWEST("Самые новые")
}