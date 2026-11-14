package org.nodescope.android

import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.nodescope.android.feature.candidatesites.*

/** Kotlin side of the shared version-2 plan contract; mirrors iOS `SavedStudyTests`. */
class SavedStudyTest {
    private fun cases() = Json.parseToJsonElement(
        File("../../shared/fixtures/candidate-sites/plan-v2-cases.json").readText()).jsonArray

    private fun document(): DeploymentPlanDocument =
        DeploymentPlanCodec.decode(cases().first().jsonObject.getValue("document").toString().toByteArray())

    private fun fixture(action: (File) -> Unit) {
        val root = Files.createTempDirectory("saved-studies").toFile()
        try { action(File(root, "plans.json")) } finally { root.deleteRecursively() }
    }

    private fun backup(file: File) = File(file.path + ".v1-backup")

    @Test fun sharedFixturesAndInputRoundTrips() {
        val cases = cases()
        assertEquals(16, cases.size)
        for (item in cases) {
            val fixture = item.jsonObject
            val name = fixture.getValue("name").jsonPrimitive.content
            val bytes = fixture.getValue("document").toString().toByteArray()
            if (fixture.getValue("valid").jsonPrimitive.boolean) {
                val decoded = DeploymentPlanCodec.decode(bytes)
                assertEquals("Round trip: $name", decoded, DeploymentPlanCodec.decode(DeploymentPlanCodec.encode(decoded)))
            } else {
                assertThrows("Reject: $name", Exception::class.java) { DeploymentPlanCodec.decode(bytes) }
            }
        }
        val snapshot = document()
        assertEquals(6.096, requireNotNull(snapshot.savedStudies[0].linkProfile).pointA.antenna.heightMeters, 0.0)
        assertNull(requireNotNull(snapshot.savedStudies[0].linkProfile).pointB.name)
        assertEquals(16_093, requireNotNull(snapshot.savedStudies[1].coverage).radiusMeters)
        assertEquals("Old node name", requireNotNull(snapshot.savedStudies[1].coverage).nodes.first().name)
    }

    @Test fun versionOneNeverWritesStudies() {
        val legacy = DeploymentPlanDocument(1, document().plans, emptyList())
        val text = DeploymentPlanCodec.encode(legacy).decodeToString()
        assertFalse(text.contains("\"studies\""))
        assertEquals(DeploymentPlanDocument(1, legacy.plans), DeploymentPlanCodec.decode(text.toByteArray()))
    }

    @Test fun migrationPreservesLegacyBytesAndUnknownAssumptions() = fixture { file ->
        val next = document()
        val legacy = DeploymentPlanDocument(1, next.plans)
        val bytes = DeploymentPlanCodec.encode(legacy)
        file.writeBytes(bytes)
        val store = DeploymentPlanStore(file)
        assertEquals(legacy, store.load())
        assertFalse(backup(file).exists())
        store.save(next)
        assertArrayEquals(bytes, backup(file).readBytes())
        val reopened = DeploymentPlanStore(file).load()
        assertEquals(next, reopened)
        assertEquals(legacy.plans, reopened.plans)
        assertNull(reopened.plans.first().sites.first().assumptions.frequencyMHz)
        val error = assertThrows(DeploymentPlanException::class.java) { store.save(legacy) }
        assertEquals(DeploymentPlanFailure.UNSUPPORTED_VERSION, error.reason)
        assertEquals(next, store.load())
    }

    @Test fun interruptedMigrationCanBeRetriedWithoutLosingOriginal() = fixture { file ->
        val next = document()
        val bytes = DeploymentPlanCodec.encode(DeploymentPlanDocument(1, next.plans))
        file.writeBytes(bytes)
        val failing = DeploymentPlanStore(file) { _, _ -> throw IOException("injected disk failure") }
        failing.load()
        assertThrows(IOException::class.java) { failing.save(next) }
        assertArrayEquals(bytes, file.readBytes())
        assertArrayEquals(bytes, backup(file).readBytes())
        val retry = DeploymentPlanStore(file)
        retry.load()
        retry.save(next)
        assertEquals(next, retry.load())
    }

    @Test fun conflictingBackupAndCorruptLibraryStayProtected() = fixture { file ->
        val next = document()
        val bytes = DeploymentPlanCodec.encode(DeploymentPlanDocument(1, next.plans))
        file.writeBytes(bytes)
        backup(file).writeText("other original")
        val store = DeploymentPlanStore(file)
        store.load()
        val conflict = assertThrows(DeploymentPlanStoreException::class.java) { store.save(next) }
        assertEquals(DeploymentPlanStoreFailure.CHANGED_ON_DISK, conflict.reason)
        assertArrayEquals(bytes, file.readBytes())
        file.writeText("corrupt")
        assertThrows(Exception::class.java) { store.load() }
        val unloaded = assertThrows(DeploymentPlanStoreException::class.java) { store.save(next) }
        assertEquals(DeploymentPlanStoreFailure.NOT_LOADED, unloaded.reason)
        assertEquals("corrupt", file.readText())
    }

    @Test fun nonfiniteValuesAndOversizeResultsAreRejected() {
        val inputs = requireNotNull(document().savedStudies[1].coverage)
        val infinite = inputs.copy(receiver = inputs.receiver.copy(gainDbi = Double.POSITIVE_INFINITY))
        assertEquals(DeploymentPlanFailure.INVALID_DATA,
            assertThrows(DeploymentPlanException::class.java) { infinite.validate() }.reason)
        val encoded = java.util.Base64.getEncoder().encodeToString(ByteArray(SavedCoverage.MAXIMUM_RESULT_BYTES + 1))
        assertEquals(DeploymentPlanFailure.TOO_LARGE,
            assertThrows(DeploymentPlanException::class.java) { inputs.copy(calculatedResult = encoded).validate() }.reason)
        assertEquals(DeploymentPlanFailure.INVALID_DATA,
            assertThrows(DeploymentPlanException::class.java) { inputs.copy(calculatedResult = "not base64!").validate() }.reason)
    }
}
