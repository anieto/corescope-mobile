package org.nodescope.android

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.nodescope.android.feature.candidatesites.*

/**
 * iOS ↔ Android round trips (D step 5). Each platform exports the shared v3 reference library to
 * shared/fixtures/saved-plans/packages/<platform>-library-v1.nodescope (written when absent, then kept), and
 * imports the other platform's file into an empty library. Mirrors iOS CrossPlatformPackageTests.
 */
class CrossPlatformPackageTest {
    private val shared = File("../../shared/fixtures/saved-plans")
    private val written = File(shared, "packages/android-library-v1.nodescope")
    private val fromIOS = File(shared, "packages/ios-library-v1.nodescope")

    private suspend fun library(reference: Boolean): Pair<SavedPlansLibrary, File> {
        val root = Files.createTempDirectory("cross").toFile().apply { deleteOnExit() }
        if (reference) {
            File(shared, "v3/reference/plans.json").copyTo(File(root, "plans.json"))
            File(shared, "v3/reference/results").copyRecursively(File(root, "results"))
        }
        return SavedPlansLibrary(DeploymentPlanStore(File(root, "plans.json"))).apply { load() } to File(root, "plans.json")
    }

    private suspend fun restore(pack: SavedPlansPackage): SavedPlansLibrary {
        val (target, file) = library(reference = false)
        val current = SavedPlansImport.Library(target.plans, target.studyPlans, target.studies)
        val preview = SavedPlansImport.preview(pack, current)
        val records = SavedPlansImport.records(preview, current, preview.defaultSelection, inputsOnly = false)
        assertTrue(target.addRecords(records.plans, records.studyPlans, records.studies))
        return SavedPlansLibrary(DeploymentPlanStore(file)).apply { load() }
    }

    private fun available(library: SavedPlansLibrary) =
        library.studies.filter { it.resultStatus == SavedResultStatus.AVAILABLE }.mapNotNull { it.importOrigin?.studyID }.toSet()

    @Test fun androidsLibraryFileReadsBackAndRestores() = runTest {
        if (!written.exists()) {
            val (source, _) = library(reference = true)
            val pack = requireNotNull(SavedPlansExport.packageFor(SavedPlansExport.Contents.Library, source.plans, source.studyPlans,
                source.studies, true, true, 1_794_790_000_000, "0.10.7"))
            written.writeBytes(pack.encoded())
        }
        val pack = SavedPlansPackage.read(written.readBytes())
        assertEquals(SavedPlansPackage.Platform.ANDROID, pack.exportedBy.platform)
        val restored = restore(pack)
        assertEquals(6, restored.studies.size)
        // Android converts its own 0.10.6 result; only the result missing from this device stays behind.
        assertEquals(setOf("40000000-0000-4000-8000-000000000001", "40000000-0000-4000-8000-000000000002",
            "40000000-0000-4000-8000-000000000003", "40000000-0000-4000-8000-000000000006"), available(restored))
    }

    @Test fun iOSsLibraryFileRestoresOnAndroid() = runTest {
        assumeTrue("Run iOS CrossPlatformPackageTests once to record ${fromIOS.name}", fromIOS.exists())
        val pack = SavedPlansPackage.read(fromIOS.readBytes())
        assertEquals(SavedPlansPackage.Platform.IOS, pack.exportedBy.platform)
        assertTrue(pack.unusableResults.isEmpty())
        val restored = restore(pack)
        assertEquals(6, restored.studies.size)
        assertEquals(2, restored.studyPlans.size)
        assertEquals("Compare the roof mount with a 10 m mast.", restored.studyPlans.first { it.name == "Ridge relay site" }.notes)
        // iOS can't read Android's 0.10.6 result, so it left that one out and said so.
        assertEquals(setOf("40000000-0000-4000-8000-000000000003", "40000000-0000-4000-8000-000000000005"),
            pack.omittedResults.map { it.studyID }.toSet())
        assertEquals(setOf("40000000-0000-4000-8000-000000000001", "40000000-0000-4000-8000-000000000002",
            "40000000-0000-4000-8000-000000000006"), available(restored))
    }
}
