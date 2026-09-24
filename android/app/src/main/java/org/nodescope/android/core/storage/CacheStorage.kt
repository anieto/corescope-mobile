package org.nodescope.android.core.storage

import android.content.Context
import java.io.File
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.storage.FileSource

data class CacheUsage(val bytes: Long, val files: Int)

/**
 * Downloaded, disposable data only: MapLibre's map tile cache and the app cache directory.
 * Favorites, recent items, monitored-channel keys and preferences live in separate storage
 * and are never touched here (as on iOS).
 */
class CacheStorage(private val context: Context) {
    private fun tileCacheFiles(): List<File> =
        File(FileSource.getResourcesCachePath(context)).listFiles { file -> file.name.startsWith("mbgl-offline.db") }?.toList().orEmpty()

    suspend fun usage(): CacheUsage = withContext(Dispatchers.IO) {
        val files = tileCacheFiles() + context.cacheDir.walkTopDown().filter { it.isFile }.toList()
        CacheUsage(files.sumOf { it.length() }, files.size)
    }

    /** Clears tiles through MapLibre (the database stays valid) and empties the cache directory. */
    suspend fun clear(): String? {
        val tileError = suspendCancellableCoroutine { continuation ->
            OfflineManager.getInstance(context).clearAmbientCache(object : OfflineManager.FileSourceCallback {
                override fun onSuccess() { if (continuation.isActive) continuation.resume(null) }
                override fun onError(message: String) { if (continuation.isActive) continuation.resume(message) }
            })
        }
        withContext(Dispatchers.IO) { context.cacheDir.listFiles()?.forEach { it.deleteRecursively() } }
        return tileError
    }
}
