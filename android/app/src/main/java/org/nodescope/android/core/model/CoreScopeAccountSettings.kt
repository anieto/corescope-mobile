package org.nodescope.android.core.model

import kotlinx.serialization.Serializable

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
data class CoreScopeAccountSelectionState(val baseline: CoreScopeAccountSettings, val favorites: Set<String>) {
    fun refresh(remote: CoreScopeAccountSettings): CoreScopeAccountSelectionState {
        val (_, selection) = org.nodescope.android.core.network.CoreScopeFavoriteMerge.document(baseline, favorites, remote)
        return CoreScopeAccountSelectionState(remote, selection.favorites.map { it.lowercase() }.toSet())
    }

    val hasPendingEdits: Boolean get() = favorites !=
        org.nodescope.android.core.network.CoreScopeFavoriteMerge.selection(baseline).favorites.map { it.lowercase() }.toSet()

    /** Keep edits staged from Favorites while an upload was in flight. */
    fun acknowledge(accepted: CoreScopeAccountSettings, sentFavorites: Set<String>): CoreScopeAccountSelectionState {
        val remote = org.nodescope.android.core.network.CoreScopeFavoriteMerge.selection(accepted)
        return CoreScopeAccountSelectionState(accepted,
            org.nodescope.android.core.network.CoreScopeFavoriteMerge.merge(sentFavorites, favorites,
                remote.favorites.map { it.lowercase() }.toSet()))
    }

    fun restore(remote: CoreScopeAccountSettings): CoreScopeAccountSelectionState =
        from(remote).let { it.copy(favorites = it.favorites + favorites) }

    companion object {
        fun key(origin: String, userID: Long) = "account-selections-v2|$origin|$userID"
        fun from(remote: CoreScopeAccountSettings) = CoreScopeAccountSelectionState(remote,
            org.nodescope.android.core.network.CoreScopeFavoriteMerge.selection(remote).favorites.map { it.lowercase() }.toSet())
    }
}
