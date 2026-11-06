package org.nodescope.android.feature.coverage

import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.math.*

enum class CoverageMapLibreTilesState { invalidZoom, tileLimit, byteLimit, cancelled }
class CoverageMapLibreTilesFailure(val state: CoverageMapLibreTilesState) : Exception(state.name)
data class CoverageMapTile(val zoom: Int, val x: Int, val y: Int)

/** Complete, explicitly limited pyramid; never silently selects a lower zoom. */
object CoverageMapTilePlan {
    fun make(raster: CoverageRaster, maximumZoom: Int): List<CoverageMapTile> {
        if (maximumZoom !in 0..22) throw CoverageMapLibreTilesFailure(CoverageMapLibreTilesState.invalidZoom)
        val phi = Math.toRadians(raster.transmitter.latitude)
        val angular = raster.grid.radiusMeters / CoverageTerrainFootprint.EARTH_RADIUS_METERS
        val south = max(-85.0, Math.toDegrees(phi - angular))
        val north = min(85.0, Math.toDegrees(phi + angular))
        val halfWidth = Math.toDegrees(asin((sin(angular) / cos(phi)).coerceIn(0.0, 1.0)))
        val result = mutableListOf<CoverageMapTile>()
        for (zoom in 0..maximumZoom) {
            val count = 1 shl zoom
            fun row(latitude: Double, first: Boolean): Int {
                val value = (1 - asinh(tan(Math.toRadians(latitude))) / PI) / 2 * count
                return floor(if (first) Math.nextDown(value) else Math.nextUp(value)).toInt().coerceIn(0, count - 1)
            }
            val firstRow = row(north, true)
            val lastRow = row(south, false)
            val west = floor(Math.nextDown((raster.transmitter.longitude - halfWidth + 180) / 360 * count)).toLong()
            val east = floor(Math.nextUp((raster.transmitter.longitude + halfWidth + 180) / 360 * count)).toLong()
            val columns = min(count.toLong(), east - west + 1)
            val size = columns * (lastRow - firstRow + 1)
            if (result.size + size > 128) throw CoverageMapLibreTilesFailure(CoverageMapLibreTilesState.tileLimit)
            for (offset in 0 until columns) {
                val x = Math.floorMod(west + offset, count.toLong()).toInt()
                for (y in firstRow..lastRow) result.add(CoverageMapTile(zoom, x, y))
            }
        }
        return result.toList()
    }
}

/** Owned, ephemeral local tiles. Prepare on a worker; publish only after completion.
 * Close only after removing all layers/sources using this pyramid. The caller owns crash cleanup
 * of its dedicated cache parent; this class never sweeps or deletes sibling directories.
 */
class CoverageMapLibreTiles private constructor(
    private val directory: File,
    val maximumZoom: Int,
    val tileCount: Int,
    val encodedBytes: Long,
) : AutoCloseable {
    val urlTemplate: String = java.net.URI("file", "", directory.absolutePath, null).toASCIIString().trimEnd('/') + "/{z}/{x}/{y}.png"
    override fun close() {
        CoverageMapLibreTileCache.removeTree(directory.toPath())
    }

    companion object {
        fun prepare(raster: CoverageRaster, maximumZoom: Int, cacheParent: File,
                    cancelled: () -> Boolean = { false }): CoverageMapLibreTiles {
            val plan = CoverageMapTilePlan.make(raster, maximumZoom)
            fun check() {
                if (cancelled()) throw CoverageMapLibreTilesFailure(CoverageMapLibreTilesState.cancelled)
            }
            check()
            val directory = Files.createTempDirectory(cacheParent.toPath(), "coverage-").toFile()
            try {
                var bytes = 0L
                for (tile in plan) {
                    check()
                    val png = raster.pngTile(tile.zoom, tile.x, tile.y, cancelled)
                    bytes += png.size
                    if (bytes > 16 * 1024 * 1024) throw CoverageMapLibreTilesFailure(CoverageMapLibreTilesState.byteLimit)
                    val output = File(directory, "${tile.zoom}/${tile.x}/${tile.y}.png")
                    val tileDirectory = requireNotNull(output.parentFile)
                    if (!tileDirectory.mkdirs() && !tileDirectory.isDirectory) throw IOException("Could not create tile directory")
                    output.writeBytes(png)
                }
                check()
                return CoverageMapLibreTiles(directory, maximumZoom, plan.size, bytes)
            } catch (failure: Throwable) {
                try { CoverageMapLibreTileCache.removeTree(directory.toPath()) }
                catch (cleanup: Exception) { failure.addSuppressed(cleanup) }
                throw failure
            }
        }
    }
}
