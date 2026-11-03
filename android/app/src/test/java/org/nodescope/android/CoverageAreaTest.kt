package org.nodescope.android

import org.junit.Assert.*
import org.junit.Test
import org.nodescope.android.feature.coverage.*

class CoverageAreaTest {
    private fun evaluate(failure: CoverageTerrainProfileState? = null, cancelled: () -> Boolean = { false },
                         loss: (CoverageTerrainProfile) -> Double = { 140.0 }): CoverageAreaResult =
        CoverageArea.evaluate(CoverageGrid(1000, 1000, -1000, 1000, -1000, 1000), CoverageTerrainCoordinate(0.0, 0.0),
            CoverageLinkBudget(22.0, 3.0, 2.0, 1.0, 1.0, -130.0, 5.0), 10_000, 10_000, cancelled,
            profile = { path, _, _, _ ->
                if (failure != null) throw CoverageTerrainProfileFailure(failure)
                CoverageTerrainSampledPath(CoverageTerrainProfile(listOf(0.0, path.distanceMeters), listOf(0.0, 0.0), 2, 2), 4)
            }, predictLoss = loss)
    @Test fun completeAreaPreservesMasksAndRawMargins() {
        val result = evaluate()
        assertEquals(9, result.cells.size)
        assertEquals(4, result.cells.count { it.state == CoverageAreaState.qualified })
        assertEquals(4, result.cells.count { it.state == CoverageAreaState.outsideRadius })
        assertEquals(CoverageAreaState.nearSite, result.cells[4].state)
        assertEquals(10.0, result.cells[1].marginDb!!, 0.0)
        assertEquals(24L, result.chargedSamples)
        assertEquals(48L, result.chargedGeometry)
        assertFalse(result.workLimited)
        val unresolved = evaluate(loss = { 150.0 })
        assertEquals(4, unresolved.cells.count { it.state == CoverageAreaState.thresholdUnresolved })
        assertTrue(unresolved.cells.all { it.lossDb == null && it.marginDb == null })
    }
    @Test fun exhaustionAndMissingTerrainNeverBecomeCoverage() {
        val exhausted = evaluate(CoverageTerrainProfileState.WORK_LIMIT)
        assertTrue(exhausted.workLimited)
        assertEquals(CoverageAreaState.outsideRadius, exhausted.cells[0].state)
        assertEquals(CoverageAreaState.workLimit, exhausted.cells[1].state)
        assertTrue(exhausted.cells.drop(2).all { it.state == CoverageAreaState.notComputed && it.lossDb == null })
        assertEquals(10_000L, exhausted.chargedSamples)
        assertEquals(10_000L, exhausted.chargedGeometry)
        val missing = evaluate(CoverageTerrainProfileState.MISSING_TERRAIN)
        assertEquals(CoverageAreaState.missingTerrain, missing.cells[1].state)
        assertEquals(CoverageAreaState.workLimit, missing.cells[3].state)
        assertTrue(missing.cells.all { it.lossDb == null })
    }
    @Test fun cancellationAndInvalidModelDiscardArea() {
        var cancelled = false
        val failure = assertThrows(CoverageTerrainProfileFailure::class.java) {
            evaluate(cancelled = { cancelled }, loss = { cancelled = true; 140.0 })
        }
        assertEquals(CoverageTerrainProfileState.CANCELLED, failure.state)
        assertThrows(CoverageRFConvergenceFailure::class.java) { evaluate(loss = { Double.NaN }) }
    }
}
