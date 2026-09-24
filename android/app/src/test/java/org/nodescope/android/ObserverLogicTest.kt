package org.nodescope.android

import java.time.Instant
import org.junit.Assert.*
import org.junit.Test
import org.nodescope.android.core.design.*
import org.nodescope.android.core.model.*
import org.nodescope.android.core.network.protocolJson
import org.nodescope.android.feature.observers.*

class ObserverLogicTest {
    private val now = Instant.parse("2026-09-23T12:05:00Z").toEpochMilli()
    private val observers = protocolJson.decodeFromString<ObserversResponse>(fixture("observers.json")).observers

    @Test fun missingTelemetryStaysUnknown() {
        val bare = observers.single { it.id == "OBS-BARE" }
        assertNull(bare.batteryMv)
        assertNull(bare.noiseFloor)
        assertNull(bare.packetCount)
        assertEquals("OBS-BARE", bare.displayName)
        val north = observers.first()
        assertEquals(-112.0, north.noiseFloor!!, 0.0)
        assertEquals(250_000L, north.packetCount)
    }

    @Test fun filtersByRegionActivityModelAndQuery() {
        fun ids(region: String? = null, activity: ObserverActivity = ObserverActivity.ALL, model: String? = null, query: String = "") =
            visibleObservers(observers, region, activity, model, query, ObserverSort.RECENT, now).map { it.id }
        assertEquals(listOf("OBS-NORTH", "OBS-BARE", "OBS-SOUTH"), ids())
        assertEquals(listOf("OBS-NORTH"), ids(region = "dfw"))
        assertEquals(listOf("OBS-NORTH", "OBS-BARE"), ids(activity = ObserverActivity.RECENT))
        assertEquals(listOf("OBS-SOUTH"), ids(model = "RAK 4631"))
        assertEquals(listOf("OBS-SOUTH"), ids(query = "aus"))
    }

    @Test fun sortsWithUnknownCountsLast() {
        fun ids(sort: ObserverSort) = visibleObservers(observers, null, ObserverActivity.ALL, null, "", sort, now).map { it.id }
        assertEquals(listOf("OBS-NORTH", "OBS-SOUTH", "OBS-BARE"), ids(ObserverSort.HOURLY))
        assertEquals(listOf("OBS-NORTH", "OBS-SOUTH", "OBS-BARE"), ids(ObserverSort.TOTAL))
        assertEquals(listOf("North Observer", "OBS-BARE", "South Observer"), visibleObservers(observers, null, ObserverActivity.ALL, null, "", ObserverSort.NAME, now).map { it.displayName })
    }

    @Test fun decodesAnalyticsIncludingUnknownPacketTypes() {
        val analytics = protocolJson.decodeFromString<ObserverAnalytics>(fixture("observer-analytics.json"))
        assertEquals(listOf(10L, 25L), analytics.timeline.map { it.count })
        assertEquals("Channel Msg", payloadTypeLabel(analytics.packetTypes.keys.first { it == "5" }.toInt()))
        assertEquals("Type 13", payloadTypeLabel(13))
        assertNull(analytics.recentPackets[1].snr)
    }

    @Test fun formatsCountsTimesAndDurations() {
        assertEquals("950", compactCount(950))
        assertEquals("1.2K", compactCount(1_200))
        assertEquals("250K", compactCount(250_000))
        assertEquals("3M", compactCount(3_000_000))
        assertEquals("just now", relativeTime(Instant.ofEpochMilli(now - 10_000), now))
        assertEquals("5m ago", relativeTime(Instant.ofEpochMilli(now - 300_000), now))
        assertEquals("2h ago", relativeTime(Instant.ofEpochMilli(now - 7_200_000), now))
        assertEquals("Unknown", relativeTime(null, now))
        assertEquals("1d 1h", formatDuration(90_061))
    }

    @Test fun donutLegendPercentages() {
        assertEquals("26%", percentOf(3_314, 12_900))
        assertEquals("<1%", percentOf(1, 1_000))
        assertEquals("0%", percentOf(0, 1_000))
        assertEquals("100%", percentOf(5, 5))
        assertEquals("0%", percentOf(5, 0))
    }
}
