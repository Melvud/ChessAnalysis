package com.example.chessanalysis.engine

import com.example.chessanalysis.data.util.BoardState

data class Ply(val san: String, val uci: String)

object ChessParser {

    /** Достаём SAN из PGN, очищая теги, комментарии, варианты, NAG и номера ходов. */
    private fun tokenizeSan(pgn: String): List<String> {
        var s = pgn

        // 1) Удалить теговую секцию [Key "Value"] — целиком построчно
        s = s.lines()
            .filterNot { it.trimStart().startsWith('[') }
            .joinToString("\n")

        // 2) Удалить комментарии { ... } — любой многострочный контент внутри фигурных
        s = s.replace(Regex("\\{.*?\\}", setOf(RegexOption.DOT_MATCHES_ALL)), "")

        // 3) Удалить комментарии через ; до конца строки
        s = s.replace(Regex(";.*"), "")

        // 4) Удалять NAG вроде $1, $3 и т.п.
        s = s.replace(Regex("\\$\\d+"), "")

        // 5) Удалять варианты ( .. ) — по уровням (жадно не берём, только «плоский» уровень)
        while (true) {
            val t = s.replace(Regex("\\([^()]*\\)"), "")
            if (t == s) break
            s = t
        }

        // 6) Разбить на токены и отфильтровать служебки
        val raw = s.replace("\n", " ").trim().split(Regex("\\s+"))
        val skip = setOf("1-0", "0-1", "1/2-1/2", "*")
        val moveNum = Regex("^\\d+\\.+$")
        return raw.filter { tok ->
            tok.isNotBlank() &&
                    tok !in skip &&
                    !moveNum.matches(tok)
        }
    }

    /** SAN -> промо-буква ('q','r','b','n') или null. */
    private fun promoLetterLower(san: String): Char? {
        Regex("=([QRBN])$").find(san)?.let { return it.groupValues[1][0].lowercaseChar() }
        val last = san.lastOrNull() ?: return null
        return if (last in listOf('Q','R','B','N')) last.lowercaseChar() else null
    }

    private fun coordsToSquare(r: Int, f: Int) = "${'a'+f}${'1'+r}"

    /** Дифф двух позиций, чтобы получить UCI (from,to) под данный SAN. */
    private fun diffMove(before: BoardState, after: BoardState, san: String): Pair<String,String> {
        // Рокировки — быстрый путь
        if (san == "O-O" || san == "0-0") {
            return when {
                before.at(0,4) == 'K' -> "e1" to "g1"
                before.at(7,4) == 'k' -> "e8" to "g8"
                else -> error("Castle O-O impossible")
            }
        }
        if (san == "O-O-O" || san == "0-0-0") {
            return when {
                before.at(0,4) == 'K' -> "e1" to "c1"
                before.at(7,4) == 'k' -> "e8" to "c8"
                else -> error("Castle O-O-O impossible")
            }
        }

        // Тип фигуры по SAN
        val moverType = when (san.first()) {
            'K','Q','R','B','N' -> san.first()
            else -> 'P'
        }

        var fromR = -1; var fromF = -1
        var toR   = -1; var toF   = -1

        val wantW = moverType
        val wantB = moverType.lowercaseChar()
        var wGone = false; var bGone = false

        // Кто исчез с доски: белая/чёрная фигура нужного типа
        for (r in 0..7) for (f in 0..7) {
            val a = before.at(r,f); val b = after.at(r,f)
            if (a == wantW && b == '.') { fromR = r; fromF = f; wGone = true }
            if (a == wantB && b == '.') { fromR = r; fromF = f; bGone = true }
        }
        val moverIsWhite = if (wGone && !bGone) true else if (!wGone && bGone) false else wGone

        // Куда пришли: с учётом промо или без
        val promo = promoLetterLower(san)
        for (r in 0..7) for (f in 0..7) {
            val a = before.at(r,f); val b = after.at(r,f)
            if (a == b) continue
            val good = if (moverType == 'P') {
                if (promo != null) {
                    if (moverIsWhite) b == promo.uppercaseChar() else b == promo
                } else {
                    if (moverIsWhite) b == 'P' else b == 'p'
                }
            } else {
                if (moverIsWhite) b == wantW else b == wantB
            }
            if (good) { toR = r; toF = f }
        }

        if (fromR == -1 || toR == -1) {
            // Общий фолбэк: две изменившиеся клетки
            val fromC = mutableListOf<Pair<Int,Int>>()
            val toC   = mutableListOf<Pair<Int,Int>>()
            for (r in 0..7) for (f in 0..7) {
                val a = before.at(r,f); val b = after.at(r,f)
                if (a != b) {
                    if (b == '.') fromC += r to f else toC += r to f
                }
            }
            if (fromC.isNotEmpty()) { fromR = fromC.first().first; fromF = fromC.first().second }
            if (toC.isNotEmpty())   { toR   = toC.first().first;   toF   = toC.first().second }
        }

        require(fromR != -1 && toR != -1) { "Cannot diff SAN '$san'" }
        return coordsToSquare(fromR, fromF) to coordsToSquare(toR, toF)
    }

    /** PGN → список полуходов (SAN + UCI) строго по позиции. */
    fun pgnToPlies(pgn: String): List<Ply> {
        val tokens = tokenizeSan(pgn)
        val list = ArrayList<Ply>(tokens.size)
        var board = BoardState.initial()

        tokens.forEach { san ->
            val before = board.copy()
            board.applySan(san)
            val (from, to) = diffMove(before, board, san)
            val promo = promoLetterLower(san)
            val uci = if (promo != null) from + to + promo else from + to
            list += Ply(san = san, uci = uci)
        }
        return list
    }
}
