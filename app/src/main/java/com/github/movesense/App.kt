package com.github.movesense

import android.app.Application
import com.github.movesense.analysis.Openings
import com.github.movesense.subscription.GooglePlayBillingManager
import com.github.movesense.util.LocaleManager
import java.util.Locale

class App : Application() {

    // ✅ Добавляем глобальное состояние сессии
    companion object {
        private const val TAG = "App"

        // Флаг: был ли закрыт баннер в этой сессии (до перезапуска приложения)
        var isBannerDismissed: Boolean = false
    }

    override fun onCreate() {
        super.onCreate()
        GooglePlayBillingManager.initialize(this)

        // Настройка языка (как у вас было)
        val prefs = getSharedPreferences("app_settings", MODE_PRIVATE)
        val isFirstLaunch = prefs.getBoolean("first_launch", true)

        if (isFirstLaunch) {
            prefs.edit()
                .putString("selected_language", LocaleManager.Language.ENGLISH.code)
                .putBoolean("first_launch", false)
                .apply()
        }

        val language = LocaleManager.getLocale(this)
        Locale.setDefault(Locale(language.code))

        EngineClient.setAndroidContext(this)
        Openings.init(this)
    }
}