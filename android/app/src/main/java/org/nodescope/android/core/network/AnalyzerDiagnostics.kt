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
enum class AnalyzerCapability(val title: String, internal val path: String, internal val query: Pair<String, String>? = null) {
    MAP_CONFIGURATION("Map configuration", "api/config/map"),
    NODES("Nodes and routes", "api/nodes", "limit" to "1"),
    CHANNELS("Channels", "api/channels", "limit" to "1"),
    OBSERVERS("Observers", "api/observers", "limit" to "1"),
    REGIONS("Region filtering", "api/config/regions");

    val isRequired: Boolean get() = this == MAP_CONFIGURATION || this == NODES
}

enum class DiagnosticLevel { SUCCESS, WARNING, FAILURE }

data class CapabilityCheck(val capability: AnalyzerCapability, val level: DiagnosticLevel, val detail: String)

data class DiagnosticReport(
    val host: String, val checkedAt: Long,
    val connectionLevel: DiagnosticLevel, val connectionDetail: String,
    val compatibilityLevel: DiagnosticLevel, val compatibilityDetail: String,
    val capabilities: List<CapabilityCheck>,
)

sealed interface ProbeOutcome {
    data class Available(val milliseconds: Long) : ProbeOutcome
    data class AuthenticationRequired(val status: Int) : ProbeOutcome
    data class Unavailable(val status: Int) : ProbeOutcome
    data object InvalidJson : ProbeOutcome
    data class NetworkFailure(val message: String) : ProbeOutcome
}

class AnalyzerDiagnostics(client: OkHttpClient, private val clock: () -> Long = System::currentTimeMillis) {
    // Short timeouts and no shared cache, so a check reflects the analyzer right now.
    private val http = client.newBuilder().cache(null)
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).callTimeout(15, TimeUnit.SECONDS).build()

    suspend fun run(host: String): DiagnosticReport = run("https://${normalizeHost(host)}/".toHttpUrl(), host)

    internal suspend fun run(base: HttpUrl, host: String): DiagnosticReport = coroutineScope {
        val results = AnalyzerCapability.entries.map { capability -> async { capability to probe(base, capability) } }.awaitAll()
        report(host, clock(), results)
    }

    private suspend fun probe(base: HttpUrl, capability: AnalyzerCapability): ProbeOutcome {
        val url = base.newBuilder().addPathSegments(capability.path).apply { capability.query?.let { addQueryParameter(it.first, it.second) } }.build()
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
            val ordered = AnalyzerCapability.entries.mapNotNull { capability -> results.firstOrNull { it.first == capability } }
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
