package org.nodescope.android

import android.graphics.Bitmap
import android.graphics.RectF
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.MapLibreMap
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.nodescope.android.app.*
import org.nodescope.android.core.design.NodeScopeTheme
import org.nodescope.android.core.model.*
import org.nodescope.android.core.network.*
import org.nodescope.android.core.storage.*
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/** Explicit opt-in only: captures actual analyzer traffic without altering saved app preferences. */
class LiveNetworkUiTest {
    @get:Rule val compose = createAndroidComposeRule<UiTestActivity>()
    @Test fun receivesRealTrafficAndDisplaysMapExploreAndPackets() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveNetwork") == "true")
        val store = PreviewPreferences()
        val preferences = store.values.value
        val app = compose.activity.application as NodeScopeApplication
        val observed = AtomicReference(LiveFeedState())
        val loaded = AtomicReference<AnalyzerSnapshot>()
        compose.setContent {
            var feed by remember { mutableStateOf(LiveFeedState()) }
            var snapshot by remember { mutableStateOf<AnalyzerSnapshot?>(null) }
            var appearance by remember { mutableStateOf(Appearance.DARK) }
            LaunchedEffect(Unit) {
                launch { LiveFeed(store, app.container.packetSource).states.collect { feed = it; observed.set(it) } }
                launch { snapshot = app.container.repository.load(AnalyzerSelection(DEFAULT_HOST, null)); loaded.set(snapshot) }
            }
            NodeScopeTheme(appearance) {
                Surface(Modifier.fillMaxSize()) { Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                    AppShell(preferences.copy(appearance = appearance), SessionState(snapshot = snapshot), {}, {}, { appearance = it }, {}, {}, feed)
                } }
            }
        }
        compose.waitUntil(60_000) { observed.get().connection == LiveConnection.LIVE && observed.get().packets.any { it.isLive } && loaded.get() != null }
        compose.onNodeWithText("Connected").assertIsDisplayed()
        compose.waitUntil(30_000) { observed.get().observersLoaded }
        waitForMap()
        capture("live-map")
        compose.onNodeWithText("Explore").performClick()
        compose.onNodeWithText("Network at a Glance").assertIsDisplayed()
        capture("live-explore")
        compose.onNodeWithText("Live packets").performClick()
        compose.onNodeWithText("INCOMING TRAFFIC").assertIsDisplayed()
        compose.onNodeWithText("Listening for live traffic").assertExists()
        capture("live-packets")
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("Light").performClick()
        compose.onNodeWithText("Map").performClick()
        waitForMap()
        capture("light-map")
    }
    private fun waitForMap() {
        val map = AtomicReference<MapLibreMap>()
        val view = AtomicReference<MapView>()
        compose.runOnIdle { view.set(findMap(compose.activity.window.decorView)); view.get()!!.getMapAsync { map.set(it) } }
        compose.waitUntil(30_000) {
            compose.runOnIdle {
                val ready = map.get()
                ready?.style?.isFullyLoaded == true && ready.queryRenderedFeatures(
                    RectF(0f, 0f, view.get().width.toFloat(), view.get().height.toFloat()), "nodescope-points", "nodescope-clusters").isNotEmpty()
            }
        }
    }
    private fun findMap(view: View): MapView? {
        if (view is MapView) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) findMap(view.getChildAt(i))?.let { return it }
        return null
    }
    private fun capture(name: String) {
        compose.waitForIdle()
        // Native map surfaces and Navigation animations render independently of Compose's test clock.
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        SystemClock.sleep(1_000)
        val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(compose.activity.getExternalFilesDir(null), "$name.png").outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
        screenshot.recycle()
    }
}
private class PreviewPreferences : PreferencesStore {
    override val values = MutableStateFlow(AppPreferences(onboarded = true, appearance = Appearance.DARK))
    override suspend fun selectSource(host: String) { }
    override suspend fun setRegion(region: String?) { }
    override suspend fun setAppearance(appearance: Appearance) { }
    override suspend fun setDestination(destination: String) { }
    override suspend fun cacheRegistry(document: String) { }
}
