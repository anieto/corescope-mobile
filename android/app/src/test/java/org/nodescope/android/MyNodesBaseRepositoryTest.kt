package org.nodescope.android

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.junit.Assert.*
import org.junit.Test
import org.nodescope.android.core.model.MeshNode
import org.nodescope.android.core.model.MeshObserver
import org.nodescope.android.core.network.*

class MyNodesBaseRepositoryTest {
    private fun key(index: Int) = "a".repeat(56) + "%08x".format(index)
    private class Server {
        val calls = AtomicInteger()
        val active = AtomicInteger()
        val peak = AtomicInteger()
        @Volatile var missing = false
        @Volatile var rateLimited = false
        @Volatile var blocked: String? = null
        val release = CompletableDeferred<Unit>()
        suspend fun fetch(key: String, budget: MyNodesRequestBudget): MeshNode? = budget.perform(key) {
            calls.incrementAndGet()
            val count = active.incrementAndGet()
            peak.updateAndGet { maxOf(it, count) }
            try {
                if (blocked == key) withContext(NonCancellable) { release.await() }
                yield()
                if (rateLimited) throw HttpFailure(429)
                if (missing) null else MeshNode(key, role = "repeater", lastSeen = "")
            } finally { active.decrementAndGet() }
        }
    }
    private fun repository(server: Server) = MyNodesBaseRepository("fixture", AnalyzerSourceSession()) { key, _, budget -> server.fetch(key, budget) }

    @Test fun observerPagesShareBudgetAndWarmCache() = runBlocking {
        val server = Server()
        val stamp = System.currentTimeMillis()
        val repo = MyNodesBaseRepository("fixture", AnalyzerSourceSession(), now = { stamp }, fetchObservers = { budget ->
            MyNodesBaseRepository.observerPages({ stamp }) { cursor ->
                budget.perform("observer-catalog") {
                    val index = cursor?.value?.toInt() ?: 0
                    AnalyzerPage(listOf(MeshObserver("observer-$index")), AnalyzerCursor((index + 1).toString()))
                }
            }
        }) { key, _, budget -> server.fetch(key, budget) }
        val rows = repo.load((0 until 100).map(::key))
        assertEquals(16, rows.count { it.state == MyNodesBaseState.FETCHED })
        assertEquals(16, server.calls.get())
        assertEquals(4, repo.observers().snapshot?.observers?.size)
        assertEquals(MyNodesCatalogCompleteness.PARTIAL, repo.observers().snapshot?.completeness)
        assertEquals(MyNodesBaseState.FETCHED, repo.observers().state)
        repo.load((0 until 16).map(::key))
        assertEquals(MyNodesBaseState.CACHED, repo.observers().state)
        assertEquals(stamp, repo.observers().snapshot?.fetchedAt)
        assertEquals(16, server.calls.get())
    }

    @Test fun observerOnlyRefreshAndFailurePreservePriorEvidence() = runBlocking {
        val stamp = System.currentTimeMillis()
        val previous = MyNodesObserverCatalogSnapshot(listOf(MeshObserver("remembered")), stamp - 400_000, MyNodesCatalogCompleteness.PARTIAL)
        val repo = MyNodesBaseRepository("fixture", AnalyzerSourceSession(), fetchObservers = { budget ->
            budget.perform("observer-catalog") { throw HttpFailure(429) }
        }) { _, _, _ -> error("Node work must stop after catalog rate limit") }
        repo.seedObservers(previous, "fixture")
        assertEquals(MyNodesBaseState.DEFERRED, repo.load(listOf(key(0))).first().state)
        assertEquals(MyNodesBaseState.RATE_LIMITED, repo.observers().state)
        assertEquals(previous.fetchedAt, repo.observers().snapshot?.fetchedAt)
        repo.clear()
        assertNull(repo.observers().snapshot)
        val empty = MyNodesBaseRepository("fixture", AnalyzerSourceSession(), fetchObservers = {
            MyNodesBaseRepository.observerPages({ stamp }) { AnalyzerPage(emptyList(), null) }
        }) { _, _, _ -> null }
        assertTrue(empty.load(emptyList()).isEmpty())
        assertEquals(MyNodesCatalogCompleteness.COMPLETE, empty.observers().snapshot?.completeness)
    }

    @Test fun repeatedObserverCursorIsRejected() = runBlocking {
        assertTrue(runCatching {
            MyNodesBaseRepository.observerPages(System::currentTimeMillis) {
                AnalyzerPage(listOf(MeshObserver("same")), AnalyzerCursor("loop"))
            }
        }.exceptionOrNull() is IllegalArgumentException)
    }

    @Test fun coldWarmAndNextBatchAcrossSelectionSizes() = runBlocking {
        for (size in listOf(0, 1, 20, 100, 500)) {
            val server = Server()
            val repo = repository(server)
            val keys = (0 until size).map(::key)
            val first = repo.load(keys)
            assertEquals(keys, first.map { it.publicKey })
            assertEquals(minOf(size, 20), first.count { it.state == MyNodesBaseState.FETCHED })
            assertEquals(minOf(size, 20), server.calls.get())
            assertTrue(server.peak.get() <= 2)
            val next = repo.load(keys)
            assertEquals(minOf(size, 20), next.count { it.state == MyNodesBaseState.CACHED })
            assertEquals(minOf(size, 40), server.calls.get())
        }
    }

    @Test fun largeSelectionDrainsBoundedBatchesThenLoadsEntirelyFromCache() = runBlocking {
        val server = Server()
        val stamp = 1_800_000_000_000L
        val repo = MyNodesBaseRepository("fixture", AnalyzerSourceSession(), now = { stamp }) { key, _, budget ->
            server.fetch(key, budget)
        }
        val keys = (0 until 500).map(::key)
        val started = System.nanoTime()
        repeat(25) { batch ->
            val rows = repo.load(keys)
            assertEquals(keys, rows.map { it.publicKey })
            assertEquals(20, rows.count { it.state == MyNodesBaseState.FETCHED })
            assertEquals(batch * 20, rows.count { it.state == MyNodesBaseState.CACHED })
            assertEquals(500 - (batch + 1) * 20, rows.count { it.state == MyNodesBaseState.DEFERRED })
            assertEquals((batch + 1) * 20, server.calls.get())
            assertTrue(server.peak.get() <= 2)
        }
        val coldMillis = (System.nanoTime() - started) / 1_000_000.0
        val warmStarted = System.nanoTime()
        val warm = repo.load(keys)
        val warmMillis = (System.nanoTime() - warmStarted) / 1_000_000.0
        assertEquals(keys, warm.map { it.publicKey })
        assertTrue(warm.all { it.state == MyNodesBaseState.CACHED })
        assertEquals(500, server.calls.get())
        println("Synthetic 500-node loading: 25 batches=${coldMillis}ms, warm=${warmMillis}ms, calls=${server.calls.get()}, peak=${server.peak.get()}")
    }

    @Test fun catalogOmissionsAndUnavailableStayUnknown() = runBlocking {
        val server = Server()
        val repo = repository(server)
        val now = System.currentTimeMillis()
        repo.seedCatalog(listOf(MeshNode(key(0), role = "repeater", lastSeen = "")), now, "fixture")
        assertTrue(runCatching { repo.seedCatalog(emptyList(), now, "another-source") }.isFailure)
        val rows = repo.load(listOf(key(0), key(1)))
        assertEquals(MyNodesBaseState.CACHED, rows[0].state)
        assertEquals(now, rows[0].snapshot?.fetchedAt)
        assertEquals(MyNodesBaseState.FETCHED, rows[1].state)
        assertEquals(1, server.calls.get())
        server.missing = true
        val missing = repo.load(listOf(key(0)), forceRefresh = true).first()
        assertEquals(MyNodesBaseState.UNAVAILABLE, missing.state)
        assertNotNull(missing.snapshot)
        assertTrue(repo.cached(listOf(key(0))).isEmpty())
        repo.load(listOf(key(0)))
        assertEquals(3, server.calls.get())
    }

    @Test fun staleCatalogRefreshesAndFutureCatalogIsRejected() = runBlocking {
        val server = Server()
        val repo = repository(server)
        repo.seedCatalog(listOf(MeshNode(key(0), role = "repeater", lastSeen = "")), System.currentTimeMillis() - 301000, "fixture")
        assertEquals(MyNodesBaseState.FETCHED, repo.load(listOf(key(0))).first().state)
        assertTrue(runCatching { repo.seedCatalog(emptyList(), System.currentTimeMillis() + 100000, "fixture") }.isFailure)
        assertEquals(1, server.calls.get())
    }

    @Test fun progressiveRowsArriveBeforeSlowLookup() = runBlocking {
        val server = Server().apply { blocked = key(0) }
        val repo = repository(server)
        val updates = Channel<MyNodesBaseRow>(Channel.UNLIMITED)
        val task = async { repo.load(listOf(key(0), key(1))) { updates.trySend(it) } }
        val row = withTimeout(5000) { updates.receive() }
        assertEquals(key(1), row.publicKey)
        assertEquals(MyNodesBaseState.FETCHED, row.state)
        assertEquals(1, repo.cached(listOf(key(1))).size)
        assertFalse(task.isCompleted)
        server.release.complete(Unit)
        assertEquals(2, task.await().size)
        updates.close()
        Unit
    }

    @Test fun clearRejectsJoinedAndLateWorkAndRateLimitStopsScheduling() = runBlocking {
        val server = Server().apply { blocked = key(0) }
        val repo = repository(server)
        val first = async { repo.load(listOf(key(0))) }
        withTimeout(5000) { while (server.calls.get() != 1) delay(1) }
        val joined = async { repo.load(listOf(key(1))) }
        withTimeout(5000) { while (repo.activeConsumerCount != 2) delay(1) }
        repo.clear()
        try { first.await(); fail("Expected cancellation") } catch (_: CancellationException) { }
        try { joined.await(); fail("Expected cancellation") } catch (_: CancellationException) { }
        server.release.complete(Unit)
        assertTrue(repo.cached(listOf(key(0))).isEmpty())
        server.rateLimited = true
        val rows = repo.load((0 until 100).map(::key))
        assertTrue(rows.any { it.state == MyNodesBaseState.RATE_LIMITED })
        assertTrue(server.calls.get() <= 3)
        assertTrue(rows.count { it.state == MyNodesBaseState.DEFERRED } >= 98)
    }
}
