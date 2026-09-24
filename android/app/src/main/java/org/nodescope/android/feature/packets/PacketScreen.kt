package org.nodescope.android.feature.packets

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
    val label = when (connection) {
        LiveConnection.LIVE -> "Connected"
        LiveConnection.CONNECTING -> "Connecting"
        LiveConnection.RECONNECTING -> "Reconnecting"
        LiveConnection.PAUSED -> "Paused"
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(7.dp).background(if (connected) HealthyGreen else ActivityAmber, CircleShape))
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
    onGroup: (String) -> Unit = {}) {
    var filter by rememberSaveable { mutableStateOf<String?>(null) }
    var showFilters by remember { mutableStateOf(false) }
    val groups = remember(feed.visiblePackets, feed.observers, filter) { groupTransmissions(feed.visiblePackets, filter, feed.observers) }
    val scroll = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val now = rememberNow()
    var shownNewest by remember { mutableStateOf<String?>(null) }
    val newPackets = groups.firstOrNull()?.id != shownNewest && scroll.firstVisibleItemIndex > 0
    LaunchedEffect(groups.firstOrNull()?.id, scroll.firstVisibleItemIndex) {
        if (scroll.firstVisibleItemIndex == 0) shownNewest = groups.firstOrNull()?.id
    }
    if (showFilters) FilterSheet("Live packet filters", regionControl, listOf(
        FilterGroup("Packet type", listOf(null) + packetTypeNames, filter, { it ?: "All packet types" }) { filter = it },
    ), onReset = { filter = null; onClearRegion() }) { showFilters = false }
    val filterCount = listOf(feed.selection?.region != null, filter != null).count { it }

    LazyColumn(Modifier.fillMaxSize(), state = scroll, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                            Box(Modifier.size(8.dp).background(if (feed.connection == LiveConnection.LIVE) HealthyGreen else ActivityAmber, CircleShape))
                            Text(if (feed.connection == LiveConnection.LIVE) "Listening for live traffic" else "Reconnecting to analyzer",
                                style = MaterialTheme.typography.titleSmall)
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            SummaryLabel(Icons.Outlined.Public, feed.selection?.region ?: "Entire network")
                            SummaryLabel(Icons.Outlined.MonitorHeart, "${groups.size} transmissions")
                            SummaryLabel(Icons.Outlined.Visibility, "${groups.sumOf { it.observationCount }} observations")
                        }
                    }
                    IconButton(onClick = { showFilters = true }) {
                        BadgedBox(badge = { if (filterCount > 0) Badge { Text(filterCount.toString()) } }) {
                            Icon(Icons.Outlined.FilterList, "Filter live packets, $filterCount active")
                        }
                    }
                }
            }
        }
        if (feed.selection?.region != null && !feed.observersLoaded) item {
            Text("Observer regions unavailable; only explicitly matching packets are shown.", style = MaterialTheme.typography.bodySmall)
        }
        if (feed.historyError) item {
            Text("Recent history unavailable. Live packets will still appear when connected.", style = MaterialTheme.typography.bodySmall)
        }
        if (newPackets) item {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                FilledTonalButton(onClick = { scope.launch { scroll.animateScrollToItem(0) } }) { Text("New packets · back to latest") }
            }
        }
        if (groups.isNotEmpty()) item { SectionLabel("Incoming traffic") }
        items(groups, key = { it.id }) { group -> TransmissionRow(group, now) { onGroup(group.id) } }
        if (groups.isEmpty()) item {
            EmptyState(Icons.Outlined.SettingsInputAntenna, if (feed.connection == LiveConnection.LIVE) "Waiting for packets" else "Not connected",
                if (filter != null) "No traffic matches this filter yet."
                else if (feed.connection == LiveConnection.LIVE) "New mesh traffic will appear here as the analyzer receives it."
                else "NodeScope will resume the live feed when the analyzer reconnects.") {
                if (feed.connection != LiveConnection.LIVE) TextButton(onClick = onReconnect) { Text("Reconnect") }
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
private fun TransmissionRow(group: TransmissionGroup, now: Long, onClick: () -> Unit) {
    val tone = packetTone(group.typeName)
    Card(onClick = onClick, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            Box(Modifier.padding(vertical = 6.dp).width(4.dp).fillMaxHeight().background(tone, CircleShape))
            Row(Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(packetIcon(group.typeName), null, Modifier.size(24.dp), tint = tone)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp),
                        itemVerticalAlignment = Alignment.CenterVertically) {
                        Text(group.typeName, style = MaterialTheme.typography.titleSmall, color = tone)
                        if (group.hopCount > 0) CountBadge(group.hopCount.toString(), Icons.AutoMirrored.Outlined.ArrowForward, MaterialTheme.colorScheme.primary,
                            "${group.hopCount} hops")
                        if (group.observationCount > 1) CountBadge(group.observationCount.toString(), Icons.Outlined.Visibility, Color(0xFF8E6BE0),
                            "heard ${group.observationCount} times")
                        group.region?.let { region ->
                            Surface(color = Color(0xFF4B5BC7), shape = MaterialTheme.shapes.extraSmall) {
                                Text(region, Modifier.padding(horizontal = 6.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall, color = Color.White)
                            }
                        }
                        if (!group.isLive) Text("Recent", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
