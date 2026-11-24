package org.nodescope.android

import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.nodescope.android.feature.settings.AppFeature
import org.nodescope.android.feature.settings.WhatsNew

class WhatsNewTest {
    private val android = File("src/main/assets/whats-new.json")

    @Test fun contentMatchesIOSAndShipsForThisVersion() {
        assertArrayEquals(File("../../CoreScopeViewer/Resources/whats-new.json").readBytes(), android.readBytes())
        val releases = WhatsNew.parse(android.readText())
        val current = WhatsNew.latest(releases, BuildConfig.VERSION_NAME)
        assertNotNull("whats-new.json has no release up to ${BuildConfig.VERSION_NAME}", current)
        // iOS-only items stay off Android; every item has an icon and text.
        assertTrue(releases.flatMap { it.items }.none { it.id == "ipad-mac" })
        assertTrue(releases.flatMap { it.items }.all { it.title.isNotBlank() && it.detail.isNotBlank() && it.icon.isNotBlank() })
        val plannerRelease = releases.single { it.version == "0.11.0" }
        assertEquals(AppFeature.estimatedCoverage, plannerRelease.items.first { it.id == "estimated-coverage" }.destination)
        assertEquals(AppFeature.linkProfile, plannerRelease.items.first { it.id == "link-profile" }.destination)
        // Anything that opens a feature also says where to find it again.
        assertTrue(releases.flatMap { it.items }.filter { it.destination != null }.all { !it.location.isNullOrBlank() })
    }

    @Test fun showsOnlyUnseenShippedReleasesOnce() {
        val releases = WhatsNew.parse("""{"version":1,"releases":[
            {"version":"0.10.6","items":[{"id":"a","title":"A","detail":"a","icon":"coverage"}]},
            {"version":"0.10.8","items":[{"id":"b","title":"B","detail":"b","icon":"savedPlans","destination":"savedPlans"}]},
            {"version":"0.11.0","items":[{"id":"c","title":"C","detail":"c","icon":"x"}]},
            {"version":"0.10.7","items":[{"id":"ios","title":"I","detail":"i","icon":"keyboard","platforms":["ios"]}]}]}""")
        // A release with only iOS items isn't a release on Android.
        assertEquals(listOf("0.10.6", "0.10.8", "0.11.0"), releases.map { it.version })
        assertEquals(listOf("0.11.0", "0.10.8", "0.10.6"), WhatsNew.history(releases, "1.0.0").map { it.version })
        assertEquals(listOf("0.10.8", "0.10.6"), WhatsNew.history(releases, "0.10.9").map { it.version })
        // Updating from before this feature: only this build's highlights, not the whole history.
        assertEquals(listOf("0.10.8"), WhatsNew.pending(releases, null, "0.10.8").map { it.version })
        // Skipping versions shows everything missed, newest first; future releases never show.
        assertEquals(listOf("0.10.8", "0.10.6"), WhatsNew.pending(releases, "0.10.5", "0.10.9").map { it.version })
        // Already seen, or a bug-fix build with no highlights: nothing.
        assertTrue(WhatsNew.pending(releases, "0.10.8", "0.10.8").isEmpty())
        assertTrue(WhatsNew.pending(releases, "0.10.8", "0.10.9").isEmpty())
        assertEquals("0.10.8", WhatsNew.latest(releases, "0.10.9")?.version)
        assertTrue(WhatsNew.compare("0.10.10", "0.10.9") > 0)
        assertEquals(0, WhatsNew.compare("1.0", "1.0.0"))
    }
}
