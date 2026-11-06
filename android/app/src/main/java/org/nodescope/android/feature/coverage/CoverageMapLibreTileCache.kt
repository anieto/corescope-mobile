package org.nodescope.android.feature.coverage

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.SimpleFileVisitor
import java.nio.file.FileVisitResult
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

/** Exclusive owner of a dedicated ephemeral directory. Open/recovery and preparation run on workers.
 * Two leased pyramids reserve at most 32 MiB of encoded tiles, allowing replacement before removal.
 * Remove each native overlay before closing its lease. No saved inputs belong in this directory.
 */
class CoverageMapLibreTileCache(private val root: File) : AutoCloseable {
    private val handle: RandomAccessFile
    private val lock: java.nio.channels.FileLock
    private var closed = false
    private var leases = 0
    private var cleanupFailed = false

    init {
        if (!root.exists() && !root.mkdirs()) throw IOException("Could not create coverage tile cache")
        require(Files.isDirectory(root.toPath(), NOFOLLOW_LINKS)) { "Invalid coverage tile cache" }
        val lockFile = File(root, ".lock")
        require(!Files.isSymbolicLink(lockFile.toPath())) { "Invalid coverage cache lock" }
        handle = RandomAccessFile(lockFile, "rw")
        try {
            lock = (try { handle.channel.tryLock() } catch (_: OverlappingFileLockException) { null })
                ?: throw IOException("Coverage tile cache is already open")
            try { recover() } catch (failure: Throwable) { lock.release(); throw failure }
        } catch (failure: Throwable) {
            handle.close()
            throw failure
        }
    }

    private fun recover() {
        val marker = File(root, ".owner")
        val expected = "nodescope-maplibre-tiles-v1\n"
        val children = root.listFiles()?.toList() ?: throw IOException("Could not list coverage cache")
        if (!Files.exists(marker.toPath(), NOFOLLOW_LINKS)) {
            require(children.all { it.name == ".lock" }) { "Unowned coverage cache is protected" }
            marker.writeText(expected)
            return
        }
        require(Files.isRegularFile(marker.toPath(), NOFOLLOW_LINKS) && marker.length() == expected.length.toLong() &&
            marker.readText() == expected) { "Unknown coverage cache owner" }
        val abandoned = children.filter { it.name != ".lock" && it.name != ".owner" }
        // Validate the complete root before deleting anything. Do not follow links during recovery.
        require(abandoned.all { it.name.matches(Regex("coverage-[A-Za-z0-9-]+")) &&
            Files.isDirectory(it.toPath(), NOFOLLOW_LINKS) }) { "Unexpected coverage cache contents" }
        abandoned.forEach { removeTree(it.toPath()) }
    }

    @Synchronized
    fun prepare(raster: CoverageRaster, maximumZoom: Int, cancelled: () -> Boolean = { false }): Lease {
        check(!closed) { "Coverage tile cache is closed" }
        check(!cleanupFailed) { "Reopen the coverage cache after a cleanup failure" }
        check(leases < 2) { "Remove an old coverage overlay before preparing another" }
        if (root.usableSpace < 16L * 1024 * 1024) throw IOException("Insufficient space for coverage tiles")
        val tiles = CoverageMapLibreTiles.prepare(raster, maximumZoom, root, cancelled)
        leases++
        return Lease(tiles) { failed -> release(failed) }
    }

    @Synchronized private fun release(failed: Boolean) {
        leases--
        cleanupFailed = cleanupFailed || failed
    }

    @Synchronized override fun close() {
        if (closed) return
        check(leases == 0) { "Coverage overlays still own tile leases" }
        lock.release()
        handle.close()
        closed = true
    }

    class Lease internal constructor(val tiles: CoverageMapLibreTiles, private val release: (Boolean) -> Unit) : AutoCloseable {
        private var closed = false
        @Synchronized override fun close() {
            if (closed) return
            var failed = true
            try {
                tiles.close()
                failed = false
            } finally {
                // Preserve leftovers for the next recovery, but do not strand the process lock.
                // The owner refuses further preparation after failed deletion.
                release(failed)
                closed = true
            }
        }
    }

    companion object {
        /** walkFileTree does not follow symbolic links; a link is deleted, never its target. */
        internal fun removeTree(root: Path) {
            if (!Files.exists(root, NOFOLLOW_LINKS)) return
            Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    Files.delete(file)
                    return FileVisitResult.CONTINUE
                }
                override fun postVisitDirectory(dir: Path, failure: IOException?): FileVisitResult {
                    if (failure != null) throw failure
                    Files.delete(dir)
                    return FileVisitResult.CONTINUE
                }
            })
        }
    }
}
