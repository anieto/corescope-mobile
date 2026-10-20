package org.nodescope.android

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.OkHttpClient
import org.nodescope.android.core.network.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.nodescope.android.core.model.*
import org.nodescope.android.core.storage.*

class MyNodesCacheOwnershipTest {
    private val namespace = MyNodesNamespace("https://example.test", "corescope", "local", 1)
    private val key = "a".repeat(64)
    private val metric = MyNodesMetric(activity = MyNodesActivity.assess(1000000, null, 1000000, 1000000, true))

    @Test fun repositoriesSharePerSessionAndClearPreservesParent() = runBlocking {
        val root = Files.createTempDirectory("my-nodes-owner").toFile()
        try {
            val owner = MyNodesStorage(root)
            val parent = AnalyzerSourceSession()
            val http = OkHttpClient()
            val first = owner.coreScope("example.test", http, parent)
            assertSame(first, owner.coreScope("example.test", http, parent))
            val other = owner.coreScope("example.test", http, AnalyzerSourceSession())
            assertNotSame(first, other)
            first.base.seedObservers(MyNodesObserverCatalogSnapshot(emptyList(), System.currentTimeMillis(), MyNodesCatalogCompleteness.PARTIAL), first.base.source)
            owner.clear()
            assertTrue(parent.isCurrent)
            assertFalse(first.lifetime.isCurrent)
            assertFalse(other.lifetime.isCurrent)
            assertTrue(runCatching { first.base.observers() }.exceptionOrNull() is CancellationException)
            assertTrue(runCatching { first.analytics.cached(listOf(key)) }.exceptionOrNull() is CancellationException)
            val fresh = owner.coreScope("example.test", http, parent)
            assertNotSame(first, fresh)
            assertNull(fresh.base.observers().snapshot)
            parent.invalidate()
            assertFalse(fresh.lifetime.isCurrent)
            assertTrue(runCatching { owner.coreScope("example.test", http, parent) }.exceptionOrNull() is CancellationException)
        } finally { root.deleteRecursively() }
    }

    @Test fun clearCancelsBothRepositoryLoadsAndRejectsLateResults() = runBlocking {
        val root = Files.createTempDirectory("my-nodes-owner").toFile()
        val release = CompletableDeferred<Unit>()
        try {
            val owner = MyNodesStorage(root)
            val parent = AnalyzerSourceSession()
            val started = AtomicInteger()
            val owned = owner.repositories("fixture", parent) { lifetime ->
                MyNodesBaseRepository("fixture", lifetime) { _, _, _ ->
                    started.incrementAndGet()
                    withContext(NonCancellable) { release.await() }
                    null
                } to MyNodesAnalyticsRepository("fixture", lifetime) { _, _, _ ->
                    started.incrementAndGet()
                    withContext(NonCancellable) { release.await() }
                    throw HttpFailure(503)
                }
            }
            val base = async { owned.base.load(listOf(key)) }
            val analytics = async { owned.analytics.load(listOf(key)) }
            withTimeout(5000) { while (started.get() != 2) delay(1) }
            owner.clear()
            release.complete(Unit)
            assertTrue(runCatching { base.await() }.exceptionOrNull() is CancellationException)
            assertTrue(runCatching { analytics.await() }.exceptionOrNull() is CancellationException)
            assertTrue(parent.isCurrent)
            assertEquals(0, owned.base.activeConsumerCount)
            assertEquals(0, owned.analytics.activeConsumerCount)
        } finally { release.complete(Unit); root.deleteRecursively() }
    }

    @Test fun sharedOwnerCountsSnapshotsAndClearRejectsLateWrites() = runTest {
        val root = Files.createTempDirectory("my-nodes-cache").toFile()
        try {
            val favorites = File(root, "favorites.json").apply { writeText("keep") }
            val cache = File(root, "cache").apply { mkdirs() }
            val owner = MyNodesStorage(cache)
            val tilesStarted = CompletableDeferred<Unit>()
            val releaseTiles = CompletableDeferred<Unit>()
            val storage = CacheStorage(cache, owner, { emptyList() }) {
                tilesStarted.complete(Unit); releaseTiles.await(); null
            }
            val store = owner.snapshots
            assertSame(store, owner.snapshots)
            val visit = store.begin(namespace, setOf(key), emptySet(), now = 1000000)
            store.rendered(visit, MyNodesEntity.node(key), metric, true, 1000000)
            assertEquals(1, storage.usage().files)
            val clearing = async { storage.clear() }
            tilesStarted.await()
            // A renderer may still finish while the tile service is clearing.
            store.rendered(visit, MyNodesEntity.node(key), metric, true, 1000000)
            releaseTiles.complete(Unit)
            assertNull(clearing.await())
            store.rendered(visit, MyNodesEntity.node(key), metric, true, 1000000)
            store.end(visit)
            assertEquals(0, storage.usage().files)
            assertEquals("keep", favorites.readText())
            val next = store.begin(namespace, setOf(key), emptySet(), now = 1000000)
            assertTrue(next.baselines.isEmpty())
            store.rendered(next, MyNodesEntity.node(key), metric, true, 1000000)
            store.end(next)
            assertEquals(1, storage.usage().files)
        } finally { root.deleteRecursively() }
    }

    @Test fun unopenedCorruptSnapshotClearsEvenWhenTilesFail() = runTest {
        val cache = Files.createTempDirectory("my-nodes-cache").toFile()
        try {
            val owner = MyNodesStorage(cache)
            owner.directory.mkdirs()
            val file = File(owner.directory, "snapshots.json").apply { writeText("not-json") }
            val storage = CacheStorage(cache, owner, { emptyList() }, { "test tile failure" })
            assertEquals(1, storage.usage().files)
            assertTrue(storage.clear().orEmpty().contains("test tile failure"))
            assertFalse(file.exists())
            assertTrue(owner.snapshots.begin(namespace, setOf(key), emptySet(), now = 1000000).baselines.isEmpty())
        } finally { cache.deleteRecursively() }
    }

    @Test fun snapshotDeletionFailureIsReported() = runTest {
        val cache = Files.createTempDirectory("my-nodes-cache").toFile()
        try {
            val owner = MyNodesStorage(cache)
            val file = File(owner.directory, "snapshots.json").apply { mkdirs() }
            File(file, "child").writeText("block deletion")
            val storage = CacheStorage(cache, owner, { emptyList() }, { null })
            assertTrue(storage.clear().orEmpty().contains("My Nodes snapshots could not be cleared"))
        } finally { cache.deleteRecursively() }
    }
}
