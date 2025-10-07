package com.example.chessanalysis.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.chessanalysis.R
import com.example.chessanalysis.ui.UserProfile
import com.example.chessanalysis.util.LocaleManager
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(
    onLoginSuccess: (UserProfile) -> Unit,
    onRegisterSuccess: (UserProfile) -> Unit
) {
    val context = LocalContext.current
    var isLoginMode by remember { mutableStateOf(true) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var nickname by remember { mutableStateOf("") }
    var lichessName by remember { mutableStateOf("") }
    var chessName by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var passwordVisible by remember { mutableStateOf(false) }

    // Цвета для темной темы
    val bgColor = Color(0xFF1A1A1A)
    val cardColor = Color(0xFF2D2D2D)
    val textColor = Color(0xFFE0E0E0)
    val primaryColor = Color(0xFF4CAF50)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(bgColor)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Логотип/Заголовок
            Text(
                text = stringResource(R.string.app_name),
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                color = primaryColor,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            Text(
                text = stringResource(R.string.welcome),
                fontSize = 18.sp,
                color = textColor.copy(alpha = 0.7f),
                modifier = Modifier.padding(bottom = 32.dp)
            )

            // Карточка с формой
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = cardColor),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Заголовок режима
                    Text(
                        text = if (isLoginMode) {
                            stringResource(R.string.login)
                        } else {
                            stringResource(R.string.register)
                        },
                        fontSize = 24.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = textColor,
                        modifier = Modifier.padding(bottom = 24.dp)
                    )

                    // Email
                    OutlinedTextField(
                        value = email,
                        onValueChange = { email = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text(stringResource(R.string.email)) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = textColor,
                            unfocusedTextColor = textColor,
                            focusedBorderColor = primaryColor,
                            unfocusedBorderColor = textColor.copy(alpha = 0.5f),
                            focusedLabelColor = primaryColor,
                            unfocusedLabelColor = textColor.copy(alpha = 0.7f),
                            cursorColor = primaryColor
                        )
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // Password
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text(stringResource(R.string.password)) },
                        visualTransformation = if (passwordVisible) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                        trailingIcon = {
                            IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                Icon(
                                    imageVector = if (passwordVisible) {
                                        Icons.Default.Visibility
                                    } else {
                                        Icons.Default.VisibilityOff
                                    },
                                    contentDescription = if (passwordVisible) {
                                        stringResource(R.string.hide_password)
                                    } else {
                                        stringResource(R.string.show_password)
                                    },
                                    tint = textColor.copy(alpha = 0.7f)
                                )
                            }
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = textColor,
                            unfocusedTextColor = textColor,
                            focusedBorderColor = primaryColor,
                            unfocusedBorderColor = textColor.copy(alpha = 0.5f),
                            focusedLabelColor = primaryColor,
                            unfocusedLabelColor = textColor.copy(alpha = 0.7f),
                            cursorColor = primaryColor
                        )
                    )

                    // Дополнительные поля при регистрации
                    if (!isLoginMode) {
                        Spacer(modifier = Modifier.height(16.dp))

                        OutlinedTextField(
                            value = nickname,
                            onValueChange = { nickname = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text(stringResource(R.string.nickname)) },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = textColor,
                                unfocusedTextColor = textColor,
                                focusedBorderColor = primaryColor,
                                unfocusedBorderColor = textColor.copy(alpha = 0.5f),
                                focusedLabelColor = primaryColor,
                                unfocusedLabelColor = textColor.copy(alpha = 0.7f),
                                cursorColor = primaryColor
                            )
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        OutlinedTextField(
                            value = lichessName,
                            onValueChange = { lichessName = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text(stringResource(R.string.lichess_username)) },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = textColor,
                                unfocusedTextColor = textColor,
                                focusedBorderColor = primaryColor,
                                unfocusedBorderColor = textColor.copy(alpha = 0.5f),
                                focusedLabelColor = primaryColor,
                                unfocusedLabelColor = textColor.copy(alpha = 0.7f),
                                cursorColor = primaryColor
                            )
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        OutlinedTextField(
                            value = chessName,
                            onValueChange = { chessName = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text(stringResource(R.string.chesscom_username)) },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = textColor,
                                unfocusedTextColor = textColor,
                                focusedBorderColor = primaryColor,
                                unfocusedBorderColor = textColor.copy(alpha = 0.5f),
                                focusedLabelColor = primaryColor,
                                unfocusedLabelColor = textColor.copy(alpha = 0.7f),
                                cursorColor = primaryColor
                            )
                        )
                    }

                    // Сообщение об ошибке
                    if (errorMessage != null) {
                        Spacer(modifier = Modifier.height(16.dp))
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer
                            ),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = errorMessage!!,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.padding(12.dp),
                                fontSize = 14.sp,
                                textAlign = TextAlign.Center
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    // Кнопка отправки
                    Button(
                        enabled = !isLoading && email.isNotBlank() && password.isNotBlank() &&
                                (isLoginMode || nickname.isNotBlank()),
                        onClick = {
                            errorMessage = null
                            isLoading = true

                            if (isLoginMode) {
                                // Вход
                                FirebaseAuth.getInstance()
                                    .signInWithEmailAndPassword(email.trim(), password)
                                    .addOnCompleteListener { task ->
                                        isLoading = false
                                        if (task.isSuccessful) {
                                            val firebaseUser = task.result?.user
                                            if (firebaseUser != null) {
                                                FirebaseFirestore.getInstance()
                                                    .collection("users")
                                                    .document(firebaseUser.uid)
                                                    .get()
                                                    .addOnSuccessListener { doc ->
                                                        val profile = UserProfile(
                                                            email = email.trim(),
                                                            nickname = doc.getString("nickname") ?: "",
                                                            lichessUsername = doc.getString("lichessUsername") ?: "",
                                                            chessUsername = doc.getString("chessUsername") ?: "",
                                                            language = doc.getString("language") ?: "ru"
                                                        )
                                                        // Применяем сохраненный язык
                                                        LocaleManager.setLocale(
                                                            context,
                                                            LocaleManager.Language.fromCode(profile.language)
                                                        )
                                                        onLoginSuccess(profile)
                                                    }
                                                    .addOnFailureListener {
                                                        errorMessage = context.getString(
                                                            R.string.profile_load_error
                                                        )
                                                    }
                                            }
                                        } else {
                                            errorMessage = context.getString(
                                                R.string.login_error,
                                                task.exception?.message ?: context.getString(R.string.unknown)
                                            )
                                        }
                                    }
                            } else {
                                // Регистрация
                                FirebaseAuth.getInstance()
                                    .createUserWithEmailAndPassword(email.trim(), password)
                                    .addOnCompleteListener { task ->
                                        isLoading = false
                                        if (task.isSuccessful) {
                                            val firebaseUser = task.result?.user
                                            if (firebaseUser != null) {
                                                val profileData = hashMapOf(
                                                    "nickname" to nickname.trim(),
                                                    "lichessUsername" to lichessName.trim(),
                                                    "chessUsername" to chessName.trim(),
                                                    "language" to "ru"
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
                                                            chessUsername = chessName.trim(),
                                                            language = "ru"
                                                        )
                                                        onRegisterSuccess(profile)
                                                    }
                                                    .addOnFailureListener {
                                                        errorMessage = context.getString(
                                                            R.string.profile_save_error
                                                        )
                                                    }
                                            }
                                        } else {
                                            errorMessage = context.getString(
                                                R.string.register_error,
                                                task.exception?.message ?: context.getString(R.string.unknown)
                                            )
                                        }
                                    }
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = primaryColor,
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                color = Color.White,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text(
                                text = if (isLoginMode) {
                                    stringResource(R.string.login)
                                } else {
                                    stringResource(R.string.register)
                                },
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Кнопка переключения режима
                    TextButton(
                        onClick = {
                            isLoginMode = !isLoginMode
                            errorMessage = null
                        }
                    ) {
                        Text(
                            text = if (isLoginMode) {
                                stringResource(R.string.no_account)
                            } else {
                                stringResource(R.string.have_account)
                            },
                            color = primaryColor,
                            fontSize = 14.sp
                        )
                    }
                }
            }
        }
    }
}