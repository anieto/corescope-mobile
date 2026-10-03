package org.nodescope.android

import org.junit.Assert.*
import org.junit.Test
import org.nodescope.android.core.model.*
import org.nodescope.android.feature.map.*

class MapEngineTest {
    @Test fun routeAnchorsAreDrawnOncePerNodeOrObserverPosition() {
        val node = MapMarker(Coordinate(30.0, -97.0), "repeater", "aa", "North")
        val observer = MapMarker(Coordinate(31.5, -97.25), "observer")
        assertEquals("node:aa", node.anchorId)
        assertEquals("node:aa", node.copy(coordinate = Coordinate(32.0, -96.0)).anchorId)
        assertEquals("observer:31.5,-97.25", observer.anchorId)
    }

    @Test fun nodeLabelsFollowTheVisibleArea() {
        val nodes = (0 until 61).map { MeshNode("n$it", "Node $it", "repeater", 30.0 + it * 0.0001, -97.0, "2026-09-30") }
        val close = CameraTarget.Bounds(30.05, -96.95, 29.99, -97.05)
        assertTrue(nodeLabelsVisible(close, nodes.take(60)))
        assertFalse(nodeLabelsVisible(close, nodes))
        // Nodes outside the view don't count against the limit.
        assertTrue(nodeLabelsVisible(close, nodes.take(60) + MeshNode("far", "Far", "repeater", 35.0, -97.0, "2026-09-30")))
        assertFalse(nodeLabelsVisible(CameraTarget.Bounds(30.1, -96.9, 30.0, -97.1), nodes.take(1)))
    }

    @Test fun routeBoundsHoldEveryPoint() {
        val bounds = boundsOf(listOf(Coordinate(30.0, -97.0), Coordinate(31.0, -98.0), Coordinate(30.5, -96.0)))
        assertEquals(CameraTarget.Bounds(31.0, -96.0, 30.0, -98.0), bounds)
        assertEquals(Coordinate(30.5, -97.0), bounds.center)
    }

    @Test fun mercatorRoundTripsAndOffsetsByScreenPixels() {
        val austin = Coordinate(30.2672, -97.7431)
        assertEquals(austin.latitude, Mercator.latitude(Mercator.y(austin.latitude)), 1e-9)
        assertEquals(austin.longitude, Mercator.longitude(Mercator.x(austin.longitude)), 1e-9)
        // At zoom 0 the world is 512 dp wide: 128 dp east is a quarter turn.
        assertEquals(Coordinate(0.0, 90.0).longitude, Mercator.offset(Coordinate(0.0, 0.0), 0.0, 256.0, 0.0, 2f).longitude, 1e-9)
        assertTrue(Mercator.offset(austin, 10.0, 0.0, -50.0, 2f).latitude > austin.latitude) // up the screen is north
    }

    @Test fun fittingMatchesTheClearAreaAndCentresTheBoundsInIt() {
        val density = 2f
        // 90° of longitude is a quarter of the world: it fills 1024 px at density 2 at zoom 2.
        val bounds = CameraTarget.Bounds(10.0, 45.0, -10.0, -45.0)
        val even = fitCamera(bounds, 1024, 4000, FramingInsets(0, 0, 0, 0), density)
        assertEquals(2.0, even.zoom, 1e-9)
        assertEquals(0.0, even.coordinate.longitude, 1e-9)
        assertEquals(0.0, even.coordinate.latitude, 1e-9)
        // Insets on one side move the map's centre so the bounds sit in the middle of what is left.
        val inset = fitCamera(bounds, 1024 + 200, 4000, FramingInsets(200, 0, 0, 0), density)
        assertEquals(2.0, inset.zoom, 1e-9)
        val world = Mercator.worldPixels(2.0, density)
        assertEquals(-100.0 / world * 360, inset.coordinate.longitude, 1e-9)
        // A single point can't be fitted by size, so it gets the maximum zoom.
        assertEquals(21.0, fitCamera(CameraTarget.Bounds(30.0, -97.0, 30.0, -97.0), 500, 500, FramingInsets(0, 0, 0, 0), density).zoom, 1e-9)
    }

    @Test fun savedGoogleChoiceFallsBackToCartoWithoutAKey() {
        val google = MapProviderChoice(MapProvider.GOOGLE, GoogleMapType.SATELLITE)
        assertEquals(MapProvider.CARTO, google.available(false).provider)
        assertEquals(google, google.available(true))
    }

    @Test fun mapLoadErrorsSayWhyTheMapFailed() {
        assertEquals(MapLoadError.Refused(403), mapLoadError("HTTP status code 403"))
        assertEquals(MapLoadError.Refused(401), mapLoadError("loading style failed: HTTP status code 401"))
        assertEquals(MapLoadError.ServerProblem(503), mapLoadError("HTTP status code 503"))
        assertEquals(MapLoadError.Http(404), mapLoadError("HTTP status code 404"))
        assertEquals(MapLoadError.Unreachable, mapLoadError("Unable to resolve host \"basemaps.cartocdn.com\": No address associated with hostname"))
        assertEquals(MapLoadError.Unreachable, mapLoadError("Failed to connect to basemaps.cartocdn.com/151.101.1.91:443"))
        assertEquals(MapLoadError.TimedOut, mapLoadError("timeout"))
        assertEquals(MapLoadError.SecureConnection, mapLoadError("javax.net.ssl.SSLHandshakeException: Trust anchor for certification path not found."))
        assertEquals(MapLoadError.Other("Something odd"), mapLoadError("  Something odd "))
        assertEquals(MapLoadError.Other("unknown error"), mapLoadError(null))
    }

    @Test fun aFailedMapIsRetriedTwiceBeforeTheErrorShows() {
        assertEquals(2, MAP_RETRY_DELAYS_MS.size)
        assertTrue(MAP_RETRY_DELAYS_MS.zipWithNext().all { (a, b) -> b > a })
    }
}
