package com.github.movesense.ui.screens

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.movesense.R
import com.github.movesense.EngineClient
import com.github.movesense.EngineClient.setEngineMode

import com.github.movesense.subscription.GooglePlayBillingManager
import com.github.movesense.ui.UserProfile
import com.github.movesense.util.LocaleManager
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.firebase.auth.GoogleAuthProvider
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    profile: UserProfile,
    onSave: (UserProfile) -> Unit,
    onLogout: () -> Unit,
    onBack: () -> Unit,
    onAdminClick: () -> Unit
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

    // Google Play Billing - учитываем как статус из профиля, так и из Google Play
    val googlePlayPremium by GooglePlayBillingManager.isPremiumFlow.collectAsState()
    val isPremiumUser = profile.isPremium || googlePlayPremium
    var showPaywall by remember { mutableStateOf(false) }
    var showManageSubscriptionDialog by remember { mutableStateOf(false) }

    var isGoogleLinked by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val user = FirebaseAuth.getInstance().currentUser
        isGoogleLinked = user?.providerData?.any { it.providerId == GoogleAuthProvider.PROVIDER_ID } == true
    }

    val googleSignInLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
        try {
            val account = task.getResult(ApiException::class.java)
            account?.idToken?.let { token ->
                val credential = GoogleAuthProvider.getCredential(token, null)
                FirebaseAuth.getInstance().currentUser?.linkWithCredential(credential)
                    ?.addOnSuccessListener {
                        isGoogleLinked = true
                        Toast.makeText(context, context.getString(R.string.google_linked), Toast.LENGTH_SHORT).show()
                    }
                    ?.addOnFailureListener { e ->
                        if (e.message?.contains("ERROR_CREDENTIAL_ALREADY_ASSOCIATED") == true ||
                            e.message?.contains("already associated") == true) {
                            errorMessage = context.getString(R.string.error_google_already_linked)
                        } else {
                            Toast.makeText(context, context.getString(R.string.link_error, e.message), Toast.LENGTH_LONG).show()
                        }
                    }
            }
        } catch (e: ApiException) {
            Toast.makeText(context, "Google Sign-In failed: ${e.statusCode}", Toast.LENGTH_SHORT).show()
        }
    }

    // Validation functions
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
                title = {
                    Text(
                        stringResource(R.string.profile),
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.Default.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Premium Card Section
            PremiumCard(
                isPremium = isPremiumUser,
                onUpgradeClick = { showPaywall = true },
                onManageClick = { showManageSubscriptionDialog = true },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )

            // Engine Mode Section
            EngineSection(
                engineMode = engineMode,
                isPremium = isPremiumUser,
                onUpgradeClick = { showPaywall = true },
                modifier = Modifier.padding(horizontal = 16.dp)
            )

            // Profile Information Section
            ProfileInfoSection(
                email = email,
                nickname = nickname,
                lichessName = lichessName,
                chessName = chessName,
                selectedLanguage = selectedLanguage,
                onEmailChange = { email = it },
                onNicknameChange = { nickname = it },
                onLichessNameChange = { lichessName = it },
                onChessNameChange = { chessName = it },
                onLanguageClick = { showLanguageDialog = true },
                isSaving = isSaving,
                isGoogleLinked = isGoogleLinked,
                onLinkGoogle = {
                    val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                        .requestIdToken(context.getString(R.string.default_web_client_id))
                        .requestEmail()
                        .build()
                    val googleSignInClient = GoogleSignIn.getClient(context, gso)
                    googleSignInLauncher.launch(googleSignInClient.signInIntent)
                },
                onUnlinkGoogle = {
                    FirebaseAuth.getInstance().currentUser?.unlink(GoogleAuthProvider.PROVIDER_ID)
                        ?.addOnSuccessListener {
                            isGoogleLinked = false
                            Toast.makeText(context, context.getString(R.string.google_unlinked), Toast.LENGTH_SHORT).show()
                        }
                        ?.addOnFailureListener { e ->
                            Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                },
                modifier = Modifier.padding(horizontal = 16.dp)
            )

            // Error Message
            AnimatedVisibility(
                visible = errorMessage != null,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Text(
                        text = errorMessage ?: "",
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            // Admin Panel Button
            if (profile.isAdmin) {
                Button(
                    onClick = onAdminClick,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .height(56.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondary
                    )
                ) {
                    Icon(Icons.Default.AdminPanelSettings, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.admin_panel_button),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Save Button
            Button(
                onClick = {
                    scope.launch {
                        isSaving = true
                        errorMessage = null

                        try {
                            // Validate usernames
                            if (email.isBlank() || nickname.isBlank()) {
                                errorMessage = context.getString(R.string.empty_fields_error)
                                isSaving = false
                                return@launch
                            }

                            val lichessValid = checkLichessUserExists(lichessName)
                            val chessComValid = checkChessComUserExists(chessName)

                            if (!lichessValid) {
                                errorMessage = "Lichess user '$lichessName' not found"
                                isSaving = false
                                return@launch
                            }

                            if (!chessComValid) {
                                errorMessage = "Chess.com user '$chessName' not found"
                                isSaving = false
                                return@launch
                            }

                            // Save to Firebase
                            val userId = FirebaseAuth.getInstance().currentUser?.uid
                            if (userId != null) {
                                val db = FirebaseFirestore.getInstance()
                                val languageChanged = selectedLanguage.code != profile.language

                                db.collection("users")
                                    .document(userId)
                                    .update(
                                        mapOf(
                                            "email" to email,
                                            "nickname" to nickname,
                                            "lichessUsername" to lichessName,
                                            "chessUsername" to chessName,
                                            "language" to selectedLanguage.code
                                        )
                                    )
                                    .await()

                                val updatedProfile = profile.copy(
                                    email = email,
                                    nickname = nickname,
                                    lichessUsername = lichessName,
                                    chessUsername = chessName,
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
                                        Toast.makeText(
                                            context,
                                            context.getString(R.string.profile_updated),
                                            Toast.LENGTH_SHORT
                                        ).show()
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
                },
                enabled = !isSaving,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .height(56.dp),
                shape = RoundedCornerShape(16.dp)
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
                    Icon(Icons.Default.Check, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.save_profile),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Logout Button
            OutlinedButton(
                onClick = { if (!isSaving) onLogout() },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .height(56.dp),
                enabled = !isSaving,
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                ),
                border = ButtonDefaults.outlinedButtonBorder.copy(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.error,
                            MaterialTheme.colorScheme.error
                        )
                    )
                )
            ) {
                Icon(Icons.Default.ExitToApp, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    stringResource(R.string.logout),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }

    // Language Dialog
    if (showLanguageDialog) {
        AlertDialog(
            onDismissRequest = { showLanguageDialog = false },
            title = {
                Text(
                    stringResource(R.string.select_language),
                    fontWeight = FontWeight.Bold
                )
            },
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
                            Spacer(modifier = Modifier.width(12.dp))
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

    // Paywall Dialog
    if (showPaywall) {
        PaywallDialog(
            onDismiss = { showPaywall = false },
            onPurchaseSuccess = {
                showPaywall = false
                Toast.makeText(
                    context,
                    context.getString(R.string.premium_activated),
                    Toast.LENGTH_LONG
                ).show()
            }
        )
    }

    // Manage Subscription Dialog
    if (showManageSubscriptionDialog) {
        AlertDialog(
            onDismissRequest = { showManageSubscriptionDialog = false },
            icon = {
                Icon(
                    Icons.Default.Star,
                    contentDescription = null,
                    tint = Color(0xFFFFD700),
                    modifier = Modifier.size(32.dp)
                )
            },
            title = {
                Text(
                    stringResource(R.string.manage_subscription),
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = stringResource(R.string.manage_subscription_description),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val intent = Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse("https://play.google.com/store/account/subscriptions")
                        )
                        context.startActivity(intent)
                    }
                ) {
                    Text(stringResource(R.string.open_play_store))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            GooglePlayBillingManager.restorePurchases()
                            showManageSubscriptionDialog = false
                        }
                    }
                ) {
                    Text(stringResource(R.string.restore_purchases))
                }
            }
        )
    }
}

@Composable
private fun PremiumCard(
    isPremium: Boolean,
    onUpgradeClick: () -> Unit,
    onManageClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isPremium) {
                Color(0xFFFFD700).copy(alpha = 0.1f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    if (isPremium) {
                        Brush.horizontalGradient(
                            colors = listOf(
                                Color(0xFFFFD700).copy(alpha = 0.2f),
                                Color(0xFFFFE55C).copy(alpha = 0.2f)
                            )
                        )
                    } else {
                        Brush.horizontalGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.surfaceVariant,
                                MaterialTheme.colorScheme.surfaceVariant
                            )
                        )
                    }
                )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Surface(
                        shape = CircleShape,
                        color = if (isPremium) {
                            Color(0xFFFFD700)
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                        modifier = Modifier.size(48.dp)
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.fillMaxSize()
                        ) {
                            Icon(
                                Icons.Default.Star,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (isPremium) {
                                stringResource(R.string.premium_active)
                            } else {
                                stringResource(R.string.upgrade_to_premium)
                            },
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.Bold
                            ),
                            color = if (isPremium) {
                                Color(0xFFB8860B)
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            }
                        )
                        Text(
                            text = if (isPremium) {
                                stringResource(R.string.premium_subtitle)
                            } else {
                                stringResource(R.string.faster_analysis_with_server)
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                        )
                        
                        if (isPremium && profile.premiumUntil != -1L && profile.premiumUntil > System.currentTimeMillis()) {
                             val date = java.text.SimpleDateFormat("dd MMM yyyy", java.util.Locale.getDefault())
                                .format(java.util.Date(profile.premiumUntil))
                             Text(
                                text = stringResource(R.string.premium_until, date),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                             )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                if (isPremium) {
                    // Premium Benefits
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        PremiumBenefit(stringResource(R.string.benefit_server_engine))
                        PremiumBenefit(stringResource(R.string.benefit_fast_analysis))
                        PremiumBenefit(stringResource(R.string.benefit_deep_analysis))
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    OutlinedButton(
                        onClick = onManageClick,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.Settings, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            stringResource(R.string.manage_subscription),
                            fontWeight = FontWeight.Medium
                        )
                    }
                } else {
                    Button(
                        onClick = onUpgradeClick,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Icon(Icons.Default.Star, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            stringResource(R.string.upgrade),
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EngineSection(
    engineMode: EngineClient.EngineMode,
    isPremium: Boolean,
    onUpgradeClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    Icons.Default.Settings,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = stringResource(R.string.engine_mode),
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold
                    )
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Engine Mode Options
            EngineModeOption(
                title = stringResource(R.string.engine_server),
                description = stringResource(R.string.engine_server_desc),
                icon = Icons.Default.CloudDone,
                isSelected = engineMode == EngineClient.EngineMode.SERVER,
                isEnabled = isPremium,
                onUpgradeClick = onUpgradeClick,
                onClick = {
                    if (isPremium) {
                        scope.launch {
                            setEngineMode(EngineClient.EngineMode.SERVER)
                        }
                    }
                }
            )

            Spacer(modifier = Modifier.height(12.dp))

            EngineModeOption(
                title = stringResource(R.string.engine_local),
                description = stringResource(R.string.engine_local_desc),
                icon = Icons.Default.PhoneAndroid,
                isSelected = engineMode == EngineClient.EngineMode.LOCAL,
                isEnabled = true,
                onClick = {
                    scope.launch {
                        setEngineMode(EngineClient.EngineMode.LOCAL)
                    }
                }
            )
        }
    }
}

@Composable
private fun EngineModeOption(
    title: String,
    description: String,
    icon: ImageVector,
    isSelected: Boolean,
    isEnabled: Boolean,
    onClick: () -> Unit,
    onUpgradeClick: (() -> Unit)? = null
) {
    Card(
        onClick = { if (isEnabled) onClick() },
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            }
        ),
        border = if (isSelected) {
            ButtonDefaults.outlinedButtonBorder.copy(
                width = 2.dp,
                brush = Brush.linearGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.primary,
                        MaterialTheme.colorScheme.primary
                    )
                )
            )
        } else null
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = CircleShape,
                color = if (isSelected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
                modifier = Modifier.size(40.dp)
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.fillMaxSize()
                ) {
                    Icon(
                        icon,
                        contentDescription = null,
                        tint = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontWeight = FontWeight.Bold
                        )
                    )
                    if (!isEnabled && onUpgradeClick != null) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = Color(0xFFFFD700)
                        ) {
                            Text(
                                text = "PRO",
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = Color.Black
                                )
                            )
                        }
                    }
                }
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
            }

            if (isSelected) {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            } else if (!isEnabled && onUpgradeClick != null) {
                IconButton(onClick = onUpgradeClick) {
                    Icon(
                        Icons.Default.Lock,
                        contentDescription = "Locked",
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                }
            }
        }
    }
}

@Composable
private fun ProfileInfoSection(
    email: String,
    nickname: String,
    lichessName: String,
    chessName: String,
    selectedLanguage: LocaleManager.Language,
    onEmailChange: (String) -> Unit,
    onNicknameChange: (String) -> Unit,
    onLichessNameChange: (String) -> Unit,
    onChessNameChange: (String) -> Unit,
    onLanguageClick: () -> Unit,
    isSaving: Boolean,
    isGoogleLinked: Boolean,
    onLinkGoogle: () -> Unit,
    onUnlinkGoogle: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    Icons.Default.Person,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = "Account Information",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold
                    )
                )
            }

            OutlinedTextField(
                value = email,
                onValueChange = onEmailChange,
                label = { Text(stringResource(R.string.email)) },
                leadingIcon = { Icon(Icons.Default.Email, contentDescription = null) },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isSaving,
                shape = RoundedCornerShape(12.dp)
            )

            OutlinedTextField(
                value = nickname,
                onValueChange = onNicknameChange,
                label = { Text(stringResource(R.string.nickname)) },
                leadingIcon = { Icon(Icons.Default.Person, contentDescription = null) },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isSaving,
                shape = RoundedCornerShape(12.dp)
            )

            OutlinedTextField(
                value = lichessName,
                onValueChange = onLichessNameChange,
                label = { Text(stringResource(R.string.lichess_username)) },
                leadingIcon = {
                    Text(
                        "♞",
                        fontSize = 24.sp,
                        modifier = Modifier.padding(start = 4.dp)
                    )
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isSaving,
                shape = RoundedCornerShape(12.dp)
            )

            OutlinedTextField(
                value = chessName,
                onValueChange = onChessNameChange,
                label = { Text(stringResource(R.string.chesscom_username)) },
                leadingIcon = {
                    Text(
                        "♟",
                        fontSize = 24.sp,
                        modifier = Modifier.padding(start = 4.dp)
                    )
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isSaving,
                shape = RoundedCornerShape(12.dp)
            )

            OutlinedTextField(
                value = getLanguageDisplayName(selectedLanguage),
                onValueChange = {},
                label = { Text(stringResource(R.string.language)) },
                leadingIcon = { Icon(Icons.Default.Language, contentDescription = null) },
                trailingIcon = { Icon(Icons.Default.ArrowDropDown, contentDescription = null) },
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !isSaving) { onLanguageClick() },
                enabled = false,
                shape = RoundedCornerShape(12.dp),
                colors = TextFieldDefaults.colors(
                    disabledTextColor = MaterialTheme.colorScheme.onSurface,
                    disabledContainerColor = MaterialTheme.colorScheme.surface,
                    disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    disabledLeadingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    disabledTrailingIconColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            )

            // Google Account Link/Unlink
            OutlinedButton(
                onClick = {
                    if (isGoogleLinked) onUnlinkGoogle() else onLinkGoogle()
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = if (isGoogleLinked) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                ),
                border = BorderStroke(
                    1.dp,
                    if (isGoogleLinked) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                )
            ) {
                Text(
                    text = if (isGoogleLinked) stringResource(R.string.unlink_google) else stringResource(R.string.link_google)
                )
            }
        }
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
            modifier = Modifier.size(20.dp)
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
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