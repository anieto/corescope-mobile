package org.nodescope.android.feature.coverage

import androidx.annotation.MainThread
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.Tile
import com.google.android.gms.maps.model.TileOverlay
import com.google.android.gms.maps.model.TileOverlayOptions
import com.google.android.gms.maps.model.TileProvider
import java.util.concurrent.Semaphore

/** One study's Google tile source. Google calls this on background threads.
 * Null requests a later retry when our bounded admission slots are occupied.
 */
class CoverageGoogleTileProvider(raster: CoverageRaster, cacheBytes: Int) : TileProvider {
    private val store = CoverageTileStore(raster, cacheBytes)
    private val slots = Semaphore(2)

    fun invalidate() = store.invalidate()

    override fun getTile(x: Int, y: Int, zoom: Int): Tile? {
        if (!slots.tryAcquire()) return null
        try {
            val bytes = store.tile(zoom, x, y)
            return Tile(256, 256, bytes)
        } catch (_: CoverageTileStoreFailure) {
            return TileProvider.NO_TILE
        } catch (_: CoverageRasterFailure) {
            return TileProvider.NO_TILE
        } catch (_: CoverageTilePNGFailure) {
            return null
        } finally {
            slots.release()
        }
    }
}

/** Own separately from live-map overlays. Close before replacing a study or disposing its map. */
@MainThread
class CoverageGoogleOverlay(map: GoogleMap, raster: CoverageRaster, cacheBytes: Int) : AutoCloseable {
    private val provider = CoverageGoogleTileProvider(raster, cacheBytes)
    private var overlay: TileOverlay? = map.addTileOverlay(
        TileOverlayOptions().tileProvider(provider).fadeIn(false).zIndex(0f)
    )

    override fun close() {
        provider.invalidate()
        // Removing the provider-specific overlay also prevents delayed old requests from being
        // displayed in a replacement study. Never swap raster inputs in an existing provider.
        overlay?.remove()
        overlay = null
    }
}
