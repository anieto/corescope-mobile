package org.nodescope.android

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.nodescope.android.core.model.AnalyzerSelection
import org.nodescope.android.core.network.*

/** Mirrors iOS BeaconAPITests: REST client, readers, backend and source resolution. */
class BeaconApiTest {
    private val host = "beacon.example"
    private val info = BeaconServerInfo("2.0.3")

    /** Fake Beacon: "path?query" → (status, body, Retry-After); records every request path. */
    private class FakeBeacon(val routes: MutableMap<String, Triple<Int, String, String?>> = mutableMapOf()) : Interceptor {
        val requests = mutableListOf<String>()
        override fun intercept(chain: Interceptor.Chain): Response {
            val url = chain.request().url
            val key = url.encodedPath.removePrefix("/api/v1/") + (url.encodedQuery?.let { "?$it" } ?: "")
            requests += key
            val (status, body, retry) = routes[key] ?: Triple(404, """{"error":{"code":"not_found","message":"x"}}""", null)
            return Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(status).message("fixture")
                .apply { retry?.let { header("Retry-After", it) } }
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }
    }

    private fun client(fake: FakeBeacon) = OkHttpClient.Builder().addInterceptor(fake).build()
    private fun api(fake: FakeBeacon, session: AnalyzerSourceSession = AnalyzerSourceSession(), rate: BeaconRateLimit = BeaconRateLimit()) =
        BeaconApiClient("https://$host", session, client(fake), rate)

    private fun body(name: String): JsonElement = protocolJson.parseToJsonElement(fixture("beacon/$name"))
        .jsonObject.getValue("body")
    private fun page(items: JsonElement, hasMore: Boolean = false, next: JsonElement? = null) =
        buildJsonObject { put("items", items); put("hasMore", hasMore); next?.let { put("nextCursor", it) } }.toString()

    @Test fun versionsAreStrictAndTheMobileFloorIsIgnored() {
        assertEquals(listOf(2, 0, 3), BeaconServerInfo("2.0.3").versionComponents)
        assertEquals(listOf(2, 1, 0), BeaconServerInfo("2.1.0+build.5").versionComponents)
        for (bad in listOf("2.0", "v2.0.3", "02.0.3", "2.0.3.1", "2.0.x", "", "2.0.3+")) {
            assertNull(bad, BeaconServerInfo(bad).versionComponents)
        }
        BeaconServerInfo("2.0.5").requireSupported()
        assertThrows(BeaconApiException.UnsupportedVersion::class.java) { BeaconServerInfo("2.0.2").requireSupported() }
        // dev.meshcore.ca sets minAppVersion 2.0.0 for BEACON Mobile; NodeScope never reads it.
        val decoded = protocolJson.decodeFromString(BeaconServerInfo.serializer(),
            """{"minAppVersion":"9.9.9","minWebVersion":"9.9.9","serverVersion":"2.0.5"}""")
        decoded.requireSupported()
    }

    @Test fun encodesEachPathSegmentAndReportsStructuredErrors() = runBlocking {
        val fake = FakeBeacon()
        val client = api(fake)
        try {
            client.get(JsonObject.serializer(), listOf("packets", "a/b?c"))
            fail("404 expected")
        } catch (e: BeaconApiException.Server) {
            assertEquals(404, e.status)
            assertEquals("not_found", e.code)
        }
        assertEquals("packets/a%2Fb%3Fc", fake.requests.single())
    }

    @Test fun invalidatedSourcesNeverStartRequests() = runBlocking {
        val fake = FakeBeacon(mutableMapOf("info" to Triple(200, """{"serverVersion":"2.0.3"}""", null)))
        val session = AnalyzerSourceSession()
        val client = api(fake, session)
        session.invalidate()
        try {
            client.serverInfo()
            fail("An invalidated source must not run")
        } catch (_: CancellationException) {}
        assertTrue(fake.requests.isEmpty())
    }

    @Test fun cooldownIsSharedAcrossClientsButNotServers() = runBlocking {
        val rate = BeaconRateLimit()
        val fake = FakeBeacon(mutableMapOf("info" to Triple(429, "{}", "120")))
        val first = api(fake, rate = rate)
        try { first.serverInfo(); fail("429 expected") } catch (_: BeaconApiException.RateLimited) {}
        val second = api(fake, rate = rate)
        try { second.serverInfo(); fail("cooldown expected") } catch (_: BeaconApiException.RateLimited) {}
        assertEquals("A cooling-down origin must not be requested again", 1, fake.requests.size)
        val other = BeaconApiClient("https://other.example", AnalyzerSourceSession(), client(fake), rate)
        // The fake answers 429 everywhere; what matters is that the other origin was still asked.
        try { other.serverInfo() } catch (_: BeaconApiException.RateLimited) {}
        assertEquals("Other servers are not throttled", 2, fake.requests.size)
    }

    @Test fun retryAfterDatesAndFallbackAreBounded() {
        val now = 1_000_000L
        assertEquals(now + 5_000, BeaconRateLimit.retryDate("5", now))
        assertEquals(now + 30_000, BeaconRateLimit.retryDate("soon", now))
        assertEquals(now + 30_000, BeaconRateLimit.retryDate(null, now))
        assertEquals(now + 30_000, BeaconRateLimit.retryDate("-3", now))
        val future = Instant.ofEpochMilli(now).plusSeconds(90)
        val http = DateTimeFormatter.RFC_1123_DATE_TIME.format(future.atOffset(ZoneOffset.UTC))
        assertEquals(future.toEpochMilli(), BeaconRateLimit.retryDate(http, now))
        val past = DateTimeFormatter.RFC_1123_DATE_TIME.format(Instant.ofEpochMilli(0).atOffset(ZoneOffset.UTC))
        assertEquals("A past date never moves the deadline backwards", now, BeaconRateLimit.retryDate(past, now))
    }

    @Test fun nodePagesWalkCursorsWithoutTruncation() = runBlocking {
        val items = body("nodes-page.json").jsonObject.getValue("items").jsonArray
        val fake = FakeBeacon(mutableMapOf(
            "nodes?limit=200" to Triple(200, page(JsonArray(items.take(3)), true, JsonPrimitive(1791663775459)), null),
            "nodes?limit=200&cursor=1791663775459" to Triple(200, page(JsonArray(items.drop(3))), null),
        ))
        val reader = BeaconNodeReader(api(fake), info)
        val nodes = reader.nodes(null)
        assertEquals(items.size, nodes.size)
        assertEquals(listOf("nodes?limit=200", "nodes?limit=200&cursor=1791663775459"), fake.requests)
    }

    @Test fun nodeCursorsAreBoundToEndpointSourceSessionAndRegion() = runBlocking {
        val items = body("nodes-page.json").jsonObject.getValue("items").jsonArray
        val fake = FakeBeacon(mutableMapOf("nodes?limit=200&iatas=SAT" to Triple(200, page(items, true, JsonPrimitive("opaque")), null)))
        val client = api(fake)
        val reader = BeaconNodeReader(client, info)
        val cursor = checkNotNull(reader.nodePage("SAT").nextCursor)
        for (wrong in listOf<suspend () -> Unit>(
            { reader.nodePage("AUS", cursor = cursor) },
            { BeaconNodeReader(client.replacingSourceSession(AnalyzerSourceSession()), info).nodePage("SAT", cursor = cursor) },
            { BeaconObserverReader(client, info).observerPage("SAT", cursor = cursor) },
        )) {
            try { wrong(); fail("Cursor must be rejected") } catch (_: AnalyzerPagingException.InvalidCursor) {}
        }
    }

    @Test fun missingOrRepeatedContinuationFailsInsteadOfTruncating() = runBlocking {
        val items = body("nodes-page.json").jsonObject.getValue("items").jsonArray
        val missing = FakeBeacon(mutableMapOf("nodes?limit=200" to Triple(200, page(items, true), null)))
        try { BeaconNodeReader(api(missing), info).nodes(null); fail() } catch (_: BeaconApiException.MalformedPage) {}
        val repeated = FakeBeacon(mutableMapOf(
            "nodes?limit=200" to Triple(200, page(JsonArray(items.take(2)), true, JsonPrimitive(7)), null),
            "nodes?limit=200&cursor=7" to Triple(200, page(JsonArray(items.drop(2)), true, JsonPrimitive(7)), null),
        ))
        try { BeaconNodeReader(api(repeated), info).nodes(null); fail() } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("repeated"))
        }
    }

    @Test fun nodeMappingUsesNewestRegionAndDropsInvalidCoordinates() {
        val node = BeaconNode(
            id = "e28eb9e9-ab40-c112-32c5-50393ebb2359", publicKey = "D".repeat(64), nodeType = 9, lat = 0.0, lng = 0.0,
            iatas = listOf(BeaconNodeRegion("SAT", 1_000), BeaconNodeRegion("AUS", 5_000), BeaconNodeRegion("DFW", null)),
        ).meshNode()
        assertEquals("d".repeat(64), node.publicKey)
        assertEquals("unknown", node.role)
        assertNull("(0, 0) is an unset position", node.lat)
        assertEquals(Instant.ofEpochMilli(5_000).toString(), node.lastSeen)
        assertNull(node.advertCount)
    }

    @Test fun nodeDetailLooksUpKeyThenServerId() = runBlocking {
        val key = "d077b9ec24d44ad069c03348ff01d88d0a86e35cee85d8d8c0a9e1ffb73fdfe3"
        val id = "e28eb9e9-ab40-c112-32c5-50393ebb2359"
        val fake = FakeBeacon(mutableMapOf(
            "nodes?pubkey=$key" to Triple(200, body("node-by-pubkey.json").toString(), null),
            "nodes/$id" to Triple(200, body("node-detail.json").toString(), null),
        ))
        val node = checkNotNull(BeaconNodeReader(api(fake), info).nodeDetail(key.uppercase()))
        assertEquals(id, node.id)
        assertEquals(listOf("nodes?pubkey=$key", "nodes/$id"), fake.requests)
    }

    @Test fun missingNodeDoesNotScanOrFetchDetail() = runBlocking {
        val key = "a".repeat(64)
        val fake = FakeBeacon(mutableMapOf("nodes?pubkey=$key" to Triple(200, page(JsonArray(emptyList())), null)))
        assertNull(BeaconNodeReader(api(fake), info).nodeDetail(key))
        assertEquals(1, fake.requests.size)
    }

    @Test fun observerDetailLearnsKeyWithoutFanout() = runBlocking {
        val detail = body("observer-detail.json").jsonObject
        val id = detail.getValue("id").jsonPrimitive.content
        val key = detail.getValue("publicKey").jsonPrimitive.content
        val fake = FakeBeacon(mutableMapOf("observers/$id" to Triple(200, detail.toString(), null)))
        val reader = BeaconObserverReader(api(fake), info)
        assertNull("An unlearned key never scans observers", reader.observerDetailByKey(key))
        assertTrue(fake.requests.isEmpty())
        reader.observerDetail(id)
        assertEquals(id, reader.observerDetailByKey(key)?.id)
        assertEquals(listOf("observers/$id", "observers/$id"), fake.requests)
    }

    @Test fun observer404EvictsLearnedMappingAndLiveLearningIsSessionBound() = runBlocking {
        val id = "f6c9d02e-eee0-7882-1a89-9bc1dc6d1170"
        val key = "b".repeat(64)
        val fake = FakeBeacon()
        val client = api(fake)
        val reader = BeaconObserverReader(client, info)
        try {
            reader.learn(id, key, AnalyzerSourceSession().id)
            fail("Another session's event must be rejected")
        } catch (_: CancellationException) {}
        reader.learn(id, key, client.sourceSession.id)
        assertEquals(key, reader.learnedKey(id))
        assertNull(reader.observerDetailByKey(key)) // 404
        assertNull("A 404 forgets the mapping", reader.learnedKey(id))
    }

    @Test fun observerMappingKeepsUnknownsAndNeverInventsBattery() {
        val observer = protocolJson.decodeFromJsonElement(BeaconObserver.serializer(), body("observer-detail.json")).meshObserver()
        assertNull(observer.packetCount)
        assertNull("batteryLevel is a percentage, not millivolts", observer.batteryMv)
        assertEquals("f6c9d02e-eee0-7882-1a89-9bc1dc6d1170", observer.id)
    }

    @Test fun packetFixturePreservesOrderAndOpaqueCursor() = runBlocking {
        val fixture = body("packets-page.json")
        val fake = FakeBeacon(mutableMapOf("packets?limit=50&iatas=SAT" to Triple(200, fixture.toString(), null)))
        val result = BeaconPacketReader(api(fake), info).packetPage("SAT", 50, null, null)
        val hashes = fixture.jsonObject.getValue("items").jsonArray.map { it.jsonObject.getValue("packetHash").jsonPrimitive.content }
        assertEquals("Server order is kept, never re-sorted", hashes, result.items.map { it.hash })
        assertTrue(result.items.all { it.id == null && it.resolvedPath.isEmpty() })
        assertFalse("The cursor is opaque", checkNotNull(result.nextCursor).value.contains("1791664682240"))
    }

    @Test fun packetDetailKeepsRecordedObservationsAndOnlyConfirmedHops() = runBlocking {
        val fixture = body("packet-detail.json")
        val hash = fixture.jsonObject.getValue("packetHash").jsonPrimitive.content
        val fake = FakeBeacon(mutableMapOf("packets/$hash" to Triple(200, fixture.toString(), null)))
        val detail = BeaconPacketReader(api(fake), info).packetDetail(hash)
        assertNull("rawPayload is never exposed as raw_hex", detail.packet.rawHex)
        assertEquals(fixture.jsonObject.getValue("observations").jsonArray.size, detail.observations.size)
        assertEquals(
            BeaconResolvedHop("ambiguous", listOf(BeaconResolvedHop.Node("a".repeat(64)), BeaconResolvedHop.Node("b".repeat(64)))).confirmedKey,
            null,
        )
        assertNull(BeaconResolvedHop("high", listOf(BeaconResolvedHop.Node("abcd"))).confirmedKey)
        assertEquals("c".repeat(64), BeaconResolvedHop("high", listOf(BeaconResolvedHop.Node("C".repeat(64)))).confirmedKey)
    }

    @Test fun regionCatalogUsesFixturesAndCachesMemberDetails() = runBlocking {
        val summaries = body("regions.json").jsonArray
        val routes = mutableMapOf<String, Triple<Int, String, String?>>(
            "iatas" to Triple(200, body("iatas.json").toString(), null),
            "regions" to Triple(200, summaries.toString(), null),
        )
        for (summary in summaries) {
            val detail = buildJsonObject { summary.jsonObject.forEach { (k, v) -> put(k, v) }; put("iatas", buildJsonArray { add("SAT") }) }
            routes["regions/${summary.jsonObject.getValue("id").jsonPrimitive.int}"] = Triple(200, detail.toString(), null)
        }
        val fake = FakeBeacon(routes)
        val reader = BeaconRegionReader(api(fake), info)
        val catalog = reader.catalog()
        assertEquals(body("iatas.json").jsonArray.size, catalog.names.size)
        assertTrue(catalog.coordinates.isNotEmpty())
        val firstCount = fake.requests.size
        reader.catalog()
        assertEquals("Cached for five minutes", firstCount, fake.requests.size)
    }

    @Test fun backendRejectsUnsupportedEndpointsWithoutRequests() = runBlocking {
        val fake = FakeBeacon()
        val backend = BeaconBackend(api(fake), info)
        assertTrue(backend.capabilities.isEmpty())
        val calls: List<Pair<BackendCapability, suspend () -> Unit>> = listOf(
            BackendCapability.SERVER_CHANNELS to { backend.channels(AnalyzerSelection(host, null)); Unit },
            BackendCapability.CHANNEL_DECRYPT_ON_PHONE to { backend.channelPackets(AnalyzerSelection(host, null)); Unit },
            BackendCapability.OBSERVER_ANALYTICS to { backend.observerAnalytics(host, "x"); Unit },
            BackendCapability.NODE_HEALTH to { backend.nodeHealth(host, "x"); Unit },
            BackendCapability.NODE_ANALYTICS to { backend.nodeAnalytics(host, "x", 7); Unit },
        )
        for ((capability, call) in calls) {
            try { call(); fail() } catch (e: BeaconUnsupportedException) {
                assertEquals(capability.beaconExplanation, e.message)
            }
        }
        assertTrue(fake.requests.isEmpty())
    }

    @Test fun positiveBeaconResolutionNeverFallsBackToCoreScope() = runBlocking {
        val fake = FakeBeacon(mutableMapOf("info" to Triple(200, """{"serverVersion":"2.0.2"}""", null)))
        var coreScopeChecks = 0
        val router = AnalyzerSourceRouter(client(fake), BeaconServerCatalog(InMemoryBeaconServerStore())) { _, _ -> coreScopeChecks++ }
        router.setHost(host)
        router.resolve(knownCoreScope = false)
        assertFalse(router.state.value.ready)
        assertTrue(router.state.value.error!!.contains("2.0.3 or newer"))
        assertEquals(0, coreScopeChecks)
        assertEquals(AnalyzerSourceRouter.Route.NotReady, router.route(host))
    }

    @Test fun onlyAnAbsentBeaconEndpointPermitsCoreScope() = runBlocking {
        var coreScopeChecks = 0
        val absent = AnalyzerSourceRouter(client(FakeBeacon()), BeaconServerCatalog(InMemoryBeaconServerStore())) { _, _ -> coreScopeChecks++ }
        absent.setHost(host)
        absent.resolve(knownCoreScope = false)
        assertTrue(absent.state.value.ready)
        assertEquals(1, coreScopeChecks)
        assertEquals(AnalyzerSourceRouter.Route.CoreScope, absent.route(host))

        val failing = FakeBeacon(mutableMapOf("info" to Triple(503, "{}", null)))
        val flaky = AnalyzerSourceRouter(client(failing), BeaconServerCatalog(InMemoryBeaconServerStore())) { _, _ -> coreScopeChecks++ }
        flaky.setHost(host)
        flaky.resolve(knownCoreScope = false)
        assertFalse("A server error is not evidence of CoreScope", flaky.state.value.ready)
        assertEquals(1, coreScopeChecks)

        val registryBeacon = AnalyzerSourceRouter(client(FakeBeacon()), BeaconServerCatalog(InMemoryBeaconServerStore())) { _, _ -> coreScopeChecks++ }
        registryBeacon.setHost(host)
        registryBeacon.resolve(knownCoreScope = false, requiresBeacon = true)
        assertFalse("A listed Beacon source never becomes CoreScope", registryBeacon.state.value.ready)
        assertEquals(1, coreScopeChecks)
    }

    @Test fun verifiedBeaconIsRememberedAndKnownCoreScopeSkipsProbing() = runBlocking {
        val store = InMemoryBeaconServerStore()
        val fake = FakeBeacon(mutableMapOf("info" to Triple(200, """{"serverVersion":"2.0.3"}""", null)))
        val router = AnalyzerSourceRouter(client(fake), BeaconServerCatalog(store)) { _, _ -> fail("Not CoreScope") }
        router.setHost(host)
        val firstSession = router.state.value.session
        router.resolve(knownCoreScope = false)
        assertTrue(router.state.value.ready)
        assertTrue(router.route(host) is AnalyzerSourceRouter.Route.Beacon)
        assertFalse("Selecting Beacon starts a new source session", firstSession.isCurrent)

        val later = AnalyzerSourceRouter(client(fake), BeaconServerCatalog(store)) { _, _ -> fail("Not CoreScope") }
        later.setHost(host)
        later.resolve(knownCoreScope = true)
        assertTrue("A remembered Beacon wins over a CoreScope listing", later.route(host) is AnalyzerSourceRouter.Route.Beacon)
        assertEquals(1, fake.requests.size)

        val known = AnalyzerSourceRouter(client(fake), BeaconServerCatalog(InMemoryBeaconServerStore())) { _, _ -> fail("No probe") }
        known.setHost("analyzer.meshtexas.org")
        known.resolve(knownCoreScope = true)
        assertTrue(known.state.value.ready)
        assertEquals(1, fake.requests.size)
    }

    @Test fun nothingIsRoutedBeforeASourceIsSelected() = runBlocking {
        val router = AnalyzerSourceRouter(client(FakeBeacon()), BeaconServerCatalog(InMemoryBeaconServerStore()))
        assertEquals(AnalyzerSourceRouter.Route.NotReady, router.route(host))
        val readiness = router.liveReadiness.first()
        assertFalse(readiness.ready)
        assertNotNull("An unset host still gates sessions", readiness.host)
    }

    @Test fun switchingHostInvalidatesTheOldSession() {
        val router = AnalyzerSourceRouter(client(FakeBeacon()), BeaconServerCatalog(InMemoryBeaconServerStore()))
        router.setHost(host)
        val session = router.state.value.session
        router.setHost("other.example")
        assertFalse(session.isCurrent)
        assertEquals(AnalyzerSourceRouter.Route.CoreScope, router.route(host))
    }
}
