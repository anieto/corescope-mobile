package org.nodescope.android

import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import okhttp3.mockwebserver.*
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import org.nodescope.android.feature.coverage.*

class CoverageTerrainDownloaderTest {
    private val payload = ByteArray(150000) { (it % 251).toByte() }
    private fun descriptor(server: MockWebServer) = CoverageTerrainDescriptor(
        "test", "1", "N00E000", MessageDigest.getInstance("SHA-256").digest(payload)
            .joinToString("") { "%02x".format(it.toInt() and 255) }, payload.size.toLong(), 2,
        server.url("/tile").toString(), "test", "test")

    @Test fun verifiedDownloadAndBudgetReuse() = runBlocking {
        val root = Files.createTempDirectory("terrain-download").toFile()
        try { MockWebServer().use { server ->
            server.start()
            val spec = descriptor(server)
            val budget = CoverageTerrainDownloadBudget(spec.packedBytes * 2 + 1)
            repeat(2) {
                server.enqueue(MockResponse().setBody(Buffer().write(payload)))
                CoverageTerrainDownloader.download(spec, root, budget).use { assertArrayEquals(payload, it.file.readBytes()) }
            }
            assertEquals(spec.packedBytes * 2, budget.receivedBytes)
            try { CoverageTerrainDownloader.download(spec, root, budget); fail("Budget accepted") }
            catch (error: CoverageTerrainDownloadFailure) { assertEquals(CoverageTerrainDownloadState.DOWNLOAD_LIMIT, error.state) }
            assertEquals(2, server.requestCount)
            assertTrue(root.listFiles()!!.isEmpty())
        } } finally { root.deleteRecursively() }
    }

    @Test fun invalidResponsesLeaveNoArtifact() = runBlocking {
        val root = Files.createTempDirectory("terrain-download").toFile()
        try { MockWebServer().use { server ->
            server.start()
            val spec = descriptor(server)
            val cases = listOf(
                MockResponse().setChunkedBody(Buffer().write(payload.copyOf(payload.size - 1)), 8192) to spec.packedBytes - 1,
                MockResponse().setChunkedBody(Buffer().write(payload + byteArrayOf(0)), 8192) to spec.packedBytes + 1,
                MockResponse().setBody(Buffer().write(payload.copyOf().also { it[0] = -1 })) to spec.packedBytes,
                MockResponse().setBody(Buffer().write(payload)).setHeader("Content-Encoding", "br") to 0L,
                MockResponse().setBody("x") to 0L,
                MockResponse().setResponseCode(404) to 0L,
                MockResponse().setResponseCode(302).setHeader("Location", server.url("/other")) to 0L,
            )
            for ((response, received) in cases) {
                server.enqueue(response)
                val budget = CoverageTerrainDownloadBudget(spec.packedBytes + 1)
                try { CoverageTerrainDownloader.download(spec, root, budget); fail("Invalid response accepted") }
                catch (error: CoverageTerrainDownloadFailure) {
                    assertTrue(error.state in listOf(CoverageTerrainDownloadState.CORRUPT, CoverageTerrainDownloadState.HTTP_STATUS))
                }
                assertEquals(received, budget.receivedBytes)
                assertTrue(root.listFiles()!!.isEmpty())
            }
            assertEquals(cases.size, server.requestCount)
        } } finally { root.deleteRecursively() }
    }

    @Test fun deadlineStopsStalledResponse() = runBlocking {
        val root = Files.createTempDirectory("terrain-download").toFile()
        try { MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val spec = descriptor(server)
            val budget = CoverageTerrainDownloadBudget(spec.packedBytes + 1)
            try {
                CoverageTerrainDownloader.download(spec, root, budget, attemptTimeoutMillis = 100)
                fail("Stalled response succeeded")
            } catch (_: java.io.IOException) {}
            assertEquals(0L, budget.receivedBytes)
            assertTrue(root.listFiles()!!.isEmpty())
        } } finally { root.deleteRecursively() }
    }

    @Test fun cancellationStopsPendingRequest() = runBlocking {
        val root = Files.createTempDirectory("terrain-download").toFile()
        try { MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val spec = descriptor(server)
            val budget = CoverageTerrainDownloadBudget(spec.packedBytes + 1)
            val job = launch { CoverageTerrainDownloader.download(spec, root, budget).close() }
            withContext(Dispatchers.IO) { assertNotNull(server.takeRequest(5, TimeUnit.SECONDS)) }
            job.cancelAndJoin()
            assertTrue(root.listFiles()!!.isEmpty())
            assertEquals(0L, budget.receivedBytes)
        } } finally { root.deleteRecursively() }
    }
}

class CoverageTerrainPreparationTest {
    private val gzip = java.util.Base64.getDecoder().decode("H4sIAAAAAAAC/2Ng/P+vgYGBBQAP46E6CAAAAA==")
    private fun descriptor(server: MockWebServer, tile: String = "N00E000") = CoverageTerrainDescriptor(
        "prepare", "1", tile, MessageDigest.getInstance("SHA-256").digest(gzip)
            .joinToString("") { "%02x".format(it.toInt() and 255) }, gzip.size.toLong(), 2,
        server.url("/tile").toString(), "test", "test")

    @Test fun downloadDecodePublishAndOfflineReopen() = runBlocking {
        val root = Files.createTempDirectory("terrain-prepare").toFile()
        try { MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody(Buffer().write(gzip)))
            val spec = descriptor(server)
            val cacheRoot = java.io.File(root, "cache")
            val budget = CoverageTerrainDownloadBudget(1000)
            CoverageTerrainDiskCache(cacheRoot, 100000).use { cache ->
                val readers = cache.prepare(listOf(spec, spec), root, budget)
                assertEquals(1, readers.size)
                assertArrayEquals(shortArrayOf(1, -2, -32768, 4), readers.getValue(spec.cacheKey).read(0, 4))
                assertEquals(spec.packedBytes, budget.receivedBytes)
                assertTrue(cache.inventory.all { it.pinned })
                readers.values.forEach { it.close() }
            }
            CoverageTerrainDiskCache(cacheRoot, 100000).use { cache ->
                val readers = cache.prepare(listOf(spec), root, CoverageTerrainDownloadBudget(0), offline = true,
                    download = { _, _, _ -> error("Offline network access") })
                assertArrayEquals(shortArrayOf(1), readers.getValue(spec.cacheKey).read(0, 1))
                readers.values.forEach { it.close() }
            }
            assertEquals(1, server.requestCount)
            assertEquals(listOf("cache"), root.list()!!.toList())
        } } finally { root.deleteRecursively() }
    }

    @Test fun rejectedPreflightDoesNotDownload() = runBlocking {
        val root = Files.createTempDirectory("terrain-prepare").toFile()
        try { MockWebServer().use { server ->
            server.start()
            val spec = descriptor(server)
            val peak = 4096 + 8 + 8192 + spec.packedBytes + 8 + 8192
            CoverageTerrainDiskCache(java.io.File(root, "cache"), peak - 1).use { cache ->
                try {
                    cache.prepare(listOf(spec), root, CoverageTerrainDownloadBudget(1000),
                        download = { _, _, _ -> error("Quota rejection downloaded") })
                    fail("Quota accepted")
                } catch (error: CoverageTerrainCacheFailure) { assertEquals(CoverageTerrainCacheState.QUOTA, error.state) }
                assertTrue(cache.inventory.isEmpty())
            }
        } } finally { root.deleteRecursively() }
    }

    @Test fun partialFailureReleasesPinsAndKeepsCompletedTileReusable() = runBlocking {
        val root = Files.createTempDirectory("terrain-prepare").toFile()
        try { MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody(Buffer().write(gzip)))
            server.enqueue(MockResponse().setResponseCode(404))
            val first = descriptor(server)
            val second = descriptor(server, "N00E001")
            CoverageTerrainDiskCache(java.io.File(root, "cache"), 100000).use { cache ->
                try { cache.prepare(listOf(first, second), root, CoverageTerrainDownloadBudget(1000)); fail("Partial set escaped") }
                catch (error: CoverageTerrainDownloadFailure) { assertEquals(404, error.status) }
                assertEquals(1, cache.inventory.size)
                assertTrue(cache.inventory.none { it.pinned })
                cache.acquire(first).close()
            }
            assertEquals(2, server.requestCount)
            assertEquals(listOf("cache"), root.list()!!.toList())
        } } finally { root.deleteRecursively() }
    }

    @Test fun retryPolicyAndCancellationReleaseOwnership() = runBlocking {
        val root = Files.createTempDirectory("terrain-prepare").toFile()
        try { MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setResponseCode(503))
            server.enqueue(MockResponse().setBody(Buffer().write(gzip)))
            val spec = descriptor(server)
            CoverageTerrainDiskCache(java.io.File(root, "cache"), 100000).use { cache ->
                val readers = cache.prepare(listOf(spec), root, CoverageTerrainDownloadBudget(1000))
                readers.values.forEach { it.close() }
                try {
                    cache.prepare(listOf(spec, descriptor(server, "N00E001")), root, CoverageTerrainDownloadBudget(1000),
                        download = { _, _, _ -> throw CancellationException() })
                    fail("Cancellation accepted")
                } catch (_: CancellationException) {}
                assertTrue(cache.inventory.none { it.pinned })
            }
            assertEquals(2, server.requestCount)
        } } finally { root.deleteRecursively() }
    }
}
