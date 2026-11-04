package org.nodescope.android

import java.io.File
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.nodescope.android.feature.coverage.*

class CoverageTerrainCatalogTest {
    @Test fun sharedCatalogValidation() {
        val rows = Json.parseToJsonElement(File("../../shared/fixtures/coverage/terrain-catalog.json").readText()).jsonArray
        assertEquals(14, rows.size)
        for (row in rows) {
            val item = row.jsonObject
            val data = item.getValue("document").toString().toByteArray()
            val expected = item.getValue("expected").jsonPrimitive.content
            if (expected == "valid") {
                val catalog = CoverageTerrainCatalog(data)
                val area = CoverageTerrainFootprint.make(0.5, 0.99, 1500.0)
                assertEquals(listOf("N00E000", "N00E001"), catalog.resolve(area).map { it.tile })
                assertThrows(CoverageTerrainFootprintFailure::class.java) {
                    catalog.resolve(CoverageTerrainFootprint.make(2.5, 2.5, 1000.0))
                }
            } else {
                val state = when (expected) {
                    "duplicateTile" -> CoverageTerrainCatalogState.DUPLICATE_TILE
                    "unsupportedVersion" -> CoverageTerrainCatalogState.UNSUPPORTED_VERSION
                    else -> CoverageTerrainCatalogState.INVALID_DOCUMENT
                }
                assertEquals(item.getValue("name").jsonPrimitive.content, state,
                    assertThrows(CoverageTerrainCatalogFailure::class.java) { CoverageTerrainCatalog(data) }.state)
            }
        }
    }

    @Test fun reviewedRegionalCatalogResolvesAndPreflights() {
        val catalog = CoverageTerrainCatalog(File("../../docs/node-planner/feasibility/regional-terrain-catalog.json").readBytes())
        assertEquals(8, catalog.descriptors.size)
        assertEquals(84_062_769L, catalog.descriptors.sumOf { it.packedBytes })
        val selected = catalog.resolve(CoverageTerrainFootprint.make(50.96, -115.16, 20_000.0, 100.0))
        assertEquals(setOf("N50W116", "N50W115", "N51W116", "N51W115"), selected.map { it.tile }.toSet())
        val preflight = CoverageTerrainPipelinePreflight.make(selected, emptyList(), 300_000_000L, 50_000_000L)
        assertEquals(45_503_666L, preflight.cache.expectedDownloadBytes)
        assertTrue(preflight.cache.fitsDownloadBudget)
        assertTrue(preflight.fitsPipelineQuota)
        val tooSmall = CoverageTerrainPipelinePreflight.make(selected, emptyList(), 100_000_000L, 40_000_000L)
        assertFalse(tooSmall.cache.fitsDownloadBudget)
        assertFalse(tooSmall.fitsPipelineQuota)
        assertThrows(CoverageTerrainFootprintFailure::class.java) {
            catalog.resolve(CoverageTerrainFootprint.make(29.43, -98.62, 20_000.0))
        }
    }

    @Test fun boundedMalformedInput() {
        assertEquals(CoverageTerrainCatalogState.OVERSIZED,
            assertThrows(CoverageTerrainCatalogFailure::class.java) {
                CoverageTerrainCatalog(ByteArray(CoverageTerrainCatalog.MAXIMUM_BYTES + 1))
            }.state)
        for (data in listOf(byteArrayOf(), "[]".toByteArray(), byteArrayOf(-1, -2), "{".toByteArray())) {
            assertEquals(CoverageTerrainCatalogState.INVALID_DOCUMENT,
                assertThrows(CoverageTerrainCatalogFailure::class.java) { CoverageTerrainCatalog(data) }.state)
        }
    }
}
