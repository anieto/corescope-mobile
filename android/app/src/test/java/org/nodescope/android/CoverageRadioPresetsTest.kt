package org.nodescope.android

import org.junit.Assert.*
import org.junit.Test
import org.nodescope.android.feature.coverage.*

/** Mirrors the model-independent half of iOS `CoverageRadioPresetsTests`. */
class CoverageRadioPresetsTest {
    @Test fun sensitivityFollowsSpreadingFactorAndBandwidth() {
        // −174 + 10·log10(BW) + 6 dB noise figure + (10 − 2.5·SF), to the nearest 0.5 dB.
        assertEquals(-127.5, CoverageRadioPreset.sensitivity(7, 62.5), 0.0)
        assertEquals(-130.0, CoverageRadioPreset.sensitivity(8, 62.5), 0.0)
        assertEquals(-131.5, CoverageRadioPreset.sensitivity(11, 250.0), 0.0)
        assertEquals(-121.5, CoverageRadioPreset.sensitivity(7, 250.0), 0.0)
    }

    @Test fun presetsAreDistinctAndWithinInputLimits() {
        val ids = CoverageRadioPreset.all.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertEquals(910.525, CoverageRadioPreset.standard.frequencyMHz, 0.0)
        for (preset in CoverageRadioPreset.all) {
            assertTrue(preset.frequencyMHz in 30.0..6000.0)
            assertTrue(preset.sensitivityDbm < -100 && preset.sensitivityDbm > -140)
            assertEquals(preset, CoverageRadioPreset.preset(preset.id))
        }
        assertNull(CoverageRadioPreset.preset(null))
        assertNull(CoverageRadioPreset.preset("missing"))
        assertEquals("half-wave-dipole", CoverageAntennaPreset.matching(2.15)?.id)
        assertNull(CoverageAntennaPreset.matching(2.3))
        assertEquals(CoverageHeightPreset.ROOFTOP, CoverageHeightPreset.matching(6.096, CoverageHeightPreset.transmitter))
        assertNull(CoverageHeightPreset.matching(4.0, CoverageHeightPreset.transmitter))
    }

    @Test fun editedValuesNoLongerMatchAPreset() {
        val standard = CoverageRadioPreset.standard
        assertEquals(standard, CoverageRadioPreset.matching(910.525, 22.0, -127.5))
        assertNull(CoverageRadioPreset.matching(910.525, 30.0, -127.5))
        assertEquals("SF7 · 62.5 kHz", standard.modemSummary)
        assertEquals("SF11 · 250 kHz", CoverageRadioPreset.all.last().modemSummary)
    }
}
