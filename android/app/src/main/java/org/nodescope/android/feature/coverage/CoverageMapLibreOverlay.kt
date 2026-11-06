package org.nodescope.android.feature.coverage

import androidx.annotation.MainThread
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.layers.PropertyFactory.rasterFadeDuration
import org.maplibre.android.style.layers.PropertyFactory.rasterResampling
import org.maplibre.android.style.sources.RasterSource
import org.maplibre.android.style.sources.TileSet
import java.util.UUID

/** One style attachment. Remove before deleting its tiles; create a new attachment after style reload.
 * The tile owner remains with the caller so a style change need not regenerate the pyramid.
 */
@MainThread
class CoverageMapLibreOverlay(private val style: Style, tiles: CoverageMapLibreTiles, belowLayerID: String) : AutoCloseable {
    private val id = "coverage-${UUID.randomUUID()}"
    private var attached = false
    init {
        require(style.getLayer(belowLayerID) != null) { "Coverage requires an explicit layer above it" }
        val tileSet = TileSet("2.2.0", tiles.urlTemplate).apply {
            minZoom = 0f
            maxZoom = tiles.maximumZoom.toFloat()
            scheme = "xyz"
        }
        style.addSource(RasterSource(id, tileSet, 256))
        try {
            style.addLayerBelow(RasterLayer(id, id).withProperties(
                rasterResampling("nearest"), rasterFadeDuration(0f)
            ), belowLayerID)
            attached = true
        } catch (failure: Throwable) {
            style.removeSource(id)
            throw failure
        }
    }
    override fun close() {
        if (!attached) return
        style.removeLayer(id)
        style.removeSource(id)
        attached = false
    }
}
