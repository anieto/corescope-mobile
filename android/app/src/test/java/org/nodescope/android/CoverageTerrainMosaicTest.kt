package org.nodescope.android

import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.security.MessageDigest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.nodescope.android.feature.coverage.*

private class MosaicFiles : AutoCloseable {
    val folder = Files.createTempDirectory("coverage-mosaic").toFile()
    var released = 0
    fun reader(samples: List<Int>, side: Int = 3, digest: String? = null,
               cancelled: () -> Boolean = { false }): LeasedTerrainReader {
        val file = File.createTempFile("tile", ".hgt", folder)
        val bytes = samples.flatMap { listOf((it shr 8).toByte(), it.toByte()) }.toByteArray()
        file.writeBytes(bytes)
        val hash = digest ?: MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        return LeasedTerrainReader.open(file, side, hash, cancelled) { released++ }
    }
    override fun close() { folder.deleteRecursively() }
}

class CoverageTerrainMosaicTest {
    @Test fun geographicProfileReadsTerrainAndRetainsPins() {
        MosaicFiles().use { files ->
            val reader = files.reader(List(121 * 121) { 200 }, side = 121)
            CoverageTerrainMosaic(mapOf(CoverageTerrainTile(0, 0) to reader)).use { mosaic ->
                val path = CoverageTerrainPath(0.5, 0.5, 90.0, 2000.0)
                val result = mosaic.profile(path, 30.0)
                assertTrue(result.profile.elevationsMeters.all { kotlin.math.abs(it - 200) < 1e-9 })
                assertEquals(2000.0, result.profile.distancesMeters.last(), 0.0)
                assertTrue(result.geometryEvaluations > 0)
                // Controlled model response validates integration, not physical RF accuracy.
                val converged = CoverageRFConvergence.evaluate(path.distanceMeters, 152.0,
                    profile = { spacing, samples, geometry -> mosaic.profile(path, spacing, samples, geometry) }) { 100.0 }
                assertEquals(CoverageRFConvergenceState.QUALIFIED, converged.state)
                assertEquals(3, converged.passes)
                assertEquals(0, files.released)
                val missing = CoverageTerrainPath(0.5, 0.999, 90.0, 2000.0)
                val error = assertThrows(CoverageTerrainProfileFailure::class.java) { mosaic.profile(missing, 30.0) }
                assertEquals(CoverageTerrainProfileState.MISSING_TERRAIN, error.state)
                assertEquals(0, files.released)
            }
            assertEquals(1, files.released)
        }
    }

    private fun failure(state: TerrainReadState, block: () -> Unit) {
        assertEquals(state, assertThrows(TerrainReadFailure::class.java, block).state)
    }

    @Test fun sharedGeographicFixtures() {
        val fixture = Json.parseToJsonElement(File("../../shared/fixtures/coverage/terrain-mosaic.json").readText()).jsonObject
        val cases = fixture.getValue("cases").jsonArray
        assertEquals(18, cases.size)
        MosaicFiles().use { files ->
            val readers = mutableMapOf<CoverageTerrainTile, LeasedTerrainReader>()
            try {
                for (entry in fixture.getValue("tiles").jsonArray) {
                    val tile = entry.jsonObject
                    readers[CoverageTerrainTile(tile.getValue("south").jsonPrimitive.int, tile.getValue("west").jsonPrimitive.int)] =
                        files.reader(tile.getValue("samples").jsonArray.map { it.jsonPrimitive.int }, tile.getValue("side").jsonPrimitive.int)
                }
                CoverageTerrainMosaic(readers).use { mosaic ->
                    for (entry in cases) {
                        val item = entry.jsonObject
                        val actual = mosaic.sample(item.getValue("latitude").jsonPrimitive.double, item.getValue("longitude").jsonPrimitive.double)
                        val expected = when (item.getValue("state").jsonPrimitive.content) {
                            "elevation" -> CoverageTerrainSample.ElevationMeters(item.getValue("meters").jsonPrimitive.double)
                            "nodata" -> CoverageTerrainSample.NoData
                            "missing" -> CoverageTerrainSample.MissingTile(CoverageTerrainTile(item.getValue("south").jsonPrimitive.int, item.getValue("west").jsonPrimitive.int))
                            else -> error("Unknown fixture state")
                        }
                        assertEquals(item.getValue("name").jsonPrimitive.content, expected, actual)
                    }
                    assertEquals(0, files.released)
                    mosaic.close()
                    mosaic.close()
                    assertEquals(readers.size, files.released)
                    failure(TerrainReadState.CLOSED) { mosaic.sample(0.0, 0.0) }
                }
            } finally { readers.values.forEach { it.close() } }
        }
    }

    @Test fun invalidCoordinatesAndEmptyMosaic() {
        CoverageTerrainMosaic(emptyMap()).use { mosaic ->
            for ((lat, lon) in listOf(Double.NaN to 0.0, 0.0 to Double.POSITIVE_INFINITY,
                91.0 to 0.0, -91.0 to 0.0, 0.0 to 181.0, 0.0 to -181.0)) {
                failure(TerrainReadState.INVALID_RANGE) { mosaic.sample(lat, lon) }
            }
            assertEquals(CoverageTerrainSample.MissingTile(CoverageTerrainTile(0, 0)), mosaic.sample(0.0, 0.0))
            failure(TerrainReadState.CANCELLED) { mosaic.sample(0.0, 0.0) { true } }
        }
    }

    @Test fun cancellationNeverReturnsPartialElevationAndKeepsPins() {
        MosaicFiles().use { files ->
            CoverageTerrainMosaic(mapOf(CoverageTerrainTile(0, 0) to files.reader(List(9) { 20 }))).use { mosaic ->
                for (checkpoint in 1..8) {
                    var checks = 0
                    failure(TerrainReadState.CANCELLED) { mosaic.sample(0.25, 0.25) { ++checks == checkpoint } }
                    assertEquals(0, files.released)
                }
                assertEquals(CoverageTerrainSample.ElevationMeters(20.0), mosaic.sample(0.25, 0.25))
            }
        }
    }

    @Test fun failedReaderOpenReleasesAndNeverReturnsZeroTerrain() {
        MosaicFiles().use { files ->
            failure(TerrainReadState.CORRUPT) { files.reader(List(9) { 1 }, digest = "0".repeat(64)) }
            failure(TerrainReadState.CORRUPT) { files.reader(listOf(1, 2)) }
            failure(TerrainReadState.INVALID_DESCRIPTOR) { files.reader(listOf(1), side = Int.MAX_VALUE) }
            failure(TerrainReadState.CANCELLED) { files.reader(List(9) { 1 }, cancelled = { true }) }
            assertEquals(4, files.released)
        }
    }

    @Test fun duplicateReaderOwnershipIsRejectedAndReleasedOnce() {
        MosaicFiles().use { files ->
            val reader = files.reader(List(9) { 1 })
            failure(TerrainReadState.INVALID_DESCRIPTOR) {
                CoverageTerrainMosaic(mapOf(CoverageTerrainTile(0, 0) to reader, CoverageTerrainTile(0, 1) to reader))
            }
            assertEquals(1, files.released)
        }
    }

    @Test fun readerCorruptionIsFailureNotMissingTerrain() {
        MosaicFiles().use { files ->
            val reader = files.reader(List(9) { 1 })
            CoverageTerrainMosaic(mapOf(CoverageTerrainTile(0, 0) to reader)).use { mosaic ->
                RandomAccessFile(requireNotNull(files.folder.listFiles()).single(), "rw").use { it.setLength(2) }
                failure(TerrainReadState.CORRUPT) { mosaic.sample(0.25, 0.25) }
                assertEquals(0, files.released)
            }
        }
    }

    @Test fun boundedReaderAndMosaicOwnership() {
        MosaicFiles().use { files ->
            val reader = files.reader(listOf(-32768, -32767, -23, -1, 0, 1, 256, 32767, 17))
            reader.use {
                assertArrayEquals(shortArrayOf(-23, -1, 0, 1), it.read(2, 4))
                for ((start, count) in listOf(-1 to 1, 0 to 0, 9 to 1, 8 to 2, 0 to 32769, Int.MAX_VALUE to 1, 0 to Int.MAX_VALUE)) {
                    failure(TerrainReadState.INVALID_RANGE) { it.read(start, count) }
                }
            }
            assertEquals(1, files.released)
            val readers = (0..256).associate { index -> CoverageTerrainTile(index / 180, index % 180) to files.reader(List(9) { 1 }) }
            failure(TerrainReadState.INVALID_DESCRIPTOR) { CoverageTerrainMosaic(readers) }
            assertEquals(258, files.released)
        }
    }
}
