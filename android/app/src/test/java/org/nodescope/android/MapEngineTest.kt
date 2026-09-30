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
}
