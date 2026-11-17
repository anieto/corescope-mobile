package org.nodescope.android

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.nodescope.android.core.design.formatDistance
import org.nodescope.android.core.storage.AppPreferences
import org.nodescope.android.core.storage.DistanceUnit
import org.nodescope.android.feature.linkprofile.formatProfileHeight

/** Mirrors CoreScopeViewerTests/DistanceFormatTests.swift. */
class DistanceFormatTest {
    private val us = Locale.US
    private val brazil = Locale.forLanguageTag("pt-BR")

    @Test fun imperialUsesMilesAndFeetForShortLinks() {
        assertEquals("6.2 mi", formatDistance(10.0, DistanceUnit.IMPERIAL, us))
        assertEquals("0.1 mi", formatDistance(0.17, DistanceUnit.IMPERIAL, us))
        assertEquals("490 ft", formatDistance(0.15, DistanceUnit.IMPERIAL, us))
        assertEquals("0 ft", formatDistance(0.0, DistanceUnit.IMPERIAL, us))
    }

    @Test fun metricUsesKilometersAndMetersForShortLinks() {
        assertEquals("10.0 km", formatDistance(10.0, DistanceUnit.METRIC, us))
        assertEquals("1.0 km", formatDistance(1.0, DistanceUnit.METRIC, us))
        assertEquals("350 m", formatDistance(0.347, DistanceUnit.METRIC, us))
    }

    @Test fun ukUsesMilesAndMetersForShortLinks() {
        val gb = Locale.UK
        assertEquals("6.2 mi", formatDistance(10.0, DistanceUnit.UK, gb))
        assertEquals("150 m", formatDistance(0.15, DistanceUnit.UK, gb))
        assertEquals("6 m", formatProfileHeight(6.0, DistanceUnit.UK, gb))
        assertTrue(DistanceUnit.UK.usesMiles)
        assertFalse(DistanceUnit.UK.usesFeet)
    }

    @Test fun decimalsFollowTheLocale() {
        assertEquals("12,3 km", formatDistance(12.34, DistanceUnit.METRIC, brazil))
        assertEquals("6,2 mi", formatDistance(10.0, DistanceUnit.IMPERIAL, brazil))
    }

    @Test fun defaultFollowsTheRegion() {
        assertEquals(DistanceUnit.IMPERIAL, DistanceUnit.defaultUnit(us))
        assertEquals(DistanceUnit.UK, DistanceUnit.defaultUnit(Locale.UK))
        assertEquals(DistanceUnit.METRIC, DistanceUnit.defaultUnit(brazil))
        assertEquals(DistanceUnit.METRIC, DistanceUnit.defaultUnit(Locale.GERMANY))
    }

    @Test fun aStoredChoiceWinsAndAnAbsentChoiceFollowsRegionChanges() {
        val previous = Locale.getDefault()
        val display = Locale.getDefault(Locale.Category.DISPLAY)
        val format = Locale.getDefault(Locale.Category.FORMAT)
        try {
            val automatic = AppPreferences()
            val manual = AppPreferences(storedDistanceUnit = DistanceUnit.IMPERIAL)
            Locale.setDefault(Locale.UK)
            assertEquals(DistanceUnit.UK, automatic.distanceUnit)
            Locale.setDefault(brazil)
            assertEquals(DistanceUnit.METRIC, automatic.distanceUnit)
            assertEquals(DistanceUnit.IMPERIAL, manual.distanceUnit)
        } finally {
            Locale.setDefault(previous)
            Locale.setDefault(Locale.Category.DISPLAY, display)
            Locale.setDefault(Locale.Category.FORMAT, format)
        }
    }
}
