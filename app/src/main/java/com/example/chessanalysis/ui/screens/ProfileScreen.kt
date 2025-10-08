package com.example.chessanalysis.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.chessanalysis.R
import com.example.chessanalysis.ui.UserProfile
import com.example.chessanalysis.EngineClient
import com.example.chessanalysis.util.LocaleManager
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    profile: UserProfile,
    onSave: (UserProfile) -> Unit,
    onLogout: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var email by remember { mutableStateOf(profile.email) }
    var nickname by remember { mutableStateOf(profile.nickname) }
    var lichessName by remember { mutableStateOf(profile.lichessUsername) }
    var chessName by remember { mutableStateOf(profile.chessUsername) }
    var selectedLanguage by remember {
        mutableStateOf(LocaleManager.Language.fromCode(profile.language))
    }
    var showLanguageDialog by remember { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val engineMode by EngineClient.engineMode.collectAsState()

    // Функция для проверки существования пользователя на Lichess
    suspend fun checkLichessUserExists(username: String): Boolean = withContext(Dispatchers.IO) {
        if (username.isBlank()) return@withContext true // пустой ник считаем валидным
        try {
            val client = OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build()

            val request = Request.Builder()
                .url("https://lichess.org/api/user/$username")
                .get()
                .build()

            val response = client.newCall(request).execute()
            response.isSuccessful
        } catch (e: Exception) {
            false
        }
    }

    // Функция для проверки существования пользователя на Chess.com
    suspend fun checkChessComUserExists(username: String): Boolean = withContext(Dispatchers.IO) {
        if (username.isBlank()) return@withContext true // пустой ник считаем валидным
        try {
            val client = OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build()

            val request = Request.Builder()
                .url("https://api.chess.com/pub/player/$username")
                .get()
                .build()

            val response = client.newCall(request).execute()
            response.isSuccessful
        } catch (e: Exception) {
            false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.profile)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Email
            OutlinedTextField(
                value = email,
                onValueChange = { email = it },
                label = { Text(stringResource(R.string.email)) },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isSaving
            )

            // Nickname
            OutlinedTextField(
                value = nickname,
                onValueChange = { nickname = it },
                label = { Text(stringResource(R.string.nickname)) },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isSaving
            )

            // Lichess Username
            OutlinedTextField(
                value = lichessName,
                onValueChange = { lichessName = it },
                label = { Text(stringResource(R.string.lichess_username)) },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isSaving
            )

            // Chess.com Username
            OutlinedTextField(
                value = chessName,
                onValueChange = { chessName = it },
                label = { Text(stringResource(R.string.chesscom_username)) },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isSaving
            )

            // Language Selector
            OutlinedTextField(
                value = getLanguageDisplayName(selectedLanguage),
                onValueChange = {},
                label = { Text(stringResource(R.string.language)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !isSaving) { showLanguageDialog = true },
                enabled = false,
                trailingIcon = {
                    Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                },
                colors = TextFieldDefaults.colors(
                    disabledTextColor = MaterialTheme.colorScheme.onSurface,
                    disabledContainerColor = MaterialTheme.colorScheme.surface
                )
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Engine Mode Selection
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Text(
                        text = stringResource(R.string.engine_mode),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    // Server Mode (DISABLED - только показываем, но нельзя выбрать)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp)
                    ) {
                        RadioButton(
                            selected = engineMode == EngineClient.EngineMode.SERVER,
                            onClick = null, // Нельзя нажать
                            enabled = false // Заблокировано
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = stringResource(R.string.engine_server),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                            )
                            Text(
                                text = stringResource(R.string.engine_server_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Local Mode (единственный доступный вариант)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !isSaving) {
                                scope.launch {
                                    try {
                                        EngineClient.setAndroidContext(context.applicationContext)
                                        EngineClient.setEngineMode(EngineClient.EngineMode.LOCAL)
                                    } catch (e: Exception) {
                                        errorMessage = context.getString(
                                            R.string.switch_error,
                                            e.message ?: ""
                                        )
                                    }
                                }
                            }
                            .padding(vertical = 8.dp)
                    ) {
                        RadioButton(
                            selected = engineMode == EngineClient.EngineMode.LOCAL,
                            onClick = {
                                scope.launch {
                                    try {
                                        EngineClient.setAndroidContext(context.applicationContext)
                                        EngineClient.setEngineMode(EngineClient.EngineMode.LOCAL)
                                    } catch (e: Exception) {
                                        errorMessage = context.getString(
                                            R.string.switch_error,
                                            e.message ?: ""
                                        )
                                    }
                                }
                            },
                            enabled = !isSaving
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = stringResource(R.string.engine_local),
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Text(
                                text = stringResource(R.string.engine_local_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // Error Message
            if (errorMessage != null) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Text(
                        text = errorMessage!!,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Save Button
            Button(
                onClick = {
                    if (email.trim().isEmpty() || nickname.trim().isEmpty()) {
                        errorMessage = context.getString(R.string.empty_fields_error)
                    } else {
                        errorMessage = null
                        isSaving = true

                        scope.launch {
                            try {
                                // Проверка существования никнеймов
                                val lichessTrimed = lichessName.trim()
                                val chessTrimed = chessName.trim()

                                if (lichessTrimed.isNotEmpty()) {
                                    val lichessExists = checkLichessUserExists(lichessTrimed)
                                    if (!lichessExists) {
                                        errorMessage = context.getString(
                                            R.string.user_not_found_error
                                        ) + " (Lichess: $lichessTrimed)"
                                        isSaving = false
                                        return@launch
                                    }
                                }

                                if (chessTrimed.isNotEmpty()) {
                                    val chessComExists = checkChessComUserExists(chessTrimed)
                                    if (!chessComExists) {
                                        errorMessage = context.getString(
                                            R.string.user_not_found_error
                                        ) + " (Chess.com: $chessTrimed)"
                                        isSaving = false
                                        return@launch
                                    }
                                }

                                val user = FirebaseAuth.getInstance().currentUser
                                val uid = user?.uid

                                if (user != null && uid != null) {
                                    val newEmail = email.trim()

                                    // Update email if changed
                                    if (newEmail != user.email) {
                                        user.updateEmail(newEmail).await()
                                    }

                                    // Update Firestore
                                    val data = mapOf(
                                        "nickname" to nickname.trim(),
                                        "lichessUsername" to lichessTrimed,
                                        "chessUsername" to chessTrimed,
                                        "language" to selectedLanguage.code
                                    )

                                    FirebaseFirestore.getInstance()
                                        .collection("users")
                                        .document(uid)
                                        .update(data)
                                        .await()

                                    // Apply locale
                                    LocaleManager.setLocale(context, selectedLanguage)

                                    // Save profile
                                    val updatedProfile = UserProfile(
                                        email = newEmail,
                                        nickname = nickname.trim(),
                                        lichessUsername = lichessTrimed,
                                        chessUsername = chessTrimed,
                                        language = selectedLanguage.code
                                    )

                                    isSaving = false
                                    onSave(updatedProfile)

                                } else {
                                    errorMessage = context.getString(R.string.user_not_found_error)
                                    isSaving = false
                                }
                            } catch (e: Exception) {
                                errorMessage = context.getString(
                                    R.string.save_error,
                                    e.message ?: context.getString(R.string.unknown)
                                )
                                isSaving = false
                            }
                        }
                    }
                },
                enabled = !isSaving,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (isSaving) {
                    Row(
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.saving))
                    }
                } else {
                    Text(stringResource(R.string.save_profile))
                }
            }

            // Logout Button
            OutlinedButton(
                onClick = { if (!isSaving) onLogout() },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isSaving
            ) {
                Text(stringResource(R.string.logout))
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }

    // Language Selection Dialog
    if (showLanguageDialog) {
        AlertDialog(
            onDismissRequest = { showLanguageDialog = false },
            title = { Text(stringResource(R.string.language)) },
            text = {
                Column {
                    LocaleManager.Language.values().forEach { language ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selectedLanguage = language
                                    showLanguageDialog = false
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = selectedLanguage == language,
                                onClick = {
                                    selectedLanguage = language
                                    showLanguageDialog = false
                                }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = getLanguageDisplayName(language),
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showLanguageDialog = false }) {
                    Text(stringResource(R.string.close))
                }
            }
        )
    }
}

@Composable
private fun getLanguageDisplayName(language: LocaleManager.Language): String {
    return when (language) {
        LocaleManager.Language.RUSSIAN -> stringResource(R.string.lang_russian)
        LocaleManager.Language.ENGLISH -> stringResource(R.string.lang_english)
        LocaleManager.Language.SPANISH -> stringResource(R.string.lang_spanish)
    }
}