package com.example.chessanalysis.data

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private const val DS_NAME = "settings"
private val Context.dataStore by preferencesDataStore(name = DS_NAME)

object AnalysisModeStore {
    private val KEY_MODE = stringPreferencesKey("analysis_mode")

    /**
     * Поток текущего режима анализа. Если ничего не сохранено — вернёт LOCAL.
     */
    fun modeFlow(context: Context): Flow<AnalysisMode> =
        context.dataStore.data.map { prefs ->
            when (prefs[KEY_MODE]) {
                AnalysisMode.SERVER.name -> AnalysisMode.SERVER
                else -> AnalysisMode.LOCAL // default = LOCAL
            }
        }

    /**
     * Сохранить выбранный режим анализа (LOCAL / SERVER).
     */
    suspend fun setMode(context: Context, mode: AnalysisMode) {
        context.dataStore.edit { prefs: MutablePreferences ->
            prefs[KEY_MODE] = mode.name
        }
    }
}
