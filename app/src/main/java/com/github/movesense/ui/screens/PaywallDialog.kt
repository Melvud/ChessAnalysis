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

// Вспомогательный класс для отображения оффера
data class SubscriptionOfferUiModel(
        val offerToken: String,
        val price: String,
        val periodCode: String, // "P1Y" или "P1M"
        val priceMicros: Long
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
                    val phase = offer.pricingPhases.pricingPhaseList.firstOrNull()
                    if (phase != null) {
                        val model =
                                SubscriptionOfferUiModel(
                                        offerToken = offer.offerToken,
                                        price = phase.formattedPrice,
                                        periodCode = phase.billingPeriod,
                                        priceMicros = phase.priceAmountMicros
                                )

                        // P1Y = Год, P1M = Месяц (ISO 8601)
                        if (phase.billingPeriod.contains("Y")) {
                            yearlyOffer = model
                        } else if (phase.billingPeriod.contains("M")) {
                            monthlyOffer = model
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
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Box(modifier = Modifier.fillMaxSize()) {

                // --- ОСНОВНОЙ КОНТЕНТ (Скроллируемый) ---
                Column(
                        modifier =
                                Modifier.fillMaxSize()
                                        .verticalScroll(rememberScrollState())
                                        .padding(bottom = 24.dp), // Отступ снизу
                        horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // 1. Header Area
                    Box(
                            modifier =
                                    Modifier.fillMaxWidth()
                                            .height(240.dp)
                                            .background(
                                                    Brush.verticalGradient(
                                                            colors =
                                                                    listOf(
                                                                            Color(0xFFFFD700)
                                                                                    .copy(
                                                                                            alpha =
                                                                                                    0.2f
                                                                                    ),
                                                                            MaterialTheme
                                                                                    .colorScheme
                                                                                    .surface
                                                                    )
                                                    )
                                            ),
                            contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                    imageVector = Icons.Default.WorkspacePremium,
                                    contentDescription = null,
                                    tint = Color(0xFFFFD700), // Золотой
                                    modifier = Modifier.size(80.dp)
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                    text = stringResource(R.string.unlock_premium),
                                    style =
                                            MaterialTheme.typography.headlineMedium.copy(
                                                    fontWeight = FontWeight.Bold
                                            ),
                                    color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                    text = stringResource(R.string.upgrade_for_faster_analysis),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                            )
                        }
                    }

                    Column(modifier = Modifier.padding(horizontal = 24.dp)) {
                        // 2. Features List
                        PremiumFeatureRow(
                                icon = Icons.Default.Bolt,
                                title = stringResource(R.string.feature_server_engine),
                                subtitle = "Fast Stockfish 17.1 on cloud servers"
                        )
                        PremiumFeatureRow(
                                icon = Icons.Default.Psychology,
                                title = stringResource(R.string.feature_deep_analysis),
                                subtitle = "Higher depth calculation"
                        )
                        PremiumFeatureRow(
                                icon = Icons.Default.BatterySaver,
                                title = "Save Battery",
                                subtitle = "Phone doesn't overheat"
                        )

                        Spacer(modifier = Modifier.height(32.dp))

                        // 3. Loading / Error State
                        if (isLoading) {
                            Box(
                                    modifier = Modifier.fillMaxWidth(),
                                    contentAlignment = Alignment.Center
                            ) { CircularProgressIndicator() }
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
                                    modifier = Modifier.padding(bottom = 12.dp)
                            )

                            // YEARLY PLAN (Highlighted)
                            yearlyOffer?.let { offer ->
                                val isSelected = selectedOfferToken == offer.offerToken
                                val monthlyPriceCalc = offer.priceMicros / 12 / 1000000.0
                                // Грубый подсчет скидки для UI
                                val savings =
                                        if (monthlyOffer != null) {
                                            val mPrice = monthlyOffer!!.priceMicros
                                            val yPricePerMonth = offer.priceMicros / 12
                                            val percent =
                                                    ((mPrice - yPricePerMonth).toDouble() / mPrice *
                                                                    100)
                                                            .toInt()
                                            "$percent%"
                                        } else "50%"

                                PlanCard(
                                        title = "Annual",
                                        price = offer.price,
                                        subtitle =
                                                "Just ~${String.format("%.2f", monthlyPriceCalc)} / month",
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
                                        subtitle = "Billed every month",
                                        badge = null,
                                        isSelected = isSelected,
                                        isBestValue = false,
                                        onClick = { selectedOfferToken = offer.offerToken }
                                )
                            }

                            Spacer(modifier = Modifier.height(24.dp))

                            // 5. ACTION BUTTON
                            Button(
                                    onClick = {
                                        if (activity != null && selectedOfferToken != null) {
                                            isPurchasing = true
                                            errorMessage = null

                                            // ВАЖНО: Используем метод с offerToken (добавьте его в
                                            // Manager!)
                                            GooglePlayBillingManager.launchPurchaseFlow(
                                                    activity,
                                                    selectedOfferToken!!
                                            ) { success, error ->
                                                if (success || error == null) {
                                                    scope.launch {
                                                        val isPremium =
                                                                GooglePlayBillingManager
                                                                        .isPremiumUser()
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
                                    colors =
                                            ButtonDefaults.buttonColors(
                                                    containerColor =
                                                            Color(0xFF1A1A1A), // Black button
                                                    contentColor = Color(0xFFFFD700) // Gold text
                                            ),
                                    elevation =
                                            ButtonDefaults.buttonElevation(defaultElevation = 6.dp)
                            ) {
                                if (isPurchasing) {
                                    CircularProgressIndicator(
                                            modifier = Modifier.size(24.dp),
                                            color = Color(0xFFFFD700)
                                    )
                                } else {
                                    Text(
                                            text =
                                                    stringResource(R.string.continue_button)
                                                            .uppercase(),
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
                                                    errorMessage =
                                                            context.getString(
                                                                    R.string.restore_failed
                                                            )
                                                }
                                                isPurchasing = false
                                            }
                                        }
                                ) {
                                    Text(
                                            stringResource(R.string.restore_purchases),
                                            color =
                                                    MaterialTheme.colorScheme.onSurface.copy(
                                                            alpha = 0.6f
                                                    ),
                                            fontSize = 13.sp
                                    )
                                }
                            }

                            Text(
                                    text = stringResource(R.string.subscription_terms),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                                    textAlign = TextAlign.Center,
                                    lineHeight = 14.sp
                            )
                        }
                    }
                }

                // --- КРЕСТИК (CLOSE BUTTON) ---
                // Он объявлен ПОСЛЕ Column, поэтому будет всегда сверху
                IconButton(
                        onClick = { if (!isPurchasing) onDismiss() },
                        modifier =
                                Modifier.align(Alignment.TopEnd)
                                        .statusBarsPadding()
                                        .padding(top = 16.dp, end = 16.dp)
                                        .clip(CircleShape)
                                        .background(
                                                MaterialTheme.colorScheme.surface.copy(alpha = 0.8f)
                                        ) // Полупрозрачный фон
                                        .size(36.dp)
                ) {
                    Icon(
                            Icons.Default.Close,
                            contentDescription = stringResource(R.string.close),
                            tint = MaterialTheme.colorScheme.onSurface,
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
        subtitle: String,
        badge: String?,
        isSelected: Boolean,
        isBestValue: Boolean,
        onClick: () -> Unit
) {
    val borderColor =
            if (isSelected) Color(0xFFFFD700)
            else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
    val borderWidth = if (isSelected) 2.dp else 1.dp
    val backgroundColor =
            if (isSelected) Color(0xFFFFD700).copy(alpha = 0.05f) else Color.Transparent

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
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                    modifier = Modifier.size(24.dp)
            )

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                )
                Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
            }

            Column(horizontalAlignment = Alignment.End) {
                Text(
                        text = price,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                )
                if (isBestValue && badge != null) {
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
        icon: androidx.compose.ui.graphics.vector.ImageVector,
        title: String,
        subtitle: String
) {
    Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
                shape = CircleShape,
                color = Color(0xFFFFD700).copy(alpha = 0.2f),
                modifier = Modifier.size(40.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = Color(0xFFB8860B), // Dark Gold
                        modifier = Modifier.size(24.dp)
                )
            }
        }
        Spacer(modifier = Modifier.width(16.dp))
        Column {
            Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold
            )
            Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
    }
}
