package com.example.chessanalysis.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.chessanalysis.Provider

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(
    isLoading: Boolean,
    onSubmit: (provider: Provider, username: String) -> Unit
) {
    var username by remember { mutableStateOf("") }
    var selectedProvider by remember { mutableStateOf(Provider.LICHESS) }

    val options = listOf(Provider.LICHESS, Provider.CHESSCOM)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Выберите провайдера и введите ник")

        SingleChoiceSegmentedButtonRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp)
        ) {
            options.forEachIndexed { index, item ->
                SegmentedButton(
                    selected = selectedProvider == item,
                    onClick = { selectedProvider = item },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        when (item) {
                            Provider.LICHESS -> "Lichess"
                            Provider.CHESSCOM -> "Chess.com"
                            else -> item.name
                        }
                    )
                }
            }
        }

        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp),
            singleLine = true,
            label = { Text("Username") }
        )

        Button(
            enabled = !isLoading && username.isNotBlank(),
            onClick = { onSubmit(selectedProvider, username.trim()) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp)
        ) {
            if (isLoading) {
                CircularProgressIndicator()
            } else {
                Text("Загрузить партии")
            }
        }
    }
}
