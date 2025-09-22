package com.example.chessanalysis.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.chessanalysis.data.model.ChessSite
import com.example.chessanalysis.data.model.GameSummary
import com.example.chessanalysis.data.repository.GameRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class GameListViewModel(private val repository: GameRepository) : ViewModel() {
    private val _games = MutableStateFlow<List<GameSummary>>(emptyList())
    val games: StateFlow<List<GameSummary>> = _games
    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    fun loadGames(site: ChessSite, username: String) {
        viewModelScope.launch {
            _loading.value = true
            _error.value = null
            try {
                _games.value = repository.loadGames(site, username) // ← было fetchGames
            } catch (e: Exception) {
                _error.value = e.message
            } finally {
                _loading.value = false
            }
        }
    }
}
