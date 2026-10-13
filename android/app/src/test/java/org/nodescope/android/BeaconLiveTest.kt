package org.nodescope.android

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import org.nodescope.android.core.model.MeshNode
import org.nodescope.android.core.model.MeshObserver
import org.nodescope.android.core.network.*
import org.nodescope.android.core.network.BeaconLiveProtocol.Incoming
import org.nodescope.android.core.network.BeaconLiveProtocol.NodeUpdate

/** Mirrors the live-protocol, transport and recovery cases in iOS BeaconAPITests. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class BeaconLiveTest {
    private val info = BeaconServerInfo("2.0.3")
    private val key = "a".repeat(64)
    private val nodeId = "00d4777e-066d-a817-622f-338eca619683"
    private val observerId = "e207a86a-4128-74e6-568f-2b571e3ec71d"

    private class TestSocket(
        override var responseCode: Int? = null,
        override var retryAfter: String? = null,
        override var closeCode: Int? = null,
    ) : BeaconSocket {
        val incoming = Channel<String>(Channel.UNLIMITED)
        val sent = mutableListOf<JsonObject>()
        var closed = false
        fun push(text: String) { incoming.trySend(text) }
        override suspend fun receive() = incoming.receive()
        override suspend fun send(text: String) { sent += protocolJson.parseToJsonElement(text).jsonObject }
        override fun close() { closed = true; incoming.close(IOException("closed")) }
        fun sentTypes() = sent.map { it.getValue("type").jsonPrimitive.content }
    }

    private fun client(session: AnalyzerSourceSession = AnalyzerSourceSession(), rate: BeaconRateLimit = BeaconRateLimit(), now: () -> Long = { 0 }) =
        BeaconApiClient("https://beacon.invalid", session, OkHttpClient(), rate, now)

    private fun node(json: String) = (BeaconLiveProtocol.decode(
        """{"v":1,"type":"event","event":"nodeUpdate","data":{"nodeId":"$nodeId","publicKey":"$key","nodeType":2$json}}""",
    ) as Incoming.Node).value

    @Test fun liveConfigurationAlwaysSendsAllFlags() {
        val command = BeaconLiveProtocol.Command.configure(LiveFeedConfiguration(resolvePath = true)).copy(id = "c")
        assertEquals(
            """{"id":"c","includeObserverKey":false,"includeRepeats":false,"resolvePath":true,"type":"configure","v":1}""",
            reorder(command.encoded()),
        )
        val subscribe = BeaconLiveProtocol.Command("subscribe", "s", BeaconLiveProtocol.Command.Scope(listOf("SAT"), emptyList()))
        val scope = protocolJson.parseToJsonElement(subscribe.encoded()).jsonObject.getValue("scope").jsonObject
        assertEquals(listOf("packetObservation", "observerStatus", "nodeUpdate"),
            scope.getValue("events").jsonArray.map { it.jsonPrimitive.content })
    }

    /** Keys must already be sorted: re-sorting must not change the text. */
    private fun reorder(text: String): String {
        val root = protocolJson.parseToJsonElement(text).jsonObject
        assertEquals(root.keys.sorted(), root.keys.toList())
        return text
    }

    @Test fun liveSubscriptionsRemoveLateAcknowledgementsAndRestoreAfterReconnect() {
        val subscriptions = BeaconLiveProtocol.Subscriptions()
        val sat = LiveFeedSubscription(setOf("SAT"))
        assertTrue("Nothing is sent before hello", subscriptions.update(setOf(sat), LiveFeedConfiguration()).isEmpty())
        val hello = subscriptions.hello()
        assertEquals(listOf("configure", "subscribe"), hello.map { it.type })
        val request = hello.last().id
        subscriptions.update(emptySet(), LiveFeedConfiguration())
        val late = subscriptions.acknowledge(request, "server-1")
        assertEquals(listOf("unsubscribe"), late.map { it.type })
        assertEquals("server-1", late.single().subscriptionId)

        subscriptions.update(setOf(sat), LiveFeedConfiguration())
        subscriptions.disconnected()
        assertEquals("Reconnect restores every subscription", listOf("configure", "subscribe"), subscriptions.hello().map { it.type })
    }

    @Test fun liveActiveSubscriptionRemovalAndFlagDisableAreExplicit() {
        val subscriptions = BeaconLiveProtocol.Subscriptions()
        val sat = LiveFeedSubscription(setOf("SAT"))
        subscriptions.update(setOf(sat), LiveFeedConfiguration(resolvePath = true))
        val request = subscriptions.hello().last().id
        assertTrue(subscriptions.acknowledge(request, "server-1").isEmpty())
        val removal = subscriptions.update(emptySet(), LiveFeedConfiguration())
        assertEquals(listOf("unsubscribe", "configure"), removal.map { it.type })
        assertEquals(false, removal.last().resolvePath)
    }

    @Test fun liveLaggedNoticesRequestRecoveryAndUnknownEventsAreIgnored() {
        assertEquals(Incoming.RecoveryRequired, BeaconLiveProtocol.decode("""{"v":1,"type":"lagged","dropped":4}"""))
        assertEquals(Incoming.Ignored, BeaconLiveProtocol.decode("""{"v":1,"type":"event","event":"channelMessage","data":{}}"""))
        assertEquals(Incoming.Ignored, BeaconLiveProtocol.decode("""{"v":1,"type":"future"}"""))
        assertThrows(BeaconApiException.InvalidResponse::class.java) { BeaconLiveProtocol.decode("""{"v":2,"type":"hello"}""") }
        assertThrows(BeaconApiException.InvalidResponse::class.java) { BeaconLiveProtocol.decode("""{"v":1,"type":"pong"}""") }
    }

    @Test fun liveFixtureDecodesEvenNonFirstObservationsAndOptionalRoutes() {
        val frames = protocolJson.parseToJsonElement(fixture("beacon/ws-session.json")).jsonObject.getValue("frames").jsonArray
        val incoming = frames.map { it.jsonObject }.filter { it.getValue("dir").jsonPrimitive.content == "in" }
        val decoded = incoming.map { BeaconLiveProtocol.decode(it.getValue("msg").toString()) }
        assertTrue(decoded.first() == Incoming.Hello)
        val packets = decoded.filterIsInstance<Incoming.Packet>()
        assertTrue(packets.any { it.value.packet.observationCount > 1 })
        assertTrue(decoded.any { it is Incoming.Node } && decoded.any { it is Incoming.Observer })
    }

    @Test fun liveNodePositionsDistinguishOmissionFromClearing() {
        assertEquals(NodeUpdate.Position.Unchanged, node("").position)
        assertEquals(NodeUpdate.Position.Clear, node(""","lat":null,"lng":null""").position)
        assertEquals(NodeUpdate.Position.Clear, node(""","lat":0,"lng":0""").position)
        assertEquals(NodeUpdate.Position.Coordinate(29.4, -98.5), node(""","lat":29.4,"lng":-98.5""").position)
        assertThrows(BeaconApiException.InvalidResponse::class.java) { node(""","lat":29.4""") }
    }

    @Test fun liveNodeStateMergesMembershipAndPreservesExplicitClear() {
        var state = BeaconLiveProtocol.NodeState.of(node(""","name":"A","lat":29.4,"lng":-98.5,"iatas":[{"iata":"SAT","lastHeard":1000}]"""))
        state = state.merge(node(""","iatas":[{"iata":"aus","lastHeard":2000}]"""))
        assertEquals(setOf("SAT", "AUS"), state.regions.keys)
        assertEquals("A", state.name)
        assertTrue(state.position is NodeUpdate.Position.Coordinate)
        state = state.merge(node(""","lat":null,"lng":null,"iatas":[{"iata":"SAT","lastHeard":3000}]"""))
        assertEquals(NodeUpdate.Position.Clear, state.position)
        state = state.merge(node(""","name":"Old","lat":1,"lng":1,"iatas":[{"iata":"SAT","lastHeard":500}]"""))
        assertEquals("An older advert never overwrites newer state", "A", state.name)
        assertEquals(NodeUpdate.Position.Clear, state.position)
        assertEquals(3000L, state.regions.getValue("SAT").lastHeard)
    }

    @Test fun liveMapOlderPositionDoesNotOverwriteNewerRest() {
        val existing = MeshNode(key, "Rest", "repeater", 30.0, -97.0, "1970-01-01T00:00:05Z")
        val state = BeaconLiveProtocol.NodeState.of(node(""","name":"Live","lat":29.4,"lng":-98.5,"iatas":[{"iata":"SAT","lastHeard":1000}]"""))
        val merged = state.applying(existing)
        assertEquals("Rest", merged.name)
        assertEquals(30.0, merged.lat!!, 0.0)
        assertEquals("1970-01-01T00:00:05Z", merged.lastSeen)
        val fresh = BeaconLiveProtocol.NodeState.of(node(""","lat":null,"lng":null,"iatas":[{"iata":"SAT","lastHeard":9000}]""")).applying(existing)
        assertNull(fresh.lat)
        assertEquals("Rest", fresh.name)
    }

    @Test fun observerStatusProjectionClearsMissingFieldsWithoutInventingActivity() {
        val observer = MeshObserver(observerId, name = "Old", iata = "AUS", radio = "x", batteryMv = 4000, uptimeSecs = 5,
            lastSeen = "2026-01-01T00:00:00Z", model = "RAK")
        val status = (BeaconLiveProtocol.decode(
            """{"v":1,"type":"event","event":"observerStatus","data":{"observerId":"$observerId","online":false}}""",
        ) as Incoming.Observer).value
        val applied = status.applying(observer)
        assertNull(applied.name); assertNull(applied.radio); assertNull(applied.batteryMv)
        assertEquals(false, applied.online)
        assertEquals("2026-01-01T00:00:00Z", applied.lastSeen)
        assertEquals("RAK", applied.model)
        assertSame("Other observers are untouched", observer, status.copy(observerId = "x").applying(observer))
    }

    @Test fun liveStateKeepsAtMostAThousandNodesInRecencyOrder() {
        var state = BeaconLiveState()
        repeat(1_001) { index ->
            val hex = index.toString(16).padStart(64, '0')
            state = state.reduce(BeaconLiveEvent.NodeUpdated(NodeUpdate(nodeId, hex, 2, null, null, null, NodeUpdate.Position.Unchanged)))
        }
        assertEquals(1_000, state.nodes.size)
        assertFalse("0".repeat(64) in state.nodes)
        state = state.reduce(BeaconLiveEvent.MetadataInvalidated)
        assertTrue(state.nodes.isEmpty() && state.metadataNeedsRecovery)
    }

    private fun TestScope.transport(
        client: BeaconApiClient,
        sockets: MutableList<TestSocket>,
        made: MutableList<String> = mutableListOf(),
    ) = BeaconLiveTransport(client, info, backgroundScope, makeSocket = { url ->
        made += url
        sockets.removeAt(0)
    }, now = { testScheduler.currentTime })

    @Test fun liveTransportSendsDemandAndStopsOnSourceSwitch() = runTest {
        val socket = TestSocket()
        val made = mutableListOf<String>()
        val client = client(now = { testScheduler.currentTime })
        val transport = transport(client, mutableListOf(socket), made)
        transport.update(setOf(LiveFeedSubscription(setOf("SAT"))), LiveFeedConfiguration(resolvePath = true))
        transport.connect()
        socket.push("""{"v":1,"type":"hello"}""")
        runCurrent()
        assertEquals(listOf("wss://beacon.invalid/ws"), made)
        assertEquals(listOf("configure", "subscribe"), socket.sentTypes())
        assertEquals(true, socket.sent.first()["resolvePath"]?.jsonPrimitive?.boolean)
        assertEquals(Incoming.Hello, (transport.events.receive() as BeaconLiveTransport.Event.Frame).frame)
        assertEquals("Hello always asks for recovery", Incoming.RecoveryRequired,
            (transport.events.receive() as BeaconLiveTransport.Event.Frame).frame)
        client.sourceSession.invalidate()
        runCurrent()
        assertTrue(socket.closed)
    }

    @Test fun liveTransportPingsAndReconnectsAfterMissingHello() = runTest {
        val first = TestSocket()
        val second = TestSocket().also { it.push("""{"v":1,"type":"hello"}""") }
        val made = mutableListOf<String>()
        val client = client(now = { testScheduler.currentTime })
        val transport = transport(client, mutableListOf(first, second), made)
        transport.update(setOf(LiveFeedSubscription()), LiveFeedConfiguration())
        transport.connect()
        advanceTimeBy(20_001)
        runCurrent()
        assertTrue("No hello within 20 s closes the socket", first.closed)
        advanceTimeBy(1_001)
        runCurrent()
        assertEquals(2, made.size)
        // Acknowledge, so no request stays outstanding long enough to be timed out.
        second.sent.forEach { command ->
            val id = command.getValue("id").jsonPrimitive.content
            if (command.getValue("type").jsonPrimitive.content == "subscribe") {
                second.push("""{"v":1,"type":"subscribed","id":"$id","subscriptionId":"s"}""")
            } else second.push("""{"v":1,"type":"configured","id":"$id"}""")
        }
        advanceTimeBy(30_001)
        runCurrent()
        assertTrue(second.sentTypes().contains("ping"))
        assertFalse(second.closed)
        transport.disconnect()
    }

    @Test fun liveTransport429SharesCooldownWithRest() = runTest {
        val rate = BeaconRateLimit()
        val client = client(rate = rate, now = { testScheduler.currentTime })
        val refused = TestSocket(responseCode = 429, retryAfter = "120").also { it.incoming.close(IOException("429")) }
        val made = mutableListOf<String>()
        val transport = transport(client, mutableListOf(refused, TestSocket()), made)
        transport.connect()
        runCurrent()
        assertTrue(transport.events.receive() is BeaconLiveTransport.Event.Disconnected)
        try {
            rate.check(client.cacheIdentifier, testScheduler.currentTime)
            fail("REST must share the socket's cooldown")
        } catch (_: BeaconApiException.RateLimited) {}
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals("No reconnect while cooling down", 1, made.size)
        advanceTimeBy(61_000)
        runCurrent()
        assertEquals(2, made.size)
        transport.disconnect()
    }

    private fun summary(hash: String, last: Long, first: Long = last, count: Int = 1) =
        BeaconPacketSummary(hash, 4, 1, first, last, count)

    private fun observation(hash: String, heard: Long, count: Int = 1, iata: String? = "SAT", type: Int = 4) =
        BeaconLiveProtocol.Observation(hash, BeaconLiveProtocol.Observation.Header(type, 1, count),
            BeaconLiveProtocol.Observation.Hearing(observerId, iata = iata, heardAt = heard))

    @Test fun liveRecoveryMergesByHashWithoutInventingFirstSeen() = runTest {
        val recovery = BeaconLiveRecovery(AnalyzerSourceSession(), "SAT", 4) {
            AnalyzerPage(listOf(summary("a", 50, first = 10, count = 3)), null)
        }
        recovery.receive(observation("a", 60, count = 5))
        recovery.receive(observation("b", 20, iata = "AUS"))
        recovery.receive(observation("c", 20, type = 0))
        assertEquals(listOf("a"), recovery.rows.map { it.hash })
        assertNull("Live rows have no first-seen date", recovery.rows.single().firstSeen)
        recovery.recover()
        val row = recovery.rows.single()
        assertEquals(10L, row.firstSeen)
        assertEquals(60L, row.lastHeard)
        assertEquals(5, row.observationCount)
        assertTrue("A first page can't prove nothing was missed", recovery.possiblyIncomplete)
    }

    @Test fun liveRecoveryKeepsNewerEventsArrivingDuringRest() = runTest {
        val gate = CompletableDeferred<Unit>()
        lateinit var recovery: BeaconLiveRecovery
        recovery = BeaconLiveRecovery(AnalyzerSourceSession(), null, null) {
            gate.await()
            AnalyzerPage(listOf(summary("server-new", 100), summary("server-old", 50)), null)
        }
        recovery.receive(observation("old-live", 10))
        val job = backgroundScope.launchRecovery(recovery)
        runCurrent()
        recovery.receive(observation("during", 200))
        gate.complete(Unit)
        job.await()
        assertEquals(listOf("during", "server-new", "server-old", "old-live"), recovery.rows.map { it.hash })
    }

    @Test fun liveRecoveryRejectsLateSourceResults() = runTest {
        val session = AnalyzerSourceSession()
        val recovery = BeaconLiveRecovery(session, null, null) {
            session.invalidate()
            AnalyzerPage(listOf(summary("late", 1)), null)
        }
        try { recovery.recover(); fail() } catch (_: kotlinx.coroutines.CancellationException) {}
        assertTrue(recovery.rows.isEmpty())
    }

    private fun kotlinx.coroutines.CoroutineScope.launchRecovery(recovery: BeaconLiveRecovery) =
        async { recovery.recover() }

    @Test fun nodeRecoveryIsDemandGatedAndRejectsStaleResults() = runTest {
        val socket = TestSocket()
        val client = client(now = { testScheduler.currentTime })
        var fetches = 0
        val feed = BeaconLiveFeed(client, info, backgroundScope,
            transport = transport(client, mutableListOf(socket)),
            fetchNodes = { fetches++; emptyList() }, fetchObservers = { emptyList() })
        feed.subscribe(setOf(LiveFeedSubscription()))
        feed.connect()
        socket.push("""{"v":1,"type":"hello"}""")
        runCurrent()
        assertEquals("No node catalog until a screen asks for resolved paths", 0, fetches)
        feed.configure(LiveFeedConfiguration(resolvePath = true))
        runCurrent()
        assertEquals(1, fetches)
        val events = generateSequence { feed.events.tryReceive().getOrNull() }.toList()
        assertTrue(events.contains(BeaconLiveEvent.Connected))
        assertTrue(events.any { it is BeaconLiveEvent.NodeCatalog })
        assertTrue(events.any { it is BeaconLiveEvent.BrowseSnapshot })
        feed.disconnect()
    }
}
