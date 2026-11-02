package org.nodescope.android.feature.coverage

import kotlin.math.floor

/** Southwest corner of a one-degree HGT tile. Repository must enforce common dataset/revision/datum. */
data class CoverageTerrainTile(val south: Int, val west: Int) {
    init {
        if (south !in -90..89 || west !in -180..179) throw TerrainReadFailure(TerrainReadState.INVALID_DESCRIPTOR)
    }
    companion object {
        /** North/east tile owns a seam. +180 becomes -180; north pole uses tile 89's north edge. */
        fun containing(latitude: Double, longitude: Double): CoverageTerrainTile {
            if (!latitude.isFinite() || !longitude.isFinite() || latitude !in -90.0..90.0 || longitude !in -180.0..180.0) {
                throw TerrainReadFailure(TerrainReadState.INVALID_RANGE)
            }
            return CoverageTerrainTile(minOf(89, floor(latitude).toInt()),
                floor(if (longitude == 180.0) -180.0 else longitude).toInt())
        }
    }
}

sealed interface CoverageTerrainSample {
    data class ElevationMeters(val value: Double) : CoverageTerrainSample
    data class MissingTile(val tile: CoverageTerrainTile) : CoverageTerrainSample
    data object NoData : CoverageTerrainSample
}

/**
 * One background worker owns all readers, including on failed construction. Pinned files remain
 * immutable until close. Point interpolation does not perform terrain-profile ridge preservation.
 */
class CoverageTerrainMosaic(readers: Map<CoverageTerrainTile, LeasedTerrainReader>) : AutoCloseable {
    private val readers = readers.toMap()
    private var closed = false
    init {
        if (readers.size > MAXIMUM_TILES || readers.values.toSet().size != readers.size) {
            close()
            throw TerrainReadFailure(TerrainReadState.INVALID_DESCRIPTOR)
        }
    }
    companion object { const val MAXIMUM_TILES = 256 }

    /** Nonzero bilinear contributors only; missing owner tiles never borrow a neighbor's border. */
    fun sample(latitude: Double, longitude: Double, cancelled: () -> Boolean = { false }): CoverageTerrainSample {
        if (closed) throw TerrainReadFailure(TerrainReadState.CLOSED)
        if (cancelled()) throw TerrainReadFailure(TerrainReadState.CANCELLED)
        val tile = CoverageTerrainTile.containing(latitude, longitude)
        val reader = readers[tile] ?: return CoverageTerrainSample.MissingTile(tile)
        val canonicalLongitude = if (longitude == 180.0) -180.0 else longitude
        val intervals = reader.side - 1
        val x = ((canonicalLongitude - tile.west) * intervals).coerceIn(0.0, intervals.toDouble())
        val y = ((tile.south + 1 - latitude) * intervals).coerceIn(0.0, intervals.toDouble())
        val column = floor(x).toInt()
        val row = floor(y).toInt()
        val dx = x - column
        val dy = y - row
        var elevation = 0.0
        // At most two reads of two samples each, never a whole-tile allocation.
        for ((r, weight) in listOf(row to 1 - dy, minOf(row + 1, intervals) to dy)) {
            if (weight <= 0) continue
            val samples = reader.read(r * reader.side + column, if (dx > 0) 2 else 1, cancelled)
            if (samples[0] == Short.MIN_VALUE) return CoverageTerrainSample.NoData
            var rowElevation = samples[0] * (1 - dx)
            if (dx > 0) {
                if (samples[1] == Short.MIN_VALUE) return CoverageTerrainSample.NoData
                rowElevation += samples[1] * dx
            }
            elevation += rowElevation * weight
        }
        if (cancelled()) throw TerrainReadFailure(TerrainReadState.CANCELLED)
        return CoverageTerrainSample.ElevationMeters(elevation)
    }

    override fun close() {
        if (closed) return
        closed = true
        // Attempt every release even if closing one OS file fails.
        var firstError: Exception? = null
        for (reader in readers.values) {
            try { reader.close() } catch (error: Exception) { if (firstError == null) firstError = error }
        }
        firstError?.let { throw it }
    }
}
