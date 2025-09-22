package com.example.chessanalysis.opening

import android.content.Context
import com.example.chessanalysis.data.model.OpeningInfo
import org.json.JSONArray

/**
 * Дебютная книга:
 * 1) По умолчанию содержит небольшой встроенный список (для работы “из коробки”).
 * 2) Если положить в assets файл `openings.json` в формате:
 * [
 *   {"eco":"A45","name":"Trompowsky","uci":"d2d4 g8f6 c1g5"},
 *   ...
 * ]
 * — будет использован он (полный opening.ts можно конвертировать в такой JSON).
 */
object OpeningBook {

    private val builtin = listOf(
        OpeningInfo("B00", "King's Pawn Opening", listOf("e2e4")),
        OpeningInfo("D00", "Queen's Pawn Game", listOf("d2d4")),
        OpeningInfo("C20", "KP Opening: e4 e5", listOf("e2e4","e7e5")),
        OpeningInfo("B01", "Scandinavian Defense", listOf("e2e4","d7d5")),
        OpeningInfo("B10", "Caro-Kann Defense", listOf("e2e4","c7c6"))
    )

    fun loadFromAssets(context: Context): List<OpeningInfo> = try {
        val ins = context.assets.open("openings.json")
        val txt = ins.bufferedReader().use { it.readText() }
        val arr = JSONArray(txt)
        val list = ArrayList<OpeningInfo>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val eco = o.getString("eco")
            val name = o.getString("name")
            val uci = o.getString("uci").trim().split(Regex("\\s+")).filter { it.isNotBlank() }
            list += OpeningInfo(eco, name, uci)
        }
        list
    } catch (_: Throwable) {
        builtin
    }

    // В рантайме удобно держать кеш; здесь — статично для простоты.
    private var cache: List<OpeningInfo>? = null
    fun ensureLoaded(context: Context) { if (cache == null) cache = loadFromAssets(context) }

    /** Сопоставление по префиксу UCI-последовательности. */
    fun detectByUci(uciMoves: List<String>): OpeningInfo? {
        val book = cache ?: builtin
        var best: OpeningInfo? = null
        var bestLen = 0
        for (op in book) {
            val m = op.movesUci
            if (uciMoves.size < m.size) continue
            var ok = true
            for (i in m.indices) if (uciMoves[i] != m[i]) { ok = false; break }
            if (ok && m.size > bestLen) { best = op; bestLen = m.size }
        }
        return best
    }
}
