package com.example.chessanalysis.repository

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.chessanalysis.data.model.*
import com.example.chessanalysis.engine.StockfishAnalyzer
import com.example.chessanalysis.opening.OpeningBook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Ничего функционально не меняем — просто используем новый движок.
 */
class AnalysisViewModel : ViewModel() {

    private val analyzer = StockfishAnalyzer(depth = 14)

    private val _analysisResult = MutableStateFlow<AnalysisResult?>(null)
    val analysisResult = _analysisResult.asStateFlow()

    val loading = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)

    fun analyze(summary: GameSummary) {
        loading.value = true
        error.value = null
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Получаем распарсенную игру (PGN -> ParsedGame). Предполагается, что у вас это уже есть.
                val parsed = ParsedGame.fromSummary(summary).copy(
                    opening = OpeningBook.match(summary.pgnMoves) // если есть книга дебютов — добавим
                )
                val result = analyzer.analyze(parsed)
                _analysisResult.value = result
            } catch (t: Throwable) {
                error.value = t.message
            } finally {
                loading.value = false
            }
        }
    }
}
