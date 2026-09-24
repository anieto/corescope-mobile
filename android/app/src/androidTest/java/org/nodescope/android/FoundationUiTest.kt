package org.nodescope.android

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.nodescope.android.app.PendingLink
import org.nodescope.android.core.model.NodeScopeLink
import org.nodescope.android.core.network.LiveConnection
import org.nodescope.android.app.AppShell
import org.nodescope.android.core.network.SessionState
import org.nodescope.android.core.design.NodeScopeTheme
import org.nodescope.android.core.model.*
import org.nodescope.android.core.storage.*
import org.nodescope.android.feature.explore.NodeBrowser
import org.nodescope.android.feature.onboarding.SourceScreen
import org.nodescope.android.feature.settings.SettingsScreen

class FoundationUiTest {
    @get:Rule val compose = createAndroidComposeRule<UiTestActivity>()

    @Test fun chartValuesExposeExactCountsAndCanBeDismissed() {
        compose.setContent { NodeScopeTheme(Appearance.LIGHT) {
            org.nodescope.android.core.design.TimeChart("Packet activity",
                listOf(org.nodescope.android.core.design.TimeSeries(androidx.compose.ui.graphics.Color.Blue,
                    listOf(0L to 1234.0, 1000L to 5.0))), 0L, 1000L, true, true,
                formatValue = { "rounded" }, formatTime = { "time" })
        } }
        compose.onNodeWithContentDescription("Interactive plot: Packet activity", useUnmergedTree = true)
            .performTouchInput { click(androidx.compose.ui.geometry.Offset(1f, 1f)) }
        compose.onNodeWithText("1970-01-01T00:00:00Z · 1234").assertIsDisplayed()
        compose.onNodeWithText("View chart values").performClick()
        compose.onNodeWithText("1234").assertIsDisplayed()
        compose.onNodeWithText("1970-01-01T00:00:00Z").assertIsDisplayed()
        compose.onNodeWithText("Done").performClick()
        compose.onNodeWithText("Chart values").assertDoesNotExist()
    }

    @Test fun analyticsRangeRemainsAvailableAfterScrolling() {
        compose.setContent { NodeScopeTheme(Appearance.DARK) {
            var range by remember { mutableStateOf(org.nodescope.android.feature.nodes.AnalyticsRange.WEEK) }
            org.nodescope.android.feature.nodes.AnalyticsLayout(range, { range = it }) {
                items(80) { index -> androidx.compose.material3.Text("Analytics row $index") }
            }
        } }
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(79)
        compose.onNodeWithText("Time range").assertIsDisplayed()
        compose.onNodeWithText("24h").performClick()
        compose.onNodeWithText("24h").assertIsSelected()
    }

    @Test fun tabsAndDetailBackStackRemainIndependent() {
        val preferences = AppPreferences(onboarded = true)
        val snapshot = AnalyzerSnapshot(listOf(MeshNode("test", "Test node", "repeater", lastSeen = "2026-01-01")), 1, emptyMap(), null)
        compose.setContent {
            NodeScopeTheme(Appearance.SYSTEM) {
                AppShell(preferences, SessionState(snapshot = snapshot), {}, {}, {}, {}, { _ -> }, nodeLibrary = NodeLibrary())
            }
        }
        compose.onNodeWithText("Live packets").assertDoesNotExist()
        compose.onNodeWithText("Explore").performClick()
        compose.onNodeWithContentDescription("Search the network").performClick()
        compose.onNode(hasSetTextAction()).performTextInput("Test")
        compose.onNodeWithText("Test node").performClick()
        compose.onNodeWithText("Technical identity").performClick()
        compose.onNodeWithText("TEST").assertIsDisplayed()
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("APPEARANCE").assertIsDisplayed()
        compose.onNodeWithText("Channels").performClick()
        compose.onNodeWithText("Channels are unavailable in this preview.").assertIsDisplayed()
        compose.onNodeWithText("Explore").performClick()
        compose.onNodeWithText("TEST").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Recently Viewed").assertIsDisplayed()
        compose.onNodeWithText("Test node").assertIsDisplayed()
    }

    @Test fun pendingLinkOpensItsItemAndIsMarkedHandled() {
        val snapshot = AnalyzerSnapshot(listOf(MeshNode("linked", "Linked node", "room", lastSeen = "2026-09-22")), 1, emptyMap(), null)
        var handled: Long? = null
        compose.setContent { NodeScopeTheme(Appearance.SYSTEM) {
            AppShell(AppPreferences(onboarded = true), SessionState(snapshot = snapshot), {}, {}, {}, {}, { _ -> }, nodeLibrary = NodeLibrary(),
                pendingLink = PendingLink(7, NodeScopeLink.node("linked")), onLinkHandled = { handled = it })
        } }
        compose.waitUntil(5_000) { handled == 7L }
        compose.onNodeWithText("Technical identity").performClick()
        compose.onNodeWithText("LINKED").assertIsDisplayed() // node detail shows the public key
    }

        @Test fun favoriteNodesCanBeAddedOpenedAndRemovedFromExplore() {
        val library = NodeLibrary()
        val snapshot = AnalyzerSnapshot(listOf(MeshNode("favorite", "Saved node", "repeater", lastSeen = "2026-09-22")), 1, emptyMap(), null)
        compose.setContent { NodeScopeTheme(Appearance.DARK) {
            AppShell(AppPreferences(onboarded = true, destination = "EXPLORE"), SessionState(snapshot = snapshot), {}, {}, {}, {}, {}, nodeLibrary = library)
        } }
        compose.onNodeWithContentDescription("Add favorite node").performClick()
        compose.onNodeWithText("Saved node").performClick()
        compose.onNodeWithText("FAVORITE NODES").assertIsDisplayed()
        compose.onNodeWithText("Saved node").performClick()
        compose.onNodeWithContentDescription("Remove favorite").performClick()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("FAVORITE NODES").assertDoesNotExist()
        compose.onNodeWithText("Recently Viewed").assertIsDisplayed()
        compose.onNodeWithText("Clear").performClick()
        compose.onNodeWithText("Explore Your Mesh").assertIsDisplayed()
    }

    @Test fun savedNodesPersistAndStayScopedToTheirAnalyzer() {
        val context = compose.activity
        val a = "library-a-${System.nanoTime()}.test"
        val b = "library-b-${System.nanoTime()}.test"
        try {
            NodeLibrary.forAnalyzer(context, a).toggle(MeshNode("one", "Saved", "repeater", lastSeen = "2026-09-22"))
            assertEquals(1, NodeLibrary.forAnalyzer(context, a).state.favorites.size)
            assertEquals(0, NodeLibrary.forAnalyzer(context, b).state.favorites.size)
        } finally {
            context.getSharedPreferences("node-library", android.content.Context.MODE_PRIVATE).edit().remove(a).remove(b).commit()
        }
    }

    @Test fun onboardingCanSelectCommunityAnalyzer() {
        var saved: String? = null
        val sources = listOf(
            AnalyzerSource("a", "Community A", "a.example", "North"),
            AnalyzerSource("b", "Community B", "b.example", "South"),
        )
        compose.setContent {
            NodeScopeTheme(Appearance.SYSTEM) {
                SourceScreen(sources, "a.example", true, false, null, { saved = it })
            }
        }
        compose.onNodeWithText("Community B").performClick()
        compose.onNodeWithText("Continue").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("b.example", saved) }
    }

    @Test fun settingsSourcePickerAppliesATapImmediately() {
        var saved: String? = null
        val sources = listOf(AnalyzerSource("a", "Community A", "a.example", "North", isDefault = true), AnalyzerSource("b", "Community B", "b.example", "South"))
        compose.setContent { NodeScopeTheme(Appearance.SYSTEM) { SourceScreen(sources, "a.example", false, false, null, { saved = it }) } }
        compose.onNodeWithContentDescription("Selected").assertIsDisplayed()
        compose.onNodeWithText("Community B").performClick()
        compose.runOnIdle { assertEquals("b.example", saved) }
    }

    @Test fun customSourceAndValidationErrorStayVisible() {
        var saved: String? = null
        compose.setContent {
            NodeScopeTheme(Appearance.SYSTEM) {
                SourceScreen(emptyList(), "a.example", true, false, "Enter a valid analyzer hostname.", { saved = it })
            }
        }
        compose.onNodeWithText("Analyzer hostname").performTextReplacement("custom.example:8443")
        compose.onNodeWithText("Enter a valid analyzer hostname.").assertIsDisplayed()
        compose.onNodeWithText("Continue").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("custom.example:8443", saved) }
    }

    @Test fun appearanceUsesNativeSelectionAndCallback() {
        var selected = Appearance.SYSTEM
        compose.setContent {
            var preferences by remember { mutableStateOf(AppPreferences()) }
            NodeScopeTheme(preferences.appearance) {
                SettingsScreen(preferences, LiveConnection.LIVE, null, null, { selected = it; preferences = preferences.copy(appearance = it) },
                    {}, {}, {}, {}, {})
            }
        }
        compose.onNodeWithText("Dark").performClick().assertIsSelected()
        compose.runOnIdle { assertEquals(Appearance.DARK, selected) }
    }

    @Test fun nodeSearchFiltersAndOpensMatchingIdentity() {
        var selected: String? = null
        val nodes = listOf(
            MeshNode("one", "North", "repeater", lastSeen = "2026-01-01T00:00:00Z"),
            MeshNode("two", "South", "sensor", lastSeen = "2026-01-01T00:00:00Z"),
        )
        compose.setContent { NodeScopeTheme(Appearance.SYSTEM) { NodeBrowser(nodes, 2, { selected = it }) } }
        compose.onNodeWithText("Search name, public key, or role").performTextInput("SENSOR")
        compose.onNodeWithText("North").assertDoesNotExist()
        compose.onNodeWithText("South").performClick()
        compose.runOnIdle { assertEquals("two", selected) }
    }
}
