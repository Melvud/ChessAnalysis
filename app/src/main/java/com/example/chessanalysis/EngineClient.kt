package com.example.chessanalysis

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString

object EngineClient {
    private val client = OkHttpClient()
    private val json = Json { ignoreUnknownKeys = true }

    // StockfishOnline v2: fen + depth (<=15). Есть и совместимость с .php.
    suspend fun analyzeFen(fen: String, depth: Int = 12): StockfishResponse = withContext(Dispatchers.IO) {
        val safeFen = URLEncoder.encode(fen, "UTF-8")
        val urls = listOf(
            "https://www.stockfish.online/api/stockfish?fen=$safeFen&depth=$depth",
            "https://www.stockfish.online/api/stockfish.php?fen=$safeFen&depth=$depth"
        )
        for (u in urls) {
            try {
                val req = Request.Builder().url(u).get().build()
                client.newCall(req).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    if (resp.isSuccessful && body.isNotBlank()) {
                        return@withContext json.decodeFromString<StockfishResponse>(body)
                    }
                }
            } catch (_: Exception) {}
        }
        return@withContext StockfishResponse(false, error = "engine_unreachable")
    }
}
