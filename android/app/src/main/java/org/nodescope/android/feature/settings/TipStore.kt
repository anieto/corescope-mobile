package org.nodescope.android.feature.settings

import android.app.Activity
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.android.billingclient.api.*
import kotlin.coroutines.resume
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

/** Same product IDs, order and names as iOS `DevelopmentTipStore.Tip` and the Play Console products. */
enum class Tip(val productId: String) {
    COFFEE("com.btdev.nodescope.tip.coffee"),
    LUNCH("com.btdev.nodescope.tip.lunch"),
    DINNER("com.btdev.nodescope.tip.dinner");

    companion object { fun of(productId: String) = entries.firstOrNull { it.productId == productId } }
}

data class TipProduct(val tip: Tip, val title: String, val price: String, internal val details: ProductDetails?)

data class TipState(
    val loading: Boolean = false,
    val products: List<TipProduct> = emptyList(),
    val purchasing: Tip? = null,
    val error: String? = null,
    val thankYou: Boolean = false,
)

sealed interface TipOutcome {
    data object ThankYou : TipOutcome
    data object Pending : TipOutcome
    data object Cancelled : TipOutcome
    data class Failed(val message: String) : TipOutcome
}

/** Maps a Play purchase update to what the person sees, mirroring the iOS messages. */
fun tipOutcome(responseCode: Int, purchaseStates: List<Int>): TipOutcome = when (responseCode) {
    BillingClient.BillingResponseCode.OK -> when {
        purchaseStates.any { it == Purchase.PurchaseState.PURCHASED } -> TipOutcome.ThankYou
        purchaseStates.any { it == Purchase.PurchaseState.PENDING } -> TipOutcome.Pending
        else -> TipOutcome.Failed("The purchase couldn't be completed.")
    }
    BillingClient.BillingResponseCode.USER_CANCELED -> TipOutcome.Cancelled
    BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED ->
        TipOutcome.Failed("A previous tip is still being finalized. Please try again in a moment.")
    BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE, BillingClient.BillingResponseCode.SERVICE_DISCONNECTED,
    BillingClient.BillingResponseCode.NETWORK_ERROR -> TipOutcome.Failed("Google Play isn't reachable right now. Please try again.")
    BillingClient.BillingResponseCode.BILLING_UNAVAILABLE ->
        TipOutcome.Failed("Google Play purchases aren't available on this device or account.")
    else -> TipOutcome.Failed("The purchase couldn't be completed. Please try again.")
}

/**
 * Optional one-time tips through Google Play Billing (iOS uses StoreKit). Tips are consumed
 * after purchase so they can be given again and never unlock anything; like the iOS app,
 * there is no server-side receipt check because tips grant no entitlement.
 */
/**
 * The backwards-compatible purchase option, or the first one if none is marked as such
 * (the tips use a single "buy" option).
 */
internal fun ProductDetails.tipOffer(): ProductDetails.OneTimePurchaseOfferDetails? =
    oneTimePurchaseOfferDetails ?: oneTimePurchaseOfferDetailsList?.firstOrNull()

class TipViewModel(application: Application) : AndroidViewModel(application), PurchasesUpdatedListener {
    private val mutable = MutableStateFlow(TipState())
    val state: StateFlow<TipState> = mutable.asStateFlow()

    private val client = BillingClient.newBuilder(application).setListener(this)
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .enableAutoServiceReconnection()
        .build()

    private suspend fun connect(): Boolean {
        if (client.isReady) return true
        val result = suspendCancellableCoroutine { continuation ->
            client.startConnection(object : BillingClientStateListener {
                override fun onBillingSetupFinished(result: BillingResult) { if (continuation.isActive) continuation.resume(result) }
                override fun onBillingServiceDisconnected() { /* automatic reconnection is enabled */ }
            })
        }
        return result.responseCode == BillingClient.BillingResponseCode.OK
    }

    fun load(force: Boolean = false) {
        val current = mutable.value
        if (current.loading || (!force && current.products.isNotEmpty())) return
        mutable.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                if (!connect()) { mutable.update { it.copy(loading = false, error = "Tips couldn't be loaded. Please try again.") }; return@launch }
                val params = QueryProductDetailsParams.newBuilder().setProductList(Tip.entries.map {
                    QueryProductDetailsParams.Product.newBuilder().setProductId(it.productId).setProductType(BillingClient.ProductType.INAPP).build()
                }).build()
                val result = client.queryProductDetails(params)
                val products = result.productDetailsList.orEmpty().mapNotNull { details ->
                    val tip = Tip.of(details.productId) ?: return@mapNotNull null
                    val offer = details.tipOffer() ?: return@mapNotNull null
                    TipProduct(tip, details.name.ifBlank { tip.name.lowercase().replaceFirstChar { it.uppercase() } }, offer.formattedPrice, details)
                }.sortedBy { it.tip.ordinal }
                mutable.update {
                    it.copy(loading = false, products = products, error = when {
                        result.billingResult.responseCode != BillingClient.BillingResponseCode.OK -> "Tips couldn't be loaded. Please try again."
                        products.isEmpty() -> "Tips are temporarily unavailable."
                        else -> null
                    })
                }
                finishPendingTips()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                mutable.update { it.copy(loading = false, error = "Tips couldn't be loaded. Please try again.") }
            }
        }
    }

    fun purchase(activity: Activity, product: TipProduct) {
        val details = product.details ?: return
        if (mutable.value.purchasing != null) return
        val offerToken = details.tipOffer()?.offerToken
        val flow = BillingFlowParams.newBuilder().setProductDetailsParamsList(listOf(
            BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(details).apply { offerToken?.let(::setOfferToken) }.build()
        )).build()
        mutable.update { it.copy(purchasing = product.tip, error = null) }
        val result = client.launchBillingFlow(activity, flow)
        if (result.responseCode != BillingClient.BillingResponseCode.OK) handle(result, null)
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) = handle(result, purchases)

    private fun handle(result: BillingResult, purchases: List<Purchase>?) {
        val tips = purchases.orEmpty().filter { purchase -> purchase.products.any { Tip.of(it) != null } }
        when (val outcome = tipOutcome(result.responseCode, tips.map { it.purchaseState })) {
            TipOutcome.ThankYou -> mutable.update { it.copy(purchasing = null, thankYou = true) }
            TipOutcome.Pending -> mutable.update { it.copy(purchasing = null, error = "The purchase is pending approval.") }
            TipOutcome.Cancelled -> mutable.update { it.copy(purchasing = null) }
            is TipOutcome.Failed -> mutable.update { it.copy(purchasing = null, error = outcome.message) }
        }
        viewModelScope.launch { tips.filter { it.purchaseState == Purchase.PurchaseState.PURCHASED }.forEach { consume(it) } }
    }

    /** Completed tips left unconsumed (app closed mid-purchase, or a pending tip that later cleared). */
    private suspend fun finishPendingTips() {
        val purchases = client.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build())
        purchases.purchasesList.filter { purchase -> purchase.purchaseState == Purchase.PurchaseState.PURCHASED && purchase.products.any { Tip.of(it) != null } }
            .forEach { consume(it) }
    }

    private suspend fun consume(purchase: Purchase) {
        client.consumePurchase(ConsumeParams.newBuilder().setPurchaseToken(purchase.purchaseToken).build())
    }

    fun dismissThankYou() = mutable.update { it.copy(thankYou = false) }

    override fun onCleared() { client.endConnection() }
}
