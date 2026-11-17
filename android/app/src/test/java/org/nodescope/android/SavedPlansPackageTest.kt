package org.nodescope.android

import java.io.File
import java.util.Base64
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.nodescope.android.feature.candidatesites.SavedPlansPackage

/** `.nodescope` packages against the shared cases (shared/fixtures/saved-plans/packages), as iOS. */
class SavedPlansPackageTest {
    private val cases = Json.parseToJsonElement(File("../../shared/fixtures/saved-plans/packages/package-cases-v1.json").readText())
        .jsonObject.getValue("cases").jsonArray.map { it.jsonObject }

    private fun JsonObject.text(name: String) = getValue(name).jsonPrimitive.content
    private fun bytes(case: JsonObject) = Base64.getDecoder().decode(case.text("packageBase64"))

    private fun outcome(data: ByteArray): Pair<String, SavedPlansPackage?> = try {
        val read = SavedPlansPackage.read(data)
        (if (read.unusableResults.isEmpty()) "valid" else "resultUnusable") to read
    } catch (failure: SavedPlansPackage.Failure) {
        (if (failure.newerVersion) "future" else "invalid") to null
    }

    @Test fun everySharedPackageCaseIsReadAsExpected() {
        assertEquals(71, cases.size)
        for (case in cases) {
            val name = case.text("name")
            val (expect, read) = outcome(bytes(case))
            assertEquals(name, case.text("expect"), expect)
            if (read != null) {
                assertEquals(name, case.getValue("unusableStudyIDs").jsonArray.map { it.jsonPrimitive.content }.toSet(), read.unusableResults)
                assertTrue(name, read.studies.none { it.id in read.unusableResults && it.resultRef != null })
            }
            val summary = case["summary"] as? JsonObject ?: continue
            requireNotNull(read)
            assertEquals(name, summary.text("scope"), read.scope.wire)
            assertEquals(name, summary.getValue("studies").jsonPrimitive.int, read.studies.size)
            assertEquals(name, summary.getValue("studyPlans").jsonPrimitive.int, read.studyPlans.size)
            assertEquals(name, summary.getValue("candidateSitePlans").jsonPrimitive.int, read.candidateSitePlans.size)
            assertEquals(name, summary.getValue("resultFiles").jsonPrimitive.int, read.results.size)
            assertEquals(name, summary.getValue("omittedResults").jsonPrimitive.int, read.omittedResults.size)
        }
    }

    @Test fun aWrittenPackageReadsBackExactly() {
        for (case in cases.filter { it.text("expect") == "valid" }) {
            val original = SavedPlansPackage.read(bytes(case))
            val written = original.encoded()
            assertEquals(case.text("name"), original, SavedPlansPackage.read(written))
            // Standard tools can open it too.
            java.util.zip.ZipInputStream(written.inputStream()).use { zip ->
                var count = 0
                while (zip.nextEntry != null) { zip.readBytes(); count++ }
                assertEquals(1 + original.results.size, count)
            }
        }
    }
}
