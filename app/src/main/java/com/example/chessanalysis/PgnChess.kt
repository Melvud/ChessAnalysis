package com.example.chessanalysis

import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.game.Game
import com.github.bhlangonijr.chesslib.move.Move
import com.github.bhlangonijr.chesslib.move.MoveGenerator
import com.github.bhlangonijr.chesslib.move.MoveList
import com.github.bhlangonijr.chesslib.pgn.PgnHolder
import java.io.File

object PgnChess {

    data class MoveItem(
        val san: String,
        val uci: String,
        val beforeFen: String,
        val afterFen: String
    )

    private fun parseTags(pgn: String): Map<String, String> {
        val tagRx = Regex("""\[(\w+)\s+"([^"]*)"\]""")
        return tagRx.findAll(pgn).associate { it.groupValues[1] to it.groupValues[2] }
    }

    fun headerFromPgn(pgn: String): GameHeader {
        val tags = parseTags(pgn)
        return GameHeader(
            site = Provider.LICHESS,
            white = tags["White"],
            black = tags["Black"],
            result = tags["Result"],
            date = tags["UTCDate"] ?: tags["Date"],
            eco = tags["ECO"],
            opening = tags["Opening"],
            pgn = pgn,
            whiteElo = tags["WhiteElo"]?.toIntOrNull(),
            blackElo = tags["BlackElo"]?.toIntOrNull()
        )
    }

    fun movesWithFens(pgn: String): List<MoveItem> {
        val tmp = File.createTempFile("single_", ".pgn")
        tmp.writeText(pgn)

        val holder = PgnHolder(tmp.absolutePath)
        holder.loadPgn()
        val game: Game = holder.games.firstOrNull() ?: return emptyList()
        game.loadMoveText()
        val ml: MoveList = game.halfMoves

        val tags = parseTags(pgn)
        val board = Board()
        if (tags["SetUp"] == "1" && !tags["FEN"].isNullOrBlank()) {
            board.loadFromFen(tags["FEN"])
        }

        val sanArray = ml.toSanArray().toList()
        val result = ArrayList<MoveItem>(ml.size)
        for (i in 0 until ml.size) {
            val move: Move = ml[i]
            val before = board.fen
            val uci = move.toString()
            board.doMove(move)
            val after = board.fen
            val san = sanArray.getOrElse(i) { "" }
            result += MoveItem(san = san, uci = uci, beforeFen = before, afterFen = after)
        }
        runCatching { tmp.delete() }
        return result
    }

    fun legalCount(fen: String): Int {
        val b = Board()
        b.loadFromFen(fen)
        return MoveGenerator.generateLegalMoves(b).size
    }

    fun isSimpleRecapture(prevUci: String?, curUci: String): Boolean {
        if (prevUci == null || prevUci.length < 4 || curUci.length < 4) return false
        val prevTo = prevUci.substring(2, 4)
        val curTo = curUci.substring(2, 4)
        return curTo.equals(prevTo, ignoreCase = true)
    }
}
