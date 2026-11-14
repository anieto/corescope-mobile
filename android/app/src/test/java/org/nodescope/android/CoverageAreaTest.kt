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
    @Test fun progressCountsOnlySamplesThatNeedAPath() {
        fun reports(failure: CoverageTerrainProfileState? = null): List<Pair<Int, Int>> {
            val seen = mutableListOf<Pair<Int, Int>>()
            CoverageArea.evaluate(CoverageGrid(1000, 1000, -1000, 1000, -1000, 1000), CoverageTerrainCoordinate(0.0, 0.0),
                CoverageLinkBudget(22.0, 3.0, 2.0, 1.0, 1.0, -130.0, 5.0), 10_000, 10_000,
                progress = { completed, total -> seen += completed to total },
                profile = { path, _, _, _ ->
                    if (failure != null) throw CoverageTerrainProfileFailure(failure)
                    CoverageTerrainSampledPath(CoverageTerrainProfile(listOf(0.0, path.distanceMeters), listOf(0.0, 0.0), 2, 2), 4)
                }, predictLoss = { 140.0 })
            return seen
        }
        // Four eligible samples: the near-site centre and outside corners are not counted.
        val complete = reports()
        assertEquals(0 to 4, complete.first())
        assertEquals(4 to 4, complete.last())
        // A work-limited run ends short of the total; those samples are not done.
        assertTrue(reports(CoverageTerrainProfileState.WORK_LIMIT).last().first < 4)
    }
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
        // Out of batch budget: samples are left for Continue, not marked as failed paths (iOS expectations).
        val exhausted = evaluate(CoverageTerrainProfileState.WORK_LIMIT)
        assertTrue(exhausted.workLimited)
        assertEquals(CoverageAreaState.outsideRadius, exhausted.cells[0].state)
        assertEquals(CoverageAreaState.notComputed, exhausted.cells[1].state)
        assertEquals(4, exhausted.pendingCount)
        assertTrue(listOf(3, 5, 7).all { exhausted.cells[it].state == CoverageAreaState.notComputed })
        assertEquals(4, exhausted.cells.count { it.state == CoverageAreaState.outsideRadius })
        assertEquals(CoverageAreaState.nearSite, exhausted.cells[4].state)
        assertTrue(exhausted.cells.all { it.lossDb == null })
        assertEquals(10_000L, exhausted.chargedSamples)
        assertEquals(10_000L, exhausted.chargedGeometry)
        val missing = evaluate(CoverageTerrainProfileState.MISSING_TERRAIN)
        assertEquals(CoverageAreaState.missingTerrain, missing.cells[1].state)
        assertEquals(CoverageAreaState.notComputed, missing.cells[5].state)
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
