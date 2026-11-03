package org.nodescope.android.feature.coverage

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*

enum class CoverageTerrainCatalogLoadState { INVALID_REQUEST, INVALID_RESPONSE, HTTP_STATUS }
class CoverageTerrainCatalogLoadFailure(val state: CoverageTerrainCatalogLoadState, val status: Int? = null) : Exception(state.name)

/** Fetches one catalog from a caller-configured trusted HTTPS endpoint. No retries or redirects. */
object CoverageTerrainCatalogLoader {
    private val sharedClient = OkHttpClient()
    suspend fun load(endpoint: String, timeoutMillis: Long = 30_000,
                     client: OkHttpClient = sharedClient): CoverageTerrainCatalog {
        val valid = runCatching {
            val uri = URI(endpoint)
            uri.scheme == "https" && !uri.host.isNullOrEmpty() && uri.userInfo == null && uri.fragment == null &&
                timeoutMillis in 50..60_000
        }.getOrDefault(false)
        if (!valid) throw CoverageTerrainCatalogLoadFailure(CoverageTerrainCatalogLoadState.INVALID_REQUEST)
        // Isolate this request's cancellation and policy without shutting down a supplied client's shared executor.
        val transport = client.newBuilder().followRedirects(false).followSslRedirects(false)
            .retryOnConnectionFailure(false).cache(null).cookieJar(CookieJar.NO_COOKIES)
            .authenticator(Authenticator.NONE).proxyAuthenticator(Authenticator.NONE)
            .connectTimeout(timeoutMillis, TimeUnit.MILLISECONDS).readTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
            .callTimeout(timeoutMillis, TimeUnit.MILLISECONDS).build()
        val call = transport.newCall(Request.Builder().url(endpoint)
            .header("Accept-Encoding", "identity").header("Accept", "application/json").build())
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { continuation.resumeWithException(e) }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        val catalog = response.use {
                            if (response.code != 200) throw CoverageTerrainCatalogLoadFailure(CoverageTerrainCatalogLoadState.HTTP_STATUS, response.code)
                            if ((response.header("Content-Encoding") ?: "identity").lowercase() != "identity") invalidResponse()
                            val expected = response.header("Content-Length")?.let { length ->
                                if (length.isEmpty() || length.any { it !in '0'..'9' }) invalidResponse()
                                length.toLongOrNull()?.takeIf { it <= CoverageTerrainCatalog.MAXIMUM_BYTES } ?: invalidResponse()
                            }
                            val body = response.body ?: invalidResponse()
                            val output = ByteArrayOutputStream()
                            body.byteStream().use { input ->
                                val buffer = ByteArray(8192)
                                while (true) {
                                    if (!continuation.isActive) throw IOException("Cancelled")
                                    val remaining = CoverageTerrainCatalog.MAXIMUM_BYTES - output.size()
                                    val count = input.read(buffer, 0, minOf(buffer.size, remaining + 1))
                                    if (count < 0) break
                                    if (count > remaining) throw CoverageTerrainCatalogFailure(CoverageTerrainCatalogState.OVERSIZED)
                                    output.write(buffer, 0, count)
                                }
                            }
                            if (expected != null && expected != output.size().toLong()) invalidResponse()
                            if (!continuation.isActive) throw IOException("Cancelled")
                            CoverageTerrainCatalog(output.toByteArray())
                        }
                        continuation.resumeWith(Result.success(catalog))
                    } catch (error: Exception) { continuation.resumeWithException(error) }
                }
            })
        }
    }
    private fun invalidResponse(): Nothing = throw CoverageTerrainCatalogLoadFailure(CoverageTerrainCatalogLoadState.INVALID_RESPONSE)
}
