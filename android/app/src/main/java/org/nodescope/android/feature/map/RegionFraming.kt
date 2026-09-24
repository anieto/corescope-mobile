package org.nodescope.android.feature.map

import org.nodescope.android.core.model.Coordinate
import org.nodescope.android.core.model.RegionCoordinate
import kotlin.math.*

/** Only camera fitting uses this selection; nodes and route geometry remain untouched. */
internal fun regionFramingPositions(positions: List<Coordinate>): List<Coordinate> {
    if (positions.size < 3) return positions.distinct() // Too little evidence to call either point an outlier.
    fun median(values: List<Double>): Double {
        val sorted = values.sorted()
        return (sorted[(sorted.size - 1) / 2] + sorted[sorted.size / 2]) / 2
    }
    val reference = positions.first().longitude
    // Unwrap around a reference so a region crossing the date line has a local center.
    val longitude = median(positions.map { reference + ((it.longitude - reference + 540) % 360) - 180 })
    val latitude = median(positions.map { it.latitude })
    val distances = positions.map {
        val a = sin(Math.toRadians(it.latitude - latitude) / 2).pow(2) +
            cos(Math.toRadians(latitude)) * cos(Math.toRadians(it.latitude)) *
            sin(Math.toRadians(it.longitude - longitude) / 2).pow(2)
        6371 * 2 * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }
    val typical = median(distances)
    val deviation = median(distances.map { abs(it - typical) })
    // A generous 50 km margin avoids treating ordinary regional spread as bad GPS.
    val cutoff = typical + max(50.0, 4.5 * deviation)
    val inliers = positions.filterIndexed { index, _ -> distances[index] <= cutoff }
    return (if (inliers.size > positions.size / 2) inliers else positions).distinct()
}

internal sealed interface CameraTarget {
    data class Bounds(val north: Double, val east: Double, val south: Double, val west: Double) : CameraTarget
    data class Center(val coordinate: Coordinate, val zoom: Double) : CameraTarget
}

private fun Collection<CameraTarget.Bounds>.union() = CameraTarget.Bounds(maxOf { it.north }, maxOf { it.east }, minOf { it.south }, minOf { it.west })

internal fun regionBounds(region: RegionCoordinate): CameraTarget.Bounds {
    val (latitudeSpan, longitudeSpan) = regionSpan(region)
    val latitude = region.lat.coerceIn(-85.0, 85.0)
    return CameraTarget.Bounds((latitude + latitudeSpan / 2).coerceAtMost(85.0), region.lon + longitudeSpan / 2,
        (latitude - latitudeSpan / 2).coerceAtLeast(-85.0), region.lon - longitudeSpan / 2)
}

/**
 * A selected region frames its center; its node list is not used first because a region's
 * nodes are the ones its observers *heard*, which can lie far outside it.
 * "All regions" frames, in order: the community viewport from the source registry, every
 * region the analyzer defines, the analyzer's map default, then any known node.
 */
internal fun cameraTarget(snapshot: org.nodescope.android.core.model.AnalyzerSnapshot, selectedRegion: String?,
    sourceViewport: RegionCoordinate? = null): CameraTarget? {
    if (selectedRegion != null) {
        snapshot.regionCoordinates.entries.firstOrNull { it.key.equals(selectedRegion, true) }?.value
            ?.takeIf { it.coordinate != null }?.let { return regionBounds(it) }
        val positions = regionFramingPositions(snapshot.nodes.mapNotNull { it.coordinate })
        return when (positions.size) {
            0 -> null
            1 -> CameraTarget.Center(positions[0], 9.0)
            else -> CameraTarget.Bounds(positions.maxOf { it.latitude }, positions.maxOf { it.longitude },
                positions.minOf { it.latitude }, positions.minOf { it.longitude })
        }
    }
    sourceViewport?.takeIf { it.coordinate != null }?.let { return regionBounds(it) }
    val regions = snapshot.regions.keys.mapNotNull { code ->
        snapshot.regionCoordinates.entries.firstOrNull { it.key.equals(code, true) }?.value?.takeIf { it.coordinate != null }
    }
    if (regions.size >= 2) return regions.map(::regionBounds).union()
    snapshot.mapDefaults?.coordinate?.let { return CameraTarget.Center(it, snapshot.mapDefaults.zoom.coerceIn(1.0, 20.0)) }
    return snapshot.nodes.firstNotNullOfOrNull { it.coordinate }?.let { CameraTarget.Center(it, 7.0) }
}
