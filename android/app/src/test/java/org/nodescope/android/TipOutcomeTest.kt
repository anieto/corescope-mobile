package org.nodescope.android

import com.android.billingclient.api.BillingClient.BillingResponseCode
import com.android.billingclient.api.Purchase.PurchaseState
import org.junit.Assert.*
import org.junit.Test
import org.nodescope.android.feature.settings.*

class TipOutcomeTest {
    @Test fun purchaseUpdatesMapToWhatThePersonSees() {
        assertEquals(TipOutcome.ThankYou, tipOutcome(BillingResponseCode.OK, listOf(PurchaseState.PURCHASED)))
        assertEquals(TipOutcome.Pending, tipOutcome(BillingResponseCode.OK, listOf(PurchaseState.PENDING)))
        assertEquals(TipOutcome.Cancelled, tipOutcome(BillingResponseCode.USER_CANCELED, emptyList()))
        assertEquals(TipOutcome.Failed("The purchase couldn't be completed."), tipOutcome(BillingResponseCode.OK, emptyList()))
        assertTrue(tipOutcome(BillingResponseCode.NETWORK_ERROR, emptyList()) is TipOutcome.Failed)
        assertTrue(tipOutcome(BillingResponseCode.ITEM_ALREADY_OWNED, emptyList()) is TipOutcome.Failed)
    }

    @Test fun productIdsMatchIosAndThePlayConsole() {
        assertEquals(listOf("com.btdev.nodescope.tip.coffee", "com.btdev.nodescope.tip.lunch", "com.btdev.nodescope.tip.dinner"),
            Tip.entries.map { it.productId })
        assertEquals(Tip.LUNCH, Tip.of("com.btdev.nodescope.tip.lunch"))
        assertNull(Tip.of("other"))
    }
}
