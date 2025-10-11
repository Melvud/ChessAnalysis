package com.github.movesense

import android.app.Application
import com.github.movesense.analysis.LocalGameAnalyzer
import com.github.movesense.analysis.Openings
import com.github.movesense.engine.EnginePool

class App : Application() {
    override fun onCreate() {
        super.onCreate()

        // КРИТИЧНО: устанавливаем контекст для локального движка
        EngineClient.setAndroidContext(this)
        Openings.init(this)

        // КРИТИЧНО: Инициализируем пул движков
        val cores = Runtime.getRuntime().availableProcessors()
        val poolSize = when {
            cores >= 8 -> 4  // 8+ ядер: 4 воркера
            cores >= 6 -> 3  // 6-7 ядер: 3 воркера
            cores >= 4 -> 2  // 4-5 ядер: 2 воркера
            else -> 1        // 2-3 ядра: 1 воркер
        }

        LocalGameAnalyzer.initializePool(this, poolSize)
        android.util.Log.i("App", "🚀 Engine pool initialized with $poolSize workers")
    }

    override fun onTerminate() {
        super.onTerminate()
        LocalGameAnalyzer.shutdownPool()
        EnginePool.destroyInstance()
    }
}