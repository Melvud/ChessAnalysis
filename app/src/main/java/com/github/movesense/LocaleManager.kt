@file:Suppress("DEPRECATION")

package com.github.movesense.util

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
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
                return values().find { it.code == code } ?: ENGLISH
            }
        }
    }

    /**
     * Устанавливает язык и ПЕРЕЗАПУСКАЕТ Activity для применения изменений
     */
    fun setLocale(context: Context, language: Language) {
        val currentLanguage = getLocale(context)

        // Сохраняем новый язык
        saveLanguage(context, language)

        // Если язык изменился, перезапускаем Activity
        if (currentLanguage != language && context is Activity) {
            val intent = context.intent
            context.finish()
            context.startActivity(intent)
        }
    }

    /**
     * Получает текущий язык из SharedPreferences
     */
    fun getLocale(context: Context): Language {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val code = prefs.getString(KEY_LANGUAGE, null)
        return if (code != null) {
            Language.fromCode(code)
        } else {
            Language.ENGLISH
        }
    }

    /**
     * Сохраняет язык в SharedPreferences
     */
    private fun saveLanguage(context: Context, language: Language) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_LANGUAGE, language.code).apply()
    }

    /**
     * Получает код языка из SharedPreferences (может быть null)
     */
    fun getSavedLanguageCode(context: Context): String? {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_LANGUAGE, null)
    }

    /**
     * Применяет локаль к контексту (для attachBaseContext)
     */
    fun applyLocale(context: Context): Context {
        val language = getLocale(context)
        return updateContextLocale(context, language.code)
    }

    /**
     * Создает новый контекст с правильной локалью
     */
    private fun updateContextLocale(context: Context, languageCode: String): Context {
        val locale = Locale(languageCode)
        Locale.setDefault(locale)

        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)

        return context.createConfigurationContext(config)
    }
}