package org.nodescope.android

import java.time.Instant
import org.junit.Assert.*
import org.junit.Test
import org.nodescope.android.core.model.LivePacket
import org.nodescope.android.core.model.MeshNode
import org.nodescope.android.core.model.MeshObserver
import org.nodescope.android.feature.map.*

class MapFiltersTest {
    private val now = Instant.parse("2026-09-23T12:00:00Z").toEpochMilli()
    private fun node(key: String, role: String, minutesAgo: Long) =
        MeshNode(key, key, role, 30.0, -97.0, Instant.ofEpochMilli(now - minutesAgo * 60_000).toString())
    private fun packet(observer: String?, hops: List<String?>, resolved: List<String?> = emptyList()) = LivePacket(1, "abcdef0123456789", 5, "GRP_TXT",
        observer, "North $observer", null, 9.5, -88.0, hops, resolved, null, now, true, payloadText = "hello")

    @Test fun activityAndRolesChooseMarkers() {
        val fresh = node("a", "repeater", 10)
        val hourOld = node("b", "companion", 90)
        val filters = MapFilters(activity = ActivityFilter.FIFTEEN_MINUTES)
        assertTrue(filters.shows(fresh, now))
        assertFalse(filters.shows(hourOld, now))
        assertTrue(MapFilters(activity = ActivityFilter.ONE_DAY).shows(hourOld, now))
        assertFalse(MapFilters(roles = setOf("repeater")).shows(hourOld, now))
        assertTrue(MapFilters(roles = setOf("repeater", "companion")).shows(hourOld, now))
        // Unknown last-heard time never passes an activity window.
        assertFalse(filters.shows(fresh.copy(lastSeen = "not a date"), now))
        assertTrue(MapFilters().shows(fresh.copy(lastSeen = "not a date"), now))
    }

    @Test fun observerOnlyLimitsRoutesAndCountsAsOneFilter() {
        val filters = MapFilters(roles = setOf("room"), observerId = "OBS1")
        assertTrue(filters.showsRoute(packet("obs1", emptyList())))
        assertFalse(filters.showsRoute(packet("obs2", emptyList())))
        assertTrue(MapFilters().showsRoute(packet(null, emptyList())))
        assertEquals(2, filters.activeCount)
        assertEquals(0, MapFilters().activeCount)
        assertEquals(MapFilters(activity = ActivityFilter.FIFTEEN_MINUTES), MapFilters.ACTIVE_NODES)
    }

    @Test fun observerChoicesFollowTheRegion() {
        val observers = listOf(MeshObserver("2", "zeta", "DFW"), MeshObserver("1", "Alpha", "dfw"), MeshObserver("3", null, "AUS"))
        assertEquals(listOf("1", "2"), mapObserverOptions(observers, "DFW").map { it.id })
        assertEquals(listOf("3", "1", "2"), mapObserverOptions(observers, null).map { it.id }) // unnamed sorts by ID
    }

    @Test fun routeDetailsListHopsThenTheObserverWithoutGuessing() {
        val nodes = listOf(node("aa11", "repeater", 1).copy(name = "Hilltop"), node("bb22", "repeater", 1), node("bb23", "repeater", 1))
        // "bb" matches two nodes, so it stays unresolved instead of being guessed.
        val details = routeDetails(packet("obs1", listOf("aa", "bb")), nodes, now - 60_000)
        assertEquals(listOf("Hilltop", "Unknown node (BB)", "North obs1"), details.stops.map { it.title })
        assertEquals(listOf("aa11", null, null), details.stops.map { it.publicKey })
        assertEquals(2, details.hopCount)
        assertTrue(details.stops.last().receiver)
        val share = details.shareText("1 minute ago")
        assertTrue(share.startsWith("NodeScope Route\nHops: 2\nObserved: 1 minute ago\nObserver: North obs1\nSNR: 9.5 dB\nRSSI: -88 dBm\nPacket: ABCDEF0123456789"))
        assertTrue(share.contains("Message:\nhello"))
        assertTrue(share.endsWith("Route:\n1. Hilltop (aa11)\n2. Unknown node (BB)\n3. North obs1"))
    }
}
