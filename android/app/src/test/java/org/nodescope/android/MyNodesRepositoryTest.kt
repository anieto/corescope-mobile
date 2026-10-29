package org.nodescope.android

import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.nodescope.android.core.model.*
import org.nodescope.android.core.network.*

class MyNodesRepositoryTest {
    private fun key(index: Int) = "a".repeat(56) + "%08x".format(index)
    private class Server {
        val calls = AtomicInteger()
        val active = AtomicInteger()
        val peak = AtomicInteger()
        @Volatile var gate: CompletableDeferred<Unit>? = null
        @Volatile var fails = false
        @Volatile var status = 503
        suspend fun fetch(key: String, to: Long, budget: MyNodesRequestBudget): NodeAnalytics = budget.perform(key) {
            calls.incrementAndGet()
            val count = active.incrementAndGet()
            peak.updateAndGet { maxOf(it, count) }
            try {
                // Deliberately finish late after cancellation to exercise cache generation guards.
                gate?.let { withContext(NonCancellable) { it.await() } }
                if (fails) throw HttpFailure(status)
                NodeAnalytics(node = MeshNode(key, role = "repeater", lastSeen = Instant.ofEpochMilli(to).toString()),
                    timeRange = AnalyticsTimeRange(Instant.ofEpochMilli(to - 86_400_000L).toString(), Instant.ofEpochMilli(to).toString(), 1))
            } finally { active.decrementAndGet() }
        }
    }
    private fun repository(server: Server, clock: AtomicLong, session: AnalyzerSourceSession = AnalyzerSourceSession()) =
        MyNodesAnalyticsRepository("corescope|https://fixture.invalid", session, clock::get, server::fetch)
    private fun clock() = AtomicLong(1_800_000_000_000L)
    private suspend fun until(condition: () -> Boolean) = withTimeout(5000) { while (!condition()) delay(1) }

    @Test fun coldWarmAndNextBatch() = runBlocking {
        for (size in listOf(0, 1, 20, 100, 500)) {
            val server = Server()
            val clock = clock()
            val repository = repository(server, clock)
            val keys = (0 until size).map(::key)
            val first = repository.load(keys)
            assertEquals(keys, first.map { it.publicKey })
            assertEquals(minOf(size, 20), first.count { it.state == MyNodesAnalyticsState.FETCHED })
            assertEquals(maxOf(0, size - 20), first.count { it.state == MyNodesAnalyticsState.DEFERRED })
            assertEquals(minOf(size, 20), server.calls.get())
            assertTrue(server.peak.get() <= 2)
            val second = repository.load(keys)
            assertEquals(minOf(size, 20), second.count { it.state == MyNodesAnalyticsState.CACHED })
            assertEquals(minOf(size, 40), server.calls.get())
            assertTrue(second.take(minOf(size, 20)).all { it.snapshot?.evidence?.fetchedAt == clock.get() })
        }
    }

    @Test fun freshnessAndFailedRefreshPreserveOriginalEvidence() = runBlocking {
        val server = Server()
        val clock = clock()
        val repository = repository(server, clock)
        val first = checkNotNull(repository.load(listOf(key(0))).first().snapshot)
        assertEquals(MyNodesCompleteness.UNKNOWN, first.evidence.completeness)
        clock.addAndGet(900_000)
        assertEquals(MyNodesAnalyticsState.CACHED, repository.load(listOf(key(0))).first().state)
        assertEquals(1, server.calls.get())
        clock.incrementAndGet()
        server.fails = true
        val failed = repository.load(listOf(key(0))).first()
        assertEquals(MyNodesAnalyticsState.FAILED, failed.state)
        assertEquals(first.evidence.fetchedAt, failed.snapshot?.evidence?.fetchedAt)
        assertFalse(checkNotNull(failed.snapshot).isReusable(clock.get()))
        clock.addAndGet(-1_000_000)
        assertEquals(MyNodesAnalyticsState.FAILED, repository.load(listOf(key(0))).first().state)
        assertEquals(3, server.calls.get())
    }

    @Test fun joinedForceRefreshCoalescesAndCancellationIsPerConsumer() = runBlocking {
        val server = Server()
        val gate = CompletableDeferred<Unit>().also { server.gate = it }
        val repository = repository(server, clock())
        val first = async { repository.load(listOf(key(0))) }
        until { server.calls.get() == 1 }
        val second = async { repository.load(listOf(key(0).uppercase(), key(0), key(1)), forceRefresh = true) }
        until { repository.activeConsumerCount == 2 }
        first.cancelAndJoin()
        assertEquals(1, repository.activeConsumerCount)
        gate.complete(Unit)
        val rows = second.await()
        assertEquals(2, rows.size)
        assertEquals(MyNodesAnalyticsState.FETCHED, rows.first().state)
        assertEquals(MyNodesAnalyticsState.DEFERRED, rows.last().state)
        assertNull(rows.last().snapshot)
        assertEquals(1, server.calls.get())
    }

    @Test fun clearRejectsLateWritesAndAllowsNewBatch() = runBlocking {
        val server = Server()
        val gate = CompletableDeferred<Unit>().also { server.gate = it }
        val clock = clock()
        val repository = repository(server, clock)
        val first = async { repository.load(listOf(key(0))) }
        until { server.calls.get() == 1 }
        repository.clear()
        first.join()
        assertTrue(first.isCancelled)
        assertTrue(repository.cached(listOf(key(0))).isEmpty())
        server.gate = null
        clock.addAndGet(60_000)
        val rows = repository.load(listOf(key(0)))
        gate.complete(Unit)
        until { server.active.get() == 0 }
        assertEquals(MyNodesAnalyticsState.FETCHED, rows.first().state)
        assertEquals(clock.get(), repository.cached(listOf(key(0)))[key(0)]?.evidence?.fetchedAt)
    }

    @Test fun sourceInvalidationRejectsResultsAndCacheReads() = runBlocking {
        val server = Server()
        val gate = CompletableDeferred<Unit>().also { server.gate = it }
        val session = AnalyzerSourceSession()
        val repository = repository(server, clock(), session)
        val task = async { repository.load(listOf(key(0))) }
        until { server.calls.get() == 1 }
        session.invalidate()
        task.join()
        assertTrue(task.isCancelled)
        gate.complete(Unit)
        assertThrows(CancellationException::class.java) { repository.cached(listOf(key(0))) }
        assertTrue(repository(server, clock()).cached(listOf(key(0))).isEmpty())
    }

    @Test fun cancellingLastConsumerStopsOwnedWork() = runBlocking {
        val server = Server()
        val gate = CompletableDeferred<Unit>().also { server.gate = it }
        val repository = repository(server, clock())
        val task = async { repository.load(listOf(key(0))) }
        until { server.calls.get() == 1 }
        task.cancelAndJoin()
        assertEquals(0, repository.activeConsumerCount)
        gate.complete(Unit)
        until { server.active.get() == 0 }
        assertTrue(repository.cached(listOf(key(0))).isEmpty())
    }

    @Test fun rateLimitStopsSchedulingWithoutInventingData() = runBlocking {
        val server = Server().apply { fails = true; status = 429 }
        val repository = repository(server, clock())
        val rows = repository.load((0 until 100).map(::key))
        assertEquals(100, rows.size)
        assertTrue(rows.all { it.snapshot == null })
        assertTrue(rows.count { it.state == MyNodesAnalyticsState.DEFERRED } >= 98)
        assertTrue(rows.any { it.state == MyNodesAnalyticsState.RATE_LIMITED })
        assertTrue(server.calls.get() <= 2)
    }

    @Test fun mismatchedIdentityIsNeverCached() = runBlocking {
        val server = Server()
        val repository = MyNodesAnalyticsRepository("fixture", AnalyzerSourceSession()) { _, to, budget ->
            server.fetch(key(1), to, budget)
        }
        assertEquals(MyNodesAnalyticsState.FAILED, repository.load(listOf(key(0))).first().state)
        assertTrue(repository.cached(listOf(key(0))).isEmpty())
    }
}
