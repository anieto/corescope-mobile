package org.nodescope.android

import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test
import org.nodescope.android.core.model.*
import org.nodescope.android.core.network.protocolJson
import org.nodescope.android.feature.map.*
import org.nodescope.android.feature.packets.*

class PacketGroupsTest {
    private fun packet(id: Long, hash: String, at: Long, observer: String = "obs$id", type: String = "ADVERT", hops: List<String?> = emptyList(),
        resolved: List<String?> = emptyList(), live: Boolean = true, region: String? = null, text: String? = null, name: String? = null, count: Int = 1) =
        LivePacket(id, hash, 4, type, observer, "Observer $observer", region, 5.0, -90.0, hops, resolved, null, at, live, count,
            payloadText = text, payloadName = name)

    @Test fun observationsOfOnePacketGroupWithinThirtySeconds() {
        val packets = listOf(packet(1, "aa", 1_000), packet(2, "aa", 20_000, hops = listOf("01", "02")), packet(3, "aa", 40_000),
            packet(4, "", 5_000), packet(5, "", 6_000), packet(6, "bb", 10_000, type = "ACK"))
        val groups = groupTransmissions(packets.shuffled(), null, emptyList())
        // "aa" at 40 s is more than 30 s after its group's first observation, so it starts a new transmission.
        assertEquals(listOf(1, 1, 1, 1, 2), groups.map { it.observations.size }.sorted())
        val first = groups.single { it.id == "aa" && it.observations.size == 2 }
        assertEquals(2, first.hopCount)
        assertEquals(20_000L, first.latestAt)
        assertEquals(40_000L, groups.first().latestAt) // newest first
        assertEquals(listOf("bb"), groupTransmissions(packets, "ACK", emptyList()).map { it.id })
    }

    @Test fun regionPreviewAndCountsFollowIos() {
        val observers = listOf(MeshObserver("OBS2", iata = "SAT"))
        val group = groupTransmissions(listOf(packet(1, "aa", 0, observer = "obs1", count = 7), packet(2, "aa", 1_000, observer = "OBS2")), null, observers).single()
        assertEquals("SAT", group.region)
        assertEquals(7, group.observationCount) // the analyzer's count wins when larger
        assertEquals("Observer obs1", group.preview)
        assertEquals("Hello", groupTransmissions(listOf(packet(3, "cc", 0, text = "Hello", name = "Node")), null, emptyList()).single().preview)
        assertEquals("Node", groupTransmissions(listOf(packet(3, "cc", 0, name = "Node")), null, emptyList()).single().preview)
        assertFalse(groupTransmissions(listOf(packet(4, "dd", 0, live = false)), null, emptyList()).single().isLive)
    }

    @Test fun replayRoutesDropDuplicatesAndContainedRoutes() {
        val group = groupTransmissions(listOf(
            packet(1, "aa", 0, resolved = listOf("A", "B", "C")),
            packet(2, "aa", 1, resolved = listOf("b", "c")),
            packet(3, "aa", 2, resolved = listOf("A", "B", "C")),
            packet(4, "aa", 3, resolved = listOf("X", null, "Y")),
            packet(5, "aa", 4, resolved = listOf("Z"))), null, emptyList()).single()
        assertEquals(listOf(listOf("A", "B", "C"), listOf("X", "Y")), replayRoutes(group))
    }

    @Test fun routeSubchainsBreakAtUnknownNodes() {
        val nodes = listOf(MeshNode("a", null, "repeater", 30.0, -97.0, ""), MeshNode("b", null, "repeater", 30.1, -97.1, ""),
            MeshNode("d", null, "repeater", 30.3, -97.3, ""), MeshNode("unset", null, "repeater", 0.0, 0.0, ""))
        assertEquals(listOf(2, 1), routeSubchains(listOf("A", "b", "missing", "d", "unset"), nodes).map { it.size })
    }

    @Test fun replayTravelsHopsInSequenceThenFadesTogether() {
        val route = LiveRoute("r", 1_000, "#FFA833", emptyList(),
            listOf(listOf(Coordinate(30.0, -97.0), Coordinate(30.0, -96.0)), listOf(Coordinate(31.0, -96.0), Coordinate(31.0, -95.0))), replay = true)
        assertEquals(listOf(1_000L, 1_850L), route.hops.map { it.startsAt }) // strictly sequential across subchains
        assertTrue(route.pulses.isEmpty())
        assertEquals(1_000L + 2 * 850 + 750 + 650, route.endsAt)
        val mid = routeFrame(listOf(route), 1_000 + 425, animate = true)
        assertEquals(1, mid.heads.size)
        val held = routeFrame(listOf(route), 1_000 + 2 * 850 + 100, animate = true)
        assertEquals(listOf(1f, 1f), held.lines.map { it.opacity })
    }

    @Test fun parsesPayloadPreviewAndRawFields() {
        val rest = protocolJson.parseToJsonElement("""{"id":9,"hash":"h","payload_type":5,"raw_hex":"15aa",
            "decoded_json":"{\"type\":\"CHAN\",\"channel\":\"#test\",\"text\":\"A: hi\"}"}""").jsonObject
        val parsed = parseLivePacket(rest, live = false)!!
        assertEquals("A: hi", parsed.payloadText)
        assertEquals("#test", parsed.payloadChannel)
        assertEquals("15aa", parsed.rawHex)
        val socket = protocolJson.parseToJsonElement("""{"id":10,"decoded":{"header":{"payloadType":4,"payloadTypeName":"ADVERT","payloadVersion":1},
            "payload":{"name":"Hilltop"}}}""").jsonObject
        val advert = parseLivePacket(socket, live = true)!!
        assertEquals("Hilltop", advert.payloadName)
        assertEquals(1, advert.payloadVersion)
        assertEquals("GRP_DATA", parseLivePacket(protocolJson.parseToJsonElement("""{"id":11,"payload_type":6}""").jsonObject, true)!!.typeName)
    }
}
