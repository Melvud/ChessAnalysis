package com.example.chessanalysis

import android.content.Context
import com.example.chessanalysis.ui.screens.BotConfig
import com.example.chessanalysis.ui.screens.BotSide
import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.Side
import com.github.bhlangonijr.chesslib.move.Move
import com.github.bhlangonijr.chesslib.move.MoveGenerator
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

// Вспомогательные функции для бота
fun Board.sanMove(move: Move): String {
    // Создаем временную доску и копируем позицию
    val tempBoard = Board()
    tempBoard.loadFromFen(this.fen)
    return tempBoard.getSanMoveFromMove(move)
}

private fun Board.getSanMoveFromMove(move: Move): String {
    val piece = getPiece(move.from)
    val capture = getPiece(move.to) != com.github.bhlangonijr.chesslib.Piece.NONE

    val sanBuilder = StringBuilder()

    when (piece.pieceType) {
        com.github.bhlangonijr.chesslib.PieceType.PAWN -> {
            if (capture) {
                sanBuilder.append(move.from.toString()[0].lowercase())
                sanBuilder.append("x")
            }
            sanBuilder.append(move.to.toString().lowercase())
            move.promotion?.let { promo ->
                sanBuilder.append("=")
                sanBuilder.append(when (promo.pieceType) {
                    com.github.bhlangonijr.chesslib.PieceType.QUEEN -> "Q"
                    com.github.bhlangonijr.chesslib.PieceType.ROOK -> "R"
                    com.github.bhlangonijr.chesslib.PieceType.BISHOP -> "B"
                    com.github.bhlangonijr.chesslib.PieceType.KNIGHT -> "N"
                    else -> ""
                })
            }
        }
        else -> {
            sanBuilder.append(when (piece.pieceType) {
                com.github.bhlangonijr.chesslib.PieceType.KING -> "K"
                com.github.bhlangonijr.chesslib.PieceType.QUEEN -> "Q"
                com.github.bhlangonijr.chesslib.PieceType.ROOK -> "R"
                com.github.bhlangonijr.chesslib.PieceType.BISHOP -> "B"
                com.github.bhlangonijr.chesslib.PieceType.KNIGHT -> "N"
                else -> ""
            })
            if (capture) sanBuilder.append("x")
            sanBuilder.append(move.to.toString().lowercase())
        }
    }

    // Проверяем на шах или мат после хода
    doMove(move)
    when {
        isMated -> sanBuilder.append("#")
        isKingAttacked -> sanBuilder.append("+")
    }
    // Откатываем ход обратно
    undoMove()

    return sanBuilder.toString()
}

@Serializable
data class BotGameSave(
    val pgn: String,
    val white: String,
    val black: String,
    val result: String,
    val dateIso: String
)

@Serializable
data class BotFinishResult(
    val stored: BotGameSave,
    val report: FullReport
)

// Простое хранилище для игр с ботом
object BotGamesLocal {
    private const val PREFS_NAME = "bot_games"
    private const val KEY_GAMES = "games_list"

    fun append(context: Context, game: BotGameSave) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = Json { ignoreUnknownKeys = true }

        val existing = prefs.getString(KEY_GAMES, null)
        val games = if (existing != null) {
            try {
                json.decodeFromString<List<BotGameSave>>(existing).toMutableList()
            } catch (e: Exception) {
                mutableListOf()
            }
        } else {
            mutableListOf()
        }

        games.add(game)

        prefs.edit()
            .putString(KEY_GAMES, json.encodeToString(games))
            .apply()
    }

    fun getAll(context: Context): List<BotGameSave> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = Json { ignoreUnknownKeys = true }

        val existing = prefs.getString(KEY_GAMES, null) ?: return emptyList()

        return try {
            json.decodeFromString<List<BotGameSave>>(existing)
        } catch (e: Exception) {
            emptyList()
        }
    }
}
object PgnChess_bot {
    fun sanListToPgn(moves: List<String>): String {
        val sb = StringBuilder()
        moves.forEachIndexed { index, san ->
            if (index % 2 == 0) {
                if (index > 0) sb.append(" ")
                sb.append("${(index / 2) + 1}. ")
            } else {
                sb.append(" ")
            }
            sb.append(san)
        }
        return sb.toString()
    }

    fun headerFromPgn(pgn: String): GameHeader {
        val tags = parseTags(pgn)
        val siteTag = (tags["Site"] ?: "").lowercase()

        val provider = when {
            "lichess.org" in siteTag -> Provider.LICHESS
            "chess.com" in siteTag -> Provider.CHESSCOM
            "local" in siteTag -> Provider.BOT
            else -> null
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

    private fun parseTags(pgn: String): Map<String, String> {
        val rx = Regex("""\[(\w+)\s+"([^"]*)"\]""")
        return rx.findAll(pgn).associate { it.groupValues[1] to it.groupValues[2] }
    }
}