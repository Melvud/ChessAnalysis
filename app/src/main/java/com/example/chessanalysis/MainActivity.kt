package com.example.chessanalysis

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.chessanalysis.data.StockfishApiV2
import com.example.chessanalysis.repository.*
import com.example.chessanalysis.ui.AppNav

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val repo = GameRepository(
            lichess = ApiClient.lichessService,
            chessCom = ApiClient.chessComService,
            chessApi = ApiClient.chessApiService
        )
        val listVm = GameListViewModel(repo)
        val analysisVm = AnalysisViewModel(repo)

        setContent {
            AppNav(listVm, analysisVm)
        }
    }
}
