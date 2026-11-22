package org.nodescope.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.nodescope.android.feature.nodevision.VisionHeadingWarmup
import org.nodescope.android.core.model.FieldCardinalDirection
import org.nodescope.android.core.model.FieldCoordinate
import org.nodescope.android.core.model.FieldGeometry

/** Mirrors iOS `FieldGeometryTests` and the canonical geometry-v1 fixture. */
class FieldGeometryTest {
    @Test fun calculatesDistanceAndInitialBearing() {
        val austin = requireNotNull(FieldCoordinate.create(30.2672, -97.7431))
        val dallas = requireNotNull(FieldCoordinate.create(32.7767, -96.7970))

        val result = FieldGeometry.geometry(austin, dallas)

        assertEquals(293_095.4775595935, result.distanceMeters, 0.001)
        assertEquals(17.57049352937031, requireNotNull(result.initialBearingDegrees), 0.000_001)
        assertEquals(FieldCardinalDirection.NORTH_NORTHEAST, result.cardinalDirection)
    }

    @Test fun crossesTheDateLineByTheShortPath() {
        val west = requireNotNull(FieldCoordinate.create(0.0, 179.9))
        val east = requireNotNull(FieldCoordinate.create(0.0, -179.9))

        val result = FieldGeometry.geometry(west, east)

        assertEquals(22_239.016046706758, result.distanceMeters, 0.001)
        assertEquals(90.0, requireNotNull(result.initialBearingDegrees), 0.0)
        assertEquals(FieldCardinalDirection.EAST, result.cardinalDirection)
    }

    @Test fun sameCoordinateHasNoDirection() {
        val coordinate = requireNotNull(FieldCoordinate.create(30.0, -97.0))

        val result = FieldGeometry.geometry(coordinate, coordinate)

        assertEquals(0.0, result.distanceMeters, 0.0)
        assertNull(result.initialBearingDegrees)
        assertNull(result.cardinalDirection)
    }

    @Test fun validatesCoordinateRangesButAllowsNullIsland() {
        assertTrue(FieldCoordinate.create(0.0, 0.0) != null)
        assertNull(FieldCoordinate.create(91.0, 0.0))
        assertNull(FieldCoordinate.create(0.0, -181.0))
        assertNull(FieldCoordinate.create(Double.NaN, 0.0))
    }

    @Test fun normalizesCardinalBearings() {
        assertEquals(FieldCardinalDirection.NORTH, FieldGeometry.cardinalDirection(0.0))
        assertEquals(FieldCardinalDirection.NORTH_NORTHEAST, FieldGeometry.cardinalDirection(11.25))
        assertEquals(FieldCardinalDirection.WEST, FieldGeometry.cardinalDirection(-90.0))
        assertEquals(FieldCardinalDirection.NORTH, FieldGeometry.cardinalDirection(360.0))
    }

    @Test fun rotatesTrueBearingRelativeToDeviceHeading() {
        assertEquals(30.0, requireNotNull(FieldGeometry.relativeHeadingRotation(20.0, 350.0)), 0.0)
        assertEquals(330.0, requireNotNull(FieldGeometry.relativeHeadingRotation(350.0, 20.0)), 0.0)
        assertEquals(0.0, requireNotNull(FieldGeometry.relativeHeadingRotation(90.0, 90.0)), 0.0)
        assertNull(FieldGeometry.relativeHeadingRotation(Double.NaN, 0.0))
    }

    @Test
    fun showsCameraOffsetsOnTheCorrectSideAcrossNorth() {
        val right = requireNotNull(FieldGeometry.cameraHorizontalOffset(10.0, 350.0, 0.5))
        val left = requireNotNull(FieldGeometry.cameraHorizontalOffset(350.0, 10.0, 0.5))
        assertEquals(0.72794, right, 0.00001)
        assertEquals(right, -left, 0.00001)
        assertEquals(1.45588, requireNotNull(FieldGeometry.cameraHorizontalOffset(10.0, 350.0, 0.25)), 0.00001)
        assertEquals(Double.NEGATIVE_INFINITY, FieldGeometry.cameraHorizontalOffset(230.0, 0.0, 0.5))
    }

    @Test
    fun projectsAntennaElevationAndRejectsUncertainty() {
        val level = requireNotNull(FieldGeometry.cameraVerticalOffset(500.0, 160.0, 100.0, 5.0, 0.0, 0.5))
        val tilted = requireNotNull(FieldGeometry.cameraVerticalOffset(500.0, 160.0, 100.0, 5.0, 10.0, 0.5))
        assertTrue(FieldGeometry.cameraVerticalOffset(16_737.0, 203.0, 288.8, 30.0, 0.0, 0.5) != null)
        assertNull(FieldGeometry.cameraVerticalOffset(46.0, 291.6, 288.8, 30.0, 0.0, 0.5))
        assertTrue(level > 0 && tilted < 0)
        assertTrue(FieldGeometry.cameraVerticalOffset(46.0, 292.0, 285.0, 5.0, 0.0, 0.5) != null)
        assertTrue(FieldGeometry.cameraElevationUncertainty(46.0, 5.0) > 8)
        assertNull(FieldGeometry.cameraVerticalOffset(30.0, 160.0, 100.0, 5.0, 0.0, 0.5))
        assertNull(FieldGeometry.cameraVerticalOffset(50.0, 160.0, 100.0, 20.0, 0.0, 0.5))
    }
    @Test
    fun bearingGuideMovesOppositeCameraTiltWithoutAltitude() {
        val level = FieldGeometry.cameraBearingGuideOffset(0.0, 0.5)
        val up = FieldGeometry.cameraBearingGuideOffset(10.0, 0.5)
        val down = FieldGeometry.cameraBearingGuideOffset(-10.0, 0.5)
        assertEquals(0.14, level, 0.00001)
        assertTrue(up < 0 && down > level)
    }

    @Test
    fun headingWarmupAcceptsStableReadingsAcrossNorthAndRestartsAfterLoss() {
        val warmup = VisionHeadingWarmup()
        assertNull(warmup.settledHeading(40.0, 0))
        assertNull(warmup.settledHeading(359.0, 70))
        assertNull(warmup.settledHeading(0.0, 140))
        assertNull(warmup.settledHeading(1.0, 210))
        assertNull(warmup.settledHeading(0.0, 280))
        assertEquals(359.8, requireNotNull(warmup.settledHeading(359.0, 350)), 0.001)
        assertEquals(20.0, requireNotNull(warmup.settledHeading(20.0, 420)), 0.001)
        assertNull(warmup.settledHeading(20.0, 700))
        warmup.reset()
        assertNull(warmup.settledHeading(20.0, 770))
    }
}
