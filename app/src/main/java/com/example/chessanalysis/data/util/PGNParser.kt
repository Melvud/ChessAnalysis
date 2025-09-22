package com.example.chessanalysis.data.util

import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.move.Move
import com.github.bhlangonijr.chesslib.move.MoveList
import com.github.bhlangonijr.chesslib.pgn.PgnHolder
import java.nio.file.Files
import kotlin.io.path.writeText

data class PositionSnapshot(
    val fenBefore: String,
    val fenAfter: String,
    val san: String,
    val uci: String
)

data class ParsedGame(val positions: List<PositionSnapshot>)

object PGNParser {

    fun parseGame(pgn: String): ParsedGame {
        // пишем PGN во временный файл — так PgnHolder его спокойно прочитает
        val tmp = Files.createTempFile("game_", ".pgn")
        tmp.writeText(pgn)

        val holder = PgnHolder(tmp.toFile().absolutePath)
        holder.loadPgn()

        val game = holder.games.first()
        // КЛЮЧЕВОЕ: прогружаем текст ходов, чтобы заполнить halfMoves
        game.loadMoveText()

        val moveList: MoveList = game.halfMoves
        val sanArray: Array<String> = game.halfMoves.toSanArray()

        val board = Board()
        val positions = ArrayList<PositionSnapshot>(moveList.size)

        for (i in 0 until moveList.size) {
            val move: Move = moveList[i]
            val fenBefore = board.fen
            val san = if (i < sanArray.size) sanArray[i] else ""
            val uci = move.toString() // в chesslib toString() -> UCI, напр. "e2e4"

            board.doMove(move)

            val fenAfter = board.fen
            positions.add(
                PositionSnapshot(
                    fenBefore = fenBefore,
                    fenAfter = fenAfter,
                    san = san,
                    uci = uci
                )
            )
        }

        try { Files.deleteIfExists(tmp) } catch (_: Exception) {}
        return ParsedGame(positions)
    }
}
