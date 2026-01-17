package com.github.movesense

import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.github.movesense.ui.AppRoot
import com.github.movesense.ui.AppRoot
import com.github.movesense.util.LocaleManager
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.ExistingPeriodicWorkPolicy
import com.github.movesense.worker.RetentionWorker
import java.util.concurrent.TimeUnit
import android.os.Build
import androidx.activity.result.contract.ActivityResultContracts
import android.Manifest
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        // Permission result handled here if needed
    }

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

        // Schedule Retention Worker
        scheduleRetentionWorker()

        // Request Notification Permission (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        // Initialize Opening Library
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            com.github.movesense.data.OpeningLibrary.initialize(applicationContext)
        }
    }

    private fun scheduleRetentionWorker() {
        val now = java.util.Calendar.getInstance()
        val target = java.util.Calendar.getInstance().apply {
            if (get(java.util.Calendar.HOUR_OF_DAY) >= 12) {
                add(java.util.Calendar.DAY_OF_YEAR, 1)
            }
            set(java.util.Calendar.HOUR_OF_DAY, 12)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }

        val initialDelay = target.timeInMillis - now.timeInMillis

        val workRequest = PeriodicWorkRequestBuilder<RetentionWorker>(24, TimeUnit.HOURS)
            .setInitialDelay(initialDelay, TimeUnit.MILLISECONDS)
            .build()

        WorkManager.getInstance(applicationContext).enqueueUniquePeriodicWork(
            RetentionWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            workRequest
        )
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