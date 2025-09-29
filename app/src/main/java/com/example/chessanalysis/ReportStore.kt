package com.example.chessanalysis

import java.util.UUID
import java.util.LinkedHashMap

/**
 * Небольшой in‑memory стор для временного хранения больших JSON отчётов.
 * Нужен, чтобы не зависеть от особенностей savedStateHandle и не возить
 * через аргументы навигации длинные строки.
 */
object ReportStore {
    // ЛРУ-карта на несколько последних отчётов.
    private const val MAX = 10
    private val map = object : LinkedHashMap<String, String>(MAX, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean {
            return size > MAX
        }
    }

    /** Сохраняет JSON отчёта и возвращает короткий id. */
    fun put(json: String): String {
        val id = UUID.randomUUID().toString()
        synchronized(map) { map[id] = json }
        return id
    }

    /** Получить JSON по id (или null, если уже вытеснен). */
    fun get(id: String?): String? {
        if (id.isNullOrBlank()) return null
        return synchronized(map) { map[id] }
    }

    /** Удалить отчёт (не обязательно вызывать). */
    fun remove(id: String?) {
        if (id.isNullOrBlank()) return
        synchronized(map) { map.remove(id) }
    }
}
