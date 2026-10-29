package org.nodescope.android

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.nodescope.android.core.model.*
import org.nodescope.android.core.network.*
import org.nodescope.android.core.storage.*

class MyNodesVisitSessionTest {
    private val namespace = MyNodesNamespace("https://fixture.invalid", "corescope", "local", 1)
    private val key = "a".repeat(64)
    private suspend fun open(store: MyNodesSnapshotStore, source: AnalyzerSourceSession) =
        MyNodesVisitSession.open(store, source, namespace, setOf(key), emptySet(), now = 1000000)
    private suspend fun temporary(block: suspend (File) -> Unit) {
        val directory = Files.createTempDirectory("my-nodes-visit").toFile()
        try { block(File(directory, "snapshots.json")) } finally { directory.deleteRecursively() }
    }

    @Test fun reviewDistinguishesUnavailableFirstVisitChangedAndUnchanged() = runBlocking { temporary { file ->
        val store = MyNodesSnapshotStore(file)
        val source = AnalyzerSourceSession()
        val entity = MyNodesEntity.node(key)
        fun metric(seen: Long, fetched: Long) = MyNodesMetric(activity = MyNodesActivity.assess(seen, null, fetched, fetched, true))
        val first = open(store, source)
        val quiet = metric(0, 1_000_000)
        assertEquals(MyNodesReviewResult.State.UNAVAILABLE, first.reviewed(entity, quiet, false, 1_000_000).state)
        assertEquals(MyNodesReviewResult.State.FIRST_VISIT, first.reviewed(entity, quiet, true, 1_000_000).state)
        first.finish()
        val visit = MyNodesVisitSession.open(store, source, namespace, setOf(key), emptySet(), now = 1_010_000)
        val active = metric(1_010_000, 1_010_000)
        val changed = visit.reviewed(entity, active, true, 1_010_000)
        assertEquals(MyNodesReviewResult.State.CHANGED, changed.state)
        assertEquals("newlyActive", changed.event?.change)
        val repeated = visit.reviewed(entity, metric(1_020_000, 1_020_000), true, 1_020_000)
        assertEquals(MyNodesReviewResult.State.CHANGED, repeated.state)
        assertEquals(changed.event?.id, repeated.event?.id)
        assertEquals(MyNodesReviewResult.State.UNAVAILABLE, visit.reviewed(entity, active, false, 1_020_000).state)
        visit.finish()
        val next = MyNodesVisitSession.open(store, source, namespace, setOf(key), emptySet(), now = 1_030_000)
        assertEquals(MyNodesReviewResult.State.UNCHANGED, next.reviewed(entity, metric(1_020_000, 1_030_000), true, 1_030_000).state)
        next.finish()
        val expiredTime = 1_030_000L + 8 * 86_400_000L
        val expired = MyNodesVisitSession.open(store, source, namespace, setOf(key), emptySet(), now = expiredTime)
        assertEquals(MyNodesReviewResult.State.EXPIRED_BASELINE, expired.reviewed(entity, metric(expiredTime, expiredTime), true, expiredTime).state)
        expired.finish()
    } }

    @Test fun departurePromotesAndForegroundStartsNewVisit() = runBlocking { temporary { file ->
        val store = MyNodesSnapshotStore(file)
        val source = AnalyzerSourceSession()
        val session = open(store, source)
        val captured = session.visit
        val metric = MyNodesMetric(activity = MyNodesActivity.assess(1000000, null, 1000000, 1000000, true))
        session.rendered(MyNodesEntity.node(key), metric, true, 1000000)
        assertEquals(captured.id, session.visit.id)
        session.finish()
        assertTrue(source.isCurrent)
        try { session.perform { 1 }; fail("Closed visit ran work") } catch (_: CancellationException) { }
        val foreground = open(store, source)
        assertNotEquals(captured.id, foreground.visit.id)
        assertEquals(1, foreground.visit.baselines.size)
        foreground.finish()
    } }

    @Test fun sourceChangeAndCacheClearRejectLateResults() = runBlocking {
        for (clearsCache in listOf(false, true)) temporary { file ->
            val store = MyNodesSnapshotStore(file)
            val source = AnalyzerSourceSession()
            val session = open(store, source)
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val task = async {
                session.perform { started.complete(Unit); withContext(NonCancellable) { release.await() }; 1 }
            }
            started.await()
            if (clearsCache) store.clear() else source.invalidate()
            release.complete(Unit)
            try { task.await(); fail("Late result escaped") } catch (_: CancellationException) { }
            try {
                session.rendered(MyNodesEntity.node(key), MyNodesMetric(), true, 1000000)
                fail("Late write escaped")
            } catch (_: CancellationException) { }
            session.finish()
            if (clearsCache) assertFalse(file.exists())
        }
    }

    @Test fun closingOneVisitPreservesSharedAnalyticsConsumer() = runBlocking { temporary { file ->
        val store = MyNodesSnapshotStore(file)
        val source = AnalyzerSourceSession()
        val first = open(store, source)
        val second = open(store, source)
        val release = CompletableDeferred<Unit>()
        val repository = MyNodesAnalyticsRepository("fixture", source) { _, _, _ ->
            release.await(); throw HttpFailure(503)
        }
        val one = async { first.loadAnalytics(repository, listOf(key)) }
        val two = async { second.loadAnalytics(repository, listOf(key)) }
        withTimeout(5000) { while (repository.activeConsumerCount != 2) delay(1) }
        first.finish()
        try { one.await(); fail("Closed visit returned data") } catch (_: CancellationException) { }
        assertEquals(1, repository.activeConsumerCount)
        release.complete(Unit)
        val rows = two.await()
        assertEquals(1, rows.size)
        assertEquals(MyNodesAnalyticsState.FAILED, rows.first().state)
        assertTrue(source.isCurrent)
        second.finish()
    } }
}
