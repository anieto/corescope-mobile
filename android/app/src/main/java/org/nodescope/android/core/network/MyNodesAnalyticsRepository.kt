package org.nodescope.android.core.network

import java.time.Instant
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.nodescope.android.core.model.MyNodesEvidence
import org.nodescope.android.core.model.NodeAnalytics
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class MyNodesAnalyticsSnapshot(val analytics: NodeAnalytics, val evidence: MyNodesEvidence) {
    fun isReusable(now: Long): Boolean {
        val age = runCatching { Math.subtractExact(now, evidence.fetchedAt) }.getOrNull() ?: return false
        return age in 0..900_000L
    }
}

enum class MyNodesAnalyticsState { FETCHED, CACHED, DEFERRED, FAILED, RATE_LIMITED }
data class MyNodesAnalyticsRow(val publicKey: String, val snapshot: MyNodesAnalyticsSnapshot?, val state: MyNodesAnalyticsState)

/** One shared repository per source selection. Joining refreshes share a single bounded batch. */
class MyNodesAnalyticsRepository(
    val source: String,
    private val session: AnalyzerSourceSession,
    private val now: () -> Long = System::currentTimeMillis,
    private val fetch: suspend (String, Long, MyNodesRequestBudget) -> NodeAnalytics,
) {
    private val lock = Any()
    private val cache = mutableMapOf<String, MyNodesAnalyticsSnapshot>()
    private val attempted = mutableSetOf<String>()
    private var active: Batch? = null
    val activeConsumerCount: Int get() = synchronized(lock) { active?.waiters?.size ?: 0 }
    private data class Waiter(val keys: List<String>, val continuation: CancellableContinuation<List<MyNodesAnalyticsRow>>)
    private data class Batch(val id: UUID, val job: Job, val cancellationId: UUID, val waiters: MutableMap<UUID, Waiter>)

    /** Returns original timestamps, including stale snapshots for immediate display. */
    fun cached(publicKeys: List<String>): Map<String, MyNodesAnalyticsSnapshot> = synchronized(lock) {
        session.checkCurrent()
        val selected = publicKeys.map { it.lowercase(Locale.ROOT) }.toSet()
        cache.filterKeys { it in selected }
    }

    suspend fun load(publicKeys: List<String>, forceRefresh: Boolean = false): List<MyNodesAnalyticsRow> {
        session.check()
        val keys = publicKeys.map { it.lowercase(Locale.ROOT) }.distinct()
        require(keys.all { it.length == 64 && it.all { char -> char in '0'..'9' || char in 'a'..'f' } })
        if (keys.isEmpty()) return emptyList()
        val waiterId = UUID.randomUUID()
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { cancelWaiter(waiterId) }
            synchronized(lock) {
                if (!continuation.isActive) return@synchronized
                if (!session.isCurrent) {
                    continuation.cancel(CancellationException("The analyzer source changed."))
                    return@synchronized
                }
                val waiter = Waiter(keys, continuation)
                val existing = active
                if (existing != null) {
                    existing.waiters[waiterId] = waiter
                    return@synchronized
                }
                val id = UUID.randomUUID()
                // The batch belongs to its waiters, not the first caller's coroutine.
                val job = CoroutineScope(Dispatchers.Default).launch(start = CoroutineStart.LAZY) {
                    try {
                        finish(id, Result.success(runBatch(id, keys, forceRefresh)))
                    } catch (error: Throwable) {
                        finish(id, Result.failure(error))
                    }
                }
                val cancellationId = session.registerCancellation { cancelBatch(id); job.cancel() }
                active = Batch(id, job, cancellationId, mutableMapOf(waiterId to waiter))
                job.invokeOnCompletion { error -> if (error != null) finish(id, Result.failure(error)) }
                job.start()
            }
        }
    }

    fun clear() = synchronized(lock) {
        active?.let { cancelBatch(it.id) }
        cache.clear()
        attempted.clear()
    }

    private fun cancelBatch(id: UUID) = synchronized(lock) {
        val batch = active?.takeIf { it.id == id } ?: return@synchronized
        active = null
        batch.job.cancel()
        session.removeCancellation(batch.cancellationId)
        batch.waiters.values.forEach { it.continuation.cancel() }
    }

    private fun cancelWaiter(id: UUID) = synchronized(lock) {
        val batch = active ?: return@synchronized
        batch.waiters.remove(id)
        if (batch.waiters.isEmpty()) {
            active = null
            batch.job.cancel()
            session.removeCancellation(batch.cancellationId)
        }
    }

    private fun checkCurrent(id: UUID) {
        session.checkCurrent()
        if (active?.id != id) throw CancellationException("The dashboard batch changed.")
    }

    private fun finish(id: UUID, result: Result<Map<String, MyNodesAnalyticsRow>>) = synchronized(lock) {
        val batch = active?.takeIf { it.id == id } ?: return@synchronized
        active = null
        session.removeCancellation(batch.cancellationId)
        val checkedResult = if (session.isCurrent && batch.job.isActive) result else Result.failure(CancellationException("Obsolete dashboard batch."))
        for (waiter in batch.waiters.values) {
            checkedResult.fold(onSuccess = { rows ->
                waiter.continuation.resume(waiter.keys.map { key ->
                    rows[key] ?: MyNodesAnalyticsRow(key, cache[key], MyNodesAnalyticsState.DEFERRED)
                })
            }, onFailure = { waiter.continuation.resumeWithException(it) })
        }
    }

    private suspend fun runBatch(id: UUID, keys: List<String>, forceRefresh: Boolean): Map<String, MyNodesAnalyticsRow> = coroutineScope {
        val rows = mutableMapOf<String, MyNodesAnalyticsRow>()
        val work = synchronized(lock) {
            checkCurrent(id)
            val pending = keys.filter { key ->
                val snapshot = cache[key]
                if (!forceRefresh && snapshot != null && snapshot.isReusable(now())) {
                    rows[key] = MyNodesAnalyticsRow(key, snapshot, MyNodesAnalyticsState.CACHED)
                    false
                } else true
            }
            pending.filter { it !in attempted } + pending.filter { it in attempted }
        }
        val budget = MyNodesRequestBudget()
        val results = Channel<MyNodesAnalyticsRow>(Channel.UNLIMITED)
        var next = 0
        var running = 0
        fun enqueue() {
            if (next >= work.size) return
            val key = work[next++]
            val previous = synchronized(lock) { cache[key] }
            running++
            launch { results.send(fetchRow(key, previous, budget)) }
        }
        enqueue()
        enqueue()
        var stopped = false
        while (running > 0) {
            val row = results.receive()
            running--
            currentCoroutineContext().ensureActive()
            synchronized(lock) {
                checkCurrent(id)
                rows[row.publicKey] = row
                if (row.state != MyNodesAnalyticsState.DEFERRED) attempted += row.publicKey
                if (row.state == MyNodesAnalyticsState.FETCHED) cache[row.publicKey] = checkNotNull(row.snapshot)
            }
            if (row.state == MyNodesAnalyticsState.DEFERRED || row.state == MyNodesAnalyticsState.RATE_LIMITED) stopped = true
            if (!stopped) enqueue()
        }
        currentCoroutineContext().ensureActive()
        synchronized(lock) {
            checkCurrent(id)
            keys.forEach { key -> rows.putIfAbsent(key, MyNodesAnalyticsRow(key, cache[key], MyNodesAnalyticsState.DEFERRED)) }
        }
        rows
    }

    private suspend fun fetchRow(key: String, previous: MyNodesAnalyticsSnapshot?, budget: MyNodesRequestBudget): MyNodesAnalyticsRow {
        currentCoroutineContext().ensureActive()
        val to = now()
        return try {
            val analytics = fetch(key, to, budget)
            currentCoroutineContext().ensureActive()
            require(analytics.node?.publicKey?.lowercase(Locale.ROOT) == key)
            val range = requireNotNull(analytics.timeRange)
            require(range.days == 1 && Instant.parse(range.from) < Instant.parse(range.to))
            val snapshot = MyNodesAnalyticsSnapshot(analytics, MyNodesEvidence.from(analytics.context, now(), to - 86_400_000L, to))
            MyNodesAnalyticsRow(key, snapshot, MyNodesAnalyticsState.FETCHED)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            val state = when {
                error is MyNodesBudgetException && error.reason == MyNodesBudgetFailure.INVALIDATED -> throw CancellationException("Invalidated batch.")
                error is MyNodesBudgetException && error.reason in setOf(MyNodesBudgetFailure.EXHAUSTED, MyNodesBudgetFailure.NODE_EXHAUSTED) -> MyNodesAnalyticsState.DEFERRED
                error is BeaconApiException.RateLimited || error is HttpFailure && error.status == 429 -> MyNodesAnalyticsState.RATE_LIMITED
                else -> MyNodesAnalyticsState.FAILED
            }
            MyNodesAnalyticsRow(key, previous, state)
        }
    }

    companion object {
        fun coreScope(host: String, http: okhttp3.OkHttpClient, session: AnalyzerSourceSession,
                      now: () -> Long = System::currentTimeMillis): MyNodesAnalyticsRepository {
            val normalized = normalizeHost(host)
            return MyNodesAnalyticsRepository("corescope|https://$normalized", session, now) { key, _, budget ->
                protocolJson.decodeFromString<NodeAnalytics>(http.myNodesBodies(budget, key).read(nodeAnalyticsUrl(normalized, key, 1)))
            }
        }

        fun beacon(client: BeaconApiClient, serverInfo: BeaconServerInfo,
                   now: () -> Long = System::currentTimeMillis): MyNodesAnalyticsRepository {
            val reader = BeaconNodeReader(client, serverInfo)
            return MyNodesAnalyticsRepository(client.cacheIdentifier, client.sourceSession, now) { key, to, budget ->
                val history = reader.myNodesObservationHistory(key, to - 86_400_000L, budget) ?: throw BeaconNodeUnavailableException()
                BeaconNodeAnalyticsBuilder.build(history, 1, to - 86_400_000L, to)
            }
        }
    }
}
