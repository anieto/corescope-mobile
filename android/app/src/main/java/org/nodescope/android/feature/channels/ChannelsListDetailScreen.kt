package org.nodescope.android.feature.channels

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.nodescope.android.core.design.AdaptiveListDetail
import org.nodescope.android.core.model.AnalyzerSelection
import org.nodescope.android.core.model.MeshChannel
import org.nodescope.android.core.network.LiveFeedState
import org.nodescope.android.core.storage.NodeLibrary
import org.nodescope.android.core.storage.SavedKind

@Composable
fun ChannelsListDetailScreen(
    model: ChannelsViewModel, selection: AnalyzerSelection, feed: LiveFeedState, library: NodeLibrary,
    regionControl: @Composable () -> Unit, onClearRegion: () -> Unit, onAdd: () -> Unit,
    packetContent: @Composable (hash: String, sender: String, text: String) -> Unit,
    initialId: String? = null, initialName: String = "", onExit: (() -> Unit)? = null,
) {
    var selectedId by rememberSaveable(selection.host, initialId) { mutableStateOf(initialId) }
    var selectedName by rememberSaveable(selection.host, initialId) { mutableStateOf(initialName) }
    // A packet is a child of this conversation, even while the window folds/resizes.
    var packet by rememberSaveable(selection.host, selection.region, selectedId) { mutableStateOf<List<String>?>(null) }
    val contentStates = rememberSaveableStateHolder()
    val channels by model.channels.state.collectAsStateWithLifecycle()
    fun closeDetails() {
        if (packet != null) packet = null
        else if (onExit != null) onExit() else selectedId = null
    }
    AdaptiveListDetail(
        selectedId = selectedId, detailTitle = if (packet != null) "Packet" else selectedName,
        detailBackLabel = if (packet != null) "Back to channel" else null,
        emptyTitle = "Select a channel", emptyDescription = "Choose a channel to read its messages here.",
        emptyIcon = Icons.Outlined.Forum, onCloseDetail = ::closeDetails,
        listPane = { showSelection ->
            ChannelsScreen(model, selection, feed, regionControl, onClearRegion,
                onChannel = { packet = null; selectedId = it.id; selectedName = it.name }, onAdd = onAdd,
                selectedId = selectedId.takeIf { showSelection })
        },
        detailPane = { id ->
            val known = channels.value.takeIf { channels.key == selection }?.firstOrNull { it.hash == id }
                ?: MeshChannel(id, selectedName)
            LaunchedEffect(id) { library.viewed(known) }
            val shownPacket = packet
            if (shownPacket == null) {
                contentStates.SaveableStateProvider("conversation:$id") {
                    ChannelDetailScreen(model, id, selectedName, selection, feed, onRemoved = ::closeDetails,
                        onPacket = { hash, sender, text -> packet = listOf(hash, sender, text) },
                        favorite = library.isFavorite(SavedKind.CHANNEL, id), onFavorite = { library.toggle(known) })
                }
            } else {
                // Separate saveable keys keep packet scrolling from replacing the conversation position.
                contentStates.SaveableStateProvider("packet:$id:${shownPacket[0]}") {
                    packetContent(shownPacket[0], shownPacket[1], shownPacket[2])
                }
            }
        },
    )
}
