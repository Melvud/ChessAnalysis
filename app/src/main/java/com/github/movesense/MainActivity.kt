package com.github.movesense

import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.github.movesense.ui.AppRoot
import com.github.movesense.util.LocaleManager

class MainActivity : ComponentActivity() {

    /**
     * ✅ Применяем локаль ДО создания контекста Activity
     * Это критично для правильной работы локализации
     */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleManager.applyLocale(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Инициализация движка
        EngineClient.setAndroidContext(applicationContext)

        // Устанавливаем портретную ориентацию
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT

        // Не даём экрану гаснуть
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Запускаем UI
        setContent {
            AppRoot()
        }
    }

    /**
     * ✅ Обрабатываем изменение конфигурации (поворот экрана, смена языка системы)
     * НО не перезагружаем Activity, так как это указано в AndroidManifest
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)

        // Игнорируем изменения системного языка
        // Мы всегда используем язык, сохранённый в SharedPreferences
        // (это уже обработано в attachBaseContext)
    }

    override fun onDestroy() {
        super.onDestroy()
        // Очищаем флаг, если нужно
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
}