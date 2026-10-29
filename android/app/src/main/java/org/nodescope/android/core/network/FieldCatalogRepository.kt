package org.nodescope.android.core.network

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.CancellationException
import org.nodescope.android.core.model.FieldRequestBudget
import org.nodescope.android.core.model.MeshNode
import org.nodescope.android.core.model.MeshObserver

data class FieldCatalogValue<T>(val value: T, val savedAtMillis: Long)

/**
 * Single catalog owner for Field Tools. It reuses BrowseRepository's persistent response cache,
 * accepts snapshots already loaded by another screen, and coalesces refreshes by entity kind.
 */
class FieldCatalogRepository(
    private val upstream: BrowseRepository,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private data class Entry<T>(val host: String, val value: T, val savedAtMillis: Long)

    private val nodeMutex = Mutex()
    private val observerMutex = Mutex()
    private var nodes: Entry<List<MeshNode>>? = null
    private var observers: Entry<List<MeshObserver>>? = null

    suspend fun nodes(host: String, forceRefresh: Boolean = false): FieldCatalogValue<List<MeshNode>> =
        nodeMutex.withLock {
            val normalizedHost = normalizeHost(host)
            var current = nodes?.takeIf { it.host == normalizedHost }
            if (current == null) {
                current = upstream.saved(FieldRequestBudget.STALE_CATALOG_LIFETIME_MILLIS) {
                    allNodes(normalizedHost)
                }?.let { Entry(normalizedHost, it.value, it.savedAt) }
                nodes = current
            }
            if (!forceRefresh && current != null &&
                clock() - current.savedAtMillis < FieldRequestBudget.AUTOMATIC_CATALOG_REFRESH_INTERVAL_MILLIS
            ) return@withLock current.catalogValue()

            try {
                Entry(normalizedHost, upstream.allNodes(normalizedHost), clock()).also { nodes = it }.catalogValue()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                current?.catalogValue() ?: throw error
            }
        }

    suspend fun observers(host: String, forceRefresh: Boolean = false): FieldCatalogValue<List<MeshObserver>> =
        observerMutex.withLock {
            val normalizedHost = normalizeHost(host)
            var current = observers?.takeIf { it.host == normalizedHost }
            if (current == null) {
                current = upstream.saved(FieldRequestBudget.STALE_CATALOG_LIFETIME_MILLIS) {
                    observers(normalizedHost)
                }?.let { Entry(normalizedHost, it.value, it.savedAt) }
                observers = current
            }
            if (!forceRefresh && current != null &&
                clock() - current.savedAtMillis < FieldRequestBudget.AUTOMATIC_CATALOG_REFRESH_INTERVAL_MILLIS
            ) return@withLock current.catalogValue()

            try {
                Entry(normalizedHost, upstream.observers(normalizedHost), clock()).also { observers = it }.catalogValue()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                current?.catalogValue() ?: throw error
            }
        }

    suspend fun seedNodes(host: String, value: List<MeshNode>, savedAtMillis: Long = clock()) {
        nodeMutex.withLock { nodes = Entry(normalizeHost(host), value, savedAtMillis) }
    }

    suspend fun seedObservers(host: String, value: List<MeshObserver>, savedAtMillis: Long = clock()) {
        observerMutex.withLock { observers = Entry(normalizeHost(host), value, savedAtMillis) }
    }

    private fun <T> Entry<T>.catalogValue() = FieldCatalogValue(value, savedAtMillis)
}
