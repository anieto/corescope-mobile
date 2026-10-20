package org.nodescope.android

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.nodescope.android.core.design.NodeScopeTheme
import org.nodescope.android.core.model.MeshNode
import org.nodescope.android.core.network.*
import org.nodescope.android.core.storage.*
import org.nodescope.android.feature.explore.MyNodesScreen
import java.io.File
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

class MyNodesUiTest {
    @get:Rule val compose = createAndroidComposeRule<UiTestActivity>()

    @Test fun warmActivityOpensNodeAndCacheClearRemovesEvidence() {
        val root = File(compose.activity.cacheDir, "my-nodes-test-${UUID.randomUUID()}")
        val requests = AtomicInteger()
        val http = OkHttpClient.Builder().addInterceptor {
            requests.incrementAndGet()
            throw java.io.IOException("Unexpected network request")
        }.build()
        val owner = MyNodesStorage(root)
        val source = AnalyzerSourceSession()
        val owned = owner.coreScope("example.test", http, source)
        val now = System.currentTimeMillis()
        val key = "a".repeat(64)
        val node = MeshNode(key, name = "Test repeater", role = "repeater", lastSeen = Instant.ofEpochMilli(now).toString())
        val library = NodeLibrary().apply { toggle(node) }
        owned.base.seedCatalog(listOf(node), now, owned.base.source)
        owned.base.seedObservers(MyNodesObserverCatalogSnapshot(emptyList(), now, MyNodesCatalogCompleteness.COMPLETE), owned.base.source)
        var opened: String? = null
        try {
            compose.setContent {
                NodeScopeTheme(Appearance.LIGHT) {
                    MyNodesScreen("example.test", library, { owned }, { opened = it }, { _, _ -> })
                }
            }
            compose.waitUntil(5000) {
                compose.onAllNodesWithText("Recently heard").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("Recently heard").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Load more").assertDoesNotExist()
            compose.onNodeWithContentDescription("About activity").performClick()
            compose.onNodeWithText("Done").performClick()
            compose.onNodeWithText("Test repeater").performClick()
            compose.runOnIdle { assertEquals(key, opened) }
            compose.runOnIdle { runBlocking { owner.clear() } }
            compose.waitUntil(5000) {
                compose.onAllNodesWithText("Activity data was cleared or the source changed. Refresh to continue.").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("Activity unknown").performScrollTo().assertIsDisplayed()
            compose.runOnIdle { assertEquals(0, requests.get()) }
        } finally { source.invalidate(); root.deleteRecursively() }
    }
}
