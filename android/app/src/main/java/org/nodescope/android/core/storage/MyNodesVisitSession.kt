package org.nodescope.android.core.storage

import java.util.UUID
import kotlinx.coroutines.*
import org.nodescope.android.core.model.*
import org.nodescope.android.core.network.*

/** One foreground dashboard visit; keep across nested detail, finish on departure/background. */
class MyNodesVisitSession private constructor(
    val visit: MyNodesSnapshotStore.Visit,
    private val store: MyNodesSnapshotStore,
    private val source: AnalyzerSourceSession,
    private val lifetime: AnalyzerSourceSession,
    private val cancellationId: UUID,
) {
    /** Cancels this consumer only, leaving the repository's other waiters intact. */
    suspend fun <T> perform(operation: suspend () -> T): T = coroutineScope {
        lifetime.check()
        val task = async(start = CoroutineStart.LAZY) { operation() }
        val id = lifetime.registerCancellation { task.cancel() }
        try {
            task.start()
            task.await().also { lifetime.check() }
        } finally { lifetime.removeCancellation(id) }
    }

    suspend fun loadAnalytics(repository: MyNodesAnalyticsRepository, publicKeys: List<String>,
                              forceRefresh: Boolean = false): List<MyNodesAnalyticsRow> =
        perform { repository.load(publicKeys, forceRefresh) }

    suspend fun loadBase(repository: MyNodesBaseRepository, publicKeys: List<String>, forceRefresh: Boolean = false,
                         onRow: ((MyNodesBaseRow) -> Unit)? = null): List<MyNodesBaseRow> =
        perform { repository.load(publicKeys, forceRefresh) { if (lifetime.isCurrent) onRow?.invoke(it) } }

    suspend fun rendered(entity: MyNodesEntity, metric: MyNodesMetric, freshFetch: Boolean,
                         now: Long): MyNodesEvent? {
        lifetime.check()
        return store.rendered(visit, entity, metric, freshFetch, now).also { lifetime.check() }
    }

    suspend fun reviewed(entity: MyNodesEntity, metric: MyNodesMetric, freshFetch: Boolean,
                         now: Long): MyNodesReviewResult {
        lifetime.check()
        return store.reviewed(visit, entity, metric, freshFetch, now).also { lifetime.check() }
    }

    /** Cleanup survives cancellation of a UI scope; disk errors still propagate to its caller. */
    suspend fun finish() {
        lifetime.invalidate()
        source.removeCancellation(cancellationId)
        withContext(NonCancellable) { store.end(visit) }
    }

    companion object {
        suspend fun open(store: MyNodesSnapshotStore, source: AnalyzerSourceSession, namespace: MyNodesNamespace,
                         nodes: Set<String>, favoriteObservers: Set<String>, coveringObservers: Set<String> = emptySet(),
                         now: Long): MyNodesVisitSession {
            source.check()
            val lifetime = AnalyzerSourceSession()
            val id = source.registerCancellation { lifetime.invalidate() }
            var visit: MyNodesSnapshotStore.Visit? = null
            try {
                // Capture the handle even if the UI is cancelled during the atomic disk operation.
                withContext(NonCancellable) {
                    visit = store.begin(namespace, nodes, favoriteObservers, coveringObservers, now, lifetime)
                }
                source.check()
                lifetime.check()
                return MyNodesVisitSession(checkNotNull(visit), store, source, lifetime, id)
            } catch (error: Throwable) {
                lifetime.invalidate()
                source.removeCancellation(id)
                withContext(NonCancellable) { visit?.let { runCatching { store.end(it) } } }
                throw error
            }
        }
    }
}
