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
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.chessanalysis.data.api.ApiClient
import com.example.chessanalysis.data.model.ChessSite
import com.example.chessanalysis.data.model.GameSummary
import com.example.chessanalysis.data.repository.GameRepository
import com.example.chessanalysis.viewmodel.GameListViewModel

@Composable
fun GameListScreen(
    site: ChessSite,
    username: String,
    onGameSelected: (GameSummary) -> Unit
) {
    // ViewModel with repository injection (includes chessApiService for analyzer)
    val viewModel: GameListViewModel = viewModel(factory = viewModelFactory {
        initializer {
            GameListViewModel(
                GameRepository(
                    ApiClient.lichessService,
                    ApiClient.chessComService,
                    ApiClient.stockfishOnlineService,
                    ApiClient.chessApiService
                )
            )
        }
    })

    val games by viewModel.games.collectAsState()
    val loading by viewModel.loading.collectAsState()
    val error by viewModel.error.collectAsState()

    // Trigger load when inputs change
    LaunchedEffect(site, username) {
        viewModel.loadGames(site, username)
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Ваши последние партии") })
        }
    ) { paddingValues ->
        Box(
            Modifier
                .padding(paddingValues)
                .fillMaxSize()
        ) {
            when {
                loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                error != null -> Text("Ошибка: $error", Modifier.align(Alignment.Center))
                games.isEmpty() -> Text("Ничего не найдено", Modifier.align(Alignment.Center))
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(games) { game ->
                        ElevatedCard(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onGameSelected(game) }
                        ) {
                            Column(Modifier.padding(12.dp)) {
                                // Display players' names (fallback to PGN tags if missing)
                                Text("${game.white} — ${game.black}", style = MaterialTheme.typography.titleMedium)
                                game.result?.let { Text("Результат: $it", style = MaterialTheme.typography.bodyMedium) }
                                game.timeControl?.let { Text("Контроль: $it", style = MaterialTheme.typography.bodyMedium) }
                            }
                        }
                    }
                }
            }
        }
    }
}
