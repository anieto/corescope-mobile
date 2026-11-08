package org.nodescope.android.feature.coverage

import kotlin.math.*

enum class CoverageTerrainProfileState { INVALID_INPUT, WORK_LIMIT, CANCELLED, MISSING_TERRAIN, ACCURACY_UNRESOLVED }
class CoverageTerrainProfileFailure(val state: CoverageTerrainProfileState) : Exception(state.name)
data class CoverageTerrainProfile(
    val distancesMeters: List<Double>, val elevationsMeters: List<Double>,
    val terrainEvaluations: Int, val featureCount: Int,
    val pathCenterLatitude: Double? = null,
)

/**
 * Native feature-profile construction. Caller supplies every DEM cell crossing and latitude extremum.
 * The 0.01 m fit check is empirical; it does not qualify RF accuracy or discover absent DEM ridges.
 */
object CoverageTerrainProfileSampler {
    fun build(distanceMeters: Double, cellCrossingsMeters: List<Double>, spacingMeters: Double,
              sampleLimit: Int = 250_000, cancelled: () -> Boolean = { false },
              elevation: (Double) -> Double?): CoverageTerrainProfile {
        fun fail(state: CoverageTerrainProfileState): Nothing = throw CoverageTerrainProfileFailure(state)
        if (!distanceMeters.isFinite() || distanceMeters !in 0.001..100_000.0 ||
            !spacingMeters.isFinite() || spacingMeters !in 1.0..30.0 ||
            sampleLimit !in 1..250_000 || cellCrossingsMeters.size !in 2..50_000 ||
            cellCrossingsMeters.first() != 0.0 || cellCrossingsMeters.last() != distanceMeters) {
            fail(CoverageTerrainProfileState.INVALID_INPUT)
        }
        var previous = Double.NEGATIVE_INFINITY
        for (position in cellCrossingsMeters) {
            if (!position.isFinite() || position !in 0.0..distanceMeters || position <= previous) fail(CoverageTerrainProfileState.INVALID_INPUT)
            previous = position
        }
        fun check() { if (cancelled()) fail(CoverageTerrainProfileState.CANCELLED) }
        var evaluations = 0
        fun height(position: Double): Double {
            check()
            if (evaluations >= sampleLimit) fail(CoverageTerrainProfileState.WORK_LIMIT)
            evaluations++
            val value = elevation(position) ?: fail(CoverageTerrainProfileState.MISSING_TERRAIN)
            if (!value.isFinite()) fail(CoverageTerrainProfileState.INVALID_INPUT)
            return value
        }
        fun key(position: Double) = (position * 1000).roundToLong()
        val end = key(distanceMeters)
        fun meters(key: Long) = if (key == end) distanceMeters else key / 1000.0
        val crossings = cellCrossingsMeters.map(::key).distinct().sorted()
        val features = crossings.toMutableSet()
        check()
        for (index in 0 until crossings.size - 1) {
            check()
            val a = meters(crossings[index])
            val b = meters(crossings[index + 1])
            if (b - a < 0.002) continue
            val h0 = height(a); val hm = height((a + b) / 2); val h1 = height(b)
            val quadratic = 2 * (h1 + h0 - 2 * hm)
            val linear = h1 - h0 - quadratic
            if (!quadratic.isFinite() || !linear.isFinite()) fail(CoverageTerrainProfileState.INVALID_INPUT)
            for (t in listOf(0.25, 0.75)) {
                val actual = height(a + (b - a) * t)
                if (abs(actual - (h0 + linear * t + quadratic * t * t)) > 0.01) fail(CoverageTerrainProfileState.ACCURACY_UNRESOLVED)
            }
            if (abs(quadratic) > 1e-7) {
                val t = -linear / (2 * quadratic)
                if (t > 0 && t < 1) features.add(key(a + (b - a) * t))
            }
            if (features.size > 50_000) fail(CoverageTerrainProfileState.WORK_LIMIT)
        }
        val featureCount = features.size
        val intervals = ceil(distanceMeters / spacingMeters).toInt()
        if (intervals + 1 > sampleLimit - evaluations) fail(CoverageTerrainProfileState.WORK_LIMIT)
        for (index in 0..intervals) {
            if (index % 32 == 0) check()
            features.add(key(distanceMeters * index / intervals))
        }
        if (features.size > sampleLimit - evaluations) fail(CoverageTerrainProfileState.WORK_LIMIT)
        val distances = features.sorted().map(::meters)
        val heights = distances.map(::height)
        check()
        return CoverageTerrainProfile(distances, heights, evaluations, featureCount)
    }
}
