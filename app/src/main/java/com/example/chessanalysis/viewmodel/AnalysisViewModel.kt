package com.example.chessanalysis.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.chessanalysis.data.model.AnalysisResult
import com.example.chessanalysis.data.model.GameSummary
import com.example.chessanalysis.data.repository.GameRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class AnalysisViewModel(private val repository: GameRepository) : ViewModel() {
    private val _analysisResult = MutableStateFlow<AnalysisResult?>(null)
    val analysisResult: StateFlow<AnalysisResult?> = _analysisResult
    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    // Stockfish Online: max depth ≈ 15
    fun analyze(game: GameSummary, depth: Int = 15) {
        viewModelScope.launch {
            _loading.value = true
            _error.value = null
            try {
                _analysisResult.value = repository.analyzeGame(game, depth)
            } catch (e: Exception) {
                _error.value = e.message
            } finally {
                _loading.value = false
            }
        }
    }
}
