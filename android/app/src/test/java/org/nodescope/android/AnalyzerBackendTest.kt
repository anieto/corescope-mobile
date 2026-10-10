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
