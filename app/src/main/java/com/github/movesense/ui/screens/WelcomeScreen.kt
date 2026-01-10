package com.github.movesense.ui.screens

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.movesense.R
import com.github.movesense.ui.UserProfile
import com.github.movesense.util.LocaleManager
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.FirebaseFirestore

@Composable
fun WelcomeScreen(
    onNavigateToLogin: (Boolean) -> Unit, // true for Login, false for Register
    onLoginSuccess: (UserProfile) -> Unit
) {
    val context = LocalContext.current
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // Colors (reusing from LoginScreen logic/theme if possible, or defining here)
    val primaryColor = Color(0xFF4CAF50)
    val bgColor = MaterialTheme.colorScheme.background
    val textColor = MaterialTheme.colorScheme.onBackground

    // Google Sign-In Launcher
    val googleSignInLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
        try {
            val account = task.getResult(ApiException::class.java)
            if (account != null) {
                isLoading = true
                val credential = GoogleAuthProvider.getCredential(account.idToken, null)
                FirebaseAuth.getInstance().signInWithCredential(credential)
                    .addOnCompleteListener { authTask ->
                        if (authTask.isSuccessful) {
                            val user = authTask.result?.user
                            if (user != null) {
                                // Check if user exists in Firestore
                                val db = FirebaseFirestore.getInstance()
                                val userRef = db.collection("users").document(user.uid)

                                userRef.get().addOnSuccessListener { document ->
                                    if (document.exists()) {
                                        // User exists, load profile
                                        val currentLocalLang = LocaleManager.getLocale(context).code
                                        val profileLang = document.getString("language") ?: "ru"
                                        
                                        // If local language differs from profile, update profile to match local
                                        if (currentLocalLang != profileLang) {
                                            userRef.update("language", currentLocalLang)
                                        }

                                        val profile = UserProfile(
                                            email = user.email ?: "",
                                            lichessUsername = document.getString("lichessUsername") ?: "",
                                            chessUsername = document.getString("chessUsername") ?: "",
                                            language = currentLocalLang // Use local language
                                        )
                                        // Do NOT call setLocale here, as it restarts activity and breaks login
                                        onLoginSuccess(profile)
                                    } else {
                                        // New user, create profile
                                        val currentLocalLang = LocaleManager.getLocale(context).code
                                        val newProfile = hashMapOf(
                                            "lichessUsername" to "",
                                            "chessUsername" to "",
                                            "language" to currentLocalLang // Use local language
                                        )
                                        userRef.set(newProfile).addOnSuccessListener {
                                            val profile = UserProfile(
                                                email = user.email ?: "",
                                                lichessUsername = "",
                                                chessUsername = "",
                                                language = currentLocalLang // Use local language
                                            )
                                            onLoginSuccess(profile)
                                        }.addOnFailureListener { e ->
                                            isLoading = false
                                            errorMessage = e.message
                                        }
                                    }
                                }.addOnFailureListener { e ->
                                    isLoading = false
                                    errorMessage = e.message
                                }
                            }
                        } else {
                            isLoading = false
                            errorMessage = authTask.exception?.message
                        }
                    }
            }
        } catch (e: ApiException) {
            isLoading = false
            errorMessage = "Google sign-in failed: ${e.statusCode}"
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(bgColor),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Language Selection
            val currentLanguage = LocaleManager.getLocale(context)
            val languages = LocaleManager.Language.values().map { 
                Triple(it, it.flag, it.displayName)
            }

            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 32.dp),
                horizontalArrangement = Arrangement.Center,
                contentPadding = PaddingValues(horizontal = 8.dp)
            ) {
                items(languages) { (lang, flag, name) ->
                    val isSelected = currentLanguage == lang
                    Surface(
                        onClick = { LocaleManager.setLocale(context, lang) },
                        shape = RoundedCornerShape(12.dp),
                        color = if (isSelected) primaryColor else MaterialTheme.colorScheme.surfaceVariant,
                        border = if (isSelected) null else BorderStroke(1.dp, Color.Gray.copy(alpha = 0.3f)),
                        modifier = Modifier
                            .padding(horizontal = 4.dp)
                            .height(60.dp)
                            .width(80.dp)
                    ) {
                        Column(
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(4.dp)
                        ) {
                            Text(
                                text = flag,
                                fontSize = 20.sp
                            )
                            Text(
                                text = name,
                                fontSize = 10.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                color = if (isSelected) Color.White else textColor,
                                maxLines = 1
                            )
                        }
                    }
                }
            }

            // Logo/Header
            Text(
                text = stringResource(R.string.app_name),
                fontSize = 40.sp,
                fontWeight = FontWeight.Bold,
                color = primaryColor,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            Text(
                text = stringResource(R.string.welcome),
                fontSize = 20.sp,
                color = textColor.copy(alpha = 0.7f),
                modifier = Modifier.padding(bottom = 48.dp)
            )

            if (errorMessage != null) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    ),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = errorMessage!!,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(12.dp),
                        fontSize = 14.sp
                    )
                }
            }

            if (isLoading) {
                CircularProgressIndicator(color = primaryColor)
            } else {
                // Login Button
                Button(
                    onClick = { onNavigateToLogin(true) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = primaryColor,
                        contentColor = Color.White
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = stringResource(R.string.login),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Register Button
                OutlinedButton(
                    onClick = { onNavigateToLogin(false) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = primaryColor
                    ),
                    border = BorderStroke(2.dp, primaryColor),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = stringResource(R.string.register),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Divider
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    HorizontalDivider(
                        modifier = Modifier.weight(1f),
                        color = Color.Gray.copy(alpha = 0.3f)
                    )
                    Text(
                        text = "OR",
                        modifier = Modifier.padding(horizontal = 8.dp),
                        color = Color.Gray,
                        fontSize = 12.sp
                    )
                    HorizontalDivider(
                        modifier = Modifier.weight(1f),
                        color = Color.Gray.copy(alpha = 0.3f)
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Google Sign-In Button
                OutlinedButton(
                    onClick = {
                        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                            .requestIdToken(context.getString(R.string.default_web_client_id))
                            .requestEmail()
                            .build()
                        val googleSignInClient = GoogleSignIn.getClient(context, gso)
                        googleSignInLauncher.launch(googleSignInClient.signInIntent)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, Color.LightGray)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_google),
                            contentDescription = "Google Sign In",
                            tint = Color.Unspecified,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.sign_in_google),
                            color = textColor,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Guest Button
                TextButton(
                    onClick = {
                        isLoading = true
                        FirebaseAuth.getInstance().signInAnonymously()
                            .addOnSuccessListener { result ->
                                val user = result.user
                                if (user != null) {
                                    val currentLocalLang = LocaleManager.getLocale(context).code
                                    // Save default guest prefs
                                    com.github.movesense.data.local.GuestPreferences.setLanguage(context, currentLocalLang)
                                    com.github.movesense.data.local.GuestPreferences.setGuestActive(context, true)
                                    
                                    val profile = UserProfile(
                                        email = "",
                                        lichessUsername = "",
                                        chessUsername = "",
                                        language = currentLocalLang,
                                        isGuest = true
                                    )
                                    onLoginSuccess(profile)
                                } else {
                                    isLoading = false
                                    errorMessage = "Guest login failed: User is null"
                                }
                            }
                            .addOnFailureListener { e ->
                                isLoading = false
                                errorMessage = "Guest login failed: ${e.message}"
                            }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = stringResource(R.string.continue_as_guest),
                        fontSize = 16.sp,
                        color = textColor.copy(alpha = 0.7f)
                    )
                }
            }
        }
    }
}
