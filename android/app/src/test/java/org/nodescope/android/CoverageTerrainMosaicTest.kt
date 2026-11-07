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
    @Test fun combinedStudyReservesNodeWorkAndDiscardsCancelledArea() {
        MosaicFiles().use { files ->
            val reader = files.reader(List(121 * 121) { 200 }, side = 121)
            CoverageTerrainMosaic(mapOf(CoverageTerrainTile(0, 0) to reader)).use { mosaic ->
                val origin = CoverageTerrainCoordinate(0.5, 0.5)
                val grid = CoverageGrid(5000, 1000, 1000, 1000, 0, 0)
                val budget = CoverageLinkBudget(22.0, 3.0, 2.0, 1.0, 1.0, -130.0, 5.0)
                val node = CoverageNodeSnapshot("source", "node", 0.5, 0.52)
                for (areaSamples in listOf(337, 1_000_000)) {
                    val limits = CoverageStudyLimits(areaSamples, 1_000_000, 1_000_000, 1_000_000, 5)
                    val result = CoverageStudy.evaluate(CoverageStudyInputs(origin, grid, budget, 915.0, 30.0, 1.5), listOf(node), true, 1, limits, mosaic) { _, inputs ->
                        assertEquals(915.0, inputs.frequencyMHz, 0.0)
                        assertEquals(30.0, inputs.transmitHeightMeters, 0.0)
                        assertEquals(1.5, inputs.receiveHeightMeters, 0.0)
                        140.0
                    }
                    assertEquals(areaSamples == 337, result.area.workLimited)
                    assertEquals(CoverageNodeState.inCoverage, result.nodes.run.status(node).state)
                    assertTrue(result.area.chargedSamples <= limits.areaSamples)
                    assertTrue(result.nodes.chargedTerrainEvaluations <= limits.nodeSamples)
                }
                val limits = CoverageStudyLimits(1_000_000, 1_000_000, 1_000_000, 1_000_000, 5)
                var cancelled = false
                val failure = assertThrows(CoverageTerrainProfileFailure::class.java) {
                    CoverageStudy.evaluate(CoverageStudyInputs(origin, grid, budget, 915.0, 30.0, 1.5), listOf(node), true, 1, limits, mosaic,
                        cancelled = { cancelled }) { profile, _ ->
                        if (profile.distancesMeters.last() > 1500) cancelled = true
                        140.0
                    }
                }
                assertEquals(CoverageTerrainProfileState.CANCELLED, failure.state)
                val empty = CoverageStudy.evaluate(CoverageStudyInputs(origin, grid, budget, 915.0, 30.0, 1.5), emptyList(), false, 1, limits, mosaic) { _, inputs ->
                        assertEquals(915.0, inputs.frequencyMHz, 0.0)
                        assertEquals(30.0, inputs.transmitHeightMeters, 0.0)
                        assertEquals(1.5, inputs.receiveHeightMeters, 0.0)
                        140.0
                    }
                assertEquals(CoverageAreaState.qualified, empty.area.cells[0].state)
                assertEquals(0, empty.nodes.run.receivedCount)
                assertEquals(0, files.released)
                assertThrows(CoverageTerrainProfileFailure::class.java) {
                    CoverageStudyLimits(150_000_000, 0, 1, 0, 0).validate()
                }
            }
            assertEquals(1, files.released)
        }
    }

    @Test fun exactNodesUseFileBackedTerrainWithoutTakingOwnership() {
        MosaicFiles().use { files ->
            val reader = files.reader(List(121 * 121) { -20 }, side = 121)
            CoverageTerrainMosaic(mapOf(CoverageTerrainTile(0, 0) to reader)).use { mosaic ->
                val budget = CoverageLinkBudget(22.0, 3.0, 2.0, 1.0, 1.0, -130.0, 5.0)
                val node = CoverageNodeSnapshot("source", "node", 0.5, 0.52)
                val run = java.util.UUID.randomUUID()
                var calls = 0
                val result = CoverageNodeEvaluator.evaluate(run, CoverageTerrainCoordinate(0.5, 0.5), node,
                    5000.0, budget, mosaic) { profile ->
                    calls++
                    assertTrue(profile.elevationsMeters.all { kotlin.math.abs(it + 20) < 1e-9 })
                    140.0
                }
                assertEquals(3, calls)
                assertEquals(CoverageNodeState.inCoverage, CoverageNodeStatus.resolve(node, run, 5000.0, result).state)
                val missing = CoverageNodeSnapshot("source", "missing", 0.5, 1.01)
                val unavailable = CoverageNodeEvaluator.evaluate(run, CoverageTerrainCoordinate(0.5, 0.99), missing,
                    5000.0, budget, mosaic) { error("Missing tile reached the model") }
                assertEquals(CoverageNodeReason.missingTerrain, CoverageNodeStatus.resolve(missing, run, 5000.0, unavailable).reason)
                var cancelled = false
                val failure = assertThrows(CoverageTerrainProfileFailure::class.java) {
                    CoverageNodeEvaluator.evaluate(run, CoverageTerrainCoordinate(0.5, 0.5), node,
                        5000.0, budget, mosaic, cancelled = { cancelled }) { cancelled = true; 140.0 }
                }
                assertEquals(CoverageTerrainProfileState.CANCELLED, failure.state)
                assertEquals(0, files.released)
            }
            assertEquals(1, files.released)
        }
    }

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
