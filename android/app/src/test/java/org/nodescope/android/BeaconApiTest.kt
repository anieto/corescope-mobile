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

    private val nodeKey = "d077b9ec24d44ad069c03348ff01d88d0a86e35cee85d8d8c0a9e1ffb73fdfe3"
    private val nodeId = "e28eb9e9-ab40-c112-32c5-50393ebb2359"

    @Test fun nodeNeighborsResolveFullKeyAndPreserveUnknownDirection() = runBlocking {
        val fake = FakeBeacon(mutableMapOf(
            "nodes?pubkey=$nodeKey" to Triple(200, body("node-by-pubkey.json").toString(), null),
            "nodes/$nodeId/neighbors" to Triple(200, body("node-neighbors.json").toString(), null),
        ))
        val result = checkNotNull(BeaconNodeReader(api(fake), info).neighbors(nodeKey.uppercase()))
        val neighbor = result.neighbors.first()
        assertEquals(org.nodescope.android.core.model.AnalyticsCompleteness.COMPLETE, result.completeness)
        assertNotNull(neighbor.identity.linkablePublicKey)
        assertEquals(8, neighbor.identity.displayPrefix?.length)
        assertEquals(org.nodescope.android.core.model.NeighborDirection.UNKNOWN, neighbor.direction)
        assertEquals(-5.75, neighbor.signal.snr!!, 0.0)
        assertNull(neighbor.signal.rssi)
        assertEquals(2, neighbor.observationCount)
        assertEquals(listOf("nodes?pubkey=$nodeKey", "nodes/$nodeId/neighbors"), fake.requests)
    }

    @Test fun nodeObservationHistoryUsesBoundedPagesAndObservationSemantics() = runBlocking {
        val fake = FakeBeacon(mutableMapOf(
            "nodes?pubkey=$nodeKey" to Triple(200, body("node-by-pubkey.json").toString(), null),
            "nodes/$nodeId/observations?limit=200" to Triple(200, body("node-observations.json").toString(), null),
        ))
        val history = checkNotNull(BeaconNodeReader(api(fake), info).observationHistory(nodeKey, since = 0))
        assertTrue(history.isComplete)
        assertEquals(2, history.requestCount)
        assertEquals(8, history.observations.size)
        assertTrue(history.observations.all { it.payloadType == 4 && it.hopCount == 0 })
        assertEquals(1791666758828L, history.observations.first().heardAt)
    }

    @Test fun observationHistoryStopsAtTheRequestBudgetAndRejectsRepeatedCursors() = runBlocking {
        val items = body("node-observations.json").jsonObject.getValue("items")
        val first = items.jsonArray.take(4).let(::JsonArray)
        val second = items.jsonArray.drop(4).let(::JsonArray)
        val budget = FakeBeacon(mutableMapOf(
            "nodes?pubkey=$nodeKey" to Triple(200, body("node-by-pubkey.json").toString(), null),
            "nodes/$nodeId/observations?limit=200" to Triple(200, page(first, true, JsonPrimitive("c1")), null),
            "nodes/$nodeId/observations?limit=200&cursor=c1" to Triple(200, page(second, true, JsonPrimitive("c2")), null),
        ))
        val partial = checkNotNull(BeaconNodeReader(api(budget), info).observationHistory(nodeKey, since = 0, maximumPages = 2))
        assertFalse(partial.isComplete)
        assertEquals(8, partial.observations.size)
        assertEquals(3, budget.requests.size)

        val repeating = FakeBeacon(mutableMapOf(
            "nodes?pubkey=$nodeKey" to Triple(200, body("node-by-pubkey.json").toString(), null),
            "nodes/$nodeId/observations?limit=200" to Triple(200, page(first, true, JsonPrimitive("c1")), null),
            "nodes/$nodeId/observations?limit=200&cursor=c1" to Triple(200, page(second, true, JsonPrimitive("c1")), null),
        ))
        assertThrows(BeaconApiException.MalformedPage::class.java) {
            runBlocking { BeaconNodeReader(api(repeating), info).observationHistory(nodeKey, since = 0) }
        }
        Unit
    }

    /** Pages follow observation ids, so delayed ingestion inverts timestamps; results are sorted. */
    @Test fun observationHistoryAcceptsDelayedIngestionTimestamps() = runBlocking {
        val items = body("node-observations.json").jsonObject.getValue("items").jsonArray.reversed().let(::JsonArray)
        val fake = FakeBeacon(mutableMapOf(
            "nodes?pubkey=$nodeKey" to Triple(200, body("node-by-pubkey.json").toString(), null),
            "nodes/$nodeId/observations?limit=200" to Triple(200, page(items), null),
        ))
        val history = checkNotNull(BeaconNodeReader(api(fake), info).observationHistory(nodeKey, since = 0))
        val times = history.observations.map { it.heardAt }
        assertEquals(times.sortedDescending(), times)
        assertEquals(8, times.size)
    }

    @Test fun beaconBackendDerivesNodeAnalyticsWithoutPacketFanout() = runBlocking {
        val fake = FakeBeacon(mutableMapOf(
            "nodes?pubkey=$nodeKey" to Triple(200, body("node-by-pubkey.json").toString(), null),
            "nodes/$nodeId/observations?limit=200" to Triple(200, body("node-observations.json").toString(), null),
        ))
        val analytics = BeaconBackend(api(fake), info).nodeAnalytics(host, nodeKey, 30)
        val context = checkNotNull(analytics.context)
        assertEquals(org.nodescope.android.core.model.AnalyticsCountUnit.OBSERVATIONS, context.countUnit)
        assertEquals(org.nodescope.android.core.model.AnalyticsCompleteness.PARTIAL, context.completeness)
        assertEquals(7, analytics.timeRange?.days)
        val stats = checkNotNull(analytics.computedStats)
        assertNull(stats.availabilityPct)
        assertNull(stats.signalGrade)
        assertNull(stats.uniqueObservers)
        assertNull(stats.longestSilenceMs)
        assertEquals(4, analytics.packetTypeBreakdown.first().payloadType)
        assertEquals("0", analytics.hopDistribution.first().hops)
        assertTrue(analytics.observerCoverage.isEmpty() && analytics.peerInteractions.isEmpty())
        assertTrue("No packet-detail requests", fake.requests.none { it.startsWith("packets") })
        assertEquals(2, fake.requests.size)
    }

    /** Same fixture, same aggregates as iOS: one hourly bucket per hour, observations not packets. */
    @Test fun beaconNodeAnalyticsAggregatesMatchTheFixture() {
        val observations = body("node-observations.json").jsonObject.getValue("items").jsonArray.map {
            protocolJson.decodeFromJsonElement(BeaconObservation.serializer(), it)
        }
        val node = protocolJson.decodeFromJsonElement(BeaconNode.serializer(), body("node-detail.json"))
        val to = observations.first().heardAt + 1_000
        val history = BeaconObservationHistory(node, observations, isComplete = true, requestCount = 2)
        val analytics = BeaconNodeAnalyticsBuilder.build(history, 1, to - 86_400_000, to)
        val stats = checkNotNull(analytics.computedStats)
        val inWindow = observations.filter { it.heardAt >= to - 86_400_000 }
        assertEquals(inWindow.size.toLong(), stats.totalPackets)
        assertEquals(inWindow.size.toLong(), analytics.activityTimeline.sumOf { it.count })
        assertEquals(inWindow.size.toLong(), analytics.uptimeHeatmap.sumOf { it.count })
        assertEquals(0.0, stats.relayPct!!, 0.0)
        assertEquals(inWindow.mapNotNull { it.snr }.average(), stats.snrMean!!, 1e-9)
        assertEquals(org.nodescope.android.core.model.AnalyticsCompleteness.COMPLETE, analytics.context?.completeness)
    }

    private val observerId = "9549bfd5-d6de-87b4-a186-c26cef90587c"
    private fun observerRoutes(activity: String, telemetry: String, id: String = observerId) = mutableMapOf<String, Triple<Int, String, String?>>(
        "observers/$id/activity?range=24h&interval=15m" to Triple(200, body(activity).toString(), null),
        "observers/$id/telemetry?range=24h&interval=1h" to Triple(200, body(telemetry).toString(), null),
    )

    @Test fun observerAnalyticsMapsActivityAndOptionalTelemetryInTwoRequests() = runBlocking {
        val fake = FakeBeacon(observerRoutes("observer-activity-24h.json", "observer-telemetry-24h.json"))
        val analytics = BeaconObserverAnalyticsReader(api(fake), BeaconServerInfo("2.0.5")).analytics(observerId)
        assertEquals(org.nodescope.android.core.model.AnalyticsCountUnit.OBSERVATIONS, analytics.context?.countUnit)
        assertEquals(org.nodescope.android.core.model.AnalyticsCompleteness.COMPLETE, analytics.context?.completeness)
        assertEquals(96, analytics.timeline.size)
        assertEquals(7_837L, analytics.packetTypes["11"])
        assertTrue(analytics.nodesTimeline.isEmpty() && analytics.snrDistribution.isEmpty() && analytics.recentPackets.isEmpty())
        val telemetry = checkNotNull(analytics.telemetry).first()
        assertEquals(4_221, telemetry.batteryMillivolts)
        assertEquals(-112.0, telemetry.noiseFloorDecibels!!, 0.0)
        assertEquals(2, fake.requests.size)
    }

    @Test fun observerAnalyticsPreservesEmptyActivityAndTelemetry() = runBlocking {
        val id = "00000000-0000-0000-0000-000000000002"
        val fake = FakeBeacon(observerRoutes("observer-activity-empty.json", "observer-telemetry-empty.json", id))
        val analytics = BeaconObserverAnalyticsReader(api(fake), info).analytics(id)
        assertTrue(analytics.timeline.isEmpty() && analytics.packetTypes.isEmpty())
        assertEquals(emptyList<Any>(), analytics.telemetry)
    }

    @Test fun observerAnalyticsRejectsANonUuidWithoutRequests() {
        val fake = FakeBeacon()
        assertThrows(BeaconApiException.InvalidResponse::class.java) {
            runBlocking { BeaconObserverAnalyticsReader(api(fake), info).analytics("observer") }
        }
        assertTrue(fake.requests.isEmpty())
    }

    @Test fun channelPagesUseOpaqueCursorAndSourceScopedIntegerIdentity() = runBlocking {
        val first = body("channels-keyknown-page-1.json")
        val cursor = first.jsonObject.getValue("nextPageCursor").jsonPrimitive.content
        val fake = FakeBeacon(mutableMapOf(
            "channels?limit=2&keyKnown=true" to Triple(200, first.toString(), null),
            "channels?limit=2&keyKnown=true&pageCursor=$cursor" to Triple(200, body("channels-keyknown-page-2.json").toString(), null),
            "channels?limit=2&keyKnown=true&pageCursor=${java.net.URLEncoder.encode(cursor, "UTF-8")}" to
                Triple(200, body("channels-keyknown-page-2.json").toString(), null),
        ))
        val channels = BeaconChannelReader(api(fake), info).channels(pageSize = 2)
        assertEquals(listOf(58, 1958, 372, 3662), channels.map { it.serverId })
        assertEquals(listOf("beacon:58", "beacon:1958", "beacon:372", "beacon:3662"), channels.map { it.id })
        assertTrue(channels.all { it.messageCount == null })
        assertEquals(2, fake.requests.size)
    }

    @Test fun channelMessagesMapRecordedServerDataWithoutInventingRouteMetadata() = runBlocking {
        val fake = FakeBeacon(mutableMapOf("channels/58/messages?limit=20" to Triple(200, body("channel-messages.json").toString(), null)))
        val response = BeaconChannelReader(api(fake), info).messages("beacon:58")
        val messages = response.messages
        assertEquals(4, messages.size)
        assertEquals(5, response.total)
        assertEquals(12150L, messages.first().packetId)
        assertEquals("Sender 049", messages.first().sender)
        assertEquals(1, messages.first().repeats)
        assertTrue(messages.all { it.observers.isEmpty() && it.hops == null && it.snr == null })
    }

    @Test fun channelMessagePagesMustSayWhereTheyContinue() {
        val missing = body("channel-messages.json").jsonObject.toMutableMap().apply { remove("nextCursor") }.let(::JsonObject)
        val fake = FakeBeacon(mutableMapOf("channels/58/messages?limit=20" to Triple(200, missing.toString(), null)))
        assertThrows(BeaconApiException.MalformedPage::class.java) {
            runBlocking { BeaconChannelReader(api(fake), info).messages("58") }
        }
    }

    @Test fun emptyBeaconChannelIsSuccessfulAndComplete() = runBlocking {
        val fake = FakeBeacon(mutableMapOf("channels/58/messages?limit=20" to Triple(200, """{"items":[],"hasMore":false}""", null)))
        val response = BeaconChannelReader(api(fake), info).messages("beacon:58")
        assertTrue(response.messages.isEmpty())
        assertEquals(0, response.total)
    }

    /** iOS keeps the cursor for each loaded offset, so "Load older messages" is one request. */
    @Test fun olderChannelMessagesContinueFromTheSavedCursor() = runBlocking {
        val first = body("channel-messages.json")
        val cursor = first.jsonObject.getValue("nextCursor").jsonPrimitive.long
        val older = buildJsonObject {
            put("items", first.jsonObject.getValue("items").jsonArray.map { item ->
                buildJsonObject { item.jsonObject.forEach { (k, v) -> put(k, if (k == "id") JsonPrimitive(v.jsonPrimitive.long - 1000) else v) } }
            }.let(::JsonArray))
            put("hasMore", false)
        }
        val fake = FakeBeacon(mutableMapOf(
            "channels/58/messages?limit=20" to Triple(200, first.toString(), null),
            "channels/58/messages?limit=20&cursor=$cursor" to Triple(200, older.toString(), null),
        ))
        val reader = BeaconChannelReader(api(fake), info)
        val newest = reader.messages("beacon:58")
        val page = reader.messages("beacon:58", newest.messages.size)
        assertEquals(4, page.messages.size)
        assertEquals(8, page.total) // nothing older remains
        assertEquals(listOf("channels/58/messages?limit=20", "channels/58/messages?limit=20&cursor=$cursor"), fake.requests)
    }

    @Test fun olderChannelMessagesRejectARepeatedContinuation() {
        val repeated = body("channel-messages.json")
        val cursor = repeated.jsonObject.getValue("nextCursor").jsonPrimitive.long
        val fake = FakeBeacon(mutableMapOf(
            "channels/58/messages?limit=20" to Triple(200, repeated.toString(), null),
            "channels/58/messages?limit=20&cursor=$cursor" to Triple(200, repeated.toString(), null),
        ))
        // No saved cursor for this offset: the bounded walk sees the same continuation twice.
        assertThrows(BeaconApiException.MalformedPage::class.java) {
            runBlocking { BeaconChannelReader(api(fake), info).messages("58", 4) }
        }
    }

    @Test fun beaconChannelResetRecoveryIsExactUniqueAndRestricted() {
        fun channel(name: String, id: Int, hashtag: Boolean, hash: String = "h") =
            org.nodescope.android.core.model.MeshChannel(hash, name, serverId = id, isHashtag = hashtag)
        val savedPublic = channel("Public", 58, false, "old")
        val replacement = channel("Public", 99, false, "new")
        assertEquals(99, savedPublic.beaconReplacement(listOf(replacement))?.serverId)
        assertNull("Ambiguous", savedPublic.beaconReplacement(listOf(replacement, replacement.copy(serverId = 100))))
        assertNull("Private names never re-match", channel("Private", 12, false).beaconReplacement(listOf(channel("Private", 13, false))))
        assertNull("Renamed or hash-only matches never count", channel("#mesh", 20, true, "collision")
            .beaconReplacement(listOf(channel("#renamed", 21, true, "collision"))))
        assertNull("CoreScope channels never recover", org.nodescope.android.core.model.MeshChannel("11", "Public")
            .beaconReplacement(listOf(replacement)))
    }

    @Test fun legacyCoreScopeChannelDecodingKeepsHashIdentity() {
        val channel = protocolJson.decodeFromString(org.nodescope.android.core.model.MeshChannel.serializer(),
            """{"hash":"11","name":"Public","lastMessage":null,"lastSender":null,"messageCount":4,"lastActivity":"2026-10-01T00:00:00Z"}""")
        assertEquals("11", channel.id)
        assertNull(channel.serverId)
        assertEquals(4, channel.messageCount)
    }

    private fun diagnosticRoutes(version: String = "2.0.5") = mutableMapOf<String, Triple<Int, String, String?>>(
        "info" to Triple(200, """{"serverVersion":"$version","minAppVersion":"99.0.0"}""", null),
        "nodes?limit=1" to Triple(200, """{"items":[],"hasMore":false}""", null),
        "observers?limit=1" to Triple(200, """{"items":[],"hasMore":false}""", null),
        "channels?limit=1&keyKnown=true" to Triple(200, """{"items":[],"hasMore":false}""", null),
    )

    @Test fun beaconDiagnosticsMakeFourBoundedRequestsAndNoFalseFailures() = runBlocking {
        val fake = FakeBeacon(diagnosticRoutes())
        val report = AnalyzerDiagnostics(client(fake)).runBeacon(host, BeaconBackend(api(fake), info))
        assertEquals("Beacon" to "2.0.5", report.backendType to report.serverVersion)
        assertEquals(DiagnosticLevel.SUCCESS, report.compatibilityLevel)
        assertEquals(AnalyzerCapability.entries, report.capabilities.map { it.capability })
        val byCapability = report.capabilities.associateBy { it.capability }
        assertEquals(DiagnosticLevel.WARNING, byCapability.getValue(AnalyzerCapability.MAP_CONFIGURATION).level)
        assertEquals(AnalyzerCapability.entries.size - 1, report.capabilities.count { it.level == DiagnosticLevel.SUCCESS })
        assertTrue(byCapability.getValue(AnalyzerCapability.NODE_ANALYTICS).detail.contains("family check passed"))
        assertEquals(setOf("info", "nodes?limit=1", "observers?limit=1", "channels?limit=1&keyKnown=true"), fake.requests.toSet())
        assertEquals(4, fake.requests.size)
    }

    @Test fun beaconDiagnosticsReportAFailedFamilyAsAWarning() = runBlocking {
        val fake = FakeBeacon(diagnosticRoutes().apply { remove("channels?limit=1&keyKnown=true") })
        val report = AnalyzerDiagnostics(client(fake)).runBeacon(host, BeaconBackend(api(fake), info))
        assertEquals(DiagnosticLevel.SUCCESS, report.compatibilityLevel)
        val byCapability = report.capabilities.associateBy { it.capability }
        for (capability in listOf(AnalyzerCapability.CHANNELS, AnalyzerCapability.CHANNEL_MESSAGES)) {
            assertEquals(DiagnosticLevel.WARNING, byCapability.getValue(capability).level)
        }
        assertEquals(DiagnosticLevel.SUCCESS, byCapability.getValue(AnalyzerCapability.NODES).level)
    }

    @Test fun unsupportedBeaconVersionSkipsFamilyChecks() = runBlocking {
        val fake = FakeBeacon(diagnosticRoutes("2.0.2"))
        val report = AnalyzerDiagnostics(client(fake)).runBeacon(host, BeaconBackend(api(fake), info))
        assertEquals(DiagnosticLevel.FAILURE, report.compatibilityLevel)
        assertEquals(listOf("info"), fake.requests)
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
        assertEquals(setOf(BackendCapability.NEIGHBORS, BackendCapability.NODE_ANALYTICS, BackendCapability.OBSERVER_ANALYTICS,
            BackendCapability.SERVER_CHANNELS), backend.capabilities)
        val calls: List<Pair<BackendCapability, suspend () -> Unit>> = listOf(
            BackendCapability.CHANNEL_DECRYPT_ON_PHONE to { backend.channelPackets(AnalyzerSelection(host, null)); Unit },
            BackendCapability.NODE_HEALTH to { backend.nodeHealth(host, "x"); Unit },
            BackendCapability.NODE_REACH to { backend.nodeReach(host, "x"); Unit },
            BackendCapability.NODE_PATHS to { backend.nodePaths(host, "x"); Unit },
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

    @Test fun phaseThreeFixturesPreserveCoverageAndPagingSemantics() {
        val rawActivity = body("observer-activity-24h.json").jsonObject
        assertEquals("15m", rawActivity.getValue("interval").jsonPrimitive.content)
        assertTrue(rawActivity.getValue("points").jsonArray.isNotEmpty())
        assertFalse(rawActivity.containsKey("rolledUntil"))
        assertFalse(rawActivity.containsKey("rawFrom"))

        val hourlyActivity = body("observer-activity-7d.json").jsonObject
        assertEquals("1h", hourlyActivity.getValue("interval").jsonPrimitive.content)
        assertTrue(hourlyActivity.getValue("rolledUntil").jsonPrimitive.long > 0)
        assertTrue(hourlyActivity.getValue("rawFrom").jsonPrimitive.long > 0)

        val telemetryPoint = body("observer-telemetry-24h.json").jsonObject
            .getValue("points").jsonArray.first().jsonObject
        for (field in listOf("batteryMv", "noiseFloorDb", "airtimeTxSecs", "airtimeRxSecs")) {
            assertTrue(field, telemetryPoint.getValue(field).jsonPrimitive.double.isFinite())
        }

        val firstPage = body("channels-keyknown-page-1.json").jsonObject
        val secondPage = body("channels-keyknown-page-2.json").jsonObject
        assertTrue(firstPage.getValue("nextPageCursor").jsonPrimitive.content.isNotBlank())
        val firstIds = firstPage.getValue("items").jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.int }.toSet()
        val secondIds = secondPage.getValue("items").jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.int }.toSet()
        assertTrue(firstIds.intersect(secondIds).isEmpty())
    }

    @Test fun phaseThreeEmptyHistoryFixturesAreSuccessfulAndComplete() {
        val observations = body("node-observations-empty.json").jsonObject
        assertTrue(observations.getValue("items").jsonArray.isEmpty())
        assertFalse(observations.getValue("hasMore").jsonPrimitive.boolean)

        val activity = body("observer-activity-empty.json").jsonObject
        assertTrue(activity.getValue("points").jsonArray.isEmpty())
        assertTrue(activity.getValue("payloadTypes").jsonArray.isEmpty())
        assertEquals(0, activity.getValue("summary").jsonObject
            .getValue("recordedPackets").jsonPrimitive.int)

        val telemetry = body("observer-telemetry-empty.json").jsonObject
        assertTrue(telemetry.getValue("points").jsonArray.isEmpty())
    }
}
