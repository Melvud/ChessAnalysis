package com.github.movesense

import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.PieceType
import com.github.bhlangonijr.chesslib.game.Game
import com.github.bhlangonijr.chesslib.move.Move
import com.github.bhlangonijr.chesslib.move.MoveGenerator
import com.github.bhlangonijr.chesslib.move.MoveList
import com.github.bhlangonijr.chesslib.pgn.PgnHolder
import java.io.File
import java.util.Locale
import kotlin.text.iterator

object PgnChess {

    data class MoveItem(
        val san: String,
        val uci: String,
        val beforeFen: String,
        val afterFen: String
    )

    data class PgnParseResult(
        val fens: List<String>,
        val uciMoves: List<String>,
        val header: GameHeader?
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

        val whiteTitle = tags["WhiteTitle"]
        val blackTitle = tags["BlackTitle"]

        val eco = tags["ECO"]
        // Try to get refined opening name from library, fallback to PGN tag
        val openingName = com.github.movesense.data.OpeningLibrary.getOpeningName(eco, pgn) 
            ?: tags["Opening"]

        return GameHeader(
            site = provider,
            white = if (whiteTitle != null) "$whiteTitle ${tags["White"]}" else tags["White"],
            black = if (blackTitle != null) "$blackTitle ${tags["Black"]}" else tags["Black"],
            result = tags["Result"],
            date = tags["UTCDate"] ?: tags["Date"],
            eco = eco,
            opening = openingName,
            pgn = pgn,
            whiteElo = tags["WhiteElo"]?.toIntOrNull(),
            blackElo = tags["BlackElo"]?.toIntOrNull()
        )
    }

    fun movesWithFens(pgn: String): List<MoveItem> {
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

    fun fromPgn(pgn: String): PgnParseResult {
        val p = pgn.replace("\r\n", "\n").trim()
        val tmp = File.createTempFile("game_", ".pgn")
        tmp.writeText(p, Charsets.UTF_8)

        val holder = PgnHolder(tmp.absolutePath)
        holder.loadPgn()
        val game: Game = holder.games.firstOrNull()
            ?: return PgnParseResult(emptyList(), emptyList(), null)

        game.loadMoveText()
        val ml: MoveList = game.halfMoves

        val tags = parseTags(p)
        val whiteTitle = tags["WhiteTitle"]
        val blackTitle = tags["BlackTitle"]

        val eco = tags["ECO"]
        val openingName = com.github.movesense.data.OpeningLibrary.getOpeningName(eco, p)
            ?: tags["Opening"]

        val header = GameHeader(
            site = if ((tags["Site"] ?: "").contains("lichess", true)) Provider.LICHESS else null,
            white = if (whiteTitle != null) "$whiteTitle ${tags["White"]}" else tags["White"],
            black = if (blackTitle != null) "$blackTitle ${tags["Black"]}" else tags["Black"],
            result = tags["Result"],
            date = tags["UTCDate"] ?: tags["Date"],
            eco = eco,
            opening = openingName,
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

        fens += board.fen
        for (i in 0 until ml.size) {
            val move: Move = ml[i]
            uci += toUci(move)
            board.doMove(move)
            fens += board.fen
        }

        runCatching { tmp.delete() }

        return PgnParseResult(fens = fens, uciMoves = uci, header = header)
    }

    private fun toUci(move: Move): String {
        val from = move.from.toString().lowercase(Locale.ROOT)
        val to = move.to.toString().lowercase(Locale.ROOT)
        val promo = move.promotion
        val promoChar = when (promo?.pieceType) {
            PieceType.QUEEN -> "q"
            PieceType.ROOK -> "r"
            PieceType.BISHOP -> "b"
            PieceType.KNIGHT -> "n"
            else -> ""
        }
        return from + to + promoChar
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