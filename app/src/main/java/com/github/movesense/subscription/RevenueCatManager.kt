package com.github.movesense.subscription

import android.content.Context
import android.util.Log
import com.revenuecat.purchases.CustomerInfo
import com.revenuecat.purchases.Purchases
import com.revenuecat.purchases.PurchasesConfiguration
import com.revenuecat.purchases.PurchasesError
import com.revenuecat.purchases.interfaces.ReceiveCustomerInfoCallback
import com.revenuecat.purchases.interfaces.UpdatedCustomerInfoListener
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

object RevenueCatManager {
    private const val TAG = "RevenueCatManager"

    // Замените на ваш API ключ из RevenueCat Dashboard
    private const val REVENUECAT_API_KEY = "YOUR_REVENUECAT_API_KEY"

    // Entitlement ID из RevenueCat Dashboard
    const val ENTITLEMENT_ID = "premium"

    /**
     * Инициализация RevenueCat SDK
     * Вызывайте один раз при старте приложения
     */
    fun initialize(context: Context, userId: String? = null) {
        if (Purchases.isConfigured) {
            Log.d(TAG, "RevenueCat already configured")
            return
        }

        val configuration = PurchasesConfiguration.Builder(context, REVENUECAT_API_KEY)
            .apply {
                if (userId != null) {
                    appUserID(userId)
                }
            }
            .build()

        Purchases.configure(configuration)
        Log.d(TAG, "✅ RevenueCat initialized for user: ${userId ?: "anonymous"}")
    }

    /**
     * Проверка статуса подписки
     */
    suspend fun isPremiumUser(): Boolean = suspendCancellableCoroutine { continuation ->
        Purchases.sharedInstance.getCustomerInfo(object : ReceiveCustomerInfoCallback {
            override fun onReceived(customerInfo: CustomerInfo) {
                val isPremium = customerInfo.entitlements.active.containsKey(ENTITLEMENT_ID)
                Log.d(TAG, "Premium status: $isPremium")
                continuation.resume(isPremium)
            }

            override fun onError(error: PurchasesError) {
                Log.e(TAG, "Error checking premium status: ${error.underlyingErrorMessage}")
                continuation.resume(false)
            }
        })
    }

    /**
     * Flow для отслеживания изменений статуса подписки
     */
    fun observePremiumStatus(): Flow<Boolean> = callbackFlow {
        val listener = UpdatedCustomerInfoListener { customerInfo ->
            val isPremium = customerInfo.entitlements.active.containsKey(ENTITLEMENT_ID)
            trySend(isPremium)
        }

        Purchases.sharedInstance.updatedCustomerInfoListener = listener

        // Отправляем текущий статус
        Purchases.sharedInstance.getCustomerInfo(object : ReceiveCustomerInfoCallback {
            override fun onReceived(customerInfo: CustomerInfo) {
                val isPremium = customerInfo.entitlements.active.containsKey(ENTITLEMENT_ID)
                trySend(isPremium)
            }

            override fun onError(error: PurchasesError) {
                Log.e(TAG, "Error getting customer info: ${error.underlyingErrorMessage}")
            }
        })

        awaitClose {
            Purchases.sharedInstance.updatedCustomerInfoListener = null
        }
    }

    /**
     * Получение информации о клиенте
     */
    suspend fun getCustomerInfo(): CustomerInfo? = suspendCancellableCoroutine { continuation ->
        Purchases.sharedInstance.getCustomerInfo(object : ReceiveCustomerInfoCallback {
            override fun onReceived(customerInfo: CustomerInfo) {
                continuation.resume(customerInfo)
            }

            override fun onError(error: PurchasesError) {
                Log.e(TAG, "Error getting customer info: ${error.underlyingErrorMessage}")
                continuation.resume(null)
            }
        })
    }

    /**
     * Логин пользователя в RevenueCat
     */
    suspend fun login(userId: String) {
        try {
            Purchases.sharedInstance.logIn(
                newAppUserID = userId,
                onSuccess = { customerInfo, created ->
                    Log.d(TAG, "User logged in: $userId, created: $created")
                },
                onError = { error ->
                    Log.e(TAG, "Error logging in: ${error.underlyingErrorMessage}")
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error logging in: ${e.message}")
        }
    }

    /**
     * Логаут пользователя из RevenueCat
     */
    suspend fun logout() {
        try {
            Purchases.sharedInstance.logOut(
                onSuccess = { customerInfo ->
                    Log.d(TAG, "User logged out successfully")
                },
                onError = { error ->
                    Log.e(TAG, "Error logging out: ${error.underlyingErrorMessage}")
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error logging out: ${e.message}")
        }
    }

    /**
     * Восстановление покупок
     */
    suspend fun restorePurchases(): Boolean = suspendCancellableCoroutine { continuation ->
        Purchases.sharedInstance.restorePurchases(
            onSuccess = { customerInfo ->
                val isPremium = customerInfo.entitlements.active.containsKey(ENTITLEMENT_ID)
                Log.d(TAG, "Purchases restored, premium: $isPremium")
                continuation.resume(true)
            },
            onError = { error ->
                Log.e(TAG, "Error restoring purchases: ${error.underlyingErrorMessage}")
                continuation.resume(false)
            }
        )
    }
}