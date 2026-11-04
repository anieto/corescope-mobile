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
