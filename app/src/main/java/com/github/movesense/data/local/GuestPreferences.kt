package com.github.movesense.data.local

import android.content.Context
import androidx.core.content.edit

object GuestPreferences {
    private const val PREF_NAME = "guest_prefs"
    private const val KEY_LICHESS_USERNAME = "lichess_username"
    private const val KEY_CHESS_USERNAME = "chess_username"
    private const val KEY_LANGUAGE = "language"

    fun getLichessUsername(context: Context): String {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getString(KEY_LICHESS_USERNAME, "") ?: ""
    }

    fun setLichessUsername(context: Context, username: String) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit {
            putString(KEY_LICHESS_USERNAME, username)
        }
    }

    fun getChessUsername(context: Context): String {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getString(KEY_CHESS_USERNAME, "") ?: ""
    }

    fun setChessUsername(context: Context, username: String) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit {
            putString(KEY_CHESS_USERNAME, username)
        }
    }

    fun getLanguage(context: Context): String? {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getString(KEY_LANGUAGE, null)
    }

    fun setLanguage(context: Context, language: String) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit {
            putString(KEY_LANGUAGE, language)
        }
    }

    private const val KEY_IS_GUEST_ACTIVE = "is_guest_active"

    fun isGuestActive(context: Context): Boolean {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_IS_GUEST_ACTIVE, false)
    }

    fun setGuestActive(context: Context, active: Boolean) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit(commit = true) {
            putBoolean(KEY_IS_GUEST_ACTIVE, active)
        }
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit(commit = true) {
            clear()
        }
    }
    private const val KEY_IS_ONBOARDING_SHOWN = "is_onboarding_shown"

    fun isOnboardingShown(context: Context): Boolean {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_IS_ONBOARDING_SHOWN, false)
    }

    fun setOnboardingShown(context: Context, shown: Boolean) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit(commit = true) {
            putBoolean(KEY_IS_ONBOARDING_SHOWN, shown)
        }
    }
}
