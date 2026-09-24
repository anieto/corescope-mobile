package org.nodescope.android

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test
import org.nodescope.android.core.design.NodeScopeTheme
import org.nodescope.android.core.model.*
import org.nodescope.android.core.network.*
import org.nodescope.android.core.storage.Appearance
import org.nodescope.android.feature.packets.PacketScreen

class PacketUiTest {
    @get:Rule val compose = createAndroidComposeRule<UiTestActivity>()
    @Test fun transmissionsAreGroupedLabeledAndFilterable() {
        val now = System.currentTimeMillis()
        val packet = LivePacket(1, "testhash", 4, "ADVERT", "observer", "North observer", null, 4.5, -100.0, listOf("aa"), emptyList(), null, now, true,
            payloadName = "Hilltop")
        val sameTransmission = packet.copy(id = 3, observerId = "observer2", observerName = "South observer", receivedAt = now + 1_000)
        var opened: String? = null
        compose.setContent { NodeScopeTheme(Appearance.DARK) {
            PacketScreen(LiveFeedState(AnalyzerSelection("example.org", null), LiveConnection.LIVE,
                listOf(sameTransmission, packet, packet.copy(id = 2, hash = "other", typeName = "ACK", isLive = false, payloadName = null))), {},
                onGroup = { opened = it })
        } }
        compose.onNodeWithText("Listening for live traffic").assertIsDisplayed()
        compose.onNodeWithText("2 transmissions").assertIsDisplayed()
        compose.onNodeWithText("3 observations").assertIsDisplayed()
        compose.onNodeWithText("Hilltop").assertIsDisplayed()
        compose.onNodeWithText("Recent").assertIsDisplayed() // history is never shown as live
        compose.onNodeWithContentDescription("Filter live packets, 0 active").performClick()
        // The sheet's option is the last "ACK" node; the other is the list row behind it.
        compose.onAllNodesWithText("ACK").onLast().performClick()
        compose.onNodeWithText("Hilltop").assertDoesNotExist()
        compose.onNodeWithText("Reset").performClick()
        compose.onNodeWithText("Hilltop").assertExists()
        androidx.test.espresso.Espresso.pressBack()
        compose.onNodeWithText("Hilltop").performClick()
        compose.runOnIdle { org.junit.Assert.assertEquals("testhash", opened) }
    }
}
