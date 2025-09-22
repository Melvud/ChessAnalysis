@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.example.chessanalysis.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.chessanalysis.data.model.ChessSite
import com.example.chessanalysis.data.model.GameSummary
import com.example.chessanalysis.data.repository.GameRepository
import com.example.chessanalysis.viewmodel.GameListViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.chessanalysis.data.api.ApiClient

private fun tag(pgn: String, key: String): String? {
    // [Key "Value"]
    val re = Regex("\\[$key\\s+\"([^\"]+)\"\\]")
    return re.find(pgn)?.groupValues?.getOrNull(1)
}

@Composable
fun GameListScreen(
    site: ChessSite,
    username: String,
    onGameSelected: (GameSummary) -> Unit
) {
    val vm: GameListViewModel = viewModel(factory = viewModelFactory {
        initializer {
            GameListViewModel(
                GameRepository(
                    ApiClient.lichessService,
                    ApiClient.chessComService,
                    ApiClient.stockfishOnlineService
                )
            )
        }
    })

    val games by vm.games.collectAsState()
    val loading by vm.loading.collectAsState()
    val error by vm.error.collectAsState()

    LaunchedEffect(site, username) { vm.loadGames(site, username) }

    Scaffold(topBar = { TopAppBar(title = { Text("Ваши последние партии") }) }) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                error != null -> Text("Ошибка: $error", Modifier.align(Alignment.Center))
                games.isEmpty() -> Text("Ничего не найдено", Modifier.align(Alignment.Center))
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(games) { g ->
                        // Фолбэк для имён из PGN
                        val whiteName = when {
                            !g.white.isNullOrBlank() -> g.white
                            else -> tag(g.pgn, "White") ?: "White"
                        }
                        val blackName = when {
                            !g.black.isNullOrBlank() -> g.black
                            else -> tag(g.pgn, "Black") ?: "Black"
                        }

                        ElevatedCard(
                            modifier = Modifier.fillMaxWidth().clickable { onGameSelected(g) }
                        ) {
                            Column(Modifier.padding(12.dp)) {
                                Text("$whiteName — $blackName", style = MaterialTheme.typography.titleMedium)
                                g.result?.let { Text("Результат: $it") }
                                g.timeControl?.let { Text("Контроль: $it") }
                            }
                        }
                    }
                }
            }
        }
    }
}
