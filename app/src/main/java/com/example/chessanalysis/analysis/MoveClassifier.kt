package com.example.chessanalysis.analysis

import com.example.chessanalysis.LineEval
import com.example.chessanalysis.MoveClass
import com.example.chessanalysis.PositionEval
import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.Piece
import com.github.bhlangonijr.chesslib.Square
import com.github.bhlangonijr.chesslib.move.Move
import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString

object MoveClassification {

    data class ClassifiedPosition(
        val fen: String,
        val idx: Int,
        val lines: List<LineEval>,
        val bestMove: String? = null,
        val opening: String? = null,
        val moveClassification: MoveClass? = null
    )

    fun getMovesClassification(
        rawPositions: List<PositionEval>,
        uciMoves: List<String>,
        fens: List<String>
    ): List<ClassifiedPosition> {
        val positionsWinPercentage = rawPositions.map {
            WinPercentage.getPositionWinPercentage(it)
        }
        var currentOpening: String? = null

        return rawPositions.mapIndexed { index, rawPosition ->
            if (index == 0) {
                return@mapIndexed ClassifiedPosition(
                    fen = rawPosition.fen,
                    idx = rawPosition.idx,
                    lines = rawPosition.lines,
                    bestMove = rawPosition.bestMove
                )
            }

            val currentFen = fens[index].split(" ")[0]
            val opening = Openings.findOpening(currentFen)

            if (opening != null) {
                currentOpening = opening
                return@mapIndexed ClassifiedPosition(
                    fen = rawPosition.fen,
                    idx = rawPosition.idx,
                    lines = rawPosition.lines,
                    bestMove = rawPosition.bestMove,
                    opening = opening,
                    moveClassification = MoveClass.OPENING
                )
            }

            val prevPosition = rawPositions[index - 1]

            if (prevPosition.lines.size == 1) {
                return@mapIndexed ClassifiedPosition(
                    fen = rawPosition.fen,
                    idx = rawPosition.idx,
                    lines = rawPosition.lines,
                    bestMove = rawPosition.bestMove,
                    opening = currentOpening,
                    moveClassification = MoveClass.FORCED
                )
            }

            val playedMove = uciMoves[index - 1]

            val lastPositionAlternativeLine: LineEval? = prevPosition.lines
                .firstOrNull { it.pv.firstOrNull() != playedMove }

            val lastPositionAlternativeLineWinPercentage = lastPositionAlternativeLine?.let {
                WinPercentage.getLineWinPercentage(it)
            }

            // КРИТИЧНО: PV берется из позиции ПОСЛЕ хода (как на сервере!)
            val bestLinePvToPlay = rawPosition.lines[0].pv

            val lastPositionWinPercentage = positionsWinPercentage[index - 1]
            val positionWinPercentage = positionsWinPercentage[index]

            // ФИКС: определяем, кто сделал ход, по FEN позиции ДО хода
            // fens[index-1] -> "<board> <side> ..."
            val prevFenParts = fens[index - 1].split(" ")
            val moverIsWhite = prevFenParts.getOrNull(1) == "w"
            val isWhiteMove = moverIsWhite

            if (isSplendidMove(
                    lastPositionWinPercentage,
                    positionWinPercentage,
                    isWhiteMove,
                    playedMove,
                    bestLinePvToPlay,  // PV из rawPosition (позиция ПОСЛЕ)
                    fens[index - 1],   // FEN позиции ДО хода
                    lastPositionAlternativeLineWinPercentage
                )
            ) {
                return@mapIndexed ClassifiedPosition(
                    fen = rawPosition.fen,
                    idx = rawPosition.idx,
                    lines = rawPosition.lines,
                    bestMove = rawPosition.bestMove,
                    opening = currentOpening,
                    moveClassification = MoveClass.SPLENDID
                )
            }

            val fenTwoMovesAgo = if (index > 1) fens[index - 2] else null
            val uciNextTwoMoves: Pair<String, String>? = if (index > 1) {
                Pair(uciMoves[index - 2], uciMoves[index - 1])
            } else null

            if (isPerfectMove(
                    lastPositionWinPercentage,
                    positionWinPercentage,
                    isWhiteMove,
                    lastPositionAlternativeLineWinPercentage,
                    fenTwoMovesAgo,
                    uciNextTwoMoves
                )
            ) {
                return@mapIndexed ClassifiedPosition(
                    fen = rawPosition.fen,
                    idx = rawPosition.idx,
                    lines = rawPosition.lines,
                    bestMove = rawPosition.bestMove,
                    opening = currentOpening,
                    moveClassification = MoveClass.PERFECT
                )
            }

            if (playedMove == prevPosition.bestMove) {
                return@mapIndexed ClassifiedPosition(
                    fen = rawPosition.fen,
                    idx = rawPosition.idx,
                    lines = rawPosition.lines,
                    bestMove = rawPosition.bestMove,
                    opening = currentOpening,
                    moveClassification = MoveClass.BEST
                )
            }

            val moveClassification = getMoveBasicClassification(
                lastPositionWinPercentage,
                positionWinPercentage,
                isWhiteMove
            )

            ClassifiedPosition(
                fen = rawPosition.fen,
                idx = rawPosition.idx,
                lines = rawPosition.lines,
                bestMove = rawPosition.bestMove,
                opening = currentOpening,
                moveClassification = moveClassification
            )
        }
    }

    private fun getMoveBasicClassification(
        lastPositionWinPercentage: Double,
        positionWinPercentage: Double,
        isWhiteMove: Boolean
    ): MoveClass {
        val winPercentageDiff = (positionWinPercentage - lastPositionWinPercentage) *
                (if (isWhiteMove) 1 else -1)

        return when {
            winPercentageDiff < -20 -> MoveClass.BLUNDER
            winPercentageDiff < -10 -> MoveClass.MISTAKE
            winPercentageDiff < -5 -> MoveClass.INACCURACY
            winPercentageDiff < -2 -> MoveClass.OKAY
            else -> MoveClass.EXCELLENT
        }
    }

    private fun isSplendidMove(
        lastPositionWinPercentage: Double,
        positionWinPercentage: Double,
        isWhiteMove: Boolean,
        playedMove: String,
        bestLinePvToPlay: List<String>,  // PV из позиции ПОСЛЕ хода
        fen: String,                      // FEN позиции ДО хода
        lastPositionAlternativeLineWinPercentage: Double?
    ): Boolean {
        if (lastPositionAlternativeLineWinPercentage == null) return false

        val winPercentageDiff = (positionWinPercentage - lastPositionWinPercentage) *
                (if (isWhiteMove) 1 else -1)
        if (winPercentageDiff < -2) return false

        val isPieceSacrifice = getIsPieceSacrifice(fen, playedMove, bestLinePvToPlay)
        if (!isPieceSacrifice) return false

        if (isLosingOrAlternateCompletelyWinning(
                positionWinPercentage,
                lastPositionAlternativeLineWinPercentage,
                isWhiteMove
            )
        ) {
            return false
        }

        return true
    }

    private fun isLosingOrAlternateCompletelyWinning(
        positionWinPercentage: Double,
        lastPositionAlternativeLineWinPercentage: Double,
        isWhiteMove: Boolean
    ): Boolean {
        val isLosing = if (isWhiteMove) {
            positionWinPercentage < 50
        } else {
            positionWinPercentage > 50
        }

        val isAlternateCompletelyWinning = if (isWhiteMove) {
            lastPositionAlternativeLineWinPercentage > 97
        } else {
            lastPositionAlternativeLineWinPercentage < 3
        }

        return isLosing || isAlternateCompletelyWinning
    }

    private fun isPerfectMove(
        lastPositionWinPercentage: Double,
        positionWinPercentage: Double,
        isWhiteMove: Boolean,
        lastPositionAlternativeLineWinPercentage: Double?,
        fenTwoMovesAgo: String?,
        uciMoves: Pair<String, String>?
    ): Boolean {
        if (lastPositionAlternativeLineWinPercentage == null) return false

        val winPercentageDiff = (positionWinPercentage - lastPositionWinPercentage) *
                (if (isWhiteMove) 1 else -1)
        if (winPercentageDiff < -2) return false

        if (fenTwoMovesAgo != null && uciMoves != null &&
            isSimplePieceRecapture(fenTwoMovesAgo, uciMoves)
        ) {
            return false
        }

        if (isLosingOrAlternateCompletelyWinning(
                positionWinPercentage,
                lastPositionAlternativeLineWinPercentage,
                isWhiteMove
            )
        ) {
            return false
        }

        val hasChangedGameOutcome = getHasChangedGameOutcome(
            lastPositionWinPercentage,
            positionWinPercentage,
            isWhiteMove
        )

        val isTheOnlyGoodMove = getIsTheOnlyGoodMove(
            positionWinPercentage,
            lastPositionAlternativeLineWinPercentage,
            isWhiteMove
        )

        return hasChangedGameOutcome || isTheOnlyGoodMove
    }

    private fun getHasChangedGameOutcome(
        lastPositionWinPercentage: Double,
        positionWinPercentage: Double,
        isWhiteMove: Boolean
    ): Boolean {
        val winPercentageDiff = (positionWinPercentage - lastPositionWinPercentage) *
                (if (isWhiteMove) 1 else -1)
        return winPercentageDiff > 10 &&
                ((lastPositionWinPercentage < 50 && positionWinPercentage > 50) ||
                        (lastPositionWinPercentage > 50 && positionWinPercentage < 50))
    }

    private fun getIsTheOnlyGoodMove(
        positionWinPercentage: Double,
        lastPositionAlternativeLineWinPercentage: Double,
        isWhiteMove: Boolean
    ): Boolean {
        val winPercentageDiff = (positionWinPercentage - lastPositionAlternativeLineWinPercentage) *
                (if (isWhiteMove) 1 else -1)
        return winPercentageDiff > 10
    }

    private fun getIsPieceSacrifice(
        fen: String,
        playedMove: String,
        bestLinePvToPlay: List<String>
    ): Boolean {
        try {
            val board = Board()
            board.loadFromFen(fen)

            val from = Square.fromValue(playedMove.substring(0, 2).uppercase())
            val to = Square.fromValue(playedMove.substring(2, 4).uppercase())

            val move = Move(from, to)
            val movingPiece = board.getPiece(from)
            val capturedPiece = board.getPiece(to)

            // Не жертва если берем фигуру
            if (capturedPiece != Piece.NONE) return false

            // Пешки не считаются жертвой
            if (movingPiece == Piece.WHITE_PAWN || movingPiece == Piece.BLACK_PAWN) {
                return false
            }

            // Делаем ход
            board.doMove(move)

            // Смотрим, есть ли реальные атакующие на нашу фигуру после хода
            val attackedBy: Any? = board.squareAttackedBy(to, board.sideToMove)
            val hasAttackers = when (attackedBy) {
                null -> false
                is Collection<*> -> attackedBy.isNotEmpty()
                is Array<*> -> attackedBy.isNotEmpty()
                is Long -> attackedBy != 0L
                is Int -> attackedBy != 0
                else -> true
            }
            if (!hasAttackers) return false

            // bestLinePvToPlay[0] - это лучший ответ противника (первый ход в PV)
            if (bestLinePvToPlay.isNotEmpty()) {
                val opponentResponse = bestLinePvToPlay[0]
                val responseTo = Square.fromValue(opponentResponse.substring(2, 4).uppercase())

                // Если противник берет нашу фигуру
                if (responseTo == to) {
                    val responseFrom = Square.fromValue(opponentResponse.substring(0, 2).uppercase())
                    val respondingPiece = board.getPiece(responseFrom)

                    val movingValue = getPieceValue(movingPiece)
                    val respondingValue = getPieceValue(respondingPiece)

                    // Если можно забрать равноценной или меньшей фигурой - не жертва
                    if (respondingValue <= movingValue) return false
                }
            }

            return true
        } catch (e: Exception) {
            return false
        }
    }

    private fun isSimplePieceRecapture(
        fenTwoMovesAgo: String,
        uciMoves: Pair<String, String>
    ): Boolean {
        try {
            val board = Board()
            board.loadFromFen(fenTwoMovesAgo)

            val (firstMove, secondMove) = uciMoves

            val firstFrom = Square.fromValue(firstMove.substring(0, 2).uppercase())
            val firstTo = Square.fromValue(firstMove.substring(2, 4).uppercase())

            val secondFrom = Square.fromValue(secondMove.substring(0, 2).uppercase())
            val secondTo = Square.fromValue(secondMove.substring(2, 4).uppercase())

            // Второй ход должен идти на поле первого хода
            if (secondTo != firstTo) return false

            val firstMovingPiece = board.getPiece(firstFrom)
            val capturedPiece = board.getPiece(firstTo)

            // Первый ход должен брать фигуру
            if (capturedPiece == Piece.NONE) return false

            board.doMove(Move(firstFrom, firstTo))

            val secondMovingPiece = board.getPiece(secondFrom)

            val firstValue = getPieceValue(firstMovingPiece)
            val capturedValue = getPieceValue(capturedPiece)
            val secondValue = getPieceValue(secondMovingPiece)

            // Простой размен если ценности примерно равны
            return kotlin.math.abs(firstValue - secondValue) <= 1 &&
                    kotlin.math.abs(capturedValue - secondValue) <= 1
        } catch (e: Exception) {
            return false
        }
    }

    private fun getPieceValue(piece: Piece): Int {
        return when (piece) {
            Piece.WHITE_PAWN, Piece.BLACK_PAWN -> 1
            Piece.WHITE_KNIGHT, Piece.BLACK_KNIGHT -> 3
            Piece.WHITE_BISHOP, Piece.BLACK_BISHOP -> 3
            Piece.WHITE_ROOK, Piece.BLACK_ROOK -> 5
            Piece.WHITE_QUEEN, Piece.BLACK_QUEEN -> 9
            Piece.WHITE_KING, Piece.BLACK_KING -> 0
            else -> 0
        }
    }
}

@Serializable
data class Opening(
    val name: String,
    val fen: String
)

object Openings {
    private var openingsCache: List<Opening>? = null

    fun init(context: Context) {
        if (openingsCache == null) {
            try {
                val jsonString = context.assets.open("openings.json")
                    .bufferedReader()
                    .use { it.readText() }

                openingsCache = Json.decodeFromString<List<Opening>>(jsonString)
            } catch (e: Exception) {
                e.printStackTrace()
                openingsCache = emptyList()
            }
        }
    }

    fun findOpening(fen: String): String? {
        val fenBoard = fen.split(" ")[0]
        return openingsCache?.firstOrNull { it.fen == fenBoard }?.name
    }
}
