package com.example.chessanalysis.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.rememberAsyncImagePainter
import coil.decode.SvgDecoder
import coil.request.ImageRequest
import com.example.chessanalysis.LineEval
import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.Piece
import com.github.bhlangonijr.chesslib.PieceType
import com.github.bhlangonijr.chesslib.Side
import com.github.bhlangonijr.chesslib.Square
import com.github.bhlangonijr.chesslib.move.Move
import com.github.bhlangonijr.chesslib.move.MoveGenerator
import kotlin.math.abs

// Вспомогательный токен для отображения PV-строки
private data class BotPvToken(
    val iconAsset: String,
    val toSquare: String,
    val capture: Boolean,
    val promoSuffix: String = ""
)

private fun botPieceAssetName(p: Piece): String {
    val pref = if (p.pieceSide == Side.WHITE) "w" else "b"
    val name = when (p.pieceType) {
        PieceType.KING   -> "K"
        PieceType.QUEEN  -> "Q"
        PieceType.ROOK   -> "R"
        PieceType.BISHOP -> "B"
        PieceType.KNIGHT -> "N"
        else -> "P"
    }
    return "$pref$name.svg"
}

@Composable
private fun BotPieceAssetIcon(name: String, size: Dp) {
    val ctx = LocalContext.current
    val painter = rememberAsyncImagePainter(
        ImageRequest.Builder(ctx)
            .data("file:///android_asset/fresca/$name")
            .decoderFactory(SvgDecoder.Factory())
            .build()
    )
    Image(
        painter = painter,
        contentDescription = null,
        modifier = Modifier.size(size)
    )
}

/** Поиск легального хода с поддержкой промоций */
private fun botFindLegalMove(board: Board, uci: String): Move? {
    if (uci.length < 4) return null
    val from = Square.fromValue(uci.substring(0, 2).uppercase())
    val to   = Square.fromValue(uci.substring(2, 4).uppercase())
    val promoChar = if (uci.length > 4) uci[4].lowercaseChar() else null
    val legal = MoveGenerator.generateLegalMoves(board)
    return legal.firstOrNull { m ->
        m.from == from && m.to == to &&
                (promoChar == null || when (m.promotion?.pieceType) {
                    PieceType.QUEEN  -> promoChar == 'q'
                    PieceType.ROOK   -> promoChar == 'r'
                    PieceType.BISHOP -> promoChar == 'b'
                    PieceType.KNIGHT -> promoChar == 'n'
                    null -> false
                    else -> false
                })
    } ?: legal.firstOrNull { it.from == from && it.to == to }
}

/** Построение токенов для строки PV с иконками */
private fun botBuildIconTokens(fen: String, pv: List<String>): List<BotPvToken> {
    val b = Board().apply { loadFromFen(fen) }
    val out = mutableListOf<BotPvToken>()
    for (uci in pv) {
        val legal = botFindLegalMove(b, uci) ?: break
        val mover   = b.getPiece(legal.from)
        val dst     = b.getPiece(legal.to)
        val capture = dst != Piece.NONE ||
                (mover.pieceType == PieceType.PAWN && legal.from.file != legal.to.file)
        val promoSuffix = when (legal.promotion?.pieceType) {
            PieceType.QUEEN  -> "=Q"
            PieceType.ROOK   -> "=R"
            PieceType.BISHOP -> "=B"
            PieceType.KNIGHT -> "=N"
            else -> ""
        }
        out += BotPvToken(
            iconAsset = botPieceAssetName(mover),
            toSquare  = legal.to.toString().lowercase(),
            capture   = capture,
            promoSuffix = promoSuffix
        )
        b.doMove(legal)
    }
    return out
}

/** Чип с оценкой (+1.53 / M…) */
@Composable
private fun BotEvalChip(line: LineEval, modifier: Modifier = Modifier) {
    val txt = when {
        line.mate != null -> if (line.mate!! > 0) "M${abs(line.mate!!)}" else "M-${abs(line.mate!!)}"
        line.cp != null   -> String.format("%+,.2f", line.cp!! / 100f)
        else -> "—"
    }
    Box(
        modifier = modifier
            .background(Color(0xFF2F2F2F), RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(txt, color = Color.White, fontSize = 16.sp)
    }
}

/** Одна строка PV: чип с оценкой + ряд иконок-ходов */
@Composable
private fun BotPvRow(
    baseFen: String,
    line: LineEval,
    onClickMoveAtIndex: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val tokens = remember(baseFen, line.pv) { botBuildIconTokens(baseFen, line.pv) }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        BotEvalChip(line)
        Spacer(Modifier.width(10.dp))
        Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
            tokens.forEachIndexed { i, t ->
                Row(
                    modifier = Modifier
                        .clickable { onClickMoveAtIndex(i) }
                        .padding(end = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    BotPieceAssetIcon(t.iconAsset, 20.dp)
                    Spacer(Modifier.width(4.dp))
                    val suffix = buildString {
                        append(t.toSquare)
                        if (t.capture) append("x")
                        append(t.promoSuffix)
                    }
                    Text(suffix, color = Color.White.copy(alpha = 0.9f), fontSize = 16.sp)
                }
            }
        }
    }
}

/** Панель из трёх линий */
@Composable
fun BotEnginePvPanel(
    baseFen: String,
    lines: List<LineEval>,
    onClickMoveInLine: ((Int, Int) -> Unit)?,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xFF1E1C1A))
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        lines.take(3).forEachIndexed { li, line ->
            BotPvRow(
                baseFen = baseFen,
                line = line,
                onClickMoveAtIndex = { mi -> onClickMoveInLine?.invoke(li, mi) }
            )
        }
    }
}
