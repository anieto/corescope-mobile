package org.nodescope.android

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.nodescope.android.feature.coverage.*

/**
 * Step 6 speed work must not change a prediction: mapped reads, the block-cache fallback and path
 * reuse are each compared with the plain file reader on rough terrain, down to the fingerprint.
 */
class CoverageTerrainPerformanceTest {
    private val side = 121
    /** Hills, a ridge and some no-data, so paths refine, qualify, fail and go unresolved. */
    private val heights = List(side * side) { index ->
        val row = index / side
        val column = index % side
        if (row == 37 && column in 80..83) Short.MIN_VALUE.toInt()
        else (200 + 60 * kotlin.math.sin(row / 6.0) * kotlin.math.cos(column / 9.0) + if (column == 70) 90 else 0).toInt()
    }

    private fun <T> withMosaic(mapped: Boolean, reuse: Boolean, body: (CoverageTerrainMosaic) -> T): T {
        val folder = Files.createTempDirectory("coverage-speed").toFile()
        val previous = LeasedTerrainReader.mappingEnabled
        try {
            LeasedTerrainReader.mappingEnabled = mapped
            val file = File(folder, "tile.hgt")
            val bytes = heights.flatMap { listOf((it shr 8).toByte(), it.toByte()) }.toByteArray()
            file.writeBytes(bytes)
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
            val reader = LeasedTerrainReader.open(file, side, digest) {}
            assertEquals(mapped, reader.isMapped)
            return CoverageTerrainMosaic(mapOf(CoverageTerrainTile(0, 0) to reader), reusePreparedPaths = reuse).use(body)
        } finally {
            LeasedTerrainReader.mappingEnabled = previous
            folder.deleteRecursively()
        }
    }

    private fun study(mosaic: CoverageTerrainMosaic): CoverageStudyResult {
        val inputs = CoverageStudyInputs(CoverageTerrainCoordinate(0.5, 0.5), CoverageGrid.studyArea(8000, 1000),
            CoverageLinkBudget(22.0, 3.0, 2.0, 1.0, 1.0, -130.0, 5.0), 915.0, 10.0, 1.5)
        val nodes = listOf(CoverageNodeSnapshot("source", "a", 0.53, 0.52), CoverageNodeSnapshot("source", "b", 0.46, 0.55))
        return CoverageStudy.evaluate(inputs, nodes, true, 10, CoverageStudyLimits(120_000_000, 120_000_000, 30_000_000, 30_000_000, 1000),
            mosaic, UUID(0, 0)) { profile, captured ->
            val latitude = requireNotNull(profile.pathCenterLatitude)
            CoverageRFKernel.predict(profile, captured, latitude)
        }
    }

    @Test fun mappedReadsAndPathReuseMatchThePlainReaderExactly() {
        val baseline = withMosaic(mapped = false, reuse = false) { mosaic ->
            study(mosaic).also { assertTrue(mosaic.readerCounters.getValue("terrainBlockHits") > 0) }
        }
        val reference = CoverageRunDiagnostics.predictionFingerprint(baseline)
        assertTrue(baseline.area.cells.count { it.state == CoverageAreaState.qualified } > 10)
        for ((mapped, reuse) in listOf(true to false, false to true, true to true)) {
            val fast = withMosaic(mapped, reuse) { mosaic ->
                study(mosaic).also { result ->
                    if (reuse) {
                        assertTrue(mosaic.crossingCacheHits > 0)
                        assertTrue(mosaic.elevationCacheHits > 0)
                    }
                    if (mapped) assertEquals(0L, mosaic.readerCounters.getValue("terrainFileReadBytes"))
                    // Cache hits never discount the logical work a study is charged.
                    assertEquals(baseline.area.chargedSamples, result.area.chargedSamples)
                    assertEquals(baseline.area.chargedGeometry, result.area.chargedGeometry)
                }
            }
            assertEquals("mapped=$mapped reuse=$reuse", reference, CoverageRunDiagnostics.predictionFingerprint(fast))
            assertEquals(baseline.area.cells, fast.area.cells)
        }
    }

    @Test fun reuseCutsTerrainReadsAcrossRefinementPasses() {
        val path = CoverageTerrainPath(0.5, 0.5, 37.0, 6000.0)
        fun passes(reuse: Boolean) = withMosaic(mapped = true, reuse = reuse) { mosaic ->
            val profiles = listOf(30.0, 10.0, 5.0, 2.0).map { mosaic.profile(path, it) }
            profiles to mosaic.elevationReads
        }
        val (plain, plainReads) = passes(false)
        val (reused, reusedReads) = passes(true)
        assertEquals(plain, reused)
        // Finer passes reread only the points the coarser ones didn't cover.
        assertTrue("reads $reusedReads vs $plainReads", reusedReads < plainReads * 3 / 4)
        // A new path starts over: its heights are never served from another path's cache.
        withMosaic(mapped = true, reuse = true) { mosaic ->
            mosaic.profile(path, 30.0)
            val other = CoverageTerrainPath(0.5, 0.5, 38.0, 6000.0)
            assertEquals(withMosaic(mapped = false, reuse = false) { it.profile(other, 30.0) }, mosaic.profile(other, 30.0))
        }
    }

    @Test fun closedReadersRefuseReadsInEveryMode() {
        for (mapped in listOf(true, false)) withMosaic(mapped, reuse = true) { mosaic ->
            mosaic.close()
            assertThrows(TerrainReadFailure::class.java) { mosaic.sample(0.5, 0.5) }
        }
    }
}
