package org.nodescope.android

import org.junit.Assert.*
import org.junit.Test
import org.nodescope.android.core.model.*
import org.nodescope.android.core.network.*
import org.nodescope.android.feature.packets.distinctRoutes

class PacketDetailTest {
    private val detail = protocolJson.decodeFromString<PacketDetail>(fixture("packet-detail.json"))

    @Test fun decodesWithPartialObservations() {
        assertEquals("GRP_TXT", payloadTypeName(detail.packet.payloadType))
        assertEquals(3, detail.path.size)
        assertEquals(3, detail.observationCount)
        assertNull(detail.observations[1].observerName)
        assertNull(detail.observations[1].snr)
        assertEquals(listOf(null), detail.observations[2].resolvedPath?.filterIndexed { i, _ -> i == 1 })
    }

    @Test fun routesDropContainedPathsAndBreakAtUnresolvedHops() {
        // ["BB22","CC33"] is inside ["aa11","bb22","cc33"] ignoring case; the null hop leaves ["dd44","ee55"].
        assertEquals(listOf(listOf("aa11", "bb22", "cc33"), listOf("dd44", "ee55")),
            distinctRoutes(detail.observations.map { it.resolvedPath.orEmpty() }))
    }

    @Test fun hashIsOnePathSegment() {
        assertEquals("/api/packets/ab%2Fcd", packetDetailUrl("example.org", "ab/cd").encodedPath)
    }
}
