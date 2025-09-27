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
        val siteTag = (tags["Site"] ?: "").lowercase()

        val provider = when {
            "lichess.org" in siteTag -> Provider.LICHESS
            "chess.com" in siteTag   -> Provider.CHESSCOM
            else                     -> null
        }

        return GameHeader(
            site = provider,
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
        // Нормализуем прежде чем отдавать в PgnHolder
        val norm = runCatching { normalizeInternal(pgn) }.getOrElse { pgn }
        val tmp = File.createTempFile("single_", ".pgn")
        tmp.writeText(norm)

        val holder = PgnHolder(tmp.absolutePath)
        holder.loadPgn()
        val game: Game = holder.games.firstOrNull() ?: return emptyList()
        game.loadMoveText()
        val ml: MoveList = game.halfMoves

        val tags = parseTags(norm)
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

    fun validatePgn(pgn: String) {
        val norm = runCatching { normalizeInternal(pgn) }.getOrElse { pgn }
        val tmp = File.createTempFile("chk", ".pgn")
        tmp.writeText(norm)
        try {
            val holder = PgnHolder(tmp.absolutePath)
            holder.loadPgn()
            val games = holder.games
            require(games.isNotEmpty()) { "PGN содержит 0 партий" }
            val b = Board()
            val legal0 = MoveGenerator.generateLegalMoves(b).size
            require(legal0 > 0) { "Не удалось инициализировать позицию FEN" }
        } catch (e: Exception) {
            throw IllegalArgumentException("Некорректный PGN: ${e.message}", e)
        } finally {
            tmp.delete()
        }
    }

    // Локальная упрощённая нормализация (идентичная по сути клиентской)
    private fun normalizeInternal(src: String): String {
        var s = src
            .replace("\uFEFF", "")
            .replace("\r\n", "\n")
            .replace("\r", "\n")
        s = s.replace("0-0-0", "O-O-O").replace("0-0", "O-O")
        s = s.replace("1–0", "1-0").replace("0–1", "0-1")
            .replace("½–½", "1/2-1/2").replace("½-½", "1/2-1/2")
        s = s.replace(Regex("""\{\[%clk [^}]+\]\}"""), "")
        s = s.replace(Regex("""\s\$\d+"""), "")
        s = buildString(s.length) {
            for (ch in s) {
                if (ch == '\n' || ch == '\t' || ch.code >= 32) append(ch)
            }
        }
        val tagBlockRegex = Regex("""\A(?:\[[^\]\n]+\]\s*\n)+""")
        val tagMatch = tagBlockRegex.find(s)
        val rebuilt = if (tagMatch != null) {
            val tagBlock = tagMatch.value.trimEnd('\n')
            val rest = s.substring(tagMatch.range.last + 1)
            val movetext = rest.trimStart('\n', ' ', '\t')
            tagBlock + "\n\n" + movetext
        } else s.trimStart('\n', ' ', '\t')
        var out = rebuilt.trimEnd()
        if (!out.endsWith("\n")) out += "\n"
        return out
    }
}
