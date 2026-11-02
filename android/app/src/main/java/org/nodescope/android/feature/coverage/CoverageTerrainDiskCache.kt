package org.nodescope.android.feature.coverage

import java.io.IOException
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.FileVisitResult
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

enum class CoverageTerrainCacheState { BUSY, CLOSED, INVALID_CACHE, MISSING, QUOTA, CANCELLED, IO }
class CoverageTerrainCacheFailure(val state: CoverageTerrainCacheState) : Exception(state.name)

/** One serialized background worker owns the cache and all reader-release callbacks. */
class CoverageTerrainDiskCache(private val root: File, private val quota: Long) : AutoCloseable {
    @Serializable
    private data class Metadata(val schema: Int, val identity: String, val side: Int, val decodedSHA256: String,
        val sourceURL: String, val originURL: String, val fetchedAtMilliseconds: Long, val accessSequence: Long) {
        val bytes: Long get() = side.toLong() * side * 2
    }
    private var lockFile: RandomAccessFile? = null
    private var processLock: FileLock? = null
    private val entries = mutableMapOf<String, Metadata>()
    private val pins = mutableMapOf<String, Int>()
    private var sequence = 0L
    private var closed = false
    private val json = Json { encodeDefaults = true }

    init {
        if (quota !in 8192..2_000_000_000L) throw CoverageTerrainRepositoryFailure(CoverageTerrainRepositoryState.INVALID_LIMITS)
        try {
            if (Files.isSymbolicLink(root.toPath())) fail(CoverageTerrainCacheState.INVALID_CACHE)
            Files.createDirectories(root.toPath())
            val children = root.list() ?: fail(CoverageTerrainCacheState.IO)
            val owner = File(root, "owner")
            if (children.isNotEmpty() && (!regular(owner) || !smallData(owner).contentEquals(MARKER))) fail(CoverageTerrainCacheState.INVALID_CACHE)
            val lock = File(root, "lock")
            if (Files.isSymbolicLink(lock.toPath())) fail(CoverageTerrainCacheState.INVALID_CACHE)
            val handle = RandomAccessFile(lock, "rw")
            lockFile = handle
            processLock = try { handle.channel.tryLock() } catch (_: OverlappingFileLockException) { null }
            if (processLock == null) fail(CoverageTerrainCacheState.BUSY)
            if (children.isEmpty()) owner.writeBytes(MARKER)
            recover()
        } catch (error: Exception) {
            processLock?.release()
            lockFile?.close()
            throw error
        }
    }

    val inventory: List<CoverageTerrainInventoryEntry> get() = entries.map { (key, value) ->
        CoverageTerrainInventoryEntry(key, value.bytes, (pins[key] ?: 0) > 0)
    }

    fun preflight(descriptors: List<CoverageTerrainDescriptor>, remainingDownloadBytes: Long, attempts: Int = 2): CoverageTerrainPreflight {
        requireOpen()
        return CoverageTerrainPreflight.make(descriptors, inventory, quota, remainingDownloadBytes, attempts)
    }

    /** Private decoded adapter output only; this verifies decoded hash/length, not gzip/source digest. */
    fun publish(descriptor: CoverageTerrainDescriptor, decodedFile: File, decodedSHA256: String,
                fetchedAtMilliseconds: Long, cancelled: () -> Boolean = { false }) {
        requireOpen(); check(cancelled)
        if (fetchedAtMilliseconds < 0 || !digest(decodedSHA256)) fail(CoverageTerrainCacheState.INVALID_CACHE)
        val key = descriptor.cacheKey
        if ((pins[key] ?: 0) > 0) fail(CoverageTerrainCacheState.BUSY)
        val metadata = Metadata(1, descriptor.canonicalIdentity, descriptor.side, decodedSHA256,
            descriptor.url, descriptor.originURL, fetchedAtMilliseconds, nextSequence())
        val metadataBytes = encode(metadata)
        makeRoom(descriptor.decodedBytes + 8192, key)
        val stage = File(root, ".stage-${UUID.randomUUID()}")
        Files.createDirectory(stage.toPath())
        try {
            val destination = File(stage, "terrain.hgt")
            decodedFile.inputStream().use { input ->
                destination.outputStream().use { output ->
                    val buffer = ByteArray(65536)
                    var written = 0L
                    while (true) {
                        check(cancelled)
                        val count = input.read(buffer, 0, minOf(buffer.size.toLong(), descriptor.decodedBytes - written + 1).toInt())
                        if (count < 0) break
                        if (count.toLong() > descriptor.decodedBytes - written) throw TerrainReadFailure(TerrainReadState.CORRUPT)
                        output.write(buffer, 0, count)
                        written += count
                    }
                    if (written != descriptor.decodedBytes) throw TerrainReadFailure(TerrainReadState.CORRUPT)
                    output.fd.sync()
                }
            }
            LeasedTerrainReader.open(destination, descriptor.side, decodedSHA256, cancelled) {}.use { }
            File(stage, "metadata.json").writeBytes(metadataBytes)
            check(cancelled)
            if (key in entries) remove(key)
            Files.move(stage.toPath(), File(root, key).toPath(), ATOMIC_MOVE)
            entries[key] = metadata
        } finally { removeTree(stage) }
    }

    fun acquire(descriptor: CoverageTerrainDescriptor, cancelled: () -> Boolean = { false }): LeasedTerrainReader {
        requireOpen(); check(cancelled)
        val key = descriptor.cacheKey
        val metadata = entries[key] ?: fail(CoverageTerrainCacheState.MISSING)
        if (metadata.identity != descriptor.canonicalIdentity || metadata.side != descriptor.side) fail(CoverageTerrainCacheState.INVALID_CACHE)
        val directory = File(root, key)
        val terrain = File(directory, "terrain.hgt")
        if (!regular(terrain)) fail(CoverageTerrainCacheState.INVALID_CACHE)
        pins[key] = (pins[key] ?: 0) + 1
        val reader = LeasedTerrainReader.open(terrain, metadata.side, metadata.decodedSHA256, cancelled) {
            pins[key] = (pins[key] ?: 0) - 1
        }
        try {
            val updated = metadata.copy(accessSequence = nextSequence())
            val part = File(directory, "metadata.part")
            part.writeBytes(encode(updated))
            Files.move(part.toPath(), File(directory, "metadata.json").toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
            entries[key] = updated
            return reader
        } catch (error: Exception) { reader.close(); throw error }
    }

    /** All-or-none reader transfer. No partially acquired set escapes on failure. */
    fun acquireMany(descriptors: List<CoverageTerrainDescriptor>, cancelled: () -> Boolean = { false }): Map<String, LeasedTerrainReader> {
        if (!preflight(descriptors, 0).canPreparePinnedSet) fail(CoverageTerrainCacheState.QUOTA)
        val readers = mutableMapOf<String, LeasedTerrainReader>()
        try {
            for (descriptor in descriptors) if (descriptor.cacheKey !in readers) readers[descriptor.cacheKey] = acquire(descriptor, cancelled)
            check(cancelled)
            return readers
        } catch (error: Exception) {
            readers.values.forEach { try { it.close() } catch (closeError: Exception) { error.addSuppressed(closeError) } }
            throw error
        }
    }

    fun clearUnpinned() {
        requireOpen()
        entries.keys.toList().filter { (pins[it] ?: 0) == 0 }.forEach { remove(it) }
    }

    override fun close() {
        if (closed) return
        if (pins.values.any { it > 0 }) fail(CoverageTerrainCacheState.BUSY)
        closed = true
        try { processLock?.release() } finally { lockFile?.close() }
    }

    private fun recover() {
        val children = root.listFiles() ?: fail(CoverageTerrainCacheState.IO)
        if (children.size > 4098) fail(CoverageTerrainCacheState.INVALID_CACHE)
        for (child in children) {
            val name = child.name
            if (name == "owner" || name == "lock") continue
            if (Files.isSymbolicLink(child.toPath()) || !Files.isDirectory(child.toPath(), NOFOLLOW_LINKS)) fail(CoverageTerrainCacheState.INVALID_CACHE)
            if (name.startsWith(".stage-") && runCatching { UUID.fromString(name.removePrefix(".stage-")).toString() == name.removePrefix(".stage-") }.getOrDefault(false)) {
                removeTree(child)
                continue
            }
            if (!digest(name)) fail(CoverageTerrainCacheState.INVALID_CACHE)
            val names = child.list()?.toSet() ?: fail(CoverageTerrainCacheState.IO)
            if (!setOf("terrain.hgt", "metadata.json", "metadata.part").containsAll(names) ||
                !regular(File(child, "metadata.json")) || !regular(File(child, "terrain.hgt"))) fail(CoverageTerrainCacheState.INVALID_CACHE)
            val metadata = json.decodeFromString<Metadata>(smallData(File(child, "metadata.json")).decodeToString(throwOnInvalidSequence = true))
            val key = MessageDigest.getInstance("SHA-256").digest(metadata.identity.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }
            if (metadata.schema != 1 || metadata.side !in 2..3601 || 3600 % (metadata.side - 1) != 0 ||
                !digest(metadata.decodedSHA256) || metadata.fetchedAtMilliseconds < 0 ||
                metadata.accessSequence < 0 || metadata.accessSequence == Long.MAX_VALUE || key != name) fail(CoverageTerrainCacheState.INVALID_CACHE)
            entries[name] = metadata
            sequence = maxOf(sequence, metadata.accessSequence)
            if ("metadata.part" in names) {
                val part = File(child, "metadata.part")
                if (!regular(part)) fail(CoverageTerrainCacheState.INVALID_CACHE)
                Files.delete(part.toPath())
            }
        }
    }

    private val used: Long get() = 4096 + entries.values.sumOf { it.bytes + 8192 }
    private fun makeRoom(needed: Long, protecting: String) {
        if (needed + 4096 > quota) fail(CoverageTerrainCacheState.QUOTA)
        while (used + needed > quota) {
            val victim = entries.keys.filter { it != protecting && (pins[it] ?: 0) == 0 }
                .minWithOrNull(compareBy<String> { entries.getValue(it).accessSequence }.thenBy { it }) ?: fail(CoverageTerrainCacheState.QUOTA)
            remove(victim)
        }
    }
    private fun remove(key: String) {
        if ((pins[key] ?: 0) > 0) fail(CoverageTerrainCacheState.BUSY)
        removeTree(File(root, key))
        entries.remove(key); pins.remove(key)
    }
    private fun nextSequence(): Long {
        if (sequence >= Long.MAX_VALUE - 1) fail(CoverageTerrainCacheState.INVALID_CACHE)
        return ++sequence
    }
    private fun requireOpen() { if (closed) fail(CoverageTerrainCacheState.CLOSED) }
    private fun check(cancelled: () -> Boolean) { if (cancelled()) fail(CoverageTerrainCacheState.CANCELLED) }
    private fun encode(metadata: Metadata): ByteArray = json.encodeToString(metadata).toByteArray(Charsets.UTF_8).also {
        if (it.size > 4096) fail(CoverageTerrainCacheState.INVALID_CACHE)
    }
    companion object {
        private val MARKER = "nodescope-coverage-cache-v1\n".toByteArray()
        // Default walkFileTree does not follow symbolic links, including inside interrupted stages.
        private fun removeTree(file: File) {
            if (!Files.exists(file.toPath(), NOFOLLOW_LINKS)) return
            Files.walkFileTree(file.toPath(), object : SimpleFileVisitor<Path>() {
                override fun visitFile(path: Path, attributes: BasicFileAttributes): FileVisitResult {
                    Files.delete(path)
                    return FileVisitResult.CONTINUE
                }
                override fun postVisitDirectory(path: Path, error: IOException?): FileVisitResult {
                    if (error != null) throw error
                    Files.delete(path)
                    return FileVisitResult.CONTINUE
                }
            })
        }
        private fun regular(file: File) = Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS)
        private fun digest(text: String) = Regex("[a-f0-9]{64}").matches(text)
        private fun smallData(file: File): ByteArray = file.inputStream().use { input ->
            val buffer = ByteArray(4097)
            var count = 0
            while (count < buffer.size) {
                val read = input.read(buffer, count, buffer.size - count)
                if (read < 0) break
                count += read
            }
            if (count > 4096) fail(CoverageTerrainCacheState.INVALID_CACHE)
            buffer.copyOf(count)
        }
        private fun fail(state: CoverageTerrainCacheState): Nothing = throw CoverageTerrainCacheFailure(state)
    }
}
