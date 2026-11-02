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
