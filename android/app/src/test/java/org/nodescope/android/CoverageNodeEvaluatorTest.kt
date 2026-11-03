package org.nodescope.android

import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.nodescope.android.feature.coverage.*

class CoverageNodeEvaluatorTest {
    private fun budget() = CoverageLinkBudget(22.0, 3.0, 2.0, 1.0, 1.0, -130.0, 5.0)
    @Test fun exactDatelinePathFeedsConvergenceAndRun() {
        val node = CoverageNodeSnapshot("source", "node", 0.0, -179.99)
        val run = CoverageNodeRun(listOf(node), true, 1, 5000.0)
        var calls = 0
        val result = CoverageNodeEvaluator.evaluate(run.id, CoverageTerrainCoordinate(0.0, 179.99), node,
            run.radiusMeters, budget(), profile = { path, _, _, _ ->
                calls++
                val end = path.point(path.distanceMeters)
                assertEquals(node.latitude, end.latitude, 1e-8)
                assertEquals(node.longitude, end.longitude, 1e-8)
                assertEquals(90.0, path.bearingDegrees, 1e-8)
                CoverageTerrainSampledPath(CoverageTerrainProfile(listOf(0.0, path.distanceMeters), listOf(0.0, 0.0), 2, 2), 0)
            }) { 140.0 }
        assertEquals(3, calls)
        assertTrue(result.distanceMeters in 2200.0..2300.0)
        assertTrue(run.accept(result))
        assertTrue(run.complete())
        assertEquals(CoverageNodeState.inCoverage, run.status(node).state)
    }
    @Test fun excludedAndUnsupportedPathsNeverReadTerrain() {
        for ((longitude, radius, reason) in listOf(Triple(1.0, 1000.0, CoverageNodeReason.outsideRadius),
            Triple(0.0001, 1000.0, CoverageNodeReason.unsupportedPath), Triple(2.0, 300_000.0, CoverageNodeReason.unsupportedPath))) {
            val node = CoverageNodeSnapshot("source", "node", 0.0, longitude)
            val run = UUID.randomUUID()
            val result = CoverageNodeEvaluator.evaluate(run, CoverageTerrainCoordinate(0.0, 0.0), node, radius, budget(),
                profile = { _, _, _, _ -> error("Excluded path read terrain") }) { error("Excluded path called RF model") }
            assertEquals(reason, CoverageNodeStatus.resolve(node, run, radius, result).reason)
        }
    }
    @Test fun terrainFailuresRemainExplicitAndCancellationThrows() {
        val node = CoverageNodeSnapshot("source", "node", 0.0, 0.01)
        for ((failure, reason) in listOf(CoverageTerrainProfileState.MISSING_TERRAIN to CoverageNodeReason.missingTerrain,
            CoverageTerrainProfileState.ACCURACY_UNRESOLVED to CoverageNodeReason.accuracyUnresolved,
            CoverageTerrainProfileState.WORK_LIMIT to CoverageNodeReason.processingLimit)) {
            val run = UUID.randomUUID()
            val result = CoverageNodeEvaluator.evaluate(run, CoverageTerrainCoordinate(0.0, 0.0), node, 5000.0, budget(),
                profile = { _, _, _, _ -> throw CoverageTerrainProfileFailure(failure) }) { error("Failed terrain reached model") }
            assertEquals(reason, CoverageNodeStatus.resolve(node, run, 5000.0, result).reason)
        }
        val failure = assertThrows(CoverageTerrainProfileFailure::class.java) {
            CoverageNodeEvaluator.evaluate(UUID.randomUUID(), CoverageTerrainCoordinate(0.0, 0.0), node, 5000.0, budget(),
                cancelled = { true }, profile = { _, _, _, _ -> error("Cancelled path read terrain") }) { 0.0 }
        }
        assertEquals(CoverageTerrainProfileState.CANCELLED, failure.state)
    }
}
