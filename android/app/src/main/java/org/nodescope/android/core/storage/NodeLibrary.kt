package org.nodescope.android.core.storage

import android.content.Context
import androidx.core.content.edit
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.nodescope.android.core.model.CoreScopeAccountSelectionState
import org.nodescope.android.core.model.CoreScopeAccountSettings
import org.nodescope.android.core.network.CoreScopeFavoriteMerge
import org.nodescope.android.core.network.normalizeHost
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
class NodeLibrary(document: String? = null, private val clock: () -> Long = System::currentTimeMillis,
    private val accountStore: AccountSelectionStore? = null, private val host: String = "",
    private val persist: (String) -> Unit = {}) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    var state by mutableStateOf(load(document))
        private set

    val favorites: List<SavedItem> get() = accountStore?.overlay(state.favorites, host).orEmpty().let {
        if (accountStore == null) state.favorites else it
    }
    fun isDeviceFavorite(item: SavedItem) = state.favorites.any { it.key.equals(item.key, true) }
    fun isAccountFavorite(item: SavedItem) = item.kind == SavedKind.NODE &&
        accountStore?.visible(host)?.favorites?.contains(item.entityId.lowercase()) == true
    fun membershipLabel(item: SavedItem): String {
        val labels = mutableListOf<String>()
        if (isDeviceFavorite(item)) labels += "Device"
        if (isAccountFavorite(item)) labels += "Account"
        if (item.kind == SavedKind.NODE && accountStore?.claimed(host)?.containsKey(item.entityId.lowercase()) == true) labels += "Claimed"
        return labels.joinToString(" · ")
    }
    fun removeAccountFavorite(item: SavedItem) { accountStore?.removeFavorite(host, item.entityId) }

    private fun load(document: String?): NodeLibraryState {
        if (document == null) return NodeLibraryState()
        runCatching { json.decodeFromString<NodeLibraryState>(document) }.getOrNull()?.let { return it }
        val legacy = runCatching { json.decodeFromString<LegacyLibrary>(document) }.getOrNull() ?: return NodeLibraryState()
        return NodeLibraryState(favorites = legacy.favorites.map(::nodeItem), recent = legacy.recent.map(::nodeItem))
    }

    fun nodeKeys(): Set<String> = state.favorites.filter { it.kind == SavedKind.NODE }.map { it.entityId.lowercase() }.toSet()

    fun applyAccountNodes(keys: Set<String>, names: Map<String, String>) {
        val seen = mutableSetOf<String>()
        val kept = state.favorites.filter { it.kind != SavedKind.NODE || (it.entityId.lowercase() in keys && seen.add(it.entityId.lowercase())) }
        val added = (keys - seen).sorted().map { key ->
            val name = names[key]?.takeIf { it.isNotBlank() }
            SavedItem(SavedKind.NODE, key, name ?: key.take(12), key, clock(),
                node = MeshNode(key, name, "unknown", lastSeen = ""))
        }
        update(state.copy(favorites = kept + added))
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
        accountStore?.refreshNodes(host, nodes)
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

    /**
     * After a Beacon reset re-matched a channel: favorites keep their position and saved date,
     * recently viewed moves to the new identity (iOS `replaceChannel(from:with:)`).
     */
    fun replaceChannel(oldId: String, channel: MeshChannel) {
        val replacement = channelItem(channel)
        val favorite = state.favorites.indexOfFirst { it.kind == SavedKind.CHANNEL && it.entityId == oldId }
        val favorites = if (favorite < 0) state.favorites else state.favorites.toMutableList().apply {
            val at = this[favorite].at
            removeAll { it.kind == SavedKind.CHANNEL && (it.entityId == oldId || it.entityId == channel.id) }
            add(minOf(favorite, size), replacement.copy(at = at))
        }
        val wasRecent = state.recent.any { it.kind == SavedKind.CHANNEL && it.entityId == oldId }
        val recent = if (!wasRecent) state.recent else (listOf(replacement.copy(at = clock())) +
            state.recent.filterNot { it.kind == SavedKind.CHANNEL && (it.entityId == oldId || it.entityId == channel.id) }).take(MAX_RECENT)
        if (favorite >= 0 || wasRecent) update(state.copy(favorites = favorites, recent = recent))
    }

    /** Any saved snapshot of a channel, keeping its Beacon identity when it isn't listed. */
    fun channel(id: String): MeshChannel? = (state.favorites + state.recent).firstNotNullOfOrNull { item ->
        item.channel?.takeIf { it.id == id }
    }

    /** Any saved snapshot of a node, used to open it when it isn't in the current results. */
    fun node(publicKey: String): MeshNode? = (favorites + state.recent).firstNotNullOfOrNull { item ->
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
        fun channelItem(channel: MeshChannel) = SavedItem(SavedKind.CHANNEL, channel.id, channel.name, channel.lastSender, channel = channel)

        fun forAnalyzer(context: Context, host: String): NodeLibrary {
            val preferences = context.applicationContext.getSharedPreferences("node-library", Context.MODE_PRIVATE)
            val key = host.lowercase()
            return NodeLibrary(preferences.getString(key, null), accountStore = AccountSelectionStore.shared(context), host = host) { preferences.edit { putString(key, it) } }
        }
    }
}


/** Separate account storage plus a read-only projection; device favorites are never rewritten. */
class AccountSelectionStore(
    private val read: (String) -> String?,
    private val write: (String, String?) -> Unit,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private var revision by mutableStateOf(0)
    private var active by mutableStateOf(read("active-accounts-v2")?.let {
        runCatching { json.decodeFromString<Map<String, Long>>(it) }.getOrNull()
    }.orEmpty())
    private var snapshots by mutableStateOf<Map<String, MeshNode>>(emptyMap())
    // Favorites rows read the active selection several times per recomposition; decode it once per save.
    private val decoded = mutableMapOf<String, CoreScopeAccountSelectionState>()
    private var claimedCache: Pair<CoreScopeAccountSettings, Map<String, String>>? = null

    fun load(origin: String, id: Long): CoreScopeAccountSelectionState? {
        val key = CoreScopeAccountSelectionState.key(origin, id)
        synchronized(decoded) { decoded[key]?.let { return it } }
        val state = read(key)?.let { json.decodeFromString<CoreScopeAccountSelectionState>(it) } ?: return null
        synchronized(decoded) { decoded[key] = state }
        return state
    }

    fun save(origin: String, id: Long, state: CoreScopeAccountSelectionState) {
        val key = CoreScopeAccountSelectionState.key(origin, id)
        write(key, json.encodeToString(state))
        synchronized(decoded) { decoded[key] = state }
        revision++
    }

    /** Claimed nodes of the visible account, by lowercase key. */
    fun claimed(host: String): Map<String, String> {
        val baseline = visible(host)?.baseline ?: return emptyMap()
        claimedCache?.let { (settings, claimed) -> if (settings === baseline) return claimed }
        val claimed = runCatching { CoreScopeFavoriteMerge.selection(baseline).claimed }.getOrDefault(emptyMap())
        claimedCache = baseline to claimed
        return claimed
    }

    fun activate(origin: String, id: Long) {
        val next = active.filterKeys { normalizeHost(it) != normalizeHost(origin) } + (origin to id)
        write("active-accounts-v2", json.encodeToString(next))
        active = next
    }

    fun deactivate(origin: String) {
        val next = active - origin
        write("active-accounts-v2", json.encodeToString(next))
        active = next
    }

    fun remove(origin: String, id: Long) {
        if (active[origin] == id) deactivate(origin)
        write(CoreScopeAccountSelectionState.key(origin, id), null)
        synchronized(decoded) { decoded.remove(CoreScopeAccountSelectionState.key(origin, id)) }
        write("$origin|$id", null)
        revision++
    }

    fun visible(host: String): CoreScopeAccountSelectionState? {
        revision // Observe saves, including pending membership edits.
        val entry = active.entries.firstOrNull { normalizeHost(it.key) == normalizeHost(host) } ?: return null
        return runCatching { load(entry.key, entry.value) }.getOrNull()
    }

    fun removeFavorite(host: String, key: String) {
        val entry = active.entries.firstOrNull { normalizeHost(it.key) == normalizeHost(host) } ?: return
        val state = load(entry.key, entry.value) ?: return
        save(entry.key, entry.value, state.copy(favorites = state.favorites - key.lowercase()))
    }

    fun refreshNodes(host: String, nodes: List<MeshNode>) {
        snapshots = snapshots + nodes.associateBy { "${normalizeHost(host)}|${it.publicKey.lowercase()}" }
    }

    fun overlay(device: List<SavedItem>, host: String): List<SavedItem> {
        val state = visible(host) ?: return device
        val claimed = claimed(host)
        val local = device.filter { it.kind == SavedKind.NODE }.map { it.entityId.lowercase() }.toSet()
        val extra = (state.favorites + claimed.keys - local).map { key ->
            val node = snapshots["${normalizeHost(host)}|$key"] ?: MeshNode(key,
                claimed[key]?.takeIf { it.isNotBlank() }, "unknown", lastSeen = "")
            NodeLibrary.nodeItem(node).copy(title = node.name?.takeIf { it.isNotBlank() } ?: key.take(12))
        }.sortedWith(compareBy<SavedItem> { it.title.lowercase() }.thenBy { it.entityId })
        return device + extra
    }

    companion object {
        private var instance: AccountSelectionStore? = null
        @Synchronized fun shared(context: Context): AccountSelectionStore {
            instance?.let { return it }
            val preferences = context.applicationContext.getSharedPreferences("corescope-favorite-baselines", Context.MODE_PRIVATE)
            return AccountSelectionStore({ preferences.getString(it, null) }, { key, value ->
                val editor = preferences.edit()
                if (value == null) editor.remove(key) else editor.putString(key, value)
                check(editor.commit()) { "Account selections could not be saved." }
            }).also { instance = it }
        }
    }
}
