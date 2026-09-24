package org.nodescope.android

import java.time.Instant
import org.junit.Assert.*
import org.junit.Test
import org.nodescope.android.core.model.*
import org.nodescope.android.core.storage.NodeLibrary
import org.nodescope.android.feature.explore.*

class ExploreModelTest {
    private val now = Instant.parse("2026-09-23T12:00:00Z").toEpochMilli()
    private fun ago(minutes: Long) = Instant.ofEpochMilli(now - minutes * 60_000).toString()
    private val nodes = listOf(MeshNode("n1", "Hilltop", "repeater", lastSeen = ago(5)), MeshNode("n2", "Ridge", "companion", lastSeen = ago(30)))
    private val observers = listOf(MeshObserver("O1", "North", iata = "DFW", lastSeen = ago(1), model = "RAK"), MeshObserver("O2", "South", lastSeen = ago(90)))
    private fun packet(id: Long, minutes: Long, snr: Double?, hash: String = "h$id") =
        LivePacket(id, hash, 4, "ADVERT", "O1", "North", null, snr, null, emptyList(), emptyList(), ago(minutes), now, false)

    @Test fun dashboardMatchesIosWindows() {
        val packets = listOf(packet(1, 10, 4.0), packet(2, 50, 6.0), packet(3, 70, 100.0))
        assertEquals(GlanceValue("1", "of 2 seen"), glanceValue(GlanceMetric.ACTIVE_NODES, nodes, observers, packets, now))
        assertEquals(GlanceValue("1", "of 2 known"), glanceValue(GlanceMetric.OBSERVERS_ONLINE, nodes, observers, packets, now))
        assertEquals(GlanceValue("2", "in the last hour"), glanceValue(GlanceMetric.PACKETS, nodes, observers, packets, now))
        assertEquals(GlanceValue("5.0", "dB in the last hour"), glanceValue(GlanceMetric.AVERAGE_SNR, nodes, observers, packets, now))
        assertEquals(GlanceValue("—", "no recent samples"), glanceValue(GlanceMetric.AVERAGE_SNR, nodes, observers, emptyList(), now))
        val saturated = (1..1_000L).map { packet(it, 1, null) }
        assertEquals("1000+", glanceValue(GlanceMetric.PACKETS, nodes, observers, saturated, now).value)
    }

    @Test fun favoritesSummaryCountsActiveNodesAndObservers() {
        val library = NodeLibrary()
        library.toggle(nodes[0]); library.toggle(nodes[1]); library.toggle(observers[0]); library.toggle(MeshChannel("#a", "#a"))
        assertEquals(2, activeFavoriteCount(library.state.favorites, nodes, observers, now))
        assertEquals("1 channel · 2 nodes · 1 observer", favoriteBreakdown(library.state.favorites))
    }

    @Test fun searchCoversEveryKindWithMonitoredChannelsFirst() {
        val channels = listOf(MeshChannel("#dfw", "#dfw", lastSender = "Relay"), MeshChannel("Public", "Public"))
        val monitored = listOf(MonitoredChannel("#dfw", "00".repeat(16)))
        val packets = listOf(packet(1, 1, null, hash = "abcd1234"), packet(2, 2, null, hash = "abcd1234"))
        val results = searchNetwork("d", nodes, observers, channels, monitored, packets)
        assertEquals(listOf("user:#dfw"), results.channels.map { it.hash }) // monitored replaces the server entry
        assertEquals(listOf("n2"), results.nodes.map { it.publicKey }) // name "Ridge"
        assertEquals(listOf("n1"), searchNetwork("repeat", nodes, observers, channels, monitored, packets).nodes.map { it.publicKey }) // by role
        assertEquals(listOf("abcd1234"), results.packets.map { it.hash }) // one row per packet hash
        assertEquals(listOf("O1"), searchNetwork("rak", nodes, observers, channels, monitored, packets).observers.map { it.id })
        assertTrue(searchNetwork("  ", nodes, observers, channels, monitored, packets).isEmpty)
    }

    @Test fun preferencesKeepEveryMetricAndOneVisible() {
        val stored = GlancePreferences(order = listOf(GlanceMetric.PACKETS), hidden = setOf(GlanceMetric.AVERAGE_SNR)).normalized()
        assertEquals(GlanceMetric.PACKETS, stored.order.first())
        assertEquals(GlanceMetric.entries.toSet(), stored.order.toSet())
        assertEquals(3, stored.visible.size)
    }

    @Test fun exportsMatchIosFieldsAndQuoteCsv() {
        val metrics = listOf(GlanceMetric.ACTIVE_NODES to GlanceValue("1", "of 2 seen"), GlanceMetric.AVERAGE_SNR to GlanceValue("—", "no recent samples"))
        val csv = glanceCsv(metrics, 1, "1 channel · 2 nodes · 1 observer")
        assertEquals("metric,value,detail\r\nactiveNodes,1,of 2 seen\r\naverageSNR,,no recent samples\r\nfavorites,1,1 channel · 2 nodes · 1 observer\r\n", csv)
        assertEquals("\"a,b\"", glanceCsv(listOf(GlanceMetric.PACKETS to GlanceValue("1", "a,b")), 0, "").lines()[1].substringAfterLast("1,"))
        val json = glanceJson("example.org", now, metrics, 1, 4)
        assertTrue(json.contains("\"scope\": \"entire_network\""))
        assertTrue(json.contains("\"id\": \"averageSNR\""))
        assertTrue(json.contains("\"favoriteCount\": 4"))
    }
}
