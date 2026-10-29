package org.nodescope.android

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import org.nodescope.android.core.network.protocolJson
import org.junit.Assert.*
import org.junit.Test
import org.nodescope.android.core.model.*
import org.nodescope.android.core.storage.MyNodesSnapshotStore

class MyNodesSnapshotTest {
    @Serializable private data class IdentityCase(val components: List<String>, val expected: String)
    private val namespace = MyNodesNamespace("https://example.test:8443/base", "corescope", "local", 1)
    private val node = MyNodesEntity.node("A".repeat(64))
    private fun metric(time: Long, activity: Long) = MyNodesMetric(activity =
        MyNodesActivity.assess(activity * 1000, null, time * 1000, time * 1000, true))
    private suspend fun begin(store: MyNodesSnapshotStore, time: Long) =
        store.begin(namespace, setOf(node.value), emptySet(), now = time * 1000)
    private suspend fun seed(store: MyNodesSnapshotStore) {
        val visit = begin(store, 1000)
        store.rendered(visit, node, metric(1000, 0), true, 1000000)
        store.end(visit)
    }
    private suspend fun temporary(block: suspend (File) -> Unit) {
        val directory = Files.createTempDirectory("my-nodes").toFile()
        try { block(File(directory, "snapshots.json")) } finally { directory.deleteRecursively() }
    }

    @Test fun delayedRenderingAndWriteFailureDoNotAdvance() = runTest { temporary { file ->
        val store = MyNodesSnapshotStore(file)
        val visit = begin(store, 1000)
        store.rendered(visit, node, metric(1000, 101), true, 1002000)
        store.end(visit)
        assertTrue(begin(store, 1003).baselines.isEmpty())
        val next = begin(store, 2000)
        file.delete()
        file.mkdir()
        File(file, "child").writeText("block")
        try {
            store.rendered(next, node, metric(2000, 2000), true, 2000000)
            fail("Atomic replacement should fail")
        } catch (_: java.io.IOException) { }
        file.deleteRecursively()
        store.end(next)
        assertTrue(begin(store, 2001).baselines.isEmpty())
    } }

    @Test fun sharedWireFormatAndIdentity() = runTest { temporary { file ->
        val identities = protocolJson.decodeFromString<List<IdentityCase>>(fixture("my-nodes/identity-cases.json"))
        for (entry in identities) assertEquals(entry.expected, MyNodesIdentity.encode(entry.components))
        file.writeText(fixture("my-nodes/snapshot-v1.json"))
        val store = MyNodesSnapshotStore(file)
        val visit = begin(store, 2000)
        assertEquals(1, visit.baselines.size)
        val event = store.rendered(visit, node, metric(2000, 2000), true, 2000000)
        assertEquals(identities.last().expected, event?.id)
        store.end(visit)
        assertEquals(2000000L, begin(MyNodesSnapshotStore(file), 2001).baselines.values.first().metric.fetchedAt)
    } }

    @Test fun fixedBaselineAndStableNetEvent() = runTest { temporary { file ->
        val store = MyNodesSnapshotStore(file)
        seed(store)
        val visit = begin(store, 2000)
        val event = store.rendered(visit, node, metric(2000, 2000), true, 2000000)
        val refresh = store.rendered(visit, node, metric(2100, 2100), true, 2100000)
        assertNotNull(event)
        assertEquals(event?.id, refresh?.id)
        assertEquals(MyNodesActivityState.NOT_RECENTLY_HEARD, visit.baselines.values.first().metric.activity?.state)
        assertNull(store.rendered(visit, node, metric(3100, 2100), true, 3100000))
        store.end(visit)
        assertEquals(3100000L, begin(store, 3200).baselines.values.first().metric.fetchedAt)
    } }

    @Test fun renderedCandidateRecoversAndCacheDisplayDoesNotAdvance() = runTest { temporary { file ->
        val store = MyNodesSnapshotStore(file)
        val visit = begin(store, 1000)
        store.rendered(visit, node, metric(1000, 1000), false, 1000000)
        assertTrue(begin(MyNodesSnapshotStore(file), 1001).baselines.isEmpty())
        store.rendered(visit, node, metric(1002, 1002), true, 1002000)
        val next = begin(MyNodesSnapshotStore(file), 1003)
        assertEquals(1, next.baselines.size)
        assertEquals(1002000L, next.baselines.values.first().metric.fetchedAt)
    } }

    @Test fun olderWindowCannotReplaceNewerEvidence() = runTest { temporary { file ->
        val store = MyNodesSnapshotStore(file)
        seed(store)
        val old = begin(store, 2000)
        val new = begin(store, 2001)
        store.rendered(new, node, metric(2004, 2004), true, 2004000)
        store.rendered(old, node, metric(2003, 2003), true, 2005000)
        store.end(old)
        store.end(new)
        assertEquals(2004000L, begin(store, 2006).baselines.values.first().metric.fetchedAt)
    } }

    @Test fun removalAndClearRejectLateWrites() = runTest { temporary { file ->
        val store = MyNodesSnapshotStore(file)
        seed(store)
        val old = begin(store, 2000)
        store.begin(namespace, emptySet(), emptySet(), now = 2001000)
        val readded = begin(store, 2002)
        assertTrue(readded.baselines.isEmpty())
        store.rendered(old, node, metric(2003, 2003), true, 2003000)
        store.end(old)
        assertTrue(begin(store, 2004).baselines.isEmpty())
        store.clear()
        store.rendered(readded, node, metric(2005, 2005), true, 2005000)
        store.end(readded)
        assertFalse(file.exists())
    } }

    @Test fun namespacesExpiryAndClockRollback() = runTest { temporary { file ->
        val store = MyNodesSnapshotStore(file)
        seed(store)
        for (scope in listOf(namespace.copy(backend = "beacon"), namespace.copy(selectionScope = "account:one"),
            namespace.copy(origin = namespace.origin + "/other"), namespace.copy(policyVersion = 2))) {
            assertTrue(store.begin(scope, setOf(node.value), emptySet(), now = 2000000).baselines.isEmpty())
        }
        val expired = begin(store, 1000 + 7 * 86400 + 1)
        assertTrue(expired.baselines.isEmpty())
        assertEquals(1, expired.expiredKeys.size)
        val visit = begin(store, 2000)
        store.rendered(visit, node, metric(1999, 1999), true, 1999000)
        store.rendered(visit, node, metric(2001, 2001), true, 2001000)
        store.end(visit)
        assertEquals(1000000L, begin(store, 2002).baselines.values.first().metric.fetchedAt)
    } }

    @Test fun observerLimitAndCorruptionResetAreFeatureLocal() = runTest { temporary { file ->
        val favorites = File(file.parentFile, "favorites.json").apply { writeText("keep") }
        for (invalid in listOf("corrupt", "{\"schemaVersion\":99,\"entries\":{}}")) {
            file.writeText(invalid)
            val store = MyNodesSnapshotStore(file)
            val covering = (0 until 202).map { "%03d".format(it) }.toSet()
            val visit = store.begin(namespace, emptySet(), setOf("favorite"), covering, 1000000)
            assertEquals(2, visit.omittedObserverCount)
            for (id in listOf("000", "199", "200", "favorite")) {
                val status = MyNodesObserverStatus.assess(true, 1001000, null, 1001000, 1001000, true)
                store.rendered(visit, MyNodesEntity.observer(id), MyNodesMetric(observer = status), true, 1001000)
            }
            store.end(visit)
            assertEquals(3, store.begin(namespace, emptySet(), setOf("favorite"), covering, 1002000).baselines.size)
            store.clear()
            assertEquals("keep", favorites.readText())
        }
    } }
}
