package org.nodescope.android

import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test
import org.nodescope.android.feature.map.CartoRequests
import org.nodescope.android.feature.map.nodeFeatures
import org.nodescope.android.core.model.MeshNode

class CartoRequestsTest {
    private val auth = CartoRequests("test-key", "org.nodescope.android", "AABB")
    @Test fun authenticatesNestedCartoResourcesAndReplacesOldKey() {
        for (host in listOf("basemaps.cartocdn.com", "tiles.basemaps.cartocdn.com", "a.basemaps.cartocdn.com")) {
            val request = auth.authenticate(Request.Builder().url("https://$host/style.json?key=old&test=1").build())
            assertEquals(listOf("test-key"), request.url.queryParameterValues("key"))
            assertEquals("1", request.url.queryParameter("test"))
            assertEquals("org.nodescope.android", request.header("X-Android-Package"))
            assertEquals("AABB", request.header("X-Android-Cert"))
        }
    }
    @Test fun neverSendsCredentialsToAnalyzersLookalikeHostsOrCleartext() {
        for (url in listOf("https://analyzer.example/nodes", "https://basemaps.cartocdn.com.evil.example/", "https://evilbasemaps.cartocdn.com/", "http://basemaps.cartocdn.com/")) {
            val request = Request.Builder().url(url).build()
            assertSame(request, auth.authenticate(request))
        }
    }
    @Test fun nodeFeaturesPreserveIdentityAndOmitMissingOrInvalidCoordinates() {
        val features = nodeFeatures(listOf(
            MeshNode("valid", "North", "repeater", 30.0, -97.0, "2026-01-01"),
            MeshNode("missing", "Missing", "repeater", lastSeen = "2026-01-01"),
            MeshNode("invalid", "Invalid", "repeater", 999.0, 0.0, "2026-01-01"),
        )).features()!!
        assertEquals(1, features.size)
        assertEquals("valid", features.single().getStringProperty("publicKey"))
    }
}
