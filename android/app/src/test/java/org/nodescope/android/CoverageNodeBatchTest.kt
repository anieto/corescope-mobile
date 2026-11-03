package org.nodescope.android

import org.junit.Assert.*
import org.junit.Test
import org.nodescope.android.feature.coverage.*

class CoverageNodeBatchTest {
    private val nodes = (1..2).map { CoverageNodeSnapshot("source", it.toString(), 0.0, it * 0.01) }
    private fun evaluate(terrain: Int = 100, geometry: Int = 100, models: Int = 100,
                         cancelled: () -> Boolean = { false },
                         profile: ((CoverageTerrainPath, Double, Int, Int) -> CoverageTerrainSampledPath)? = null,
                         predict: (CoverageTerrainProfile) -> Double = { 140.0 }): CoverageNodeBatchResult =
        CoverageNodeBatch.evaluate(nodes, true, 2, CoverageTerrainCoordinate(0.0, 0.0), 5000.0,
            CoverageLinkBudget(22.0, 3.0, 2.0, 1.0, 1.0, -130.0, 5.0), terrain, geometry, models, cancelled,
            profile = profile ?: { path, _, samples, geometries ->
                if (samples < 2 || geometries < 1) throw CoverageTerrainProfileFailure(CoverageTerrainProfileState.WORK_LIMIT)
                CoverageTerrainSampledPath(CoverageTerrainProfile(listOf(0.0, path.distanceMeters), listOf(0.0, 0.0), 2, 2), 1)
            }, predictLoss = predict)
    @Test fun budgetsAreSharedAcrossNodesAndRefinements() {
        for (result in listOf(evaluate(terrain = 6), evaluate(geometry = 3), evaluate(models = 3))) {
            assertEquals(CoverageNodeRunPhase.completed, result.run.phase)
            assertEquals(CoverageNodeState.inCoverage, result.run.status(nodes[0]).state)
            assertEquals(CoverageNodeReason.processingLimit, result.run.status(nodes[1]).reason)
            assertEquals(6, result.chargedTerrainEvaluations)
            assertEquals(3, result.chargedGeometryEvaluations)
            assertEquals(3, result.modelCalls)
        }
        val complete = evaluate()
        assertEquals(6, complete.modelCalls)
        assertEquals(12, complete.chargedTerrainEvaluations)
        assertEquals(6, complete.chargedGeometryEvaluations)
        assertEquals(CoverageNodeState.inCoverage, complete.run.status(nodes[1]).state)
    }
    @Test fun failedProfilesConsumeReservationsAndCannotLoop() {
        var calls = 0
        val result = evaluate(profile = { _, _, _, _ ->
            calls++
            throw CoverageTerrainProfileFailure(CoverageTerrainProfileState.MISSING_TERRAIN)
        })
        assertEquals(1, calls)
        assertEquals(0, result.modelCalls)
        assertEquals(100, result.chargedTerrainEvaluations)
        assertEquals(100, result.chargedGeometryEvaluations)
        assertEquals(CoverageNodeReason.missingTerrain, result.run.status(nodes[0]).reason)
        assertEquals(CoverageNodeReason.processingLimit, result.run.status(nodes[1]).reason)
    }
    @Test fun cancellationAndModelFailureNeverReturnPartialRuns() {
        var cancelled = false
        val failure = assertThrows(CoverageTerrainProfileFailure::class.java) {
            evaluate(cancelled = { cancelled }, predict = { cancelled = true; 140.0 })
        }
        assertEquals(CoverageTerrainProfileState.CANCELLED, failure.state)
        assertThrows(CoverageRFConvergenceFailure::class.java) { evaluate(predict = { Double.NaN }) }
    }
}
