package org.nodescope.android

import org.junit.Assert.*
import org.junit.Test
import org.nodescope.android.core.model.Coordinate
import org.nodescope.android.feature.map.regionFramingPositions

class RegionFramingTest {
    @Test fun gulfOutlierDoesNotExpandTexasRegionFraming() {
        val local = listOf(Coordinate(29.64, -98.98), Coordinate(29.8, -98.7), Coordinate(30.0, -98.5), Coordinate(29.7, -98.9))
        val gulf = Coordinate(23.322080, -87.451171)
        assertEquals(local, regionFramingPositions(listOf(gulf) + local))
    }
    @Test fun smallSamplesAndSeparateEqualSizedGroupsAreNotDiscarded() {
        val pair = listOf(Coordinate(30.0, -97.0), Coordinate(40.0, -75.0))
        assertEquals(pair, regionFramingPositions(pair))
        assertEquals(pair, regionFramingPositions(pair + pair))
        assertTrue(regionFramingPositions(emptyList()).isEmpty())
    }
    @Test fun worksOutsideTexasAndAcrossDateLine() {
        val local = listOf(Coordinate(-17.7, 179.8), Coordinate(-17.8, -179.9), Coordinate(-17.6, 179.9))
        assertEquals(local, regionFramingPositions(local + Coordinate(10.0, 100.0)))
    }
    @Test fun colocatedNodesStillEstablishAMajority() {
        val local = Coordinate(30.0, -97.0)
        assertEquals(listOf(local), regionFramingPositions(List(10) { local } + Coordinate(0.1, 0.1)))
    }
}
