package org.nodescope.android

import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.nodescope.android.feature.candidatesites.SavedAntenna
import org.nodescope.android.feature.candidatesites.SavedEndpoint
import org.nodescope.android.feature.candidatesites.SavedLinkProfile
import org.nodescope.android.feature.coverage.*
import org.nodescope.android.feature.linkprofile.*

/**
 * Worst-case encoder output for both portable results (deliverable B, step 5), measured rather than
 * estimated: the largest grid with every cell qualified at full precision, 200 nodes with the longest
 * IDs in characters JSON escapes, 64 terrain descriptors with the longest four-byte text; a Link Profile
 * with 100 samples and 98 obstructions. The limits in saved-plans-v3-result-fields.md come from these.
 */
class PortableResultSizeTest {
    /** A finite double that prints at full length. */
    private val long = -123.45678901234567

    @Test fun worstCaseCoverageFitsItsLimit() {
        // 243 × 243 = 59,049 cells, the largest odd square within 60,000.
        val inputs = CoverageStudyInputs(CoverageTerrainCoordinate(29.123456789012345, -98.12345678901234),
            CoverageGrid.studyArea(12_100, 100), CoverageLinkBudget(22.123456789012345, 2.123456789012345, 2.123456789012345,
                1.123456789012345, 1.123456789012345, -128.12345678901234, 10.123456789012345), 910.12345678901234,
            30.123456789012345, 1.123456789012345)
        assertEquals(59_049, inputs.grid.count)
        val cells = List(inputs.grid.count) { CoverageAreaCell(CoverageAreaState.qualified, long, long) }
        // Node IDs at the 1,024-byte limit, every byte a quote that JSON writes as two.
        val snapshots = List(200) { index ->
            CoverageNodeSnapshot("\"".repeat(1020) + "%04d".format(index), "\"".repeat(1020) + "%04d".format(index),
                -89.12345678901234, -179.1234567890123)
        }
        val run = CoverageNodeRun(snapshots, true, 200, 12_100.0)
        snapshots.forEach { assertTrue(run.accept(CoverageNodeEvaluation(run.id, it, 12_345.678901234567,
            CoverageNodePrediction.QualifiedMargin(long)))) }
        assertTrue(run.complete())
        val text = "😀".repeat(1024)
        val url = "https://example.com/" + "a".repeat(1004)
        val descriptors = List(64) {
            CoverageTerrainDescriptor("d".repeat(48), "r".repeat(48), "N29W099", "ab".repeat(32), 40_000_000, 3601, url,
                text, text, text, url, null)
        }
        val result = CoverageStudyResult(UUID.randomUUID(), inputs, CoverageStudyLimits(75_000_000, 75_000_000, 75_000_000, 75_000_000, 50_000),
            CoverageAreaResult(cells, Long.MAX_VALUE, Long.MAX_VALUE, true), CoverageNodeBatchResult(run, 0, 0, 0),
            CoverageStudyTerrainSources(descriptors), CoverageStudyModel("a".repeat(40), "s".repeat(1024)), 253_402_300_799_999)
        val bytes = requireNotNull(PortableCoverageResult.encode(result))
        println("Worst-case coverage payload: ${bytes.size} bytes")
        assertTrue(bytes.size <= 8 * 1024 * 1024)
    }

    @Test fun worstCaseLinkProfileFitsItsLimit() {
        val antenna = SavedAntenna(10_000.0 - 1e-12, -123.45678901234567, 123.45678901234567)
        val inputs = SavedLinkProfile(SavedEndpoint("A", -89.12345678901234, -179.1234567890123, antenna),
            SavedEndpoint("B", -89.02345678901234, -179.0234567890123, antenna), 910.12345678901234, -22.123456789012345,
            -128.12345678901234, 1.3333333333333333, 100)
        val coordinates = LinkGeometry.samples(ProfileCoordinate(inputs.pointA.latitude, inputs.pointA.longitude),
            ProfileCoordinate(inputs.pointB.latitude, inputs.pointB.longitude), 100)
        val points = List(100) { ElevationPoint(it * 123.45678901234567, -1234.5678901234567) }
        val request = PathRequest(points, antenna.heightMeters, antenna.heightMeters, inputs.frequencyMHz, inputs.refractionK,
            LinkBudget(inputs.transmitPowerDbm, antenna.gainDbi, antenna.gainDbi, antenna.lossDb, antenna.lossDb, inputs.sensitivityDbm))
        val path = PathResult(points.last().distanceMeters, long, long, long, long, ClearanceStatus.PARTIAL_OBSTRUCTION,
            List(98) { Obstruction(long, long, long) }, inputs.frequencyMHz, inputs.refractionK, long, long)
        val analysis = LinkAnalysis(request, TerrainProfile(points, 253_402_300_799_999, "network", coordinates), path,
            359.12345678901234, 253_402_300_799_999)
        val bytes = requireNotNull(PortableLinkProfileResult.encode(analysis, inputs))
        println("Worst-case Link Profile payload: ${bytes.size} bytes")
        assertTrue(bytes.size <= PortableLinkProfileResult.MAXIMUM_BYTES)
    }
}
