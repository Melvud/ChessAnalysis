package com.github.movesense.subscription

import android.app.Activity
import android.content.Context
import android.util.Log
import com.android.billingclient.api.*
import com.android.billingclient.api.BillingClient.BillingResponseCode
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Менеджер для работы с Google Play Billing
 * Поддерживает выбор тарифных планов (OfferToken) и синхронизацию с Firebase
 */
object GooglePlayBillingManager {

    private const val TAG = "BillingManager"

    // Твой ID продукта (подписки) в консоли
    const val PREMIUM_PRODUCT_ID = "chess_premium_annual"

    private var billingClient: BillingClient? = null
    private var productDetails: ProductDetails? = null

    // StateFlow для реактивного отслеживания статуса в приложении
    private val _isPremiumFlow = MutableStateFlow(false)
    val isPremiumFlow: StateFlow<Boolean> = _isPremiumFlow

    /**
     * Инициализация Billing Client.
     * Вызывать в MainActivity.onCreate() или Application.onCreate()
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
                } else if (billingResult.responseCode == BillingResponseCode.USER_CANCELED) {
                    Log.i(TAG, "User canceled purchase flow.")
                } else {
                    Log.e(TAG, "Purchase error: ${billingResult.debugMessage}")
                }
            }
            .enablePendingPurchases()
            .build()

        startConnection(onReady)
    }

    private fun startConnection(onReady: () -> Unit) {
        billingClient?.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(billingResult: BillingResult) {
                if (billingResult.responseCode == BillingResponseCode.OK) {
                    Log.d(TAG, "✅ Billing client connected")
                    // Сначала загружаем детали продукта
                    loadProducts {
                        // Потом проверяем, куплен ли он уже
                        checkPremiumStatus()
                        onReady()
                    }
                } else {
                    Log.e(TAG, "❌ Billing setup failed: ${billingResult.debugMessage}")
                }
            }

            override fun onBillingServiceDisconnected() {
                Log.w(TAG, "⚠️ Billing service disconnected. Will retry on next request.")
                // Можно добавить логику ретрая с задержкой, если критично
            }
        })
    }

    /**
     * Загружает детали продукта (цены, периоды) из Google Play
     */
    private fun loadProducts(onLoaded: () -> Unit = {}) {
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
                    Log.d(TAG, "   Offers: ${productDetails?.subscriptionOfferDetails?.size}")
                } else {
                    Log.e(TAG, "❌ No products found for ID: $PREMIUM_PRODUCT_ID. Check Google Play Console!")
                }
            } else {
                Log.e(TAG, "❌ Failed to load products: ${billingResult.debugMessage}")
            }
            onLoaded()
        }
    }

    /**
     * Получить ProductDetails для UI (suspend)
     */
    suspend fun getProductDetails(): ProductDetails? = suspendCancellableCoroutine { continuation ->
        if (productDetails != null) {
            continuation.resume(productDetails)
        } else {
            // Если null, пробуем загрузить еще раз
            loadProducts {
                continuation.resume(productDetails)
            }
        }
    }

    /**
     * ✅ НОВЫЙ МЕТОД: Запуск покупки с конкретным offerToken (Год или Месяц)
     */
    fun launchPurchaseFlow(activity: Activity, offerToken: String, onResult: (Boolean, String?) -> Unit) {
        val currentProductDetails = productDetails
        if (currentProductDetails == null) {
            onResult(false, "Product details not loaded")
            return
        }

        val params = BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(currentProductDetails)
            .setOfferToken(offerToken) // Используем выбранный токен!
            .build()

        val billingFlowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(params))
            .build()

        val responseCode = billingClient?.launchBillingFlow(activity, billingFlowParams)?.responseCode

        if (responseCode != BillingResponseCode.OK) {
            onResult(false, "Failed to launch flow: $responseCode")
        } else {
            // Flow launched successfully
            onResult(true, null)
        }
    }

    /**
     * Обработка списка покупок
     */
    private fun handlePurchases(purchases: List<Purchase>) {
        var isPremiumFound = false

        purchases.forEach { purchase ->
            if (purchase.purchaseState == Purchase.PurchaseState.PURCHASED) {
                // 1. Подтверждаем покупку (Acknowledge), если еще не подтверждена
                if (!purchase.isAcknowledged) {
                    acknowledgePurchase(purchase)
                }

                // 2. Проверяем, относится ли покупка к нашей подписке
                if (purchase.products.contains(PREMIUM_PRODUCT_ID)) {
                    isPremiumFound = true
                }
            }
        }

        // 3. Обновляем локальный статус и Firebase
        if (isPremiumFound) {
            Log.d(TAG, "✅ Premium subscription active")
            _isPremiumFlow.value = true
            syncPremiumStatusToFirebase(true)
        }
    }

    private fun acknowledgePurchase(purchase: Purchase) {
        val params = AcknowledgePurchaseParams.newBuilder()
            .setPurchaseToken(purchase.purchaseToken)
            .build()

        billingClient?.acknowledgePurchase(params) { billingResult ->
            if (billingResult.responseCode == BillingResponseCode.OK) {
                Log.d(TAG, "✅ Purchase acknowledged: ${purchase.orderId}")
            }
        }
    }

    /**
     * Проверка активных подписок.
     * Важно: Вызывать в onResume()
     */
    fun checkPremiumStatus() {
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.SUBS)
            .build()

        billingClient?.queryPurchasesAsync(params) { billingResult, purchases ->
            if (billingResult.responseCode == BillingResponseCode.OK) {
                // Ищем активную подписку
                val isPremiumActive = purchases.any { purchase ->
                    purchase.products.contains(PREMIUM_PRODUCT_ID) &&
                            purchase.purchaseState == Purchase.PurchaseState.PURCHASED
                }

                _isPremiumFlow.value = isPremiumActive
                Log.d(TAG, "Checking status... Premium Active: $isPremiumActive")

                // ✅ СИНХРОНИЗАЦИЯ: Обновляем Firebase всегда (и true, и false)
                // Это отключит премиум в базе, если подписка истекла.
                syncPremiumStatusToFirebase(isPremiumActive)

            } else {
                Log.e(TAG, "Failed to query purchases: ${billingResult.debugMessage}")
            }
        }
    }

    private fun syncPremiumStatusToFirebase(isPremium: Boolean) {
        val user = FirebaseAuth.getInstance().currentUser
        if (user != null) {
            FirebaseFirestore.getInstance()
                .collection("users")
                .document(user.uid)
                .update("isPremium", isPremium)
                .addOnFailureListener { e ->
                    Log.e(TAG, "Failed to sync premium status: ${e.message}")
                }
        }
    }

    suspend fun isPremiumUser(): Boolean = suspendCancellableCoroutine { continuation ->
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.SUBS)
            .build()

        billingClient?.queryPurchasesAsync(params) { billingResult, purchases ->
            if (billingResult.responseCode == BillingResponseCode.OK) {
                val isPremium = purchases.any { it.products.contains(PREMIUM_PRODUCT_ID) && it.purchaseState == Purchase.PurchaseState.PURCHASED }
                continuation.resume(isPremium)
            } else {
                continuation.resume(false)
            }
        } ?: continuation.resume(false)
    }

    suspend fun restorePurchases(): Boolean = suspendCancellableCoroutine { continuation ->
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.SUBS)
            .build()

        billingClient?.queryPurchasesAsync(params) { billingResult, purchases ->
            if (billingResult.responseCode == BillingResponseCode.OK) {
                handlePurchases(purchases) // Это обновит статус и Firebase
                val isPremium = purchases.any { it.products.contains(PREMIUM_PRODUCT_ID) && it.purchaseState == Purchase.PurchaseState.PURCHASED }
                continuation.resume(true)
            } else {
                continuation.resume(false)
            }
        } ?: continuation.resume(false)
    }

    fun observePremiumStatus(): Flow<Boolean> = callbackFlow {
        trySend(_isPremiumFlow.value)
        val job = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
            _isPremiumFlow.collect { isPremium ->
                trySend(isPremium)
            }
        }
        awaitClose { job.cancel() }
    }
}