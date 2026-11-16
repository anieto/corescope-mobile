package org.nodescope.android

import java.io.File
import java.nio.file.Files
import java.util.UUID
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.nodescope.android.feature.candidatesites.*

/** Recovery drafts (deliverable C, step 1): independent sessions, raw fields, results kept through cleanup, no eviction. */
class StudyDraftStoreTest {
    private val root = Files.createTempDirectory("drafts").toFile()
    private val store = StudyDraftStore(root)

    @After fun cleanUp() { root.deleteRecursively() }

    private fun draft(title: String, updated: Long = 1, editor: Map<String, String> = emptyMap()) = StudyDraft(
        UUID.randomUUID().toString(), SavedStudy.Kind.COVERAGE, 1, updated, title, host = "analyzer.example", region = "AUS",
        editor = editor)

    /** A library save, which runs result cleanup. */
    private fun saveLibrary() {
        val library = DeploymentPlanStore(File(root, "plans.json"))
        val document = library.load()
        library.save(document.copy(schemaVersion = 3, studies = document.studies.orEmpty(), studyPlans = document.studyPlans.orEmpty()))
    }

    @Test fun sessionsAreSavedListedAndDeletedIndependently() {
        val first = store.save(draft("Ridge", updated = 10), null)
        val second = store.save(draft("Valley", updated = 20), null)
        assertEquals(listOf("Valley", "Ridge"), store.list().map { it.title })
        store.save(first.copy(title = "Ridge, edited", updatedAtMilliseconds = 30), null)
        assertEquals(listOf("Ridge, edited", "Valley"), store.list().map { it.title })
        store.delete(second.id)
        assertEquals(listOf(first.id), store.list().map { it.id })
    }

    @Test fun incompleteTextAndTheCatalogComeBackExactly() {
        val catalog = listOf(SavedCoverageNode("analyzer.example", "a1", "Hilltop", "repeater", 29.4, -98.5, 1794770000000))
        val saved = store.save(draft("Ridge", editor = mapOf("latitude" to "29.", "txHeight" to "")).copy(
            catalog = catalog, catalogComplete = true, planID = UUID.randomUUID().toString(), savedRevision = 3), null)
        assertEquals(saved, store.draft(saved.id))
    }

    @Test fun aDraftsResultSurvivesLibraryCleanupUntilTheDraftGoes() {
        val payload = "{\"format\":\"nodescope.coverage-result\"}".toByteArray()
        val saved = store.save(draft("Ridge"), payload)
        saveLibrary()
        assertArrayEquals(payload, store.result(saved))
        store.delete(saved.id)
        saveLibrary()
        assertFalse(File(root, "results/${saved.resultSha256}.result").exists())
    }

    @Test fun anUnreadableDraftStopsCleanupDeletingAnything() {
        File(root, "drafts").mkdirs()
        File(root, "drafts/${UUID.randomUUID()}.json").writeText("{ not a draft")
        val stray = File(root, "results/${"ab".repeat(32)}.result").apply { parentFile.mkdirs(); writeText("x") }
        saveLibrary()
        assertTrue(stray.exists())
    }

    @Test fun aFullStoreRefusesNewDraftsButNeverEvicts() {
        val drafts = (1..StudyDraftStore.MAXIMUM_DRAFTS).map { store.save(draft("Draft $it", updated = it.toLong()), null) }
        val failure = assertThrows(StudyDraftException::class.java) { store.save(draft("One more"), null) }
        assertEquals(StudyDraftFailure.FULL, failure.reason)
        assertEquals(StudyDraftStore.MAXIMUM_DRAFTS, store.list().size)
        // Existing sessions still save.
        store.save(drafts.first().copy(title = "Still saving"), null)
        assertTrue(store.list().any { it.title == "Still saving" })
    }
}
