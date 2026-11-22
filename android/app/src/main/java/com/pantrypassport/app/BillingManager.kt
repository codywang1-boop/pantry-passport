package com.pantrypassport.app

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import org.json.JSONObject

/**
 * Pantry Passport Premium: a Google Play auto-renewing subscription.
 *
 * Play Console setup this code expects:
 *   Subscription product ID: pantry_premium
 *   Base plan: monthly, auto-renewing, 1 month, $4.99
 *   Offer on that base plan: free trial, 14 days, new customers only
 *
 * Google Play runs the free trial (one per Google account) and shows the price in the
 * buyer's local currency. Prices are read from Play, never hard-coded.
 */
class BillingManager(
    private val activity: Activity,
    private val onChange: (String) -> Unit
) : PurchasesUpdatedListener {

    private val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    @Volatile private var connecting = false
    @Volatile private var available = true
    @Volatile private var premium = prefs.getBoolean(KEY_PREMIUM, false)
    @Volatile private var pending = false
    @Volatile private var message = ""
    @Volatile private var productDetails: ProductDetails? = null
    @Volatile private var offerToken: String? = null
    @Volatile private var price: String? = null
    @Volatile private var trialDays = 0

    private val client: BillingClient = BillingClient.newBuilder(activity)
        .setListener(this)
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .enableAutoServiceReconnection()
        .build()

    fun start() {
        if (client.isReady) {
            refresh()
            return
        }
        if (connecting) return
        connecting = true
        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                connecting = false
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    available = true
                    message = ""
                    queryProduct()
                    queryPurchases()
                } else {
                    available = result.responseCode != BillingClient.BillingResponseCode.BILLING_UNAVAILABLE
                    message = describe(result)
                    publish()
                }
            }

            override fun onBillingServiceDisconnected() {
                connecting = false
            }
        })
    }

    fun refresh() {
        if (!client.isReady) {
            start()
            return
        }
        if (productDetails == null) queryProduct()
        queryPurchases()
    }

    fun end() {
        client.endConnection()
    }

    fun buy() {
        val product = productDetails
        val token = offerToken
        if (product == null || token == null) {
            message = if (available) "Premium is still loading. Please try again in a moment."
            else "Google Play billing isn’t available on this device or account."
            publish()
            if (available) refresh()
            return
        }
        val params = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(
                listOf(
                    BillingFlowParams.ProductDetailsParams.newBuilder()
                        .setProductDetails(product)
                        .setOfferToken(token)
                        .build()
                )
            )
            .build()
        activity.runOnUiThread {
            val result = client.launchBillingFlow(activity, params)
            if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                message = describe(result)
                publish()
            }
        }
    }

    fun stateJson(): String = JSONObject()
        .put("ready", client.isReady && productDetails != null && offerToken != null)
        .put("available", available)
        .put("premium", premium)
        .put("pending", pending)
        .put("price", price ?: JSONObject.NULL)
        .put("trialDays", trialDays)
        .put("message", message)
        .toString()

    // ---- Google Play callbacks ---------------------------------------------------------

    override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) {
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> {
                message = ""
                handlePurchases(purchases.orEmpty(), fullList = false)
            }
            BillingClient.BillingResponseCode.USER_CANCELED -> {
                message = ""
                publish()
            }
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> queryPurchases()
            else -> {
                message = describe(result)
                publish()
            }
        }
    }

    private fun queryProduct() {
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(
                listOf(
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(PRODUCT_ID)
                        .setProductType(BillingClient.ProductType.SUBS)
                        .build()
                )
            )
            .build()
        client.queryProductDetailsAsync(params) { result, details ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                val product = details.productDetailsList.firstOrNull { it.productId == PRODUCT_ID }
                productDetails = product
                pickOffer(product)
                if (product == null) message = "Premium isn’t available yet. Please try again later."
            } else {
                message = describe(result)
            }
            publish()
        }
    }

    /** Uses the free-trial offer when this account can still get it, otherwise the plain monthly plan. */
    private fun pickOffer(product: ProductDetails?) {
        val offers = product?.subscriptionOfferDetails.orEmpty().filter { it.basePlanId == BASE_PLAN }
            .ifEmpty { product?.subscriptionOfferDetails.orEmpty() }
        val trial = offers.firstOrNull { o ->
            o.offerId != null && o.pricingPhases.pricingPhaseList.any { it.priceAmountMicros == 0L }
        }
        val base = offers.firstOrNull { it.offerId == null } ?: offers.firstOrNull()
        val chosen = trial ?: base
        offerToken = chosen?.offerToken
        price = base?.pricingPhases?.pricingPhaseList?.lastOrNull()?.formattedPrice
            ?: chosen?.pricingPhases?.pricingPhaseList?.lastOrNull()?.formattedPrice
        trialDays = trial?.pricingPhases?.pricingPhaseList
            ?.firstOrNull { it.priceAmountMicros == 0L }?.billingPeriod?.let { daysIn(it) } ?: 0
    }

    private fun daysIn(period: String): Int {
        // ISO 8601 period, e.g. P14D, P2W, P1M
        val n = period.filter { it.isDigit() }.toIntOrNull() ?: return 0
        return when {
            period.endsWith("D") -> n
            period.endsWith("W") -> n * 7
            period.endsWith("M") -> n * 30
            else -> 0
        }
    }

    private fun queryPurchases() {
        client.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.SUBS).build()
        ) { result, purchases ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                handlePurchases(purchases, fullList = true)
            } else {
                message = describe(result)
                publish()
            }
        }
    }

    private fun handlePurchases(purchases: List<Purchase>, fullList: Boolean) {
        val ours = purchases.filter { PRODUCT_ID in it.products }
        val active = ours.any { it.purchaseState == Purchase.PurchaseState.PURCHASED }
        pending = ours.any { it.purchaseState == Purchase.PurchaseState.PENDING }
        if (fullList || active) setPremium(active)

        // Google refunds purchases that are not acknowledged within 3 days.
        ours.filter { it.purchaseState == Purchase.PurchaseState.PURCHASED && !it.isAcknowledged }
            .forEach { purchase ->
                val ack = AcknowledgePurchaseParams.newBuilder().setPurchaseToken(purchase.purchaseToken).build()
                client.acknowledgePurchase(ack) { r ->
                    if (r.responseCode != BillingClient.BillingResponseCode.OK) {
                        message = "Google Play hasn’t confirmed the subscription yet; it will retry next time the app opens."
                        publish()
                    }
                }
            }
        publish()
    }

    private fun setPremium(value: Boolean) {
        premium = value
        prefs.edit().putBoolean(KEY_PREMIUM, value).apply()
    }

    private fun publish() {
        onChange(stateJson())
    }

    private fun describe(result: BillingResult): String = when (result.responseCode) {
        BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE,
        BillingClient.BillingResponseCode.SERVICE_DISCONNECTED,
        BillingClient.BillingResponseCode.NETWORK_ERROR ->
            "Can’t reach Google Play right now. Check your connection and try again."
        BillingClient.BillingResponseCode.BILLING_UNAVAILABLE ->
            "Google Play billing isn’t available on this device or account."
        BillingClient.BillingResponseCode.ITEM_UNAVAILABLE ->
            "Premium isn’t available for your account."
        BillingClient.BillingResponseCode.DEVELOPER_ERROR ->
            "Purchase setup problem: ${result.debugMessage}"
        else -> "Google Play error ${result.responseCode}. Please try again."
    }

    companion object {
        const val PRODUCT_ID = "pantry_premium"
        const val BASE_PLAN = "monthly"
        private const val PREFS = "pantry_billing"
        private const val KEY_PREMIUM = "premium"
    }
}
