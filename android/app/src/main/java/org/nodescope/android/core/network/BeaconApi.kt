package org.nodescope.android.core.network

import java.io.IOException
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

// Port of iOS BeaconAPIClient.swift: the same validation, errors, cooldown and catalog rules.

@Serializable
data class BeaconServerInfo(val serverVersion: String) {
    /** Strict `X.Y.Z` (optional `+build`); anything else is unrecognized. */
    val versionComponents: List<Int>? get() {
        val sections = serverVersion.split("+")
        if (sections.size > 2) return null
        val release = sections.first()
        if (release.isEmpty()) return null
        if (sections.size == 2) {
            val identifiers = sections[1].split(".")
            if (!identifiers.all { id -> id.isNotEmpty() && id.all { it.isAsciiLetterOrDigit() || it == '-' } }) return null
        }
        val parts = release.split(".")
        if (parts.size != 3) return null
        val numbers = parts.mapNotNull { part ->
            if (part.isEmpty() || (part.length > 1 && part.first() == '0') || !part.all { it in '0'..'9' }) null
            else part.toIntOrNull()
        }
        return numbers.takeIf { it.size == 3 }
    }

    fun requireSupported() {
        val version = versionComponents ?: throw BeaconApiException.UnrecognizedVersion()
        if (compareVersions(version, MINIMUM_VERSION) < 0) throw BeaconApiException.UnsupportedVersion(serverVersion)
    }

    companion object {
        val MINIMUM_VERSION = listOf(2, 0, 3)
        private fun Char.isAsciiLetterOrDigit() = this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'
        private fun compareVersions(a: List<Int>, b: List<Int>): Int {
            for (index in 0 until minOf(a.size, b.size)) if (a[index] != b[index]) return a[index].compareTo(b[index])
            return a.size.compareTo(b.size)
        }
    }
}

sealed class BeaconApiException(message: String) : IOException(message) {
    class UnrecognizedVersion : BeaconApiException("The server did not report a recognized Beacon version.")
    class UnsupportedVersion(version: String) :
        BeaconApiException("Beacon $version is not supported. Version 2.0.3 or newer is required.")
    class RateLimited(val untilMillis: Long) : BeaconApiException(
        "This server is rate limiting requests. Try again after ${
            DateTimeFormatter.ofPattern("h:mm:ss a").withZone(java.time.ZoneId.systemDefault()).format(Instant.ofEpochMilli(untilMillis))
        }."
    )
    class Server(val status: Int, val code: String?) : BeaconApiException("Beacon returned status $status.")
    class MalformedPage : BeaconApiException("Beacon returned an incomplete pagination response.")
    class InvalidResponse : BeaconApiException("The analyzer returned an invalid response.")
}

/** One cooldown per server origin, shared by every client and screen (iOS `BeaconRateLimit`). */
class BeaconRateLimit {
    private val mutex = Mutex()
    private val deadlines = HashMap<String, Long>()

    suspend fun check(origin: String, now: Long) = mutex.withLock {
        val deadline = deadlines[origin]
        if (deadline != null && deadline > now) throw BeaconApiException.RateLimited(deadline)
        deadlines.remove(origin)
        Unit
    }

    suspend fun record(origin: String, retryAfter: String?, now: Long): Long = mutex.withLock {
        val proposed = retryDate(retryAfter, now)
        val deadline = maxOf(deadlines[origin] ?: proposed, proposed)
        deadlines[origin] = deadline
        deadline
    }

    companion object {
        val shared = BeaconRateLimit()

        /** `Retry-After` as seconds or an HTTP date; anything else waits 30 s. */
        fun retryDate(header: String?, now: Long): Long {
            val value = header?.trim()
            if (!value.isNullOrEmpty()) {
                value.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }?.let { seconds ->
                    val millis = seconds * 1000
                    return if (millis >= (Long.MAX_VALUE - now).toDouble()) Long.MAX_VALUE else now + millis.toLong()
                }
                runCatching { ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }
                    .getOrNull()?.let { return maxOf(it, now) }
            }
            return now + 30_000
        }
    }
}

/** A validated HTTPS Beacon origin bound to one [AnalyzerSourceSession]. */
class BeaconApiClient(
    baseUrl: String,
    val sourceSession: AnalyzerSourceSession,
    private val http: OkHttpClient,
    private val rateLimit: BeaconRateLimit = BeaconRateLimit.shared,
    private val now: () -> Long = System::currentTimeMillis,
    private val requestAllowance: MyNodesRequestAllowance? = null,
) {
    /** `https://host[:port]`, lowercase host, no default port, no path. */
    val origin: String
    val baseUrl: HttpUrl

    init {
        val url = baseUrl.toHttpUrlOrNull() ?: throw BeaconApiException.InvalidResponse()
        val path = url.encodedPath
        if (url.scheme != "https" || url.host.isEmpty() || url.username.isNotEmpty() || url.password.isNotEmpty() ||
            url.query != null || url.fragment != null || (path != "/" && path.isNotEmpty())) throw IllegalArgumentException("Invalid analyzer URL.")
        val host = url.host.lowercase()
        origin = if (url.port == 443) "https://$host" else "https://$host:${url.port}"
        this.baseUrl = checkNotNull("$origin/".toHttpUrlOrNull())
    }

    val cacheIdentifier: String get() = "beacon|$origin"

    fun replacingSourceSession(session: AnalyzerSourceSession) = BeaconApiClient(origin, session, http, rateLimit, now, requestAllowance)

    fun budgetingRequests(budget: MyNodesRequestBudget, node: String) =
        BeaconApiClient(origin, sourceSession, http.forMyNodes(), rateLimit, now, MyNodesRequestAllowance(budget, node))

    suspend fun checkCooldown() {
        sourceSession.check()
        rateLimit.check(cacheIdentifier, now())
        sourceSession.check()
    }

    suspend fun recordCooldown(retryAfter: String?): Long = rateLimit.record(cacheIdentifier, retryAfter, now())

    fun currentTime(): Long = now()

    suspend fun serverInfo(): BeaconServerInfo {
        val info = get(BeaconServerInfo.serializer(), listOf("info"))
        if (info.versionComponents == null) throw BeaconApiException.UnrecognizedVersion()
        return info
    }

    suspend fun <T> get(serializer: KSerializer<T>, path: List<String>, query: List<Pair<String, String>> = emptyList()): T {
        sourceSession.check()
        rateLimit.check(cacheIdentifier, now())
        sourceSession.check()
        require(path.isNotEmpty() && path.all { it.isNotEmpty() && it != "." && it != ".." }) { "Invalid analyzer URL." }
        val url = baseUrl.newBuilder().addPathSegment("api").addPathSegment("v1")
            .apply { path.forEach(::addPathSegment); query.forEach { (name, value) -> addQueryParameter(name, value) } }
            .build()
        val request = Request.Builder().url(url).header("Accept", "application/json").build()
        try {
            val (status, body, retryAfter) = requestAllowance?.perform { execute(request) } ?: execute(request)
            sourceSession.check()
            if (status == 429) {
                val deadline = rateLimit.record(cacheIdentifier, retryAfter, now())
                sourceSession.check()
                throw BeaconApiException.RateLimited(deadline)
            }
            if (status !in 200..299) {
                val code = runCatching {
                    protocolJson.parseToJsonElement(body).jsonObject["error"]?.jsonObject?.get("code")?.jsonPrimitive?.contentOrNull
                }.getOrNull()
                throw BeaconApiException.Server(status, code)
            }
            return try {
                protocolJson.decodeFromString(serializer, body)
            } catch (e: kotlinx.serialization.SerializationException) {
                throw BeaconApiException.InvalidResponse()
            } catch (e: IllegalArgumentException) {
                throw BeaconApiException.InvalidResponse()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            sourceSession.check()
            throw e
        }
    }

    private suspend fun execute(request: Request): Triple<Int, String, String?> = suspendCancellableCoroutine { continuation ->
        val call = http.newCall(request)
        val registration = sourceSession.registerCancellation { call.cancel() }
        continuation.invokeOnCancellation { call.cancel(); sourceSession.removeCancellation(registration) }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                sourceSession.removeCancellation(registration)
                continuation.resumeWithException(e)
            }
            override fun onResponse(call: Call, response: Response) {
                sourceSession.removeCancellation(registration)
                try {
                    val result = response.use { Triple(it.code, it.body?.string().orEmpty(), it.header("Retry-After")) }
                    continuation.resume(result)
                } catch (e: Exception) {
                    continuation.resumeWithException(e)
                }
            }
        })
    }
}

/** Where verified server infos persist between launches (SharedPreferences in the app). */
interface BeaconServerStore {
    fun load(): String?
    fun save(value: String)
}

class InMemoryBeaconServerStore(private var value: String? = null) : BeaconServerStore {
    override fun load() = value
    override fun save(value: String) { this.value = value }
}

/** Remembers which hosts are verified Beacon servers; concurrent probes for one source share a request. */
class BeaconServerCatalog(private val store: BeaconServerStore) {
    private val mutex = Mutex()
    private val pending = HashMap<String, CompletableDeferred<BeaconServerInfo>>()
    private val serializer = MapSerializer(String.serializer(), BeaconServerInfo.serializer())

    fun knownInfo(client: BeaconApiClient): BeaconServerInfo? = saved()[client.cacheIdentifier]

    suspend fun resolve(client: BeaconApiClient, refreshKnown: Boolean = false): BeaconServerInfo {
        client.sourceSession.check()
        if (!refreshKnown) saved()[client.cacheIdentifier]?.let { known ->
            known.requireSupported()
            return known
        }
        val key = "${client.cacheIdentifier}|${client.sourceSession.id}"
        val (deferred, owner) = mutex.withLock {
            pending[key]?.let { it to false } ?: CompletableDeferred<BeaconServerInfo>().also { pending[key] = it }.let { it to true }
        }
        if (owner) {
            try {
                deferred.complete(probe(client))
            } catch (e: Throwable) {
                deferred.completeExceptionally(e)
            } finally {
                mutex.withLock { if (pending[key] === deferred) pending.remove(key) }
            }
        }
        val info = deferred.await()
        client.sourceSession.check()
        info.requireSupported()
        return info
    }

    suspend fun probe(client: BeaconApiClient): BeaconServerInfo {
        val info = client.serverInfo()
        client.sourceSession.check()
        val records = saved().toMutableMap()
        records[client.cacheIdentifier] = info
        store.save(protocolJson.encodeToString(serializer, records))
        return info
    }

    private fun saved(): Map<String, BeaconServerInfo> = store.load()?.let {
        runCatching { protocolJson.decodeFromString(serializer, it) }.getOrNull()
    }.orEmpty()

    companion object {
        const val STORAGE_KEY = "beacon-server-info-v1"
    }
}

internal fun isFullBeaconKey(value: String): Boolean =
    value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }

internal fun isUuid(value: String): Boolean =
    runCatching { UUID.fromString(value) }.isSuccess && value.length == 36

internal fun isoMillis(millis: Long?): String? = millis?.let { Instant.ofEpochMilli(it).toString() }
