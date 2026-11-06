package org.nodescope.android

import java.io.File
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.nodescope.android.feature.coverage.*

class CoverageRasterTest {
    private fun grid() = CoverageGrid(1000, 500, -1000, 1000, -1000, 1000)
    private fun cells(grid: CoverageGrid) = (0 until grid.count).map { index ->
        val point = requireNotNull(grid.point(index))
        when {
            point.state == CoverageGridPointState.outsideRadius -> CoverageAreaCell(CoverageAreaState.outsideRadius)
            point.state == CoverageGridPointState.nearSite -> CoverageAreaCell(CoverageAreaState.nearSite)
            point.northMeters > 0 -> CoverageAreaCell(CoverageAreaState.missingTerrain)
            else -> CoverageAreaCell(CoverageAreaState.qualified, 120.0, if (point.eastMeters < 0) -1.0 else 1.0)
        }
    }
    private fun raster(latitude: Double = 0.0, longitude: Double = 0.0): CoverageRaster {
        val grid = grid()
        return CoverageRaster(grid, CoverageTerrainCoordinate(latitude, longitude), CoverageAreaResult(cells(grid), 0, 0, false))
    }

    @Test fun mapLibreCleanupFailureDoesNotStrandOwnership() {
        val root = java.nio.file.Files.createTempDirectory("coverage-cache").toFile()
        var directory: java.nio.file.Path? = null
        var original: Set<java.nio.file.attribute.PosixFilePermission>? = null
        val cache = CoverageMapLibreTileCache(root)
        try {
            val lease = cache.prepare(raster(), 0)
            directory = java.nio.file.Paths.get(java.net.URI(lease.tiles.urlTemplate.substringBefore("/{z}")))
            original = java.nio.file.Files.getPosixFilePermissions(directory)
            java.nio.file.Files.setPosixFilePermissions(directory, setOf(
                java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE))
            assertThrows(java.io.IOException::class.java) { lease.close() }
            lease.close()
            assertThrows(IllegalStateException::class.java) { cache.prepare(raster(), 0) }
            cache.close()
            java.nio.file.Files.setPosixFilePermissions(directory, original)
            CoverageMapLibreTileCache(root).use { it.prepare(raster(), 0).close() }
        } finally {
            if (directory != null && original != null && java.nio.file.Files.exists(directory)) {
                java.nio.file.Files.setPosixFilePermissions(directory, original)
            }
            cache.close()
            root.deleteRecursively()
        }
    }

    @Test fun mapLibreCacheEnforcesExclusiveOwnershipAndLeases() {
        val root = java.nio.file.Files.createTempDirectory("coverage-cache").toFile()
        try {
            val cache = CoverageMapLibreTileCache(root)
            assertThrows(java.io.IOException::class.java) { CoverageMapLibreTileCache(root) }
            val first = cache.prepare(raster(), 0)
            val second = cache.prepare(raster(), 0)
            assertThrows(IllegalStateException::class.java) { cache.prepare(raster(), 0) }
            assertThrows(IllegalStateException::class.java) { cache.close() }
            first.close()
            first.close()
            cache.prepare(raster(), 0).close()
            second.close()
            cache.close()
            cache.close()
            assertThrows(IllegalStateException::class.java) { cache.prepare(raster(), 0) }
            CoverageMapLibreTileCache(root).use { reopened ->
                assertThrows(CoverageMapLibreTilesFailure::class.java) { reopened.prepare(raster(), 22) }
                reopened.prepare(raster(), 0).close()
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun mapLibreCacheRecoversAbandonedFilesWithoutFollowingLinks() {
        val root = java.nio.file.Files.createTempDirectory("coverage-cache").toFile()
        val outside = java.nio.file.Files.createTempDirectory("coverage-outside").toFile()
        try {
            CoverageMapLibreTileCache(root).close()
            val keep = File(outside, "keep").apply { writeText("preserved") }
            val abandoned = File(root, "coverage-abandoned").apply { mkdir() }
            File(abandoned, "partial.png").writeText("partial")
            java.nio.file.Files.createSymbolicLink(File(abandoned, "link").toPath(), outside.toPath())
            CoverageMapLibreTileCache(root).close()
            assertFalse(abandoned.exists())
            assertEquals("preserved", keep.readText())
            val unexpected = File(root, "saved-plan.json").apply { writeText("protected") }
            val another = File(root, "coverage-another").apply { mkdir() }
            assertThrows(IllegalArgumentException::class.java) { CoverageMapLibreTileCache(root) }
            assertTrue(another.exists())
            assertEquals("protected", unexpected.readText())
            unexpected.delete()
            CoverageMapLibreTileCache(root).close()
            assertFalse(another.exists())
        } finally { root.deleteRecursively(); outside.deleteRecursively() }
    }

    @Test fun mapLibreCacheProtectsUnownedAndUnknownVersions() {
        val root = java.nio.file.Files.createTempDirectory("coverage-cache").toFile()
        try {
            val keep = File(root, "coverage-existing").apply { mkdir() }
            assertThrows(IllegalArgumentException::class.java) { CoverageMapLibreTileCache(root) }
            assertTrue(keep.exists())
            keep.delete()
            CoverageMapLibreTileCache(root).close()
            File(root, ".owner").writeText("future-version")
            assertThrows(IllegalArgumentException::class.java) { CoverageMapLibreTileCache(root) }
            assertEquals("future-version", File(root, ".owner").readText())
        } finally { root.deleteRecursively() }
    }

    @Test fun mapLibrePyramidWrapsLongitudeAndEnforcesLimits() {
        for (longitude in listOf(179.999, -179.999, 180.0, -180.0)) {
            val plan = CoverageMapTilePlan.make(raster(longitude = longitude), 14)
            assertEquals(plan.size, plan.distinct().size)
            val edge = plan.filter { it.zoom == 14 }
            assertEquals(setOf(0, 16383), edge.map { it.x }.toSet())
            assertEquals(setOf(8191, 8192), edge.map { it.y }.toSet())
        }
        val center = CoverageMapTilePlan.make(raster(), 14).filter { it.zoom == 14 }
        assertEquals(setOf(8191, 8192), center.map { it.x }.toSet())
        assertEquals(setOf(8191, 8192), center.map { it.y }.toSet())
        for (latitude in listOf(-85.0, 85.0)) {
            val plan = CoverageMapTilePlan.make(raster(latitude = latitude), 10)
            assertTrue(plan.all { it.y in 0 until (1 shl it.zoom) })
        }
        assertEquals(CoverageMapLibreTilesState.tileLimit, assertThrows(CoverageMapLibreTilesFailure::class.java) {
            CoverageMapTilePlan.make(raster(), 22)
        }.state)
        assertEquals(CoverageMapLibreTilesState.invalidZoom, assertThrows(CoverageMapLibreTilesFailure::class.java) {
            CoverageMapTilePlan.make(raster(), -1)
        }.state)
    }

    @Test fun mapLibreLocalTilesPreservePNGAndCleanOnlyOwnedFiles() {
        val parent = java.nio.file.Files.createTempDirectory("coverage tiles ").toFile()
        try {
            val sentinel = File(parent, "unrelated").apply { writeText("keep") }
            val raster = raster()
            val tiles = CoverageMapLibreTiles.prepare(raster, 2, parent)
            assertTrue(tiles.urlTemplate.startsWith("file:///"))
            assertTrue(tiles.urlTemplate.contains("%20"))
            val plan = CoverageMapTilePlan.make(raster, 2)
            assertEquals(plan.size, tiles.tileCount)
            var bytes = 0L
            for (tile in plan) {
                val uri = tiles.urlTemplate.replace("{z}", tile.zoom.toString())
                    .replace("{x}", tile.x.toString()).replace("{y}", tile.y.toString())
                val data = File(java.net.URI(uri)).readBytes()
                assertArrayEquals(raster.pngTile(tile.zoom, tile.x, tile.y), data)
                bytes += data.size
            }
            assertEquals(bytes, tiles.encodedBytes)
            tiles.close()
            tiles.close()
            assertEquals(listOf("unrelated"), parent.list()!!.toList())
            var checks = 0
            assertThrows(CoverageRasterFailure::class.java) {
                CoverageMapLibreTiles.prepare(raster, 2, parent) { ++checks > 3 }
            }
            assertEquals(listOf("unrelated"), parent.list()!!.toList())
            assertEquals("keep", sentinel.readText())
            assertThrows(CoverageMapLibreTilesFailure::class.java) {
                CoverageMapLibreTiles.prepare(raster, 22, parent)
            }
            assertEquals(listOf("unrelated"), parent.list()!!.toList())
        } finally { parent.deleteRecursively() }
    }

    @Test fun googleProviderReturnsPNGAndRejectsInvalidatedRequests() {
        val raster = raster()
        val provider = CoverageGoogleTileProvider(raster, 1024 * 1024)
        val tile = requireNotNull(provider.getTile(8192, 8192, 14))
        assertEquals(256, tile.width)
        assertEquals(256, tile.height)
        assertArrayEquals(raster.pngTile(14, 8192, 8192), tile.data)
        assertSame(com.google.android.gms.maps.model.TileProvider.NO_TILE, provider.getTile(0, 0, 23))
        provider.invalidate()
        assertSame(com.google.android.gms.maps.model.TileProvider.NO_TILE, provider.getTile(8192, 8192, 14))
    }

    @Test fun tinyTilesCannotGrowCacheEntryCountWithoutBound() {
        val store = CoverageTileStore(raster(), 8 * 1024 * 1024)
        val size = store.tile(8, 0, 0).size
        for (x in 1 until 64) store.tile(8, x, 0)
        var hitChecks = 0
        store.tile(8, 0, 0) { ++hitChecks > 1 }
        store.tile(8, 64, 0)
        assertEquals(64 * size, store.cachedBytes)
        var checks = 0
        assertEquals(CoverageRasterState.cancelled, assertThrows(CoverageRasterFailure::class.java) {
            store.tile(8, 1, 0) { ++checks > 1 }
        }.state)
        assertEquals(64 * size, store.cachedBytes)
    }

    @Test fun invalidationDoesNotWaitForRendering() {
        val store = CoverageTileStore(raster(), 1024 * 1024)
        val entered = java.util.concurrent.CountDownLatch(1)
        val resume = java.util.concurrent.CountDownLatch(1)
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            val worker = executor.submit<CoverageRasterState> {
                var checkpoints = 0
                assertThrows(CoverageRasterFailure::class.java) {
                    store.tile(14, 8192, 8192) {
                        checkpoints++
                        if (checkpoints == 3) {
                            entered.countDown()
                            resume.await(5, java.util.concurrent.TimeUnit.SECONDS)
                        }
                        false
                    }
                }.state
            }
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            store.invalidate()
            assertEquals(0, store.cachedBytes)
            resume.countDown()
            assertEquals(CoverageRasterState.cancelled, worker.get(5, java.util.concurrent.TimeUnit.SECONDS))
        } finally {
            resume.countDown()
            executor.shutdownNow()
        }
    }

    @Test fun tileStoreBoundsRetentionAndRejectsObsoleteWork() {
        val raster = raster()
        val reference = raster.pngTile(0, 0, 0)
        val store = CoverageTileStore(raster, reference.size)
        val first = store.tile(0, 0, 0)
        assertArrayEquals(reference, first)
        assertEquals(reference.size, store.cachedBytes)
        first.fill(0)
        assertArrayEquals(reference, store.tile(0, 0, 0))
        for (x in 0 until 4) {
            store.tile(2, x, 0)
            assertTrue(store.cachedBytes <= reference.size)
        }
        assertEquals(CoverageRasterState.cancelled, assertThrows(CoverageRasterFailure::class.java) {
            store.tile(2, 3, 0) { true }
        }.state)
        store.invalidate()
        assertEquals(0, store.cachedBytes)
        assertEquals(CoverageTileStoreState.inactive, assertThrows(CoverageTileStoreFailure::class.java) {
            store.tile(0, 0, 0)
        }.state)
        val uncached = CoverageTileStore(raster, 0)
        assertArrayEquals(reference, uncached.tile(0, 0, 0))
        assertEquals(0, uncached.cachedBytes)
        for (capacity in listOf(-1, 8 * 1024 * 1024 + 1)) {
            assertEquals(CoverageTileStoreState.invalidCapacity, assertThrows(CoverageTileStoreFailure::class.java) {
                CoverageTileStore(raster, capacity)
            }.state)
        }
        val interrupted = CoverageTileStore(raster, 1024 * 1024)
        var checkpoints = 0
        assertEquals(CoverageRasterState.cancelled, assertThrows(CoverageRasterFailure::class.java) {
            interrupted.tile(14, 8192, 8192) {
                checkpoints++
                if (checkpoints == 3) interrupted.invalidate()
                false
            }
        }.state)
        assertEquals(0, interrupted.cachedBytes)
        assertEquals(CoverageTileStoreState.inactive, assertThrows(CoverageTileStoreFailure::class.java) {
            interrupted.tile(0, 0, 0)
        }.state)
    }

    @Test fun sharedGeographicCheckpoints() {
        val fixtures = Json.parseToJsonElement(File("../../shared/fixtures/coverage/raster-geography.json").readText()).jsonArray
        assertEquals(4, fixtures.size)
        for (fixture in fixtures) {
            val row = fixture.jsonObject
            val image = raster(row.getValue("latitude").jsonPrimitive.double, row.getValue("longitude").jsonPrimitive.double)
            for (point in row.getValue("points").jsonArray) {
                val p = point.jsonObject
                assertEquals(p.getValue("state").jsonPrimitive.content,
                    image.classification(p.getValue("latitude").jsonPrimitive.double, p.getValue("longitude").jsonPrimitive.double).name)
            }
        }
    }

    @Test fun projectedTilePreservesMaskAndTransparency() {
        val raster = raster()
        val data = raster.tile(14, 8192, 8191)
        assertEquals(256 * 256 * 4, data.size)
        fun pixel(bytes: ByteArray, x: Int, y: Int) = bytes.slice((y * 256 + x) * 4 until (y * 256 + x + 1) * 4).map { it.toInt() and 255 }
        assertEquals(listOf(0x66, 0x66, 0x66, 0x90), pixel(data, 32, 224))
        assertEquals(listOf(0x66, 0x66, 0x66, 0x90), pixel(data, 33, 224))
        assertEquals(listOf(0, 0, 0, 0), pixel(data, 34, 224))
        assertEquals(listOf(0, 0, 0, 0), pixel(data, 255, 0))
        assertEquals(listOf(0x26, 0xB8, 0x5A, 0xA0), pixel(raster.tile(14, 8192, 8192), 32, 32))
        assertEquals(0, CoverageRaster.rgba(CoverageRasterClass.belowThreshold, 0, 0))
        assertEquals(CoverageRaster.rgba(CoverageRasterClass.unresolved, 0, 0), CoverageRaster.rgba(CoverageRasterClass.unresolved, 256, 0))
    }

    @Test fun rejectsInvalidAndCancelledRasters() {
        val grid = grid()
        val invalid = cells(grid).toMutableList()
        invalid[12] = CoverageAreaCell(CoverageAreaState.qualified, 1.0, 1.0)
        assertEquals(CoverageRasterState.invalidResult, assertThrows(CoverageRasterFailure::class.java) {
            CoverageRaster(grid, CoverageTerrainCoordinate(0.0, 0.0), CoverageAreaResult(invalid, 0, 0, false))
        }.state)
        invalid[12] = CoverageAreaCell(CoverageAreaState.nearSite)
        invalid[17] = CoverageAreaCell(CoverageAreaState.qualified, Double.NaN, 1.0)
        assertEquals(CoverageRasterState.invalidResult, assertThrows(CoverageRasterFailure::class.java) {
            CoverageRaster(grid, CoverageTerrainCoordinate(0.0, 0.0), CoverageAreaResult(invalid, 0, 0, false))
        }.state)
        val raster = raster()
        assertEquals(CoverageRasterState.cancelled, assertThrows(CoverageRasterFailure::class.java) { raster.tile(14, 8192, 8192) { true } }.state)
        for ((z, x, y) in listOf(Triple(-1, 0, 0), Triple(23, 0, 0), Triple(0, 1, 0), Triple(0, 0, -1))) {
            assertEquals(CoverageRasterState.invalidTile, assertThrows(CoverageRasterFailure::class.java) { raster.tile(z, x, y) }.state)
        }
    }
}
