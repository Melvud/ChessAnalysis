package com.github.movesense.ui.screens

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.movesense.EngineClient
import com.github.movesense.EngineClient.EngineMode
import com.github.movesense.R
import com.github.movesense.ui.UserProfile
import com.github.movesense.util.LocaleManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
    var selectedLanguage by remember { mutableStateOf(LocaleManager.Language.fromCode(profile.language)) }
    var showLanguageDialog by remember { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val engineMode by EngineClient.engineMode.collectAsState()

    suspend fun checkLichessUserExists(username: String): Boolean = withContext(Dispatchers.IO) {
        if (username.isBlank()) return@withContext true
        runCatching {
            val client = OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build()
            val request = Request.Builder()
                .url("https://lichess.org/api/user/$username")
                .get()
                .build()
            client.newCall(request).execute().use { it.isSuccessful }
        }.getOrElse { false }
    }

    suspend fun checkChessComUserExists(username: String): Boolean = withContext(Dispatchers.IO) {
        if (username.isBlank()) return@withContext true
        runCatching {
            val client = OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build()
            val request = Request.Builder()
                .url("https://api.chess.com/pub/player/$username")
                .get()
                .build()
            client.newCall(request).execute().use { it.isSuccessful }
        }.getOrElse { false }
    }

    Scaffold(
        topBar = {
            // используем библиотечный Material3 SmallTopAppBar
            TopAppBar(
                title = { Text(stringResource(R.string.profile), fontSize = 20.sp, fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    IconButton(onClick = {
                        onSave(
                            profile.copy(
                                email = email.trim(),
                                nickname = nickname.trim(),
                                lichessUsername = lichessName.trim(),
                                chessUsername = chessName.trim(),
                                language = selectedLanguage.code
                            )
                        )
                        Toast.makeText(context, R.string.saved, Toast.LENGTH_SHORT).show()
                    }) {
                        Icon(Icons.Default.Save, contentDescription = stringResource(R.string.save_profile))
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
            OutlinedTextField(
                value = email,
                onValueChange = { email = it },
                label = { Text(stringResource(R.string.email)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = !isSaving
            )
            OutlinedTextField(
                value = nickname,
                onValueChange = { nickname = it },
                label = { Text(stringResource(R.string.nickname)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = !isSaving
            )
            OutlinedTextField(
                value = lichessName,
                onValueChange = { lichessName = it },
                label = { Text(stringResource(R.string.lichess_username)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = !isSaving
            )
            OutlinedTextField(
                value = chessName,
                onValueChange = { chessName = it },
                label = { Text(stringResource(R.string.chesscom_username)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = !isSaving
            )

            OutlinedTextField(
                value = getLanguageDisplayName(selectedLanguage),
                onValueChange = {},
                label = { Text(stringResource(R.string.language)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !isSaving) { showLanguageDialog = true },
                readOnly = true,
                enabled = !isSaving,
                trailingIcon = { Icon(Icons.Default.ArrowBack, contentDescription = null) }
            )

            Divider()

            Text(stringResource(R.string.engine_mode), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)

            // Server
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !isSaving) {
                        scope.launch {
                            try {
                                EngineClient.setAndroidContext(context.applicationContext)
                                EngineClient.setEngineMode(EngineMode.SERVER)
                                Toast.makeText(context, "Режим: Server", Toast.LENGTH_SHORT).show()
                            } catch (e: Exception) {
                                errorMessage = context.getString(R.string.switch_error, e.message ?: "")
                            }
                        }
                    }
                    .padding(vertical = 8.dp)
            ) {
                RadioButton(
                    selected = engineMode == EngineMode.SERVER,
                    onClick = {
                        scope.launch {
                            try {
                                EngineClient.setAndroidContext(context.applicationContext)
                                EngineClient.setEngineMode(EngineMode.SERVER)
                                Toast.makeText(context, "Режим: Server", Toast.LENGTH_SHORT).show()
                            } catch (e: Exception) {
                                errorMessage = context.getString(R.string.switch_error, e.message ?: "")
                            }
                        }
                    },
                    enabled = !isSaving
                )
                Spacer(Modifier.width(8.dp))
                Column {
                    Text("Server (удалённый)")
                    Text(
                        "Использовать облачный бэкенд",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Local (WebView)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !isSaving) {
                        scope.launch {
                            try {
                                EngineClient.setAndroidContext(context.applicationContext)
                                EngineClient.setEngineMode(EngineMode.LOCAL)
                                Toast.makeText(context, "Режим: Local (Web)", Toast.LENGTH_SHORT).show()
                            } catch (e: Exception) {
                                errorMessage = context.getString(R.string.switch_error, e.message ?: "")
                            }
                        }
                    }
                    .padding(vertical = 8.dp)
            ) {
                RadioButton(
                    selected = engineMode == EngineMode.LOCAL,
                    onClick = {
                        scope.launch {
                            try {
                                EngineClient.setAndroidContext(context.applicationContext)
                                EngineClient.setEngineMode(EngineMode.LOCAL)
                                Toast.makeText(context, "Режим: Local (Web)", Toast.LENGTH_SHORT).show()
                            } catch (e: Exception) {
                                errorMessage = context.getString(R.string.switch_error, e.message ?: "")
                            }
                        }
                    },
                    enabled = !isSaving
                )
                Spacer(Modifier.width(8.dp))
                Column {
                    Text("Local (WebView)")
                    Text(
                        "Встроенный JS-движок в WebView",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Native (JNI)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !isSaving) {
                        scope.launch {
                            try {
                                EngineClient.setAndroidContext(context.applicationContext)
                                EngineClient.setEngineMode(EngineMode.NATIVE)
                                Toast.makeText(context, "Режим: Native (JNI)", Toast.LENGTH_SHORT).show()
                            } catch (e: Exception) {
                                errorMessage = context.getString(R.string.switch_error, e.message ?: "")
                            }
                        }
                    }
                    .padding(vertical = 8.dp)
            ) {
                RadioButton(
                    selected = engineMode == EngineMode.NATIVE,
                    onClick = {
                        scope.launch {
                            try {
                                EngineClient.setAndroidContext(context.applicationContext)
                                EngineClient.setEngineMode(EngineMode.NATIVE)
                                Toast.makeText(context, "Режим: Native (JNI)", Toast.LENGTH_SHORT).show()
                            } catch (e: Exception) {
                                errorMessage = context.getString(R.string.switch_error, e.message ?: "")
                            }
                        }
                    },
                    enabled = !isSaving
                )
                Spacer(Modifier.width(8.dp))
                Column {
                    Text("Native (JNI)")
                    Text(
                        "libstockfish через JNI (без скачиваний)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (errorMessage != null) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = errorMessage!!,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }

            Button(
                onClick = {
                    if (email.trim().isEmpty() || nickname.trim().isEmpty()) {
                        errorMessage = context.getString(R.string.empty_fields_error)
                    } else {
                        errorMessage = null
                        isSaving = true
                        scope.launch {
                            try {
                                val lichessTrimed = lichessName.trim()
                                val chessTrimed = chessName.trim()

                                if (lichessTrimed.isNotEmpty() && !checkLichessUserExists(lichessTrimed)) {
                                    errorMessage = context.getString(R.string.user_not_found_error) + " (Lichess: $lichessTrimed)"
                                    isSaving = false
                                    return@launch
                                }
                                if (chessTrimed.isNotEmpty() && !checkChessComUserExists(chessTrimed)) {
                                    errorMessage = context.getString(R.string.user_not_found_error) + " (Chess.com: $chessTrimed)"
                                    isSaving = false
                                    return@launch
                                }

                                LocaleManager.setLocale(context, selectedLanguage)

                                onSave(
                                    profile.copy(
                                        email = email.trim(),
                                        nickname = nickname.trim(),
                                        lichessUsername = lichessTrimed,
                                        chessUsername = chessTrimed,
                                        language = selectedLanguage.code
                                    )
                                )
                                Toast.makeText(context, R.string.saved, Toast.LENGTH_SHORT).show()
                            } catch (e: Exception) {
                                errorMessage = context.getString(R.string.save_error, e.message ?: context.getString(R.string.unknown))
                            } finally {
                                isSaving = false
                            }
                        }
                    }
                },
                enabled = !isSaving,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (isSaving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.saving))
                } else {
                    Text(stringResource(R.string.save_profile))
                }
            }

            OutlinedButton(
                onClick = onLogout,
                modifier = Modifier.fillMaxWidth(),
                enabled = !isSaving
            ) { Text(stringResource(R.string.logout)) }

            Spacer(Modifier.height(12.dp))
        }
    }

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
                            Spacer(Modifier.width(8.dp))
                            Text(getLanguageDisplayName(language))
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
