package org.nodescope.android.core.model

import kotlinx.serialization.Serializable
import org.nodescope.android.core.network.CoreScopeFavoriteMerge

/** Preserve raw document values, including unrelated settings and future item fields. */
@Serializable
data class CoreScopeAccountSettings(
    val revision: Long,
    val generation: String,
    val doc: AccountSettingsDocument?,
    val allowlist: List<AccountAllowedKey>,
) {
    val hasSupportedDocument: Boolean get() = if (doc != null) {
        revision > 0 && generation.isNotEmpty() && doc.v == 1
    } else revision == 0L && generation.isEmpty()

    val supportsNodeFavorites: Boolean get() = supportsSet("meshcore-favorites", null)
    val supportsOwnNodes: Boolean get() = supportsSet("meshcore-my-nodes", "pubkey")

    private fun supportsSet(key: String, identityField: String?): Boolean {
        if (!hasSupportedDocument) return false
        val entry = allowlist.filter { it.key == key }.singleOrNull() ?: return false
        return entry.kind == "set" && entry.id == identityField
    }
}

@Serializable
data class AccountSettingsDocument(val v: Int, val keys: Map<String, String>)

@Serializable
data class AccountAllowedKey(val key: String, val kind: String, val id: String? = null)


/** Account edits never read or overwrite the device's favorites. */
@kotlinx.serialization.Serializable
data class CoreScopeAccountSelectionState(
    val baseline: CoreScopeAccountSettings,
    val favorites: Set<String>,
    val removedMyNodes: Set<String> = emptySet(),
) {
    val myNodeNames: Map<String, String> get() = CoreScopeFavoriteMerge.selection(baseline).myNodes - removedMyNodes
    val favoriteKeys: Set<String> get() = favorites + myNodeNames.keys

    fun removeFavorite(publicKey: String): CoreScopeAccountSelectionState {
        val key = publicKey.lowercase()
        val removed = if (key in myNodeNames) removedMyNodes + key else removedMyNodes
        return copy(favorites = favorites - key, removedMyNodes = removed)
    }

    fun refresh(remote: CoreScopeAccountSettings): CoreScopeAccountSelectionState {
        val (_, selection) = CoreScopeFavoriteMerge.document(baseline, favorites, remote)
        return CoreScopeAccountSelectionState(remote, selection.favorites.map { it.lowercase() }.toSet(),
            removedMyNodes intersect selection.myNodes.keys)
    }

    val hasPendingEdits: Boolean get() = removedMyNodes.isNotEmpty() || favorites !=
        CoreScopeFavoriteMerge.selection(baseline).favorites.map { it.lowercase() }.toSet()

    /** Keep edits staged from Favorites while an upload was in flight. */
    fun acknowledge(accepted: CoreScopeAccountSettings, sentFavorites: Set<String>): CoreScopeAccountSelectionState {
        val remote = CoreScopeFavoriteMerge.selection(accepted)
        return CoreScopeAccountSelectionState(accepted,
            CoreScopeFavoriteMerge.merge(sentFavorites, favorites,
                remote.favorites.map { it.lowercase() }.toSet()), removedMyNodes intersect remote.myNodes.keys)
    }

    fun restore(remote: CoreScopeAccountSettings): CoreScopeAccountSelectionState =
        from(remote).let { it.copy(favorites = it.favorites + favoriteKeys) }

    companion object {
        fun key(origin: String, userID: Long) = "account-selections-v2|$origin|$userID"
        fun from(remote: CoreScopeAccountSettings) = CoreScopeAccountSelectionState(remote,
            CoreScopeFavoriteMerge.selection(remote).favorites.map { it.lowercase() }.toSet())
    }
}
