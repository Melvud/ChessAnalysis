package com.example.chessanalysis

import android.app.Application
import com.example.chessanalysis.EngineClient
import com.example.chessanalysis.analysis.Openings

class App : Application() {
    override fun onCreate() {
        super.onCreate()

        // КРИТИЧНО: устанавливаем контекст для локального движка
        EngineClient.setAndroidContext(this)
        Openings.init(this)
    }
}