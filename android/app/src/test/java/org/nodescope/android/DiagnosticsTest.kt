package org.nodescope.android

import org.nodescope.android.core.design.UiText

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Test
import org.nodescope.android.core.network.*

class DiagnosticsTest {
    private fun serve(responses: Map<String, MockResponse>, block: suspend (MockWebServer) -> Unit) = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = responses[request.requestUrl!!.encodedPath] ?: MockResponse().setResponseCode(404)
            }
            block(server)
        }
    }

    @Test fun compatibleAnalyzerWithOptionalFeatureMissing() = serve(mapOf(
        "/api/config/map" to MockResponse().setBody("""{"center":[30,-97],"zoom":8}"""),
        "/api/nodes" to MockResponse().setBody("""{"nodes":[],"total":0}"""),
        "/api/channels" to MockResponse().setBody("not json"),
        "/api/observers" to MockResponse().setBody("""{"observers":[]}"""),
    )) { server ->
        val report = AnalyzerDiagnostics(OkHttpClient()).run(server.url("/"), "example.org")
        assertEquals(DiagnosticLevel.SUCCESS, report.connectionLevel)
        assertResource(R.string.settings_diagnostics_core_nodescope_features_are_supported, report.compatibilityDetail)
        val byCapability = report.capabilities.associateBy { it.capability }
        assertEquals(DiagnosticLevel.WARNING, byCapability.getValue(AnalyzerCapability.CHANNELS).level)
        assertResource(R.string.settings_diagnostics_response_format_not_recognized, byCapability.getValue(AnalyzerCapability.CHANNELS).detail)
        assertResource(R.string.settings_diagnostics_not_supported, byCapability.getValue(AnalyzerCapability.REGIONS).detail)
        val available = byCapability.getValue(AnalyzerCapability.NODES).detail as UiText.Resource
        assertEquals(R.string.settings_diagnostics_available_ms, available.id)
        assertTrue(available.args.single() is Long)
        // Stable order, and only the CoreScope probes: the Beacon-only rows are never requested here.
        assertEquals(AnalyzerCapability.coreScopeProbed, report.capabilities.map { it.capability })
        assertEquals(5, AnalyzerCapability.coreScopeProbed.size)
    }

    @Test fun authenticationAndIncompatibleServersAreExplained() {
        val auth = AnalyzerDiagnostics.report("h", 0, AnalyzerCapability.entries.map { it to ProbeOutcome.AuthenticationRequired(401) })
        assertResource(R.string.settings_diagnostics_the_analyzer_or_an_upstream_proxy_requires_authentication, auth.compatibilityDetail)
        assertResource(R.string.settings_diagnostics_authentication_required_http, auth.capabilities.first().detail, 401)
        val wrongServer = AnalyzerDiagnostics.report("h", 0, AnalyzerCapability.entries.map { it to ProbeOutcome.Unavailable(500) })
        assertEquals(DiagnosticLevel.FAILURE, wrongServer.compatibilityLevel)
        assertResource(R.string.settings_diagnostics_the_server_responded_but_its_api_is_not, wrongServer.compatibilityDetail)
        assertEquals(DiagnosticLevel.FAILURE, wrongServer.capabilities.first { it.capability.isRequired }.level)
        assertEquals(DiagnosticLevel.WARNING, wrongServer.capabilities.first { !it.capability.isRequired }.level)
        val offline = AnalyzerDiagnostics.report("h", 0, AnalyzerCapability.entries.map { it to ProbeOutcome.NetworkFailure(UiText.Resource(R.string.settings_diagnostics_the_analyzer_host_could_not_be_found)) })
        assertResource(R.string.settings_diagnostics_the_analyzer_host_could_not_be_found, offline.connectionDetail)
        assertResource(R.string.settings_diagnostics_compatibility_could_not_be_checked_while_the_analyzer, offline.compatibilityDetail)
        assertResource(R.string.settings_diagnostics_the_analyzer_host_could_not_be_found, AnalyzerDiagnostics.networkMessage(java.net.UnknownHostException("x")))
    }
}

private fun assertResource(expected: Int, value: UiText, vararg args: Any) {
    val text = value as UiText.Resource
    assertEquals(expected, text.id)
    assertEquals(args.toList(), text.args.toList())
}
