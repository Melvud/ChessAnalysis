package com.example.chessanalysis.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.chessanalysis.FullReport
import com.example.chessanalysis.PgnChess
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class GameReportUiState(
    val currentPlyIndex: Int = 0,
    val currentFen: String = "",
    val lastMove: Pair<String, String>? = null,
    val showArrows: Boolean = true
)

sealed interface GameReportIntent {
    data object GoStart : GameReportIntent
    data object GoPrev : GameReportIntent
    data object GoNext : GameReportIntent
    data object GoEnd : GameReportIntent
    data class SeekTo(val plyIndex: Int) : GameReportIntent
    data class ToggleArrows(val enabled: Boolean) : GameReportIntent
}

class GameReportViewModel(
    private val report: FullReport
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        GameReportUiState(
            currentPlyIndex = 0,
            currentFen = report.positions.getOrNull(0)?.fen ?: "",
            lastMove = null,
            showArrows = true
        )
    )
    val uiState: StateFlow<GameReportUiState> = _uiState.asStateFlow()

    fun handleIntent(intent: GameReportIntent) {
        when (intent) {
            is GameReportIntent.GoStart -> {
                updatePlyIndex(0)
            }
            is GameReportIntent.GoPrev -> {
                val newIndex = (_uiState.value.currentPlyIndex - 1).coerceAtLeast(0)
                updatePlyIndex(newIndex)
            }
            is GameReportIntent.GoNext -> {
                val newIndex = (_uiState.value.currentPlyIndex + 1).coerceAtMost(report.positions.lastIndex)
                updatePlyIndex(newIndex)
            }
            is GameReportIntent.GoEnd -> {
                updatePlyIndex(report.positions.lastIndex)
            }
            is GameReportIntent.SeekTo -> {
                val clampedIndex = intent.plyIndex.coerceIn(0, report.positions.lastIndex)
                updatePlyIndex(clampedIndex)
            }
            is GameReportIntent.ToggleArrows -> {
                _uiState.value = _uiState.value.copy(showArrows = intent.enabled)
            }
        }
    }

    private fun updatePlyIndex(newIndex: Int) {
        if (newIndex == _uiState.value.currentPlyIndex) return

        val newFen = report.positions.getOrNull(newIndex)?.fen ?: ""
        val lastMove = if (newIndex > 0) {
            val move = report.moves[newIndex - 1]
            val uci = move.uci
            if (uci.length >= 4) {
                Pair(uci.substring(0, 2), uci.substring(2, 4))
            } else null
        } else null

        _uiState.value = _uiState.value.copy(
            currentPlyIndex = newIndex,
            currentFen = newFen,
            lastMove = lastMove
        )
    }
}
