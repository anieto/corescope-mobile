package org.nodescope.android

import kotlin.math.*
import org.junit.Assert.*
import org.junit.Test
import org.nodescope.android.feature.coverage.*

class CoverageTerrainProfileTest {
    private fun failure(state: CoverageTerrainProfileState, block: () -> Unit) {
        assertEquals(state, assertThrows(CoverageTerrainProfileFailure::class.java, block).state)
    }
    @Test fun retainsNarrowRidgeAndWithinCellPeak() {
        val ridge = CoverageTerrainProfileSampler.build(1000.0, listOf(0.0, 400.0, 400.75, 401.5, 1000.0), 30.0) {
            200 + 100 * max(0.0, 1 - abs(it - 400.75) / 0.75)
        }
        assertTrue(ridge.distancesMeters.contains(400.75))
        assertEquals(300.0, ridge.elevationsMeters.max(), 0.0)
        val quadratic = CoverageTerrainProfileSampler.build(30.0, listOf(0.0, 30.0), 30.0) { 200 - 0.1 * (it - 13.7).pow(2) }
        assertEquals(listOf(0.0, 13.7, 30.0), quadratic.distancesMeters)
        assertEquals(200.0, quadratic.elevationsMeters[1], 0.0)
        assertEquals(8, quadratic.terrainEvaluations)
    }
    @Test fun exactEndpointAndMillimeterDeduplication() {
        val distance = 30.0006
        val profile = CoverageTerrainProfileSampler.build(distance, listOf(0.0, 0.0001, 0.0002, distance), 30.0) {
            assertTrue(it <= distance); -12.0
        }
        assertEquals(0.0, profile.distancesMeters.first(), 0.0)
        assertEquals(distance, profile.distancesMeters.last(), 0.0)
        assertEquals(profile.distancesMeters.size, profile.distancesMeters.toSet().size)
        assertTrue(profile.elevationsMeters.all { it == -12.0 })
    }
    @Test fun unresolvedFitMissingDataAndBudgetNeverReturnPartialProfile() {
        failure(CoverageTerrainProfileState.ACCURACY_UNRESOLVED) {
            CoverageTerrainProfileSampler.build(30.0, listOf(0.0, 30.0), 30.0) { it.pow(3) }
        }
        failure(CoverageTerrainProfileState.MISSING_TERRAIN) {
            CoverageTerrainProfileSampler.build(30.0, listOf(0.0, 30.0), 30.0) { null }
        }
        var calls = 0
        failure(CoverageTerrainProfileState.WORK_LIMIT) {
            CoverageTerrainProfileSampler.build(30.0, listOf(0.0, 30.0), 30.0, sampleLimit = 4) { calls++; 0.0 }
        }
        assertEquals(4, calls)
        failure(CoverageTerrainProfileState.INVALID_INPUT) {
            CoverageTerrainProfileSampler.build(30.0, listOf(0.0, 30.0), 30.0) { Double.POSITIVE_INFINITY }
        }
    }
    @Test fun cancellationAtEveryCheckpointAndInvalidOrdering() {
        var checkpoints = 0
        CoverageTerrainProfileSampler.build(30.0, listOf(0.0, 30.0), 10.0, cancelled = { checkpoints++; false }) { 0.0 }
        for (stop in 1..checkpoints) {
            var count = 0
            failure(CoverageTerrainProfileState.CANCELLED) {
                CoverageTerrainProfileSampler.build(30.0, listOf(0.0, 30.0), 10.0, cancelled = { ++count == stop }) { 0.0 }
            }
        }
        for (crossings in listOf(listOf(0.0, 20.0, 10.0, 30.0), listOf(0.0, 10.0, 10.0, 30.0),
            listOf(1.0, 30.0), listOf(0.0, Double.NaN, 30.0))) {
            failure(CoverageTerrainProfileState.INVALID_INPUT) {
                CoverageTerrainProfileSampler.build(30.0, crossings, 10.0) { 0.0 }
            }
        }
    }
}
