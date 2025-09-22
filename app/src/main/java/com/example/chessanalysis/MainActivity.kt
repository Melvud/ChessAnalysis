package com.example.chessanalysis

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.navigation.compose.rememberNavController
import com.example.chessanalysis.ui.AppNavGraph
import com.example.chessanalysis.ui.theme.ChessAnalyzerTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ChessAnalyzerTheme {
                val navController = rememberNavController()
                AppNavGraph(navController)
            }
        }
    }
}
