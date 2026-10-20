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
class CacheStorage internal constructor(
    private val cacheDirectory: File,
    private val myNodesStorage: MyNodesStorage,
    private val tileCacheFiles: () -> List<File>,
    private val clearTiles: suspend () -> String?,
) {
    constructor(context: Context, myNodesStorage: MyNodesStorage) : this(
        context.cacheDir, myNodesStorage,
        { File(FileSource.getResourcesCachePath(context)).listFiles { file -> file.name.startsWith("mbgl-offline.db") }?.toList().orEmpty() },
        { clearMapTiles(context) },
    )

    suspend fun usage(): CacheUsage = withContext(Dispatchers.IO) {
        val files = tileCacheFiles() + cacheDirectory.walkTopDown().filter { it.isFile }.toList()
        CacheUsage(files.sumOf { it.length() }, files.size)
    }

    /** Clears tiles through MapLibre (the database stays valid) and empties the cache directory. */
    suspend fun clear(): String? {
        val errors = mutableListOf<String>()
        clearTiles()?.let { errors += "Map tiles couldn't be fully cleared: $it" }
        withContext(Dispatchers.IO) {
            // Only the shared store may delete its files, under its mutex. A broad recursive
            // deletion after store.clear() could erase a newly started visit's fresh snapshot.
            cacheDirectory.listFiles()?.filter { it != myNodesStorage.directory }?.forEach {
                if (!it.deleteRecursively()) errors += "Some temporary files could not be cleared."
            }
        }
        try { myNodesStorage.clear() }
        catch (_: java.io.IOException) { errors += "My Nodes snapshots could not be cleared. Please try again." }
        return errors.takeIf { it.isNotEmpty() }?.distinct()?.joinToString("\n")
    }

    companion object {
        private suspend fun clearMapTiles(context: Context): String? = suspendCancellableCoroutine { continuation ->
            OfflineManager.getInstance(context).clearAmbientCache(object : OfflineManager.FileSourceCallback {
                override fun onSuccess() { if (continuation.isActive) continuation.resume(null) }
                override fun onError(message: String) { if (continuation.isActive) continuation.resume(message) }
            })
        }
    }
}
