package org.nodescope.android

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.nodescope.android.core.model.*
import org.nodescope.android.core.network.*
import org.nodescope.android.core.storage.*
import org.nodescope.android.feature.explore.FavoriteChangesModel
import java.nio.file.Files
import java.time.Instant

class FavoriteChangesTest {
    @Test fun onlyReviewedRowsAdvanceAndRefreshKeepsBaseline() = runBlocking {
        val root = Files.createTempDirectory("changes-ui").toFile()
        val store = MyNodesSnapshotStore(root.resolve("snapshots.json"))
        val parent = AnalyzerSourceSession()
        val lifetime = AnalyzerSourceSession()
        val source = "corescope|https://fixture.invalid"
        val keys = listOf("a".repeat(64), "b".repeat(64))
        val base = MyNodesBaseRepository(source, lifetime) { key, _, budget ->
            budget.perform(key) { MeshNode(key, "Test", "repeater", lastSeen = Instant.now().toString()) }
        }
        val analytics = MyNodesAnalyticsRepository(source, lifetime) { _, _, _ -> error("Unused analytics") }
        val owned = MyNodesRepositories(parent, lifetime, base, analytics)
        val items = keys.map { SavedItem(SavedKind.NODE, it, "Test") }
        val model = FavoriteChangesModel()
        var run: Job? = null
        try {
            run = launch { model.run(store, { owned }, items) }
            withTimeout(5000) { while (model.rows.size != 2 || model.loading) delay(10) }
            assertTrue(model.rows.all { it.result.state == MyNodesReviewResult.State.FIRST_VISIT })
            val visible = model.rows.first()
            model.review(visible)
            model.refresh(true)
            withTimeout(5000) { while (model.rows.first().revision == visible.revision || model.loading) delay(10) }
            assertTrue(model.rows.all { it.result.state == MyNodesReviewResult.State.FIRST_VISIT })
            model.review(model.rows.first())
            run.cancelAndJoin()
            val namespace = MyNodesNamespace("https://fixture.invalid", "corescope", "local", 1)
            val next = store.begin(namespace, keys.toSet(), emptySet(), now = System.currentTimeMillis())
            assertEquals(1, next.baselines.size)
            assertNotNull(next.baselines[MyNodesSnapshotStore.metricKey(visible.entity, MyNodesMetric.Category.ACTIVITY)])
            store.end(next)
            val revision = model.rows.first().revision
            run = launch { model.run(store, { owned }, items) }
            withTimeout(5000) {
                while (model.rows.size != 2 || model.loading || model.rows.first().revision == revision) delay(10)
            }
            assertEquals(MyNodesReviewResult.State.UNCHANGED, model.rows.first().result.state)
            assertEquals(MyNodesReviewResult.State.FIRST_VISIT, model.rows.last().result.state)
        } finally {
            run?.cancelAndJoin()
            owned.invalidate()
            root.deleteRecursively()
        }
    }
}
