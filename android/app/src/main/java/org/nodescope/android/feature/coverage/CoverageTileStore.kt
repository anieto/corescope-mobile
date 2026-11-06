package org.nodescope.android.feature.coverage

enum class CoverageTileStoreState { invalidCapacity, inactive }
class CoverageTileStoreFailure(val state: CoverageTileStoreState) : Exception(state.name)

/** One immutable study; call from bounded map workers, never the UI thread.
 * Invalidate and replace on input changes. Rendering is serialized independently of invalidation.
 * The cache cap excludes encoder workspace and bytes retained by map providers.
 */
class CoverageTileStore(private val raster: CoverageRaster, private val byteLimit: Int) {
    private data class Key(val zoom: Int, val x: Int, val y: Int)
    private val stateLock = Any()
    private val renderLock = Any()
    private var active = true
    private val cache = LinkedHashMap<Key, ByteArray>(16, 0.75f, true)
    private var bytes = 0
    init {
        if (byteLimit !in 0..8 * 1024 * 1024) throw CoverageTileStoreFailure(CoverageTileStoreState.invalidCapacity)
    }
    val cachedBytes: Int get() = synchronized(stateLock) { bytes }
    private val inactive: Boolean get() = synchronized(stateLock) { !active }
    fun invalidate() = synchronized(stateLock) {
        active = false
        cache.clear()
        bytes = 0
    }
    fun tile(zoom: Int, x: Int, y: Int, cancelled: () -> Boolean = { false }): ByteArray = synchronized(renderLock) {
        if (cancelled()) throw CoverageRasterFailure(CoverageRasterState.cancelled)
        val key = Key(zoom, x, y)
        val cached = synchronized(stateLock) {
            if (!active) throw CoverageTileStoreFailure(CoverageTileStoreState.inactive)
            cache[key]?.copyOf()
        }
        if (cached != null) return@synchronized cached
        val data = raster.pngTile(zoom, x, y) { cancelled() || inactive }
        if (cancelled()) throw CoverageRasterFailure(CoverageRasterState.cancelled)
        synchronized(stateLock) {
            if (!active) throw CoverageTileStoreFailure(CoverageTileStoreState.inactive)
            if (data.size <= byteLimit) {
                while (bytes + data.size > byteLimit || cache.size >= 64) {
                    val iterator = cache.entries.iterator()
                    bytes -= iterator.next().value.size
                    iterator.remove()
                }
                // Never expose the mutable cached byte array to a provider.
                cache[key] = data.copyOf()
                bytes += data.size
            }
        }
        data
    }
}
