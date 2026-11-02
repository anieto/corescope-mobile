package org.nodescope.android

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.nodescope.android.feature.coverage.*

private class CacheFiles : AutoCloseable {
    val folder = Files.createTempDirectory("coverage-cache").toFile()
    val root = File(folder, "cache")
    val source = File(folder, "source.hgt")
    val bytes = byteArrayOf(0x80.toByte(), 0, 0xff.toByte(), 0xff.toByte(), 0, 0, 0, 1, 0, 2, 0, 3, 0, 4, 0, 5, 0, 6)
    val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    init { source.writeBytes(bytes) }
    fun descriptor(tile: String = "N00E000") = CoverageTerrainDescriptor("test", "r1", tile, "0".repeat(64), 20, 3,
        "https://example.com/tile", "EGM96", "Test")
    fun publish(cache: CoverageTerrainDiskCache, descriptor: CoverageTerrainDescriptor, cancelled: () -> Boolean = { false }) {
        cache.publish(descriptor, source, hash, 10, cancelled)
    }
    override fun close() { folder.deleteRecursively() }
}

class CoverageTerrainDiskCacheTest {
    private fun failure(state: CoverageTerrainCacheState, block: () -> Unit) {
        assertEquals(state, assertThrows(CoverageTerrainCacheFailure::class.java, block).state)
    }
    private fun corrupt(block: () -> Unit) {
        assertEquals(TerrainReadState.CORRUPT, assertThrows(TerrainReadFailure::class.java, block).state)
    }

    @Test fun publishRestartAndOfflineRead() = CacheFiles().use { f ->
        val descriptor = f.descriptor()
        CoverageTerrainDiskCache(f.root, 100000).use { cache ->
            f.publish(cache, descriptor)
            assertTrue(cache.preflight(listOf(descriptor), 0).hasAllCachedTiles)
        }
        CoverageTerrainDiskCache(f.root, 100000).use { cache ->
            cache.acquire(descriptor).use { assertArrayEquals(shortArrayOf(-32768, -1, 0, 1), it.read(0, 4)) }
            cache.close()
            failure(CoverageTerrainCacheState.CLOSED) { cache.acquire(descriptor) }
        }
    }

    @Test fun pinsProtectReadsReplacementAndClose() = CacheFiles().use { f ->
        val a = f.descriptor(); val b = f.descriptor("N00E001")
        CoverageTerrainDiskCache(f.root, 100000).use { cache ->
            f.publish(cache, a); f.publish(cache, b)
            val first = cache.acquire(a); val second = cache.acquire(a)
            failure(CoverageTerrainCacheState.BUSY) { f.publish(cache, a) }
            failure(CoverageTerrainCacheState.BUSY) { cache.close() }
            cache.clearUnpinned()
            assertEquals(1, cache.inventory.size)
            first.close()
            cache.clearUnpinned()
            assertArrayEquals(shortArrayOf(6), second.read(8, 1))
            second.close()
            cache.clearUnpinned()
            assertTrue(cache.inventory.isEmpty())
        }
    }

    @Test fun evictionRespectsPinsAndPersistedLRU() = CacheFiles().use { f ->
        val a = f.descriptor(); val b = f.descriptor("N00E001"); val c = f.descriptor("N00E002")
        val quota = 4096L + 2 * (8192 + 18)
        CoverageTerrainDiskCache(f.root, quota).use { cache ->
            f.publish(cache, a); f.publish(cache, b)
            cache.acquire(a).close()
        }
        CoverageTerrainDiskCache(f.root, quota).use { cache ->
            cache.acquire(a).use {
                f.publish(cache, c)
                assertEquals(setOf(a.cacheKey, c.cacheKey), cache.inventory.map { it.key }.toSet())
                failure(CoverageTerrainCacheState.MISSING) { cache.acquire(b) }
                cache.acquire(c).use { failure(CoverageTerrainCacheState.QUOTA) { f.publish(cache, b) } }
            }
        }
    }

    @Test fun cancelledOrCorruptPublicationLeavesNoCommittedEntry() = CacheFiles().use { f ->
        val descriptor = f.descriptor()
        CoverageTerrainDiskCache(f.root, 100000).use { cache ->
            var totalChecks = 0
            f.publish(cache, descriptor) { totalChecks++; false }
            cache.clearUnpinned()
            for (checkpoint in 1..totalChecks) {
                var checks = 0
                assertThrows(Exception::class.java) { f.publish(cache, descriptor) { ++checks == checkpoint } }
                assertTrue(cache.inventory.isEmpty())
                assertTrue(requireNotNull(f.root.list()).none { it.startsWith(".stage-") })
            }
            for (bytes in listOf(byteArrayOf(0), f.bytes + byteArrayOf(0))) {
                f.source.writeBytes(bytes)
                corrupt { f.publish(cache, descriptor) }
            }
            f.source.writeBytes(f.bytes)
            corrupt { cache.publish(descriptor, f.source, "1".repeat(64), 10) }
            assertTrue(cache.inventory.isEmpty())
        }
    }

    @Test fun partialAcquisitionReleasesEveryPin() = CacheFiles().use { f ->
        val a = f.descriptor(); val b = f.descriptor("N00E001")
        CoverageTerrainDiskCache(f.root, 100000).use { cache ->
            f.publish(cache, a)
            failure(CoverageTerrainCacheState.MISSING) { cache.acquireMany(listOf(a, b)) }
            assertTrue(cache.inventory.none { it.pinned })
            val readers = cache.acquireMany(listOf(a, a))
            assertEquals(1, readers.size)
            readers.values.forEach { it.close() }
            cache.clearUnpinned()
            assertTrue(cache.inventory.isEmpty())
        }
    }

    @Test fun everyLeaseRechecksPayloadAndFailedOpenReleases() = CacheFiles().use { f ->
        val a = f.descriptor()
        CoverageTerrainDiskCache(f.root, 100000).use { cache ->
            f.publish(cache, a)
            val file = File(File(f.root, a.cacheKey), "terrain.hgt")
            file.writeBytes(f.bytes.copyOf().also { it[0] = 0 })
            corrupt { cache.acquire(a) }
            assertTrue(cache.inventory.none { it.pinned })
            cache.clearUnpinned()
        }
    }

    @Test fun processLockAndUnownedDirectoryProtection() = CacheFiles().use { f ->
        CoverageTerrainDiskCache(f.root, 100000).use {
            failure(CoverageTerrainCacheState.BUSY) { CoverageTerrainDiskCache(f.root, 100000) }
        }
        val other = File(f.folder, "unowned").also { it.mkdir() }
        val file = File(other, "keep").also { it.writeBytes(byteArrayOf(1)) }
        failure(CoverageTerrainCacheState.INVALID_CACHE) { CoverageTerrainDiskCache(other, 100000) }
        assertArrayEquals(byteArrayOf(1), file.readBytes())
    }

    @Test fun restartCleansStagesAndRejectsCorruptMetadata() = CacheFiles().use { f ->
        val a = f.descriptor()
        CoverageTerrainDiskCache(f.root, 100000).use { f.publish(it, a) }
        val stage = File(f.root, ".stage-${UUID.randomUUID()}").also { it.mkdir() }
        File(stage, "partial").writeBytes(byteArrayOf(1))
        CoverageTerrainDiskCache(f.root, 100000).use { assertFalse(stage.exists()) }
        val metadata = File(File(f.root, a.cacheKey), "metadata.json")
        metadata.writeBytes(ByteArray(4097) { 32 })
        failure(CoverageTerrainCacheState.INVALID_CACHE) { CoverageTerrainDiskCache(f.root, 100000) }
        assertTrue(metadata.exists())
    }

    @Test fun invalidReplacementPreservesCommittedTile() = CacheFiles().use { f ->
        val a = f.descriptor()
        CoverageTerrainDiskCache(f.root, 100000).use { cache ->
            f.publish(cache, a)
            f.source.writeBytes(byteArrayOf(1))
            corrupt { f.publish(cache, a) }
            cache.acquire(a).use { assertArrayEquals(shortArrayOf(6), it.read(8, 1)) }
        }
    }
    @Test fun interruptedStageCleanupDoesNotFollowLinks() = CacheFiles().use { f ->
        CoverageTerrainDiskCache(f.root, 100000).use { }
        val outside = File(f.folder, "outside").also { it.mkdir() }
        val sentinel = File(outside, "keep").also { it.writeBytes(byteArrayOf(7)) }
        val stage = File(f.root, ".stage-${UUID.randomUUID()}").also { it.mkdir() }
        Files.createSymbolicLink(File(stage, "link").toPath(), outside.toPath())
        CoverageTerrainDiskCache(f.root, 100000).use {
            assertArrayEquals(byteArrayOf(7), sentinel.readBytes())
            assertFalse(stage.exists())
        }
    }

}
