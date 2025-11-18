package com.github.movesense.ui.screens

import androidx.activity.ComponentActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.github.movesense.R
import com.github.movesense.subscription.GooglePlayBillingManager
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PaywallDialog(
    onDismiss: () -> Unit,
    onPurchaseSuccess: () -> Unit
) {
    val context = LocalContext.current
    val activity = context as? ComponentActivity
    val scope = rememberCoroutineScope()

    var isLoading by remember { mutableStateOf(true) }
    var productPrice by remember { mutableStateOf("") }
    var productTitle by remember { mutableStateOf("") }
    var isPurchasing by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // Загрузка информации о продукте
    LaunchedEffect(Unit) {
        val productDetails = GooglePlayBillingManager.getProductDetails()
        if (productDetails != null) {
            // Получаем цену из первого offer
            val offer = productDetails.subscriptionOfferDetails?.firstOrNull()
            val pricingPhase = offer?.pricingPhases?.pricingPhaseList?.firstOrNull()

            productPrice = pricingPhase?.formattedPrice ?: ""
            productTitle = productDetails.title.replace(" (Chess Analysis)", "") // Убираем app name
            isLoading = false
        } else {
            errorMessage = context.getString(R.string.load_products_error)
            isLoading = false
        }
    }

    Dialog(
        onDismissRequest = { if (!isPurchasing) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                // Close Button
                IconButton(
                    onClick = { if (!isPurchasing) onDismiss() },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .size(40.dp)
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = stringResource(R.string.close),
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(24.dp)
                    )
                }

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Spacer(Modifier.height(40.dp))

                    // Header Illustration
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp),
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(
                                    Brush.verticalGradient(
                                        colors = listOf(
                                            MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                                            MaterialTheme.colorScheme.tertiary.copy(alpha = 0.2f)
                                        )
                                    )
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.Star,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(80.dp)
                            )
                        }
                    }

                    Spacer(Modifier.height(24.dp))

                    // Title
                    Text(
                        text = stringResource(R.string.unlock_premium),
                        style = MaterialTheme.typography.headlineMedium.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 28.sp
                        ),
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    Spacer(Modifier.height(32.dp))

                    // Comparison Table
                    ComparisonTable()

                    Spacer(Modifier.height(32.dp))

                    // Price Display
                    if (isLoading) {
                        CircularProgressIndicator()
                    } else if (errorMessage != null) {
                        Text(
                            text = errorMessage ?: "",
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center
                        )
                    } else {
                        Text(
                            text = stringResource(R.string.subscribe_to_premium, productPrice),
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.Bold
                            ),
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurface
                        )

                        Spacer(Modifier.height(24.dp))

                        // Subscribe Button
                        Button(
                            onClick = {
                                if (activity == null) {
                                    errorMessage = "Activity not found"
                                    return@Button
                                }

                                isPurchasing = true
                                errorMessage = null

                                GooglePlayBillingManager.launchPurchaseFlow(activity) { success, error ->
                                    if (success || error == null) {
                                        // Проверяем статус после покупки
                                        scope.launch {
                                            val isPremium = GooglePlayBillingManager.isPremiumUser()
                                            isPurchasing = false
                                            if (isPremium) {
                                                onPurchaseSuccess()
                                            }
                                        }
                                    } else {
                                        errorMessage = error
                                        isPurchasing = false
                                    }
                                }
                            },
                            enabled = !isPurchasing && !isLoading && errorMessage == null,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp),
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF4CAF50)
                            )
                        ) {
                            if (isPurchasing) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                    color = Color.White,
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Text(
                                    text = stringResource(R.string.continue_button),
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                            }
                        }

                        // Error Message
                        AnimatedVisibility(
                            visible = errorMessage != null,
                            enter = fadeIn(),
                            exit = fadeOut()
                        ) {
                            Text(
                                text = errorMessage ?: "",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(top = 8.dp)
                            )
                        }

                        Spacer(Modifier.height(16.dp))

                        // Restore Purchases Button
                        TextButton(
                            onClick = {
                                scope.launch {
                                    isPurchasing = true
                                    val success = GooglePlayBillingManager.restorePurchases()
                                    if (success) {
                                        val isPremium = GooglePlayBillingManager.isPremiumUser()
                                        isPurchasing = false
                                        if (isPremium) {
                                            onPurchaseSuccess()
                                        }
                                    } else {
                                        isPurchasing = false
                                        errorMessage = context.getString(R.string.restore_failed)
                                    }
                                }
                            },
                            enabled = !isPurchasing
                        ) {
                            Text(
                                stringResource(R.string.restore_purchases),
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                            )
                        }
                    }

                    Spacer(Modifier.height(16.dp))

                    // Terms text
                    Text(
                        text = stringResource(R.string.subscription_terms),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )

                    Spacer(Modifier.height(16.dp))
                }
            }
        }
    }
}

@Composable
private fun ComparisonTable() {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        // Header Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    MaterialTheme.colorScheme.surfaceVariant,
                    RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)
                )
                .padding(vertical = 12.dp, horizontal = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Box(modifier = Modifier.weight(1f))

            Text(
                text = stringResource(R.string.free),
                style = MaterialTheme.typography.titleSmall.copy(
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                ),
                modifier = Modifier.width(80.dp),
                textAlign = TextAlign.Center
            )

            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.width(80.dp)
            ) {
                Text(
                    text = stringResource(R.string.pro),
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    ),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            }
        }

        // Features
        ComparisonRow(
            feature = stringResource(R.string.feature_basic_analysis),
            free = true,
            pro = true
        )

        ComparisonRow(
            feature = stringResource(R.string.feature_server_engine),
            free = false,
            pro = true,
            isHighlight = true
        )

        ComparisonRow(
            feature = stringResource(R.string.feature_deep_analysis),
            free = false,
            pro = true,
            isHighlight = true
        )

        ComparisonRow(
            feature = stringResource(R.string.feature_unlimited_analysis),
            free = false,
            pro = true
        )

        ComparisonRow(
            feature = stringResource(R.string.feature_priority_support),
            free = false,
            pro = true,
            isLast = true
        )
    }
}

@Composable
private fun ComparisonRow(
    feature: String,
    free: Boolean,
    pro: Boolean,
    isHighlight: Boolean = false,
    isLast: Boolean = false
) {
    val backgroundColor = if (isHighlight) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.05f)
    } else {
        Color.Transparent
    }

    val shape = if (isLast) {
        RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp)
    } else {
        RoundedCornerShape(0.dp)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(backgroundColor, shape)
            .padding(vertical = 16.dp, horizontal = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = feature,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )

        Box(
            modifier = Modifier.width(80.dp),
            contentAlignment = Alignment.Center
        ) {
            if (free) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = "Available",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.size(20.dp)
                )
            } else {
                Text(
                    text = "—",
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                    fontSize = 18.sp
                )
            }
        }

        Box(
            modifier = Modifier.width(80.dp),
            contentAlignment = Alignment.Center
        ) {
            if (pro) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = "Available",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            } else {
                Text(
                    text = "—",
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                    fontSize = 18.sp
                )
            }
        }
    }

    if (!isLast) {
        HorizontalDivider(
            modifier = Modifier.padding(horizontal = 16.dp),
            thickness = 0.5.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
        )
    }
}