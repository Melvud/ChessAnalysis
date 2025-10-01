package com.example.chessanalysis

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.example.chessanalysis.ui.AppRoot

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // На всякий случай дублируем установку контекста
        EngineClient.setAndroidContext(applicationContext)

        setContent { AppRoot() }
    }
}