package com.github.movesense.api

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicInteger

/**
 * Клиент для работы с Lichess API с персистентным кешем и rate limiting
 *
 * Официальные лимиты Lichess:
 * - Opening Explorer: ~20 req/sec
 * - Cloud Eval: ~15-20 req/sec
 *
 * Стратегия:
 * - Минимум 50ms между запросами (max 20 req/sec)
 * - Exponential backoff при 429 ошибке
 * - Двухуровневый кеш (memory + persistent)
 * - Обязательный User-Agent
 */
object LichessApiClient {
    private const val TAG = "LichessApiClient"

    private const val OPENING_EXPLORER_URL = "https://explorer.lichess.ovh/lichess"
    private const val CLOUD_EVAL_URL = "https://lichess.org/api/cloud-eval"

    private const val PREFS_NAME = "lichess_cache"
    private const val KEY_CLOUD_EVAL_PREFIX = "cloud_eval_"
    private const val KEY_OPENING_PREFIX = "opening_"

    // КРИТИЧНО: Rate limiting - минимум 50ms между запросами = max 20 req/sec
    private const val MIN_REQUEST_INTERVAL_MS = 50L

    // Exponential backoff при 429 ошибке
    private const val RATE_LIMIT_BACKOFF_MS = 2000L
    private const val MAX_BACKOFF_COUNT = 3

    private val lastRequestTime = AtomicLong(0)
    private val consecutiveRateLimits = AtomicInteger(0)

    private var prefs: SharedPreferences? = null
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .callTimeout(8, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    // In-memory кеш для быстрого доступа
    private val openingMemCache = ConcurrentHashMap<String, Boolean>()
    private val cloudEvalMemCache = ConcurrentHashMap<String, CloudEvalResponse>()

    @Serializable
    data class OpeningExplorerResponse(
        val moves: List<OpeningMove> = emptyList(),
        val white: Long = 0,
        val draws: Long = 0,
        val black: Long = 0
    )

    @Serializable
    data class OpeningMove(
        val uci: String,
        val san: String? = null,
        val white: Long = 0,
        val draws: Long = 0,
        val black: Long = 0,
        val averageRating: Int? = null
    )

    @Serializable
    data class CloudEvalResponse(
        val fen: String,
        val knodes: Int = 0,
        val depth: Int = 0,
        val pvs: List<CloudPv> = emptyList()
    )

    @Serializable
    data class CloudPv(
        val moves: String? = null,
        val cp: Int? = null,
        val mate: Int? = null
    )

    /**
     * Инициализация с контекстом Android
     */
    fun init(context: Context) {
        if (prefs == null) {
            prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            Log.d(TAG, "✓ Initialized with persistent cache")
        }
    }

    /**
     * Rate limiting с exponential backoff
     * Гарантирует минимум 50ms между запросами + дополнительная задержка при 429
     */
    private suspend fun waitForRateLimit() {
        val now = System.currentTimeMillis()
        val lastRequest = lastRequestTime.get()
        val elapsed = now - lastRequest

        // Если получали 429, увеличиваем задержку экспоненциально
        val rateLimitCount = consecutiveRateLimits.get()
        val extraDelay = if (rateLimitCount > 0) {
            val backoffMultiplier = minOf(rateLimitCount, MAX_BACKOFF_COUNT)
            RATE_LIMIT_BACKOFF_MS * backoffMultiplier
        } else {
            0L
        }

        val requiredDelay = MIN_REQUEST_INTERVAL_MS + extraDelay

        if (elapsed < requiredDelay) {
            val waitTime = requiredDelay - elapsed
            Log.d(TAG, "⏱ Rate limit wait: ${waitTime}ms (backoff count: $rateLimitCount)")
            delay(waitTime)
        }

        lastRequestTime.set(System.currentTimeMillis())
    }

    /**
     * Обработка 429 ошибки
     */
    private fun handleRateLimit() {
        val count = consecutiveRateLimits.incrementAndGet()
        Log.w(TAG, "⚠ Rate limited (429)! Backoff count: $count")
    }

    /**
     * Сброс счетчика rate limits при успешном запросе
     */
    private fun resetRateLimitCounter() {
        if (consecutiveRateLimits.get() > 0) {
            consecutiveRateLimits.set(0)
            Log.d(TAG, "✓ Rate limit counter reset")
        }
    }

    /**
     * Проверяет, является ли позиция дебютной (есть ли в Opening Explorer)
     *
     * @return true если позиция найдена в базе дебютов (>100 партий)
     */
    suspend fun isOpeningPosition(fen: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val normalized = normalizeFen(fen)
            val cacheKey = KEY_OPENING_PREFIX + normalized.hashCode()

            // 1. Проверяем memory cache (самый быстрый)
            openingMemCache[normalized]?.let {
                return@withContext it
            }

            // 2. Проверяем persistent cache
            prefs?.getString(cacheKey, null)?.let { cached ->
                val isOpening = cached.toBoolean()
                openingMemCache[normalized] = isOpening
                return@withContext isOpening
            }

            // 3. Делаем запрос с rate limiting
            waitForRateLimit()

            val url = "$OPENING_EXPLORER_URL?fen=$normalized&ratings=2000,2200,2500&speeds=blitz,rapid,classical"
            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .header("User-Agent", "MoveSense-Android/1.0 (contact: @Melvud; +https://github.com/melvud)")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                when (response.code) {
                    429 -> {
                        handleRateLimit()
                        // При rate limit считаем позицию не дебютной (безопасный fallback)
                        return@withContext false
                    }
                    200 -> {
                        resetRateLimitCounter()
                        val body = response.body?.string() ?: return@withContext false
                        val result = json.decodeFromString<OpeningExplorerResponse>(body)

                        // Считаем позицию дебютной, если есть минимум 100 партий
                        val totalGames = result.white + result.draws + result.black
                        val isOpening = totalGames > 100

                        // Сохраняем в оба кеша
                        openingMemCache[normalized] = isOpening
                        prefs?.edit()?.putString(cacheKey, isOpening.toString())?.apply()

                        Log.d(TAG, "✓ Opening: $isOpening (games=$totalGames)")
                        isOpening
                    }
                    else -> {
                        Log.w(TAG, "Opening explorer failed: ${response.code}")
                        false
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "isOpeningPosition error: ${e.message}")
            false
        }
    }

    /**
     * Получает облачную оценку позиции из Lichess
     *
     * @param fen FEN позиции (будет нормализован)
     * @param multiPv количество вариантов (1-5)
     * @return CloudEvalResponse или null при ошибке
     */
    suspend fun getCloudEval(fen: String, multiPv: Int = 3): CloudEvalResponse? = withContext(Dispatchers.IO) {
        try {
            val normalized = normalizeFen(fen)
            val memCacheKey = "$normalized:$multiPv"
            val prefsCacheKey = KEY_CLOUD_EVAL_PREFIX + memCacheKey.hashCode()

            // 1. Проверяем memory cache (самый быстрый)
            cloudEvalMemCache[memCacheKey]?.let {
                return@withContext it
            }

            // 2. Проверяем persistent cache
            prefs?.getString(prefsCacheKey, null)?.let { cached ->
                try {
                    val result = json.decodeFromString<CloudEvalResponse>(cached)
                    if (result.depth >= 18) {
                        cloudEvalMemCache[memCacheKey] = result
                        return@withContext result
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to parse cached cloud eval: ${e.message}")
                }
            }

            // 3. Делаем запрос с rate limiting
            waitForRateLimit()

            val url = "$CLOUD_EVAL_URL?fen=$normalized&multiPv=${multiPv.coerceIn(1, 5)}"
            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .header("User-Agent", "MoveSense-Android/1.0 (contact: @Melvud; +https://github.com/melvud)")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                when (response.code) {
                    429 -> {
                        handleRateLimit()
                        null
                    }
                    200 -> {
                        resetRateLimitCounter()
                        val body = response.body?.string() ?: return@withContext null
                        val result = json.decodeFromString<CloudEvalResponse>(body)

                        // Кешируем только если глубина достаточная (минимум 18)
                        if (result.depth >= 18) {
                            cloudEvalMemCache[memCacheKey] = result
                            prefs?.edit()?.putString(prefsCacheKey, json.encodeToString(result))?.apply()
                            Log.d(TAG, "✓ Cloud eval: depth=${result.depth}, pvs=${result.pvs.size}")
                        } else {
                            Log.d(TAG, "⚠ Cloud eval too shallow: depth=${result.depth}")
                        }

                        result
                    }
                    else -> {
                        Log.w(TAG, "Cloud eval failed: ${response.code}")
                        null
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "getCloudEval error: ${e.message}")
            null
        }
    }

    /**
     * Получает cloud eval из кеша БЕЗ сетевого запроса
     * Используется для быстрой проверки доступности данных
     *
     * @return CloudEvalResponse из кеша или null
     */
    fun getCachedCloudEval(fen: String, multiPv: Int): CloudEvalResponse? {
        val normalized = normalizeFen(fen)
        val memCacheKey = "$normalized:$multiPv"

        // Проверяем memory cache
        cloudEvalMemCache[memCacheKey]?.let { return it }

        // Проверяем persistent cache
        val prefsCacheKey = KEY_CLOUD_EVAL_PREFIX + memCacheKey.hashCode()
        prefs?.getString(prefsCacheKey, null)?.let { cached ->
            try {
                val result = json.decodeFromString<CloudEvalResponse>(cached)
                if (result.depth >= 18) {
                    // Восстанавливаем в memory cache
                    cloudEvalMemCache[memCacheKey] = result
                    return result
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse cached cloud eval: ${e.message}")
            }
        }

        return null
    }

    /**
     * Получает статус дебютности из кеша БЕЗ сетевого запроса
     * Используется для быстрой проверки доступности данных
     *
     * @return Boolean из кеша или null
     */
    fun getCachedOpeningStatus(fen: String): Boolean? {
        val normalized = normalizeFen(fen)

        // Проверяем memory cache
        openingMemCache[normalized]?.let { return it }

        // Проверяем persistent cache
        val cacheKey = KEY_OPENING_PREFIX + normalized.hashCode()
        prefs?.getString(cacheKey, null)?.let { cached ->
            val isOpening = cached.toBoolean()
            // Восстанавливаем в memory cache
            openingMemCache[normalized] = isOpening
            return isOpening
        }

        return null
    }

    /**
     * Нормализует FEN для использования в API
     * Убирает счетчики ходов (halfmove clock и fullmove number)
     *
     * Пример:
     * "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
     * -> "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq -"
     */
    private fun normalizeFen(fen: String): String {
        val parts = fen.split(" ")
        return if (parts.size >= 4) {
            parts.take(4).joinToString(" ")
        } else {
            fen
        }
    }

    /**
     * Очищает все кеши (memory + persistent)
     * Используется для освобождения памяти или сброса данных
     */
    fun clearCaches() {
        openingMemCache.clear()
        cloudEvalMemCache.clear()
        prefs?.edit()?.clear()?.apply()
        consecutiveRateLimits.set(0)
        Log.d(TAG, "✓ All caches cleared")
    }

    /**
     * Получает статистику использования кешей
     * Полезно для отладки и мониторинга
     *
     * @return строка с информацией о размерах кешей
     */
    fun getCacheStats(): String {
        val persistentSize = prefs?.all?.size ?: 0
        val rateLimitCount = consecutiveRateLimits.get()
        return "Memory: opening=${openingMemCache.size}, cloud=${cloudEvalMemCache.size} | " +
                "Persistent: $persistentSize | " +
                "Rate limit: $rateLimitCount"
    }

    /**
     * Проверяет, инициализирован ли клиент
     *
     * @return true если init() был вызван
     */
    fun isInitialized(): Boolean = prefs != null
}