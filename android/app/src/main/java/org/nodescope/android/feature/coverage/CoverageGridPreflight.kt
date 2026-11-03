package org.nodescope.android.feature.coverage

import kotlin.math.ceil

enum class CoverageGridPreflightFailureState { invalidBudget, pathSampleBudget, pathGeometryBudget, sampleBudget, geometryBudget, cancelled }
class CoverageGridPreflightFailure(val state: CoverageGridPreflightFailureState) : Exception(state.name)
data class CoverageGridWorkEstimate(val eligiblePoints: Int, val minimumSamples: Long, val minimumGeometry: Long,
    val maximumSamples: Long, val maximumGeometry: Long)
/** Necessary lower bounds only: acceptance does not guarantee convergence or completion. */
object CoverageGridPreflight {
    fun estimate(grid: CoverageGrid, sampleBudget: Long, geometryBudget: Long,
                 pathSampleBudget: Int = 250_000, pathGeometryBudget: Int = 200_000,
                 cancelled: () -> Boolean = { false }): CoverageGridWorkEstimate {
        fun fail(state: CoverageGridPreflightFailureState): Nothing = throw CoverageGridPreflightFailure(state)
        if (sampleBudget !in 0L..150_000_000L || geometryBudget !in 0L..150_000_000L ||
            pathSampleBudget !in 1..250_000 || pathGeometryBudget !in 1..200_000) fail(CoverageGridPreflightFailureState.invalidBudget)
        var eligible = 0
        var minimumSamples = 0L
        for (index in 0 until grid.count) {
            if (cancelled()) fail(CoverageGridPreflightFailureState.cancelled)
            val point = grid.point(index) ?: continue
            if (point.state != CoverageGridPointState.eligible) continue
            val minimum = listOf(30.0, 10.0, 5.0).sumOf { ceil(point.distanceMeters / it).toInt() + 1 }
            if (minimum > pathSampleBudget) fail(CoverageGridPreflightFailureState.pathSampleBudget)
            // Native profiles rediscover both axis endpoints on all three required passes.
            if (pathGeometryBudget < 12) fail(CoverageGridPreflightFailureState.pathGeometryBudget)
            eligible++
            minimumSamples += minimum
        }
        if (cancelled()) fail(CoverageGridPreflightFailureState.cancelled)
        val minimumGeometry = eligible.toLong() * 12
        if (minimumSamples > sampleBudget) fail(CoverageGridPreflightFailureState.sampleBudget)
        if (minimumGeometry > geometryBudget) fail(CoverageGridPreflightFailureState.geometryBudget)
        return CoverageGridWorkEstimate(eligible, minimumSamples, minimumGeometry,
            eligible.toLong() * pathSampleBudget, eligible.toLong() * pathGeometryBudget)
    }
}
