package org.nodescope.android.core.storage

import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import org.nodescope.android.core.network.*

/** Disposable repository generation; invalidating it never disconnects other source consumers. */
class MyNodesRepositories internal constructor(
    private val parent: AnalyzerSourceSession,
    val lifetime: AnalyzerSourceSession,
    val base: MyNodesBaseRepository,
    val analytics: MyNodesAnalyticsRepository,
) {
    private val cancellationId = parent.registerCancellation { lifetime.invalidate() }
    fun invalidate() {
        lifetime.invalidate()
        parent.removeCancellation(cancellationId)
    }
}

/** Application-owned instance shared by every window and the storage settings screen. */
class MyNodesStorage(cacheDirectory: File) {
    val directory = File(cacheDirectory, "MyNodesSnapshots")
    // Construction performs no I/O; recovery happens under the store's IO dispatcher/mutex.
    val snapshots = MyNodesSnapshotStore(File(directory, "snapshots.json"))
    private val lock = Any()
    private val clearMutex = Mutex()
    private var clearing = false
    private data class RepositoryKey(val source: String, val sessionId: UUID)
    private val repositories = mutableMapOf<RepositoryKey, MyNodesRepositories>()

    /** Factory implementations must bind both repositories to the supplied disposable lifetime. */
    internal fun repositories(source: String, session: AnalyzerSourceSession,
                              make: (AnalyzerSourceSession) -> Pair<MyNodesBaseRepository, MyNodesAnalyticsRepository>): MyNodesRepositories = synchronized(lock) {
        session.checkCurrent()
        if (clearing) throw CancellationException("My Nodes cache is clearing.")
        repositories.entries.removeAll { (_, value) ->
            (!value.lifetime.isCurrent).also { if (it) value.invalidate() }
        }
        val key = RepositoryKey(source, session.id)
        repositories[key]?.let { return@synchronized it }
        val lifetime = AnalyzerSourceSession()
        try {
            val (base, analytics) = make(lifetime)
            require(base.source == source && analytics.source == source)
            val owned = MyNodesRepositories(session, lifetime, base, analytics)
            lifetime.checkCurrent()
            repositories[key] = owned
            owned
        } catch (error: Throwable) {
            lifetime.invalidate()
            throw error
        }
    }

    fun coreScope(host: String, http: OkHttpClient, session: AnalyzerSourceSession): MyNodesRepositories {
        val normalized = normalizeHost(host)
        return repositories("corescope|https://$normalized", session) { lifetime ->
            MyNodesBaseRepository.coreScope(normalized, http, lifetime) to
                MyNodesAnalyticsRepository.coreScope(normalized, http, lifetime)
        }
    }

    fun beacon(client: BeaconApiClient, serverInfo: BeaconServerInfo): MyNodesRepositories {
        serverInfo.requireSupported()
        return repositories(client.cacheIdentifier, client.sourceSession) { lifetime ->
            val scoped = client.replacingSourceSession(lifetime)
            MyNodesBaseRepository.beacon(scoped, serverInfo) to MyNodesAnalyticsRepository.beacon(scoped, serverInfo)
        }
    }

    suspend fun clear() = withContext(NonCancellable) {
        clearMutex.withLock {
            val owned = synchronized(lock) {
                clearing = true
                repositories.values.toList().also { values ->
                    repositories.clear()
                    values.forEach { it.invalidate() }
                }
            }
            try {
                owned.forEach { it.base.clear(); it.analytics.clear() }
                snapshots.clear()
            } finally {
                synchronized(lock) { clearing = false }
            }
        }
    }
}
