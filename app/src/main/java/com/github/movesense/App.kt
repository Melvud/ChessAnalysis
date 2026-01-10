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
            // Determine default language based on system locale
            val systemLocale = Locale.getDefault().language
            val defaultLanguage = when (systemLocale) {
                "ru" -> LocaleManager.Language.RUSSIAN.code
                "es" -> LocaleManager.Language.SPANISH.code
                "hi" -> LocaleManager.Language.HINDI.code
                "pt" -> LocaleManager.Language.PORTUGUESE.code
                "de" -> LocaleManager.Language.GERMAN.code
                "fr" -> LocaleManager.Language.FRENCH.code
                "pl" -> LocaleManager.Language.POLISH.code
                "in", "id" -> LocaleManager.Language.INDONESIAN.code
                "uk" -> LocaleManager.Language.UKRAINIAN.code
                else -> LocaleManager.Language.ENGLISH.code
            }

            prefs.edit()
                .putString("selected_language", defaultLanguage)
                .putBoolean("first_launch", false)
                .apply()
        }

        val language = LocaleManager.getLocale(this)
        Locale.setDefault(Locale(language.code))

        EngineClient.setAndroidContext(this)
        Openings.init(this)
    }
}