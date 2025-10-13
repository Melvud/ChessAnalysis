package com.github.movesense

import android.app.Application
import com.github.movesense.analysis.Openings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class App : Application() {
    // В App.kt
    override fun onCreate() {
        super.onCreate()

        // КРИТИЧНО: Повышаем приоритет процесса
        android.os.Process.setThreadPriority(
            android.os.Process.THREAD_PRIORITY_URGENT_AUDIO
        )

        // Важно! Вызываем suspend-функцию в корутине
        CoroutineScope(Dispatchers.Default).launch {
            EngineClient.setAndroidContext(this@App)
        }

        Openings.init(this)
    }
}