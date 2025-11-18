package com.github.movesense.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.github.movesense.R
import com.github.movesense.subscription.RevenueCatManager
import com.revenuecat.purchases.Purchases
import com.revenuecat.purchases.PurchasesError
import com.revenuecat.purchases.interfaces.ReceiveOfferingsCallback
import com.revenuecat.purchases.models.StoreProduct
import com.revenuecat.purchases.models.StoreTransaction
import com.revenuecat.purchases.CustomerInfo
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PaywallDialog(
    onDismiss: () -> Unit,
    onPurchaseSuccess: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var isLoading by remember { mutableStateOf(true) }
    var availableProducts by remember { mutableStateOf<List<StoreProduct>>(emptyList()) }
    var selectedProduct by remember { mutableStateOf<StoreProduct?>(null) }
    var isPurchasing by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // Load available products
    LaunchedEffect(Unit) {
        try {
            Purchases.sharedInstance.getOfferings(object : ReceiveOfferingsCallback {
                override fun onReceived(offerings: com.revenuecat.purchases.Offerings) {
                    val products = offerings.current?.availablePackages?.map { it.product } ?: emptyList()
                    availableProducts = products
                    selectedProduct = products.firstOrNull()
                    isLoading = false
                }

                override fun onError(error: PurchasesError) {
                    errorMessage = context.getString(R.string.load_products_error)
                    isLoading = false
                }
            })
        } catch (e: Exception) {
            errorMessage = context.getString(R.string.unexpected_error)
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
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = stringResource(R.string.close),
                        tint = MaterialTheme.colorScheme.onSurface
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

                    // Title
                    Text(
                        text = stringResource(R.string.unlock_premium),
                        style = MaterialTheme.typography.headlineLarge.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 32.sp
                        ),
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.primary
                    )

                    Spacer(Modifier.height(8.dp))

                    Text(
                        text = stringResource(R.string.premium_subtitle),
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )

                    Spacer(Modifier.height(32.dp))

                    // Features
                    PremiumFeature(
                        icon = Icons.Default.Speed,
                        title = stringResource(R.string.feature_fast_analysis),
                        description = stringResource(R.string.feature_fast_analysis_desc)
                    )

                    Spacer(Modifier.height(16.dp))

                    PremiumFeature(
                        icon = Icons.Default.CloudDone,
                        title = stringResource(R.string.feature_server_engine),
                        description = stringResource(R.string.feature_server_engine_desc)
                    )

                    Spacer(Modifier.height(16.dp))

                    PremiumFeature(
                        icon = Icons.Default.Insights,
                        title = stringResource(R.string.feature_deep_analysis),
                        description = stringResource(R.string.feature_deep_analysis_desc)
                    )

                    Spacer(Modifier.height(32.dp))

                    // Product Selection
                    if (isLoading) {
                        CircularProgressIndicator()
                    } else if (availableProducts.isEmpty()) {
                        Text(
                            text = errorMessage ?: stringResource(R.string.no_products_available),
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center
                        )
                    } else {
                        availableProducts.forEach { product ->
                            ProductCard(
                                product = product,
                                isSelected = product == selectedProduct,
                                onClick = { selectedProduct = product },
                                enabled = !isPurchasing
                            )
                            Spacer(Modifier.height(12.dp))
                        }

                        Spacer(Modifier.height(24.dp))

                        // Subscribe Button
                        Button(
                            onClick = {
                                isPurchasing = true
                                errorMessage = null

                                scope.launch {
                                    try {
                                        // 1. Get fresh offerings to find the correct Package
                                        Purchases.sharedInstance.getOfferings(object : ReceiveOfferingsCallback {
                                            override fun onReceived(offerings: com.revenuecat.purchases.Offerings) {
                                                val currentOffering = offerings.current
                                                if (currentOffering == null) {
                                                    errorMessage = context.getString(R.string.no_products_available)
                                                    isPurchasing = false
                                                    return
                                                }

                                                // 2. Find the package that matches the selected product
                                                val packageToPurchase = currentOffering.availablePackages.find {
                                                    it.product.id == selectedProduct?.id
                                                }

                                                if (packageToPurchase == null) {
                                                    errorMessage = context.getString(R.string.product_not_found)
                                                    isPurchasing = false
                                                    return
                                                }

                                                // 3. Execute Purchase
                                                Purchases.sharedInstance.purchase(
                                                    com.revenuecat.purchases.PurchaseParams.Builder(
                                                        context as androidx.activity.ComponentActivity,
                                                        packageToPurchase
                                                    ).build(),
                                                    onError = { error: PurchasesError, userCancelled: Boolean ->
                                                        if (!userCancelled) {
                                                            errorMessage = error.underlyingErrorMessage
                                                        }
                                                        isPurchasing = false
                                                    },
                                                    onSuccess = { _: StoreTransaction, customerInfo: CustomerInfo ->
                                                        val isPremium = customerInfo.entitlements.active.containsKey(RevenueCatManager.ENTITLEMENT_ID)
                                                        isPurchasing = false
                                                        if (isPremium) {
                                                            onPurchaseSuccess()
                                                        } else {
                                                            errorMessage = context.getString(R.string.purchase_failed)
                                                        }
                                                    }
                                                )
                                            }

                                            override fun onError(error: PurchasesError) {
                                                errorMessage = error.underlyingErrorMessage
                                                isPurchasing = false
                                            }
                                        })
                                    } catch (e: Exception) {
                                        errorMessage = e.message ?: context.getString(R.string.unexpected_error)
                                        isPurchasing = false
                                    }
                                }
                            },
                            enabled = !isPurchasing && selectedProduct != null,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp),
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary
                            )
                        ) {
                            if (isPurchasing) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Text(
                                    text = stringResource(R.string.subscribe_now),
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold
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
                                    val success = RevenueCatManager.restorePurchases()
                                    isPurchasing = false
                                    if (success) {
                                        val isPremium = RevenueCatManager.isPremiumUser()
                                        if (isPremium) {
                                            onPurchaseSuccess()
                                        }
                                    }
                                }
                            },
                            enabled = !isPurchasing
                        ) {
                            Text(stringResource(R.string.restore_purchases))
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
                }
            }
        }
    }
}

@Composable
private fun PremiumFeature(
    icon: ImageVector,
    title: String,
    description: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.size(48.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp)
                )
            }
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold
                ),
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )
        }
    }
}

@Composable
private fun ProductCard(
    product: StoreProduct,
    isSelected: Boolean,
    onClick: () -> Unit,
    enabled: Boolean
) {
    Card(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        ),
        border = if (isSelected) {
            BorderStroke(
                2.dp,
                MaterialTheme.colorScheme.primary
            )
        } else null
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = product.title,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold
                    )
                )
                Text(
                    text = product.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
            }

            Text(
                text = product.price.formatted,
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            )
        }
    }
}