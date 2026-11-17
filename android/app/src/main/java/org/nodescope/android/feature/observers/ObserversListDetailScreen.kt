package org.nodescope.android.feature.observers

import androidx.compose.ui.res.stringResource
import org.nodescope.android.R
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Sensors
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import org.nodescope.android.core.design.AdaptiveListDetail
import org.nodescope.android.core.design.OnTabReset
import org.nodescope.android.core.model.AnalyzerSelection
import org.nodescope.android.core.network.LiveFeedState
import org.nodescope.android.core.storage.NodeLibrary
import org.nodescope.android.core.storage.SavedKind

@Composable
fun ObserversListDetailScreen(
    model: ObserversViewModel, selection: AnalyzerSelection, feed: LiveFeedState, library: NodeLibrary,
    regionControl: @Composable () -> Unit, onClearRegion: () -> Unit,
    activeOnlyRequest: Long? = null, initialId: String? = null, onExit: (() -> Unit)? = null,
    resetRequest: Int = 0,
) {
    var selectedId by rememberSaveable(selection.host, initialId) { mutableStateOf(initialId) }
    // Reselecting the Observers tab returns to the list (as on iOS).
    OnTabReset(resetRequest) { selectedId = null }
    fun closeDetails() {
        if (onExit != null) onExit() else selectedId = null
    }
    AdaptiveListDetail(
        selectedId = selectedId, detailTitle = stringResource(R.string.observers_observer_details),
        emptyTitle = stringResource(R.string.observers_select_an_observer), emptyDescription = stringResource(R.string.observers_choose_an_observer_to_see_its_activity_and),
        emptyIcon = Icons.Outlined.Sensors, onCloseDetail = ::closeDetails,
        listPane = { showSelection ->
            ObserversScreen(model, selection, feed, regionControl, onClearRegion,
                onObserver = { selectedId = it.id }, activeOnlyRequest = activeOnlyRequest,
                selectedId = selectedId.takeIf { showSelection }, resetRequest = resetRequest)
        },
        detailPane = { id ->
            ObserverDetailScreen(model, selection.host, id, feed = feed, favorite = library.isFavorite(SavedKind.OBSERVER, id),
                onFavorite = library::toggle, onViewed = library::viewed)
        },
    )
}
