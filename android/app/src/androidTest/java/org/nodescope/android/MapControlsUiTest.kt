package org.nodescope.android

import android.graphics.Bitmap
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.nodescope.android.core.design.NodeScopeTheme
import org.nodescope.android.core.model.MeshNode
import org.nodescope.android.core.storage.Appearance
import org.nodescope.android.feature.map.MapNodeSelectionSheet
import org.nodescope.android.feature.map.ReplayControls
import org.nodescope.android.feature.map.ReplayRoute
import java.io.File

class MapControlsUiTest {
    @get:Rule val compose = createAndroidComposeRule<UiTestActivity>()

    @Test fun selectionRequiresExplicitDetailsAndCanBeDismissed() {
        var opened = false
        var visible by mutableStateOf(true)
        compose.setContent { NodeScopeTheme(Appearance.LIGHT) {
            if (visible) MapNodeSelectionSheet(
                MeshNode("test-node", "A long repeater name in the neighborhood", "repeater", lastSeen = "2026-09-26T12:00:00Z"),
                onDetails = { opened = true; visible = false }, onDismiss = { visible = false })
        } }
        compose.onNodeWithText("View node details").assertIsDisplayed()
        assertFalse(opened)
        screenshot("node-selection")
        compose.onNodeWithContentDescription("Dismiss node selection").performClick()
        assertFalse(opened)
        compose.runOnIdle { visible = true }
        compose.onNodeWithText("View node details").performClick()
        compose.runOnIdle { assertTrue(opened) }
    }

    @Test fun replayControlsRetainEveryActionAtLargeText() {
        var selected by mutableIntStateOf(0)
        var routeOnly by mutableStateOf(true)
        var playing by mutableStateOf(false)
        var exited = false
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                NodeScopeTheme(Appearance.DARK) {
                    ReplayControls(playing, listOf(ReplayRoute(emptyList(), emptyList(), 2), ReplayRoute(emptyList(), emptyList(), 3)),
                        selected, routeOnly, onPlay = { playing = true }, onLive = { exited = true },
                        onRoute = { selected = it }, onRouteOnly = { routeOnly = !routeOnly },
                        modifier = Modifier.width(360.dp).heightIn(max = 220.dp))
                }
            }
        }
        compose.onNodeWithText("Play again").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(playing) }
        compose.onNodeWithText("Route 1 of 2").performScrollTo().performClick()
        compose.onNodeWithText("Route 2 · 3 hops").performClick()
        compose.runOnIdle { assertEquals(1, selected) }
        compose.onNodeWithText("Route nodes only").performScrollTo().performClick()
        compose.runOnIdle { assertFalse(routeOnly) }
        screenshot("replay-large-text")
        compose.onNodeWithText("Return to live").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(exited) }
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(compose.activity.getExternalFilesDir(null), "$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }
}
