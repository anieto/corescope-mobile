package org.nodescope.android

import java.io.File
import java.util.Base64
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.nodescope.android.feature.coverage.*

/**
 * Saved-plans v3 legacy inventory: the v3 reference library's migrated study keeps Android 0.10.6's
 * coverage bytes as they were. The production 0.10.6 reader must still restore them, so A1's
 * migration and B's conversion start from bytes this app really accepts.
 */
class LegacyCoverageFixtureTest {
    private val root = File("../../shared/fixtures/saved-plans/v3/reference")
    private val study = Json.parseToJsonElement(File(root, "plans.json").readText()).jsonObject.getValue("studies").jsonArray
        .map { it.jsonObject }.single { it["resultRef"]?.jsonObject?.get("format")?.jsonPrimitive?.content == "legacy.android-json-coverage" }
    private val nodes = listOf(CoverageNodeSnapshot("analyzer.example", "a1b2c3d4", 29.4282, -98.4936),
        CoverageNodeSnapshot("analyzer.example", "e5f6a7b8", 29.5, -98.4))

    private fun inputs(power: Double = 22.0) = CoverageStudyInputs(CoverageTerrainCoordinate(29.4241, -98.4936),
        CoverageGrid.studyArea(1000, 1000), CoverageLinkBudget(power, 2.15, 2.15, 1.0, 1.0, -128.0, 5.0), 910.525, 6.096, 1.524)

    private fun bytes(): ByteArray {
        val ref = study.getValue("resultRef").jsonObject
        val data = File(root, "results/${ref.getValue("sha256").jsonPrimitive.content}.result").readBytes()
        assertEquals(ref.getValue("byteLength").jsonPrimitive.int, data.size)
        return data
    }

    @Test fun androidLegacyBytesRestoreWithTheProductionReader() {
        val restored = SavedCoverageCalculation.decode(Base64.getEncoder().encodeToString(bytes())).restore(inputs(), nodes, false)
        assertEquals(listOf(CoverageAreaState.outsideRadius, CoverageAreaState.qualified, CoverageAreaState.outsideRadius,
            CoverageAreaState.qualified, CoverageAreaState.nearSite, CoverageAreaState.notComputed, CoverageAreaState.outsideRadius,
            CoverageAreaState.qualified, CoverageAreaState.outsideRadius), restored.area.cells.map { it.state })
        assertEquals(listOf(null, 121.25, null, 118.0, null, null, null, 126.75, null), restored.area.cells.map { it.lossDb })
        assertEquals(1, restored.area.pendingCount)
        assertTrue(restored.hasPendingWork)
        assertEquals(CoverageNodeReason.processingLimit, restored.nodes.run.status(nodes[0]).reason)
        assertEquals(CoverageNodeReason.outsideRadius, restored.nodes.run.status(nodes[1]).reason)
        assertEquals("a911b6ecd58f8c16ac8ae4becf6bdbba1784e5a9", restored.model?.sourceRevision)
        // The same inputs as the iOS legacy sample: margins agree bit for bit with the budget either side computes.
        assertEquals(26.05000000000001, restored.area.cells[1].marginDb!!, 0.0)
    }

    @Test fun androidLegacyBytesRefuseOtherInputs() {
        val calculation = SavedCoverageCalculation.decode(Base64.getEncoder().encodeToString(bytes()))
        assertThrows(Exception::class.java) { calculation.restore(inputs(power = 20.0), nodes, false) }
        assertThrows(Exception::class.java) { calculation.restore(inputs(), nodes.take(1), false) }
    }

    @Test fun iosLegacySampleIsABinaryPlistThatAndroidDoesNotRead() {
        val plist = File("../../shared/fixtures/saved-plans/legacy/ios-0.10.6-coverage.plist").readBytes()
        assertArrayEquals("bplist00".toByteArray(), plist.copyOf(8))
        // Neither platform reads the other's legacy format; the inputs still open without the result.
        assertThrows(Exception::class.java) { SavedCoverageCalculation.decode(Base64.getEncoder().encodeToString(plist)) }
    }
}
