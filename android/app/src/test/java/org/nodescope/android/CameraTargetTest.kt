package org.nodescope.android

import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.nodescope.android.core.model.*
import org.nodescope.android.core.network.*
import org.nodescope.android.feature.map.*

class CameraTargetTest {
    private val airports = parseAirportTable(File("src/main/assets/iata-airports.csv").readText())
    private val texas = mapOf("ABI" to "Abilene, TX", "ACT" to "Waco, TX", "AMA" to "Amarillo, TX", "AUS" to "Austin, TX",
        "CRP" to "Corpus Christi, TX", "DFW" to "Dallas-Fort Worth, TX", "ELP" to "El Paso, TX", "HOU" to "Houston, TX",
        "MFE" to "McAllen, TX", "SAT" to "San Antonio, TX", "SJT" to "San Angelo, TX", "TXK" to "Texarkana, TX")
    // What the analyzer publishes today: centers for only two of its twelve regions.
    private val published = mapOf("AUS" to RegionCoordinate(30.1975, -97.6664), "DFW" to RegionCoordinate(32.8998, -97.0403))
    // Nodes heard by Corpus Christi's observers sit near Austin, not in Corpus Christi.
    private val heardNearAustin = listOf(MeshNode("n1", "a", "repeater", 30.2, -97.7, ""), MeshNode("n2", "b", "repeater", 29.5, -98.4, ""),
        MeshNode("n3", "c", "repeater", 30.5, -97.9, ""))
    private fun snapshot(nodes: List<MeshNode> = heardNearAustin) = AnalyzerSnapshot(nodes, nodes.size, texas,
        MapDefaults(listOf(30.2672, -97.7431), 8.0), regionCoordinates = regionCoordinatesFor(texas, published, airports))

    @Test fun bundledAirportsResolveEveryRegion() {
        assertTrue(airports.size > 9_000)
        val coordinates = regionCoordinatesFor(texas, published, airports)
        assertEquals(texas.keys, coordinates.keys)
        assertEquals(published.getValue("AUS"), coordinates.getValue("AUS")) // analyzer data wins
        assertEquals(27.77, coordinates.getValue("CRP").lat, 0.01)
    }

    @Test fun airportTableSkipsMalformedRows() {
        assertEquals(setOf("AAA"), parseAirportTable("AAA,1.0,2.0\nBB,1,2\nCCC,x,2\nDDD,95,2\n\n").keys)
    }

    @Test fun selectedRegionFramesItsCenterNotTheNodesItHeard() {
        val target = cameraTarget(snapshot(), "crp") as CameraTarget.Bounds
        assertEquals(27.77, (target.north + target.south) / 2, 0.01)
        assertEquals(-97.50, (target.east + target.west) / 2, 0.01)
    }

    @Test fun unknownRegionStillFallsBackToItsNodes() {
        val target = cameraTarget(snapshot().copy(regionCoordinates = emptyMap()), "CRP") as CameraTarget.Bounds
        assertEquals(30.5, target.north, 0.001)
        assertEquals(29.5, target.south, 0.001)
    }

    @Test fun allRegionsCoversEveryConfiguredRegion() {
        val target = cameraTarget(snapshot(), null) as CameraTarget.Bounds
        assertTrue(target.west < -106.3 && target.east > -94.0) // El Paso to Texarkana
        assertTrue(target.south < 26.2 && target.north > 35.2) // McAllen to Amarillo
    }

    @Test fun communityViewportWinsAndSmallAnalyzersKeepTheirDefault() {
        val viewport = AnalyzerSource("g", "Gulf", "example.org", "", mapCenter = listOf(30.2, -90.5), mapRadiusKm = 300.0).viewport!!
        val framed = cameraTarget(snapshot(), null, viewport) as CameraTarget.Bounds
        assertEquals(30.2, (framed.north + framed.south) / 2, 0.01)
        val single = snapshot().copy(regions = mapOf("AUS" to "Austin, TX"))
        assertEquals(CameraTarget.Center(Coordinate(30.2672, -97.7431), 8.0), cameraTarget(single, null))
        assertNull(AnalyzerSource("x", "X", "example.org", "").viewport)
    }

    // Colorado's analyzer publishes a center only for DEN; unlabeled RNB and YQB match
    // airports in Sweden and Quebec.
    private val colorado = listOf("ALS", "ASE", "CEZ", "COS", "DEN", "DRO", "EGE", "FNL", "GJT", "GUC", "HDN", "LAA", "MTJ",
        "PUB", "RNB", "STK", "TEX", "YQB").associateWith { it }
    private fun coloradoSnapshot(nodes: List<MeshNode> = emptyList()) = AnalyzerSnapshot(nodes, nodes.size, colorado,
        MapDefaults(listOf(39.103563, -105.6686709), 9.0),
        regionCoordinates = regionCoordinatesFor(colorado, mapOf("DEN" to RegionCoordinate(39.8561, -104.6737)), airports))

    @Test fun distantRegionCodesDoNotStretchAllRegions() {
        assertEquals(setOf("RNB", "YQB"), regionCenters(coloradoSnapshot()).second)
        val target = cameraTarget(coloradoSnapshot(), null) as CameraTarget.Bounds
        assertTrue(target.west > -110 && target.east < -101) // Colorado, not Europe
        assertTrue(target.south > 36 && target.north < 42)
    }

    @Test fun anOutlierRegionFramesItsNodesInstead() {
        val heard = listOf(MeshNode("n1", "a", "repeater", 40.0, -106.0, ""), MeshNode("n2", "b", "repeater", 40.5, -106.5, ""))
        val target = cameraTarget(coloradoSnapshot(heard), "RNB") as CameraTarget.Bounds
        assertEquals(40.5, target.north, 0.001)
        assertEquals(-106.5, target.west, 0.001)
    }

    @Test fun regionsThatMissTheAnalyzerCenterFallBackToIt() {
        // Two trusted regions far from the analyzer's own map center: trust the analyzer.
        val elsewhere = snapshot().copy(regions = mapOf("AUS" to "Austin", "DFW" to "Dallas"), mapDefaults = MapDefaults(listOf(39.1, -105.7), 9.0))
        assertEquals(CameraTarget.Center(Coordinate(39.1, -105.7), 9.0), cameraTarget(elsewhere, null))
    }
}
