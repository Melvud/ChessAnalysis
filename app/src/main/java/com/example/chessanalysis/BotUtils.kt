package com.example.chessanalysis

import android.content.Context
import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.Piece
import com.github.bhlangonijr.chesslib.PieceType
import com.github.bhlangonijr.chesslib.move.Move
import com.github.bhlangonijr.chesslib.move.MoveGenerator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap

/** SAN для текущей позиции (не мутируем оригинальную доску) */
fun Board.sanMove(move: Move): String {
    val tmp = Board()
    tmp.loadFromFen(this.fen)
    return tmp.getSanMoveFromMove(move)
}

private fun Board.getSanMoveFromMove(move: Move): String {
    val mover = getPiece(move.from)

    // Рокировка
    if (mover.pieceType == PieceType.KING) {
        val df = move.to.file.ordinal - move.from.file.ordinal
        if (kotlin.math.abs(df) == 2) return if (df > 0) "O-O" else "O-O-O"
    }

    // Было ли взятие (учитываем "на проходе")
    var isCapture = getPiece(move.to) != Piece.NONE
    if (!isCapture && mover.pieceType == PieceType.PAWN) {
        if (move.from.file != move.to.file) isCapture = true
    }

    val sb = StringBuilder()

    when (mover.pieceType) {
        PieceType.PAWN -> {
            // Пешка: при взятии добавляем исходный файл
            if (isCapture) {
                sb.append(move.from.toString()[0].lowercaseChar())
                sb.append('x')
            }
            sb.append(move.to.toString().lowercase())

            // Промоция (добавляем ТОЛЬКО если тип — Q/R/B/N)
            val promoLetter = when (move.promotion?.pieceType) {
                PieceType.QUEEN  -> "Q"
                PieceType.ROOK   -> "R"
                PieceType.BISHOP -> "B"
                PieceType.KNIGHT -> "N"
                else -> null
            }
            if (promoLetter != null) {
                sb.append('=').append(promoLetter)
            }
        }

        else -> {
            // Буква фигуры
            val pieceLetter = when (mover.pieceType) {
                PieceType.KING   -> "K"
                PieceType.QUEEN  -> "Q"
                PieceType.ROOK   -> "R"
                PieceType.BISHOP -> "B"
                PieceType.KNIGHT -> "N"
                else -> ""
            }
            sb.append(pieceLetter)

            // Дисамбигация: если есть другие фигуры того же типа, которые могут прийти на to
            val legal = MoveGenerator.generateLegalMoves(this)
            val same = legal.filter { it.to == move.to && getPiece(it.from) == mover }
            if (same.size > 1) {
                var needsFile = false
                var needsRank = false
                for (o in same) {
                    if (o == move) continue
                    if (o.from.file == move.from.file) needsRank = true
                    if (o.from.rank == move.from.rank) needsFile = true
                }
                // Если и файл, и ранг разные — по стандарту достаточно файла
                if (!needsFile && !needsRank) needsFile = true
                if (needsFile) sb.append(move.from.toString()[0].lowercaseChar())
                if (needsRank) sb.append(move.from.toString()[1])
            }

            if (isCapture) sb.append('x')
            sb.append(move.to.toString().lowercase())
        }
    }

    // Шах/мат после применения хода
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

object BotGamesLocal {
    private const val PREFS_NAME = "bot_games"
    private const val KEY_GAMES = "games_list"

    fun append(context: Context, game: BotGameSave) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
        val list = runCatching {
            prefs.getString(KEY_GAMES, null)?.let { s ->
                json.decodeFromString(ListSerializer(BotGameSave.serializer()), s)
                    .toMutableList()
            }
        }.getOrNull() ?: mutableListOf()
        list.add(game)
        prefs.edit()
            .putString(KEY_GAMES, json.encodeToString(ListSerializer(BotGameSave.serializer()), list))
            .apply()
    }

    fun getAll(context: Context): List<BotGameSave> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
        return runCatching {
            prefs.getString(KEY_GAMES, null)?.let { s ->
                json.decodeFromString(ListSerializer(BotGameSave.serializer()), s)
            } ?: emptyList()
        }.getOrElse { emptyList() }
    }
}

object PgnChess_bot {
    fun sanListToPgn(moves: List<String>): String {
        val sb = StringBuilder()
        moves.forEachIndexed { idx, san ->
            if (idx % 2 == 0) {
                if (idx > 0) sb.append(' ')
                sb.append("${(idx / 2) + 1}. ")
            } else {
                sb.append(' ')
            }
            // подстрахуемся от возможных "…="
            sb.append(san.removeSuffix("="))
        }
        return sb.toString()
    }

    fun headerFromPgn(pgn: String): GameHeader {
        val tags = parseTags(pgn)
        val siteTag = (tags["Site"] ?: "").lowercase()
        val provider = when {
            "lichess.org" in siteTag -> Provider.LICHESS
            "chess.com" in siteTag   -> Provider.CHESSCOM
            "local" in siteTag       -> Provider.BOT
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

/* ===================== ПРОГРЕСС И КЭШ ОТЧЁТОВ ===================== */

// Глобальные стейты прогресса, к которым обращается AppRoot
val percent: MutableStateFlow<Double?> = MutableStateFlow(null)
val stage: MutableStateFlow<String?> = MutableStateFlow(null)

/** Обновление глобального прогресса (зови из onProgress лямбды) */
fun postProgress(snap: EngineClient.ProgressSnapshot) {
    percent.value = snap.percent
    stage.value = snap.stage
}

/**
 * Совместимая подписка, как ожидает AppRoot:
 * observeProgress({ p -> ... }, { s -> ... })
 */
fun observeProgress(
    onPercent: (Double?) -> Unit,
    onStage: (String?) -> Unit,
    scope: CoroutineScope = CoroutineScope(Dispatchers.IO)
) {
    // сразу отдадим текущее
    onPercent(percent.value)
    onStage(stage.value)

    // и дальше пушим обновления
    scope.launch { percent.collectLatest { onPercent(it) } }
    scope.launch { stage.collectLatest { onStage(it) } }
}

// Простой in-memory кэш для отчётов анализа
private val reportCache = ConcurrentHashMap<String, FullReport>()

fun getReportFromCache(pgn: String): FullReport? = reportCache[pgn]

fun saveReportToCache(pgn: String, report: FullReport) {
    reportCache[pgn] = report
}
