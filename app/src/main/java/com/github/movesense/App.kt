package com.github.movesense

import android.app.Application
import com.github.movesense.analysis.Openings

class App : Application() {
    override fun onCreate() {
        super.onCreate()

        // КРИТИЧНО: устанавливаем контекст для локального движка
        EngineClient.setAndroidContext(this)
        Openings.init(this)
    }
}