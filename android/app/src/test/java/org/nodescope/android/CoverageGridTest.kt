package org.nodescope.android

import org.junit.Assert.*
import org.junit.Test
import org.nodescope.android.feature.coverage.*

class CoverageGridTest {
    @Test fun studyLayoutsPreserveRadiusSpacingAndRowOrder() {
        for (values in listOf(listOf(20_000, 1000, 20_000, 1681), listOf(100_000, 20_000, 100_000, 121), listOf(20_000, 100, 2000, 1681))) {
            val (radius, spacing, extent, expected) = values
            val grid = CoverageGrid(radius, spacing, -extent, extent, -extent, extent)
            assertEquals(expected, grid.count)
            assertEquals(radius, grid.radiusMeters)
            assertEquals(spacing, grid.spacingMeters)
            assertEquals(-extent, grid.point(0)!!.eastMeters)
            assertEquals(extent, grid.point(0)!!.northMeters)
            assertEquals(-extent, grid.point(expected - 1)!!.northMeters)
            assertEquals(CoverageGridPointState.nearSite, grid.point(expected / 2)!!.state)
            assertNull(grid.point(-1))
            assertNull(grid.point(expected))
        }
        val grid = CoverageGrid(1000, 50, -1000, 1000, -1000, 1000)
        assertEquals(CoverageGridPointState.outsideRadius, grid.point(0)!!.state)
        assertEquals(CoverageGridPointState.eligible, grid.point(20)!!.state)
        assertEquals(250.0, grid.point(20 * 41 + 25)!!.distanceMeters, 0.0)
        assertEquals(CoverageGridPointState.eligible, grid.point(20 * 41 + 25)!!.state)
    }
    @Test fun excessiveOrMisalignedRequestsNeverCoarsenSilently() {
        fun failure(expected: CoverageGridFailureState, block: () -> Unit) {
            assertEquals(expected, assertThrows(CoverageGridFailure::class.java, block).state)
        }
        failure(CoverageGridFailureState.cellBudget) { CoverageGrid(100_000, 200, -100_000, 100_000, -100_000, 100_000) }
        failure(CoverageGridFailureState.unalignedExtent) { CoverageGrid(1000, 200, -999, 1000, -1000, 1000) }
        failure(CoverageGridFailureState.invalidBounds) { CoverageGrid(1000, 200, Int.MIN_VALUE, Int.MAX_VALUE, -1000, 1000) }
        failure(CoverageGridFailureState.invalidParameters) { CoverageGrid(200_000, 200, -1000, 1000, -1000, 1000) }
    }
}
