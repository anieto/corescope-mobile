package org.nodescope.android

import java.io.IOException
import java.nio.file.Files
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.nodescope.android.feature.coverage.*

class CoverageSkadiProviderTest {
    private val gzip = Base64.getDecoder().decode("H4sIAAAAAAAC/2Ng/P+vgYGBBQAP46E6CAAAAA==")
    private fun client(started: CountDownLatch = CountDownLatch(1)) = OkHttpClient.Builder().addInterceptor { chain ->
        val request = chain.request()
        val path = request.url.encodedPath
        if (path.contains("N04")) {
            started.countDown()
            while (!chain.call().isCanceled()) Thread.sleep(5)
            throw IOException("Cancelled")
        }
        val head = request.method == "HEAD"
        val status = when {
            path.contains("N01") && path.startsWith("/v2/") -> 404
            path.contains("N02") -> 404
            path.contains("N03") -> 503
            !head && request.header("If-Match") !in listOf("\"fixture\"", "\"wrong-response\"") -> 412
            else -> 200
        }
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message("test")
            .header("Content-Length", gzip.size.toString()).header("ETag", "\"fixture\"")
            .body((if (head) byteArrayOf() else gzip).toResponseBody()).build()
    }.build()

    @Test fun metadataIsVersionedAndUnpinned() {
        val tile = CoverageTerrainTile(-1, -180)
        val url = CoverageSkadiProvider.endpoint(tile)
        assertTrue(url.endsWith("/v2/skadi/S01/S01W180.hgt.gz"))
        val source = CoverageSkadiProvider.descriptor(tile, url, 200, "28", "\"first\"", null)
        val changed = CoverageSkadiProvider.descriptor(tile, url, 200, "28", "\"second\"", null)
        assertNull(source.compressedSHA256)
        assertEquals("\"first\"", source.sourceETag)
        assertNotEquals(source.cacheKey, changed.cacheKey)
        assertEquals(3601, source.side)
        for (etag in listOf(null, "", "weak", "W/\"weak\"", "\"bad\r\nheader\"")) {
            assertThrows(Exception::class.java) { CoverageSkadiProvider.descriptor(tile, url, 200, "28", etag, null) }
        }
        for (length in listOf("", "-1", "0", "40000001", "1e3", "9999999999999999999999")) {
            assertThrows(Exception::class.java) { CoverageSkadiProvider.descriptor(tile, url, 200, length, "\"first\"", null) }
        }
    }
    @Test fun discoveryFallbackMissingAndCancellation() = runBlocking {
        val started = CountDownLatch(1)
        val transport = client(started)
        try {
            for (latitude in listOf(0.5, 1.5)) {
                val sources = CoverageSkadiProvider.discover(CoverageTerrainFootprint.make(latitude, 0.5, 1000.0), client = transport)
                assertEquals(1, sources.size)
                assertEquals(latitude < 1, sources[0].url.contains("/v2/"))
            }
            for ((latitude, status) in listOf(2.5 to 404, 3.5 to 503)) {
                val error = runCatching { CoverageSkadiProvider.discover(CoverageTerrainFootprint.make(latitude, 0.5, 1000.0), client = transport) }.exceptionOrNull()
                assertEquals(status, (error as CoverageTerrainDownloadFailure).status)
            }
            val task = async(Dispatchers.Default) {
                CoverageSkadiProvider.discover(CoverageTerrainFootprint.make(4.5, 0.5, 1000.0), client = transport)
            }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            task.cancelAndJoin()
            assertTrue(task.isCancelled)
        } finally { transport.dispatcher.executorService.shutdown(); transport.connectionPool.evictAll() }
    }
    @Test fun conditionalDownloadAndLocalDigestProtectDecode() = runBlocking {
        val root = Files.createTempDirectory("skadi-test").toFile()
        val transport = client()
        val url = CoverageSkadiProvider.endpoint(CoverageTerrainTile(0, 0))
        fun source(etag: String) = CoverageTerrainDescriptor("test", "1", "N00E000", null,
            gzip.size.toLong(), 2, url, "EGM96", "test", originURL = url, sourceETag = etag)
        try {
            val descriptor = source("\"fixture\"")
            CoverageTerrainDownloader.download(descriptor, root, CoverageTerrainDownloadBudget(1000), transportClient = transport).use { packed ->
                assertNotNull(packed.sha256)
                CoverageTerrainDecoder.decode(descriptor, packed.file, root, packed.sha256).use { decoded ->
                    assertThrows(CoverageTerrainDecodeFailure::class.java) { CoverageTerrainDecoder.decode(descriptor, packed.file, root, "0".repeat(64)) }
                    assertThrows(CoverageTerrainDecodeFailure::class.java) { CoverageTerrainDecoder.decode(descriptor, packed.file, root) }
                    val cacheRoot = java.io.File(root, "cache")
                    CoverageTerrainDiskCache(cacheRoot, 100000).use {
                        it.publish(descriptor, decoded.file, decoded.sha256, 1, downloadedSHA256 = packed.sha256)
                    }
                    CoverageTerrainDiskCache(cacheRoot, 100000).use { cache ->
                        cache.acquire(descriptor).use { assertArrayEquals(shortArrayOf(1, -2, -32768, 4), it.read(0, 4)) }
                    }
                }
            }
            val error = runCatching { CoverageTerrainDownloader.download(source("\"old\""), root, CoverageTerrainDownloadBudget(1000), transportClient = transport) }.exceptionOrNull()
            assertEquals(412, (error as CoverageTerrainDownloadFailure).status)
            val changed = runCatching { CoverageTerrainDownloader.download(source("\"wrong-response\""), root, CoverageTerrainDownloadBudget(1000), transportClient = transport) }.exceptionOrNull()
            assertEquals(CoverageTerrainDownloadState.CORRUPT, (changed as CoverageTerrainDownloadFailure).state)
        } finally { transport.dispatcher.executorService.shutdown(); transport.connectionPool.evictAll(); root.deleteRecursively() }
    }
    @Test fun liveAWSDiscoveryPreparationAndOfflineReuse() = runBlocking {
        assumeTrue(System.getenv("NODESCOPE_SKADI_LIVE") == "1")
        val root = Files.createTempDirectory("skadi-live").toFile()
        try {
            val footprint = CoverageTerrainFootprint.make(50.5, -115.5, 1000.0)
            val sources = CoverageSkadiProvider.discover(footprint)
            assertEquals("N50W116", sources.single().tile)
            val budget = CoverageTerrainDownloadBudget(40_000_001)
            CoverageTerrainDiskCache(java.io.File(root, "cache"), 100_000_000).use { cache ->
                cache.prepareMosaic(footprint, sources, budget).use { }
                assertEquals(sources[0].packedBytes, budget.receivedBytes)
            }
            CoverageTerrainDiskCache(java.io.File(root, "cache"), 100_000_000).use { cache ->
                cache.prepareMosaic(footprint, sources, CoverageTerrainDownloadBudget(0), true).use { }
            }
            println("SKADI LIVE Android/JVM: ${sources[0].tile}, ${sources[0].packedBytes} bytes, ETag ${sources[0].sourceETag}, verified decode and offline reuse")
        } finally { root.deleteRecursively() }
    }
}
