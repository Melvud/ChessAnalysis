package com.github.movesense.analysis

import android.util.Log
import androidx.collection.LruCache
import com.github.movesense.EngineClient
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Semaphore
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@Serializable
private data class LichessCloudResponse(
    val fen: String,
    val knodes: Int,
    val depth: Int?,
    val pvs: List<PvData>
)

@Serializable
private data class PvData(
    val moves: String,
    val cp: Int? = null,
    val mate: Int? = null
)

object CloudEvalCache {
    private const val TAG = "CloudEvalCache"
    private const val MAX_CACHE_SIZE = 15000 // Увеличили
    private const val MAX_CONCURRENT = 5 // Lichess позволяет до 10
    private const val TIMEOUT_MS = 1500L // Снизили для скорости

    private data class CachedEval(
        val eval: EngineClient.PositionDTO,
        val timestamp: Long = System.currentTimeMillis()
    )

    private val cache = LruCache<String, CachedEval>(MAX_CACHE_SIZE)
    private val semaphore = Semaphore(MAX_CONCURRENT)
    private val requestCounter = AtomicInteger(0)
    private val json = Json { ignoreUnknownKeys = true }

    private val client = OkHttpClient.Builder()
        .connectTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()

    private val requestChannel = Channel<Pair<String, CompletableDeferred<EngineClient.PositionDTO?>>>(Channel.UNLIMITED)

    init {
        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            var lastRequestTime = 0L
            val minInterval = 100L // 10 req/sec (Lichess limit)

            for ((fen, deferred) in requestChannel) {
                try {
                    val now = System.currentTimeMillis()
                    val elapsed = now - lastRequestTime
                    if (elapsed < minInterval) {
                        delay(minInterval - elapsed)
                    }
                    lastRequestTime = System.currentTimeMillis()

                    val result = fetchFromLichess(fen)
                    deferred.complete(result)
                } catch (e: Exception) {
                    deferred.complete(null)
                }
            }
        }
    }

    suspend fun getEval(fen: String): EngineClient.PositionDTO? {
        cache[fen]?.let { cached ->
            if (System.currentTimeMillis() - cached.timestamp < 3600_000) {
                Log.d(TAG, "💾 Cache hit")
                return cached.eval
            }
        }

        return withTimeoutOrNull(TIMEOUT_MS + 500) {
            val deferred = CompletableDeferred<EngineClient.PositionDTO?>()
            requestChannel.send(fen to deferred)
            deferred.await()?.also { eval ->
                cache.put(fen, CachedEval(eval))
                Log.d(TAG, "☁️ Cloud hit (depth=${eval.lines.firstOrNull()?.depth})")
            }
        }
    }

    fun prefetchBatch(fens: List<String>) {
        CoroutineScope(Dispatchers.IO).launch {
            fens.forEach { fen ->
                if (cache[fen] == null) {
                    val deferred = CompletableDeferred<EngineClient.PositionDTO?>()
                    requestChannel.trySend(fen to deferred)
                }
            }
        }
    }

    private suspend fun fetchFromLichess(fen: String): EngineClient.PositionDTO? = withContext(Dispatchers.IO) {
        semaphore.acquire()
        try {
            val count = requestCounter.incrementAndGet()

            // ✅ КРИТИЧНО: правильное кодирование FEN
            val fenEncoded = java.net.URLEncoder.encode(fen, "UTF-8")
                .replace("+", "%20") // Lichess не понимает +

            val url = "https://lichess.org/api/cloud-eval?fen=$fenEncoded&multiPv=3"

            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .header("User-Agent", "ChessAnalysis/2.0")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "Cloud failed: HTTP ${response.code}")
                    return@withContext null
                }

                val body = response.body?.string() ?: return@withContext null
                val cloudData = json.decodeFromString<LichessCloudResponse>(body)

                // ✅ Логируем успех
                if (count % 5 == 0) {
                    Log.i(TAG, "📊 Cloud requests: $count, depth=${cloudData.depth}")
                }

                val lines = cloudData.pvs.mapIndexed { index, pv ->
                    EngineClient.LineDTO(
                        pv = pv.moves.split(" ").filter { it.isNotBlank() },
                        cp = pv.cp,
                        mate = pv.mate,
                        depth = cloudData.depth ?: 40,
                        multiPv = index + 1
                    )
                }

                EngineClient.PositionDTO(lines, lines.firstOrNull()?.pv?.firstOrNull())
            }
        } catch (e: Exception) {
            Log.w(TAG, "Cloud error: ${e.message}")
            null
        } finally {
            semaphore.release()
        }
    }

    fun getStats(): String = "Requests: ${requestCounter.get()}, Cache: ${cache.size()}/$MAX_CACHE_SIZE"
}