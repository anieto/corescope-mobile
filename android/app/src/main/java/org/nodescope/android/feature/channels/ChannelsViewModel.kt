package org.nodescope.android.feature.channels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.util.concurrent.ConcurrentHashMap
import org.nodescope.android.core.model.AnalyzerSelection
import org.nodescope.android.core.model.ChannelMessage
import org.nodescope.android.core.model.MeshChannel
import org.nodescope.android.core.network.BeaconApiException
import org.nodescope.android.core.network.BeaconChannelUnavailableException
import org.nodescope.android.core.network.BrowseRepository
import org.nodescope.android.core.network.KeyedLoader
import org.nodescope.android.core.storage.MonitoredChannelStore

/** A conversation, plus the replacement channel when a Beacon reset forced a re-match. */
data class ChannelConversation(val messages: List<ChannelMessage>, val recovered: MeshChannel? = null)

/** Shared by the Channels list and its conversations, so one packet download serves both. */
class ChannelsViewModel(repository: BrowseRepository, val monitor: MonitoredChannelStore) : ViewModel() {
    val channels = KeyedLoader(viewModelScope, 90_000) { selection: AnalyzerSelection -> repository.channels(selection) }
    /** Recent GRP_TXT/CHAN packets for decrypting locally monitored channels. */
    val packets = KeyedLoader(viewModelScope, 30_000) { selection: AnalyzerSelection -> repository.channelPackets(selection) }
    /** Snapshots of open channels, so a Beacon 404 can be re-matched by name (iOS reset recovery). */
    private val known = ConcurrentHashMap<String, MeshChannel>()
    val messages = KeyedLoader(viewModelScope, 30_000) { key: Pair<String, String> ->
        try {
            ChannelConversation(repository.channelMessages(key.first, key.second))
        } catch (e: BeaconApiException.Server) {
            val saved = known[key.second]
            if (e.status != 404 || saved == null || !saved.allowsBeaconResetRecovery) throw e
            // One forced list refresh on the same source; only a unique exact-name Public or hashtag match counts.
            val replacement = saved.beaconReplacement(repository.channels(AnalyzerSelection(key.first, null)))
                ?.takeIf { it.id != saved.id } ?: throw BeaconChannelUnavailableException()
            known[replacement.id] = replacement
            ChannelConversation(repository.channelMessages(key.first, replacement.id), replacement)
        }
    }

    fun remember(channel: MeshChannel) { known[channel.id] = channel }
}
