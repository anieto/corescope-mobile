package org.nodescope.android

import org.junit.Assert.*
import org.junit.Test
import org.nodescope.android.core.model.Coordinate
import org.nodescope.android.core.model.MeshNode
import org.nodescope.android.feature.coverage.nearestCoverageNodes

class CoverageNearbyNodesTest {
    private fun node(id: String, latitude: Double?, longitude: Double?) =
        MeshNode(publicKey = id, role = "repeater", lat = latitude, lon = longitude, lastSeen = "")

    @Test fun ranksBeforeApplyingMarkerLimit() {
        val far = (0 until 200).map { node("far-$it", 40.0, -100.0) }
        val nearest = node("nearest", 29.4301, -98.6201)
        val result = nearestCoverageNodes(far + nearest, Coordinate(29.43, -98.62))
        assertEquals(200, result.size)
        assertEquals("nearest", result.first().publicKey)
    }

    @Test fun movesOriginClearsSelectionAndHandlesDateLine() {
        val west = node("west", 10.0, -179.9)
        val east = node("east", 10.0, 179.7)
        val distant = node("distant", 10.0, 170.0)
        val nodes = listOf(distant, east, west, west, node("missing", null, null), node("invalid", Double.NaN, 1.0))
        assertEquals(listOf("west", "east", "distant"),
            nearestCoverageNodes(nodes, Coordinate(10.0, 179.99)).map { it.publicKey })
        assertEquals("distant", nearestCoverageNodes(nodes, Coordinate(10.0, 170.0)).first().publicKey)
        assertTrue(nearestCoverageNodes(nodes, null).isEmpty())
    }

    @Test fun tiesHaveStableIdentityOrdering() {
        val a = node("a", 29.0, -98.0)
        val b = node("b", 29.0, -98.0)
        assertEquals(listOf("a", "b"), nearestCoverageNodes(listOf(b, a), Coordinate(30.0, -98.0)).map { it.publicKey })
    }
}
