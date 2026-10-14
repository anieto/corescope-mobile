package org.nodescope.android

import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.nodescope.android.core.model.AnalyzerSelection
import org.nodescope.android.core.network.*

class AnalyzerBackendTest {
    @Test fun unsupportedFeaturesDoNotReachTheNetwork() = runBlocking {
        val client = OkHttpClient.Builder().addInterceptor { error("Unexpected network request") }.build()
        val backend = AnalyzerBackendFactory.create(client)
        val browse = CapabilityBrowseRepository(backend) { emptySet() }
        try {
            val requests: List<suspend () -> Unit> = listOf(
                { browse.channels(AnalyzerSelection("fixture.example", null)); Unit },
                { browse.channelMessages("fixture.example", "old-favorite"); Unit },
                { browse.channelPackets(AnalyzerSelection("fixture.example", null)); Unit },
                { browse.observerAnalytics("fixture.example", "observer"); Unit },
                { browse.nodeHealth("fixture.example", "node"); Unit },
                { browse.nodePaths("fixture.example", "node"); Unit },
                { browse.nodeReach("fixture.example", "node"); Unit },
                { browse.neighbors("fixture.example", "node"); Unit },
                { browse.nodeAnalytics("fixture.example", "node", 7); Unit },
            )
            for (request in requests) {
                try {
                    request()
                    fail("Unsupported feature must fail")
                } catch (error: BeaconUnsupportedException) {
                    assertEquals(error.capability.beaconExplanation, error.message)
                }
            }
        } finally {
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }
    /** CoreScope neighbors are its reach links: same request, no invented signal or last-heard values. */
    @Test fun neutralNeighborsReuseCoreScopeReachWithoutInventingSignal() = runBlocking {
        val key = "ab".repeat(32)
        val requests = mutableListOf<String>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests += chain.request().url.encodedPath
            val body = """{"window":{"days":7},"importance":{"neighbor_degree":1,"bidirectional_links":1,"direct_observers":0},
                "direct_observers":[],"links":[{"pubkey":"$key","name":"Neighbor","role":"repeater","lat":30,"lon":-98,
                "we_hear":2,"they_hear":3,"bidir":true,"distance_km":4.5}]}"""
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        try {
            val result = AnalyzerBackendFactory.create(client).neighbors("fixture.example", "key/neutral")
            assertEquals(listOf("/api/nodes/key%2Fneutral/reach"), requests)
            val neighbor = result.neighbors.single()
            assertEquals(key, neighbor.identity.linkablePublicKey)
            assertEquals(org.nodescope.android.core.model.NeighborDirection.BIDIRECTIONAL, neighbor.direction)
            assertNull(neighbor.signal.snr)
            assertNull(neighbor.signal.rssi)
            assertNull(neighbor.observationCount)
            assertNull(neighbor.lastHeard)
            assertTrue(neighbor.hasValidCoordinate)
        } finally {
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }

    @Test fun factoryPreservesBrowseLiveAndSnapshotContracts() = runBlocking {
        val requests = java.util.Collections.synchronizedList(mutableListOf<String>())
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val url = chain.request().url
            requests.add(url.encodedPath + (url.encodedQuery?.let { "?" + it } ?: ""))
            val body = when (url.encodedPath) {
                "/api/observers" -> """{"observers":[{"id":"observer","name":"North"}]}"""
                "/api/nodes" -> """{"nodes":[],"total":0}"""
                "/api/config/regions" -> """{"SAT":"San Antonio"}"""
                "/api/iata-coords" -> """{"coords":{}}"""
                "/api/config/map" -> """{"center":[29.4,-98.5],"zoom":8}"""
                "/api/packets" -> """{"packets":[],"total":0}"""
                "/api/channels/%23Public/messages" -> """{"messages":[],"total":0}"""
                else -> error("Unexpected request: $url")
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val directory = Files.createTempDirectory("backend-test").toFile()
        try {
            val backend = AnalyzerBackendFactory.create(client, ResponseCache(directory))
            assertEquals(BackendCapability.entries.toSet(), backend.capabilities)
            assertEquals("North", backend.observers("fixture.example").single().name)
            val requestCount = requests.size
            assertEquals("North", backend.saved(ResponseCache.STALE_LIFETIME) {
                observers("fixture.example")
            }?.value?.single()?.name)
            assertEquals(requestCount, requests.size)
            assertTrue(backend.channelMessages("fixture.example", "#Public").isEmpty())
            val selection = AnalyzerSelection("fixture.example", "SAT")
            assertTrue(backend.liveFeed.recent(selection).isEmpty())
            assertEquals("North", backend.liveFeed.observers(selection.host).single().name)
            val snapshot = backend.load(selection)
            assertEquals("San Antonio", snapshot.regions["SAT"])
            assertTrue(snapshot.nodes.isEmpty())
            assertTrue(requests.contains("/api/nodes?limit=5000&region=SAT"))
            assertTrue(requests.contains("/api/packets?limit=200&region=SAT"))
            assertTrue(requests.contains("/api/channels/%23Public/messages"))
            val beforeRestore = requests.size
            assertEquals(snapshot, backend.saved(selection)?.value)
            assertEquals(beforeRestore, requests.size)
        } finally {
            directory.deleteRecursively()
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }
}
