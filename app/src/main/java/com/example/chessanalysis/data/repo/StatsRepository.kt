package com.example.chessanalysis.data.repo

import android.content.Context
import com.example.chessanalysis.data.local.StatsDatabase
import com.example.chessanalysis.data.local.dao.StatsDao
import com.example.chessanalysis.data.local.entity.GameStatsEntity
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class StatsRepository(
    context: Context,
    private val io: CoroutineDispatcher = Dispatchers.IO
) {
    private val dao: StatsDao = StatsDatabase.get(context).statsDao()
    private val sync = FirestoreSync()

    // ======== Публичные флоу для UI ========
    val countFlow: Flow<Int> = dao.countFlow()
    val last10Flow: Flow<List<GameStatsEntity>> = dao.last10Flow()
    val avgAccuracyFlow: Flow<Double?> = dao.avgAccuracyFlow()
    val avgPerformanceFlow: Flow<Double?> = dao.avgPerformanceFlow()
    val totalsFlow: Flow<StatsDao.Totals?> = dao.totalsFlow()

    // ======== API сохранения из отчёта анализа ========
    /**
     * Вызываем по факту завершения анализа одной партии.
     */
    suspend fun saveFromEvaluation(
        accuracy: Double,
        performance: Int,
        opponentRating: Int,
        moveCount: Int,
        best: Int,
        brilliant: Int,
        great: Int,
        good: Int,
        inaccuracy: Int,
        mistake: Int,
        blunder: Int
    ) = withContext(io) {
        val entity = GameStatsEntity(
            accuracy = accuracy.coerceIn(0.0, 100.0),
            performance = performance,
            opponentRating = opponentRating,
            moveCount = moveCount,
            bestCount = best,
            brilliantCount = brilliant,
            greatCount = great,
            goodCount = good,
            inaccuracyCount = inaccuracy,
            mistakeCount = mistake,
            blunderCount = blunder
        )
        dao.upsert(entity)

        // Пытаемся синкнуть в облако (офлайн — не страшно: Firestore сам кэширует). :contentReference[oaicite:3]{index=3}
        trySyncPending()
    }

    /**
     * Ручной вызов синка, можно дергать на старте приложения/экранов.
     */
    suspend fun trySyncPending() = withContext(io) {
        val uid = sync.ensureUser()
        val pending = dao.pendingForSync()
        for (p in pending) {
            val syncedAt = sync.push(uid, p)
            dao.update(p.copy(syncedAt = syncedAt))
        }
    }
}
