package org.nodescope.android

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.nodescope.android.feature.coverage.*

class CoverageTerrainCatalogLoaderTest {
    private val body = """{"version":1,"dataset":"test","revision":"1","vertical_datum":"test","attribution":"test","source_type":"synthetic","side":2,"tiles":[{"tile":"N00E000","sha256":"${"a".repeat(64)}","bytes":100,"url":"https://example.com/tile"}]}"""
    private fun client(started: CountDownLatch = CountDownLatch(1)) = OkHttpClient.Builder().addInterceptor { chain ->
        val path = chain.request().url.encodedPath
        if (path == "/stall") {
            started.countDown()
            while (!chain.call().isCanceled()) Thread.sleep(5)
            throw IOException("Cancelled")
        }
        var payload = body
        val response = Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("test")
        when (path) {
            "/oversized" -> payload = " ".repeat(CoverageTerrainCatalog.MAXIMUM_BYTES + 1)
            "/declared-large" -> response.header("Content-Length", "${CoverageTerrainCatalog.MAXIMUM_BYTES + 1}")
            "/length" -> response.header("Content-Length", "1")
            "/encoding" -> response.header("Content-Encoding", "gzip")
            "/missing" -> response.code(404)
            "/redirect" -> response.code(302).header("Location", "https://example.invalid/other")
            "/invalid" -> payload = "{}"
            else -> response.header("Content-Length", payload.toByteArray().size.toString())
        }
        response.body(payload.toResponseBody("application/json".toMediaType())).build()
    }.build()

    @Test fun fetchAndResolve() = runBlocking {
        val transport = client()
        try {
            val catalog = CoverageTerrainCatalogLoader.load("https://example.invalid/valid", client = transport)
            assertEquals(listOf("N00E000"), catalog.resolve(CoverageTerrainFootprint.make(0.5, 0.5, 1000.0)).map { it.tile })
        } finally { transport.dispatcher.executorService.shutdown(); transport.connectionPool.evictAll() }
    }

    @Test fun rejectsBadResponses() = runBlocking {
        val transport = client()
        try {
            for (path in listOf("oversized", "declared-large", "length", "encoding", "missing", "redirect", "invalid")) {
                val error = runCatching { CoverageTerrainCatalogLoader.load("https://example.invalid/$path", client = transport) }.exceptionOrNull()
                assertNotNull(path, error)
                when (path) {
                    "oversized" -> assertEquals(CoverageTerrainCatalogState.OVERSIZED, (error as CoverageTerrainCatalogFailure).state)
                    "invalid" -> assertEquals(CoverageTerrainCatalogState.INVALID_DOCUMENT, (error as CoverageTerrainCatalogFailure).state)
                    "missing", "redirect" -> assertEquals(if (path == "missing") 404 else 302, (error as CoverageTerrainCatalogLoadFailure).status)
                    else -> assertEquals(CoverageTerrainCatalogLoadState.INVALID_RESPONSE, (error as CoverageTerrainCatalogLoadFailure).state)
                }
            }
        } finally { transport.dispatcher.executorService.shutdown(); transport.connectionPool.evictAll() }
    }

    @Test fun cancellationAndInvalidRequests() = runBlocking {
        val started = CountDownLatch(1)
        val transport = client(started)
        try {
            val job = async(Dispatchers.Default) { CoverageTerrainCatalogLoader.load("https://example.invalid/stall", client = transport) }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            job.cancelAndJoin()
            assertTrue(job.isCancelled)
            for (url in listOf("http://example.com/catalog", "https://user@example.com/catalog", "https://example.com/catalog#fragment")) {
                val error = runCatching { CoverageTerrainCatalogLoader.load(url, client = transport) }.exceptionOrNull()
                assertEquals(CoverageTerrainCatalogLoadState.INVALID_REQUEST, (error as CoverageTerrainCatalogLoadFailure).state)
            }
        } finally { transport.dispatcher.executorService.shutdown(); transport.connectionPool.evictAll() }
    }
}
