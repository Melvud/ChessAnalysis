package com.example.chessanalysis.local

data class ParsedGame(
    val header: Map<String, String>,
    val movesUci: List<String>,
    val fensBefore: List<String>,
    val fensAfter: List<String>
)

fun parsePgnToUciAndFens(pgn: String): ParsedGame {
    // Упрощенная версия для демонстрации
    // В реальном приложении используйте полноценный PGN парсер

    val header = mutableMapOf<String, String>()
    val lines = pgn.lines()
    var moveTextStartIndex = 0

    // Парсим заголовки
    for (i in lines.indices) {
        val line = lines[i]
        if (line.startsWith("[") && line.endsWith("]")) {
            val match = Regex("""\[(\w+)\s+"([^"]*)"\]""").find(line)
            if (match != null) {
                header[match.groupValues[1]] = match.groupValues[2]
            }
        } else if (line.isNotBlank()) {
            moveTextStartIndex = i
            break
        }
    }

    // Упрощенная обработка ходов - нужна полная реализация
    val startFen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"

    return ParsedGame(
        header = header,
        movesUci = emptyList(), // Требует полной реализации
        fensBefore = listOf(startFen),
        fensAfter = listOf(startFen)
    )
}