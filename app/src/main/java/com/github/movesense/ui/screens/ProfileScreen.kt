package com.github.movesense.ui.screens

import android.content.Intent
import android.net.Uri
import android.text.format.DateFormat
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.movesense.EngineClient
import com.github.movesense.R
import com.github.movesense.subscription.RevenueCatManager
import com.github.movesense.ui.UserProfile
import com.github.movesense.util.LocaleManager
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.revenuecat.purchases.CustomerInfo
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

    var isPremiumUser by remember { mutableStateOf(false) }
    var customerInfo by remember { mutableStateOf<CustomerInfo?>(null) }
    var showPaywall by remember { mutableStateOf(false) }
    var showManageSubscriptionDialog by remember { mutableStateOf(false) }
    var isLoadingSubscription by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        isPremiumUser = RevenueCatManager.isPremiumUser()
        customerInfo = RevenueCatManager.getCustomerInfo()
    }

    suspend fun checkLichessUserExists(username: String): Boolean = withContext(Dispatchers.IO) {
        if (username.isBlank()) return@withContext true
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

    suspend fun checkChessComUserExists(username: String): Boolean = withContext(Dispatchers.IO) {
        if (username.isBlank()) return@withContext true
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
            OutlinedTextField(
                value = email,
                onValueChange = { email = it },
                label = { Text(stringResource(R.string.email)) },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isSaving
            )

            OutlinedTextField(
                value = nickname,
                onValueChange = { nickname = it },
                label = { Text(stringResource(R.string.nickname)) },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isSaving
            )

            OutlinedTextField(
                value = lichessName,
                onValueChange = { lichessName = it },
                label = { Text(stringResource(R.string.lichess_username)) },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isSaving
            )

            OutlinedTextField(
                value = chessName,
                onValueChange = { chessName = it },
                label = { Text(stringResource(R.string.chesscom_username)) },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isSaving
            )

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
                    disabledContainerColor = MaterialTheme.colorScheme.surface,
                    disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    disabledTrailingIconColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            )

            Spacer(modifier = Modifier.height(8.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (isPremiumUser) {
                        Color(0xFFFFD700).copy(alpha = 0.1f)
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    }
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.subscription_status),
                            style = MaterialTheme.typography.titleMedium
                        )

                        if (isPremiumUser) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = Color(0xFFFFD700).copy(alpha = 0.2f)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Default.Star,
                                        contentDescription = null,
                                        tint = Color(0xFFFFD700),
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Text(
                                        text = stringResource(R.string.premium),
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFFB8860B)
                                    )
                                }
                            }
                        } else {
                            Text(
                                text = stringResource(R.string.free),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    if (isPremiumUser) {
                        customerInfo?.let { info ->
                            val entitlement = info.entitlements[RevenueCatManager.ENTITLEMENT_ID]
                            entitlement?.let { ent ->
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    if (ent.expirationDate != null) {
                                        Text(
                                            text = stringResource(
                                                R.string.subscription_expires,
                                                DateFormat.format(
                                                    "dd MMM yyyy",
                                                    ent.expirationDate
                                                ).toString()
                                            ),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }

                                    if (ent.willRenew) {
                                        Text(
                                            text = stringResource(R.string.subscription_will_renew),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    } else {
                                        Text(
                                            text = stringResource(R.string.subscription_will_not_renew),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        OutlinedButton(
                            onClick = { showManageSubscriptionDialog = true },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !isSaving
                        ) {
                            Text(stringResource(R.string.manage_subscription))
                        }
                    } else {
                        Text(
                            text = stringResource(R.string.premium_benefits),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            PremiumBenefit(stringResource(R.string.benefit_server_engine))
                            PremiumBenefit(stringResource(R.string.benefit_fast_analysis))
                            PremiumBenefit(stringResource(R.string.benefit_deep_analysis))
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Button(
                            onClick = { showPaywall = true },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !isSaving,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFFFFD700),
                                contentColor = Color.Black
                            )
                        ) {
                            Icon(
                                Icons.Default.Star,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.upgrade_to_premium),
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

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

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !isSaving) {
                                if (!isPremiumUser) {
                                    showPaywall = true
                                } else {
                                    scope.launch {
                                        try {
                                            EngineClient.setAndroidContext(context.applicationContext)
                                            EngineClient.setEngineMode(EngineClient.EngineMode.SERVER)
                                        } catch (e: Exception) {
                                            errorMessage = context.getString(
                                                R.string.switch_error,
                                                e.message ?: ""
                                            )
                                        }
                                    }
                                }
                            }
                            .padding(vertical = 8.dp)
                            .alpha(if (!isPremiumUser) 0.5f else 1f)
                    ) {
                        RadioButton(
                            selected = engineMode == EngineClient.EngineMode.SERVER,
                            onClick = {
                                if (!isPremiumUser) {
                                    showPaywall = true
                                } else {
                                    scope.launch {
                                        try {
                                            EngineClient.setAndroidContext(context.applicationContext)
                                            EngineClient.setEngineMode(EngineClient.EngineMode.SERVER)
                                        } catch (e: Exception) {
                                            errorMessage = context.getString(
                                                R.string.switch_error,
                                                e.message ?: ""
                                            )
                                        }
                                    }
                                }
                            },
                            enabled = !isSaving && isPremiumUser
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = stringResource(R.string.engine_server),
                                    style = MaterialTheme.typography.bodyLarge
                                )
                                if (!isPremiumUser) {
                                    Surface(
                                        shape = RoundedCornerShape(4.dp),
                                        color = Color(0xFFFFD700).copy(alpha = 0.2f)
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                Icons.Default.Star,
                                                contentDescription = null,
                                                tint = Color(0xFFFFD700),
                                                modifier = Modifier.size(12.dp)
                                            )
                                            Text(
                                                text = stringResource(R.string.premium),
                                                style = MaterialTheme.typography.labelSmall,
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = Color(0xFFB8860B)
                                            )
                                        }
                                    }
                                }
                            }
                            Text(
                                text = stringResource(R.string.engine_server_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

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
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = stringResource(R.string.engine_local),
                                    style = MaterialTheme.typography.bodyLarge
                                )
                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = MaterialTheme.colorScheme.primaryContainer
                                ) {
                                    Text(
                                        text = stringResource(R.string.free),
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
                            }
                            Text(
                                text = stringResource(R.string.engine_local_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

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

            Button(
                onClick = {
                    if (email.trim().isEmpty() || nickname.trim().isEmpty()) {
                        errorMessage = context.getString(R.string.empty_fields_error)
                    } else {
                        errorMessage = null
                        isSaving = true

                        scope.launch {
                            try {
                                val lichessTrimmed = lichessName.trim()
                                val chessTrimmed = chessName.trim()

                                if (lichessTrimmed.isNotEmpty()) {
                                    val lichessExists = checkLichessUserExists(lichessTrimmed)
                                    if (!lichessExists) {
                                        errorMessage = context.getString(
                                            R.string.user_not_found_error
                                        ) + " (Lichess: $lichessTrimmed)"
                                        isSaving = false
                                        return@launch
                                    }
                                }

                                if (chessTrimmed.isNotEmpty()) {
                                    val chessComExists = checkChessComUserExists(chessTrimmed)
                                    if (!chessComExists) {
                                        errorMessage = context.getString(
                                            R.string.user_not_found_error
                                        ) + " (Chess.com: $chessTrimmed)"
                                        isSaving = false
                                        return@launch
                                    }
                                }

                                val user = FirebaseAuth.getInstance().currentUser
                                val uid = user?.uid

                                if (user != null && uid != null) {
                                    val newEmail = email.trim()

                                    if (newEmail != user.email) {
                                        user.updateEmail(newEmail).await()
                                    }

                                    val languageChanged = selectedLanguage.code != profile.language

                                    val data = mapOf(
                                        "nickname" to nickname.trim(),
                                        "lichessUsername" to lichessTrimmed,
                                        "chessUsername" to chessTrimmed,
                                        "language" to selectedLanguage.code
                                    )

                                    FirebaseFirestore.getInstance()
                                        .collection("users")
                                        .document(uid)
                                        .update(data)
                                        .await()

                                    val updatedProfile = UserProfile(
                                        email = newEmail,
                                        nickname = nickname.trim(),
                                        lichessUsername = lichessTrimmed,
                                        chessUsername = chessTrimmed,
                                        language = selectedLanguage.code
                                    )

                                    onSave(updatedProfile)

                                    if (languageChanged) {
                                        withContext(Dispatchers.Main) {
                                            LocaleManager.setLocale(
                                                context as ComponentActivity,
                                                selectedLanguage
                                            )
                                        }
                                    } else {
                                        isSaving = false
                                        withContext(Dispatchers.Main) {
                                            onBack()
                                        }
                                    }

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

    if (showLanguageDialog) {
        AlertDialog(
            onDismissRequest = { showLanguageDialog = false },
            title = { Text(stringResource(R.string.select_language)) },
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

    if (showPaywall) {
        PaywallDialog(
            onDismiss = { showPaywall = false },
            onPurchaseSuccess = {
                showPaywall = false
                scope.launch {
                    isPremiumUser = RevenueCatManager.isPremiumUser()
                    customerInfo = RevenueCatManager.getCustomerInfo()
                }
                Toast.makeText(
                    context,
                    context.getString(R.string.premium_activated),
                    Toast.LENGTH_LONG
                ).show()
            }
        )
    }

    if (showManageSubscriptionDialog) {
        AlertDialog(
            onDismissRequest = { showManageSubscriptionDialog = false },
            title = { Text(stringResource(R.string.manage_subscription)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = stringResource(R.string.manage_subscription_description),
                        style = MaterialTheme.typography.bodyMedium
                    )

                    if (isLoadingSubscription) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .size(40.dp)
                                .align(Alignment.CenterHorizontally)
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val intent = Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse("https://play.google.com/store/account/subscriptions")
                        )
                        context.startActivity(intent)
                    },
                    enabled = !isLoadingSubscription
                ) {
                    Text(stringResource(R.string.open_play_store))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        isLoadingSubscription = true
                        scope.launch {
                            RevenueCatManager.restorePurchases()
                            isPremiumUser = RevenueCatManager.isPremiumUser()
                            customerInfo = RevenueCatManager.getCustomerInfo()
                            isLoadingSubscription = false
                            showManageSubscriptionDialog = false
                        }
                    },
                    enabled = !isLoadingSubscription
                ) {
                    Text(stringResource(R.string.restore_purchases))
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

@Composable
private fun PremiumBenefit(text: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Default.CheckCircle,
            contentDescription = null,
            tint = Color(0xFF4CAF50),
            modifier = Modifier.size(18.dp)
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}