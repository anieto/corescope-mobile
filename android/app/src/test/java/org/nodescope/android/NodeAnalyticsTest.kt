package org.nodescope.android

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.nodescope.android.core.model.*
import org.nodescope.android.core.network.*
import org.nodescope.android.feature.nodes.*

class NodeAnalyticsTest {
    private val analytics = protocolJson.decodeFromString<NodeAnalytics>(fixture("node-analytics.json"))

    @Test fun decodesWithMissingSectionsAndOffsetTimes() {
        assertTrue(analytics.peerInteractions.isEmpty())
        assertEquals(7, analytics.timeRange?.days)
        assertEquals(parseInstant("2026-09-16T12:00:00Z"), parseInstant(analytics.timeRange?.from))
        assertEquals("Type 13", payloadTypeLabel(analytics.packetTypeBreakdown[1].payloadType))
        assertEquals("B", analytics.computedStats?.signalGrade)
    }

    @Test fun urlCarriesTheRange() {
        val url = nodeAnalyticsUrl("example.org", "ab/cd", 30)
        assertEquals("/api/nodes/ab%2Fcd/analytics", url.encodedPath)
        assertEquals("30", url.queryParameter("days"))
    }

    @Test fun readingsBelongToOneObserverOldestFirst() {
        val north = observerSignals(analytics.snrTrend, emptyList()).first { it.observer == "North Observer" }
        assertEquals(listOf(5.0, 6.5), observerReadings(analytics.snrTrend, north).map { it.second })
        val south = observerSignals(analytics.snrTrend, emptyList()).first { it.observerId == "OBS-SOUTH" }
        assertEquals(listOf(-2.0), observerReadings(analytics.snrTrend, south).map { it.second })
    }

    @Test fun observerSignalsCarryNumbersAndQualityFromRadioSettings() {
        val observers = listOf(MeshObserver("OBS-NORTH", "North Observer", radio = "910.525,62.5,7,5"),
            MeshObserver("OBS-SOUTH", "South Observer", radio = "910.525,62.5,9,5"))
        val ranked = observerSignals(analytics.snrTrend, observers)
        // A sample without a name is labeled from the observer list.
        assertEquals(listOf("North Observer", "South Observer"), ranked.map { it.observer })
        val north = ranked[0]
        assertEquals(5.75, north.median, 1e-9)
        assertEquals(5.0, north.min, 1e-9); assertEquals(6.5, north.max, 1e-9)
        assertEquals(-80.0, north.medianRssi!!, 1e-9)
        assertEquals(7, north.spreadingFactor)
        assertEquals(13.25, north.margin!!, 1e-9) // 5.75 − (−7.5)
        assertEquals(SignalQuality.GOOD, north.quality)
        assertTrue(north.fewReadings)
        val south = ranked[1] // its own SF9 limit (−12.5 dB) applies
        assertEquals(10.5, south.margin!!, 1e-9)
        assertEquals(SignalQuality.GOOD, south.quality)
    }

    @Test fun unknownRadioFallsBackToTheNetworkOrToNumbersOnly() {
        val network = observerSignals(analytics.snrTrend, listOf(MeshObserver("OTHER", radio = "910.525,62.5,8,5")))
        assertEquals(listOf(8, 8), network.map { it.spreadingFactor })
        val none = observerSignals(analytics.snrTrend, emptyList())
        assertTrue(none.all { it.quality == null && it.margin == null })
        assertEquals(5.75, none[0].median, 1e-9)
    }

    @Test fun decodeLimitsAndQualityBands() {
        assertEquals(7, spreadingFactor("910.5250244,62.5,7,5"))
        assertNull(spreadingFactor("910.5,62.5"))
        assertNull(spreadingFactor(null))
        assertEquals(-7.5, snrFloor(7), 1e-9)
        assertEquals(-20.0, snrFloor(12), 1e-9)
        assertEquals(SignalQuality.STRONG, signalQuality(15.0))
        assertEquals(SignalQuality.GOOD, signalQuality(10.0))
        assertEquals(SignalQuality.WEAK, signalQuality(5.0))
        assertEquals(SignalQuality.NEAR_LIMIT, signalQuality(4.9))
    }

    @Test fun heatmapSumsDuplicatesAndIgnoresInvalidCells() {
        val grid = heatmapGrid(analytics.uptimeHeatmap)
        assertEquals(5L, grid[3][10])
        assertEquals(5L, grid.sumOf { it.sum() })
    }

    @Test fun silenceMatchesIosFormatting() {
        assertEquals("1.5h", formatSilence(5_400_000.0))
        assertEquals("45s", formatSilence(45_000.0))
        assertEquals("2m", formatSilence(120_000.0))
        assertEquals("2.0d", formatSilence(172_800_000.0))
        assertEquals("0s", formatSilence(-5.0))
    }

    private class FakeAnalytics(val status: Int?) : BrowseRepository {
        override suspend fun channels(selection: AnalyzerSelection) = error("unused")
        override suspend fun channelMessages(host: String, hash: String) = error("unused")
        override suspend fun channelPackets(selection: AnalyzerSelection) = error("unused")
        override suspend fun observers(host: String) = error("unused")
        override suspend fun observerAnalytics(host: String, id: String) = error("unused")
        override suspend fun nodeHealth(host: String, publicKey: String) = error("unused")
        override suspend fun nodePaths(host: String, publicKey: String) = error("unused")
        override suspend fun nodeReach(host: String, publicKey: String) = error("unused")
        override suspend fun packetDetail(host: String, hash: String) = error("unused")
        override suspend fun allNodes(host: String) = error("unused")
        override suspend fun recentPackets(host: String, limit: Int) = error("unused")
        override suspend fun nodeAnalytics(host: String, publicKey: String, days: Int): NodeAnalytics =
            status?.let { throw HttpFailure(it) } ?: NodeAnalytics(timeRange = AnalyticsTimeRange("a", "b", days))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun notFoundMeansUnsupportedButOtherErrorsAreErrors() = runTest {
        kotlinx.coroutines.Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val key = AnalyticsKey("example.org", "k", 7)
            val unsupported = NodeAnalyticsViewModel(FakeAnalytics(404))
            unsupported.analytics.load(key); advanceUntilIdle()
            assertEquals(AnalyticsResult.Unsupported, unsupported.analytics.state.value.value)
            val failing = NodeAnalyticsViewModel(FakeAnalytics(500))
            failing.analytics.load(key); advanceUntilIdle()
            assertEquals("Analyzer returned HTTP 500.", failing.analytics.state.value.error)
            val ok = NodeAnalyticsViewModel(FakeAnalytics(null))
            ok.analytics.load(key.copy(days = 30)); advanceUntilIdle()
            assertEquals(30, (ok.analytics.state.value.value as AnalyticsResult.Available).analytics.timeRange?.days)
        } finally { kotlinx.coroutines.Dispatchers.resetMain() }
    }

    @Test fun timeAxisSpansTheDataNotTheRequestedRange() {
        assertEquals(1_000L to 5_000L, dataDomain(listOf(5_000L, 1_000L, 3_000L)))
        assertEquals((10_000_000L - 3_600_000) to (10_000_000L + 3_600_000), dataDomain(listOf(10_000_000L)))
        assertNull(dataDomain(emptyList()))
    }

    @Test fun tiedObserversRankByReadingsThenName() {
        fun point(id: String, name: String, snr: Double) = SignalPoint("2026-09-23T10:00:00Z", snr = snr, observerId = id, observerName = name)
        val tied = listOf(point("C", "Charlie", 12.0), point("A", "alpha", 12.0), point("B", "Bravo", 12.0), point("B", "Bravo", 12.0), point("D", "Delta", 13.0))
        assertEquals(listOf("Delta", "Bravo", "alpha", "Charlie"), observerSignals(tied, emptyList()).map { it.observer })
    }
}
