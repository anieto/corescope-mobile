package org.nodescope.android

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.nodescope.android.feature.candidatesites.*

/** Importing packages into the saved library (D step 4), from the shared v3 reference. Mirrors iOS SavedPlansImportTests. */
class SavedPlansImportTest {
    private val shared = File("../../shared/fixtures/saved-plans")
    private val roots = mutableListOf<File>()

    @After fun cleanUp() { roots.forEach { it.deleteRecursively() } }

    private suspend fun library(reference: Boolean): Pair<SavedPlansLibrary, File> {
        val root = Files.createTempDirectory("import").toFile().also { roots += it }
        if (reference) {
            File(shared, "v3/reference/plans.json").copyTo(File(root, "plans.json"))
            File(shared, "v3/reference/results").copyRecursively(File(root, "results"))
        }
        val library = SavedPlansLibrary(DeploymentPlanStore(File(root, "plans.json")))
        library.load()
        return library to File(root, "plans.json")
    }

    private fun SavedPlansLibrary.current() = SavedPlansImport.Library(plans, studyPlans, studies)

    /** The reference library exported (with results) and read back, as a recipient sees it. */
    private suspend fun referencePackage(contents: SavedPlansExport.Contents = SavedPlansExport.Contents.Library): SavedPlansPackage {
        val (source, _) = library(reference = true)
        val pack = requireNotNull(SavedPlansExport.packageFor(contents, source.plans, source.studyPlans, source.studies,
            true, true, 1_794_790_000_000, "0.10.7"))
        return SavedPlansPackage.read(pack.encoded())
    }

    @Test fun aLibraryRestoresAsCopiesWithTheirResults() = runTest {
        val pack = referencePackage()
        val (target, file) = library(reference = false)
        val preview = SavedPlansImport.preview(pack, target.current())
        assertTrue(preview.items.all { it.status == SavedPlansImport.Status.New })
        val records = SavedPlansImport.records(preview, target.current(), preview.defaultSelection, inputsOnly = false)
        assertTrue(target.addRecords(records.plans, records.studyPlans, records.studies))

        val reopened = SavedPlansLibrary(DeploymentPlanStore(file)).apply { load() }
        assertEquals(6, reopened.studies.size)
        assertEquals(2, reopened.studyPlans.size)
        assertEquals(1, reopened.plans.size)
        assertTrue(reopened.studies.none { study -> pack.studies.any { it.id == study.id } })
        for (study in reopened.studies) {
            val source = pack.studies.first { it.id == study.importOrigin?.studyID }
            assertEquals(source.name, study.name)
            assertEquals(source.createdAtMilliseconds, study.createdAtMilliseconds)
            assertEquals(source.origin, study.origin)
            assertEquals(source.revision, study.importOrigin?.revision)
            assertEquals(1L, study.revision)
            assertEquals(pack.studyPlans.firstOrNull { it.id == source.planID }?.name, reopened.studyPlans.firstOrNull { it.id == study.planID }?.name)
            assertEquals(source.resultRef != null, study.resultRef != null)
            if (study.resultRef != null) assertEquals(SavedResultStatus.AVAILABLE, study.resultStatus)
        }
    }

    @Test fun aLibraryImportSkipsWhatIsAlreadyHere() = runTest {
        val pack = referencePackage()
        val (target, _) = library(reference = true)
        val preview = SavedPlansImport.preview(pack, target.current())
        val status = preview.items.associate { it.name to it.status }
        assertEquals(SavedPlansImport.Status.AlreadyHere, status["Ridge relay site"])
        assertEquals(SavedPlansImport.Status.AlreadyHere, status["West-side options"])
        // On Android the 0.10.6 result is its own and travels converted, so that copy differs by its result.
        assertEquals(SavedPlansImport.Status.New, status["Coverage saved on 0.10.6"])
        assertEquals(SavedPlansImport.Status.AlreadyHere, status["Inputs only"])
        val records = SavedPlansImport.records(preview, target.current(), preview.defaultSelection, inputsOnly = false)
        assertTrue(records.studyPlans.isEmpty() && records.plans.isEmpty())
        assertEquals(setOf("Coverage saved on 0.10.6 2", "Restored without results 2"), records.studies.map { it.name }.toSet())
        assertThrows(SavedPlansImport.Refusal.NothingSelected::class.java) {
            SavedPlansImport.records(preview, target.current(), emptySet(), inputsOnly = false)
        }
    }

    @Test fun aStudyImportedTwiceBecomesACopy() = runTest {
        val pack = referencePackage(SavedPlansExport.Contents.Study("40000000-0000-4000-8000-000000000002"))
        val (target, _) = library(reference = false)
        repeat(2) {
            val preview = SavedPlansImport.preview(pack, target.current())
            val records = SavedPlansImport.records(preview, target.current(), preview.defaultSelection, inputsOnly = false)
            assertTrue(target.addRecords(records.plans, records.studyPlans, records.studies))
        }
        assertEquals(setOf("Ridge to valley", "Ridge to valley 2"), target.studies.map { it.name }.toSet())
        assertTrue(target.studies.all { it.planID == null && it.resultStatus == SavedResultStatus.AVAILABLE })
        val again = SavedPlansImport.preview(pack, target.current())
        assertEquals(SavedPlansImport.Status.AlreadyHere, again.items.single().status)
        assertTrue(again.copiesMatches)
    }

    @Test fun aResultThatCantBeUsedNeedsAcknowledgement() = runTest {
        val read = referencePackage(SavedPlansExport.Contents.Study("40000000-0000-4000-8000-000000000001"))
        val pack = read.copy(studies = read.studies.map { it.copy(resultRef = null) }, unusableResults = setOf(read.studies.single().id))
        val empty = SavedPlansImport.Library(emptyList(), emptyList(), emptyList())
        val preview = SavedPlansImport.preview(pack, empty)
        assertEquals(listOf("Ridge relay coverage"), preview.unusable)
        assertThrows(SavedPlansImport.Refusal.NothingSelected::class.java) {
            SavedPlansImport.records(preview, empty, preview.defaultSelection, inputsOnly = false)
        }
        val records = SavedPlansImport.records(preview, empty, preview.defaultSelection, inputsOnly = true)
        assertNull(records.studies.single().coverage?.calculatedResult)
    }

    @Test fun aFullLibraryRefusesTheWholeImport() = runTest {
        val pack = referencePackage(SavedPlansExport.Contents.Study("40000000-0000-4000-8000-000000000004"))
        val template = pack.studies.single()
        val full = SavedPlansImport.Library(emptyList(), emptyList(), (0 until 100).map {
            template.copy(id = java.util.UUID.randomUUID().toString(), name = "Study $it")
        })
        val preview = SavedPlansImport.preview(pack, full)
        val refusal = assertThrows(SavedPlansImport.Refusal.TooMany::class.java) {
            SavedPlansImport.records(preview, full, preview.defaultSelection, inputsOnly = false)
        }
        assertEquals("studies", refusal.what)
    }
}
