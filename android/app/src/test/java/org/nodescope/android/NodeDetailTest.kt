package org.nodescope.android

import org.junit.Assert.*
import org.junit.Test
import org.nodescope.android.core.model.*
import org.nodescope.android.core.network.*
import org.nodescope.android.feature.nodes.combinedStatus

class NodeDetailTest {
    @Test fun decodesHealthWithPartialObserverData() {
        val health = protocolJson.decodeFromString<NodeHealth>(fixture("node-health.json"))
        assertEquals("Hilltop", health.node?.name)
        assertEquals(listOf(340L, 2L), health.observers.map { it.packetCount })
        assertNull(health.observers[1].observerName)
        assertNull(health.observers[1].avgSnr)
        assertEquals(48L, health.stats?.packetsToday)
        assertNull(health.stats?.avgHops) // unknown stays unknown, not zero
    }

    @Test fun decodesPathsWithUnresolvedHops() {
        val paths = protocolJson.decodeFromString<NodePaths>(fixture("node-paths.json"))
        assertEquals(listOf("Hilltop", "BB22"), paths.paths.single().hops.map { it.label })
        assertEquals(4, paths.paths.single().count)
    }

    @Test fun decodesReachLinksAndImportance() {
        val reach = protocolJson.decodeFromString<NodeReach>(fixture("node-reach.json"))
        assertEquals(7, reach.window?.days)
        assertEquals(5, reach.importance?.neighborDegree)
        assertEquals(listOf(true, false), reach.links.map { it.bidir })
        assertEquals("dd4400000000", reach.links[1].label)
        assertNull(reach.links[1].distanceKm)
    }

    @Test fun sectionUrlsEncodeTheKey() {
        assertEquals("/api/nodes/ab%2Fcd/reach", nodeSectionUrl("example.org", "ab/cd", "reach").encodedPath)
    }

    @Test fun statusReportsAnErrorOnlyWhenEverySectionFailed() {
        val ok = Loaded(key = 1, value = "x", updatedAt = 5L)
        val failed = Loaded<Int, String>(key = 1, error = "HTTP 404")
        assertNull(combinedStatus(listOf(ok, failed, failed)).error)
        assertNotNull(combinedStatus(listOf(ok, failed, failed)).value)
        assertEquals("HTTP 404", combinedStatus(listOf(failed, failed, failed)).error)
        assertNull(combinedStatus(listOf(failed, Loaded<Int, String>(key = 1, loading = true), failed)).error)
        assertTrue(combinedStatus(listOf(failed, Loaded<Int, String>(key = 1, loading = true), failed)).loading)
    }
}
