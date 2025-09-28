package com.example.chessanalysis

import android.content.Context
import com.example.chessanalysis.ui.screens.bot.BotConfig
import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.Piece
import com.github.bhlangonijr.chesslib.PieceType
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

    // Обработка рокировки: король делает ход на две клетки – короткая/длинная
    if (piece.pieceType == PieceType.KING) {
        val fromFile = move.from.file.ordinal
        val toFile   = move.to.file.ordinal
        if (kotlin.math.abs(toFile - fromFile) == 2) {
            return if (toFile > fromFile) "O-O" else "O-O-O"
        }
    }

    // Определяем факт взятия. Для пешек учитываем взятие «на проходе» –
    // когда целевая клетка пуста, но колонка меняется
    var capture = getPiece(move.to) != Piece.NONE
    if (!capture && piece.pieceType == PieceType.PAWN) {
        if (move.from.file != move.to.file) {
            capture = true
        }
    }

    val sb = StringBuilder()

    when (piece.pieceType) {
        PieceType.PAWN -> {
            // Для пешек записываем букву столбца при взятии
            if (capture) {
                sb.append(move.from.toString()[0].lowercaseChar())
                sb.append('x')
            }
            // Клетка назначения
            sb.append(move.to.toString().lowercase())
            // Промоция, если есть
            move.promotion?.let { promo ->
                sb.append('=')
                sb.append(
                    when (promo.pieceType) {
                        PieceType.QUEEN  -> "Q"
                        PieceType.ROOK   -> "R"
                        PieceType.BISHOP -> "B"
                        PieceType.KNIGHT -> "N"
                        else -> ""
                    }
                )
            }
        }
        else -> {
            // Буква фигуры
            sb.append(
                when (piece.pieceType) {
                    PieceType.KING   -> "K"
                    PieceType.QUEEN  -> "Q"
                    PieceType.ROOK   -> "R"
                    PieceType.BISHOP -> "B"
                    PieceType.KNIGHT -> "N"
                    else             -> ""
                }
            )

            // Уточнение исходной клетки, если несколько фигур того же типа могут пойти на тот же квадрат
            val legalMoves = MoveGenerator.generateLegalMoves(this)
            val sameDestinations = legalMoves.filter { lm ->
                lm.to == move.to && getPiece(lm.from) == piece
            }
            if (sameDestinations.size > 1) {
                var needsFile = false
                var needsRank = false
                for (other in sameDestinations) {
                    if (other == move) continue
                    // Одинаковая горизонталь -> указываем файл
                    if (other.from.rank == move.from.rank) needsFile = true
                    // Одинаковый файл -> указываем ранг
                    if (other.from.file == move.from.file) needsRank = true
                }
                if (needsFile) {
                    sb.append(move.from.toString()[0].lowercaseChar())
                }
                if (needsRank) {
                    sb.append(move.from.toString()[1])
                }
            }

            // Захват
            if (capture) {
                sb.append('x')
            }

            // Клетка назначения
            sb.append(move.to.toString().lowercase())
        }
    }

    // Проверяем шах/мат после хода
    doMove(move)
    when {
        isMated -> sb.append('#')
        isKingAttacked -> sb.append('+')
    }
    undoMove()

    return sb.toString()
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
            json.decodeFromString(existing)
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
