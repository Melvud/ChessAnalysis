package com.example.chessanalysis.util

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import java.util.Locale

object LocaleManager {

    private const val PREF_NAME = "app_settings"
    private const val KEY_LANGUAGE = "selected_language"

    enum class Language(val code: String, val displayName: String) {
        RUSSIAN("ru", "Русский"),
        ENGLISH("en", "English"),
        SPANISH("es", "Español");

        companion object {
            fun fromCode(code: String): Language {
                return values().find { it.code == code } ?: RUSSIAN
            }
        }
    }

    fun setLocale(context: Context, language: Language) {
        saveLanguage(context, language)
        updateResources(context, language.code)
    }

    fun getLocale(context: Context): Language {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val code = prefs.getString(KEY_LANGUAGE, Language.RUSSIAN.code) ?: Language.RUSSIAN.code
        return Language.fromCode(code)
    }

    private fun saveLanguage(context: Context, language: Language) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_LANGUAGE, language.code).apply()
    }

    fun updateResources(context: Context, languageCode: String) {
        val locale = Locale(languageCode)
        Locale.setDefault(locale)

        val resources: Resources = context.resources
        val config: Configuration = resources.configuration
        config.setLocale(locale)

        context.createConfigurationContext(config)
        resources.updateConfiguration(config, resources.displayMetrics)
    }

    fun applyLocale(context: Context): Context {
        val language = getLocale(context)
        val locale = Locale(language.code)
        Locale.setDefault(locale)

        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)

        return context.createConfigurationContext(config)
    }
}