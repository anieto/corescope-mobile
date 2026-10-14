package org.nodescope.android.feature.channels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.nodescope.android.core.model.AnalyzerSelection
import org.nodescope.android.core.model.ChannelMessage
import org.nodescope.android.core.model.MeshChannel
import org.nodescope.android.core.network.BeaconApiException
import org.nodescope.android.core.network.BeaconChannelUnavailableException
import org.nodescope.android.core.network.BrowseRepository
import org.nodescope.android.core.network.KeyedLoader
import org.nodescope.android.core.storage.MonitoredChannelStore

/** The newest page of a conversation, plus the replacement channel when a Beacon reset forced a re-match. */
data class ChannelConversation(val messages: List<ChannelMessage>, val total: Int? = null, val recovered: MeshChannel? = null)

/** Older pages loaded on request for one conversation ([key] = host to channel id). */
data class OlderMessages(
    val key: Pair<String, String>? = null,
    val messages: List<ChannelMessage> = emptyList(),
    val total: Int? = null,
    val loading: Boolean = false,
    val error: String? = null,
)

/** Shared by the Channels list and its conversations, so one packet download serves both. */
class ChannelsViewModel(private val repository: BrowseRepository, val monitor: MonitoredChannelStore) : ViewModel() {
    val channels = KeyedLoader(viewModelScope, 90_000) { selection: AnalyzerSelection -> repository.channels(selection) }
    /** Recent GRP_TXT/CHAN packets for decrypting locally monitored channels. */
    val packets = KeyedLoader(viewModelScope, 30_000) { selection: AnalyzerSelection -> repository.channelPackets(selection) }
    /** Snapshots of open channels, so a Beacon 404 can be re-matched by name (iOS reset recovery). */
    private val known = ConcurrentHashMap<String, MeshChannel>()
    val messages = KeyedLoader(viewModelScope, 30_000) { key: Pair<String, String> ->
        try {
            repository.channelMessages(key.first, key.second).let { ChannelConversation(it.messages, it.total) }
        } catch (e: BeaconApiException.Server) {
            val saved = known[key.second]
            if (e.status != 404 || saved == null || !saved.allowsBeaconResetRecovery) throw e
            // One forced list refresh on the same source; only a unique exact-name Public or hashtag match counts.
            val replacement = saved.beaconReplacement(repository.channels(AnalyzerSelection(key.first, null)))
                ?.takeIf { it.id != saved.id } ?: throw BeaconChannelUnavailableException()
            known[replacement.id] = replacement
            repository.channelMessages(key.first, replacement.id).let { ChannelConversation(it.messages, it.total, replacement) }
        }
    }

    private val olderState = MutableStateFlow(OlderMessages())
    val older: StateFlow<OlderMessages> = olderState.asStateFlow()

    /**
     * Loads the page before everything shown for [key] (iOS "Load older messages"). The offset is
     * what's already shown, so a newer page arriving meanwhile can repeat messages; the screen
     * de-duplicates them.
     */
    fun loadOlder(key: Pair<String, String>) {
        val shown = messages.state.value.takeIf { it.key == key }?.value ?: return
        val base = olderState.value.takeIf { it.key == key } ?: OlderMessages(key)
        if (base.loading) return
        val offset = shown.messages.size + base.messages.size
        olderState.value = base.copy(loading = true, error = null)
        viewModelScope.launch {
            olderState.value = try {
                val page = repository.channelMessages(key.first, key.second, offset)
                // An empty page means there is nothing older, whatever total was reported.
                base.copy(messages = base.messages + page.messages, total = if (page.messages.isEmpty()) offset else page.total)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                base.copy(error = e.message ?: "Unable to load older messages.")
            }.takeIf { olderState.value.key == key } ?: return@launch
        }
    }

    fun remember(channel: MeshChannel) { known[channel.id] = channel }
}
