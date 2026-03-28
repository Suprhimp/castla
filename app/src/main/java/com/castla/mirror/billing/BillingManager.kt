package com.castla.mirror.billing

import android.app.Activity
import android.content.Context
import android.util.Log
import com.android.billingclient.api.*

/**
 * Wraps Google Play Billing for a single one-time in-app purchase ("castla_pro").
 *
 * - On init: connects BillingClient, queries existing purchases (single source of truth).
 * - SharedPreferences is only an offline cache — Google Play receipts always overwrite it.
 * - Call [launchPurchaseFlow] from an Activity to start the purchase UI.
 */
class BillingManager(
    private val context: Context
) : PurchasesUpdatedListener {

    companion object {
        private const val TAG = "BillingManager"
        const val PRODUCT_ID = "castla_pro"
    }

    private var billingClient: BillingClient? = null
    private var productDetails: ProductDetails? = null

    fun init() {
        val client = BillingClient.newBuilder(context)
            .setListener(this)
            .enablePendingPurchases()
            .build()
        billingClient = client

        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    Log.i(TAG, "Billing connected")
                    queryExistingPurchases()
                    queryProductDetails()
                } else {
                    Log.w(TAG, "Billing setup failed: ${result.debugMessage}")
                }
            }

            override fun onBillingServiceDisconnected() {
                Log.w(TAG, "Billing disconnected — will retry on next operation")
            }
        })
    }

    /**
     * Query Google Play for existing purchases.
     * This is the single source of truth — overwrites SharedPreferences cache.
     */
    private fun queryExistingPurchases() {
        val client = billingClient ?: return
        client.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder()
                .setProductType(BillingClient.ProductType.INAPP)
                .build()
        ) { result, purchases ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                val hasPro = purchases.any {
                    it.products.contains(PRODUCT_ID) &&
                        it.purchaseState == Purchase.PurchaseState.PURCHASED
                }
                LicenseManager.setPremium(hasPro, context)
                Log.i(TAG, "Purchase query: isPremium=$hasPro (${purchases.size} purchases)")

                // Acknowledge any unacknowledged purchases
                purchases.filter {
                    it.purchaseState == Purchase.PurchaseState.PURCHASED && !it.isAcknowledged
                }.forEach { purchase ->
                    acknowledgePurchase(purchase)
                }
            } else {
                // Network error — trust cached SharedPreferences value
                Log.w(TAG, "Purchase query failed: ${result.debugMessage}, trusting cache")
            }
        }
    }

    private fun queryProductDetails() {
        val client = billingClient ?: return
        val productList = listOf(
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(PRODUCT_ID)
                .setProductType(BillingClient.ProductType.INAPP)
                .build()
        )
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(productList)
            .build()

        client.queryProductDetailsAsync(params) { result, detailsList ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK && detailsList.isNotEmpty()) {
                productDetails = detailsList.first()
                Log.i(TAG, "Product details loaded: ${productDetails?.title}")
            } else {
                Log.w(TAG, "Product details query failed: ${result.debugMessage}")
            }
        }
    }

    /**
     * Launch the Google Play purchase flow.
     * Must be called from a foreground Activity.
     */
    fun launchPurchaseFlow(activity: Activity): Boolean {
        val client = billingClient ?: return false
        val details = productDetails

        if (details == null) {
            Log.w(TAG, "Product details not loaded yet")
            return false
        }

        val flowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(
                listOf(
                    BillingFlowParams.ProductDetailsParams.newBuilder()
                        .setProductDetails(details)
                        .build()
                )
            )
            .build()

        val result = client.launchBillingFlow(activity, flowParams)
        return result.responseCode == BillingClient.BillingResponseCode.OK
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: List<Purchase>?) {
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> {
                purchases?.forEach { purchase ->
                    if (purchase.purchaseState == Purchase.PurchaseState.PURCHASED) {
                        if (purchase.products.contains(PRODUCT_ID)) {
                            LicenseManager.setPremium(true, context)
                            Log.i(TAG, "Purchase successful!")
                        }
                        if (!purchase.isAcknowledged) {
                            acknowledgePurchase(purchase)
                        }
                    }
                }
            }
            BillingClient.BillingResponseCode.USER_CANCELED -> {
                Log.i(TAG, "Purchase cancelled by user")
            }
            else -> {
                Log.w(TAG, "Purchase failed: ${result.responseCode} - ${result.debugMessage}")
            }
        }
    }

    private fun acknowledgePurchase(purchase: Purchase) {
        val client = billingClient ?: return
        val params = AcknowledgePurchaseParams.newBuilder()
            .setPurchaseToken(purchase.purchaseToken)
            .build()
        client.acknowledgePurchase(params) { result ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                Log.i(TAG, "Purchase acknowledged")
            } else {
                Log.w(TAG, "Acknowledge failed: ${result.debugMessage}")
            }
        }
    }

    fun destroy() {
        billingClient?.endConnection()
        billingClient = null
    }
}