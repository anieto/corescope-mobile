package org.nodescope.android.feature.observers

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.nodescope.android.core.design.*
import org.nodescope.android.core.model.*

@Composable
fun ObserverDetailScreen(model: ObserversViewModel, host: String, observerId: String, favorite: Boolean = false,
    onFavorite: (MeshObserver) -> Unit = {}, onViewed: (MeshObserver) -> Unit = {}) {
    val listState by model.observers.state.collectAsStateWithLifecycle()
    val analyticsState by model.analytics.state.collectAsStateWithLifecycle()
    val key = host to observerId
    val now = rememberNow()
    LaunchedEffect(host) { model.observers.load(host) }
    LaunchedEffect(key) { model.analytics.load(key) }
    val observer = listState.value.takeIf { listState.key == host }?.firstOrNull { it.id == observerId }
    val analytics = analyticsState.value.takeIf { analyticsState.key == key }
    LaunchedEffect(observer?.id) { observer?.let(onViewed) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            when {
                observer != null -> IdentityCard(observer, now, favorite) { onFavorite(observer) }
                listState.loading -> LinearProgressIndicator(Modifier.fillMaxWidth())
                else -> Text("This observer is not in the current results for $host.", style = MaterialTheme.typography.bodyMedium)
            }
        }
        item { LoadStatus(analyticsState, analytics != null, now) { model.analytics.load(key, force = true) } }
        if (analytics != null) {
            if (analytics.timeline.isNotEmpty()) item {
                AnalyticsCard("Packets over time", Icons.Outlined.BarChart) {
                    BarChart("Packets over time", analytics.timeline.map { ChartPoint(it.label, it.count) }, MaterialTheme.colorScheme.primary)
                }
            }
            if (analytics.packetTypes.isNotEmpty()) item {
                AnalyticsCard("Packet types", Icons.Outlined.DonutLarge) {
                    val slices = analytics.packetTypes.entries.sortedByDescending { it.value }
                        .map { ChartPoint(payloadTypeLabel(it.key.toIntOrNull()), it.value) }
                    val total = slices.sumOf { it.value }
                    DonutChart("Packet types: " + slices.take(3).joinToString(", ") { "${it.label} ${percentOf(it.value, total)}" }, slices)
                }
            }
            if (analytics.nodesTimeline.isNotEmpty()) item {
                AnalyticsCard("Unique nodes heard", Icons.Outlined.Hub) {
                    LineChart("Unique nodes heard", analytics.nodesTimeline.map { ChartPoint(it.label, it.count) }, HealthyGreen)
                }
            }
            if (analytics.snrDistribution.isNotEmpty()) item {
                AnalyticsCard("SNR distribution (dB)", Icons.Outlined.GraphicEq) {
                    BarChart("SNR distribution", analytics.snrDistribution.map { ChartPoint(it.range, it.count) }, ActivityAmber)
                }
            }
            if (analytics.recentPackets.isNotEmpty()) item {
                AnalyticsCard("Recent packets", Icons.Outlined.Inventory2) {
                    analytics.recentPackets.take(25).forEach { RecentPacketRow(it, now) }
                }
            }
        }
    }
}

@Composable
private fun IdentityCard(observer: MeshObserver, now: Long, favorite: Boolean, onFavorite: () -> Unit) {
    val copy = rememberCopyAction()
    val context = androidx.compose.ui.platform.LocalContext.current
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val tone = if (observer.isActive(now)) HealthyGreen else ActivityAmber
                Box(Modifier.size(48.dp).background(tone.copy(alpha = 0.13f), CircleShape), contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.Sensors, null, tint = tone)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(observer.displayName, style = MaterialTheme.typography.titleLarge)
                    Text(listOfNotNull(observer.iata, "Seen ${relativeTime(parseInstant(observer.lastSeen), now)}").joinToString(" · "),
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = onFavorite) {
                    Icon(if (favorite) Icons.Outlined.Star else Icons.Outlined.StarBorder,
                        if (favorite) "Remove observer from favorites" else "Add observer to favorites",
                        tint = if (favorite) androidx.compose.ui.graphics.Color(0xFFE0A100) else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                var menu by remember { mutableStateOf(false) }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "Observer actions") }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(text = { Text("Copy observer ID") }, leadingIcon = { Icon(Icons.Outlined.ContentCopy, null) },
                            onClick = { menu = false; copy("Observer ID", observer.id, false) })
                        DropdownMenuItem(text = { Text("Share observer link") }, leadingIcon = { Icon(Icons.Outlined.Share, null) }, onClick = {
                            menu = false
                            // Same link format as iOS (`nodescope://observer/<id>`).
                            context.startActivity(android.content.Intent.createChooser(android.content.Intent(android.content.Intent.ACTION_SEND)
                                .setType("text/plain").putExtra(android.content.Intent.EXTRA_TEXT, NodeScopeLink.observer(observer.id).url), "Share observer link"))
                        })
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            MetricGrid(listOf(
                "Model" to (observer.model ?: "Unknown"),
                "Firmware" to (observer.firmware ?: "Unknown"),
                "Packets / hr" to (observer.packetsLastHour?.let(::compactCount) ?: "Unknown"),
                "All packets" to (observer.packetCount?.let(::compactCount) ?: "Unknown"),
                "Battery" to (observer.batteryMv?.let { "$it mV" } ?: "Unknown"),
                "Noise floor" to (observer.noiseFloor?.let { "%.0f dB".format(it) } ?: "Unknown"),
                "Uptime" to (observer.uptimeSecs?.let(::formatDuration) ?: "Unknown"),
                "First seen" to shortDateTime(parseInstant(observer.firstSeen)),
            ))
            SelectionContainer {
                Text(observer.id, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun MetricGrid(values: List<Pair<String, String>>) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        values.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                row.forEach { (label, value) ->
                    Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}) {
                        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(value, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun AnalyticsCard(title: String, icon: ImageVector, content: @Composable ColumnScope.() -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(icon, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                Text(title, style = MaterialTheme.typography.titleMedium)
            }
            content()
        }
    }
}

@Composable
private fun RecentPacketRow(packet: ObserverRecentPacket, now: Long) {
    Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.small) {
            Text(payloadTypeLabel(packet.payloadType), Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Column(Modifier.weight(1f)) {
            Text(packet.hash.take(16), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelMedium)
            Text(listOfNotNull(packet.snr?.let { "SNR %.1f dB".format(it) }, packet.rssi?.let { "RSSI %.0f dBm".format(it) })
                .joinToString(" · ").ifEmpty { "No signal data" }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(relativeTime(parseInstant(packet.timestamp), now), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
