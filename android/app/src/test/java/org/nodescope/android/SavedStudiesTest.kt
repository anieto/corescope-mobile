package org.nodescope.android

import androidx.lifecycle.SavedStateHandle
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.nodescope.android.core.model.Coordinate
import org.nodescope.android.core.model.MeshNode
import org.nodescope.android.feature.candidatesites.*
import org.nodescope.android.feature.coverage.*
import org.nodescope.android.feature.linkprofile.*

/** Saving and reopening studies on Android: the result checkpoint, both tools, and the shared library. */
@OptIn(ExperimentalCoroutinesApi::class)
class SavedStudiesTest {
    private val nodes = listOf(MeshNode("n1", "Ridge", "repeater", 10.01, 20.01, ""), MeshNode("n2", "Valley", "companion", 10.02, 19.99, ""))

    private fun withMain(test: suspend TestScope.(File) -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val root = Files.createTempDirectory("saved-studies").toFile()
        try { test(root) } finally { root.deleteRecursively(); Dispatchers.resetMain() }
    }

    private fun coverageModel(root: File) =
        EstimatedCoverageViewModel(File(root, "terrain"), CoverageMemoryPreferences(), heightInFeet = false)

    /** A completed result for the model's current setup: every sample 120 dB of loss, every node predicted. */
    private fun result(model: EstimatedCoverageViewModel): CoverageStudyResult {
        val inputs = requireNotNull(model.inputs())
        val grid = inputs.grid
        val cells = (0 until grid.count).map { index ->
            when (requireNotNull(grid.point(index)).state) {
                CoverageGridPointState.nearSite -> CoverageAreaCell(CoverageAreaState.nearSite)
                CoverageGridPointState.outsideRadius -> CoverageAreaCell(CoverageAreaState.outsideRadius)
                CoverageGridPointState.eligible -> CoverageAreaCell(CoverageAreaState.qualified, 120.0, inputs.budget.reception(120.0).adjustedMarginDb)
            }
        }
        val run = CoverageNodeRun(model.nodeSnapshots, false, 200, grid.radiusMeters.toDouble())
        model.nodeSnapshots.forEach { assertTrue(run.accept(CoverageNodeEvaluation(run.id, it, 1500.0, CoverageNodePrediction.QualifiedMargin(12.5)))) }
        assertTrue(run.complete())
        return CoverageStudyResult(java.util.UUID.randomUUID(), inputs, CoverageStudyLimits(120_000_000, 120_000_000, 30_000_000, 30_000_000, 1000),
            CoverageAreaResult(cells, 10, 20, false), CoverageNodeBatchResult(run, 0, 0, 0), model = CoverageStudyModel("rev", "scenario"))
    }

    private fun placedModel(root: File) = coverageModel(root).apply {
        setRadius(5000)
        setSpacing(1000)
        offerCatalog(nodes, "source-a")
        place(Coordinate(10.0, 20.0), "Ridge")
    }

    @Test fun savedResultRoundTripsAndRejectsAnythingThatDisagrees() = withMain { root ->
        val model = placedModel(root)
        val result = result(model)
        val saved = requireNotNull(SavedCoverageCalculation.of(result))
        val restored = SavedCoverageCalculation.decode(saved.encoded()).restore(requireNotNull(model.inputs()), model.nodeSnapshots, false)
        assertEquals(result.area.cells, restored.area.cells)
        assertEquals(12.5, restored.nodes.run.status(model.nodeSnapshots.first()).adjustedMarginDb!!, 0.0)
        assertEquals("rev", restored.model?.sourceRevision)

        // Other inputs, a tampered margin, another catalog, or a result not written by Android all fail.
        model.set("power", "20")
        assertThrows(Exception::class.java) { saved.restore(requireNotNull(model.inputs()), model.nodeSnapshots, false) }
        model.set("power", "22")
        val first = saved.cells.indexOfFirst { it.state == "qualified" }
        val tampered = saved.copy(cells = saved.cells.toMutableList().also { it[first] = it[first].copy(marginDb = 99.0) })
        assertThrows(Exception::class.java) { tampered.restore(requireNotNull(model.inputs()), model.nodeSnapshots, false) }
        assertThrows(Exception::class.java) { saved.restore(requireNotNull(model.inputs()), model.nodeSnapshots.drop(1), false) }
        assertThrows(Exception::class.java) { SavedCoverageCalculation.decode(java.util.Base64.getEncoder().encodeToString("bplist00".toByteArray())) }
    }

    @Test fun coverageStudyReopensWithItsInputsNodesAndResult() = withMain { root ->
        val model = placedModel(root)
        model.set("txHeight", "12")
        val inputsOnly = requireNotNull(model.savedSnapshot())
        assertNull(inputsOnly.coverage?.calculatedResult)
        assertEquals("Ridge", inputsOnly.coverage?.transmitter?.name)
        assertEquals(2, inputsOnly.coverage?.nodes?.size)

        val reopened = coverageModel(root)
        reopened.restore(inputsOnly)
        assertTrue(reopened.usesSavedCatalog)
        assertEquals(model.fields, reopened.fields)
        assertEquals(model.radiusMeters, reopened.radiusMeters)
        assertEquals(model.nodeSnapshots, reopened.nodeSnapshots)
        assertNull(reopened.activeResult)
        // A live catalog doesn't replace the saved one until asked to.
        reopened.offerCatalog(nodes.take(1), "source-b")
        assertEquals(2, reopened.knownNodes.size)
        reopened.refreshNodesFromSource()
        assertFalse(reopened.usesSavedCatalog)
        assertEquals(1, reopened.knownNodes.size)

        // A study saved with a result reopens showing it, without calculating.
        val calculation = requireNotNull(SavedCoverageCalculation.of(result(model))).encoded()
        val withResult = inputsOnly.copy(coverage = inputsOnly.coverage?.copy(calculatedResult = calculation))
        val shown = coverageModel(root).apply { restore(withResult) }
        assertNotNull(shown.activeResult)
        assertNull(shown.inputError)
        val broken = inputsOnly.copy(coverage = inputsOnly.coverage?.copy(calculatedResult = java.util.Base64.getEncoder().encodeToString("x".toByteArray())))
        val fallback = coverageModel(root).apply { restore(broken) }
        assertNull(fallback.activeResult)
        assertNotNull(fallback.inputError)
        assertEquals(model.fields, fallback.fields)
    }

    @Test fun linkProfileSavesOncePlacedAndReopensWithoutAnalyzing() = withMain { _ ->
        val calls = mutableListOf<List<ProfileCoordinate>>()
        val terrain = LinkTerrainProviding { calls += it; TerrainProfile(it.mapIndexed { index, _ -> ElevationPoint(index * 100.0, 100.0) }, 1000, "cache") }
        val model = LinkProfileViewModel(terrain, SavedStateHandle())
        assertNull(model.savedSnapshot())
        model.place("A", ProfileCoordinate(40.0, -90.0), "Hill")
        assertNull(model.savedSnapshot())
        model.place("B", ProfileCoordinate(40.1, -90.1), null)
        val study = requireNotNull(model.savedSnapshot())
        assertEquals("Hill", study.linkProfile?.pointA?.name)
        assertEquals(910.525, study.linkProfile!!.frequencyMHz, 0.0)
        advanceUntilIdle()

        calls.clear()
        val handle = SavedStateHandle()
        val reopened = LinkProfileViewModel(terrain, handle)
        reopened.restore(study)
        advanceUntilIdle()
        assertTrue(reopened.state.value.awaitingCalculation)
        assertTrue(calls.isEmpty())
        assertEquals("40.0", reopened.state.value.fields["Latitude A"])
        assertEquals("Hill", reopened.state.value.names["A"])
        // A second restore into the same entry (process restart) keeps edits made after reopening.
        reopened.edit("Height A (m)", "25")
        reopened.restore(study)
        assertEquals("25", reopened.state.value.fields["Height A (m)"])
        reopened.analyze()
        advanceUntilIdle()
        assertFalse(reopened.state.value.awaitingCalculation)
        assertNotNull(reopened.state.value.result)
    }

    @Test fun savingAStudyUpgradesTheLibraryAndKeepsPlans() = withMain { root ->
        val file = File(root, "plans.json")
        val plan = DeploymentPlan("11111111-1111-4111-8111-111111111111", "Plan", 1, 1, sites = emptyList())
        file.writeBytes(DeploymentPlanCodec.encode(DeploymentPlanDocument(1, listOf(plan))))
        val library = SavedPlansLibrary(DeploymentPlanStore(file))
        library.load()
        val study = requireNotNull(placedModel(root).savedSnapshot())
        assertTrue(library.saveStudy(study))
        assertTrue(File(file.path + ".v1-backup").exists())
        assertTrue(library.savePlan(plan.copy(name = "Renamed")))
        val reopened = DeploymentPlanStore(file).load()
        assertEquals(2, reopened.schemaVersion)
        assertEquals(listOf(study), reopened.savedStudies)
        assertEquals("Renamed", reopened.plans.single().name)
        assertTrue(library.deleteStudy(study))
        assertTrue(DeploymentPlanStore(file).load().savedStudies.isEmpty())
    }
}
