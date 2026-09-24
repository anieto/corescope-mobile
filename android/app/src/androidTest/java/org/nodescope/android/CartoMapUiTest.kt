package org.nodescope.android

import androidx.compose.runtime.*
import org.nodescope.android.core.model.*
import org.nodescope.android.core.network.*
import kotlinx.serialization.json.jsonObject
import android.graphics.RectF
import android.os.SystemClock
import android.view.MotionEvent
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.geojson.Point
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assume.assumeTrue
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.MapLibreMap
import org.nodescope.android.core.design.NodeScopeTheme
import org.nodescope.android.core.storage.Appearance
import org.nodescope.android.feature.map.MapScreen
import org.nodescope.android.feature.map.mapLabSnapshot
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean
import androidx.test.platform.app.InstrumentationRegistry
import android.graphics.Bitmap
import java.io.File

/** Live smoke test: intentionally skipped in keyless CI; does not contact an analyzer. */
class CartoMapUiTest {
    @get:Rule val compose = createAndroidComposeRule<UiTestActivity>()

    @Test fun cartoStylesRenderAndKeepNodeLayersAfterSwitching() {
        assumeTrue(BuildConfig.MAPS_CONFIGURED)
        val snapshot = mapLabSnapshot()
        val selected = AtomicReference<String>()
        var savedPosition by mutableStateOf<Coordinate?>(null)
        compose.setContent {
            NodeScopeTheme(Appearance.SYSTEM) {
                MapScreen(snapshot, focusedKey = if (savedPosition != null) "saved-outside-region" else null, focusedCoordinate = savedPosition, onNode = { selected.set(it) }, sampleRoute = snapshot.nodes.mapNotNull { it.coordinate })
            }
        }
        val map = AtomicReference<MapLibreMap>()
        val rendered = AtomicBoolean(false)
        compose.runOnIdle {
            findMap(compose.activity.window.decorView)!!.apply {
                addOnDidFinishRenderingMapListener { fullyRendered -> rendered.set(fullyRendered) }
                getMapAsync { map.set(it) }
            }
        }
        compose.waitUntil(30_000) { compose.runOnIdle { map.get()?.style?.isFullyLoaded == true } }
        compose.onNodeWithText("© CARTO").assertIsDisplayed()
        compose.onNodeWithText("© OpenStreetMap").assertIsDisplayed()
        for ((label, styleName) in listOf("Dark" to "dark-matter", "Light" to "positron", "Standard" to "voyager")) {
            rendered.set(false)
            compose.onNodeWithContentDescription("Map layers").performClick()
            compose.onNodeWithText(label).performClick()
            compose.waitUntil(30_000) { compose.runOnIdle { map.get()?.style?.let { it.isFullyLoaded && it.uri.contains(styleName) } == true } }
            compose.waitUntil(30_000) { rendered.get() }
            compose.runOnIdle {
                val style = map.get().style!!
                assertNotNull(style.getLayer("nodescope-points"))
                assertNotNull(style.getLayer("nodescope-clusters"))
                assertNotNull(style.getLayer("nodescope-route-line"))
                assertEquals(30.30, map.get().cameraPosition.target!!.latitude, 0.01)
            }
        }
        compose.runOnIdle { map.get().moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(30.30, -97.72), 7.0)) }
        compose.waitUntil(10_000) {
            compose.runOnIdle {
                val view = findMap(compose.activity.window.decorView)!!
                map.get().queryRenderedFeatures(RectF(0f, 0f, view.width.toFloat(), view.height.toFloat()), "nodescope-clusters").isNotEmpty()
            }
        }
        compose.runOnIdle {
            val view = findMap(compose.activity.window.decorView)!!
            val cluster = map.get().queryRenderedFeatures(RectF(0f, 0f, view.width.toFloat(), view.height.toFloat()), "nodescope-clusters").first().geometry() as Point
            tap(view, map.get(), LatLng(cluster.latitude(), cluster.longitude()))
        }
        compose.waitUntil(10_000) { compose.runOnIdle { map.get().cameraPosition.zoom > 7.0 } }
        compose.runOnIdle {
            map.get().cancelTransitions()
            map.get().moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(30.30, -97.72), 14.0))
        }
        compose.waitUntil(10_000) {
            compose.runOnIdle {
                map.get().queryRenderedFeatures(map.get().projection.toScreenLocation(LatLng(30.30, -97.72)), "nodescope-points").isNotEmpty()
            }
        }
        compose.runOnIdle { tap(findMap(compose.activity.window.decorView)!!, map.get(), LatLng(30.30, -97.72)) }
        compose.waitUntil(10_000) { selected.get() == "sample-1" }
        compose.runOnIdle { map.get().moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(30.30, -97.72), 11.0)) }
        compose.waitUntil(30_000) { rendered.get() }
        val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(compose.activity.getExternalFilesDir(null), "carto-map-smoke.png").outputStream().use {
            screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        screenshot.recycle()
        compose.onNodeWithText("Replay sample route").performClick()
        compose.onNodeWithContentDescription("Zoom in").performClick()
        compose.onNodeWithText("Could not load the map. Check your connection and try again.").assertDoesNotExist()
        compose.runOnIdle { savedPosition = Coordinate(40.0, -90.0) }
        compose.waitUntil(10_000) { compose.runOnIdle { kotlin.math.abs(map.get().cameraPosition.target!!.latitude - 40.0) < 0.01 } }
        compose.runOnIdle { assertEquals(-90.0, map.get().cameraPosition.target!!.longitude, 0.01) }
    }

    @Test fun routesRevealClusteredNodesAndRegionSelectionFramesItsCenter() {
        assumeTrue(BuildConfig.MAPS_CONFIGURED)
        val sample = mapLabSnapshot()
        val snapshot = sample.copy(regionCoordinates = mapOf("DFW" to RegionCoordinate(32.9, -97.04)))
        var region by mutableStateOf<String?>(null)
        var feed by mutableStateOf(LiveFeedState())
        val selected = AtomicReference<String>()
        compose.setContent { NodeScopeTheme(Appearance.DARK) {
            MapScreen(snapshot, onNode = { selected.set(it) }, feed = feed, selectedRegion = region)
        } }
        val map = AtomicReference<MapLibreMap>()
        compose.runOnIdle { findMap(compose.activity.window.decorView)!!.getMapAsync { map.set(it) } }
        compose.waitUntil(30_000) { compose.runOnIdle { map.get()?.style?.isFullyLoaded == true } }
        fun markers(layer: String) = compose.runOnIdle {
            val view = findMap(compose.activity.window.decorView)!!
            map.get().queryRenderedFeatures(RectF(0f, 0f, view.width.toFloat(), view.height.toFloat()), layer)
        }
        compose.runOnIdle { map.get().moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(30.30, -97.72), 7.0)) }
        compose.waitUntil(10_000) { markers("nodescope-clusters").isNotEmpty() }
        val packet = parseLivePacket(protocolJson.parseToJsonElement("""{"id":1,"hash":"cluster-route","path_json":["sample-0","sample-1","sample-2"],"observer_id":"obs"}""").jsonObject, true)!!
        compose.runOnIdle { feed = LiveFeedState(packets = listOf(packet), observers = listOf(MeshObserver("obs", lat = 30.6, lon = -97.9))) }
        // Route markers last only as long as the route (iOS timing): three hops keep them for about 3 s.
        compose.waitUntil(10_000) { markers("nodescope-route-node-points").any { it.hasProperty("publicKey") && it.getStringProperty("publicKey") == "sample-0" } }
        compose.runOnIdle { tap(findMap(compose.activity.window.decorView)!!, map.get(), LatLng(30.2672, -97.7431)) }
        compose.waitUntil(10_000) { selected.get() == "sample-0" }
        compose.runOnIdle { feed = feed.copy(packets = listOf(packet.copy(receivedAt = System.currentTimeMillis() - 13_000))) }
        compose.waitUntil(10_000) { markers("nodescope-route-node-points").isEmpty() }
        compose.runOnIdle { region = "DFW" }
        compose.waitUntil(10_000) { compose.runOnIdle { kotlin.math.abs(map.get().cameraPosition.target!!.latitude - 32.9) < 0.02 } }
        compose.runOnIdle {
            assertEquals(-97.04, map.get().cameraPosition.target!!.longitude, 0.02)
            assertTrue(map.get().cameraPosition.zoom > 7.0)
            region = null
        }
        compose.waitUntil(10_000) { compose.runOnIdle { kotlin.math.abs(map.get().cameraPosition.target!!.latitude - 30.30) < 0.01 } }
    }

    private fun tap(view: MapView, map: MapLibreMap, coordinate: LatLng) {
        val point = map.projection.toScreenLocation(coordinate)
        val now = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(now, now + if (action == MotionEvent.ACTION_UP) 50 else 0, action, point.x, point.y, 0)
            view.dispatchTouchEvent(event)
            event.recycle()
        }
    }

    private fun findMap(view: View): MapView? {
        if (view is MapView) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) findMap(view.getChildAt(i))?.let { return it }
        return null
    }
}
