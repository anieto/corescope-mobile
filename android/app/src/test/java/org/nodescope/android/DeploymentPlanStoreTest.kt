package org.nodescope.android

import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test
import org.nodescope.android.feature.candidatesites.*

class DeploymentPlanStoreTest {
    private fun document(name: String) = DeploymentPlanDocument(1, listOf(DeploymentPlan(
        "11111111-1111-4111-8111-111111111111", name, 1, 1, sites = emptyList())))
    private fun fixture(action: (File) -> Unit) {
        val root = Files.createTempDirectory("candidate-plans").toFile()
        try { action(File(root, "plans.json")) } finally { root.deleteRecursively() }
    }
    private fun storeFailure(reason: DeploymentPlanStoreFailure, action: () -> Unit) {
        assertEquals(reason, assertThrows(DeploymentPlanStoreException::class.java, action).reason)
    }

    @Test fun saveReopenReplaceAndDelete() = fixture { file ->
        val store = DeploymentPlanStore(file)
        assertTrue(store.load().plans.isEmpty())
        store.save(document("First"))
        assertEquals(document("First"), DeploymentPlanStore(file).load())
        store.save(document("Renamed"))
        assertEquals(document("Renamed"), DeploymentPlanStore(file).load())
        store.save(DeploymentPlanDocument(1, emptyList()))
        assertTrue(DeploymentPlanStore(file).load().plans.isEmpty())
        assertEquals(listOf("plans.json"), requireNotNull(requireNotNull(file.parentFile).list()).toList())
    }

    @Test fun loadRequiredAndUnreadableFilesArePreserved() = fixture { file ->
        for (raw in listOf("broken", "{\"schemaVersion\":2,\"plans\":[]}")) {
            file.writeText(raw)
            val store = DeploymentPlanStore(file)
            assertThrows(Exception::class.java) { store.load() }
            storeFailure(DeploymentPlanStoreFailure.NOT_LOADED) { store.save(document("New")) }
            assertEquals(raw, file.readText())
        }
    }

    @Test fun changedAndDeletedFilesRequireReload() = fixture { file ->
        val store = DeploymentPlanStore(file)
        store.load(); store.save(document("First"))
        file.writeBytes(DeploymentPlanCodec.encode(document("External")))
        storeFailure(DeploymentPlanStoreFailure.CHANGED_ON_DISK) { store.save(document("Draft")) }
        assertEquals(document("External"), store.load())
        assertTrue(file.delete())
        storeFailure(DeploymentPlanStoreFailure.CHANGED_ON_DISK) { store.save(document("Draft")) }
    }

    @Test fun failedWritePreservesPreviousSaveAndDraft() = fixture { file ->
        val previous = DeploymentPlanCodec.encode(document("First"))
        file.writeBytes(previous)
        val store = DeploymentPlanStore(file) { _, _ -> throw IOException("injected disk failure") }
        store.load()
        val draft = document("Unsaved")
        assertThrows(IOException::class.java) { store.save(draft) }
        assertEquals(document("Unsaved"), draft)
        assertArrayEquals(previous, file.readBytes())
        assertEquals(document("First"), store.load())
    }

    @Test fun invalidDraftDoesNotReplaceSavedPlan() = fixture { file ->
        val store = DeploymentPlanStore(file)
        store.load(); store.save(document("First"))
        assertThrows(DeploymentPlanException::class.java) { store.save(document("")) }
        assertEquals(document("First"), store.load())
    }

    @Test fun oversizedLibraryIsNotReset() = fixture { file ->
        file.writeBytes(ByteArray(DeploymentPlanCodec.MAXIMUM_BYTES + 1))
        val store = DeploymentPlanStore(file)
        assertEquals(DeploymentPlanFailure.TOO_LARGE,
            assertThrows(DeploymentPlanException::class.java) { store.load() }.reason)
        storeFailure(DeploymentPlanStoreFailure.NOT_LOADED) { store.save(document("New")) }
        assertEquals(DeploymentPlanCodec.MAXIMUM_BYTES.toLong() + 1, file.length())
    }
}
