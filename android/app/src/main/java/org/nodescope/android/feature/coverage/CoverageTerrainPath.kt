package org.nodescope.android.feature.coverage

import kotlin.math.*

data class CoverageTerrainCoordinate(val latitude: Double, val longitude: Double)
data class CoverageTerrainSampledPath(val profile: CoverageTerrainProfile, val geometryEvaluations: Int)
data class CoverageTerrainCrossings(val distancesMeters: List<Double>, val geometryEvaluations: Int)

/** Short spherical paths in the reviewed profile domain, with internally unwrapped longitude. */
class CoverageTerrainPath(val latitude: Double, longitude: Double,
                          val bearingDegrees: Double, val distanceMeters: Double) {
    val longitude = if (longitude == 180.0) -180.0 else longitude
    private val radians = PI / 180
    init {
        if (!latitude.isFinite() || !longitude.isFinite() || !bearingDegrees.isFinite() || !distanceMeters.isFinite() ||
            latitude !in -85.0..85.0 || longitude !in -180.0..180.0 ||
            bearingDegrees < 0 || bearingDegrees >= 360 || distanceMeters !in 0.001..100_000.0) {
            throw CoverageTerrainProfileFailure(CoverageTerrainProfileState.INVALID_INPUT)
        }
    }
    private fun unwrappedPoint(distance: Double): CoverageTerrainCoordinate {
        if (!distance.isFinite() || distance !in 0.0..distanceMeters) throw CoverageTerrainProfileFailure(CoverageTerrainProfileState.INVALID_INPUT)
        val phi = latitude * radians
        val theta = bearingDegrees * radians
        val arc = distance / CoverageTerrainFootprint.EARTH_RADIUS_METERS
        val lat = asin((sin(phi) * cos(arc) + cos(phi) * sin(arc) * cos(theta)).coerceIn(-1.0, 1.0))
        val lon = longitude + atan2(sin(theta) * sin(arc) * cos(phi), cos(arc) - sin(phi) * sin(lat)) / radians
        if (abs(lat / radians) > 85) throw CoverageTerrainProfileFailure(CoverageTerrainProfileState.ACCURACY_UNRESOLVED)
        return CoverageTerrainCoordinate(lat / radians, lon)
    }
    fun point(distance: Double): CoverageTerrainCoordinate {
        val point = unwrappedPoint(distance)
        return CoverageTerrainCoordinate(point.latitude, point.longitude - 360 * floor((point.longitude + 180) / 360))
    }
    fun crossings(intervalsPerDegree: Int, geometryLimit: Int = 200_000,
                  cancelled: () -> Boolean = { false }): CoverageTerrainCrossings {
        fun fail(state: CoverageTerrainProfileState): Nothing = throw CoverageTerrainProfileFailure(state)
        if (intervalsPerDegree !in 1..3600 || 3600 % intervalsPerDegree != 0 || geometryLimit !in 1..200_000) {
            fail(CoverageTerrainProfileState.INVALID_INPUT)
        }
        fun check() { if (cancelled()) fail(CoverageTerrainProfileState.CANCELLED) }
        var evaluations = 0
        fun coordinate(distance: Double, axis: Int): Double {
            check()
            if (evaluations >= geometryLimit) fail(CoverageTerrainProfileState.WORK_LIMIT)
            evaluations++
            val point = unwrappedPoint(distance)
            return (if (axis == 0) point.longitude else point.latitude) * intervalsPerDegree
        }
        val phi = latitude * radians
        val theta = bearingDegrees * radians
        val critical = atan2(cos(phi) * cos(theta), sin(phi))
        val splits = mutableListOf(0.0, distanceMeters)
        for (k in -1..1) {
            val position = (critical + k * PI) * CoverageTerrainFootprint.EARTH_RADIUS_METERS
            if (position > 0 && position < distanceMeters) splits.add(position)
        }
        splits.sort()
        val positions = splits.toMutableList()
        for (axis in 0..1) {
            for (index in 0 until splits.size - 1) {
                check()
                val start = splits[index]; val end = splits[index + 1]
                val a = coordinate(start, axis); val b = coordinate(end, axis)
                if (a == b) continue
                val first = ceil(min(a, b)).toInt(); val last = floor(max(a, b)).toInt()
                if (first > last) continue
                if (last - first + 1 > 50_000 - positions.size) fail(CoverageTerrainProfileState.WORK_LIMIT)
                for (line in first..last) {
                    var low = start; var high = end
                    while (high - low > 0.0001) {
                        val middle = (low + high) / 2
                        val value = coordinate(middle, axis)
                        if ((value < line) == (b > a)) low = middle else high = middle
                    }
                    positions.add((low + high) / 2)
                }
            }
        }
        check()
        val endKey = (distanceMeters * 1000).roundToLong()
        val result = positions.map { (it * 1000).roundToLong() }.distinct().sorted()
            .map { if (it == endKey) distanceMeters else it / 1000.0 }
        return CoverageTerrainCrossings(result, evaluations)
    }
}
