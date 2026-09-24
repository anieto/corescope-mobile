package org.nodescope.android

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
        assertEquals("Core NodeScope features are supported.", report.compatibilityDetail)
        val byCapability = report.capabilities.associateBy { it.capability }
        assertEquals(DiagnosticLevel.WARNING, byCapability.getValue(AnalyzerCapability.CHANNELS).level)
        assertEquals("Response format not recognized", byCapability.getValue(AnalyzerCapability.CHANNELS).detail)
        assertEquals("Not supported", byCapability.getValue(AnalyzerCapability.REGIONS).detail)
        assertTrue(byCapability.getValue(AnalyzerCapability.NODES).detail.startsWith("Available · "))
        assertEquals(AnalyzerCapability.entries, report.capabilities.map { it.capability }) // stable order
    }

    @Test fun authenticationAndIncompatibleServersAreExplained() {
        val auth = AnalyzerDiagnostics.report("h", 0, AnalyzerCapability.entries.map { it to ProbeOutcome.AuthenticationRequired(401) })
        assertEquals("The analyzer or an upstream proxy requires authentication.", auth.compatibilityDetail)
        assertEquals("Authentication required · HTTP 401", auth.capabilities.first().detail)
        val wrongServer = AnalyzerDiagnostics.report("h", 0, AnalyzerCapability.entries.map { it to ProbeOutcome.Unavailable(500) })
        assertEquals(DiagnosticLevel.FAILURE, wrongServer.compatibilityLevel)
        assertEquals("The server responded, but its API is not CoreScope-compatible.", wrongServer.compatibilityDetail)
        assertEquals(DiagnosticLevel.FAILURE, wrongServer.capabilities.first { it.capability.isRequired }.level)
        assertEquals(DiagnosticLevel.WARNING, wrongServer.capabilities.first { !it.capability.isRequired }.level)
        val offline = AnalyzerDiagnostics.report("h", 0, AnalyzerCapability.entries.map { it to ProbeOutcome.NetworkFailure("The analyzer host could not be found.") })
        assertEquals("The analyzer host could not be found.", offline.connectionDetail)
        assertEquals("Compatibility could not be checked while the analyzer is unreachable.", offline.compatibilityDetail)
        assertEquals("The analyzer host could not be found.", AnalyzerDiagnostics.networkMessage(java.net.UnknownHostException("x")))
    }
}
