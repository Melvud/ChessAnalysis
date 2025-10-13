package com.github.movesense

import android.content.Context
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.os.PowerManager
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.github.movesense.ui.AppRoot
import com.github.movesense.util.LocaleManager
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Вызываем suspend-функцию в корутине
        CoroutineScope(Dispatchers.Default).launch {
            EngineClient.setAndroidContext(applicationContext)
        }

        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // КРИТИЧНО: Держим CPU активным для анализа
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "ChessAnalysis::AnalysisWakeLock"
        )
        try {
            val governor = File("/sys/devices/system/cpu/cpu0/cpufreq/scaling_governor")
                .readText().trim()
            Log.i("CPU", "Current governor: $governor")
            // Должно быть "performance" или "ondemand", НЕ "powersave"
        } catch (e: Exception) {
            Log.e("CPU", "Cannot read governor", e)
        }

        setContent { AppRoot() }
    }

    override fun onResume() {
        super.onResume()
        wakeLock?.acquire(10*60*1000L /*10 minutes*/)
    }

    override fun onPause() {
        super.onPause()
        wakeLock?.release()
    }
}