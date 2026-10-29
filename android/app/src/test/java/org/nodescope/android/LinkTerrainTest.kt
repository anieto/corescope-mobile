package org.nodescope.android

import java.io.File
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.nodescope.android.feature.linkprofile.*

class LinkTerrainTest {
    @get:Rule val directory = TemporaryFolder()
    private val coordinates = listOf(ProfileCoordinate(40.0, -90.0), ProfileCoordinate(40.01, -90.0))

    @Test fun samplesShortestDateLinePathAndDistanceThresholds() {
        val points = LinkGeometry.samples(ProfileCoordinate(0.0, 179.0), ProfileCoordinate(0.0, -179.0), 3)
        assertEquals(180.0, points[1].longitude, 0.0)
        assertEquals(222389.85328911748, LinkGeometry.distance(points.first(), points.last()), 1e-6)
        assertEquals(90.0, LinkGeometry.bearing(points.first(), points.last()), 1e-8)
        assertEquals(listOf(20, 50, 80, 100), listOf(999.0, 1000.0, 5000.0, 20000.0).map(LinkGeometry::sampleCount))
    }

    @Test fun persistsAndExpiresCache() = runTest {
        MockWebServer().use { server ->
            var now = 1_000_000L
            val cache = File(directory.root, "terrain.json")
            fun provider() = LinkTerrainProvider(cache, endpoint = server.url("/v1/elevation"), now = { now })
            server.enqueue(MockResponse().setBody("{\"elevation\":[100,120]}"))
            val first = provider().profile(coordinates)
            assertEquals("network", first.origin)
            assertEquals(120.0, first.points.last().elevationMeters, 0.0)
            assertEquals("40.0,40.01", server.takeRequest().requestUrl?.queryParameter("latitude"))
            assertEquals("cache", provider().profile(coordinates).origin)
            assertEquals(1, server.requestCount)
            now += 31L * 24 * 60 * 60 * 1000
            server.enqueue(MockResponse().setBody("{\"elevation\":[101,121]}"))
            assertEquals(121.0, provider().profile(coordinates).points.last().elevationMeters, 0.0)
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun rejectsIncompleteResponseWithoutCaching() = runTest {
        MockWebServer().use { server ->
            val cache = File(directory.root, "invalid.json")
            server.enqueue(MockResponse().setBody("{\"elevation\":[100]}"))
            try {
                LinkTerrainProvider(cache, endpoint = server.url("/v1/elevation")).profile(coordinates)
                fail("Incomplete response accepted")
            } catch (_: IllegalArgumentException) { }
            assertFalse(cache.exists())
        }
    }

    @Test fun resolvesMixedCacheAndNetworkInInputOrder() = runTest {
        MockWebServer().use { server ->
            val provider = LinkTerrainProvider(File(directory.root, "mixed.json"), endpoint = server.url("/v1/elevation"))
            server.enqueue(MockResponse().setBody("{\"elevation\":[100,120]}"))
            provider.profile(coordinates)
            server.enqueue(MockResponse().setBody("{\"elevation\":[140]}"))
            val mixed = provider.profile(listOf(coordinates.last(), ProfileCoordinate(40.02, -90.0)))
            assertEquals("mixed", mixed.origin)
            assertEquals(listOf(120.0, 140.0), mixed.points.map { it.elevationMeters })
            assertTrue(mixed.points.last().distanceMeters > 0)
        }
    }
}
