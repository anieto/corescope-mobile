package org.nodescope.android

import org.junit.Assert.*
import org.junit.Test
import org.nodescope.android.core.storage.DistanceUnit
import org.nodescope.android.feature.linkprofile.*

class LinkProfileUnitsTest {
    @Test fun convertsImperialSliderHeightsToMeters() {
        assertEquals(15.24, profileHeightMeters(50.0, DistanceUnit.IMPERIAL), 1e-10)
        assertEquals(100.0, profileDisplayHeight(30.48, DistanceUnit.IMPERIAL), 1e-10)
        assertEquals(10.0, profileHeightMeters(profileDisplayHeight(10.0, DistanceUnit.IMPERIAL), DistanceUnit.IMPERIAL), 1e-10)
    }

    @Test fun metricDisplayPreservesPhysicalHeightIncludingImperialMaximum() {
        // Viewing a saved 100-foot antenna in meters must not truncate it to the 30 m slider maximum.
        assertEquals(30.48, profileDisplayHeight(30.48, DistanceUnit.METRIC), 0.0)
        assertEquals(15.0, profileHeightMeters(15.0, DistanceUnit.METRIC), 0.0)
        assertEquals(30f, profileHeightMaximum(DistanceUnit.METRIC), 0f)
        assertEquals(100f, profileHeightMaximum(DistanceUnit.IMPERIAL), 0f)
    }
}
