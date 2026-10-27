package org.nodescope.android

import org.junit.Assert.*
import org.junit.Test
import org.nodescope.android.feature.linkprofile.*

class LinkProfileEngineTest {
    private fun request(midpoint: Double = 0.0, height: Double = 50.0) = PathRequest(
        listOf(ElevationPoint(0.0, 0.0), ElevationPoint(5_000.0, midpoint), ElevationPoint(10_000.0, 0.0)), height, height, 915.0)

    // Golden values from the iOS NodePlannerRFEngineTests.
    @Test fun matchesIosGoldenValues() {
        assertEquals(0.32764203060109287, LinkProfileEngine.wavelength(915.0), 1e-9)
        assertEquals(28.620011818703574, LinkProfileEngine.fresnelRadius(915.0, 5_000.0, 5_000.0), 1e-9)
        assertEquals(1.4715115366504474, LinkProfileEngine.earthBulge(5_000.0, 5_000.0, 4.0 / 3.0), 1e-9)
        assertEquals(6.032852208563606, LinkProfileEngine.diffractionLoss(0.0, 5_000.0, 5_000.0, 915.0), 1e-9)
        val clear = LinkProfileEngine.analyze(request())
        assertEquals(111.67842188132899, clear.totalPathLossDB, 1e-8)
        assertEquals(169.5613851271561, clear.worstClearancePercent, 1e-8)
        assertEquals(ClearanceStatus.CLEAR, clear.clearanceStatus)
        assertTrue(clear.obstructions.isEmpty())
        val marginal = LinkProfileEngine.analyze(request(height = 20.0))
        assertEquals(64.73962547856438, marginal.worstClearancePercent, 1e-8)
        assertEquals(ClearanceStatus.MARGINAL, marginal.clearanceStatus)
        val blocked = LinkProfileEngine.analyze(request(30.0, 10.0))
        assertEquals(14.313515197566385, blocked.peakDiffractionLossDB, 1e-8)
        assertEquals(125.99193707889538, blocked.totalPathLossDB, 1e-8)
        assertEquals(-75.0227207195579, blocked.worstClearancePercent, 1e-8)
        assertEquals(ClearanceStatus.BLOCKED, blocked.clearanceStatus)
        assertEquals(1, blocked.obstructions.size)
        val budget = LinkProfileEngine.analyze(request().copy(linkBudget = LinkBudget(22.0, 3.0, 6.0, 1.0, 2.0, -120.0)))
        assertEquals(-83.67842188132899, requireNotNull(budget.receivePowerDBm), 1e-8)
        assertEquals(36.32157811867101, requireNotNull(budget.linkMarginDB), 1e-8)
    }

    @Test fun handlesPartialClearanceAndEndpoints() {
        assertEquals(ClearanceStatus.PARTIAL_OBSTRUCTION, LinkProfileEngine.analyze(request(height = 10.0)).clearanceStatus)
        val result = LinkProfileEngine.analyze(request().copy(profile = listOf(ElevationPoint(0.0, 0.0), ElevationPoint(100.0, 0.0))))
        assertEquals(100.0, result.worstClearancePercent, 0.0)
        assertNull(result.receivePowerDBm)
        assertNull(result.linkMarginDB)
    }

    @Test fun splitsPathIntoClearanceStretches() {
        val ridge = request(height = 50.0).copy(profile = listOf(0.0, 2_500.0, 5_000.0, 7_500.0, 10_000.0)
            .map { ElevationPoint(it, if (it == 5_000.0) 60.0 else 0.0) })
        // The ridge darkens both gaps beside it; the ends next to A and B stay clear.
        assertEquals(listOf(ClearanceStretch(0.0, 2_500.0, PathClearance.CLEAR), ClearanceStretch(2_500.0, 7_500.0, PathClearance.OBSTRUCTED),
            ClearanceStretch(7_500.0, 10_000.0, PathClearance.CLEAR)), LinkProfileEngine.clearanceStretches(ridge))
        assertEquals(listOf(ClearanceStretch(0.0, 10_000.0, PathClearance.MARGINAL)), LinkProfileEngine.clearanceStretches(request(height = 20.0)))
        assertEquals(listOf(ClearanceStretch(0.0, 10_000.0, PathClearance.CLEAR)), LinkProfileEngine.clearanceStretches(request()))
        assertEquals(PathClearance.MARGINAL, LinkProfileEngine.clearance(60.0))
        assertEquals(PathClearance.OBSTRUCTED, LinkProfileEngine.clearance(59.9))
        assertEquals(PathClearance.CLEAR, LinkProfileEngine.clearance(80.0))
    }

    @Test fun rejectsInvalidInputs() {
        val valid = request()
        listOf(
            valid.copy(profile = emptyList()),
            valid.copy(profile = listOf(ElevationPoint(0.0, 0.0), ElevationPoint(0.0, 1.0))),
            valid.copy(profile = listOf(ElevationPoint(0.0, 0.0), ElevationPoint(1.0, Double.NaN))),
            valid.copy(frequencyMHz = 0.0), valid.copy(frequencyMHz = Double.NaN),
            valid.copy(refractionK = -1.0), valid.copy(refractionK = Double.POSITIVE_INFINITY),
            valid.copy(heightAMeters = -1.0), valid.copy(heightBMeters = Double.NaN),
            valid.copy(linkBudget = LinkBudget(22.0, 0.0, 0.0, -1.0, 0.0, -120.0)),
        ).forEach { input -> assertThrows(IllegalArgumentException::class.java) { LinkProfileEngine.analyze(input) } }
    }
}
