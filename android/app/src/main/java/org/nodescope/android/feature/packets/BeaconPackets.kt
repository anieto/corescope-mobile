package org.nodescope.android.feature.packets

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
    selectedHash: String? = null, onPacket: (String) -> Unit) {
    var filterType by rememberSaveable { mutableStateOf<String?>(null) }
    var showFilters by remember { mutableStateOf(false) }
    val region = feed.selection?.region?.uppercase()
    val filter = LiveBrowseFilter(region, filterType?.let { payloadTypeCodes[it] })
    // Each filter is its own server subscription and recovered history, as on iOS.
    LiveFeedDemandEffect(host, LiveFeedDemand(LiveFeedSubscription(setOfNotNull(filter.region), setOfNotNull(filter.payloadType))))
    val snapshot = feed.beacon?.browseSnapshots?.get(filter)
    val rows = snapshot?.rows.orEmpty()
    val now = rememberNow()
    val live = feed.connection == LiveConnection.LIVE

    if (showFilters) FilterSheet("Live packet filters", regionControl, listOf(
        FilterGroup("Packet type", listOf(null) + packetTypeNames, filterType, { it ?: "All packet types" }) { filterType = it },
    ), onReset = { filterType = null; onClearRegion() }) { showFilters = false }
    val filterCount = listOf(region != null, filterType != null).count { it }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                            Box(Modifier.size(8.dp).background(statusAccent(live), CircleShape))
                            Text(connectionLabel(feed.connection, "Listening for live traffic"), style = MaterialTheme.typography.titleSmall)
                        }
                        Text(listOf(region ?: "Entire network", "${rows.size} transmissions", "${rows.sumOf { it.observationCount }} observations")
                            .joinToString(" · "), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { showFilters = true }) {
                        BadgedBox(badge = { if (filterCount > 0) Badge { Text(filterCount.toString()) } }) {
                            Icon(Icons.Outlined.FilterList, "Filter live packets, $filterCount active")
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
                        Text("History refresh failed", style = MaterialTheme.typography.bodyMedium)
                    }
                    Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (snapshot?.possiblyIncomplete == true) item {
            Text("Recent history may be incomplete.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (rows.isNotEmpty()) item { SectionLabel("Recent packets") }
        items(rows, key = { it.hash }) { row -> BeaconPacketRow(row, now, row.hash == selectedHash) { onPacket(row.hash) } }
        if (rows.isEmpty()) item {
            EmptyState(Icons.Outlined.SettingsInputAntenna, if (live) "Waiting for packets" else "Not connected",
                if (live) "New mesh traffic will appear here as the analyzer receives it."
                else "NodeScope will resume the live feed when the analyzer reconnects.")
        }
    }
}

@Composable
private fun BeaconPacketRow(row: BeaconLiveRecovery.Row, now: Long, selected: Boolean, onClick: () -> Unit) {
    val typeName = payloadTypeName(row.payloadType)
    val tone = packetTone(typeName)
    Card(onClick = onClick, modifier = Modifier.semantics { this.selected = selected },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(packetIcon(typeName), null, Modifier.size(24.dp), tint = tone)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(typeName, style = MaterialTheme.typography.titleSmall, color = tone)
                    if (row.observationCount > 1) MetricChip(row.observationCount.toString(), Icons.Outlined.Visibility)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(row.hash, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(relativeTime(Instant.ofEpochMilli(row.lastHeard), now), style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** iOS `BeaconPacketSummary`: what the snapshot knows, then the server's observations and confirmed routes. */
@Composable
fun BeaconPacketDetailScreen(feed: LiveFeedState, browse: BrowseRepository?, host: String, hash: String, nodes: List<MeshNode>,
    onReplay: (routes: List<RouteOption>, selected: Int) -> Unit) {
    val row = feed.beaconRow(hash)
    var detail by remember(hash) { mutableStateOf<PacketDetail?>(null) }
    var loading by remember(hash) { mutableStateOf(false) }
    var error by remember(hash) { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    var selectedRoute by rememberSaveable(hash) { mutableIntStateOf(0) }
    LaunchedEffect(hash, retry) {
        val repository = browse ?: return@LaunchedEffect
        loading = true
        error = null
        selectedRoute = 0
        try {
            detail = repository.packetDetail(host, hash)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: "Couldn't load this packet."
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
                    Text(message, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = { retry++ }) { Text("Retry") }
                }
            }
        }
        item {
            DetailSection("Packet") {
                DetailRow("Type", typeName)
                DetailRow("Hash", hash, monospace = true)
                DetailRow("Observations", maxOf(current?.observationCount ?: 0, row?.observationCount ?: 0).toString())
            }
        }
        item {
            DetailSection("Heard") {
                DetailRow("First seen", row?.firstSeen?.let { dates.format(Instant.ofEpochMilli(it)) } ?: "Unknown")
                DetailRow("Last heard", lastHeard?.let { dates.format(Instant.ofEpochMilli(it)) } ?: "Unknown")
            }
        }
        if (current != null) {
            item {
                DetailSection("Observations") {
                    current.observations.forEachIndexed { index, observation ->
                        if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(observation.observerName ?: "Unknown observer", style = MaterialTheme.typography.titleSmall)
                            Text(listOfNotNull(observation.observerIata,
                                parseInstant(observation.timestamp)?.let(dates::format),
                                observation.snr?.let { "%.1f dB".format(it) }, observation.rssi?.let { "%.1f dBm".format(it) },
                            ).joinToString(" · "), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            item {
                DetailSection("Route") {
                    if (routes.isEmpty()) {
                        Text("No fully resolved route is available. Uncertain hops are not replayed.",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        val index = selectedRoute.coerceIn(routes.indices)
                        if (routes.size > 1) RoutePicker(routes, index, { selectedRoute = it })
                        RouteHearers(routes[index])
                        routes[index].keys.forEachIndexed { hop, key ->
                            DetailRow("Hop ${hop + 1}", nodesByKey[key.lowercase()]?.displayName?.let { "$it · $key" } ?: key, monospace = true)
                        }
                        Button(onClick = { onReplay(routes, index) }, Modifier.fillMaxWidth()) {
                            Icon(Icons.Outlined.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text("Replay on map")
                        }
                    }
                }
            }
        }
    }
}
