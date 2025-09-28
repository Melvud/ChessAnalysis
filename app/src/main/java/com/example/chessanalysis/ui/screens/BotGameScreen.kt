package com.example.chessanalysis.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BotGameScreen(
    onStart: (BotConfig) -> Unit
) {
    var skill by remember { mutableStateOf(12f) }             // 1..20 — НАСТОЯЩИЙ SKILL
    var side by remember { mutableStateOf(BotSide.RANDOM) }
    var hints by remember { mutableStateOf(true) }
    var showLines by remember { mutableStateOf(true) }

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
            Text("Сложность (Skill): ${skill.toInt()}")

            Slider(
                value = skill,
                onValueChange = { skill = it },
                valueRange = 1f..20f,
                steps = 18 // по 1
            )

            Text("Цвет фигуры")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = side == BotSide.WHITE, onClick = { side = BotSide.WHITE }, label = { Text("Белые") })
                FilterChip(selected = side == BotSide.BLACK, onClick = { side = BotSide.BLACK }, label = { Text("Чёрные") })
                FilterChip(selected = side == BotSide.RANDOM, onClick = { side = BotSide.RANDOM }, label = { Text("Случайно") })
            }

            Divider()

            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = hints, onCheckedChange = { hints = it })
                Spacer(Modifier.width(8.dp)); Text("Подсказки (зелёная стрелка)")
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = showLines, onCheckedChange = { showLines = it })
                Spacer(Modifier.width(8.dp)); Text("Показывать топ-3 линии")
            }

            Spacer(Modifier.weight(1f))

            Button(
                onClick = {
                    onStart(
                        BotConfig(
                            skill = skill.toInt(),
                            side = side,
                            hints = hints,
                            showLines = showLines,
                            multiPv = if (showLines) 3 else 1
                        )
                    )
                },
                modifier = Modifier.fillMaxWidth().height(56.dp)
            ) { Text("Играть") }
        }
    }
}
