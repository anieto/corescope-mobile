package org.nodescope.android

import java.io.File
import java.nio.file.Files
import java.util.Base64
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.nodescope.android.feature.candidatesites.*

/** Saved-plans v3 (A1): the shared reference library and cases, migration, result files and revisions. */
class SavedPlansV3Test {
    private val shared = File("../../shared/fixtures/saved-plans")
    private val cases = Json.parseToJsonElement(File(shared, "v3/v3-cases.json").readText()).jsonObject

    private fun withLibrary(test: (File) -> Unit) {
        val root = Files.createTempDirectory("plans-v3").toFile()
        try { test(File(root, "plans.json")) } finally { root.deleteRecursively() }
    }

    /** The reference library and its result files, copied into place. */
    private fun reference(file: File) {
        File(shared, "v3/reference/plans.json").copyTo(file)
        File(shared, "v3/reference/results").copyRecursively(File(file.parentFile, "results"))
    }

    @Test fun everySharedCaseIsReadAsExpected() {
        val list = cases.getValue("cases").jsonArray.map { it.jsonObject }
        assertEquals(38, list.size)
        for (case in list) {
            val name = case.getValue("name").jsonPrimitive.content
            val bytes = case.getValue("document").toString().toByteArray()
            val outcome = runCatching { DeploymentPlanCodec.decode(bytes) }
            assertEquals(name, case.getValue("valid").jsonPrimitive.boolean, outcome.isSuccess)
        }
    }

    @Test fun referenceLibraryLoadsWithEachResultState() = withLibrary { file ->
        reference(file)
        val studies = DeploymentPlanStore(file).load().savedStudies.associateBy { it.id }
        val expect = cases.getValue("reference").jsonObject
        fun ids(key: String) = expect.getValue(key).jsonArray.map { it.jsonPrimitive.content }
        ids("missingResults").forEach { assertEquals(SavedResultStatus.MISSING, studies.getValue(it).resultStatus) }
        ids("legacyResults").forEach {
            assertEquals(SavedResultStatus.AVAILABLE, studies.getValue(it).resultStatus)
            assertNotNull(studies.getValue(it).coverage?.calculatedResult)
        }
        ids("inputsOnly").forEach { assertEquals(SavedResultStatus.NONE, studies.getValue(it).resultStatus) }
        // Portable results come in B; until then they're kept but not shown.
        ids("sharedResult").forEach { assertEquals(SavedResultStatus.UNSUPPORTED, studies.getValue(it).resultStatus) }
        ids("unknownOrigin").forEach { assertNull(studies.getValue(it).origin) }
    }

    @Test fun savingTheReferenceKeepsEveryReferenceAndFile() = withLibrary { file ->
        reference(file)
        val results = File(file.parentFile, "results")
        val before = results.list()!!.sorted()
        val store = DeploymentPlanStore(file)
        val loaded = store.load()
        store.save(loaded)
        // Unchanged records keep their revisions; the document advances by one.
        val saved = DeploymentPlanStore(file).load()
        assertEquals(loaded.documentRevision!! + 1, saved.documentRevision)
        assertEquals(loaded.savedStudies.map { it.revision }, saved.savedStudies.map { it.revision })
        assertEquals(loaded.savedStudies.map { it.resultRef }, saved.savedStudies.map { it.resultRef })
        assertEquals(loaded.studyPlans, saved.studyPlans)
        assertEquals(before, results.list()!!.sorted())
    }

    @Test fun v2InlineResultMovesToAFileWithItsExactBytes() = withLibrary { file ->
        val v2 = File("../../shared/fixtures/candidate-sites/plan-v2-cases.json")
        val document = Json.parseToJsonElement(v2.readText()).jsonArray.first().jsonObject.getValue("document")
        val payload = "{\"legacy\":true}".toByteArray()
        val encoded = Base64.getEncoder().encodeToString(payload)
        val withResult = JsonObject(document.jsonObject + ("studies" to JsonArray(document.jsonObject.getValue("studies").jsonArray.map { study ->
            val coverage = study.jsonObject["coverage"] as? JsonObject ?: return@map study
            JsonObject(study.jsonObject + ("coverage" to JsonObject(coverage + ("calculatedResult" to JsonPrimitive(encoded)))))
        })))
        val original = withResult.toString().toByteArray()
        file.writeBytes(original)
        val store = DeploymentPlanStore(file)
        val loaded = store.load()
        store.save(loaded)
        val coverage = DeploymentPlanStore(file).load().savedStudies.single { it.kind == SavedStudy.Kind.COVERAGE }
        assertEquals(ResultRef.ANDROID_LEGACY, coverage.resultRef?.format)
        assertArrayEquals(payload, File(file.parentFile, "results/${coverage.resultRef!!.sha256}.result").readBytes())
        assertEquals(encoded, coverage.coverage?.calculatedResult)
        assertEquals(SavedResultStatus.AVAILABLE, coverage.resultStatus)
        assertArrayEquals(original, migrationBackup(file, original).readBytes())
        assertFalse(file.readText().contains("calculatedResult"))
        // IDs, names and timestamps survive the move.
        assertEquals(loaded.content(), DeploymentPlanStore(file).load().content())
    }

    @Test fun missingResultSurvivesRenameButNotAnInputChange() = withLibrary { file ->
        reference(file)
        val store = DeploymentPlanStore(file)
        val loaded = store.load()
        val missingID = cases.getValue("reference").jsonObject.getValue("missingResults").jsonArray.single().jsonPrimitive.content
        val missing = loaded.savedStudies.single { it.id == missingID }
        val renamed = store.save(loaded.copy(studies = loaded.savedStudies.map { if (it.id == missingID) it.copy(name = "Renamed") else it }))
        val afterRename = renamed.savedStudies.single { it.id == missingID }
        assertEquals(missing.resultRef, afterRename.resultRef)
        assertEquals(SavedResultStatus.MISSING, afterRename.resultStatus)
        assertEquals(missing.revision!! + 1, afterRename.revision)
        val changed = store.save(renamed.copy(studies = renamed.savedStudies.map {
            if (it.id == missingID) it.copy(coverage = it.coverage!!.copy(reserveDb = 3.0)) else it
        }))
        // The old result can't be kept as a prediction for changed inputs.
        assertNull(changed.savedStudies.single { it.id == missingID }.resultRef)
    }

    @Test fun wrongBytesAreReportedNotTrusted() = withLibrary { file ->
        reference(file)
        val loaded = DeploymentPlanStore(file).load()
        val legacy = loaded.savedStudies.single { it.resultRef?.format == ResultRef.ANDROID_LEGACY }
        File(file.parentFile, "results/${legacy.resultRef!!.sha256}.result").appendText(" ")
        val reread = DeploymentPlanStore(file).load().savedStudies.single { it.id == legacy.id }
        assertEquals(SavedResultStatus.UNVERIFIED, reread.resultStatus)
        assertNull(reread.coverage?.calculatedResult)
    }

    @Test fun sharedFileStaysUntilItsLastOwnerGoes() = withLibrary { file ->
        reference(file)
        val store = DeploymentPlanStore(file)
        val loaded = store.load()
        val owners = cases.getValue("reference").jsonObject.getValue("sharedResult").jsonArray.map { it.jsonPrimitive.content }
        val sha = loaded.savedStudies.single { it.id == owners[0] }.resultRef!!.sha256
        val shared = File(file.parentFile, "results/$sha.result")
        val one = store.save(loaded.copy(studies = loaded.savedStudies.filterNot { it.id == owners[0] }))
        assertTrue(shared.exists())
        store.save(one.copy(studies = one.savedStudies.filterNot { it.id == owners[1] }))
        assertFalse(shared.exists())
    }

    @Test fun futureVersionsAreRefusedAndNeverOverwritten() = withLibrary { file ->
        file.writeText("{\"schemaVersion\":4,\"plans\":[]}")
        val store = DeploymentPlanStore(file)
        assertEquals(DeploymentPlanFailure.UNSUPPORTED_VERSION,
            assertThrows(DeploymentPlanException::class.java) { store.load() }.reason)
        assertThrows(DeploymentPlanStoreException::class.java) { store.save(DeploymentPlanDocument(3, emptyList(), emptyList())) }
        assertEquals("{\"schemaVersion\":4,\"plans\":[]}", file.readText())
    }

    @Test fun staleEditorsDontOverwriteAndDeletedStudiesSaveAsNew() = runTest {
        val root = Files.createTempDirectory("plans-v3-library").toFile()
        try {
            val file = File(root, "plans.json")
            reference(file)
            val library = SavedPlansLibrary(DeploymentPlanStore(file))
            library.load()
            val opened = library.studies.first()
            val saved = requireNotNull(library.saveStudy(opened.copy(name = "First edit")))
            assertEquals(opened.revision!! + 1, saved.revision)
            // An editor still holding the old revision is refused.
            assertNull(library.saveStudy(opened.copy(name = "Stale edit")))
            assertEquals("First edit", library.studies.first { it.id == opened.id }.name)
            // An editor whose study was deleted saves a new study, not the old one brought back.
            assertTrue(library.deleteStudy(saved))
            val again = requireNotNull(library.saveStudy(saved.copy(name = "After delete")))
            assertNotEquals(opened.id, again.id)
            assertEquals(1L, again.revision)
            // A study plan survives saves made by screens that don't know about plans yet.
            assertEquals(2, DeploymentPlanStore(file).load().studyPlans!!.size)
        } finally { root.deleteRecursively() }
    }
}
