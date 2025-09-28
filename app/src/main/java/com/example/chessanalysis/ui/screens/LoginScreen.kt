package com.example.chessanalysis.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.chessanalysis.ui.AppRoot
import com.example.chessanalysis.ui.UserProfile
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

@Composable
fun LoginScreen(
    onLoginSuccess: (UserProfile) -> Unit,
    onRegisterSuccess: (UserProfile) -> Unit
) {
    var isLoginMode by remember { mutableStateOf(true) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var nickname by remember { mutableStateOf("") }
    var lichessName by remember { mutableStateOf("") }
    var chessName by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(if (isLoginMode) "Вход" else "Регистрация")

        // Поля ввода
        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp),
            singleLine = true,
            label = { Text("Email") }
        )
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp),
            singleLine = true,
            label = { Text("Пароль") },
            visualTransformation = PasswordVisualTransformation()
        )
        if (!isLoginMode) {
            OutlinedTextField(
                value = nickname,
                onValueChange = { nickname = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
                singleLine = true,
                label = { Text("Имя в приложении") }
            )
            OutlinedTextField(
                value = lichessName,
                onValueChange = { lichessName = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
                singleLine = true,
                label = { Text("Ник на Lichess") }
            )
            OutlinedTextField(
                value = chessName,
                onValueChange = { chessName = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
                singleLine = true,
                label = { Text("Ник на Chess.com") }
            )
        }

        // Сообщение об ошибке (если есть)
        if (errorMessage != null) {
            Text(
                text = errorMessage!!,
                modifier = Modifier.padding(top = 12.dp),
                color = androidx.compose.material3.MaterialTheme.colorScheme.error
            )
        }

        // Кнопка отправки
        Button(
            enabled = !isLoading && email.isNotBlank() && password.isNotBlank() && (isLoginMode || nickname.isNotBlank()),
            onClick = {
                // Скрываем предыдущее сообщение об ошибке
                errorMessage = null
                isLoading = true
                if (isLoginMode) {
                    // Вход в аккаунт
                    FirebaseAuth.getInstance()
                        .signInWithEmailAndPassword(email.trim(), password)
                        .addOnCompleteListener { task ->
                            isLoading = false
                            if (task.isSuccessful) {
                                val firebaseUser = task.result?.user
                                if (firebaseUser != null) {
                                    // Загружаем профиль пользователя из Firestore
                                    FirebaseFirestore.getInstance()
                                        .collection("users")
                                        .document(firebaseUser.uid)
                                        .get()
                                        .addOnSuccessListener { doc ->
                                            val profile = UserProfile(
                                                email = email.trim(),
                                                nickname = doc.getString("nickname") ?: "",
                                                lichessUsername = doc.getString("lichessUsername") ?: "",
                                                chessUsername = doc.getString("chessUsername") ?: ""
                                            )
                                            onLoginSuccess(profile)
                                        }
                                        .addOnFailureListener {
                                            errorMessage = "Ошибка загрузки данных профиля"
                                        }
                                }
                            } else {
                                errorMessage = "Ошибка входа: ${task.exception?.message ?: "неизвестно"}"
                            }
                        }
                } else {
                    // Регистрация нового аккаунта
                    FirebaseAuth.getInstance()
                        .createUserWithEmailAndPassword(email.trim(), password)
                        .addOnCompleteListener { task ->
                            isLoading = false
                            if (task.isSuccessful) {
                                val firebaseUser = task.result?.user
                                if (firebaseUser != null) {
                                    // Сохраняем профиль пользователя в Firestore
                                    val profileData = hashMapOf(
                                        "nickname" to nickname.trim(),
                                        "lichessUsername" to lichessName.trim(),
                                        "chessUsername" to chessName.trim()
                                    )
                                    FirebaseFirestore.getInstance()
                                        .collection("users")
                                        .document(firebaseUser.uid)
                                        .set(profileData)
                                        .addOnSuccessListener {
                                            val profile = UserProfile(
                                                email = email.trim(),
                                                nickname = nickname.trim(),
                                                lichessUsername = lichessName.trim(),
                                                chessUsername = chessName.trim()
                                            )
                                            onRegisterSuccess(profile)
                                        }
                                        .addOnFailureListener {
                                            errorMessage = "Ошибка сохранения профиля"
                                        }
                                }
                            } else {
                                errorMessage = "Ошибка регистрации: ${task.exception?.message ?: "неизвестно"}"
                            }
                        }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 20.dp)
        ) {
            if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.padding(4.dp))
            } else {
                Text(if (isLoginMode) "Войти" else "Зарегистрироваться")
            }
        }

        // Кнопка переключения режима (вход/регистрация)
        TextButton(
            onClick = {
                isLoginMode = !isLoginMode
                errorMessage = null  // сбрасываем сообщения об ошибках при смене режима
            },
            modifier = Modifier.padding(top = 8.dp)
        ) {
            Text(
                if (isLoginMode) "Нет аккаунта? Зарегистрируйтесь"
                else "Уже есть аккаунт? Войти"
            )
        }
    }
}
