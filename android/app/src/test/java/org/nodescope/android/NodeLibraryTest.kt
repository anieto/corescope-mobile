package org.nodescope.android

import org.junit.Assert.*
import org.junit.Test
import org.nodescope.android.core.model.*
import org.nodescope.android.core.storage.*

class NodeLibraryTest {
    private fun node(key: String) = MeshNode(key, "Node $key", "repeater", 30.0, -97.0, "2026-09-22T00:00:00Z")
    private val observer = MeshObserver("OBS1", "North Observer", iata = "DFW")
    private val channel = MeshChannel("#test", "#test", lastSender = "Relay")

    @Test fun favoritesOfEveryKindPersistNewestFirst() {
        var document: String? = null
        var now = 0L
        val library = NodeLibrary(null, { ++now }) { document = it }
        library.toggle(node("one"))
        library.toggle(observer)
        library.toggle(channel)
        val restored = NodeLibrary(document)
        assertEquals(listOf(SavedKind.CHANNEL, SavedKind.OBSERVER, SavedKind.NODE), restored.state.favorites.map { it.kind })
        assertTrue(restored.isFavorite(SavedKind.OBSERVER, "obs1"))
        assertEquals("DFW", restored.state.favorites[1].subtitle)
        library.toggle(observer)
        assertFalse(library.isFavorite(SavedKind.OBSERVER, "OBS1"))
    }

    @Test fun favoritesReorderWithinTheirKindOnly() {
        val library = NodeLibrary()
        listOf("a", "b", "c").forEach { library.toggle(node(it)) } // stored c, b, a
        library.toggle(observer)
        library.moveFavorite(library.state.favorites.first { it.entityId == "a" }, -1)
        assertEquals(listOf("c", "a", "b"), library.state.favorites.filter { it.kind == SavedKind.NODE }.map { it.entityId })
        assertEquals(SavedKind.OBSERVER, library.state.favorites.first().kind) // other kinds keep their slots
        library.moveFavorite(library.state.favorites.first { it.entityId == "c" }, -1) // already first: no change
        assertEquals(listOf("c", "a", "b"), library.state.favorites.filter { it.kind == SavedKind.NODE }.map { it.entityId })
    }

    @Test fun recentlyViewedIsBoundedDeduplicatedAndRemovable() {
        val library = NodeLibrary()
        repeat(25) { library.viewed(node("$it")) }
        library.viewed(node("20").copy(name = "Updated"))
        library.viewed(observer)
        library.viewedMessage("abcd1234", "Relay", "")
        assertEquals(NodeLibrary.MAX_RECENT, library.state.recent.size)
        assertEquals("Packet ABCD1234", library.state.recent.first().title)
        assertEquals("Updated", library.state.recent.first { it.entityId == "20" }.title)
        assertEquals(1, library.state.recent.count { it.entityId == "20" })
        library.removeRecent(library.state.recent.first())
        assertEquals(SavedKind.OBSERVER, library.state.recent.first().kind)
        library.clearRecent()
        assertTrue(library.state.recent.isEmpty())
    }

    @Test fun searchesAreCaseInsensitivelyUniqueAndCapped() {
        val library = NodeLibrary()
        repeat(12) { library.recordSearch("query $it") }
        library.recordSearch("  QUERY 11 ")
        library.recordSearch(" ")
        assertEquals(NodeLibrary.MAX_SEARCHES, library.state.searches.size)
        assertEquals("QUERY 11", library.state.searches.first())
        assertEquals(1, library.state.searches.count { it.equals("query 11", true) })
    }

    @Test fun legacyNodeOnlyLibraryMigrates() {
        val legacy = """{"favorites":[{"public_key":"k1","name":"Old","role":"room","last_seen":"x"}],"recent":[{"public_key":"k2","role":"repeater","last_seen":"x"}]}"""
        val library = NodeLibrary(legacy)
        assertEquals("Old", library.state.favorites.single().title)
        assertEquals(SavedKind.NODE, library.state.recent.single().kind)
        assertEquals("k2", library.node("K2")?.publicKey)
        assertTrue(NodeLibrary("not json").state.favorites.isEmpty())
    }

    @Test fun snapshotsRefreshInPlace() {
        val library = NodeLibrary()
        library.toggle(node("a"))
        library.refreshNodes(listOf(node("a").copy(name = "Renamed")))
        assertEquals("Renamed", library.state.favorites.single().title)
    }
}
