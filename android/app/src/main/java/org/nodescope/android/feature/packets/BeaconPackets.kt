package org.nodescope.android.feature.packets

import org.nodescope.android.core.design.UiText
import org.nodescope.android.core.network.analyzerErrorText

import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import org.nodescope.android.R
import org.nodescope.android.feature.learn.*
import org.nodescope.android.core.design.meshCardColors
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.CancellationException
import org.nodescope.android.core.design.*
import org.nodescope.android.core.model.MeshNode
import org.nodescope.android.core.model.PacketDetail
import org.nodescope.android.core.model.parseInstant
import org.nodescope.android.core.model.payloadTypeName
import org.nodescope.android.core.network.*

// iOS `BeaconPacketSidebar` / `BeaconPacketSummary`: a Beacon source browses server snapshots
// per (region, packet type) instead of grouping a live packet stream.

private val payloadTypeCodes = (0..15).associateBy { payloadTypeName(it) }.filterKeys { it in packetTypeNames }

/** The snapshot row for [hash] in any active filter (the most observed one). */
fun LiveFeedState.beaconRow(hash: String): BeaconLiveRecovery.Row? =
    beacon?.browseSnapshots?.values?.flatMap { it.rows }?.filter { it.hash == hash }?.maxByOrNull { it.observationCount }

@Composable
fun BeaconPacketScreen(feed: LiveFeedState, host: String, regionControl: @Composable () -> Unit = {}, onClearRegion: () -> Unit = {},
    selectedHash: String? = null,
    /** The map's packets panel shows these under the selected row (Replay status, Details), as for CoreScope. */
    selectedActions: (@Composable (hash: String) -> Unit)? = null,
    /** Resuming live also ends a selection or replay (iOS "Resume Live"). */
    onResumeLive: () -> Unit = {},
    onPacket: (String) -> Unit) {
    var filterType by rememberSaveable { mutableStateOf<String?>(null) }
    var showFilters by remember { mutableStateOf(false) }
    val region = feed.selection?.region?.uppercase()
    val filter = LiveBrowseFilter(region, filterType?.let { payloadTypeCodes[it] })
    // Each filter is its own server subscription and recovered history, as on iOS.
    LiveFeedDemandEffect(host, LiveFeedDemand(LiveFeedSubscription(setOfNotNull(filter.region), setOfNotNull(filter.payloadType))))
    val liveSnapshot = feed.beacon?.browseSnapshots?.get(filter)
    // Paused by hand or with a packet selected (its route replaying), the rows stay as they were;
    // resuming shows the latest again (iOS).
    val paused = org.nodescope.android.feature.map.LiveTrafficPause.isPaused
    val holding = paused || selectedHash != null
    var held by remember { mutableStateOf<org.nodescope.android.core.network.LiveBrowseSnapshot?>(null) }
    LaunchedEffect(holding) { held = if (holding) held ?: liveSnapshot else null }
    val snapshot = if (holding) held ?: liveSnapshot else liveSnapshot
    val rows = snapshot?.rows.orEmpty()
    val now = rememberNow()
    val live = feed.connection == LiveConnection.LIVE

    if (showFilters) FilterSheet(stringResource(R.string.packets_live_packet_filters), regionControl, listOf(
        FilterGroup(stringResource(R.string.packets_packet_type), listOf(null) + packetTypeNames, filterType, { it ?: stringResource(R.string.packets_all_packet_types) }) { filterType = it },
    ), onReset = { filterType = null; onClearRegion() }) { showFilters = false }
    val filterCount = listOf(region != null, filterType != null).count { it }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Card(colors = meshCardColors()) {
                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                            Box(Modifier.size(8.dp).background(statusAccent(live && !paused), CircleShape))
                            Text(if (paused) stringResource(R.string.packets_live_feed_paused) else connectionLabel(feed.connection, stringResource(R.string.packets_listening_for_live_traffic)),
                                style = MaterialTheme.typography.titleSmall)
                        }
                        Text(listOf(region ?: stringResource(R.string.packets_entire_network), pluralStringResource(R.plurals.packets_transmission_count, rows.size, rows.size), pluralStringResource(R.plurals.packets_observation_count, rows.sumOf { it.observationCount }, rows.sumOf { it.observationCount }))
                            .joinToString(" · "), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = {
                        val pause = org.nodescope.android.feature.map.LiveTrafficPause
                        if (pause.isPaused) { pause.resume(); onResumeLive() } else pause.pause()
                    }) {
                        if (paused) Icon(Icons.Outlined.PlayArrow, stringResource(R.string.packets_resume_live_traffic)) else Icon(Icons.Outlined.Pause, stringResource(R.string.packets_pause_live_traffic))
                    }
                    IconButton(onClick = { showFilters = true }) {
                        BadgedBox(badge = { if (filterCount > 0) Badge { Text(filterCount.toString()) } }) {
                            Icon(Icons.Outlined.FilterList, stringResource(R.string.packets_filter_live_packets_active, filterCount))
                        }
                    }
                }
            }
        }
        feed.beacon?.recoveryErrors?.get(filter)?.let { error ->
            item {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(Icons.Outlined.WarningAmber, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
                        Text(stringResource(R.string.packets_history_refresh_failed), style = MaterialTheme.typography.bodyMedium)
                    }
                    Text(error.asString(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (snapshot?.possiblyIncomplete == true) item {
            Text(stringResource(R.string.packets_recent_history_may_be_incomplete), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (rows.isNotEmpty()) item { SectionLabel(stringResource(R.string.packets_recent_packets)) }
        items(rows, key = { it.hash }) { row ->
            BeaconPacketRow(row, now, row.hash == selectedHash, selectedActions.takeIf { row.hash == selectedHash }) { onPacket(row.hash) }
        }
        if (rows.isEmpty()) item {
            EmptyState(Icons.Outlined.SettingsInputAntenna, if (live) stringResource(R.string.packets_waiting_for_packets) else stringResource(R.string.packets_not_connected),
                if (live) stringResource(R.string.packets_new_mesh_traffic_will_appear_here_as_the)
                else stringResource(R.string.packets_nodescope_will_resume_the_live_feed_when_the))
        }
    }
}

@Composable
private fun BeaconPacketRow(row: BeaconLiveRecovery.Row, now: Long, selected: Boolean,
    actions: (@Composable (String) -> Unit)? = null, onClick: () -> Unit) {
    val typeName = payloadTypeName(row.payloadType)
    val tone = packetTone(typeName)
    Card(onClick = onClick, modifier = Modifier.semantics { this.selected = selected },
        colors = meshCardColors(),
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null) {
      // The actions under a selected row slide open instead of jolting the list.
      Column(Modifier.animateContentSize()) {
        Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(packetIcon(typeName), null, Modifier.size(24.dp), tint = tone)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(typeName, style = MaterialTheme.typography.titleSmall, color = tone)
                    if (row.observationCount > 1) MetricChip(row.observationCount.toString(), Icons.Outlined.Visibility)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(row.hash, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, fontFamily = MonaspaceNeon,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(relativeTime(Instant.ofEpochMilli(row.lastHeard), now), style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        actions?.invoke(row.hash)
      }
    }
}

/** iOS `BeaconPacketSummary`: what the snapshot knows, then the server's observations and confirmed routes. */
@Composable
fun BeaconPacketDetailScreen(feed: LiveFeedState, browse: BrowseRepository?, host: String, hash: String, nodes: List<MeshNode>,
    onReplay: (routes: List<RouteOption>, selected: Int, detail: PacketDetail?) -> Unit) {
    val row = feed.beaconRow(hash)
    var detail by remember(hash) { mutableStateOf<PacketDetail?>(null) }
    var loading by remember(hash) { mutableStateOf(false) }
    var error by remember(hash) { mutableStateOf<UiText?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    // Every route by default, as replays start (iOS); the picker narrows it to one.
    var selectedRoute by rememberSaveable(hash) { mutableIntStateOf(org.nodescope.android.feature.map.ALL_ROUTES) }
    LaunchedEffect(hash, retry) {
        val repository = browse ?: return@LaunchedEffect
        loading = true
        error = null
        selectedRoute = org.nodescope.android.feature.map.ALL_ROUTES
        try {
            detail = repository.packetDetail(host, hash)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.analyzerErrorText(R.string.packets_beacon_load_error)
        } finally {
            loading = false
        }
    }
    val current = detail
    val routes = remember(current) {
        routeOptions(current?.observations.orEmpty().map { observation ->
            HeardPath(observation.resolvedPath.orEmpty(), observation.observerName?.let { RouteHearing(it, observation.observerIata, observation.snr, observation.rssi) })
        })
    }
    val nodesByKey = remember(nodes) { nodes.associateBy { it.publicKey.lowercase() } }
    val typeName = payloadTypeName(row?.payloadType ?: current?.packet?.payloadType)
    val dates = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).withZone(ZoneId.systemDefault())
    val lastHeard = listOfNotNull(row?.lastHeard, parseInstant(current?.packet?.timestamp)?.toEpochMilli()).maxOrNull()

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        error?.let { message ->
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(message.asString(), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = { retry++ }) { Text(stringResource(R.string.retry)) }
                }
            }
        }
        item {
            DetailSection(stringResource(R.string.packets_packet)) {
                DetailRow(stringResource(R.string.packets_type), typeName)
                DetailRow(stringResource(R.string.packets_hash), hash, monospace = true)
                DetailRow(stringResource(R.string.packets_observations), maxOf(current?.observationCount ?: 0, row?.observationCount ?: 0).toString(), learn = GlossaryTerm.OBSERVATION)
            }
        }
        current?.decryptedMessage?.let { message ->
            item {
                DetailSection(stringResource(R.string.packets_message)) {
                    message.sender?.takeIf(String::isNotBlank)?.let { DetailRow(stringResource(R.string.packets_sender), it) }
                    androidx.compose.foundation.text.selection.SelectionContainer {
                        Text(message.content, style = MaterialTheme.typography.bodyLarge)
                    }
                    message.sentAt?.let { DetailRow(stringResource(R.string.packets_sent), dates.format(Instant.ofEpochMilli(it))) }
                }
            }
        } ?: run {
            if (current != null && (row?.payloadType ?: current.packet.payloadType) == 5) item {
                DetailSection(stringResource(R.string.packets_message)) {
                    Text(stringResource(R.string.packets_this_server_doesn_t_have_this_channel_s),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item {
            DetailSection(stringResource(R.string.packets_heard)) {
                DetailRow(stringResource(R.string.packets_first_seen), row?.firstSeen?.let { dates.format(Instant.ofEpochMilli(it)) } ?: stringResource(R.string.packets_unknown))
                DetailRow(stringResource(R.string.packets_last_heard), lastHeard?.let { dates.format(Instant.ofEpochMilli(it)) } ?: stringResource(R.string.packets_unknown), learn = GlossaryTerm.LAST_HEARD)
            }
        }
        if (current != null) {
            item {
                DetailSection(stringResource(R.string.packets_observations)) {
                    current.observations.forEachIndexed { index, observation ->
                        if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(observation.observerName ?: stringResource(R.string.packets_unknown_observer), style = MaterialTheme.typography.titleSmall)
                            Text(listOfNotNull(observation.observerIata,
                                parseInstant(observation.timestamp)?.let(dates::format),
                                observation.snr?.let { "%.1f dB".format(it) }, observation.rssi?.let { "%.1f dBm".format(it) },
                            ).joinToString(" · "), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            item {
                DetailSection(stringResource(R.string.packets_route), GlossaryTerm.ROUTE) {
                    if (routes.isEmpty()) {
                        Text(stringResource(R.string.packets_no_fully_resolved_route_is_available_uncertain_hops),
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        val index = singleRoute(selectedRoute, routes.size)
                        if (routes.size > 1) RoutePicker(routes, selectedRoute, { selectedRoute = it }, allowsAll = true)
                        if (index == null) AllRoutesNote(routes.size)
                        else {
                            RouteHearers(routes[index])
                            routes[index].keys.forEachIndexed { hop, key ->
                                DetailRow(stringResource(R.string.packets_hop, hop + 1), nodesByKey[key.lowercase()]?.displayName?.let { "$it · $key" } ?: key, monospace = true)
                            }
                        }
                        Button(onClick = { onReplay(routes, index ?: org.nodescope.android.feature.map.ALL_ROUTES, current) }, Modifier.fillMaxWidth()) {
                            Icon(Icons.Outlined.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.packets_replay_on_map))
                        }
                    }
                }
            }
        }
    }
}
