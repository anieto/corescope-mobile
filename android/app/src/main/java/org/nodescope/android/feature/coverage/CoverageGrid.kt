package org.nodescope.android.feature.coverage

import kotlin.math.*

enum class CoverageGridFailureState { invalidParameters, invalidBounds, unalignedExtent, cellBudget }
class CoverageGridFailure(val state: CoverageGridFailureState) : Exception(state.name)
enum class CoverageGridPointState { eligible, nearSite, outsideRadius }
data class CoverageGridPoint(val eastMeters: Int, val northMeters: Int, val distanceMeters: Double, val state: CoverageGridPointState)
/** Local east/north sampling layout, not a coverage footprint or geographic raster projection. */
class CoverageGrid(val radiusMeters: Int, val spacingMeters: Int, val minEastMeters: Int, maxEastMeters: Int,
                   minNorthMeters: Int, val maxNorthMeters: Int) {
    val columns: Int
    val rows: Int
    val count: Int get() = columns * rows
    init {
        if (radiusMeters !in 1000..100_000 || spacingMeters !in listOf(50, 100, 200, 500, 1000, 2000, 5000, 10_000, 20_000)) {
            throw CoverageGridFailure(CoverageGridFailureState.invalidParameters)
        }
        if (listOf(minEastMeters, maxEastMeters, minNorthMeters, maxNorthMeters).any { it !in -radiusMeters..radiusMeters } ||
            minEastMeters > maxEastMeters || minNorthMeters > maxNorthMeters) throw CoverageGridFailure(CoverageGridFailureState.invalidBounds)
        val width = maxEastMeters - minEastMeters
        val height = maxNorthMeters - minNorthMeters
        if (width % spacingMeters != 0 || height % spacingMeters != 0) throw CoverageGridFailure(CoverageGridFailureState.unalignedExtent)
        columns = width / spacingMeters + 1
        rows = height / spacingMeters + 1
        if (count > 60_000) throw CoverageGridFailure(CoverageGridFailureState.cellBudget)
    }
    fun point(index: Int): CoverageGridPoint? {
        if (index !in 0 until count) return null
        val east = minEastMeters + (index % columns) * spacingMeters
        val north = maxNorthMeters - (index / columns) * spacingMeters
        val distance = hypot(east.toDouble(), north.toDouble())
        val state = if (distance < 250) CoverageGridPointState.nearSite else if (distance > radiusMeters) CoverageGridPointState.outsideRadius else CoverageGridPointState.eligible
        return CoverageGridPoint(east, north, distance, state)
    }
    /** Spherical distance/bearing from local offsets. Excluded samples have no RF path. */
    fun path(index: Int, transmitter: CoverageTerrainCoordinate): CoverageTerrainPath? {
        if (!transmitter.latitude.isFinite() || !transmitter.longitude.isFinite() ||
            transmitter.latitude !in -85.0..85.0 || transmitter.longitude !in -180.0..180.0) {
            throw CoverageGridFailure(CoverageGridFailureState.invalidParameters)
        }
        val point = point(index) ?: throw CoverageGridFailure(CoverageGridFailureState.invalidParameters)
        if (point.state != CoverageGridPointState.eligible) return null
        val angle = atan2(point.eastMeters.toDouble(), point.northMeters.toDouble()) * 180 / PI
        val path = CoverageTerrainPath(transmitter.latitude, transmitter.longitude,
            angle - 360 * floor(angle / 360), point.distanceMeters)
        // Crossing discovery additionally checks interior latitude extrema.
        path.point(path.distanceMeters)
        return path
    }

}
