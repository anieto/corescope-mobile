package org.nodescope.android.feature.coverage

import android.util.Log
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.nodescope.android.feature.map.GoogleMapEngine
import org.nodescope.android.feature.map.MapEngineState
import org.nodescope.android.feature.map.MapLibreEngine
import java.io.File
import java.util.UUID
import kotlin.coroutines.coroutineContext

/** Immutable display inputs from one completed study. The screen must also display its assumptions
 * and maximum raster zoom; never change raster contents while retaining the execution identity.
 */
internal class CoverageMapLayer(val executionID: UUID, val raster: CoverageRaster, val maximumRasterZoom: Int)

/** Per-screen lifecycle; no cache is opened and no work starts without a completed layer. */
@Composable
internal fun CoverageMapAttachment(engine: MapEngineState, layer: CoverageMapLayer?, onFailure: (String?) -> Unit) {
    val cacheRoot = File(LocalContext.current.applicationContext.cacheDir, "coverage-map-tiles-v1")
    // A new style/provider effect waits for cancelled preparation and cleanup from its predecessor.
    val ownership = remember { Mutex() }
    val report by rememberUpdatedState(onFailure)
    val google = (engine as? GoogleMapEngine)?.map
    val style = (engine as? MapLibreEngine)?.style
    LaunchedEffect(google, style, layer) {
        report(null)
        if (layer == null || (google == null && style == null)) return@LaunchedEffect
        ownership.withLock {
            var cache: CoverageMapLibreTileCache? = null
            var lease: CoverageMapLibreTileCache.Lease? = null
            var attachment: AutoCloseable? = null
            try {
                if (google != null) {
                    attachment = CoverageGoogleOverlay(google, layer.raster, 8 * 1024 * 1024)
                } else if (style != null) {
                    withContext(Dispatchers.IO) {
                        val jobContext = coroutineContext
                        // Assign resources inside the worker block: prompt cancellation on returning
                        // to Main must not discard an acquired resource before finally can release it.
                        val owner = CoverageMapLibreTileCache(cacheRoot)
                        cache = owner
                        lease = owner.prepare(layer.raster, layer.maximumRasterZoom) {
                            jobContext.ensureActive()
                            false
                        }
                    }
                    coroutineContext.ensureActive()
                    val prepared = checkNotNull(lease)
                    attachment = CoverageMapLibreOverlay(style, prepared.tiles, "nodescope-pins")
                }
                awaitCancellation()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                report("Coverage overlay could not be displayed. Retry the calculation or reopen the study.")
                Log.w("NodeScope", "Coverage overlay attachment failed", failure)
            } finally {
                // Native map mutations stay on Main, before deleting files they might reference.
                try { attachment?.close() }
                catch (failure: Exception) { Log.w("NodeScope", "Coverage overlay removal failed", failure) }
                withContext(NonCancellable + Dispatchers.IO) {
                    try { lease?.close() }
                    catch (failure: Exception) { Log.w("NodeScope", "Coverage tile cleanup failed", failure) }
                    try { cache?.close() }
                    catch (failure: Exception) { Log.w("NodeScope", "Coverage cache close failed", failure) }
                }
            }
        }
    }
}
