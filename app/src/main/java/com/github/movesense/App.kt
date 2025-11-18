package com.github.movesense

import android.app.Application
import android.util.Log
import com.github.movesense.analysis.Openings
import com.github.movesense.util.LocaleManager
import com.github.movesense.subscription.RevenueCatManager
import java.util.Locale

class App : Application() {
    companion object {
        private const val TAG = "App"
    }

    override fun onCreate() {
        super.onCreate()

        // ✅ При первом запуске устанавливаем английский по умолчанию
        val prefs = getSharedPreferences("app_settings", MODE_PRIVATE)
        val isFirstLaunch = prefs.getBoolean("first_launch", true)

        if (isFirstLaunch) {
            // Сохраняем английский как дефолтный язык
            prefs.edit()
                .putString("selected_language", LocaleManager.Language.ENGLISH.code)
                .putBoolean("first_launch", false)
                .apply()
        }

        // ✅ Устанавливаем системную локаль
        val language = LocaleManager.getLocale(this)
        Locale.setDefault(Locale(language.code))

        // Устанавливаем контекст для локального движка
        EngineClient.setAndroidContext(this)
        Openings.init(this)

        // 🆕 Инициализируем RevenueCat
        try {
            RevenueCatManager.initialize(this)
            Log.d(TAG, "✅ RevenueCat initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to initialize RevenueCat", e)
        }
    }
}