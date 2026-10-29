package org.nodescope.android

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.nodescope.android.core.design.NodeScopeTheme
import org.nodescope.android.core.model.*
import org.nodescope.android.core.network.*
import org.nodescope.android.core.storage.*
import org.nodescope.android.feature.explore.FavoriteChangesScreen
import java.io.File
import java.time.Instant
import java.util.UUID

class FavoriteChangesUiTest {
    @get:Rule val compose = createAndroidComposeRule<UiTestActivity>()

    @Test fun onlyVisibleComparisonsBecomeNextVisitBaseline() {
        val root = File(compose.activity.cacheDir, "changes-ui-${UUID.randomUUID()}")
        val store = MyNodesSnapshotStore(File(root, "snapshots.json"))
        val lifetime = AnalyzerSourceSession()
        val parent = AnalyzerSourceSession()
        val source = "corescope|https://fixture.invalid"
        val base = MyNodesBaseRepository(source, lifetime) { key, _, budget ->
            budget.perform(key) { MeshNode(key, "Test node", "repeater", lastSeen = Instant.now().toString()) }
        }
        val analytics = MyNodesAnalyticsRepository(source, lifetime) { _, _, _ -> error("Unused analytics") }
        val owned = MyNodesRepositories(parent, lifetime, base, analytics)
        val library = NodeLibrary()
        val keys = (0..29).map { it.toString(16).padStart(64, 'a') }
        keys.forEach { library.toggle(MeshNode(it, "Node ${it.takeLast(2)}", "repeater", lastSeen = Instant.now().toString())) }
        val shown = mutableStateOf(true)
        try {
            compose.setContent {
                NodeScopeTheme(Appearance.LIGHT) {
                    if (shown.value) FavoriteChangesScreen("fixture.invalid", "Test source", library, store, { owned })
                }
            }
            compose.waitUntil(10000) {
                compose.onAllNodesWithText("First visit · starting comparison history").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("Test source").assertIsDisplayed()
            compose.onNodeWithContentDescription("Refresh").performClick()
            compose.waitForIdle()
            compose.runOnIdle { shown.value = false }
            val namespace = MyNodesNamespace("https://fixture.invalid", "corescope", "local", 1)
            var reviewedCount = 0
            compose.waitUntil(5000) {
                runBlocking {
                    val visit = store.begin(namespace, keys.toSet(), emptySet(), now = System.currentTimeMillis())
                    reviewedCount = visit.baselines.size
                    store.end(visit)
                }
                reviewedCount > 0
            }
            assertTrue("Offscreen rows must not be marked reviewed", reviewedCount < 20)
        } finally {
            owned.invalidate()
            root.deleteRecursively()
        }
    }
}
