package org.nodescope.android

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import org.nodescope.android.core.model.*
import org.nodescope.android.core.network.*
import org.nodescope.android.core.storage.*
import org.nodescope.android.feature.map.packetRoute

@OptIn(ExperimentalCoroutinesApi::class)
class LiveFeedTest {
    private fun packet(id: Long = 1, observer: String = "obs", hops: String = "[]", resolved: String = "[]") = parseLivePacket(
        protocolJson.parseToJsonElement("""{"id":$id,"hash":"h$id","payload_type":8,"observer_id":"$observer","path_json":$hops,"resolved_path":$resolved} """).jsonObject, true, id)!!

    @Test fun decodesCurrentAndLegacyPacketsWithNullableHops() {
        val current = packet(hops = "[\"aa\",\"bb\"]", resolved = "[\"aabb\",null]")
        assertEquals("PATH", current.typeName)
        assertEquals(listOf("aabb", null), current.resolvedPath)
        val legacy = parseLivePacket(protocolJson.parseToJsonElement("""{"id":2,"decoded":{"header":{"payloadType":5},"path":{"hops":["aa"]}},"observer_id":"radio"}""").jsonObject, true)!!
        assertEquals("GRP_TXT", legacy.typeName)
        assertEquals(listOf("aa"), legacy.hops)
        assertEquals("radio", legacy.observerId)
        assertNull(parseLivePacket(buildJsonObject { put("hash", "missing-id") }, true))
    }
    @Test fun explicitUnresolvedHopsAndAmbiguousPrefixesNeverJoinRoutes() {
        val nodes = listOf(node("aa11", 30.0), node("aa22", 31.0), node("bb11", 32.0))
        val observers = listOf(MeshObserver("obs", lat = 33.0, lon = -97.0))
        assertEquals(listOf(listOf(Coordinate(32.0, -97.0), Coordinate(33.0, -97.0))), packetRoute(packet(hops = "[\"aa\",\"bb\"]"), nodes, observers))
        val segments = packetRoute(packet(hops = "[\"aa11\",\"bb11\"]", resolved = "[\"aa11\",null]"), nodes, observers)
        assertEquals(listOf(listOf(Coordinate(30.0, -97.0)), listOf(Coordinate(33.0, -97.0))), segments)
    }
    @Test fun regionFilteringFailsClosedWithoutObserverRoster() {
        val state = LiveFeedState(AnalyzerSelection("a.example", "DFW"), packets = listOf(packet(observer = "one"), packet(2, "two")))
        assertTrue(state.visiblePackets.isEmpty())
        assertEquals(listOf(1L), state.copy(observers = listOf(MeshObserver("one", iata = "dfw"))).visiblePackets.map { it.id })
    }
    @Test fun changingSourceCancelsSocketAndClearsOldPackets() = runTest {
        val preferences = LivePreferences(AppPreferences(host = "old.example", onboarded = true))
        var canceled = false
        val source = object : PacketSource {
            override suspend fun recent(selection: AnalyzerSelection) = emptyList<LivePacket>()
            override suspend fun observers(host: String) = emptyList<MeshObserver>()
            override fun events(host: String) = flow {
                emit(LiveSignal.Connection(LiveConnection.LIVE))
                if (host == "old.example") emit(LiveSignal.Packet(packet()))
                try { awaitCancellation() } finally { if (host == "old.example") canceled = true }
            }
        }
        val states = mutableListOf<LiveFeedState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { LiveFeed(preferences, source).states.toList(states) }
        runCurrent(); assertEquals(1, states.last().packets.size)
        preferences.selectSource("new.example"); runCurrent()
        assertTrue(canceled)
        assertEquals("new.example", states.last().selection?.host)
        assertTrue(states.last().packets.isEmpty())
    }
    @Test fun historyCannotReplaceLiveObservationsAndBufferIsBounded() = runTest {
        val release = CompletableDeferred<Unit>()
        val source = object : PacketSource {
            override suspend fun recent(selection: AnalyzerSelection): List<LivePacket> { release.await(); return listOf(packet(250).copy(isLive = false)) }
            override suspend fun observers(host: String) = emptyList<MeshObserver>()
            override fun events(host: String) = flow {
                emit(LiveSignal.Connection(LiveConnection.LIVE))
                repeat(250) { emit(LiveSignal.Packet(packet(it + 1L))) }
                awaitCancellation()
            }
        }
        val states = mutableListOf<LiveFeedState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { LiveFeed(LivePreferences(AppPreferences(onboarded = true)), source).states.toList(states) }
        runCurrent(); release.complete(Unit); runCurrent()
        assertEquals(200, states.last().packets.size)
        assertTrue(states.last().packets.first().isLive)
        assertEquals(250L, states.last().packets.first().id)
    }
    @Test fun noLiveConnectionBeforeOnboardingAndCollectorCancellationClosesIt() = runTest {
        val preferences = LivePreferences(AppPreferences())
        var opened = 0; var closed = 0
        val source = object : PacketSource {
            override suspend fun recent(selection: AnalyzerSelection) = emptyList<LivePacket>()
            override suspend fun observers(host: String) = emptyList<MeshObserver>()
            override fun events(host: String) = flow<LiveSignal> { opened++; try { awaitCancellation() } finally { closed++ } }
        }
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { LiveFeed(preferences, source).states.collect() }
        runCurrent(); assertEquals(0, opened)
        preferences.selectSource("a.example"); runCurrent(); assertEquals(1, opened)
        job.cancel(); runCurrent(); assertEquals(1, closed)
    }
    @Test fun socketIgnoresHeartbeatsAndMalformedFramesThenReceivesPackets() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send("""{"type":"heartbeat"}""")
                webSocket.send("not-json")
                webSocket.send("""{"type":"message","data":{"id":99}}""")
                webSocket.send("""{"type":"packet","data":{"id":42,"payload_type":4,"observer_id":"radio"}}""")
            }
        }))
        try {
            val result = withTimeout(5_000) { HttpPacketSource(OkHttpClient()).connection(server.url("/")).filterIsInstance<LiveSignal.Packet>().first() }
            assertEquals(42L, result.value.id)
            assertEquals("ADVERT", result.value.typeName)
            assertEquals("/", server.takeRequest().path)
        } finally { server.shutdown() }
    }
    @Test fun closedSocketReconnectsAndDeliversNewTraffic() = runBlocking {
        val server = MockWebServer()
        for (id in listOf(1, 2)) server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send("""{"type":"packet","data":{"id":$id,"payload_type":8}}""")
                if (id == 1) webSocket.close(1000, "reconnect test")
            }
        }))
        try {
            val packets = withTimeout(7_000) { HttpPacketSource(OkHttpClient()).reconnecting(server.url("/")).filterIsInstance<LiveSignal.Packet>().take(2).toList() }
            assertEquals(listOf(1L, 2L), packets.map { it.value.id })
            assertEquals(2, server.requestCount)
        } finally { server.shutdown() }
    }
    private fun node(key: String, latitude: Double) = MeshNode(key, key, "repeater", latitude, -97.0, "2026-01-01")
}

private class LivePreferences(initial: AppPreferences) : PreferencesStore {
    override val values = MutableStateFlow(initial)
    override suspend fun selectSource(host: String) { values.update { it.copy(host = host, onboarded = true, region = null) } }
    override suspend fun setRegion(region: String?) { values.update { it.copy(region = region) } }
    override suspend fun setAppearance(appearance: Appearance) { values.update { it.copy(appearance = appearance) } }
    override suspend fun setDestination(destination: String) { values.update { it.copy(destination = destination) } }
    override suspend fun cacheRegistry(document: String) { }
}
