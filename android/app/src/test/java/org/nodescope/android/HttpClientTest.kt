package org.nodescope.android

import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.nodescope.android.core.network.*
import java.util.concurrent.TimeUnit

class HttpClientTest {
    @Test fun returnsBodyAndPreservesHttpFailure() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{\"ok\":true}"))
            server.enqueue(MockResponse().setResponseCode(404))
            val client = OkHttpClient()
            assertEquals("{\"ok\":true}", client.read(server.url("/test")))
            try { client.read(server.url("/missing")); fail("Expected HTTP error") }
            catch (e: HttpFailure) { assertEquals(404, e.status) }
        }
    }
    @Test fun cancellationClosesInFlightCall() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("delayed").setBodyDelay(2, TimeUnit.SECONDS))
            val client = OkHttpClient()
            val request = async(Dispatchers.Default) { client.read(server.url("/slow")) }
            assertNotNull(withContext(Dispatchers.IO) { server.takeRequest(2, TimeUnit.SECONDS) })
            request.cancelAndJoin()
            withTimeout(2_000) {
                while (client.dispatcher.runningCallsCount() != 0) delay(10)
            }
            assertTrue(request.isCancelled)
        }
    }
}
