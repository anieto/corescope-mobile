package org.nodescope.android

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.nodescope.android.core.design.LocalDistanceUnit
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.lifecycle.SavedStateHandle
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertNull
import org.nodescope.android.core.design.NodeScopeTheme
import org.nodescope.android.core.design.meshPageBackground
import org.nodescope.android.core.storage.Appearance
import org.nodescope.android.core.storage.DistanceUnit
import org.nodescope.android.feature.linkprofile.*
import java.io.File

class LinkProfileUiTest {
    @get:Rule val compose = createAndroidComposeRule<UiTestActivity>()

    private fun exercise(appearance: Appearance, density: Float, fontScale: Float, unit: DistanceUnit = DistanceUnit.METRIC) {
        val cache = File(compose.activity.cacheDir, "link-profile-ui-test.json")
        val a = ProfileCoordinate(40.0, -90.0)
        val b = ProfileCoordinate(40.001, -90.0)
        val now = System.currentTimeMillis()
        cache.writeText(LinkGeometry.samples(a, b, 20).joinToString(",", "[", "]") {
            "{\"coordinate\":{\"latitude\":${it.latitude},\"longitude\":${it.longitude}},\"elevation\":100,\"fetchedAt\":$now}"
        })
        val model = LinkProfileViewModel(LinkTerrainProvider(cache), SavedStateHandle(mapOf(
            "Latitude A" to "40", "Longitude A" to "-90", "Latitude B" to "40.001", "Longitude B" to "-90")))
        val selectedUnit = mutableStateOf(unit)
        compose.setContent {
            CompositionLocalProvider(LocalDistanceUnit provides selectedUnit.value, LocalDensity provides Density(density, fontScale)) {
                NodeScopeTheme(appearance) {
                    Box(Modifier.fillMaxSize().meshPageBackground()) { LinkProfileScreen(model) }
                }
            }
        }
        compose.waitUntil(10_000) { model.state.value.elevations.values.count { it.meters == 100.0 } == 2 }
        // Both points restored: the analysis runs on its own and the header shows the verdict.
        compose.waitUntil(10_000) { model.state.value.result != null || model.state.value.error != null }
        compose.runOnIdle { assertNull(model.state.value.error) }
        compose.onNodeWithText("Clear terrain path").assertIsDisplayed()
        val ground = if (unit == DistanceUnit.IMPERIAL) "Ground elevation: 328 ft" else "Ground elevation: 100 m"
        compose.onAllNodesWithText(ground, substring = true).onFirst().performScrollTo().assertIsDisplayed()
        val resultBeforeSwitch = model.state.value.result
        val nextUnit = if (unit == DistanceUnit.METRIC) DistanceUnit.IMPERIAL else DistanceUnit.METRIC
        compose.runOnIdle { selectedUnit.value = nextUnit }
        compose.runOnIdle {
            assertSame(resultBeforeSwitch, model.state.value.result)
            assertEquals("6", model.state.value.fields["Height A (m)"])
        }
        // Result values follow the unit without recalculating (the Distance row, as on iOS).
        val distanceResult = org.nodescope.android.core.design.formatDistance(resultBeforeSwitch!!.result.distanceMeters / 1000, nextUnit)
        compose.onNodeWithText(distanceResult).performScrollTo().assertIsDisplayed()
        // Point A's card opens to its antenna settings.
        compose.onAllNodesWithText("Dropped pin").onFirst().performScrollTo().performClick()
        val total = if (nextUnit == DistanceUnit.IMPERIAL) "Total height: 348 ft" else "Total height: 106 m"
        compose.onNodeWithText(total).performScrollTo().assertIsDisplayed()
        val slider = compose.onNodeWithContentDescription("Additional antenna height A")
        slider.performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { it(if (nextUnit == DistanceUnit.IMPERIAL) 50f else 15f) }
        compose.runOnIdle {
            assertEquals(if (nextUnit == DistanceUnit.IMPERIAL) 15.24 else 15.0, model.state.value.fields.getValue("Height A (m)").toDouble(), 1e-8)
        }
        compose.onNodeWithText("Enter coordinates").performScrollTo().performClick()
        compose.onNodeWithText("Latitude A").performScrollTo().performTextReplacement("")
        compose.runOnIdle { assertNull(model.state.value.result) }
        compose.onNodeWithText("Clear terrain path").assertDoesNotExist()
        cache.delete()
    }

    @Test fun exploreOpensPlannerBeforeLinkProfile() {
        compose.setContent {
            NodeScopeTheme(Appearance.LIGHT) {
                org.nodescope.android.app.AppShell(
                    org.nodescope.android.core.storage.AppPreferences(onboarded = true, destination = "EXPLORE"),
                    org.nodescope.android.core.network.SessionState(), {}, {}, {}, {}, {},
                    nodeLibrary = org.nodescope.android.core.storage.NodeLibrary())
            }
        }
        compose.onNodeWithText("Node Planner").performScrollTo().performClick()
        compose.onNodeWithText("Plan before you deploy").assertIsDisplayed()
        compose.onNodeWithText("Candidate Sites").assertExists()
        compose.onNodeWithText("Open Tool").performScrollTo().performClick()
        compose.onNodeWithText("Link Profile").assertIsDisplayed()
        if (BuildConfig.MAPS_CONFIGURED) {
            compose.onNodeWithText("Choose Point A").assertIsDisplayed()
            compose.onNodeWithText("Cancel").performClick()
        }
        compose.onNodeWithText("Place Point A and Point B to analyze the link.").assertIsDisplayed()
    }

    @Test fun settingsUnitIsUsedWhenOpeningPlanner() {
        val preferences = mutableStateOf(org.nodescope.android.core.storage.AppPreferences(
            onboarded = true, destination = "SETTINGS", distanceUnit = DistanceUnit.METRIC))
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(3f, 1f)) {
                NodeScopeTheme(Appearance.LIGHT) {
                    org.nodescope.android.app.AppShell(preferences.value,
                        org.nodescope.android.core.network.SessionState(), {}, {}, {}, {}, {},
                        nodeLibrary = org.nodescope.android.core.storage.NodeLibrary(),
                        onDistanceUnit = { preferences.value = preferences.value.copy(distanceUnit = it) })
                }
            }
        }
        compose.onNodeWithText("Imperial (mi)").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(DistanceUnit.IMPERIAL, preferences.value.distanceUnit) }
        compose.onNodeWithText("Explore").performClick()
        compose.onNodeWithText("Node Planner").performScrollTo().performClick()
        compose.onNodeWithText("Open Tool").performScrollTo().performClick()
        if (BuildConfig.MAPS_CONFIGURED) compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("Point A not placed").performScrollTo().performClick()
        compose.onNodeWithText("Additional antenna height A: 20 ft").performScrollTo().assertIsDisplayed()
    }

    @Test fun lightCompactAnalysisAndEdit() = exercise(Appearance.LIGHT, 3f, 1f)
    @Test fun darkWideAnalysisAndEdit() = exercise(Appearance.DARK, 1f, 1f, DistanceUnit.IMPERIAL)
    @Test fun largeTextAnalysisAndEdit() = exercise(Appearance.LIGHT, 3f, 1.5f)
}
