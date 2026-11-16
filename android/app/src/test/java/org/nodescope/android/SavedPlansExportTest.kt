package org.nodescope.android

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test
import org.nodescope.android.feature.candidatesites.DeploymentPlanDocument
import org.nodescope.android.feature.candidatesites.DeploymentPlanStore
import org.nodescope.android.feature.candidatesites.ResultRef
import org.nodescope.android.feature.candidatesites.SavedPlansExport
import org.nodescope.android.feature.candidatesites.SavedPlansPackage
import org.nodescope.android.feature.candidatesites.SavedPlansPackage.OmissionReason

/** Building packages from the saved library (D step 3), from the shared v3 reference. Mirrors iOS SavedPlansExportTests. */
class SavedPlansExportTest {
    private val shared = File("../../shared/fixtures/saved-plans")

    /** The reference library as loaded: results attached, one missing, one in Android's own legacy format. */
    private fun reference(): DeploymentPlanDocument {
        val root = Files.createTempDirectory("export").toFile()
        try {
            File(shared, "v3/reference/plans.json").copyTo(File(root, "plans.json"))
            File(shared, "v3/reference/results").copyRecursively(File(root, "results"))
            return DeploymentPlanStore(File(root, "plans.json")).load()
        } finally { root.deleteRecursively() }
    }

    private fun export(document: DeploymentPlanDocument, contents: SavedPlansExport.Contents, results: Boolean = true, notes: Boolean = true) =
        requireNotNull(SavedPlansExport.packageFor(contents, document.plans, document.studyPlans.orEmpty(), document.savedStudies,
            results, notes, 1_794_790_000_000, "0.10.7"))

    @Test fun aLibraryBackupConvertsAndroidsLegacyResultAndSaysWhatStaysBehind() {
        val document = reference()
        val pack = export(document, SavedPlansExport.Contents.Library)
        assertEquals(6, pack.studies.size)
        assertEquals(listOf(SavedPlansPackage.Omission("40000000-0000-4000-8000-000000000005", OmissionReason.NOT_ON_DEVICE)), pack.omittedResults)
        // Android's own 0.10.6 result travels in the portable format.
        assertEquals(ResultRef.PORTABLE_COVERAGE, pack.studies.first { it.id == "40000000-0000-4000-8000-000000000003" }.resultRef?.format)
        assertEquals(3, pack.results.size)  // the imported copy shares its original's file
        val read = SavedPlansPackage.read(pack.encoded())
        assertTrue(read.unusableResults.isEmpty())
        assertEquals(document.savedStudies.map { it.id }, read.studies.map { it.id })
    }

    @Test fun aStudyTravelsAloneAndAPlanCanLeaveItsNotes() {
        val document = reference()
        val study = export(document, SavedPlansExport.Contents.Study("40000000-0000-4000-8000-000000000002"))
        assertTrue(study.studies.single().planID == null && study.studyPlans.isEmpty())
        val plan = export(document, SavedPlansExport.Contents.Plan("30000000-0000-4000-8000-000000000001"), notes = false)
        assertTrue(plan.studyPlans.single().notes == null && plan.studies.size == 2)
        val inputs = export(document, SavedPlansExport.Contents.Plan("30000000-0000-4000-8000-000000000001"), results = false)
        assertTrue(inputs.results.isEmpty() && inputs.studies.all { it.resultRef == null } && inputs.omittedResults.isEmpty())
        listOf(study, plan, inputs).forEach { SavedPlansPackage.read(it.encoded()) }
        assertNull(SavedPlansExport.packageFor(SavedPlansExport.Contents.Study("missing"), emptyList(), emptyList(), emptyList(),
            true, true, 0, "1"))
    }

    @Test fun thePreviewNamesWhatTheFileReveals() {
        val pack = export(reference(), SavedPlansExport.Contents.Library)
        val preview = SavedPlansExport.Preview.of(pack)
        assertEquals(1 + 4 + 2 * 2, preview.locations)
        assertEquals(2, preview.nodes)
        assertEquals(listOf("Hilltop", "Far repeater"), preview.nodeNames)
        assertEquals(listOf("analyzer.example · SAT"), preview.analyzers)
        assertEquals(2, preview.unknownSource)
        assertEquals(1, preview.notedPlans)
        assertTrue(SavedPlansExport.fileName(pack, 1_794_790_000_000).matches(Regex("NodeScope saved plans 2026-11-1\\d\\.nodescope")))
    }
}
