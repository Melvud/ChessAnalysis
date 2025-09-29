package com.example.chessanalysis.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.chessanalysis.ui.UserProfile
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    profile: UserProfile,
    onSave: (UserProfile) -> Unit,
    onLogout: () -> Unit,
    onBack: () -> Unit
) {
    var email by remember { mutableStateOf(profile.email) }
    var nickname by remember { mutableStateOf(profile.nickname) }
    var lichessName by remember { mutableStateOf(profile.lichessUsername) }
    var chessName by remember { mutableStateOf(profile.chessUsername) }
    var isSaving by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // Лямбда сохранения в Firestore (объявлена до использования)
    val saveProfileToFirestore: (String) -> Unit = { uid ->
        val data = mapOf(
            "nickname" to nickname.trim(),
            "lichessUsername" to lichessName.trim(),
            "chessUsername" to chessName.trim()
        )
        FirebaseFirestore.getInstance().collection("users").document(uid)
            .update(data)
            .addOnSuccessListener {
                isSaving = false
                val updatedProfile = UserProfile(
                    email = email.trim(),
                    nickname = nickname.trim(),
                    lichessUsername = lichessName.trim(),
                    chessUsername = chessName.trim()
                )
                onSave(updatedProfile)
            }
            .addOnFailureListener { e ->
                isSaving = false
                errorMessage = "Ошибка при сохранении: ${e.message ?: "неизвестно"}"
            }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Профиль") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Назад")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(20.dp),
            verticalArrangement = Arrangement.Top
        ) {
            OutlinedTextField(
                value = email,
                onValueChange = { email = it },
                label = { Text("Email") },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = nickname,
                onValueChange = { nickname = it },
                label = { Text("Имя в приложении") },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
            )
            OutlinedTextField(
                value = lichessName,
                onValueChange = { lichessName = it },
                label = { Text("Ник на Lichess") },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
            )
            OutlinedTextField(
                value = chessName,
                onValueChange = { chessName = it },
                label = { Text("Ник на Chess.com") },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
            )

            if (errorMessage != null) {
                Text(
                    text = errorMessage!!,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            Button(
                onClick = {
                    // Проверяем обязательные поля
                    if (email.trim().isEmpty() || nickname.trim().isEmpty()) {
                        errorMessage = "Email и имя не должны быть пустыми"
                    } else {
                        errorMessage = null
                        isSaving = true
                        val user = FirebaseAuth.getInstance().currentUser
                        val uid = user?.uid
                        if (user != null && uid != null) {
                            val newEmail = email.trim()
                            if (newEmail != user.email) {
                                user.updateEmail(newEmail).addOnCompleteListener { task ->
                                    if (!task.isSuccessful) {
                                        errorMessage = "Не удалось обновить Email: ${task.exception?.message ?: ""}"
                                        isSaving = false
                                    } else {
                                        // Email обновлён — сохраняем профиль
                                        saveProfileToFirestore(uid)
                                    }
                                }
                            } else {
                                // Email не менялся — сразу сохраняем профиль
                                saveProfileToFirestore(uid)
                            }
                        } else {
                            // Пользователь не определён
                            errorMessage = "Не удалось определить пользователя"
                            isSaving = false
                        }
                    }
                },
                enabled = !isSaving,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp)
            ) {
                if (isSaving) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Text("Сохранить")
                }
            }

            TextButton(
                onClick = { if (!isSaving) onLogout() },
                modifier = Modifier.padding(top = 12.dp)
            ) {
                Text("Выйти из аккаунта")
            }
        }
    }
}
