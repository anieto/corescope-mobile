package org.nodescope.android.core.network

import java.io.IOException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.nodescope.android.core.model.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

val protocolJson = Json { ignoreUnknownKeys = true }

fun normalizeHost(input: String): String {
    val text = input.trim()
    require(text.isNotEmpty()) { "Enter an analyzer hostname." }
    val url = (if ("://" in text) text else "https://$text").toHttpUrlOrNull()
        ?: throw IllegalArgumentException("Enter a valid analyzer hostname.")
    require(url.scheme == "https" && url.username.isEmpty() && url.password.isEmpty() &&
        url.encodedPath == "/" && url.query == null && url.fragment == null) {
        "Use an HTTPS hostname without a path, login, or query."
    }
    return url.toString().removePrefix("https://").removeSuffix("/")
}

fun endpoint(host: String, vararg segments: String): HttpUrl =
    checkNotNull("https://${normalizeHost(host)}".toHttpUrlOrNull()).newBuilder().apply {
        segments.forEach(::addPathSegment)
    }.build()

class HttpFailure(val status: Int) : IOException("Analyzer returned HTTP $status.")

/** Cancellation closes the actual HTTP call, including while reading the body. */
suspend fun OkHttpClient.read(url: HttpUrl): String = suspendCancellableCoroutine { continuation ->
    val call = newCall(Request.Builder().url(url).build())
    continuation.invokeOnCancellation { call.cancel() }
    call.enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            continuation.resumeWithException(e)
        }
        override fun onResponse(call: Call, response: Response) {
            try {
                val text = response.use {
                    if (!it.isSuccessful) throw HttpFailure(it.code)
                    it.body?.string() ?: throw IOException("Empty analyzer response.")
                }
                continuation.resume(text)
            } catch (e: Exception) {
                continuation.resumeWithException(e)
            }
        }
    })
}

interface AnalyzerRepository {
    suspend fun load(selection: AnalyzerSelection): AnalyzerSnapshot
    /** The last snapshot saved for [selection], shown while a fresh one downloads. */
    suspend fun saved(selection: AnalyzerSelection): Cached<AnalyzerSnapshot>? = null
}

/** `CODE,lat,lon` rows; malformed rows are skipped. */
fun parseAirportTable(text: String): Map<String, RegionCoordinate> = text.lineSequence().mapNotNull { line ->
    val parts = line.split(',')
    val latitude = parts.getOrNull(1)?.toDoubleOrNull()
    val longitude = parts.getOrNull(2)?.toDoubleOrNull()
    if (parts.size != 3 || parts[0].length != 3 || Coordinate.valid(latitude, longitude) == null) null
    else parts[0].uppercase() to RegionCoordinate(latitude!!, longitude!!)
}.toMap()

/**
 * Region codes are IATA airport codes. Analyzers often publish centers for only some of
 * their regions, so the rest fall back to the airport's position (iOS geocodes the airport).
 */
fun regionCoordinatesFor(regions: Map<String, String>, analyzer: Map<String, RegionCoordinate>,
    airports: Map<String, RegionCoordinate>): Map<String, RegionCoordinate> =
    analyzer + regions.keys.filter { code -> analyzer.keys.none { it.equals(code, true) } }
        .mapNotNull { code -> airports[code.uppercase()]?.let { code to it } }

class HttpAnalyzerRepository(private val client: BodySource,
    private val airports: () -> Map<String, RegionCoordinate> = { emptyMap() },
    private val cache: ResponseCache? = null) : AnalyzerRepository {
    constructor(client: OkHttpClient, cache: ResponseCache? = null, airports: () -> Map<String, RegionCoordinate> = { emptyMap() }) :
        this(client.saving(cache), airports, cache)

    override suspend fun saved(selection: AnalyzerSelection): Cached<AnalyzerSnapshot>? =
        cache?.restore(ResponseCache.STALE_LIFETIME) { HttpAnalyzerRepository(it, airports).load(selection) }

    override suspend fun load(selection: AnalyzerSelection): AnalyzerSnapshot = coroutineScope {
        val regions = async {
            optional { protocolJson.decodeFromString<Map<String, String>>(client.read(endpoint(selection.host, "api", "config", "regions"))) }
        }
        val centers = async {
            optional { protocolJson.decodeFromString<RegionCoordinatesResponse>(client.read(endpoint(selection.host, "api", "iata-coords"))).coords }
        }
        val defaults = async {
            optional { protocolJson.decodeFromString<MapDefaults>(client.read(endpoint(selection.host, "api", "config", "map"))) }
        }
        val nodesUrl = endpoint(selection.host, "api", "nodes").newBuilder()
            .addQueryParameter("limit", "5000").apply {
                selection.region?.let { addQueryParameter("region", it) }
            }.build()
        val response = protocolJson.decodeFromString<NodesResponse>(client.read(nodesUrl))
        val regionResult = regions.await()
        val mapResult = defaults.await()
        AnalyzerSnapshot(response.nodes.distinctBy { it.publicKey }.sortedBy { it.displayName.lowercase() },
            response.total, regionResult.orEmpty(), mapResult,
            configurationIncomplete = regionResult == null || mapResult == null,
            regionCoordinates = regionCoordinatesFor(regionResult.orEmpty(), centers.await().orEmpty(),
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { airports() }))
    }
}

private suspend fun <T> optional(block: suspend () -> T): T? = try {
    block()
} catch (cancelled: kotlinx.coroutines.CancellationException) {
    throw cancelled
} catch (_: Exception) {
    null
}
