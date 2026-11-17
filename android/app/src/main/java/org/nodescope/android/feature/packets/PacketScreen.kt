package org.nodescope.android.feature.packets

import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import org.nodescope.android.R
import org.nodescope.android.core.design.meshCardColors
import androidx.compose.animation.animateContentSize
import androidx.compose.ui.semantics.selected
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.nodescope.android.core.design.*
import org.nodescope.android.core.model.*
import org.nodescope.android.core.network.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun ConnectionBadge(connection: LiveConnection, modifier: Modifier = Modifier) {
    val connected = connection == LiveConnection.LIVE
    val label = connectionLabel(connection, stringResource(R.string.packets_connection_connected))
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(7.dp).background(statusAccent(connected), CircleShape))
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

/** Payload-type accents shared with iOS: adverts green, messages blue, trace/path orange. */
@Composable
fun packetTone(typeName: String): Color {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.3f
    return when (typeName) {
        "ADVERT" -> if (dark) HealthyGreen else Color(0xFF1E8A4C)
        "GRP_TXT", "TXT_MSG" -> if (dark) SignalBlue else Color(0xFF0063A6)
        "TRACE", "PATH" -> if (dark) ActivityAmber else Color(0xFFB45F00)
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
}

fun packetIcon(typeName: String): ImageVector = when (typeName) {
    "ADVERT" -> Icons.Outlined.CellTower
    "GRP_TXT", "TXT_MSG" -> Icons.Outlined.ChatBubbleOutline
    "TRACE", "PATH" -> Icons.Outlined.Route
    else -> Icons.Outlined.Inventory2
}

@Composable
fun PacketScreen(feed: LiveFeedState, onReconnect: () -> Unit, regionControl: @Composable () -> Unit = {}, onClearRegion: () -> Unit = {},
    onGroup: (String) -> Unit = {},
    /** The map's packets panel marks one row selected and shows its actions under it. */
    selectedId: String? = null, selectedActions: (@Composable (TransmissionGroup) -> Unit)? = null,
    /** Resuming live also ends a selection or replay (iOS "Resume Live"). */
    onResumeLive: () -> Unit = {}) {
    var filter by rememberSaveable { mutableStateOf<String?>(null) }
    var showFilters by remember { mutableStateOf(false) }
    // Unchanged transmissions keep their previous instance, so their rows skip recomposition when
    // a new packet arrives (rows are only skipped for the same instance); otherwise every visible
    // row redrew on every packet, stealing frames from the map's animation.
    val groupCache = remember { HashMap<String, TransmissionGroup>() }
    // Rows' click handlers go through this, so a new onGroup from the caller (every packet) doesn't
    // count as a changed row and redraw every row.
    val currentOnGroup by rememberUpdatedState(onGroup)
    val liveGroups = remember(feed.visiblePackets, feed.observers, filter) {
        reuseUnchanged(groupTransmissions(feed.visiblePackets, filter, feed.observers), groupCache)
    }
    // While a packet is selected (its route replaying on the map, where live routes pause too),
    // the list holds its order: rows update in place, but new packets wait behind a
    // "Show new" button instead of pushing the selected row around.
    // Paused by hand as well (the map's live traffic holds too): new packets are only counted.
    val pausedByHand = org.nodescope.android.feature.map.LiveTrafficPause.isPaused
    val paused = selectedId != null || pausedByHand
    var showNew by remember { mutableIntStateOf(0) }
    val held = remember(paused, showNew, filter) { if (paused) liveGroups else null }
    val groups = remember(held, liveGroups) {
        held?.let { snapshot -> val latest = liveGroups.associateBy { it.id }; snapshot.map { latest[it.id] ?: it } } ?: liveGroups
    }
    val waiting = remember(held, liveGroups) {
        held?.let { snapshot -> val shown = snapshot.mapTo(HashSet()) { it.id }; liveGroups.count { it.id !in shown } } ?: 0
    }
    val scroll = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val now = rememberNow()
    var shownNewest by remember { mutableStateOf<String?>(null) }
    val newPackets = !paused && groups.firstOrNull()?.id != shownNewest && scroll.firstVisibleItemIndex > 0
    LaunchedEffect(groups.firstOrNull()?.id, scroll.firstVisibleItemIndex) {
        if (scroll.firstVisibleItemIndex == 0) shownNewest = groups.firstOrNull()?.id
    }
    if (showFilters) FilterSheet(stringResource(R.string.packets_live_packet_filters), regionControl, listOf(
        FilterGroup(stringResource(R.string.packets_packet_type), listOf(null) + packetTypeNames, filter, { it ?: stringResource(R.string.packets_all_packet_types) }) { filter = it },
    ), onReset = { filter = null; onClearRegion() }) { showFilters = false }
    val filterCount = listOf(feed.selection?.region != null, filter != null).count { it }

    LazyColumn(Modifier.fillMaxSize(), state = scroll, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Card(colors = meshCardColors()) {
                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                            Box(Modifier.size(8.dp).background(statusAccent(feed.connection == LiveConnection.LIVE && !pausedByHand), CircleShape))
                            Text(if (pausedByHand) (if (waiting > 0) stringResource(R.string.packets_live_feed_paused_new, waiting) else stringResource(R.string.packets_live_feed_paused_2))
                                else connectionLabel(feed.connection, stringResource(R.string.packets_listening_for_live_traffic)),
                                style = MaterialTheme.typography.titleSmall)
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            SummaryLabel(Icons.Outlined.Public, feed.selection?.region ?: stringResource(R.string.packets_entire_network))
                            SummaryLabel(Icons.Outlined.MonitorHeart, pluralStringResource(R.plurals.packets_transmission_count, groups.size, groups.size))
                            SummaryLabel(Icons.Outlined.Visibility, pluralStringResource(R.plurals.packets_observation_count, groups.sumOf { it.observationCount }, groups.sumOf { it.observationCount }))
                        }
                    }
                    // Holds the list and the map's traffic where they are, without picking a packet.
                    IconButton(onClick = {
                        val pause = org.nodescope.android.feature.map.LiveTrafficPause
                        if (pause.isPaused) { pause.resume(); onResumeLive(); showNew++; scope.launch { scroll.animateScrollToItem(0) } } else pause.pause()
                    }) {
                        if (pausedByHand) Icon(Icons.Outlined.PlayArrow, stringResource(R.string.packets_resume_live_traffic))
                        else Icon(Icons.Outlined.Pause, stringResource(R.string.packets_pause_live_traffic))
                    }
                    IconButton(onClick = { showFilters = true }) {
                        BadgedBox(badge = { if (filterCount > 0) Badge(containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer) { Text(filterCount.toString()) } }) {
                            Icon(Icons.Outlined.FilterList, stringResource(R.string.packets_filter_live_packets_active, filterCount))
                        }
                    }
                }
            }
        }
        if (feed.selection?.region != null && !feed.observersLoaded) item {
            Text(stringResource(R.string.packets_observer_regions_unavailable_only_explicitly_matching_packets_are), style = MaterialTheme.typography.bodySmall)
        }
        if (feed.historyError) item {
            Text(stringResource(R.string.packets_recent_history_unavailable_live_packets_will_still_appear), style = MaterialTheme.typography.bodySmall)
        }
        if (newPackets) item {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                FilledTonalButton(onClick = { scope.launch { scroll.animateScrollToItem(0) } }) { Text(stringResource(R.string.packets_new_packets_back_to_latest)) }
            }
        }
        if (waiting > 0 && !pausedByHand) item {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                FilledTonalButton(onClick = { showNew++; scope.launch { scroll.animateScrollToItem(0) } }) {
                    Text(pluralStringResource(R.plurals.packets_show_new, waiting, waiting))
                }
            }
        }
        if (groups.isNotEmpty()) item { SectionLabel(if (paused) stringResource(R.string.packets_incoming_traffic_paused) else stringResource(R.string.packets_incoming_traffic)) }
        items(groups, key = { it.id }) { group ->
            TransmissionRow(group, now, selected = group.id == selectedId, actions = selectedActions.takeIf { group.id == selectedId }) { currentOnGroup(group.id) }
        }
        if (groups.isEmpty()) item {
            EmptyState(Icons.Outlined.SettingsInputAntenna, if (feed.connection == LiveConnection.LIVE) stringResource(R.string.packets_waiting_for_packets) else stringResource(R.string.packets_not_connected),
                if (filter != null) stringResource(R.string.packets_no_traffic_matches_this_filter_yet)
                else if (feed.connection == LiveConnection.LIVE) stringResource(R.string.packets_new_mesh_traffic_will_appear_here_as_the_2)
                else stringResource(R.string.packets_nodescope_will_resume_the_live_feed_when_the)) {
                if (feed.connection != LiveConnection.LIVE) TextButton(onClick = onReconnect) { Text(stringResource(R.string.packets_reconnect)) }
            }
        }
    }
}

@Composable
private fun SummaryLabel(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(icon, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun TransmissionRow(group: TransmissionGroup, now: Long, selected: Boolean = false,
    actions: (@Composable (TransmissionGroup) -> Unit)? = null, onClick: () -> Unit) {
    val tone = packetTone(group.typeName)
    Card(onClick = onClick, modifier = Modifier.semantics { this.selected = selected },
        colors = meshCardColors(),
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null) {
      // The actions under a selected row slide open instead of jolting the list.
      Column(Modifier.animateContentSize()) {
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            Box(Modifier.padding(vertical = 6.dp).width(4.dp).fillMaxHeight().background(tone, CircleShape))
            Row(Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(packetIcon(group.typeName), null, Modifier.size(24.dp), tint = tone)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp),
                        itemVerticalAlignment = Alignment.CenterVertically) {
                        Text(group.typeName, style = MaterialTheme.typography.titleSmall, color = tone)
                        if (group.hopCount > 0) CountBadge(group.hopCount.toString(), Icons.AutoMirrored.Outlined.ArrowForward, MaterialTheme.colorScheme.primary,
                            pluralStringResource(R.plurals.packets_hops_4, group.hopCount, group.hopCount))
                        if (group.observationCount > 1) CountBadge(group.observationCount.toString(), Icons.Outlined.Visibility, Color(0xFF8E6BE0),
                            pluralStringResource(R.plurals.packets_heard_times, group.observationCount, group.observationCount))
                        group.region?.let { region ->
                            Surface(color = Color(0xFF4B5BC7), shape = MaterialTheme.shapes.extraSmall) {
                                Text(region, Modifier.padding(horizontal = 6.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall, color = Color.White)
                            }
                        }
                        if (!group.isLive) Text(stringResource(R.string.packets_recent), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(group.preview, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(relativeTime(Instant.ofEpochMilli(group.latestAt), now), style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        actions?.invoke(group)
      }
    }
}

@Composable
private fun CountBadge(text: String, icon: ImageVector, color: Color, description: String) {
    Surface(color = color.copy(alpha = 0.12f), shape = CircleShape, modifier = Modifier.semantics { contentDescription = description }) {
        Row(Modifier.padding(horizontal = 6.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            Icon(icon, null, Modifier.size(12.dp), tint = color)
            Text(text, style = MaterialTheme.typography.labelSmall, color = color)
        }
    }
}
