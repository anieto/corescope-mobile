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

    @Test fun unchangedTransmissionsKeepTheirInstanceWhenPacketsArrive() {
        val cache = HashMap<String, TransmissionGroup>()
        val first = reuseUnchanged(groupTransmissions(listOf(packet(1, "aa", 1_000), packet(2, "bb", 2_000)), null, emptyList()), cache)
        val next = reuseUnchanged(groupTransmissions(listOf(packet(1, "aa", 1_000), packet(2, "bb", 2_000), packet(3, "bb", 3_000)), null, emptyList()), cache)
        assertSame(first.single { it.id == "aa" }, next.single { it.id == "aa" })
        assertNotSame(first.single { it.id == "bb" }, next.single { it.id == "bb" }) // a new observation changes it
        assertEquals(2, next.single { it.id == "bb" }.observations.size)
    }

    @Test fun routesKeepTheObserversThatHeardThem() {
        val heard = { name: String, rssi: Double? -> RouteHearing(name, "AUS", null, rssi) }
        val options = routeOptions(listOf(
            HeardPath(listOf("a", "b", "c", "d"), heard("Volente", -114.0)),
            HeardPath(listOf("A", "B", "C", "D"), heard("Silverado", -98.0)), // same path, stronger
            HeardPath(listOf("a", "b"), heard("HAL9000", -90.0)),            // part of the longer route
            HeardPath(listOf("x", null, "y"), heard("ASL239", -101.0)),
            HeardPath(listOf("solo"), heard("Direct", -80.0)),               // not a route
        ))
        assertEquals(listOf(listOf("a", "b", "c", "d"), listOf("x", "y")), options.map { it.keys })
        assertEquals(listOf("Silverado", "Volente"), options[0].heardBy.map { it.observer })
        assertEquals(listOf("HAL9000"), options[0].alongTheWay.map { it.observer })
        assertEquals(2, options[0].alongTheWay.single().hops)
        assertEquals(4, options[0].heardBy.first().hops)
        assertEquals("4 hops · AUS · -98 dBm", options[0].summary())
    }

    @Test fun fullRoutesAddTheirObserversWithoutRenumbering() {
        val playing = RouteOption(listOf("a", "b", "c"), listOf(RouteHearing("Volente", rssi = -110.0)))
        val merged = mergeRouteOptions(listOf(playing), listOf(
            RouteOption(listOf("A", "B", "C"), listOf(RouteHearing("Silverado", rssi = -95.0), RouteHearing("volente", rssi = -110.0))),
            RouteOption(listOf("b", "c"), listOf(RouteHearing("HAL9000", rssi = -90.0))),
            RouteOption(listOf("d", "e"), listOf(RouteHearing("ASL239")))))
        assertEquals(listOf(listOf("a", "b", "c"), listOf("d", "e")), merged.map { it.keys })
        assertEquals(listOf("Silverado", "Volente"), merged[0].heardBy.map { it.observer }) // one Volente, strongest first
        assertEquals(listOf("HAL9000"), merged[0].alongTheWay.map { it.observer })
    }

    @Test fun anObserversPathPointsAtItsRoute() {
        val options = listOf(RouteOption(listOf("a", "b", "c", "d")), RouteOption(listOf("x", "y")))
        assertEquals(0, routeIndexFor(listOf("A", "B", "C", "D"), options))
        assertEquals(0, routeIndexFor(listOf("b", "c"), options)) // heard along the way
        assertEquals(1, routeIndexFor(listOf("x", null, "y"), options))
        assertNull(routeIndexFor(listOf("q", "r"), options))
        assertNull(routeIndexFor(listOf("a"), options))
    }

    @Test fun aRouteEndsAtTheObserversThatHeardIt() {
        val observers = listOf(MeshObserver("obs-1", name = "Volente", lat = 30.4, lon = -97.9),
            MeshObserver("obs-2", name = "Silverado", lat = 30.2, lon = -97.6), MeshObserver("obs-3", name = "Nowhere"))
        val option = RouteOption(listOf("a", "b"), listOf(RouteHearing("Volente (renamed)", observerId = "OBS-1"),
            RouteHearing("silverado"), RouteHearing("Nowhere"), RouteHearing("Unknown")))
        assertEquals(listOf("Volente (renamed)" to Coordinate(30.4, -97.9), "silverado" to Coordinate(30.2, -97.6)), option.receivers(observers))
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

    @Test fun aHashReFloodedAfterTheWindowGetsItsOwnUniqueId() {
        // The same hash heard again 45 s later is a second transmission; the list keys rows by
        // id, so two ids of "aa" would crash it (seen on a busy mesh).
        val groups = groupTransmissions(listOf(packet(1, "aa", 0), packet(2, "aa", 45_000), packet(3, "aa", 95_000)), null, emptyList())
        assertEquals(3, groups.size)
        assertEquals(3, groups.map { it.id }.toSet().size)
        assertTrue(groups.any { it.id == "aa" }) // the first transmission keeps its plain hash
    }

    @Test
    fun fullRoutesAreAddedAfterThePlayingOne() {
        val playing = listOf("a", "b", "c")
        val complete = listOf(listOf("x", "a", "b", "c"), listOf("d", "e"), listOf("A", "B"))
        // The playing route stays first even though a longer route contains it; routes already
        // covered by an existing one aren't added twice.
        assertEquals(listOf(playing, listOf("x", "a", "b", "c"), listOf("d", "e")), mergeReplayRoutes(listOf(playing), complete))
        assertEquals(listOf(playing), mergeReplayRoutes(listOf(playing), listOf(listOf("B", "c"))))
    }
}
