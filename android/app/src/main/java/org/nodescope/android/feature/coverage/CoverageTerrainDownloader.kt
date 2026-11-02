package org.nodescope.android.feature.coverage

import java.io.File
import java.io.IOException
import java.net.Proxy
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import kotlin.coroutines.resumeWithException

enum class CoverageTerrainDownloadState { INVALID_LIMITS, DOWNLOAD_LIMIT, CORRUPT, HTTP_STATUS }
class CoverageTerrainDownloadFailure(val state: CoverageTerrainDownloadState, val status: Int? = null) : Exception(state.name)

/** Consumed response-body bytes, including failed attempts; excludes HTTP/TLS overhead. */
class CoverageTerrainDownloadBudget(limit: Long) {
    private var available = limit
    @Volatile var receivedBytes = 0L
        private set
    init {
        if (limit !in 0..512_000_000L) throw CoverageTerrainDownloadFailure(CoverageTerrainDownloadState.INVALID_LIMITS)
    }
    @Synchronized internal fun reserve(count: Long) {
        if (count > available) throw CoverageTerrainDownloadFailure(CoverageTerrainDownloadState.DOWNLOAD_LIMIT)
        available -= count
    }
    @Synchronized internal fun finish(reserved: Long, received: Long) {
        available += reserved - received
        receivedBytes += received
    }
}

class DownloadedCoverageTerrain internal constructor(private val directory: File) : AutoCloseable {
    val file = File(directory, "terrain.hgt.gz")
    override fun close() {
        Files.deleteIfExists(file.toPath())
        Files.deleteIfExists(directory.toPath())
    }
}

/** One bounded attempt. Coordinator owns retry policy and whole-pipeline disk reservations. */
object CoverageTerrainDownloader {
    suspend fun download(descriptor: CoverageTerrainDescriptor, workspace: File,
                         budget: CoverageTerrainDownloadBudget, idleTimeoutMillis: Long = 15_000,
                         attemptTimeoutMillis: Long = 60_000): DownloadedCoverageTerrain {
        if (idleTimeoutMillis !in 50..30_000 || attemptTimeoutMillis !in 50..120_000) {
            throw CoverageTerrainDownloadFailure(CoverageTerrainDownloadState.INVALID_LIMITS)
        }
        val client = OkHttpClient.Builder().proxy(Proxy.NO_PROXY)
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
            .connectTimeout(idleTimeoutMillis, TimeUnit.MILLISECONDS)
            .readTimeout(idleTimeoutMillis, TimeUnit.MILLISECONDS)
            .callTimeout(attemptTimeoutMillis, TimeUnit.MILLISECONDS).build()
        val call = client.newCall(Request.Builder().url(descriptor.url)
            .header("Accept-Encoding", "identity").header("User-Agent", "NodeScope-Terrain/1").build())
        // File I/O occurs in OkHttp's worker callback. Cancellation closes the active socket.
        return suspendCancellableCoroutine { continuation ->
            val reserved = descriptor.packedBytes + 1
            try { budget.reserve(reserved) } catch (error: Exception) {
                continuation.resumeWithException(error)
                return@suspendCancellableCoroutine
            }
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    budget.finish(reserved, 0)
                    client.connectionPool.evictAll()
                    client.dispatcher.executorService.shutdown()
                    continuation.resumeWithException(e)
                }
                override fun onResponse(call: Call, response: Response) {
                    var artifact: DownloadedCoverageTerrain? = null
                    var received = 0L
                    var hasReservation = true
                    try {
                        val completed = response.use {
                            if (!continuation.isActive) throw IOException("Cancelled")
                            if (response.code != 200) throw CoverageTerrainDownloadFailure(CoverageTerrainDownloadState.HTTP_STATUS, response.code)
                            if ((response.header("Content-Encoding") ?: "identity").lowercase() != "identity") corrupt()
                            response.header("Content-Length")?.let { length ->
                                if (length.isEmpty() || length.any { it !in '0'..'9' } || length.toLongOrNull() != descriptor.packedBytes) corrupt()
                            }
                            val directory = File(workspace, ".download-${UUID.randomUUID()}")
                            Files.createDirectory(directory.toPath())
                            val owned = DownloadedCoverageTerrain(directory)
                            artifact = owned
                            val body = response.body ?: corrupt()
                            val hash = MessageDigest.getInstance("SHA-256")
                            body.byteStream().use { input ->
                                owned.file.outputStream().use { output ->
                                    val buffer = ByteArray(65536)
                                    while (true) {
                                        if (!continuation.isActive) throw IOException("Cancelled")
                                        val count = input.read(buffer, 0, minOf(buffer.size.toLong(), reserved - received).toInt())
                                        if (count < 0) break
                                        received += count
                                        if (received > descriptor.packedBytes) corrupt()
                                        output.write(buffer, 0, count)
                                        hash.update(buffer, 0, count)
                                    }
                                    if (received != descriptor.packedBytes || hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) } != descriptor.compressedSHA256) corrupt()
                                    output.fd.sync()
                                }
                            }
                            owned
                        }
                        budget.finish(reserved, received)
                        hasReservation = false
                        continuation.resume(completed, onCancellation = { _, value, _ -> runCatching { value.close() } })
                        artifact = null
                    } catch (error: Exception) {
                        try { artifact?.close() } catch (cleanup: Exception) { error.addSuppressed(cleanup) }
                        artifact = null
                        if (hasReservation) { budget.finish(reserved, received); hasReservation = false }
                        continuation.resumeWithException(error)
                    } finally {
                        try { artifact?.close() } finally {
                            if (hasReservation) budget.finish(reserved, received)
                            client.connectionPool.evictAll()
                            client.dispatcher.executorService.shutdown()
                        }
                    }
                }
            })
        }
    }
    private fun corrupt(): Nothing = throw CoverageTerrainDownloadFailure(CoverageTerrainDownloadState.CORRUPT)
}
