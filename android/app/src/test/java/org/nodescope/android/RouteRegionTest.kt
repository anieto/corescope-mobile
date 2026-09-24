package org.nodescope.android

import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.nodescope.android.core.model.*
import org.nodescope.android.core.network.*
import org.nodescope.android.feature.map.*
import kotlinx.serialization.json.jsonObject

class RouteRegionTest {
    @Test fun routeMarkersPreserveIdentityAndDoNotInventAmbiguousHops() {
        val nodes = listOf("aa01", "aa02", "bb01").mapIndexed { i, key -> MeshNode(key, key, "repeater", 30.0 + i, -97.0, "2026-09-22") }
        val packet = parseLivePacket(protocolJson.parseToJsonElement("""{"id":1,"hash":"route","path_json":["aa","bb"],"observer_id":"obs"}""").jsonObject, true)!!
        val markers = routeAnchorFeatures(packet, nodes, listOf(MeshObserver("obs", lat = 34.0, lon = -97.0)))
        assertEquals(listOf("bb01"), markers.filter { it.hasProperty("publicKey") }.map { it.getStringProperty("publicKey") })
        assertEquals(2, markers.size) // One resolved node plus the real observer endpoint.
    }
    @Test fun unsetGpsBreaksRoutesAndNeverCreatesMarkersOrObserverEndpoints() {
        val nodes = listOf(
            MeshNode("aa", "Before", "repeater", 30.0, -97.0, "2026-09-22"),
            MeshNode("bb", "Unset", "repeater", 0.0, 0.0, "2026-09-22"),
            MeshNode("cc", "After", "repeater", 31.0, -97.0, "2026-09-22"),
        )
        val packet = parseLivePacket(protocolJson.parseToJsonElement("""{"id":1,"hash":"unset","path_json":["aa","bb","cc"],"observer_id":"obs"}""").jsonObject, true)!!
        val observers = listOf(MeshObserver("obs", lat = 0.0, lon = 0.0))
        assertNull(nodes[1].coordinate)
        assertNull(observers.single().coordinate)
        assertEquals(listOf(listOf(Coordinate(30.0, -97.0)), listOf(Coordinate(31.0, -97.0))), packetRoute(packet, nodes, observers))
        assertEquals(listOf("aa", "cc"), routeAnchorFeatures(packet, nodes, observers).map { it.getStringProperty("publicKey") })
        assertEquals(2, nodeFeatures(nodes).features()!!.size)
    }
    @Test fun gpsAllowsEquatorAndPrimeMeridianExceptTheirUnsetIntersection() {
        assertNull(Coordinate.gps(-0.0, 0.0))
        assertEquals(Coordinate(0.0, -97.0), Coordinate.gps(0.0, -97.0))
        assertEquals(Coordinate(30.0, 0.0), Coordinate.gps(30.0, 0.0))
        // Configured map centers are not GPS readings.
        assertEquals(Coordinate(0.0, 0.0), MapDefaults(listOf(0.0, 0.0), 2.0).coordinate)
    }
    @Test fun regionDirectoryIsLoadedAndOptionalFailurePreservesNodes() = runBlocking {
        for (missing in listOf(false, true)) {
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                val path = chain.request().url.encodedPath
                val body = when (path) {
                    "/api/nodes" -> """{"nodes":[],"total":0}"""
                    "/api/config/regions" -> """{"DFW":"Dallas"}"""
                    "/api/config/map" -> """{"center":[30,-97],"zoom":7}"""
                    "/api/iata-coords" -> """{"coords":{"DFW":{"lat":32.9,"lon":-97.04,"radiusKm":60}}}"""
                    else -> error(path)
                }
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(if (missing && path == "/api/iata-coords") 404 else 200)
                    .message("fixture").body(body.toResponseBody("application/json".toMediaType())).build()
            }.build()
            val snapshot = HttpAnalyzerRepository(client).load(AnalyzerSelection("fixture.example", "DFW"))
            assertFalse(snapshot.configurationIncomplete)
            if (missing) assertTrue(snapshot.regionCoordinates.isEmpty())
            else assertEquals(Coordinate(32.9, -97.04), snapshot.regionCoordinates["DFW"]!!.coordinate)
        }
    }
    @Test fun regionRadiusUsesIosDefaultAndRejectsInvalidSizes() {
        val normal = regionSpan(RegionCoordinate(30.0, -97.0))
        assertEquals(45 * 2.4 / 111, normal.first, 0.00001)
        assertEquals(normal, regionSpan(RegionCoordinate(30.0, -97.0, -1.0)))
        assertTrue(regionSpan(RegionCoordinate(30.0, -97.0, 90.0)).first > normal.first)
    }
}
