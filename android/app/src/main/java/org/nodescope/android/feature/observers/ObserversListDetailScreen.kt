package org.nodescope.android.feature.observers

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Sensors
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import org.nodescope.android.core.design.AdaptiveListDetail
import org.nodescope.android.core.model.AnalyzerSelection
import org.nodescope.android.core.network.LiveFeedState
import org.nodescope.android.core.storage.NodeLibrary
import org.nodescope.android.core.storage.SavedKind

@Composable
fun ObserversListDetailScreen(
    model: ObserversViewModel, selection: AnalyzerSelection, feed: LiveFeedState, library: NodeLibrary,
    regionControl: @Composable () -> Unit, onClearRegion: () -> Unit,
    activeOnlyRequest: Long? = null, initialId: String? = null, onExit: (() -> Unit)? = null,
) {
    var selectedId by rememberSaveable(selection.host, initialId) { mutableStateOf(initialId) }
    fun closeDetails() {
        if (onExit != null) onExit() else selectedId = null
    }
    AdaptiveListDetail(
        selectedId = selectedId, detailTitle = "Observer details",
        emptyTitle = "Select an observer", emptyDescription = "Choose an observer to see its activity and signal data here.",
        emptyIcon = Icons.Outlined.Sensors, onCloseDetail = ::closeDetails,
        listPane = { showSelection ->
            ObserversScreen(model, selection, feed, regionControl, onClearRegion,
                onObserver = { selectedId = it.id }, activeOnlyRequest = activeOnlyRequest,
                selectedId = selectedId.takeIf { showSelection })
        },
        detailPane = { id ->
            ObserverDetailScreen(model, selection.host, id, favorite = library.isFavorite(SavedKind.OBSERVER, id),
                onFavorite = library::toggle, onViewed = library::viewed)
        },
    )
}
