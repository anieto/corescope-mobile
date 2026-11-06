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
