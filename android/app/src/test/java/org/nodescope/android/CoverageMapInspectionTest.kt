package org.nodescope.android

import org.junit.Assert.*
import org.junit.Test
import org.nodescope.android.core.model.Coordinate
import org.nodescope.android.feature.coverage.*
import org.nodescope.android.feature.map.MapPin

class CoverageMapInspectionTest {
    private val point = requireNotNull(Coordinate.valid(40.0, -90.0))
    private val budget = CoverageLinkBudget(20.0, 2.0, 2.0, 1.0, 1.0, -120.0, 10.0)

    @Test fun qualifiedSampleUsesCompletedBudgetAndKeepsGridSpacing() {
        val card = requireNotNull(coverageSampleInspection(
            CoverageRasterSample(CoverageRasterClass.limitedHeadroom, 130.0, 2.0), budget, point, 500))
        assertEquals(-108.0, requireNotNull(card.receiveDbm), 0.0001)
        assertEquals(2.0, requireNotNull(card.marginDb), 0.0001)
        assertEquals(500, card.spacingMeters)
        assertEquals(point, card.pin?.coordinate)
        assertEquals(MapPin.Kind.COVERAGE_LIMITED, card.pin?.kind)
        assertNull(card.status)
    }

    @Test fun unknownSamplesNeverShowReceiverValues() {
        for (state in listOf(CoverageRasterClass.unresolved, CoverageRasterClass.unsupportedNearSite)) {
            val card = requireNotNull(coverageSampleInspection(CoverageRasterSample(state), budget, point, 1000))
            assertEquals("No usable prediction", card.status)
            assertNull(card.receiveDbm)
            assertNull(card.marginDb)
            assertEquals(MapPin.Kind.COVERAGE_UNKNOWN, card.pin?.kind)
        }
    }

    @Test fun outsideStudyDismissesSelection() {
        assertNull(coverageSampleInspection(CoverageRasterSample(CoverageRasterClass.outsideAnalysis), budget, point, 500))
    }
}
