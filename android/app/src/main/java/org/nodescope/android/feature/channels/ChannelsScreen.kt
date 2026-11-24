package org.nodescope.android.feature.channels

import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import org.nodescope.android.R
import org.nodescope.android.feature.learn.*
import org.nodescope.android.core.design.meshCardColors
import org.nodescope.android.core.network.BackendCapability
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import org.nodescope.android.core.design.*
import org.nodescope.android.core.model.AnalyzerSelection
import org.nodescope.android.core.network.LiveFeedState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChannelsScreen(
    model: ChannelsViewModel, selection: AnalyzerSelection, feed: LiveFeedState,
    regionControl: @Composable () -> Unit, onClearRegion: () -> Unit,
    onChannel: (ChannelRow) -> Unit, onAdd: () -> Unit,
    selectedId: String? = null,
    resetRequest: Int = 0,
    favoriteIds: Set<String> = emptySet(),
) {
    val channelState by model.channels.state.collectAsStateWithLifecycle()
    val packetState by model.packets.state.collectAsStateWithLifecycle()
    val stored by model.monitor.channels.collectAsStateWithLifecycle()
    var source by rememberSaveable { mutableStateOf(ChannelSource.ALL) }
    var activity by rememberSaveable { mutableStateOf(ChannelActivity.ALL) }
    var sort by rememberSaveable { mutableStateOf(ChannelSort.RECENT) }
    var query by rememberSaveable { mutableStateOf("") }
    var searching by rememberSaveable { mutableStateOf(false) }
    var showFilters by remember { mutableStateOf(false) }
    // Reselecting the tab clears the search and closes the filters (iOS).
    org.nodescope.android.core.design.OnTabReset(resetRequest) { query = ""; searching = false; showFilters = false }
    val now = rememberNow()
    // Beacon decrypts known channels on the server but exposes no payloads to decrypt here, so
    // phone-monitored channels are hidden (not removed) and never shadow a server channel.
    val beacon = LocalBeaconSource.current
    val monitored = if (beacon) emptyList() else stored

    LaunchedEffect(selection, beacon) { model.channels.load(selection) }
    LaunchedEffect(selection, monitored.isNotEmpty()) { if (!beacon && monitored.isNotEmpty()) model.packets.load(selection) }
    fun refresh() {
        model.channels.load(selection, force = true)
        if (!beacon && monitored.isNotEmpty()) model.packets.load(selection, force = true)
    }
    LiveChannelRefresh(feed, ::refresh)

    val packets = packetState.value.takeIf { packetState.key == selection }.orEmpty()
    val summaries = remember(packets, monitored) {
        monitored.associate { it.channelName to summarize(monitoredConversation(packets, it)) }
    }
    val loaded = channelState.value.takeIf { channelState.key == selection }.orEmpty()
    // With a region chosen, each preview is the latest message heard there, as its conversation
    // shows (iOS), not the server's network-wide latest.
    LaunchedEffect(selection, loaded, feed.observers) { if (!beacon) model.loadRegionalPreviews(selection, loaded, feed.observers) }
    val previews by model.regionalPreviews.collectAsStateWithLifecycle()
    val noRegionalMessages = stringResource(R.string.channels_no_regional_messages)
    val server = remember(loaded, previews, selection.region, noRegionalMessages) {
        if (selection.region == null || previews.isEmpty()) loaded else loaded.map { channel ->
            val preview = previews[channel.id] ?: return@map channel
            val message = preview.message
            channel.copy(lastMessage = message?.text ?: noRegionalMessages,
                lastSender = message?.sender, lastActivity = message?.timestamp?.toString() ?: channel.lastActivity)
        }
    }
    val filters = ChannelFilters(source, activity, sort, query)
    val sections = remember(server, monitored, summaries, filters, now) { channelSections(server, monitored, summaries, filters, now) }

    val normalizedFavoriteIds = remember(favoriteIds) { favoriteIds.mapTo(mutableSetOf()) { it.lowercase() } }
    val (favoriteMonitored, otherMonitored) = remember(sections.monitored, normalizedFavoriteIds) {
        sections.monitored.partition { it.id.lowercase() in normalizedFavoriteIds }
    }
    val (favoriteServer, otherServer) = remember(sections.server, normalizedFavoriteIds) {
        sections.server.partition { it.id.lowercase() in normalizedFavoriteIds }
    }
    val favorites = favoriteMonitored + favoriteServer

    if (showFilters) FilterSheet(stringResource(R.string.channels_channel_filters), regionControl, listOf(
        FilterGroup(stringResource(R.string.channels_source), ChannelSource.entries, source, { stringResource(it.titleRes) }) { source = it },
        FilterGroup(stringResource(R.string.channels_activity), ChannelActivity.entries, activity, { stringResource(it.titleRes) }) { activity = it },
        FilterGroup(stringResource(R.string.channels_sort_by), ChannelSort.entries, sort, { stringResource(it.titleRes) }) { sort = it },
    ), onReset = { source = ChannelSource.ALL; activity = ChannelActivity.ALL; sort = ChannelSort.RECENT; onClearRegion() }) { showFilters = false }

    val summary = (listOf(selection.region ?: stringResource(R.string.channels_entire_network_2)) +
        listOfNotNull(source.takeIf { it != ChannelSource.ALL }?.let { stringResource(it.titleRes) }, activity.takeIf { it != ChannelActivity.ALL }?.let { stringResource(it.titleRes) },
            sort.takeIf { it != ChannelSort.RECENT }?.let { stringResource(it.titleRes) })).joinToString(" · ")

    PullToRefreshBox(isRefreshing = channelState.loading && server.isNotEmpty(), onRefresh = ::refresh, modifier = Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                BrowseHeader(stringResource(R.string.channels_channels), feed.connection, stringResource(R.string.channels_live_mesh_traffic), summary,
                    filters.activeCount + (if (selection.region != null) 1 else 0), searching,
                    onFilters = { showFilters = true }, onSearch = { searching = !searching; if (!searching) query = "" }) {
                    FilledIconButton(onClick = onAdd) { Icon(Icons.Outlined.Add, stringResource(R.string.channels_add_channel)) }
                }
            }
            if (searching) item { BrowseSearchField(query, { query = it }, stringResource(R.string.channels_search_channels)) }
            if (model.monitor.recoveryNeeded) item {
                Text(stringResource(R.string.channels_previously_monitored_channels_couldn_t_be_unlocked_on),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            item { LoadStatus(channelState, server.isNotEmpty(), now, ::refresh) }
            if (beacon) item { BeaconFeatureNotice(BackendCapability.CHANNEL_DECRYPT_ON_PHONE) }
            if (favorites.isNotEmpty()) {
                item { SectionLabel(stringResource(R.string.explore_favorites)) }
                items(favorites, key = { it.id }) { ChannelCard(it, now, it.id == selectedId) { onChannel(it) } }
            }
            if (otherMonitored.isNotEmpty()) {
                item { SectionLabel(stringResource(R.string.channels_monitoring_on_this_device, otherMonitored.size)) }
                items(otherMonitored, key = { it.id }) { ChannelCard(it, now, it.id == selectedId) { onChannel(it) } }
            }
            if (otherServer.isNotEmpty()) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SectionLabel(stringResource(R.string.channels_server_monitored_channels))
                        LearnButton(GlossaryTerm.CHANNEL, Modifier.padding(top = 8.dp))
                    }
                }
                items(otherServer, key = { it.id }) { ChannelCard(it, now, it.id == selectedId) { onChannel(it) } }
            }
            // Only once a load has finished: before the first load starts nothing is "loading" yet,
            // and "No channels yet" flashed on every visit.
            if (sections.isEmpty && !channelState.loading && channelState.updatedAt != null && channelState.error == null) item {
                EmptyState(if (query.isBlank()) Icons.Outlined.Tag else Icons.Outlined.SearchOff,
                    if (query.isBlank()) stringResource(R.string.channels_no_channels_yet) else stringResource(R.string.channels_no_matching_channels),
                    if (query.isBlank()) stringResource(R.string.channels_channels_appear_as_your_analyzer_hears_group_messages) else stringResource(R.string.channels_try_a_different_search_or_filter))
            }
        }
    }
}

/**
 * One new message is heard by several observers in quick succession, so wait for a short
 * quiet period before refreshing. Packets whose type is not yet decoded may be channel traffic.
 */
@Composable
internal fun LiveChannelRefresh(feed: LiveFeedState, refresh: () -> Unit) {
    val trigger = feed.packets.firstOrNull { it.isLive && (it.payloadType == 5 || it.payloadType == null) }?.key
    var handled by remember { mutableStateOf(trigger) }
    val latestRefresh by rememberUpdatedState(refresh)
    LaunchedEffect(trigger) {
        if (trigger == null || trigger == handled) return@LaunchedEffect
        delay(1_000)
        handled = trigger
        latestRefresh()
    }
}

/** Reloads a Beacon channel shortly after the server announces a message on it, or after a gap. */
@Composable
internal fun BeaconChannelRefresh(feed: LiveFeedState, channelId: String, refresh: () -> Unit) {
    val latest = feed.beacon?.channelActivity
    // Activity from before this screen opened is already in its first load.
    val opened = remember(channelId) { latest?.sequence ?: 0 }
    var relevant by remember(channelId) { mutableStateOf(opened) }
    val latestRefresh by rememberUpdatedState(refresh)
    // Other channels' messages are skipped without cancelling a refresh already waiting.
    LaunchedEffect(latest) {
        if (latest != null && latest.sequence != relevant && latest.concerns(channelId)) relevant = latest.sequence
    }
    LaunchedEffect(relevant) {
        if (relevant == opened) return@LaunchedEffect
        // A burst (one message heard by several observers, or a busy channel) reloads once.
        delay(1_000)
        latestRefresh()
    }
}

@Composable
private fun ChannelCard(row: ChannelRow, now: Long, isSelected: Boolean, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.semantics { selected = isSelected },
        colors = meshCardColors(selected = isSelected)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(shape = MaterialTheme.shapes.medium, color = if (row.monitored) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer) {
                Icon(if (row.monitored) Icons.Outlined.Lock else Icons.Outlined.Tag, null, Modifier.padding(10.dp).size(20.dp),
                    tint = if (row.monitored) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(row.name, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    row.lastActivity?.let { Text(relativeTime(it, now), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                row.lastMessage?.let { message ->
                    Text(listOfNotNull(row.lastSender, message).joinToString(": "), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                listOfNotNull(
                    row.messageCount?.let { pluralStringResource(R.plurals.channels_message_count, it, it) },
                    if (row.monitored) stringResource(R.string.channels_on_this_device) else null,
                ).takeIf { it.isNotEmpty() }?.let { parts ->
                    Text(parts.joinToString(" · "), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
