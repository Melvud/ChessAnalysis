package com.example.chessanalysis.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.chessanalysis.ui.screens.bot.BotConfig
import com.example.chessanalysis.ui.screens.bot.BotSide

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BotGameScreen(
    onStart: (BotConfig) -> Unit
) {
    var elo by remember { mutableStateOf(1350f) }
    var side by remember { mutableStateOf(BotSide.AUTO) }
    var hints by remember { mutableStateOf(false) }
    var showMultiPv by remember { mutableStateOf(false) }
    var showEvalBar by remember { mutableStateOf(false) }
    var allowUndo by remember { mutableStateOf(false) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Игра с ботом") }) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Text("Эло бота: ${elo.toInt()}")
            Slider(
                value = elo,
                onValueChange = { elo = it },
                valueRange = 800f..2200f,
                steps = ((2200 - 800) / 50) - 1
            )

            Text("Цвет фигуры")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = side == BotSide.WHITE, onClick = { side = BotSide.WHITE }, label = { Text("Белые") })
                FilterChip(selected = side == BotSide.BLACK, onClick = { side = BotSide.BLACK }, label = { Text("Чёрные") })
                FilterChip(selected = side == BotSide.AUTO,  onClick = { side = BotSide.AUTO },  label = { Text("Случайно") })
            }

            Divider()

            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = hints, onCheckedChange = { hints = it })
                Spacer(Modifier.width(8.dp)); Text("Подсказки (лучший ход)")
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = showMultiPv, onCheckedChange = { showMultiPv = it })
                Spacer(Modifier.width(8.dp)); Text("Показывать топ-3 линии")
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = showEvalBar, onCheckedChange = { showEvalBar = it })
                Spacer(Modifier.width(8.dp)); Text("Показывать шкалу оценки")
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = allowUndo, onCheckedChange = { allowUndo = it })
                Spacer(Modifier.width(8.dp)); Text("Разрешить «Вернуть ход»")
            }

            Spacer(Modifier.weight(1f))

            Button(
                onClick = {
                    onStart(
                        BotConfig(
                            elo = elo.toInt(),
                            side = side,
                            hints = hints,
                            showMultiPv = showMultiPv,
                            showEvalBar = showEvalBar,
                            allowUndo = allowUndo
                        )
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
            ) { Text("Играть") }
        }
    }
}
