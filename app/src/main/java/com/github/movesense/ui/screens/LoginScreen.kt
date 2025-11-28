package com.github.movesense.ui.screens

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
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
fun LoginScreen(
    initialLoginMode: Boolean = true,
    onLoginSuccess: (UserProfile) -> Unit,
    onRegisterSuccess: (UserProfile) -> Unit
) {
    val context = LocalContext.current
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var nickname by remember { mutableStateOf("") }
    var lichessName by remember { mutableStateOf("") }
    var chessName by remember { mutableStateOf("") }
    var isLoginMode by remember { mutableStateOf(initialLoginMode) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var passwordVisible by remember { mutableStateOf(false) }
    var showResetPasswordDialog by remember { mutableStateOf(false) }

    // Colors
    val primaryColor = Color(0xFF4CAF50)
    val bgColor = MaterialTheme.colorScheme.background
    val cardColor = MaterialTheme.colorScheme.surface
    val textColor = MaterialTheme.colorScheme.onSurface

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
                                        val profile = UserProfile(
                                            email = user.email ?: "",
                                            nickname = document.getString("nickname") ?: "",
                                            lichessUsername = document.getString("lichessUsername") ?: "",
                                            chessUsername = document.getString("chessUsername") ?: "",
                                            language = document.getString("language") ?: "ru"
                                        )
                                        LocaleManager.setLocale(
                                            context,
                                            LocaleManager.Language.fromCode(profile.language)
                                        )
                                        onLoginSuccess(profile)
                                    } else {
                                        // New user, create profile
                                        val newProfile = hashMapOf(
                                            "nickname" to (user.displayName ?: "User"),
                                            "lichessUsername" to "",
                                            "chessUsername" to "",
                                            "language" to "ru"
                                        )
                                        userRef.set(newProfile).addOnSuccessListener {
                                            val profile = UserProfile(
                                                email = user.email ?: "",
                                                nickname = user.displayName ?: "User",
                                                lichessUsername = "",
                                                chessUsername = "",
                                                language = "ru"
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

    if (showResetPasswordDialog) {
        ResetPasswordDialog(
            initialEmail = email,
            onDismiss = { showResetPasswordDialog = false },
            onSend = { resetEmail ->
                FirebaseAuth.getInstance().sendPasswordResetEmail(resetEmail)
                    .addOnCompleteListener { task ->
                        if (task.isSuccessful) {
                            Toast.makeText(
                                context,
                                context.getString(R.string.reset_email_sent),
                                Toast.LENGTH_LONG
                            ).show()
                            showResetPasswordDialog = false
                        } else {
                            Toast.makeText(
                                context,
                                context.getString(R.string.error_sending_reset, task.exception?.message),
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
            }
        )
    }

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
            // Logo/Header
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

            // Form Card
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
                    // Mode Header
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

                    // Forgot Password Button
                    if (isLoginMode) {
                        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                            TextButton(onClick = { showResetPasswordDialog = true }) {
                                Text(
                                    text = stringResource(R.string.forgot_password),
                                    color = primaryColor,
                                    fontSize = 14.sp
                                )
                            }
                        }
                    } else {
                        Spacer(modifier = Modifier.height(16.dp))
                    }

                    // Registration Fields
                    if (!isLoginMode) {
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

                    // Error Message
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

                    // Submit Button
                    Button(
                        enabled = !isLoading && email.isNotBlank() && password.isNotBlank() &&
                                (isLoginMode || nickname.isNotBlank()),
                        onClick = {
                            errorMessage = null
                            isLoading = true

                            if (isLoginMode) {
                                // Login
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
                                                            nickname = doc.getString("nickname")
                                                                ?: "",
                                                            lichessUsername = doc.getString("lichessUsername")
                                                                ?: "",
                                                            chessUsername = doc.getString("chessUsername")
                                                                ?: "",
                                                            language = doc.getString("language")
                                                                ?: "ru"
                                                        )
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
                                // Register
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
                                                        // Send Verification Email
                                                        firebaseUser.sendEmailVerification()
                                                            .addOnSuccessListener {
                                                                Toast.makeText(
                                                                    context,
                                                                    context.getString(R.string.verification_sent, email.trim()),
                                                                    Toast.LENGTH_LONG
                                                                ).show()
                                                            }

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

                    Spacer(modifier = Modifier.height(16.dp))

                    // Switch Mode Button
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

@Composable
fun ResetPasswordDialog(
    initialEmail: String,
    onDismiss: () -> Unit,
    onSend: (String) -> Unit
) {
    var email by remember { mutableStateOf(initialEmail) }
    val primaryColor = Color(0xFF4CAF50)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(R.string.reset_password))
        },
        text = {
            Column {
                Text(stringResource(R.string.enter_email))
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text(stringResource(R.string.email)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = primaryColor,
                        focusedLabelColor = primaryColor,
                        cursorColor = primaryColor
                    )
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSend(email) },
                enabled = email.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = primaryColor)
            ) {
                Text(stringResource(R.string.send_reset_link))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel), color = primaryColor)
            }
        }
    )
}