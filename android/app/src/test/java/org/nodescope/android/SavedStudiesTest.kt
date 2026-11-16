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

    @Test fun linkProfileHandoffFillsCoverageLikeAReopenedStudy() = withMain { root ->
        val seed = LinkCoverageSeed(SavedEndpoint("Site B", 41.0, -91.0, SavedAntenna(10.0, 9.0, 2.0)), 910.525, 22.0, -128.0)
        val imperial = EstimatedCoverageViewModel(File(root, "terrain"), CoverageMemoryPreferences(), heightInFeet = true)
        imperial.applyLinkSeed(seed)
        assertEquals("32.81", imperial.fields["txHeight"])
        assertEquals("22", imperial.fields["power"])
        assertEquals("9", imperial.fields["txGain"])
        assertEquals("2", imperial.fields["txLoss"])
        assertEquals("910.525", imperial.fields["frequency"])
        assertEquals("-128", imperial.fields["sensitivity"])
        val metric = coverageModel(root).apply { applyLinkSeed(seed.copy(point = seed.point.copy(antenna = SavedAntenna(6.096, 2.15, 1.0)))) }
        assertEquals("6.096", metric.fields["txHeight"])
        assertEquals("2.15", metric.fields["txGain"])
        assertEquals("1", metric.fields["txLoss"])
    }

    @Test fun unsavedLinkProfileWorkIsKeptAsADraftUntilSaved() = withMain { root ->
        val terrain = LinkTerrainProviding { TerrainProfile(it.mapIndexed { index, _ -> ElevationPoint(index * 100.0, 100.0) }, 1000, "cache", it) }
        val model = LinkProfileViewModel(terrain, SavedStateHandle()).apply { markClean() }
        val store = StudyDraftStore(root)
        val session = StudyDraftSession(store, java.util.UUID.randomUUID().toString(), SavedStudy.Kind.LINK_PROFILE)
        // Nothing placed yet: nothing to keep.
        session.flush(model.draftContent(), "analyzer.example", "AUS")
        assertTrue(store.list().isEmpty())
        model.place("A", ProfileCoordinate(40.0, -90.0), "Hill")
        model.edit("Height A (m)", "12.")
        session.flush(model.draftContent(), "analyzer.example", "AUS")
        val draft = store.list().single()
        assertEquals("Hill", draft.title)
        assertEquals("12.", draft.editor["Height A (m)"])
        assertEquals("analyzer.example" to "AUS", draft.host to draft.region)
        // Saved: the draft goes.
        model.markClean()
        session.flush(model.draftContent(), "analyzer.example", "AUS")
        assertTrue(store.list().isEmpty())
        // A result is kept with the draft once calculated.
        model.place("B", ProfileCoordinate(40.1, -90.1), null)
        advanceUntilIdle()
        session.flush(model.draftContent(), "analyzer.example", "AUS")
        val calculated = store.list().single()
        assertNotNull(store.result(calculated))
    }

    @Test fun unsavedCoverageWorkKeepsItsCatalogAndResult() = withMain { root ->
        val model = coverageModel(root).apply { markClean() }
        assertNull(model.draftContent())
        model.setRadius(5000)
        model.setSpacing(1000)
        model.offerCatalog(nodes, "source-a")
        model.place(Coordinate(10.0, 20.0), "Ridge")
        val content = requireNotNull(model.draftContent())
        assertEquals("Ridge", content.title)
        assertEquals("5000", content.editor["radiusMeters"])
        assertNull(content.result)
        // A saved study reopened with its result keeps its captured nodes and the result in the draft.
        val placed = placedModel(root)
        val inputsOnly = requireNotNull(placed.savedSnapshot())
        val payload = requireNotNull(PortableCoverageResult.encode(result(placed)))
        val study = inputsOnly.copy(coverage = inputsOnly.coverage!!.copy(calculatedResult = java.util.Base64.getEncoder().encodeToString(payload)))
        val reopened = coverageModel(root).apply { restore(study); markClean() }
        assertNull(reopened.draftContent())
        assertNotNull(reopened.activeResult)
        reopened.setRadius(10_000)
        val edited = requireNotNull(reopened.draftContent())
        assertEquals(study.coverage!!.nodes, edited.catalog)
        assertEquals(study.id, edited.savedStudyID)
    }

    @Test fun aCalculatedLinkProfileSavesItsResultAndReopensItWithoutTerrain() = withMain { root ->
        val calls = mutableListOf<List<ProfileCoordinate>>()
        val terrain = LinkTerrainProviding { calls += it; TerrainProfile(it.mapIndexed { index, _ -> ElevationPoint(index * 100.0, 100.0 + index) }, 1000, "network", it) }
        val model = LinkProfileViewModel(terrain, SavedStateHandle())
        model.place("A", ProfileCoordinate(40.0, -90.0), "Hill")
        model.place("B", ProfileCoordinate(40.1, -90.1), null)
        advanceUntilIdle()
        val calculated = requireNotNull(model.state.value.result)
        val snapshot = requireNotNull(model.savedSnapshot())
        assertNotNull(snapshot.linkProfileResult)

        val file = File(root, "plans.json")
        val library = SavedPlansLibrary(DeploymentPlanStore(file))
        library.load()
        requireNotNull(library.saveStudy(snapshot, SavedPlansLibrary.SaveMode.SAVE_AS_NEW, SavedPlansLibrary.Destination.Standalone))
        val stored = DeploymentPlanStore(file).load().savedStudies.single()
        assertEquals(ResultRef.PORTABLE_LINK_PROFILE, stored.resultRef?.format)
        assertEquals(SavedResultStatus.AVAILABLE, stored.resultStatus)

        calls.clear()
        val reopened = LinkProfileViewModel(terrain, SavedStateHandle())
        reopened.restore(stored)
        advanceUntilIdle()
        assertTrue(calls.isEmpty())
        val restored = requireNotNull(reopened.state.value.result)
        assertEquals(calculated.result, restored.result)
        assertEquals(calculated.terrain.points, restored.terrain.points)
        assertEquals(calculated.bearing, restored.bearing, 0.0)
        assertEquals(calculated.calculatedAt, restored.calculatedAt)
        // Saving it again keeps the same result file.
        assertEquals(snapshot.linkProfileResult, reopened.savedSnapshot()?.linkProfileResult)
        // An edit clears it and waits for Calculate.
        reopened.edit("Height A (m)", "25")
        advanceUntilIdle()
        assertNull(reopened.state.value.result)
        assertTrue(calls.isEmpty())
    }

    @Test fun linkProfileSavesOncePlacedAndReopensWithoutAnalyzing() = withMain { _ ->
        val calls = mutableListOf<List<ProfileCoordinate>>()
        val terrain = LinkTerrainProviding { calls += it; TerrainProfile(it.mapIndexed { index, _ -> ElevationPoint(index * 100.0, 100.0) }, 1000, "cache", it) }
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
        val original = file.readBytes()
        val stored = requireNotNull(library.saveStudy(study, SavedPlansLibrary.SaveMode.SAVE_AS_NEW, SavedPlansLibrary.Destination.Standalone))
        assertArrayEquals(original, migrationBackup(file, original).readBytes())
        assertTrue(library.savePlan(plan.copy(name = "Renamed")))
        val reopened = DeploymentPlanStore(file).load()
        assertEquals(3, reopened.schemaVersion)
        // Save as new gives the record its own ID and timestamps; everything else is the study as given.
        assertEquals(listOf(study.copy(id = stored.id, createdAtMilliseconds = stored.createdAtMilliseconds,
            updatedAtMilliseconds = stored.updatedAtMilliseconds)), reopened.content().second)
        assertEquals("Renamed", reopened.plans.single().name)
        assertTrue(library.deleteStudy(stored.id))
        assertTrue(DeploymentPlanStore(file).load().savedStudies.isEmpty())
    }
}
