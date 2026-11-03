package org.nodescope.android

import java.io.File
import kotlin.math.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.nodescope.android.feature.coverage.*

class CoverageTerrainFootprintTest {
    @Test fun sharedFootprints() {
        val fixtures = Json.parseToJsonElement(File("../../shared/fixtures/coverage/terrain-footprint.json").readText()).jsonArray
        assertEquals(8, fixtures.size)
        for (row in fixtures) {
            val item = row.jsonObject
            fun number(key: String) = item.getValue(key).jsonPrimitive.double
            val area = CoverageTerrainFootprint.make(number("latitude"), number("longitude"), number("radius"), number("halo"))
            assertEquals(item.getValue("name").jsonPrimitive.content,
                item.getValue("tiles").jsonArray.map { pair -> pair.jsonArray.map { it.jsonPrimitive.int } },
                area.tiles.map { listOf(it.south, it.west) })
        }
    }

    @Test fun radialPathsRemainWithinSelectedTiles() {
        val radians = PI / 180
        for ((latitude, longitude) in listOf(29.43 to -98.62, 50.96 to -115.16, 70.0 to 179.9, -60.0 to -179.9)) {
            for (radius in listOf(1000.0, 20000.0, 200000.0)) {
                val tiles = CoverageTerrainFootprint.make(latitude, longitude, radius, 100.0).tiles.toSet()
                for (bearing in 0 until 360 step 3) {
                    for (fraction in listOf(0.0, 0.25, 0.5, 0.75, 1.0)) {
                        val arc = (radius + 100) * fraction / CoverageTerrainFootprint.EARTH_RADIUS_METERS
                        val phi = latitude * radians
                        val theta = bearing * radians
                        val lat = asin(sin(phi) * cos(arc) + cos(phi) * sin(arc) * cos(theta))
                        val lon = longitude * radians + atan2(sin(theta) * sin(arc) * cos(phi), cos(arc) - sin(phi) * sin(lat))
                        val degrees = lon / radians
                        val normalized = degrees - 360 * floor((degrees + 180) / 360)
                        assertTrue(tiles.contains(CoverageTerrainTile.containing(lat / radians, normalized)))
                    }
                }
            }
        }
    }

    private fun failure(state: CoverageTerrainFootprintState, block: () -> Unit) {
        assertEquals(state, assertThrows(CoverageTerrainFootprintFailure::class.java, block).state)
    }

    @Test fun invalidInputsAndOversizedAreasFailWithoutTruncation() {
        for ((lat, lon, radius, halo) in listOf(
            listOf(Double.NaN, 0.0, 1.0, 0.0), listOf(0.0, Double.POSITIVE_INFINITY, 1.0, 0.0),
            listOf(91.0, 0.0, 1.0, 0.0), listOf(0.0, 181.0, 1.0, 0.0),
            listOf(0.0, 0.0, -1.0, 0.0), listOf(0.0, 0.0, 1.0, -1.0),
            listOf(0.0, 0.0, Double.POSITIVE_INFINITY, 0.0))) {
            failure(CoverageTerrainFootprintState.INVALID_REQUEST) { CoverageTerrainFootprint.make(lat, lon, radius, halo) }
        }
        for (latitude in listOf(-90.0, 90.0)) {
            failure(CoverageTerrainFootprintState.TOO_MANY_TILES) { CoverageTerrainFootprint.make(latitude, 0.0, 1.0) }
        }
        failure(CoverageTerrainFootprintState.TOO_MANY_TILES) { CoverageTerrainFootprint.make(0.0, 0.0, 1000.0, maximumTiles = 3) }
        failure(CoverageTerrainFootprintState.TOO_MANY_TILES) { CoverageTerrainFootprint.make(0.0, 0.0, 10_000_000.0) }
    }

    private fun descriptor(tile: String, revision: String = "1", url: String = "https://example.com/tile") =
        CoverageTerrainDescriptor("test", revision, tile, "a".repeat(64), 100, 2, url, "test", "test")

    @Test fun manifestResolutionRejectsGapsAmbiguityAndMixedSources() {
        val area = CoverageTerrainFootprint.make(0.5, 0.99, 1500.0)
        val west = descriptor("N00E000")
        val east = descriptor("N00E001")
        assertEquals(listOf(west.cacheKey, east.cacheKey), area.resolve(listOf(east, west)).map { it.cacheKey })
        assertEquals(2, area.resolve(listOf(west, descriptor("N00E000", url = "https://mirror.example.com/tile"), east)).size)
        val missing = assertThrows(CoverageTerrainFootprintFailure::class.java) { area.resolve(listOf(west)) }
        assertEquals(CoverageTerrainFootprintState.MISSING_TILES, missing.state)
        assertEquals(listOf(east.coordinate), missing.missingTiles)
        failure(CoverageTerrainFootprintState.AMBIGUOUS_TILE) { area.resolve(listOf(west, descriptor("N00E000", revision = "2"), east)) }
        failure(CoverageTerrainFootprintState.INCONSISTENT_SOURCES) { area.resolve(listOf(west, descriptor("N00E001", revision = "2"))) }
    }
}
