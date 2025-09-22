package com.example.chessanalysis.data.util

import kotlin.math.abs
import kotlin.math.sign

class BoardState private constructor(
    private val cells: Array<CharArray>,
    private var whiteToMove: Boolean,
    private var canCastleWK: Boolean,
    private var canCastleWQ: Boolean,
    private var canCastleBK: Boolean,
    private var canCastleBQ: Boolean,
    private var enPassantTarget: Pair<Int, Int>?,
    private var halfmoveClock: Int,
    private var fullmoveNumber: Int
) {

    companion object {
        fun initial(): BoardState {
            val b = Array(8) { CharArray(8) { '.' } }
            b[0] = charArrayOf('R','N','B','Q','K','B','N','R')
            b[1] = charArrayOf('P','P','P','P','P','P','P','P')
            b[6] = charArrayOf('p','p','p','p','p','p','p','p')
            b[7] = charArrayOf('r','n','b','q','k','b','n','r')
            return BoardState(
                b, true,
                canCastleWK = true, canCastleWQ = true,
                canCastleBK = true, canCastleBQ = true,
                enPassantTarget = null,
                halfmoveClock = 0,
                fullmoveNumber = 1
            )
        }
    }

    fun copy(): BoardState {
        val cp = Array(8) { r -> CharArray(8) { c -> cells[r][c] } }
        return BoardState(
            cp, whiteToMove,
            canCastleWK, canCastleWQ, canCastleBK, canCastleBQ,
            enPassantTarget, halfmoveClock, fullmoveNumber
        )
    }

    fun at(rank: Int, file: Int): Char = cells[rank][file]

    private fun isWhitePiece(ch: Char) = ch in 'A'..'Z'
    private fun isBlackPiece(ch: Char) = ch in 'a'..'z'
    private fun isEmptyCell(rank: Int, file: Int) = cells[rank][file] == '.'
    private fun sameColor(ch: Char, white: Boolean) =
        if (white) isWhitePiece(ch) else isBlackPiece(ch)

    private fun sideChar(white: Boolean, type: Char): Char =
        if (white) type else type.lowercaseChar()

    private fun squareToCoords(sq: String): Pair<Int, Int> {
        val f = sq[0] - 'a'
        val r = sq[1] - '1'
        require(f in 0..7 && r in 0..7) { "Bad square: $sq" }
        return r to f
    }

    // ==== ФИКС: правильная конверсия в строку клетки ====
    private fun coordsToSquare(r: Int, f: Int): String {
        val fileChar = ('a'.code + f).toChar()
        val rankChar = ('1'.code + r).toChar()
        return "$fileChar$rankChar"
    }

    private fun clearEP() { enPassantTarget = null }

    fun applySan(san: String, isWhiteMove: Boolean) {
        check(isWhiteMove == whiteToMove) { "SAN side mismatch" }
        applySan(san)
    }

    fun applySan(san: String) {
        var s = san.trim()
        s = s.replace(Regex("[+#]+$"), "")
            .replace("e.p.", "", ignoreCase = true)
            .replace(Regex("[!?]+$"), "")
            .replace(Regex("\\$\\d+$"), "")

        if (s == "O-O" || s == "0-0") {
            doCastle(kingside = true)
            postMoveHousekeeping(pawnMove = false, capture = false)
            return
        }
        if (s == "O-O-O" || s == "0-0-0") {
            doCastle(kingside = false)
            postMoveHousekeeping(pawnMove = false, capture = false)
            return
        }

        var promo: Char? = null
        Regex("=([QRBN])$").find(s)?.let {
            promo = it.groupValues[1][0]; s = s.removeSuffix("=${promo}")
        } ?: run {
            val last = s.last()
            if (last in listOf('Q','R','B','N')) { promo = last; s = s.dropLast(1) }
        }

        val isCapture = 'x' in s
        val movingType = if (s.first() in listOf('K','Q','R','B','N')) s.first() else 'P'
        val rest = if (movingType == 'P') s else s.drop(1)

        require(rest.length >= 2) { "Bad SAN: $san" }
        val target = rest.takeLast(2)
        val (dr, df) = squareToCoords(target)

        val disambig = rest.dropLast(2).replace("x", "")
        var disFile: Int? = null
        var disRank: Int? = null
        if (disambig.isNotEmpty()) {
            Regex("[a-h]").find(disambig)?.let { disFile = it.value[0] - 'a' }
            Regex("[1-8]").find(disambig)?.let { disRank = it.value[0] - '1' }
        }

        val white = whiteToMove
        val src = findSource(movingType, white, dr, df, isCapture, disRank, disFile, promo != null)
            ?: error("No source for SAN '$san'")

        var epCapture = false
        if (movingType == 'P' && isCapture && isEmptyCell(dr, df)) {
            val dir = if (white) -1 else +1
            require(enPassantTarget != null && enPassantTarget == (dr to df)) { "Illegal e.p. in '$san'" }
            cells[dr + dir][df] = '.'
            epCapture = true
        }

        val capturedBefore = cells[dr][df]
        val movingChar = cells[src.first][src.second]
        require(sameColor(movingChar, white)) { "Wrong color at source" }
        cells[src.first][src.second] = '.'
        cells[dr][df] = movingChar

        if (promo != null) {
            require(movingType == 'P') { "Promotion only for pawn" }
            cells[dr][df] = if (white) promo!! else promo!!.lowercaseChar()
        }

        onRookKingRightsUpdate(src.first to src.second, dr to df, movingChar, capturedBefore)

        if (movingType == 'P' && abs(dr - src.first) == 2 && df == src.second) {
            enPassantTarget = (src.first + (dr - src.first) / 2) to df
        } else {
            clearEP()
        }

        postMoveHousekeeping(pawnMove = movingType == 'P', capture = isCapture && (!epCapture || capturedBefore != '.'))
    }

    /** FEN генерация */
    fun toFEN(): String {
        val pieces = buildString {
            for (r in 7 downTo 0) {
                var run = 0
                for (f in 0..7) {
                    val ch = cells[r][f]
                    if (ch == '.') run++ else {
                        if (run > 0) { append(run); run = 0 }
                        append(ch)
                    }
                }
                if (run > 0) append(run)
                if (r > 0) append('/')
            }
        }
        val stm = if (whiteToMove) "w" else "b"

        val castlingCodes = buildString {
            if (canCastleWK) append('K')
            if (canCastleWQ) append('Q')
            if (canCastleBK) append('k')
            if (canCastleBQ) append('q')
        }
        val castling = if (castlingCodes.isEmpty()) "-" else castlingCodes

        val ep = enPassantTarget?.let { coordsToSquare(it.first, it.second) } ?: "-"

        return "$pieces $stm $castling $ep $halfmoveClock $fullmoveNumber"
    }

    private fun postMoveHousekeeping(pawnMove: Boolean, capture: Boolean) {
        halfmoveClock = if (pawnMove || capture) 0 else (halfmoveClock + 1)
        whiteToMove = !whiteToMove
        if (whiteToMove) fullmoveNumber += 1
    }

    private fun doCastle(kingside: Boolean) {
        if (whiteToMove) {
            require(cells[0][4] == 'K') { "No white king on e1" }
            if (kingside) {
                require(cells[0][7] == 'R') { "No white rook on h1" }
                cells[0][4] = '.'
                cells[0][7] = '.'
                cells[0][6] = 'K'
                cells[0][5] = 'R'
            } else {
                require(cells[0][0] == 'R') { "No white rook on a1" }
                cells[0][4] = '.'
                cells[0][0] = '.'
                cells[0][2] = 'K'
                cells[0][3] = 'R'
            }
            canCastleWK = false; canCastleWQ = false
        } else {
            require(cells[7][4] == 'k') { "No black king on e8" }
            if (kingside) {
                require(cells[7][7] == 'r') { "No black rook on h8" }
                cells[7][4] = '.'
                cells[7][7] = '.'
                cells[7][6] = 'k'
                cells[7][5] = 'r'
            } else {
                require(cells[7][0] == 'r') { "No black rook on a8" }
                cells[7][4] = '.'
                cells[7][0] = '.'
                cells[7][2] = 'k'
                cells[7][3] = 'r'
            }
            canCastleBK = false; canCastleBQ = false
        }
        clearEP()
    }

    private fun onRookKingRightsUpdate(
        src: Pair<Int,Int>,
        dst: Pair<Int,Int>,
        moving: Char,
        capturedBefore: Char
    ) {
        if (moving == 'K') { canCastleWK = false; canCastleWQ = false }
        if (moving == 'k') { canCastleBK = false; canCastleBQ = false }
        if (moving == 'R') {
            if (src.first == 0 && src.second == 0) canCastleWQ = false
            if (src.first == 0 && src.second == 7) canCastleWK = false
        }
        if (moving == 'r') {
            if (src.first == 7 && src.second == 0) canCastleBQ = false
            if (src.first == 7 && src.second == 7) canCastleBK = false
        }
        if (capturedBefore == 'R' && dst.first == 0 && (dst.second == 0 || dst.second == 7)) {
            if (dst.second == 0) canCastleWQ = false else canCastleWK = false
        }
        if (capturedBefore == 'r' && dst.first == 7 && (dst.second == 0 || dst.second == 7)) {
            if (dst.second == 0) canCastleBQ = false else canCastleBK = false
        }
    }

    private fun pathClear(sr: Int, sf: Int, dr: Int, df: Int): Boolean {
        val rStep = (dr - sr).sign
        val fStep = (df - sf).sign
        var r = sr + rStep
        var f = sf + fStep
        while (r != dr || f != df) {
            if (cells[r][f] != '.') return false
            r += rStep; f += fStep
        }
        return true
    }

    private fun findSource(
        movingType: Char,
        white: Boolean,
        dr: Int, df: Int,
        isCapture: Boolean,
        disRank: Int?, disFile: Int?,
        isPromotion: Boolean
    ): Pair<Int,Int>? {
        val res = ArrayList<Pair<Int,Int>>()
        val need = sideChar(white, movingType)
        when (movingType) {
            'P' -> {
                val dir = if (white) +1 else -1
                if (isCapture) {
                    val sr = dr - dir
                    for (f in listOf(df-1, df+1)) if (sr in 0..7 && f in 0..7) {
                        if (cells[sr][f] == sideChar(white, 'P')) {
                            if (!isEmptyCell(dr, df) || (enPassantTarget == (dr to df))) {
                                res += sr to f
                            }
                        }
                    }
                } else {
                    val sr1 = dr - dir
                    if (sr1 in 0..7 && isEmptyCell(dr, df) && cells[sr1][df] == sideChar(white, 'P')) {
                        res += sr1 to df
                    }
                    val sr2 = dr - 2*dir
                    val mid = dr - dir
                    val startRank = if (white) 1 else 6
                    if ((dr == (if (white) 3 else 4)) &&
                        (sr2 == startRank) &&
                        isEmptyCell(dr, df) && isEmptyCell(mid, df) &&
                        (cells[sr2][df] == sideChar(white, 'P'))
                    ) {
                        res += sr2 to df
                    }
                }
            }
            'N' -> {
                val deltas = arrayOf(
                    +2 to +1, +2 to -1, -2 to +1, -2 to -1,
                    +1 to +2, +1 to -2, -1 to +2, -1 to -2
                )
                for ((dR, dF) in deltas) {
                    val r = dr - dR; val f = df - dF
                    if (r in 0..7 && f in 0..7 && cells[r][f] == need) {
                        if ((!isCapture && isEmptyCell(dr, df)) ||
                            (isCapture && !isEmptyCell(dr, df))
                        ) res += r to f
                    }
                }
            }
            'B','R','Q' -> {
                val dirs = when (movingType) {
                    'B' -> arrayOf(+1 to +1, +1 to -1, -1 to +1, -1 to -1)
                    'R' -> arrayOf(+1 to 0, -1 to 0, 0 to +1, 0 to -1)
                    else -> arrayOf(+1 to +1, +1 to -1, -1 to +1, -1 to -1, +1 to 0, -1 to 0, 0 to +1, 0 to -1)
                }
                for ((dR, dF) in dirs) {
                    var r = dr - dR; var f = df - dF
                    while (r in 0..7 && f in 0..7) {
                        val ch = cells[r][f]
                        if (ch != '.') {
                            if (ch == need && pathClear(r, f, dr, df)) {
                                if ((!isCapture && isEmptyCell(dr, df)) ||
                                    (isCapture && !isEmptyCell(dr, df))
                                ) res += r to f
                            }
                            break
                        }
                        r -= dR; f -= dF
                    }
                }
            }
            'K' -> {
                for (dR in -1..1) for (dF in -1..1) {
                    if (dR == 0 && dF == 0) continue
                    val r = dr - dR; val f = df - dF
                    if (r in 0..7 && f in 0..7 && cells[r][f] == need) {
                        if ((!isCapture && isEmptyCell(dr, df)) ||
                            (isCapture && !isEmptyCell(dr, df))
                        ) res += r to f
                    }
                }
            }
        }
        val filtered = res.filter { (disRank == null || it.first == disRank) && (disFile == null || it.second == disFile) }
        return filtered.firstOrNull() ?: res.firstOrNull()
    }
}
