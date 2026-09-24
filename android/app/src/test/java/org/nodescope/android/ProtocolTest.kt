package org.nodescope.android

import org.junit.Assert.*
import org.junit.Test
import org.nodescope.android.core.model.*
import org.nodescope.android.core.network.*
import org.nodescope.android.feature.map.*

class ProtocolTest {
    @Test fun canonicalizesHostsWithoutChangingEndpointMeaning() {
        assertEquals("example.org", normalizeHost(" HTTPS://EXAMPLE.ORG:443/ "))
        assertEquals("example.org:8443", normalizeHost("example.org:8443"))
        assertEquals("[::1]:8443", normalizeHost("https://[::1]:8443"))
    }
    @Test fun rejectsAmbiguousAndInsecureSources() {
        listOf("", " ", "http://example.org", "https://user:password@example.org", "example.org/api", "example.org?q=a", "example.org#x").forEach {
            assertThrows(IllegalArgumentException::class.java) { normalizeHost(it) }
        }
    }
    @Test fun identifiersRemainSinglePathSegments() {
        val url = endpoint("example.org", "api", "channels", "#room/a?b", "messages")
        assertEquals("/api/channels/%23room%2Fa%3Fb/messages", url.encodedPath)
        assertNull(url.fragment)
        assertNull(url.query)
    }
    @Test fun decodesSharedFixtureAndRejectsInvalidLocations() {
        val raw = checkNotNull(javaClass.classLoader?.getResourceAsStream("nodes-minimal.json")).bufferedReader().use { it.readText() }
        val response = protocolJson.decodeFromString<NodesResponse>(raw)
        assertEquals(3, response.total)
        assertEquals(Coordinate(30.2672, -97.7431), response.nodes[0].coordinate)
        assertEquals("future-role", response.nodes[1].role)
        assertNull(response.nodes[1].coordinate)
        assertNull(response.nodes[2].coordinate)
        assertTrue(response.nodes[0].lastSeen.endsWith(".123Z"))
    }
    @Test fun unresolvedHopBreaksRouteInsteadOfInventingLink() {
        val a = Coordinate(0.0, 0.0); val b = Coordinate(1.0, 1.0)
        val c = Coordinate(2.0, 2.0); val d = Coordinate(3.0, 3.0)
        assertEquals(listOf(listOf(a, b), listOf(c, d)), resolvedSegments(listOf(a, b, null, c, d)))
        assertTrue(resolvedSegments(listOf(a, null, b)).isEmpty())
    }
    @Test fun previewHandlesEndpointsEmptyAndDateLine() {
        assertNull(routePosition(emptyList(), 0f))
        val route = listOf(Coordinate(0.0, 179.0), Coordinate(10.0, -179.0))
        assertEquals(route.first(), routePosition(route, -1f))
        assertEquals(route.last(), routePosition(route, 2f))
        // Native map lines are straight in Mercator, not latitude degrees.
        val midpoint = routePosition(route, .5f)!!
        assertEquals(5.019148, midpoint.latitude, 0.000001)
        assertEquals(-180.0, midpoint.longitude, 0.000001)
    }
    @Test fun unequalHopsMaintainSpeedAcrossJunctions() {
        val route = listOf(Coordinate(0.0, 0.0), Coordinate(0.0, 1.0), Coordinate(0.0, 10.0))
        for (step in 0..10) assertEquals(step.toDouble(), routePosition(route, step / 10f)!!.longitude, 0.00001)
    }
    @Test fun repeatedCoordinatesDoNotStallOrDivideByZero() {
        val a = Coordinate(0.0, 0.0)
        assertEquals(a, routePosition(listOf(a, a, a), .5f))
        assertEquals(5.0, routePosition(listOf(a, a, Coordinate(0.0, 10.0)), .5f)!!.longitude, 0.00001)
    }
}
