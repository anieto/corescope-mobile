package org.nodescope.android.feature.channels

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
) {
    val channelState by model.channels.state.collectAsStateWithLifecycle()
    val packetState by model.packets.state.collectAsStateWithLifecycle()
    val monitored by model.monitor.channels.collectAsStateWithLifecycle()
    var source by rememberSaveable { mutableStateOf(ChannelSource.ALL) }
    var activity by rememberSaveable { mutableStateOf(ChannelActivity.ALL) }
    var sort by rememberSaveable { mutableStateOf(ChannelSort.RECENT) }
    var query by rememberSaveable { mutableStateOf("") }
    var searching by rememberSaveable { mutableStateOf(false) }
    var showFilters by remember { mutableStateOf(false) }
    val now = rememberNow()

    LaunchedEffect(selection) { model.channels.load(selection) }
    LaunchedEffect(selection, monitored.isNotEmpty()) { if (monitored.isNotEmpty()) model.packets.load(selection) }
    fun refresh() {
        model.channels.load(selection, force = true)
        if (monitored.isNotEmpty()) model.packets.load(selection, force = true)
    }
    LiveChannelRefresh(feed, ::refresh)

    val packets = packetState.value.takeIf { packetState.key == selection }.orEmpty()
    val summaries = remember(packets, monitored) {
        monitored.associate { it.channelName to summarize(monitoredConversation(packets, it)) }
    }
    val server = channelState.value.takeIf { channelState.key == selection }.orEmpty()
    val filters = ChannelFilters(source, activity, sort, query)
    val sections = remember(server, monitored, summaries, filters, now) { channelSections(server, monitored, summaries, filters, now) }

    if (showFilters) FilterSheet("Channel filters", regionControl, listOf(
        FilterGroup("Source", ChannelSource.entries, source, { it.title }) { source = it },
        FilterGroup("Activity", ChannelActivity.entries, activity, { it.title }) { activity = it },
        FilterGroup("Sort by", ChannelSort.entries, sort, { it.title }) { sort = it },
    ), onReset = { source = ChannelSource.ALL; activity = ChannelActivity.ALL; sort = ChannelSort.RECENT; onClearRegion() }) { showFilters = false }

    val summary = (listOf(selection.region ?: "Entire network") +
        listOfNotNull(source.takeIf { it != ChannelSource.ALL }?.title, activity.takeIf { it != ChannelActivity.ALL }?.title,
            sort.takeIf { it != ChannelSort.RECENT }?.title)).joinToString(" · ")

    PullToRefreshBox(isRefreshing = channelState.loading && server.isNotEmpty(), onRefresh = ::refresh, modifier = Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                BrowseHeader("Channels", feed.connection, "Live mesh traffic", summary,
                    filters.activeCount + (if (selection.region != null) 1 else 0), searching,
                    onFilters = { showFilters = true }, onSearch = { searching = !searching; if (!searching) query = "" }) {
                    FilledIconButton(onClick = onAdd) { Icon(Icons.Outlined.Add, "Add channel") }
                }
            }
            if (searching) item { BrowseSearchField(query, { query = it }, "Search channels") }
            if (model.monitor.recoveryNeeded) item {
                Text("Previously monitored channels couldn't be unlocked on this device. Add them again to keep listening.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            item { LoadStatus(channelState, server.isNotEmpty(), now, ::refresh) }
            if (sections.monitored.isNotEmpty()) {
                item { SectionLabel("Monitoring on this device · ${sections.monitored.size}") }
                items(sections.monitored, key = { it.id }) { ChannelCard(it, now) { onChannel(it) } }
            }
            if (sections.server.isNotEmpty()) {
                item { SectionLabel("Server monitored channels") }
                items(sections.server, key = { it.id }) { ChannelCard(it, now) { onChannel(it) } }
            }
            if (sections.isEmpty && !channelState.loading) item {
                EmptyState(if (query.isBlank()) Icons.Outlined.Tag else Icons.Outlined.SearchOff,
                    if (query.isBlank()) "No channels yet" else "No matching channels",
                    if (query.isBlank()) "Channels appear as your analyzer hears group messages. You can also add a hashtag or private channel." else "Try a different search or filter.")
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

@Composable
private fun ChannelCard(row: ChannelRow, now: Long, onClick: () -> Unit) {
    Card(onClick = onClick, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
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
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    MetricChip("${row.messageCount} message${if (row.messageCount == 1) "" else "s"}", Icons.Outlined.ChatBubbleOutline)
                    if (row.monitored) MetricChip("On this device", Icons.Outlined.Key, MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}
