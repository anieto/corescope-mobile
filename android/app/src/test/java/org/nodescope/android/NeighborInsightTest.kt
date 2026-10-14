package org.nodescope.android

import org.junit.Assert.*
import org.junit.Test
import org.nodescope.android.core.model.*

/** Mirrors iOS NeighborInsightTests and the AnalyticsContext cases in ModelDecodingTests. */
class NeighborInsightTest {
    private fun neighbor(latitude: Double?, longitude: Double?) = NeighborInsight(
        NeighborIdentity(null, "abcdef12", null), null, null, null, null, NeighborSignal(null, null),
        NeighborDirection.UNKNOWN, latitude, longitude,
    )

    @Test fun onlyFullPublicKeysAreLinkable() {
        val full = "Ab".repeat(32)
        assertEquals(full.lowercase(), NeighborIdentity(full, null, "server-id").linkablePublicKey)
        val prefix = NeighborIdentity(null, "abcdef12", null)
        assertNull(prefix.linkablePublicKey)
        assertEquals("abcdef12", prefix.displayPrefix)
        assertNull(NeighborIdentity("abcdef12", "abcdef12", null).linkablePublicKey)
    }

    @Test fun coordinateValidityPreservesUnknownAndRejectsSentinels() {
        assertFalse(neighbor(null, null).hasValidCoordinate)
        assertFalse(neighbor(0.0, 0.0).hasValidCoordinate)
        assertTrue(neighbor(30.0, -98.0).hasValidCoordinate)
        assertFalse(neighbor(91.0, -98.0).hasValidCoordinate)
    }

    @Test fun beaconContextCapsHistoryAndPreservesEmptyCoverage() {
        val end = java.time.Instant.parse("2026-10-13T12:00:00Z").toEpochMilli()
        val day = 86_400_000L
        val capped = AnalyticsContext.beaconObservations(30, end - 7 * day, end)
        assertEquals(AnalyticsCompleteness.PARTIAL, capped.completeness)
        assertEquals(AnalyticsCountUnit.OBSERVATIONS, capped.countUnit)
        assertEquals(end - 30 * day, capped.requestedFrom)
        assertEquals(end - 7 * day, capped.coveredFrom)
        assertTrue(capped.explanation!!.contains("seven days"))

        val oneDay = AnalyticsContext.beaconObservations(1, end - day, end)
        assertEquals(AnalyticsCompleteness.COMPLETE, oneDay.completeness)
        assertTrue(oneDay.hasKnownCoverage)

        val unsupported = AnalyticsContext.unsupported(AnalyticsCountUnit.PACKETS, end - 7 * day, end, "Not available.")
        assertEquals(AnalyticsCompleteness.UNSUPPORTED, unsupported.completeness)
        assertFalse(unsupported.hasKnownCoverage)
    }

    @Test fun coreScopeAnalyticsDecodeWithoutContext() {
        val decoded = org.nodescope.android.core.network.protocolJson.decodeFromString(NodeAnalytics.serializer(), "{}")
        assertNull(decoded.context)
    }
}
