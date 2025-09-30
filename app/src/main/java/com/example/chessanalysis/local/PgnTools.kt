package com.example.chessanalysis.local

import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.game.Game
import com.github.bhlangonijr.chesslib.game.GameLoader
import com.github.bhlangonijr.chesslib.move.Move
import com.github.bhlangonijr.chesslib.move.SanUtils
import java.io.ByteArrayInputStream

data class ParsedGame(
    val header: Map<String, String>,
    val movesUci: List<String>,
    val fensBefore: List<String>, // FEN перед каждым ходом
    val fensAfter: List<String>   // FEN после каждого хода (соответствует movesUci[i])
)

/** Парсит один PGN (первую партию в тексте) в UCI и FEN-цепочку. */
fun parsePgnToUciAndFens(pgn: String): ParsedGame {
    val games: List<Game> = GameLoader.loadGames(ByteArrayInputStream(pgn.toByteArray()))
    require(games.isNotEmpty()) { "PGN: no games found" }
    val g = games.first()

    val board = Board() // стартовая позиция
    val header = buildMap {
        g.headers.forEach { put(it.key, it.value) }
    }

    val fensBefore = mutableListOf<String>()
    val fensAfter = mutableListOf<String>()
    val movesUci = mutableListOf<String>()

    g.halfMoves.forEach { san ->
        fensBefore += board.fen
        val move: Move = SanUtils.getMoveFromSan(board, san)
        val uci = move.toString() // chesslib печатает UCI вида e2e4, e7e8q и т.п.
        movesUci += uci
        board.doMove(move)
        fensAfter += board.fen
    }

    return ParsedGame(
        header = header,
        movesUci = movesUci,
        fensBefore = fensBefore,
        fensAfter = fensAfter
    )
}
