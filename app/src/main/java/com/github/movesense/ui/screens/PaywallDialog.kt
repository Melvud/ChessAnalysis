package com.github.movesense.ui.screens

import androidx.activity.ComponentActivity
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.github.movesense.R
import com.github.movesense.subscription.GooglePlayBillingManager
import kotlinx.coroutines.launch

// Вспомогательный класс для отображения оффера
data class SubscriptionOfferUiModel(
        val offerToken: String,
        val price: String,
        val periodCode: String, // "P1Y" или "P1M"
        val priceMicros: Long,
        val currencyCode: String,
        val originalPrice: String? = null,
        val originalPriceMicros: Long? = null
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PaywallDialog(onDismiss: () -> Unit, onPurchaseSuccess: () -> Unit) {
    val context = LocalContext.current
    val activity = context as? ComponentActivity
    val scope = rememberCoroutineScope()

    var isLoading by remember { mutableStateOf(true) }
    var isPurchasing by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // Данные о планах
    var yearlyOffer by remember { mutableStateOf<SubscriptionOfferUiModel?>(null) }
    var monthlyOffer by remember { mutableStateOf<SubscriptionOfferUiModel?>(null) }

    // Выбранный план (по умолчанию стараемся выбрать год)
    var selectedOfferToken by remember { mutableStateOf<String?>(null) }

    // Загрузка информации о продукте
    LaunchedEffect(Unit) {
        val productDetails = GooglePlayBillingManager.getProductDetails()
        if (productDetails != null) {
            val offers = productDetails.subscriptionOfferDetails
            if (offers != null) {
                // Парсим предложения
                offers.forEach { offer ->
                    // Logic to find the correct pricing phase
                    // Usually for intro offers:
                    // Phase 1: Free trial or Discount (e.g. $4.99 for 1 month)
                    // Phase 2: Regular price (e.g. $9.99/month)
                    
                    val phases = offer.pricingPhases.pricingPhaseList
                    val firstPhase = phases.firstOrNull()
                    
                    if (firstPhase != null) {
                        var originalPrice: String? = null
                        var originalPriceMicros: Long? = null
                        
                        // If we have more than 1 phase, assume the second one is the regular price
                        // This handles cases like "50% off for the first month"
                        if (phases.size > 1) {
                            val regularPhase = phases[1]
                            originalPrice = regularPhase.formattedPrice
                            originalPriceMicros = regularPhase.priceAmountMicros
                        }

                        val model =
                                SubscriptionOfferUiModel(
                                        offerToken = offer.offerToken,
                                        price = firstPhase.formattedPrice,
                                        periodCode = firstPhase.billingPeriod,
                                        priceMicros = firstPhase.priceAmountMicros,
                                        currencyCode = firstPhase.priceCurrencyCode,
                                        originalPrice = originalPrice,
                                        originalPriceMicros = originalPriceMicros
                                )

                        // P1Y = Год, P1M = Месяц (ISO 8601)
                        if (firstPhase.billingPeriod.contains("Y")) {
                            // Keep the lowest price offer (best value)
                            if (yearlyOffer == null || model.priceMicros < yearlyOffer!!.priceMicros) {
                                yearlyOffer = model
                            }
                        } else if (firstPhase.billingPeriod.contains("M")) {
                            // Keep the lowest price offer (e.g. promo)
                            if (monthlyOffer == null || model.priceMicros < monthlyOffer!!.priceMicros) {
                                monthlyOffer = model
                            }
                        }
                    }
                }

                // Выбираем год по умолчанию, если есть
                selectedOfferToken = yearlyOffer?.offerToken ?: monthlyOffer?.offerToken
            }
            isLoading = false
        } else {
            errorMessage = context.getString(R.string.load_products_error)
            isLoading = false
        }
    }

    Dialog(
            onDismissRequest = { if (!isPurchasing) onDismiss() },
            properties =
                    DialogProperties(
                            usePlatformDefaultWidth = false,
                            decorFitsSystemWindows = false
                    )
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(), 
            color = Color(0xFF121212) // Deep dark background
        ) {
            Box(modifier = Modifier.fillMaxSize()) {

                // --- ОСНОВНОЙ КОНТЕНТ (Скроллируемый) ---
                Column(
                        modifier =
                                Modifier.fillMaxSize()
                                        .verticalScroll(rememberScrollState())
                                        .padding(bottom = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // 1. Modern Header Area
                    Box(
                            modifier =
                                    Modifier.fillMaxWidth()
                                            .height(320.dp)
                    ) {
                        // Background Image/Gradient
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(
                                    Brush.verticalGradient(
                                        colors = listOf(
                                            Color(0xFF2C2C2C),
                                            Color(0xFF121212)
                                        )
                                    )
                                )
                        )
                        
                        // Decorative Circles
                        Box(
                            modifier = Modifier
                                .offset(x = (-50).dp, y = (-50).dp)
                                .size(200.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFFFD700).copy(alpha = 0.05f))
                        )
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .offset(x = 50.dp, y = 20.dp)
                                .size(150.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFFFD700).copy(alpha = 0.03f))
                        )

                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(top = 48.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            // Premium Icon with Glow
                            Box(contentAlignment = Alignment.Center) {
                                Box(
                                    modifier = Modifier
                                        .size(100.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFFFFD700).copy(alpha = 0.1f))
                                )
                                Icon(
                                    imageVector = Icons.Default.WorkspacePremium,
                                    contentDescription = null,
                                    tint = Color(0xFFFFD700),
                                    modifier = Modifier.size(64.dp)
                                )
                            }
                            
                            Spacer(modifier = Modifier.height(24.dp))
                            
                            Text(
                                text = stringResource(R.string.unlock_premium),
                                style = MaterialTheme.typography.headlineMedium.copy(
                                    fontWeight = FontWeight.ExtraBold,
                                    letterSpacing = 0.5.sp
                                ),
                                color = Color.White,
                                textAlign = TextAlign.Center
                            )
                            
                            Spacer(modifier = Modifier.height(8.dp))
                            
                            Text(
                                text = stringResource(R.string.premium_subtitle),
                                style = MaterialTheme.typography.bodyLarge,
                                color = Color.White.copy(alpha = 0.7f),
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(horizontal = 32.dp)
                            )
                        }
                    }

                    Column(modifier = Modifier.padding(horizontal = 24.dp).offset(y = (-30).dp)) {
                        // 2. Features List in a Card
                        Surface(
                            shape = RoundedCornerShape(24.dp),
                            color = Color(0xFF1E1E1E),
                            tonalElevation = 4.dp,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(24.dp)) {
                                PremiumFeatureRow(
                                    icon = Icons.Default.Bolt,
                                    title = stringResource(R.string.feature_server_engine),
                                    subtitle = "Fast Stockfish 17.1 on cloud servers"
                                )
                                HorizontalDivider(color = Color.White.copy(alpha = 0.1f), modifier = Modifier.padding(vertical = 12.dp))
                                PremiumFeatureRow(
                                    icon = Icons.Default.Psychology,
                                    title = stringResource(R.string.feature_deep_analysis),
                                    subtitle = "Higher depth calculation"
                                )
                                HorizontalDivider(color = Color.White.copy(alpha = 0.1f), modifier = Modifier.padding(vertical = 12.dp))
                                PremiumFeatureRow(
                                    icon = Icons.Default.BatterySaver,
                                    title = "Save Battery",
                                    subtitle = "Phone doesn't overheat"
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(24.dp))

                        // 3. Loading / Error State
                        if (isLoading) {
                            Box(
                                    modifier = Modifier.fillMaxWidth().height(100.dp),
                                    contentAlignment = Alignment.Center
                            ) { CircularProgressIndicator(color = Color(0xFFFFD700)) }
                        } else if (errorMessage != null) {
                            Text(
                                    text = errorMessage ?: "",
                                    color = MaterialTheme.colorScheme.error,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.fillMaxWidth()
                            )
                        } else {
                            // 4. PLANS SELECTION
                            Text(
                                    text = "Choose your plan",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White,
                                    modifier = Modifier.padding(bottom = 16.dp)
                            )

                            // YEARLY PLAN (Highlighted)
                            yearlyOffer?.let { offer ->
                                val isSelected = selectedOfferToken == offer.offerToken
                                
                                // Calculate savings
                                val savings = if (monthlyOffer != null) {
                                    val mPrice = monthlyOffer!!.priceMicros
                                    val yPricePerMonth = offer.priceMicros / 12
                                    if (mPrice > 0) {
                                        val percent = ((mPrice - yPricePerMonth).toDouble() / mPrice * 100).toInt()
                                        "$percent%"
                                    } else "50%"
                                } else "50%"

                                PlanCard(
                                        title = "Yearly",
                                        price = offer.price,
                                        originalPrice = offer.originalPrice,
                                        subtitle = "Best value",
                                        badge = "SAVE $savings",
                                        isSelected = isSelected,
                                        isBestValue = true,
                                        onClick = { selectedOfferToken = offer.offerToken }
                                )
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            // MONTHLY PLAN
                            monthlyOffer?.let { offer ->
                                val isSelected = selectedOfferToken == offer.offerToken
                                PlanCard(
                                        title = "Monthly",
                                        price = offer.price,
                                        originalPrice = offer.originalPrice,
                                        subtitle = "Flexible plan",
                                        badge = if (offer.originalPrice != null) "50% OFF" else null,
                                        isSelected = isSelected,
                                        isBestValue = false,
                                        onClick = { selectedOfferToken = offer.offerToken }
                                )
                            }

                            Spacer(modifier = Modifier.height(32.dp))

                            // 5. ACTION BUTTON
                            Button(
                                    onClick = {
                                        if (activity != null && selectedOfferToken != null) {
                                            isPurchasing = true
                                            errorMessage = null
                                            GooglePlayBillingManager.launchPurchaseFlow(
                                                    activity,
                                                    selectedOfferToken!!
                                            ) { success, error ->
                                                if (success || error == null) {
                                                    scope.launch {
                                                        val isPremium = GooglePlayBillingManager.isPremiumUser()
                                                        isPurchasing = false
                                                        if (isPremium) onPurchaseSuccess()
                                                    }
                                                } else {
                                                    errorMessage = error
                                                    isPurchasing = false
                                                }
                                            }
                                        }
                                    },
                                    enabled = !isPurchasing && selectedOfferToken != null,
                                    modifier = Modifier.fillMaxWidth().height(56.dp),
                                    shape = RoundedCornerShape(16.dp),
                                    colors = ButtonDefaults.buttonColors(
                                            containerColor = Color(0xFFFFD700),
                                            contentColor = Color.Black,
                                            disabledContainerColor = Color(0xFFFFD700).copy(alpha = 0.5f)
                                    ),
                                    elevation = ButtonDefaults.buttonElevation(defaultElevation = 8.dp)
                            ) {
                                if (isPurchasing) {
                                    CircularProgressIndicator(
                                            modifier = Modifier.size(24.dp),
                                            color = Color.Black
                                    )
                                } else {
                                    Text(
                                            text = stringResource(R.string.continue_button).uppercase(),
                                            fontSize = 16.sp,
                                            fontWeight = FontWeight.Bold
                                    )
                                }
                            }

                            // 6. RESTORE & TERMS
                            Row(
                                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                                    horizontalArrangement = Arrangement.Center
                            ) {
                                TextButton(
                                        onClick = {
                                            scope.launch {
                                                isPurchasing = true
                                                if (GooglePlayBillingManager.restorePurchases()) {
                                                    if (GooglePlayBillingManager.isPremiumUser())
                                                            onPurchaseSuccess()
                                                } else {
                                                    errorMessage = context.getString(R.string.restore_failed)
                                                }
                                                isPurchasing = false
                                            }
                                        }
                                ) {
                                    Text(
                                            stringResource(R.string.restore_purchases),
                                            color = Color.White.copy(alpha = 0.6f),
                                            fontSize = 13.sp
                                    )
                                }
                            }

                            Text(
                                    text = stringResource(R.string.subscription_terms),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.White.copy(alpha = 0.4f),
                                    textAlign = TextAlign.Center,
                                    lineHeight = 14.sp,
                                    modifier = Modifier.padding(top = 8.dp)
                            )
                        }
                    }
                }

                // --- КРЕСТИК (CLOSE BUTTON) ---
                IconButton(
                        onClick = { if (!isPurchasing) onDismiss() },
                        modifier =
                                Modifier.align(Alignment.TopEnd)
                                        .statusBarsPadding()
                                        .padding(top = 16.dp, end = 16.dp)
                                        .clip(CircleShape)
                                        .background(Color.Black.copy(alpha = 0.3f))
                                        .size(36.dp)
                ) {
                    Icon(
                            Icons.Default.Close,
                            contentDescription = stringResource(R.string.close),
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun PlanCard(
        title: String,
        price: String,
        originalPrice: String? = null,
        subtitle: String,
        badge: String?,
        isSelected: Boolean,
        isBestValue: Boolean,
        onClick: () -> Unit
) {
    val borderColor = if (isSelected) Color(0xFFFFD700) else Color.Transparent
    val borderWidth = if (isSelected) 2.dp else 0.dp
    val backgroundColor = if (isSelected) Color(0xFFFFD700).copy(alpha = 0.1f) else Color(0xFF1E1E1E)

    Box(
            modifier =
                    Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .border(borderWidth, borderColor, RoundedCornerShape(16.dp))
                            .background(backgroundColor)
                            .clickable { onClick() }
                            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Radio Circle
            Icon(
                    imageVector =
                            if (isSelected) Icons.Default.CheckCircle
                            else Icons.Default.RadioButtonUnchecked,
                    contentDescription = null,
                    tint =
                            if (isSelected) Color(0xFFFFD700)
                            else Color.White.copy(alpha = 0.4f),
                    modifier = Modifier.size(24.dp)
            )

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                )
                Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.7f)
                )
            }

            Column(horizontalAlignment = Alignment.End) {
                if (originalPrice != null) {
                    Text(
                        text = originalPrice,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            textDecoration = TextDecoration.LineThrough
                        ),
                        color = Color.White.copy(alpha = 0.5f)
                    )
                }
                Text(
                        text = price,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                )
                if (badge != null) {
                    Surface(
                            color = Color(0xFFFFD700),
                            shape = RoundedCornerShape(4.dp),
                            modifier = Modifier.padding(top = 4.dp)
                    ) {
                        Text(
                                text = badge,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = Color.Black,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun PremiumFeatureRow(
        icon: ImageVector,
        title: String,
        subtitle: String
) {
    Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
                shape = CircleShape,
                color = Color(0xFFFFD700).copy(alpha = 0.1f),
                modifier = Modifier.size(48.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = Color(0xFFFFD700),
                        modifier = Modifier.size(24.dp)
                )
            }
        }
        Spacer(modifier = Modifier.width(16.dp))
        Column {
            Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
            )
            Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.6f)
            )
        }
    }
}
