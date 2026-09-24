package org.nodescope.android

import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.nodescope.android.core.model.AnalyzerSelection
import org.nodescope.android.core.network.*

@OptIn(ExperimentalCoroutinesApi::class)
class ResponseCacheTest {
    private fun directory() = Files.createTempDirectory("responses").toFile().apply { deleteOnExit() }

    @Test fun savedBodiesExpireAndAreKeyedByFullUrl() = runBlocking {
        var now = 1_000L
        val cache = ResponseCache(directory()) { now }
        val all = endpoint("example.org", "api", "nodes")
        val dfw = all.newBuilder().addQueryParameter("region", "DFW").build()
        cache.write(all, "{\"nodes\":[]}")
        assertEquals(Cached("{\"nodes\":[]}", 1_000L), cache.read(all, 60_000))
        assertNull(cache.read(dfw, 60_000)) // another region is a different response
        assertNull(endpoint("other.example", "api", "nodes").let { cache.read(it, 60_000) })
        now += 60_001
        assertNull(cache.read(all, 60_000))
    }

    /** Answers every request with [body] (the repository only accepts HTTPS hosts). */
    private fun answering(body: String, counter: IntArray = IntArray(1)) = OkHttpClient.Builder().addInterceptor { chain ->
        counter[0]++
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .body(body.toResponseBody("application/json".toMediaType())).build()
    }.build()

    @Test fun downloadsAreSavedAndRestoredWithoutTheNetwork() = runBlocking {
        val cache = ResponseCache(directory())
        val requests = IntArray(1)
        val repository = HttpBrowseRepository(answering("{\"observers\":[{\"id\":\"obs1\",\"name\":\"North\"}]}", requests), cache)
        repository.observers("fixture.example")
        val restored = repository.saved(ResponseCache.STALE_LIFETIME) { observers("fixture.example") }
        assertEquals(listOf("North"), restored?.value?.map { it.name })
        // Nothing saved for this observer's analytics, so nothing is restored (and nothing is fetched).
        assertNull(repository.saved(ResponseCache.DETAIL_LIFETIME) { observerAnalytics("fixture.example", "obs1") })
        assertEquals(1, requests[0])
    }

    @Test fun channelsAreNeverSavedToDisk() = runBlocking {
        val cache = ResponseCache(directory())
        val selection = AnalyzerSelection("fixture.example", null)
        val repository = HttpBrowseRepository(answering("{\"channels\":[]}"), cache)
        repository.channels(selection)
        assertNull(repository.saved(ResponseCache.STALE_LIFETIME) { channels(selection) })
    }

    @Test fun loaderShowsSavedDataThenReplacesItWithTheDownload() = runTest {
        val download = CompletableDeferred<String>()
        val loader = KeyedLoader(backgroundScope, 60_000, { currentTime }, restore = { key: String -> Cached("saved $key", -120_000L) }) { _ ->
            download.await()
        }
        loader.load("a"); runCurrent()
        assertEquals("saved a", loader.state.value.value)
        assertTrue(loader.state.value.loading)
        assertEquals(-120_000L, loader.state.value.updatedAt)
        download.complete("fresh"); runCurrent()
        assertEquals("fresh", loader.state.value.value)
        assertFalse(loader.state.value.loading)
    }

    @Test fun offlineLoaderKeepsSavedDataWithAnError() = runTest {
        val loader = KeyedLoader(backgroundScope, 60_000, { currentTime }, restore = { _: String -> Cached("saved", -120_000L) }) { _ ->
            throw java.io.IOException("offline")
        }
        loader.load("a"); runCurrent()
        assertEquals("saved", loader.state.value.value)
        assertEquals("offline", loader.state.value.error)
        assertEquals(-120_000L, loader.state.value.updatedAt)
    }

    @Test fun recentSavedDataSkipsTheDownload() = runTest {
        var calls = 0
        val loader = KeyedLoader(backgroundScope, 60_000, { currentTime }, restore = { _: String -> Cached("saved", 0L) }) { _ -> calls++; "fresh" }
        loader.load("a"); runCurrent()
        assertEquals("saved", loader.state.value.value)
        assertFalse(loader.state.value.loading)
        assertEquals(0, calls)
        loader.load("a", force = true); runCurrent() // pull to refresh still downloads
        assertEquals("fresh", loader.state.value.value)
    }
}
