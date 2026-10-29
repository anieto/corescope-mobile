package org.nodescope.android.core.network

import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.nodescope.android.core.model.MeshNode
import org.nodescope.android.core.model.MeshObserver
import org.nodescope.android.core.model.ObserversResponse
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.time.Instant
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class MyNodesBaseSnapshot(val node: MeshNode, val fetchedAt: Long) {
    fun isReusable(now: Long): Boolean {
        val age = runCatching { Math.subtractExact(now, fetchedAt) }.getOrNull() ?: return false
        return age in 0..300_000L
    }
}

enum class MyNodesBaseState { FETCHED, CACHED, UNAVAILABLE, DEFERRED, FAILED, RATE_LIMITED }
data class MyNodesBaseRow(val publicKey: String, val snapshot: MyNodesBaseSnapshot?, val state: MyNodesBaseState)

enum class MyNodesCatalogCompleteness { UNKNOWN, PARTIAL, COMPLETE }
data class MyNodesObserverCatalogSnapshot(val observers: List<MeshObserver>, val fetchedAt: Long,
                                         val completeness: MyNodesCatalogCompleteness) {
    fun isReusable(now: Long): Boolean {
        val age = runCatching { Math.subtractExact(now, fetchedAt) }.getOrNull() ?: return false
        return age in 0..300_000L
    }
}
data class MyNodesObserverCatalogRow(val snapshot: MyNodesObserverCatalogSnapshot?, val state: MyNodesBaseState)

/** One shared repository per source selection. Joining refreshes share a single bounded batch. */
class MyNodesBaseRepository(
    val source: String,
    private val session: AnalyzerSourceSession,
    private val now: () -> Long = System::currentTimeMillis,
    private val fetchObservers: (suspend (MyNodesRequestBudget) -> MyNodesObserverCatalogSnapshot)? = null,
    private val fetch: suspend (String, Long, MyNodesRequestBudget) -> MeshNode?,
) {
    private val lock = Any()
    private val cache = mutableMapOf<String, MyNodesBaseSnapshot>()
    private var observerCatalog = MyNodesObserverCatalogRow(null, MyNodesBaseState.DEFERRED)
    private val attempted = mutableSetOf<String>()
    private var active: Batch? = null
    val activeConsumerCount: Int get() = synchronized(lock) { active?.waiters?.size ?: 0 }
    private data class Waiter(val keys: List<String>, val continuation: CancellableContinuation<List<MyNodesBaseRow>>, val onRow: ((MyNodesBaseRow) -> Unit)?)
    private data class Batch(val id: UUID, val job: Job, val cancellationId: UUID, val waiters: MutableMap<UUID, Waiter>)

    /** Missing records are unknown, even after a complete traversal. */
    fun observers(): MyNodesObserverCatalogRow = synchronized(lock) {
        session.checkCurrent()
        observerCatalog
    }

    fun seedObservers(snapshot: MyNodesObserverCatalogSnapshot, source: String) = synchronized(lock) {
        session.checkCurrent()
        require(source == this.source && snapshot.fetchedAt <= now())
        if (observerCatalog.snapshot?.let { it.fetchedAt < snapshot.fetchedAt } ?: true) {
            observerCatalog = MyNodesObserverCatalogRow(snapshot, MyNodesBaseState.CACHED)
        }
    }

    /** Positive catalog records only: omissions never mean absence. */
    fun seedCatalog(nodes: List<MeshNode>, fetchedAt: Long, source: String) = synchronized(lock) {
        session.checkCurrent()
        require(source == this.source && fetchedAt <= now())
        nodes.forEach { node ->
            val key = node.publicKey.lowercase(Locale.ROOT)
            if (key.length == 64 && key.all { it in '0'..'9' || it in 'a'..'f' } &&
                (cache[key]?.let { it.fetchedAt < fetchedAt } ?: true)) {
                cache[key] = MyNodesBaseSnapshot(node, fetchedAt)
            }
        }
    }

    /** Callbacks should enqueue/yield promptly; consumers still check their visit lifetime. */
    private fun publish(row: MyNodesBaseRow) {
        val callbacks = synchronized(lock) {
            if (!session.isCurrent) return
            active?.waiters?.values?.filter { row.publicKey in it.keys }?.mapNotNull { it.onRow }.orEmpty()
        }
        callbacks.forEach { it(row) }
    }

    /** Returns original timestamps, including stale snapshots for immediate display. */
    fun cached(publicKeys: List<String>): Map<String, MyNodesBaseSnapshot> = synchronized(lock) {
        session.checkCurrent()
        val selected = publicKeys.map { it.lowercase(Locale.ROOT) }.toSet()
        cache.filterKeys { it in selected }
    }

    suspend fun load(publicKeys: List<String>, forceRefresh: Boolean = false, onRow: ((MyNodesBaseRow) -> Unit)? = null): List<MyNodesBaseRow> {
        session.check()
        val keys = publicKeys.map { it.lowercase(Locale.ROOT) }.distinct()
        require(keys.all { it.length == 64 && it.all { char -> char in '0'..'9' || char in 'a'..'f' } })
        if (keys.isEmpty() && fetchObservers == null) return emptyList()
        val waiterId = UUID.randomUUID()
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { cancelWaiter(waiterId) }
            synchronized(lock) {
                if (!continuation.isActive) return@synchronized
                if (!session.isCurrent) {
                    continuation.cancel(CancellationException("The analyzer source changed."))
                    return@synchronized
                }
                val waiter = Waiter(keys, continuation, onRow)
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
        observerCatalog = MyNodesObserverCatalogRow(null, MyNodesBaseState.DEFERRED)
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

    private fun finish(id: UUID, result: Result<Map<String, MyNodesBaseRow>>) = synchronized(lock) {
        val batch = active?.takeIf { it.id == id } ?: return@synchronized
        active = null
        session.removeCancellation(batch.cancellationId)
        val checkedResult = if (session.isCurrent && batch.job.isActive) result else Result.failure(CancellationException("Obsolete dashboard batch."))
        for (waiter in batch.waiters.values) {
            checkedResult.fold(onSuccess = { rows ->
                waiter.continuation.resume(waiter.keys.map { key ->
                    rows[key] ?: MyNodesBaseRow(key, cache[key], MyNodesBaseState.DEFERRED)
                })
            }, onFailure = { waiter.continuation.resumeWithException(it) })
        }
    }

    private suspend fun runBatch(id: UUID, keys: List<String>, forceRefresh: Boolean): Map<String, MyNodesBaseRow> = coroutineScope {
        val rows = mutableMapOf<String, MyNodesBaseRow>()
        val work = synchronized(lock) {
            checkCurrent(id)
            val pending = keys.filter { key ->
                val snapshot = cache[key]
                if (!forceRefresh && snapshot != null && snapshot.isReusable(now())) {
                    rows[key] = MyNodesBaseRow(key, snapshot, MyNodesBaseState.CACHED)
                    false
                } else true
            }
            pending.filter { it !in attempted } + pending.filter { it in attempted }
        }
        rows.values.forEach(::publish)
        val budget = MyNodesRequestBudget()
        // Publish cached nodes first, then reserve at most four catalog attempts.
        // Exact node lookups consume the remainder of the same batch allowance.
        var catalogRateLimited = false
        fetchObservers?.let { fetchCatalog ->
            val previous = synchronized(lock) { observerCatalog.snapshot }
            val result = if (!forceRefresh && previous != null && previous.isReusable(now())) {
                MyNodesObserverCatalogRow(previous, MyNodesBaseState.CACHED)
            } else {
                try {
                    val snapshot = fetchCatalog(budget)
                    currentCoroutineContext().ensureActive()
                    require(snapshot.fetchedAt <= now())
                    MyNodesObserverCatalogRow(snapshot, MyNodesBaseState.FETCHED)
                } catch (error: CancellationException) { throw error
                } catch (error: Exception) {
                    currentCoroutineContext().ensureActive()
                    val state = when {
                        error is MyNodesBudgetException && error.reason == MyNodesBudgetFailure.INVALIDATED -> throw CancellationException("Invalidated batch.")
                        error is BeaconApiException.RateLimited || error is HttpFailure && error.status == 429 -> {
                            catalogRateLimited = true
                            MyNodesBaseState.RATE_LIMITED
                        }
                        error is MyNodesBudgetException && error.reason in setOf(MyNodesBudgetFailure.EXHAUSTED, MyNodesBudgetFailure.NODE_EXHAUSTED) -> MyNodesBaseState.DEFERRED
                        else -> MyNodesBaseState.FAILED
                    }
                    MyNodesObserverCatalogRow(previous, state)
                }
            }
            synchronized(lock) { checkCurrent(id); observerCatalog = result }
        }
        val results = Channel<MyNodesBaseRow>(Channel.UNLIMITED)
        var next = 0
        var running = 0
        fun enqueue() {
            if (catalogRateLimited || next >= work.size) return
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
                if (row.state != MyNodesBaseState.DEFERRED) attempted += row.publicKey
                if (row.state == MyNodesBaseState.FETCHED) cache[row.publicKey] = checkNotNull(row.snapshot)
                if (row.state == MyNodesBaseState.UNAVAILABLE) cache.remove(row.publicKey)
            }
            publish(row)
            if (row.state == MyNodesBaseState.DEFERRED || row.state == MyNodesBaseState.RATE_LIMITED) stopped = true
            if (!stopped) enqueue()
        }
        currentCoroutineContext().ensureActive()
        val deferred = synchronized(lock) {
            checkCurrent(id)
            keys.filter { it !in rows }.map { key ->
                MyNodesBaseRow(key, cache[key], MyNodesBaseState.DEFERRED).also { rows[key] = it }
            }
        }
        deferred.forEach(::publish)
        rows
    }

    private suspend fun fetchRow(key: String, previous: MyNodesBaseSnapshot?, budget: MyNodesRequestBudget): MyNodesBaseRow {
        currentCoroutineContext().ensureActive()
        val to = now()
        return try {
            val node = fetch(key, to, budget)
            currentCoroutineContext().ensureActive()
            if (node == null) return MyNodesBaseRow(key, previous, MyNodesBaseState.UNAVAILABLE)
            require(node.publicKey.lowercase(Locale.ROOT) == key)
            MyNodesBaseRow(key, MyNodesBaseSnapshot(node, now()), MyNodesBaseState.FETCHED)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            val state = when {
                error is MyNodesBudgetException && error.reason == MyNodesBudgetFailure.INVALIDATED -> throw CancellationException("Invalidated batch.")
                error is MyNodesBudgetException && error.reason in setOf(MyNodesBudgetFailure.EXHAUSTED, MyNodesBudgetFailure.NODE_EXHAUSTED) -> MyNodesBaseState.DEFERRED
                error is BeaconApiException.RateLimited || error is HttpFailure && error.status == 429 -> MyNodesBaseState.RATE_LIMITED
                else -> MyNodesBaseState.FAILED
            }
            MyNodesBaseRow(key, previous, state)
        }
    }

    @Serializable private data class NodeRecord(val node: JsonObject) {
        fun meshNode(): MeshNode {
            // The shared Android projection has one activity field. Preserve the newest
            // valid CoreScope timestamp without changing unrelated detail-screen contracts.
            val activity = listOf("last_seen", "last_heard").mapNotNull { key ->
                (node[key] as? JsonPrimitive)?.contentOrNull?.let { text ->
                    runCatching { Instant.parse(text) to text }.getOrNull()
                }
            }.maxByOrNull { it.first }?.second.orEmpty()
            return protocolJson.decodeFromJsonElement(JsonObject(node + ("last_seen" to JsonPrimitive(activity))))
        }
    }

    companion object {
        suspend fun observerPages(now: () -> Long,
                                  page: suspend (AnalyzerCursor?) -> AnalyzerPage<MeshObserver>): MyNodesObserverCatalogSnapshot {
            var cursor: AnalyzerCursor? = null
            val seen = mutableSetOf<AnalyzerCursor>()
            val records = mutableMapOf<String, MeshObserver>()
            repeat(4) {
                val result = page(cursor)
                currentCoroutineContext().ensureActive()
                result.items.forEach { records[it.id] = it }
                val next = result.nextCursor ?: return MyNodesObserverCatalogSnapshot(
                    records.values.sortedBy { it.id }, now(), MyNodesCatalogCompleteness.COMPLETE)
                require(result.items.isNotEmpty() && seen.add(next))
                cursor = next
            }
            return MyNodesObserverCatalogSnapshot(records.values.sortedBy { it.id }, now(), MyNodesCatalogCompleteness.PARTIAL)
        }

        fun coreScope(host: String, http: okhttp3.OkHttpClient, session: AnalyzerSourceSession,
                      now: () -> Long = System::currentTimeMillis): MyNodesBaseRepository {
            val normalized = normalizeHost(host)
            return MyNodesBaseRepository("corescope|https://$normalized", session, now, fetchObservers = { budget ->
                val response = protocolJson.decodeFromString<ObserversResponse>(http.myNodesBodies(budget, "observer-catalog")
                    .read(endpoint(normalized, "api", "observers")))
                MyNodesObserverCatalogSnapshot(response.observers, now(), MyNodesCatalogCompleteness.UNKNOWN)
            }) { key, _, budget ->
                try {
                    protocolJson.decodeFromString<NodeRecord>(http.myNodesBodies(budget, key)
                        .read(endpoint(normalized, "api", "nodes", key))).meshNode()
                } catch (error: HttpFailure) {
                    if (error.status == 404) null else throw error
                }
            }
        }

        fun beacon(client: BeaconApiClient, serverInfo: BeaconServerInfo,
                   now: () -> Long = System::currentTimeMillis): MyNodesBaseRepository {
            serverInfo.requireSupported()
            return MyNodesBaseRepository(client.cacheIdentifier, client.sourceSession, now, fetchObservers = { budget ->
                val reader = BeaconObserverReader(client.budgetingRequests(budget, "observer-catalog"), serverInfo)
                observerPages(now) { cursor ->
                    val page = reader.observerPage(null, cursor = cursor)
                    AnalyzerPage(page.items.map { it.meshObserver() }, page.nextCursor, page.total)
                }
            }) { key, _, budget ->
                BeaconNodeReader(client.budgetingRequests(budget, key), serverInfo).nodeSummary(key)?.meshNode()
            }
        }
    }
}
