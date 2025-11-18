package com.github.movesense.subscription

import android.app.Activity
import android.content.Context
import android.util.Log
import com.android.billingclient.api.*
import com.android.billingclient.api.BillingClient.BillingResponseCode
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Менеджер для работы с Google Play Billing
 * Заменяет RevenueCat для Android-only приложения
 */
object GooglePlayBillingManager {

    private const val TAG = "BillingManager"

    // Product ID твоей подписки в Google Play Console
    // Измени на свой Product ID!
    const val PREMIUM_PRODUCT_ID = "chess_premium_annual"

    private var billingClient: BillingClient? = null
    private var productDetails: ProductDetails? = null

    // StateFlow для отслеживания premium статуса
    private val _isPremiumFlow = MutableStateFlow(false)
    val isPremiumFlow: StateFlow<Boolean> = _isPremiumFlow

    /**
     * Инициализация Billing Client
     * Вызывай в Application.onCreate() или MainActivity.onCreate()
     */
    fun initialize(context: Context, onReady: () -> Unit = {}) {
        if (billingClient != null) {
            Log.d(TAG, "BillingClient already initialized")
            return
        }

        billingClient = BillingClient.newBuilder(context)
            .setListener { billingResult, purchases ->
                if (billingResult.responseCode == BillingResponseCode.OK && purchases != null) {
                    handlePurchases(purchases)
                }
            }
            .enablePendingPurchases()
            .build()

        startConnection(onReady)
    }

    /**
     * Подключение к Google Play
     */
    private fun startConnection(onReady: () -> Unit) {
        billingClient?.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(billingResult: BillingResult) {
                if (billingResult.responseCode == BillingResponseCode.OK) {
                    Log.d(TAG, "✅ Billing client connected")
                    loadProducts()
                    checkPremiumStatus()
                    onReady()
                } else {
                    Log.e(TAG, "❌ Billing setup failed: ${billingResult.debugMessage}")
                }
            }

            override fun onBillingServiceDisconnected() {
                Log.w(TAG, "⚠️ Billing service disconnected, will retry...")
                // Автоматически переподключится при следующем вызове
            }
        })
    }

    /**
     * Загрузка информации о продукте
     */
    private fun loadProducts() {
        val productList = listOf(
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(PREMIUM_PRODUCT_ID)
                .setProductType(BillingClient.ProductType.SUBS)
                .build()
        )

        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(productList)
            .build()

        billingClient?.queryProductDetailsAsync(params) { billingResult, productDetailsList ->
            if (billingResult.responseCode == BillingResponseCode.OK) {
                productDetails = productDetailsList.firstOrNull()
                if (productDetails != null) {
                    Log.d(TAG, "✅ Product loaded: ${productDetails?.title}")
                } else {
                    Log.e(TAG, "❌ No products found for ID: $PREMIUM_PRODUCT_ID")
                    Log.e(TAG, "   Check Product ID in Google Play Console!")
                }
            } else {
                Log.e(TAG, "❌ Failed to load products: ${billingResult.debugMessage}")
            }
        }
    }

    /**
     * Получить информацию о продукте (для отображения в UI)
     */
    suspend fun getProductDetails(): ProductDetails? = suspendCancellableCoroutine { continuation ->
        if (productDetails != null) {
            continuation.resume(productDetails)
            return@suspendCancellableCoroutine
        }

        // Если еще не загружено - загрузим
        val productList = listOf(
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(PREMIUM_PRODUCT_ID)
                .setProductType(BillingClient.ProductType.SUBS)
                .build()
        )

        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(productList)
            .build()

        billingClient?.queryProductDetailsAsync(params) { billingResult, productDetailsList ->
            if (billingResult.responseCode == BillingResponseCode.OK) {
                productDetails = productDetailsList.firstOrNull()
                continuation.resume(productDetails)
            } else {
                Log.e(TAG, "Failed to get product details: ${billingResult.debugMessage}")
                continuation.resume(null)
            }
        } ?: continuation.resume(null)
    }

    /**
     * Запуск процесса покупки
     */
    fun launchPurchaseFlow(activity: Activity, onResult: (Boolean, String?) -> Unit) {
        val currentProductDetails = productDetails

        if (currentProductDetails == null) {
            Log.e(TAG, "Product details not loaded")
            onResult(false, "Product not available")
            return
        }

        val client = billingClient
        if (client == null || !client.isReady) {
            Log.e(TAG, "Billing client not ready")
            onResult(false, "Billing not ready")
            return
        }

        // Получаем offer token (для подписок)
        val offerToken = currentProductDetails.subscriptionOfferDetails?.firstOrNull()?.offerToken

        if (offerToken == null) {
            Log.e(TAG, "No offer token available")
            onResult(false, "Subscription offer not available")
            return
        }

        val productDetailsParams = BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(currentProductDetails)
            .setOfferToken(offerToken)
            .build()

        val billingFlowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(productDetailsParams))
            .build()

        val billingResult = client.launchBillingFlow(activity, billingFlowParams)

        if (billingResult.responseCode != BillingResponseCode.OK) {
            Log.e(TAG, "Failed to launch billing flow: ${billingResult.debugMessage}")
            onResult(false, billingResult.debugMessage)
        }
        // Результат придет в PurchasesUpdatedListener
    }

    /**
     * Обработка покупок
     */
    private fun handlePurchases(purchases: List<Purchase>) {
        purchases.forEach { purchase ->
            if (purchase.purchaseState == Purchase.PurchaseState.PURCHASED) {
                if (!purchase.isAcknowledged) {
                    acknowledgePurchase(purchase)
                }

                // Обновляем статус premium
                if (purchase.products.contains(PREMIUM_PRODUCT_ID)) {
                    _isPremiumFlow.value = true
                    Log.d(TAG, "✅ User is now premium!")
                }
            }
        }
    }

    /**
     * Acknowledge покупки (обязательно!)
     */
    private fun acknowledgePurchase(purchase: Purchase) {
        val params = AcknowledgePurchaseParams.newBuilder()
            .setPurchaseToken(purchase.purchaseToken)
            .build()

        billingClient?.acknowledgePurchase(params) { billingResult ->
            if (billingResult.responseCode == BillingResponseCode.OK) {
                Log.d(TAG, "✅ Purchase acknowledged")
            } else {
                Log.e(TAG, "❌ Failed to acknowledge: ${billingResult.debugMessage}")
            }
        }
    }

    /**
     * Проверка статуса подписки
     */
    fun checkPremiumStatus() {
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.SUBS)
            .build()

        billingClient?.queryPurchasesAsync(params) { billingResult, purchases ->
            if (billingResult.responseCode == BillingResponseCode.OK) {
                val isPremium = purchases.any { purchase ->
                    purchase.products.contains(PREMIUM_PRODUCT_ID) &&
                            purchase.purchaseState == Purchase.PurchaseState.PURCHASED
                }

                _isPremiumFlow.value = isPremium
                Log.d(TAG, "Premium status: $isPremium")
            } else {
                Log.e(TAG, "Failed to query purchases: ${billingResult.debugMessage}")
                _isPremiumFlow.value = false
            }
        }
    }

    /**
     * Проверка является ли пользователь premium (suspend функция)
     */
    suspend fun isPremiumUser(): Boolean = suspendCancellableCoroutine { continuation ->
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.SUBS)
            .build()

        billingClient?.queryPurchasesAsync(params) { billingResult, purchases ->
            if (billingResult.responseCode == BillingResponseCode.OK) {
                val isPremium = purchases.any { purchase ->
                    purchase.products.contains(PREMIUM_PRODUCT_ID) &&
                            purchase.purchaseState == Purchase.PurchaseState.PURCHASED
                }
                continuation.resume(isPremium)
            } else {
                Log.e(TAG, "Error checking premium status: ${billingResult.debugMessage}")
                continuation.resume(false)
            }
        } ?: continuation.resume(false)
    }

    /**
     * Flow для реактивного отслеживания premium статуса
     */
    fun observePremiumStatus(): Flow<Boolean> = callbackFlow {
        // Отправляем текущее значение
        trySend(_isPremiumFlow.value)

        // Подписываемся на изменения
        val job = kotlinx.coroutines.GlobalScope.launch {
            _isPremiumFlow.collect { isPremium ->
                trySend(isPremium)
            }
        }

        // Периодически проверяем статус
        checkPremiumStatus()

        awaitClose {
            job.cancel()
        }
    }

    /**
     * Восстановление покупок
     */
    suspend fun restorePurchases(): Boolean = suspendCancellableCoroutine { continuation ->
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.SUBS)
            .build()

        billingClient?.queryPurchasesAsync(params) { billingResult, purchases ->
            if (billingResult.responseCode == BillingResponseCode.OK) {
                handlePurchases(purchases)

                val isPremium = purchases.any { purchase ->
                    purchase.products.contains(PREMIUM_PRODUCT_ID) &&
                            purchase.purchaseState == Purchase.PurchaseState.PURCHASED
                }

                Log.d(TAG, "Purchases restored. Premium: $isPremium")
                continuation.resume(true)
            } else {
                Log.e(TAG, "Failed to restore purchases: ${billingResult.debugMessage}")
                continuation.resume(false)
            }
        } ?: continuation.resume(false)
    }

    /**
     * Освобождение ресурсов
     */
    fun destroy() {
        billingClient?.endConnection()
        billingClient = null
        productDetails = null
        Log.d(TAG, "Billing client destroyed")
    }
}