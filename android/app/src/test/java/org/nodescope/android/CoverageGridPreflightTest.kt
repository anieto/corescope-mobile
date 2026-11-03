package org.nodescope.android

import org.junit.Assert.*
import org.junit.Test
import org.nodescope.android.feature.coverage.*

class CoverageGridPreflightTest {
    private fun point(east: Int) = CoverageGrid(1000, 50, east, east, 0, 0)
    @Test fun analyticalBoundsAndExcludedPoints() {
        val result = CoverageGridPreflight.estimate(point(250), 87, 12)
        assertEquals(1, result.eligiblePoints)
        assertEquals(87L, result.minimumSamples)
        assertEquals(12L, result.minimumGeometry)
        assertEquals(250_000L, result.maximumSamples)
        assertEquals(200_000L, result.maximumGeometry)
        val empty = CoverageGridPreflight.estimate(point(0), 0, 0)
        assertEquals(0, empty.eligiblePoints)
        assertEquals(0L, empty.maximumSamples)
    }
    @Test fun independentBudgetFailuresAndCancellation() {
        val grid = point(250)
        fun failure(state: CoverageGridPreflightFailureState, block: () -> Unit) {
            assertEquals(state, assertThrows(CoverageGridPreflightFailure::class.java, block).state)
        }
        failure(CoverageGridPreflightFailureState.sampleBudget) { CoverageGridPreflight.estimate(grid, 86, 12) }
        failure(CoverageGridPreflightFailureState.geometryBudget) { CoverageGridPreflight.estimate(grid, 87, 11) }
        failure(CoverageGridPreflightFailureState.pathSampleBudget) { CoverageGridPreflight.estimate(grid, 1000, 1000, pathSampleBudget = 86) }
        failure(CoverageGridPreflightFailureState.pathGeometryBudget) { CoverageGridPreflight.estimate(grid, 1000, 1000, pathGeometryBudget = 11) }
        failure(CoverageGridPreflightFailureState.cancelled) { CoverageGridPreflight.estimate(grid, 1000, 1000, cancelled = { true }) }
    }
    @Test fun aggregateCapsDoNotOverflow32BitCounters() {
        val grid = CoverageGrid(10_000, 100, -10_000, 10_000, -10_000, 10_000)
        val result = CoverageGridPreflight.estimate(grid, 150_000_000, 150_000_000)
        assertTrue(result.maximumSamples > Int.MAX_VALUE)
        assertTrue(result.maximumGeometry > Int.MAX_VALUE)
        assertEquals(result.eligiblePoints.toLong() * 12, result.minimumGeometry)
    }
}
