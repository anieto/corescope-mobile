package org.nodescope.android.core.network

import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

/** Same read-only probes as iOS `AnalyzerDiagnosticsService`. */
/** [path] is the CoreScope probe; the Beacon phase 3 rows have none and are reported for Beacon only. */
enum class AnalyzerCapability(val title: String, internal val path: String? = null, internal val query: Pair<String, String>? = null) {
    MAP_CONFIGURATION("Map configuration", "api/config/map"),
    NODES("Nodes and routes", "api/nodes", "limit" to "1"),
    CHANNELS("Channels", "api/channels", "limit" to "1"),
    OBSERVERS("Observers", "api/observers", "limit" to "1"),
    REGIONS("Region filtering", "api/config/regions"),
    NEIGHBORS("Node neighbors"),
    NODE_ANALYTICS("Node analytics"),
    OBSERVER_ACTIVITY("Observer activity"),
    OBSERVER_TELEMETRY("Observer telemetry"),
    CHANNEL_MESSAGES("Channel messages");

    val isRequired: Boolean get() = this == MAP_CONFIGURATION || this == NODES

    companion object {
        /** The rows a CoreScope check probes, in order; its request contract is unchanged. */
        val coreScopeProbed: List<AnalyzerCapability> get() = entries.filter { it.path != null }
    }
}

enum class DiagnosticLevel { SUCCESS, WARNING, FAILURE }

data class CapabilityCheck(val capability: AnalyzerCapability, val level: DiagnosticLevel, val detail: String)

data class DiagnosticReport(
    val host: String, val checkedAt: Long,
    val connectionLevel: DiagnosticLevel, val connectionDetail: String,
    val compatibilityLevel: DiagnosticLevel, val compatibilityDetail: String,
    val capabilities: List<CapabilityCheck>,
    val backendType: String = "CoreScope",
    /** Beacon only: the version the server last reported. */
    val serverVersion: String? = null,
)

sealed interface ProbeOutcome {
    data class Available(val milliseconds: Long) : ProbeOutcome
    data class AuthenticationRequired(val status: Int) : ProbeOutcome
    data class Unavailable(val status: Int) : ProbeOutcome
    data object InvalidJson : ProbeOutcome
    data class NetworkFailure(val message: String) : ProbeOutcome
}

class AnalyzerDiagnostics(
    client: OkHttpClient,
    private val clock: () -> Long = System::currentTimeMillis,
    /** The verified Beacon backend for a host, if that's what it is. */
    private val beaconBackend: (String) -> BeaconBackend? = { null },
) {
    // Short timeouts and no shared cache, so a check reflects the analyzer right now.
    private val http = client.newBuilder().cache(null)
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).callTimeout(15, TimeUnit.SECONDS).build()

    suspend fun run(host: String): DiagnosticReport {
        beaconBackend(host)?.let { return runBeacon(host, it) }
        return run("https://${normalizeHost(host)}/".toHttpUrl(), host)
    }

    /**
     * Beacon has no CoreScope probes: recheck `/info`, then (on a supported version) make three
     * bounded one-row family checks for nodes, observers and keyed channels (iOS `runBeacon`).
     * A failed family check is a warning, never a compatibility failure. Four requests at most.
     */
    internal suspend fun runBeacon(host: String, backend: BeaconBackend): DiagnosticReport {
        val checks = AnalyzerCapability.entries.map { capability -> beaconDefault(capability, backend) }
        return try {
            val info = backend.client.serverInfo()
            var reported = checks
            val (compatibility, detail) = try {
                info.requireSupported()
                reported = beaconFamilyChecks(backend, checks)
                DiagnosticLevel.SUCCESS to "Beacon version and bounded browse-family checks completed."
            } catch (e: BeaconApiException) {
                DiagnosticLevel.FAILURE to (e.message ?: "This Beacon version is not supported.")
            }
            DiagnosticReport(host, clock(), DiagnosticLevel.SUCCESS, "Beacon responded over HTTPS.", compatibility, detail,
                reported, "Beacon", info.serverVersion)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            DiagnosticReport(host, clock(), DiagnosticLevel.WARNING, e.message ?: networkMessage(e), DiagnosticLevel.WARNING,
                "Beacon could not be rechecked. The selected server type is unchanged.", checks, "Beacon",
                backend.serverInfo.serverVersion)
        }
    }

    private fun beaconDefault(capability: AnalyzerCapability, backend: BeaconBackend): CapabilityCheck {
        if (capability == AnalyzerCapability.MAP_CONFIGURATION) return CapabilityCheck(capability, DiagnosticLevel.WARNING,
            "Beacon does not provide map defaults; NodeScope uses region coordinates.")
        val required = when (capability) {
            AnalyzerCapability.NEIGHBORS -> BackendCapability.NEIGHBORS
            AnalyzerCapability.NODE_ANALYTICS -> BackendCapability.NODE_ANALYTICS
            AnalyzerCapability.OBSERVER_ACTIVITY, AnalyzerCapability.OBSERVER_TELEMETRY -> BackendCapability.OBSERVER_ANALYTICS
            AnalyzerCapability.CHANNELS, AnalyzerCapability.CHANNEL_MESSAGES -> BackendCapability.SERVER_CHANNELS
            else -> null
        }
        if (required != null && required !in backend.capabilities) return CapabilityCheck(capability, DiagnosticLevel.WARNING, required.beaconExplanation)
        return CapabilityCheck(capability, DiagnosticLevel.SUCCESS, when (capability) {
            AnalyzerCapability.NODE_ANALYTICS -> "Supported with Beacon’s seven-day observation-history limit; endpoint not probed."
            AnalyzerCapability.OBSERVER_ACTIVITY -> "Supported with Beacon observation counts; endpoint not probed."
            AnalyzerCapability.OBSERVER_TELEMETRY -> "Battery, airtime, and radio telemetry are supported; endpoint not probed."
            AnalyzerCapability.CHANNELS, AnalyzerCapability.CHANNEL_MESSAGES -> "Server-decrypted keyed channels are supported; endpoint not probed."
            else -> "Supported by the Beacon adapter; endpoint not probed."
        })
    }

    @kotlinx.serialization.Serializable private data class ProbePage(val items: kotlinx.serialization.json.JsonArray)

    private suspend fun beaconFamilyChecks(backend: BeaconBackend, defaults: List<CapabilityCheck>): List<CapabilityCheck> = coroutineScope {
        suspend fun family(path: String, vararg query: Pair<String, String>) = runCatching {
            backend.client.get(ProbePage.serializer(), listOf(path), listOf("limit" to "1") + query)
        }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }.isSuccess
        val nodes = async { family("nodes") }
        val observers = async { family("observers") }
        val channels = async { family("channels", "keyKnown" to "true") }
        val results = mapOf("nodes" to nodes.await(), "observers" to observers.await(), "channels" to channels.await())
        defaults.map { check ->
            val group = when (check.capability) {
                AnalyzerCapability.NODES, AnalyzerCapability.NEIGHBORS, AnalyzerCapability.NODE_ANALYTICS -> "nodes"
                AnalyzerCapability.OBSERVERS, AnalyzerCapability.OBSERVER_ACTIVITY, AnalyzerCapability.OBSERVER_TELEMETRY -> "observers"
                AnalyzerCapability.CHANNELS, AnalyzerCapability.CHANNEL_MESSAGES -> "channels"
                else -> null
            } ?: return@map check
            if (results[group] != true) CapabilityCheck(check.capability, DiagnosticLevel.WARNING, "The bounded Beacon family check did not succeed.")
            else check.copy(detail = check.detail.replace("; endpoint not probed", "; family check passed"))
        }
    }

    internal suspend fun run(base: HttpUrl, host: String): DiagnosticReport = coroutineScope {
        val results = AnalyzerCapability.coreScopeProbed.map { capability -> async { capability to probe(base, capability) } }.awaitAll()
        report(host, clock(), results)
    }

    private suspend fun probe(base: HttpUrl, capability: AnalyzerCapability): ProbeOutcome {
        val url = base.newBuilder().addPathSegments(checkNotNull(capability.path)).apply { capability.query?.let { addQueryParameter(it.first, it.second) } }.build()
        val started = clock()
        return try {
            val body = http.read(url)
            if (runCatching { protocolJson.parseToJsonElement(body) }.isFailure) ProbeOutcome.InvalidJson
            else ProbeOutcome.Available((clock() - started).coerceAtLeast(1))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: HttpFailure) {
            if (e.status == 401 || e.status == 403) ProbeOutcome.AuthenticationRequired(e.status) else ProbeOutcome.Unavailable(e.status)
        } catch (e: Exception) {
            ProbeOutcome.NetworkFailure(networkMessage(e))
        }
    }

    companion object {
        fun networkMessage(error: Throwable): String = when (error) {
            is UnknownHostException -> "The analyzer host could not be found."
            is SocketTimeoutException -> "The analyzer did not respond before the request timed out."
            is SSLException -> "A secure connection to the analyzer could not be established."
            is ConnectException, is NoRouteToHostException -> "A connection to the analyzer could not be established."
            else -> error.message ?: "The analyzer could not be reached."
        }

        /** Mirrors the iOS classification of connection, compatibility and each capability. */
        fun report(host: String, checkedAt: Long, results: List<Pair<AnalyzerCapability, ProbeOutcome>>): DiagnosticReport {
            val ordered = AnalyzerCapability.coreScopeProbed.mapNotNull { capability -> results.firstOrNull { it.first == capability } }
            val responded = ordered.any { it.second !is ProbeOutcome.NetworkFailure }
            val authFailure = ordered.any { it.second is ProbeOutcome.AuthenticationRequired }
            val requiredAvailable = ordered.filter { it.first.isRequired }.all { it.second is ProbeOutcome.Available }
            val (connectionLevel, connectionDetail) = if (responded) DiagnosticLevel.SUCCESS to "Analyzer responded over HTTPS."
                else DiagnosticLevel.FAILURE to (ordered.firstNotNullOfOrNull { (it.second as? ProbeOutcome.NetworkFailure)?.message }
                    ?: "The analyzer could not be reached.")
            val (compatibilityLevel, compatibilityDetail) = when {
                authFailure -> DiagnosticLevel.FAILURE to "The analyzer or an upstream proxy requires authentication."
                requiredAvailable -> DiagnosticLevel.SUCCESS to "Core NodeScope features are supported."
                responded -> DiagnosticLevel.FAILURE to "The server responded, but its API is not CoreScope-compatible."
                else -> DiagnosticLevel.FAILURE to "Compatibility could not be checked while the analyzer is unreachable."
            }
            return DiagnosticReport(host, checkedAt, connectionLevel, connectionDetail, compatibilityLevel, compatibilityDetail,
                ordered.map { (capability, outcome) -> check(capability, outcome) })
        }

        private fun check(capability: AnalyzerCapability, outcome: ProbeOutcome): CapabilityCheck {
            val optionalLevel = if (capability.isRequired) DiagnosticLevel.FAILURE else DiagnosticLevel.WARNING
            return when (outcome) {
                is ProbeOutcome.Available -> CapabilityCheck(capability, DiagnosticLevel.SUCCESS, "Available · ${outcome.milliseconds} ms")
                is ProbeOutcome.AuthenticationRequired -> CapabilityCheck(capability, DiagnosticLevel.FAILURE, "Authentication required · HTTP ${outcome.status}")
                is ProbeOutcome.Unavailable -> CapabilityCheck(capability, optionalLevel,
                    if (outcome.status == 404) "Not supported" else "Unavailable · HTTP ${outcome.status}")
                ProbeOutcome.InvalidJson -> CapabilityCheck(capability, optionalLevel, "Response format not recognized")
                is ProbeOutcome.NetworkFailure -> CapabilityCheck(capability, DiagnosticLevel.FAILURE, "Could not be checked")
            }
        }
    }
}
