package com.example.chessanalysis

import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.Piece
import com.github.bhlangonijr.chesslib.Square
import com.github.bhlangonijr.chesslib.game.Game
import com.github.bhlangonijr.chesslib.move.Move
import com.github.bhlangonijr.chesslib.move.MoveList
import com.github.bhlangonijr.chesslib.pgn.PgnHolder
import java.io.File
import java.util.Locale

object PgnToFens {

    data class Result(
        val fens: List<String>,
        val uciMoves: List<String>,
        val header: GameHeader?
    )

    private fun parseTags(pgn: String): Map<String, String> {
        val rx = Regex("""\[(\w+)\s+"([^"]*)"\]""")
        return rx.findAll(pgn).associate { it.groupValues[1] to it.groupValues[2] }
    }

    private fun toUci(move: Move): String {
        val from = move.from.toString().lowercase(Locale.ROOT) // "E2" -> "e2"
        val to = move.to.toString().lowercase(Locale.ROOT)
        val promo = move.promotion
        val promoChar = when (promo?.pieceType) {
            com.github.bhlangonijr.chesslib.PieceType.QUEEN -> "q"
            com.github.bhlangonijr.chesslib.PieceType.ROOK -> "r"
            com.github.bhlangonijr.chesslib.PieceType.BISHOP -> "b"
            com.github.bhlangonijr.chesslib.PieceType.KNIGHT -> "n"
            else -> ""
        }
        return from + to + promoChar
    }

    /**
     * Строим FENы и UCI из строки PGN (через chesslib).
     * Безопасно: пишем во временный файл и парсим его PgnHolder'ом.
     */
    fun fromPgn(pgn: String): Result {
        // На всякий случай — нормализуем \r\n
        val p = pgn.replace("\r\n", "\n").trim()

        // Временный файл под PgnHolder
        val tmp = File.createTempFile("game_", ".pgn")
        tmp.writeText(p, Charsets.UTF_8)

        val holder = PgnHolder(tmp.absolutePath)
        holder.loadPgn()
        val game: Game = holder.games.firstOrNull()
            ?: return Result(emptyList(), emptyList(), null)

        // Вытягиваем SAN-список у chesslib и сами проигрываем доску
        game.loadMoveText()
        val ml: MoveList = game.halfMoves

        val tags = parseTags(p)
        val header = GameHeader(
            site = if ((tags["Site"] ?: "").contains("lichess", true)) Provider.LICHESS else null,
            white = tags["White"],
            black = tags["Black"],
            result = tags["Result"],
            date = tags["UTCDate"] ?: tags["Date"],
            eco = tags["ECO"],
            opening = tags["Opening"],
            pgn = p,
            whiteElo = tags["WhiteElo"]?.toIntOrNull(),
            blackElo = tags["BlackElo"]?.toIntOrNull()
        )

        val board = Board()
        if (tags["SetUp"] == "1" && !tags["FEN"].isNullOrBlank()) {
            board.loadFromFen(tags["FEN"])
        }

        val fens = ArrayList<String>(ml.size + 1)
        val uci = ArrayList<String>(ml.size)

        fens += board.fen  // стартовая позиция
        for (i in 0 until ml.size) {
            val move: Move = ml[i]
            uci += toUci(move)
            board.doMove(move)
            fens += board.fen
        }

        // Удаляем за собой временный файл
        runCatching { tmp.delete() }

        return Result(fens = fens, uciMoves = uci, header = header)
    }
}
