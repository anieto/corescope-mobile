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
    @Test fun geographicPathsMatchCardinalsAndWrapDateLine() {
        val origin = CoverageTerrainCoordinate(0.0, 179.999)
        val degrees = 1000 / CoverageTerrainFootprint.EARTH_RADIUS_METERS * 180 / kotlin.math.PI
        for ((east, north, bearing) in listOf(Triple(1000, 0, 90.0), Triple(-1000, 0, 270.0), Triple(0, 1000, 0.0), Triple(0, -1000, 180.0))) {
            val grid = CoverageGrid(1000, 50, east, east, north, north)
            val path = requireNotNull(grid.path(0, origin))
            assertEquals(bearing, path.bearingDegrees, 0.0)
            assertEquals(1000.0, path.distanceMeters, 0.0)
            val end = path.point(1000.0)
            val longitude = origin.longitude + east / 1000.0 * degrees
            val wrapped = longitude - 360 * kotlin.math.floor((longitude + 180) / 360)
            assertEquals(wrapped, end.longitude, 1e-9)
            assertEquals(north / 1000.0 * degrees, end.latitude, 1e-9)
        }
    }
    @Test fun excludedAndUnsupportedGridPathsRemainDistinct() {
        val grid = CoverageGrid(1000, 1000, -1000, 1000, -1000, 1000)
        val origin = CoverageTerrainCoordinate(0.0, 0.0)
        assertNull(grid.path(0, origin))
        assertNull(grid.path(4, origin))
        assertThrows(CoverageGridFailure::class.java) { grid.path(-1, origin) }
        assertThrows(CoverageGridFailure::class.java) { grid.path(1, CoverageTerrainCoordinate(90.0, 0.0)) }
        val failure = assertThrows(CoverageTerrainProfileFailure::class.java) { grid.path(1, CoverageTerrainCoordinate(85.0, 0.0)) }
        assertEquals(CoverageTerrainProfileState.ACCURACY_UNRESOLVED, failure.state)
    }

}
