package org.nodescope.android.feature.coverage

import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*

/** Direct AWS discovery. ETags are opaque representation IDs, not independent content checksums. */
object CoverageSkadiProvider {
    private val sharedClient = OkHttpClient()
    fun endpoint(tile: CoverageTerrainTile, legacy: Boolean = false): String {
        val name = String.format(Locale.ROOT, "%s%02d%s%03d", if (tile.south < 0) "S" else "N", kotlin.math.abs(tile.south),
            if (tile.west < 0) "W" else "E", kotlin.math.abs(tile.west))
        return "https://elevation-tiles-prod.s3.amazonaws.com/${if (legacy) "" else "v2/"}skadi/${name.take(3)}/$name.hgt.gz"
    }
    fun descriptor(tile: CoverageTerrainTile, endpoint: String, status: Int,
                   length: String?, etag: String?, encoding: String?): CoverageTerrainDescriptor {
        if (status != 200) throw CoverageTerrainDownloadFailure(CoverageTerrainDownloadState.HTTP_STATUS, status)
        if (endpoint !in listOf(endpoint(tile), endpoint(tile, true)) || length.isNullOrEmpty() ||
            length.any { it !in '0'..'9' } || etag == null || (encoding ?: "identity").lowercase() != "identity") corrupt()
        val count = length.toLongOrNull() ?: corrupt()
        val name = endpoint.substringAfterLast('/').removeSuffix(".hgt.gz")
        return CoverageTerrainDescriptor("mapzen-skadi", "direct-v1", name, null, count, 3601, endpoint, "EGM96",
            "Mapzen Terrain Tiles; source attribution: https://github.com/tilezen/joerd/blob/master/docs/attribution.md",
            "mixed-source elevation; surface/bare-earth varies", endpoint, etag)
    }
    /** Serial HEAD requests. Only a v2 404 permits v1 fallback. Missing tiles never become ocean. */
    suspend fun discover(footprint: CoverageTerrainFootprint, timeoutMillis: Long = 15_000,
                         client: OkHttpClient = sharedClient): List<CoverageTerrainDescriptor> {
        if (timeoutMillis !in 50..60_000) throw CoverageTerrainDownloadFailure(CoverageTerrainDownloadState.INVALID_LIMITS)
        val transport = client.newBuilder().followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
            .cache(null).cookieJar(CookieJar.NO_COOKIES).authenticator(Authenticator.NONE).proxyAuthenticator(Authenticator.NONE)
            .connectTimeout(timeoutMillis, TimeUnit.MILLISECONDS).readTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
            .callTimeout(timeoutMillis, TimeUnit.MILLISECONDS).build()
        val result = mutableListOf<CoverageTerrainDescriptor>()
        for (tile in footprint.tiles) {
            for (legacy in listOf(false, true)) {
                currentCoroutineContext().ensureActive()
                val url = endpoint(tile, legacy)
                val metadata = head(transport, url)
                if (metadata.status == 404 && !legacy) continue
                result.add(descriptor(tile, url, metadata.status, metadata.length, metadata.etag, metadata.encoding))
                break
            }
        }
        currentCoroutineContext().ensureActive()
        return footprint.resolve(result)
    }
    private data class Metadata(val status: Int, val length: String?, val etag: String?, val encoding: String?)
    private suspend fun head(client: OkHttpClient, url: String): Metadata = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(Request.Builder().url(url).head().header("Accept-Encoding", "identity").build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { continuation.resumeWithException(e) }
            override fun onResponse(call: Call, response: Response) {
                val metadata = response.use { Metadata(it.code, it.header("Content-Length"), it.header("ETag"), it.header("Content-Encoding")) }
                continuation.resumeWith(Result.success(metadata))
            }
        })
    }
    private fun corrupt(): Nothing = throw CoverageTerrainDownloadFailure(CoverageTerrainDownloadState.CORRUPT)
}
