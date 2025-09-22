package com.example.chessanalysis.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.example.chessanalysis.data.model.ChessSite

@Composable
fun LoginScreen(onLogin: (ChessSite, String) -> Unit) {
    var username by remember { mutableStateOf(TextFieldValue("")) }
    var site by remember { mutableStateOf(ChessSite.LICHESS) }

    Box(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.Center)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Вход", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilterChip(
                    selected = site == ChessSite.LICHESS,
                    onClick = { site = ChessSite.LICHESS },
                    label = { Text("Lichess") }
                )
                Spacer(Modifier.width(12.dp))
                FilterChip(
                    selected = site == ChessSite.CHESS_COM,
                    onClick = { site = ChessSite.CHESS_COM },
                    label = { Text("Chess.com") }
                )
            }
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                label = { Text("Никнейм") },
                singleLine = true
            )
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = { onLogin(site, username.text.trim()) },
                enabled = username.text.isNotBlank()
            ) { Text("Загрузить партии") }
        }
    }
}
