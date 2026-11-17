package org.nodescope.android.core.network

import org.nodescope.android.core.design.UiText
import androidx.annotation.StringRes
import org.nodescope.android.R
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
enum class AnalyzerCapability(@param:StringRes val titleRes: Int, internal val path: String? = null, internal val query: Pair<String, String>? = null) {
    MAP_CONFIGURATION(R.string.settings_diagnostics_map_configuration, "api/config/map"),
    NODES(R.string.settings_diagnostics_nodes_and_routes, "api/nodes", "limit" to "1"),
    CHANNELS(R.string.settings_diagnostics_channels, "api/channels", "limit" to "1"),
    OBSERVERS(R.string.settings_diagnostics_observers, "api/observers", "limit" to "1"),
    REGIONS(R.string.settings_diagnostics_region_filtering, "api/config/regions"),
    NEIGHBORS(R.string.settings_diagnostics_node_neighbors),
    NODE_ANALYTICS(R.string.settings_diagnostics_node_analytics),
    OBSERVER_ACTIVITY(R.string.settings_diagnostics_observer_activity),
    OBSERVER_TELEMETRY(R.string.settings_diagnostics_observer_telemetry),
    CHANNEL_MESSAGES(R.string.settings_diagnostics_channel_messages);

    val isRequired: Boolean get() = this == MAP_CONFIGURATION || this == NODES

    companion object {
        /** The rows a CoreScope check probes, in order; its request contract is unchanged. */
        val coreScopeProbed: List<AnalyzerCapability> get() = entries.filter { it.path != null }
    }
}

enum class DiagnosticLevel { SUCCESS, WARNING, FAILURE }

data class CapabilityCheck(val capability: AnalyzerCapability, val level: DiagnosticLevel, val detail: UiText)

data class DiagnosticReport(
    val host: String, val checkedAt: Long,
    val connectionLevel: DiagnosticLevel, val connectionDetail: UiText,
    val compatibilityLevel: DiagnosticLevel, val compatibilityDetail: UiText,
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
    data class NetworkFailure(val message: UiText) : ProbeOutcome
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
                DiagnosticLevel.SUCCESS to UiText.Resource(R.string.settings_diagnostics_beacon_version_and_bounded_browse_family_checks_completed)
            } catch (e: BeaconApiException) {
                DiagnosticLevel.FAILURE to (e.analyzerErrorText(R.string.settings_diagnostics_this_beacon_version_is_not_supported))
            }
            DiagnosticReport(host, clock(), DiagnosticLevel.SUCCESS, UiText.Resource(R.string.settings_diagnostics_beacon_responded_over_https), compatibility, detail,
                reported, "Beacon", info.serverVersion)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            DiagnosticReport(host, clock(), DiagnosticLevel.WARNING, networkMessage(e), DiagnosticLevel.WARNING,
                UiText.Resource(R.string.settings_diagnostics_beacon_could_not_be_rechecked_the_selected_server), checks, "Beacon",
                backend.serverInfo.serverVersion)
        }
    }

    private fun beaconDefault(capability: AnalyzerCapability, backend: BeaconBackend): CapabilityCheck {
        if (capability == AnalyzerCapability.MAP_CONFIGURATION) return CapabilityCheck(capability, DiagnosticLevel.WARNING,
            UiText.Resource(R.string.settings_diagnostics_beacon_does_not_provide_map_defaults_nodescope_uses))
        val required = when (capability) {
            AnalyzerCapability.NEIGHBORS -> BackendCapability.NEIGHBORS
            AnalyzerCapability.NODE_ANALYTICS -> BackendCapability.NODE_ANALYTICS
            AnalyzerCapability.OBSERVER_ACTIVITY, AnalyzerCapability.OBSERVER_TELEMETRY -> BackendCapability.OBSERVER_ANALYTICS
            AnalyzerCapability.CHANNELS, AnalyzerCapability.CHANNEL_MESSAGES -> BackendCapability.SERVER_CHANNELS
            else -> null
        }
        if (required != null && required !in backend.capabilities) return CapabilityCheck(capability, DiagnosticLevel.WARNING, required.beaconUiExplanation)
        return CapabilityCheck(capability, DiagnosticLevel.SUCCESS, when (capability) {
            AnalyzerCapability.NODE_ANALYTICS -> UiText.Resource(R.string.settings_diagnostics_supported_with_beacon_s_seven_day_observation_history)
            AnalyzerCapability.OBSERVER_ACTIVITY -> UiText.Resource(R.string.settings_diagnostics_supported_with_beacon_observation_counts_endpoint_not_probed)
            AnalyzerCapability.OBSERVER_TELEMETRY -> UiText.Resource(R.string.settings_diagnostics_battery_airtime_and_radio_telemetry_are_supported_endpoint)
            AnalyzerCapability.CHANNELS, AnalyzerCapability.CHANNEL_MESSAGES -> UiText.Resource(R.string.settings_diagnostics_server_decrypted_keyed_channels_are_supported_endpoint_not)
            else -> UiText.Resource(R.string.settings_diagnostics_supported_by_the_beacon_adapter_endpoint_not_probed)
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
            if (results[group] != true) CapabilityCheck(check.capability, DiagnosticLevel.WARNING, UiText.Resource(R.string.settings_diagnostics_the_bounded_beacon_family_check_did_not_succeed))
            else if (check.level == DiagnosticLevel.SUCCESS) check.copy(detail = beaconFamilyPassed(check.capability))
            else check
        }
    }

    private fun beaconFamilyPassed(capability: AnalyzerCapability) = UiText.Resource(when (capability) {
        AnalyzerCapability.NODE_ANALYTICS -> R.string.settings_diagnostics_supported_with_beacon_s_seven_day_observation_history_checked
        AnalyzerCapability.OBSERVER_ACTIVITY -> R.string.settings_diagnostics_supported_with_beacon_observation_counts_endpoint_not_probed_checked
        AnalyzerCapability.OBSERVER_TELEMETRY -> R.string.settings_diagnostics_battery_airtime_and_radio_telemetry_are_supported_endpoint_checked
        AnalyzerCapability.CHANNELS, AnalyzerCapability.CHANNEL_MESSAGES -> R.string.settings_diagnostics_server_decrypted_keyed_channels_are_supported_endpoint_not_checked
        else -> R.string.settings_diagnostics_supported_by_the_beacon_adapter_endpoint_not_probed_checked
    })

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
        fun networkMessage(error: Throwable): UiText = when (error) {
            is UnknownHostException -> UiText.Resource(R.string.settings_diagnostics_the_analyzer_host_could_not_be_found)
            is SocketTimeoutException -> UiText.Resource(R.string.settings_diagnostics_the_analyzer_did_not_respond_before_the_request)
            is SSLException -> UiText.Resource(R.string.settings_diagnostics_a_secure_connection_to_the_analyzer_could_not)
            is ConnectException, is NoRouteToHostException -> UiText.Resource(R.string.settings_diagnostics_a_connection_to_the_analyzer_could_not_be)
            else -> error.analyzerErrorText(R.string.settings_diagnostics_the_analyzer_could_not_be_reached)
        }

        /** Mirrors the iOS classification of connection, compatibility and each capability. */
        fun report(host: String, checkedAt: Long, results: List<Pair<AnalyzerCapability, ProbeOutcome>>): DiagnosticReport {
            val ordered = AnalyzerCapability.coreScopeProbed.mapNotNull { capability -> results.firstOrNull { it.first == capability } }
            val responded = ordered.any { it.second !is ProbeOutcome.NetworkFailure }
            val authFailure = ordered.any { it.second is ProbeOutcome.AuthenticationRequired }
            val requiredAvailable = ordered.filter { it.first.isRequired }.all { it.second is ProbeOutcome.Available }
            val (connectionLevel, connectionDetail) = if (responded) DiagnosticLevel.SUCCESS to UiText.Resource(R.string.settings_diagnostics_analyzer_responded_over_https)
                else DiagnosticLevel.FAILURE to (ordered.firstNotNullOfOrNull { (it.second as? ProbeOutcome.NetworkFailure)?.message }
                    ?: UiText.Resource(R.string.settings_diagnostics_the_analyzer_could_not_be_reached))
            val (compatibilityLevel, compatibilityDetail) = when {
                authFailure -> DiagnosticLevel.FAILURE to UiText.Resource(R.string.settings_diagnostics_the_analyzer_or_an_upstream_proxy_requires_authentication)
                requiredAvailable -> DiagnosticLevel.SUCCESS to UiText.Resource(R.string.settings_diagnostics_core_nodescope_features_are_supported)
                responded -> DiagnosticLevel.FAILURE to UiText.Resource(R.string.settings_diagnostics_the_server_responded_but_its_api_is_not)
                else -> DiagnosticLevel.FAILURE to UiText.Resource(R.string.settings_diagnostics_compatibility_could_not_be_checked_while_the_analyzer)
            }
            return DiagnosticReport(host, checkedAt, connectionLevel, connectionDetail, compatibilityLevel, compatibilityDetail,
                ordered.map { (capability, outcome) -> check(capability, outcome) })
        }

        private fun check(capability: AnalyzerCapability, outcome: ProbeOutcome): CapabilityCheck {
            val optionalLevel = if (capability.isRequired) DiagnosticLevel.FAILURE else DiagnosticLevel.WARNING
            return when (outcome) {
                is ProbeOutcome.Available -> CapabilityCheck(capability, DiagnosticLevel.SUCCESS, UiText.Resource(R.string.settings_diagnostics_available_ms, outcome.milliseconds))
                is ProbeOutcome.AuthenticationRequired -> CapabilityCheck(capability, DiagnosticLevel.FAILURE, UiText.Resource(R.string.settings_diagnostics_authentication_required_http, outcome.status))
                is ProbeOutcome.Unavailable -> CapabilityCheck(capability, optionalLevel,
                    if (outcome.status == 404) UiText.Resource(R.string.settings_diagnostics_not_supported) else UiText.Resource(R.string.settings_diagnostics_unavailable_http, outcome.status))
                ProbeOutcome.InvalidJson -> CapabilityCheck(capability, optionalLevel, UiText.Resource(R.string.settings_diagnostics_response_format_not_recognized))
                is ProbeOutcome.NetworkFailure -> CapabilityCheck(capability, DiagnosticLevel.FAILURE, UiText.Resource(R.string.settings_diagnostics_could_not_be_checked))
            }
        }
    }
}
