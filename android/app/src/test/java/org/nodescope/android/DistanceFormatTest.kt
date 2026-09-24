package org.nodescope.android

import org.junit.Assert.assertEquals
import org.junit.Test
import org.nodescope.android.core.design.formatDistance
import org.nodescope.android.core.storage.AppPreferences
import org.nodescope.android.core.storage.DistanceUnit

class DistanceFormatTest {
    @Test fun imperialIsTheDefault() {
        assertEquals(DistanceUnit.IMPERIAL, AppPreferences().distanceUnit)
    }

    @Test fun imperialUsesMilesAndFeetForShortLinks() {
        assertEquals("6.2 mi", formatDistance(10.0, DistanceUnit.IMPERIAL))
        assertEquals("0.1 mi", formatDistance(0.17, DistanceUnit.IMPERIAL))
        assertEquals("490 ft", formatDistance(0.15, DistanceUnit.IMPERIAL)) // 0.093 mi
        assertEquals("0 ft", formatDistance(0.0, DistanceUnit.IMPERIAL))
    }

    @Test fun metricUsesKilometersAndMetersForShortLinks() {
        assertEquals("10.0 km", formatDistance(10.0, DistanceUnit.METRIC))
        assertEquals("1.0 km", formatDistance(1.0, DistanceUnit.METRIC))
        assertEquals("350 m", formatDistance(0.347, DistanceUnit.METRIC))
    }
}
