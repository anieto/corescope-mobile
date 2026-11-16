package org.nodescope.android

import java.io.File
import java.nio.file.Files
import java.util.UUID
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.nodescope.android.feature.candidatesites.*
import org.nodescope.android.feature.candidatesites.SavedPlansLibrary.Destination
import org.nodescope.android.feature.candidatesites.SavedPlansLibrary.SaveMode

/**
 * A2 library rules, as iOS's StudyPlanLibraryTests: atomic saves into new plans, stale updates
 * refused, Save as new and Duplicate keep originals, deleting a plan keeps its studies.
 */
class StudyPlanLibraryTest {
    private val root = Files.createTempDirectory("study-plans").toFile()

    @After fun cleanUp() { root.deleteRecursively() }

    private suspend fun library(): SavedPlansLibrary {
        val library = SavedPlansLibrary(DeploymentPlanStore(File(root, "plans.json")))
        library.load()
        assertTrue(library.loaded)
        return library
    }

    private fun linkProfile(name: String): SavedStudy {
        val antenna = SavedAntenna(6.0, 3.0, 0.0)
        val inputs = SavedLinkProfile(SavedEndpoint("A", 30.27, -97.74, antenna), SavedEndpoint("B", 30.30, -97.70, antenna),
            910.525, 22.0, -132.0, 4.0 / 3.0, null)
        return SavedStudy(UUID.randomUUID().toString(), name, 1, 1, SavedStudy.Kind.LINK_PROFILE, linkProfile = inputs)
    }

    @Test fun changingInputsUnderAnUnreadableResultNeedsConfirmation() = runTest {
        val reference = File("../../shared/fixtures/saved-plans/v3/reference")
        File(reference, "plans.json").copyTo(File(root, "plans.json"))
        File(reference, "results").copyRecursively(File(root, "results"))
        val library = library()
        val opened = library.studies.first { it.resultStatus == SavedResultStatus.MISSING }
        val home = opened.planID?.let { Destination.Plan(it) } ?: Destination.Standalone

        // A rename keeps the reference: a later restore can still bring the file back.
        val renamed = opened.copy(name = "Renamed")
        assertFalse(library.dropsUnreadableResult(renamed))
        val kept = library.saveStudy(renamed, SaveMode.UPDATE, home)!!
        assertEquals(opened.resultRef, kept.resultRef)

        val changed = kept.copy(coverage = kept.coverage!!.copy(reserveDb = kept.coverage!!.reserveDb + 1))
        assertTrue(library.dropsUnreadableResult(changed))
        assertNull(library.saveStudy(changed, SaveMode.UPDATE, home))
        assertEquals(opened.resultRef, library.studies.first { it.id == opened.id }.resultRef)

        val inputsOnly = library.saveStudy(changed, SaveMode.UPDATE, home, inputsOnly = true)!!
        assertTrue(inputsOnly.resultRef == null && inputsOnly.resultStatus == SavedResultStatus.NONE)
    }

    @Test fun newPlanAndItsFirstStudyAreOneSave() = runTest {
        val library = library()
        assertNull(library.saveStudy(linkProfile("Ridge"), SaveMode.SAVE_AS_NEW, Destination.NewPlan, ""))
        assertTrue(library.studyPlans.isEmpty() && library.studies.isEmpty())

        val saved = library.saveStudy(linkProfile("Ridge"), SaveMode.SAVE_AS_NEW, Destination.NewPlan, "North site")!!
        assertEquals(listOf("North site"), library.studyPlans.map { it.name })
        assertEquals(library.studyPlans.first().id, saved.planID)
        assertEquals(1L, saved.revision)
    }

    @Test fun aStaleUpdateIsRefusedAndSaveAsNewStillWorks() = runTest {
        val library = library()
        val opened = library.saveStudy(linkProfile("Ridge"), SaveMode.SAVE_AS_NEW, Destination.Standalone)!!
        assertTrue(library.renameStudy(opened.id, "Renamed elsewhere"))

        val edited = opened.copy(linkProfile = opened.linkProfile!!.copy(frequencyMHz = 915.0))
        assertNull(library.saveStudy(edited, SaveMode.UPDATE, Destination.Standalone))
        assertNotNull(library.error)
        assertEquals(listOf("Renamed elsewhere"), library.studies.map { it.name })

        val copy = library.saveStudy(edited, SaveMode.SAVE_AS_NEW, Destination.Standalone)!!
        assertNotEquals(opened.id, copy.id)
        assertEquals(2, library.studies.size)
        assertEquals(910.525, library.studies.first { it.id == opened.id }.linkProfile!!.frequencyMHz, 0.0)
    }

    @Test fun anOldEditorCantUndoALaterMove() = runTest {
        val library = library()
        val plan = library.createStudyPlan("North site", null)!!
        val opened = library.saveStudy(linkProfile("Ridge"), SaveMode.SAVE_AS_NEW, Destination.Plan(plan.id))!!
        assertTrue(library.moveStudy(opened.id, null))
        assertNull(library.saveStudy(opened, SaveMode.UPDATE, Destination.Plan(plan.id)))
        assertNull(library.studies.first().planID)
    }

    @Test fun anUpdateKeepsIdentityAndAdvancesTheRevision() = runTest {
        val library = library()
        val opened = library.saveStudy(linkProfile("Ridge"), SaveMode.SAVE_AS_NEW, Destination.Standalone)!!
        val updated = library.saveStudy(opened.copy(name = "Ridge, taller mast"), SaveMode.UPDATE, Destination.Standalone)!!
        assertEquals(opened.id, updated.id)
        assertEquals(2L, updated.revision)
        assertEquals(listOf("Ridge, taller mast"), library.studies.map { it.name })
    }

    @Test fun savingIntoAMissingPlanKeepsTheDraft() = runTest {
        val library = library()
        assertNull(library.saveStudy(linkProfile("Ridge"), SaveMode.SAVE_AS_NEW, Destination.Plan(UUID.randomUUID().toString())))
        assertTrue(library.studies.isEmpty())
        assertNotNull(library.error)
    }

    @Test fun deletingAPlanKeepsItsStudiesAsStandalone() = runTest {
        val library = library()
        val plan = library.createStudyPlan("North site", "Water tower")!!
        library.saveStudy(linkProfile("One"), SaveMode.SAVE_AS_NEW, Destination.Plan(plan.id))!!
        library.saveStudy(linkProfile("Two"), SaveMode.SAVE_AS_NEW, Destination.Plan(plan.id))!!
        assertTrue(library.deleteStudyPlan(plan.id))
        assertTrue(library.studyPlans.isEmpty())
        assertTrue(library.studies.size == 2 && library.studies.all { it.planID == null })
    }

    @Test fun duplicatesAreIndependentAndNamedDistinctly() = runTest {
        val library = library()
        val plan = library.createStudyPlan("North site", null)!!
        val original = library.saveStudy(linkProfile("Ridge"), SaveMode.SAVE_AS_NEW, Destination.Plan(plan.id))!!
        val first = library.duplicateStudy(original.id)!!
        val second = library.duplicateStudy(original.id)!!
        assertEquals(listOf("Ridge copy", "Ridge copy 2"), listOf(first.name, second.name))
        assertTrue(first.id != original.id && first.planID == plan.id && first.linkProfile == original.linkProfile)
        assertTrue(library.deleteStudy(first.id))
        assertEquals(setOf(original.id, second.id), library.studies.map { it.id }.toSet())
    }
}
