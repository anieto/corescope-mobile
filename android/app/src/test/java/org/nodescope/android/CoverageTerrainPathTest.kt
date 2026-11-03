package org.nodescope.android

import kotlin.math.*
import org.junit.Assert.*
import org.junit.Test
import org.nodescope.android.feature.coverage.*

class CoverageTerrainPathTest {
    @Test fun equatorialCrossingsMatchAnalyticDistances() {
        val path = CoverageTerrainPath(0.0, -0.0005, 90.0, 200.0)
        val result = path.crossings(3600)
        val metersPerDegree = CoverageTerrainFootprint.EARTH_RADIUS_METERS * PI / 180
        for (line in -1..4) {
            val distance = (line / 3600.0 + 0.0005) * metersPerDegree
            assertTrue(result.distancesMeters.any { abs(it - distance) <= 0.0006 })
        }
        assertEquals(0.0, result.distancesMeters.first(), 0.0)
        assertEquals(200.0, result.distancesMeters.last(), 0.0)
        assertTrue(result.geometryEvaluations > 0)
    }

    @Test fun cellSegmentsCoverDateLineAndLatitudeTurningPoint() {
        for ((lat, lon, bearing) in listOf(listOf(0.5, 179.99, 90.0), listOf(-0.5, -179.99, 270.0),
            listOf(60.0, 10.0, 89.9), listOf(-30.0, 10.0, 180.0))) {
            val path = CoverageTerrainPath(lat, lon, bearing, 20_000.0)
            val crossings = path.crossings(120)
            for (index in 0 until crossings.distancesMeters.size - 1) {
                val a = crossings.distancesMeters[index]
                val b = crossings.distancesMeters[index + 1]
                if (b - a < 0.002) continue
                val cells = listOf(0.1, 0.5, 0.9).map { t ->
                    val point = path.point(a + (b - a) * t)
                    floor(point.latitude * 120).toInt() to floor(point.longitude * 120).toInt()
                }.toSet()
                assertEquals(1, cells.size)
            }
            if (lat == 60.0) {
                val critical = atan2(cos(lat * PI / 180) * cos(bearing * PI / 180), sin(lat * PI / 180)) *
                    CoverageTerrainFootprint.EARTH_RADIUS_METERS
                assertTrue(crossings.distancesMeters.any { abs(it - critical) <= 0.0006 })
            }
        }
    }

    private fun failure(state: CoverageTerrainProfileState, block: () -> Unit) {
        assertEquals(state, assertThrows(CoverageTerrainProfileFailure::class.java, block).state)
    }
    @Test fun workCancellationAndUnsupportedDomainFailExplicitly() {
        val path = CoverageTerrainPath(0.0, 0.0, 45.0, 1000.0)
        failure(CoverageTerrainProfileState.WORK_LIMIT) { path.crossings(3600, geometryLimit = 1) }
        failure(CoverageTerrainProfileState.CANCELLED) { path.crossings(120, cancelled = { true }) }
        failure(CoverageTerrainProfileState.INVALID_INPUT) { path.crossings(7) }
        failure(CoverageTerrainProfileState.INVALID_INPUT) { CoverageTerrainPath(89.0, 0.0, 0.0, 100.0) }
        failure(CoverageTerrainProfileState.ACCURACY_UNRESOLVED) { CoverageTerrainPath(84.99, 0.0, 0.0, 2000.0).crossings(120) }
        var checks = 0
        path.crossings(1, cancelled = { checks++; false })
        for (stop in 1..checks) {
            var count = 0
            failure(CoverageTerrainProfileState.CANCELLED) { path.crossings(1, cancelled = { ++count == stop }) }
        }
    }
}
