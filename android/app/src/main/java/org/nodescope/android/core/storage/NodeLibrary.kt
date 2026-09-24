package org.nodescope.android.core.storage

import android.content.Context
import androidx.core.content.edit
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.nodescope.android.core.model.MeshChannel
import org.nodescope.android.core.model.MeshNode
import org.nodescope.android.core.model.MeshObserver

/** What a saved or recently viewed item refers to (iOS `FavoriteKind` / `RecentItemKind`). */
@Serializable enum class SavedKind { NODE, OBSERVER, CHANNEL, PACKET }

/** A channel message opened as a packet, kept so it can be reopened from Recently Viewed. */
@Serializable data class SavedMessage(val hash: String, val sender: String = "", val text: String = "")

/** A snapshot of the entity, so saved items open even when the analyzer no longer lists them. */
@Serializable
data class SavedItem(
    val kind: SavedKind,
    val entityId: String,
    val title: String,
    val subtitle: String? = null,
    val at: Long = 0,
    val node: MeshNode? = null,
    val observer: MeshObserver? = null,
    val channel: MeshChannel? = null,
    val message: SavedMessage? = null,
) {
    val key: String get() = "${kind.name}|$entityId"
}

@Serializable
data class NodeLibraryState(
    val version: Int = 2,
    val favorites: List<SavedItem> = emptyList(),
    val recent: List<SavedItem> = emptyList(),
    val searches: List<String> = emptyList(),
)

/** The original node-only format, migrated on first load. */
@Serializable private data class LegacyLibrary(val favorites: List<MeshNode> = emptyList(), val recent: List<MeshNode> = emptyList())

/**
 * Each analyzer owns its favorites, recently viewed items and recent searches (as iOS scopes
 * them by source); changing region never discards them. Favorites keep a user-chosen order
 * per kind; newly saved items go first, like iOS.
 */
class NodeLibrary(document: String? = null, private val clock: () -> Long = System::currentTimeMillis, private val persist: (String) -> Unit = {}) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    var state by mutableStateOf(load(document))
        private set

    private fun load(document: String?): NodeLibraryState {
        if (document == null) return NodeLibraryState()
        runCatching { json.decodeFromString<NodeLibraryState>(document) }.getOrNull()?.let { return it }
        val legacy = runCatching { json.decodeFromString<LegacyLibrary>(document) }.getOrNull() ?: return NodeLibraryState()
        return NodeLibraryState(favorites = legacy.favorites.map(::nodeItem), recent = legacy.recent.map(::nodeItem))
    }

    // region Favorites

    fun isFavorite(kind: SavedKind, id: String) = state.favorites.any { it.kind == kind && it.entityId.equals(id, ignoreCase = true) }
    fun toggle(node: MeshNode) = toggle(nodeItem(node))
    fun toggle(observer: MeshObserver) = toggle(observerItem(observer))
    fun toggle(channel: MeshChannel) = toggle(channelItem(channel))

    private fun toggle(item: SavedItem) {
        val saved = state.favorites
        update(state.copy(favorites = if (saved.any { it.key.equals(item.key, true) }) saved.filterNot { it.key.equals(item.key, true) }
            else listOf(item.copy(at = clock())) + saved))
    }

    fun removeFavorite(item: SavedItem) = update(state.copy(favorites = state.favorites.filterNot { it.key == item.key }))

    /** Moves an item up (-1) or down (+1) among favorites of its own kind. */
    fun moveFavorite(item: SavedItem, delta: Int) {
        val sameKind = state.favorites.filter { it.kind == item.kind }.toMutableList()
        val from = sameKind.indexOfFirst { it.key == item.key }
        val to = from + delta
        if (from < 0 || to !in sameKind.indices) return
        sameKind.add(to, sameKind.removeAt(from))
        val iterator = sameKind.iterator()
        update(state.copy(favorites = state.favorites.map { if (it.kind == item.kind) iterator.next() else it }))
    }

    /** Refreshes saved node snapshots from the analyzer, keeping order and when each was saved. */
    fun refreshNodes(nodes: List<MeshNode>) {
        val byKey = nodes.associateBy { it.publicKey.lowercase() }
        var changed = false
        val refreshed = state.favorites.map { item ->
            val fresh = item.node?.let { byKey[it.publicKey.lowercase()] }
            if (fresh == null || fresh == item.node) item else { changed = true; nodeItem(fresh).copy(at = item.at) }
        }
        if (changed) update(state.copy(favorites = refreshed))
    }

    // endregion
    // region Recently viewed (20 per analyzer; Explore shows 10)

    fun viewed(node: MeshNode) = record(nodeItem(node))
    fun viewed(observer: MeshObserver) = record(observerItem(observer))
    fun viewed(channel: MeshChannel) = record(channelItem(channel))
    fun viewedMessage(hash: String, sender: String, text: String) = record(SavedItem(SavedKind.PACKET, hash,
        text.ifBlank { "Packet ${hash.take(8).uppercase()}" }, sender.ifBlank { null }, message = SavedMessage(hash, sender, text)))

    private fun record(item: SavedItem) = update(state.copy(recent = (listOf(item.copy(at = clock())) +
        state.recent.filterNot { it.key.equals(item.key, true) }).take(MAX_RECENT)))

    fun removeRecent(item: SavedItem) = update(state.copy(recent = state.recent.filterNot { it.key == item.key }))
    fun clearRecent() = update(state.copy(recent = emptyList()))

    // endregion
    // region Recent searches (10 per analyzer)

    fun recordSearch(query: String) {
        val trimmed = query.trim().takeIf(String::isNotEmpty) ?: return
        update(state.copy(searches = (listOf(trimmed) + state.searches.filterNot { it.equals(trimmed, ignoreCase = true) }).take(MAX_SEARCHES)))
    }
    fun removeSearch(query: String) = update(state.copy(searches = state.searches.filterNot { it == query }))
    fun clearSearches() = update(state.copy(searches = emptyList()))

    // endregion

    /** Any saved snapshot of a node, used to open it when it isn't in the current results. */
    fun node(publicKey: String): MeshNode? = (state.favorites + state.recent).firstNotNullOfOrNull { item ->
        item.node?.takeIf { it.publicKey.equals(publicKey, ignoreCase = true) }
    }

    private fun update(next: NodeLibraryState) { persist(json.encodeToString(next)); state = next }

    companion object {
        const val MAX_RECENT = 20
        const val MAX_SEARCHES = 10

        fun nodeItem(node: MeshNode) = SavedItem(SavedKind.NODE, node.publicKey, node.name?.takeIf(String::isNotBlank) ?: "Unnamed node",
            node.role.replaceFirstChar { it.uppercase() }, node = node)
        fun observerItem(observer: MeshObserver) = SavedItem(SavedKind.OBSERVER, observer.id, observer.name?.takeIf(String::isNotBlank) ?: "Unnamed observer",
            observer.iata ?: observer.id.take(12), observer = observer)
        fun channelItem(channel: MeshChannel) = SavedItem(SavedKind.CHANNEL, channel.hash, channel.name, channel.lastSender, channel = channel)

        fun forAnalyzer(context: Context, host: String): NodeLibrary {
            val preferences = context.applicationContext.getSharedPreferences("node-library", Context.MODE_PRIVATE)
            val key = host.lowercase()
            return NodeLibrary(preferences.getString(key, null)) { preferences.edit { putString(key, it) } }
        }
    }
}
