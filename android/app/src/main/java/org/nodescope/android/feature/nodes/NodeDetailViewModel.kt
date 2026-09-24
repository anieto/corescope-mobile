package org.nodescope.android.feature.nodes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import org.nodescope.android.core.network.BrowseRepository
import org.nodescope.android.core.network.KeyedLoader
import org.nodescope.android.core.network.ResponseCache
import org.nodescope.android.core.network.Loaded

/** Health, reach and paths load independently, so one failing endpoint leaves the others usable. */
class NodeDetailViewModel(repository: BrowseRepository) : ViewModel() {
    val health = KeyedLoader(viewModelScope, 60_000, restore = { key: Pair<String, String> -> repository.saved(ResponseCache.DETAIL_LIFETIME) { nodeHealth(key.first, key.second) } }) { key -> repository.nodeHealth(key.first, key.second) }
    val reach = KeyedLoader(viewModelScope, 5 * 60_000, restore = { key: Pair<String, String> -> repository.saved(ResponseCache.DETAIL_LIFETIME) { nodeReach(key.first, key.second) } }) { key -> repository.nodeReach(key.first, key.second) }
    val paths = KeyedLoader(viewModelScope, 5 * 60_000, restore = { key: Pair<String, String> -> repository.saved(ResponseCache.DETAIL_LIFETIME) { nodePaths(key.first, key.second) } }) { key -> repository.nodePaths(key.first, key.second) }

    fun load(host: String, publicKey: String, force: Boolean = false) {
        val key = host to publicKey
        health.load(key, force)
        reach.load(key, force)
        paths.load(key, force)
    }
}

/** One status line for three sections: an error only when nothing could be loaded. */
fun combinedStatus(sections: List<Loaded<*, *>>): Loaded<Unit, Unit> {
    val anyValue = sections.any { it.value != null }
    return Loaded(
        key = Unit,
        value = if (anyValue) Unit else null,
        loading = sections.any { it.loading },
        error = if (!anyValue && sections.none { it.loading }) sections.firstNotNullOfOrNull { it.error } else null,
        updatedAt = sections.mapNotNull { it.updatedAt }.minOrNull(),
    )
}
