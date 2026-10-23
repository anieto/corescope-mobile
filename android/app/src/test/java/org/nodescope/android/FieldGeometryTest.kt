package org.nodescope.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
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
}
