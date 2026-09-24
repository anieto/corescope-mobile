package org.nodescope.android.feature.channels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import org.nodescope.android.core.model.AnalyzerSelection
import org.nodescope.android.core.network.BrowseRepository
import org.nodescope.android.core.network.KeyedLoader
import org.nodescope.android.core.storage.MonitoredChannelStore

/** Shared by the Channels list and its conversations, so one packet download serves both. */
class ChannelsViewModel(repository: BrowseRepository, val monitor: MonitoredChannelStore) : ViewModel() {
    val channels = KeyedLoader(viewModelScope, 90_000) { selection: AnalyzerSelection -> repository.channels(selection) }
    /** Recent GRP_TXT/CHAN packets for decrypting locally monitored channels. */
    val packets = KeyedLoader(viewModelScope, 30_000) { selection: AnalyzerSelection -> repository.channelPackets(selection) }
    val messages = KeyedLoader(viewModelScope, 30_000) { key: Pair<String, String> -> repository.channelMessages(key.first, key.second) }
}
