package org.nodescope.android.feature.observers

import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import org.nodescope.android.R
import org.nodescope.android.feature.learn.*
import org.nodescope.android.core.design.meshCardColors
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
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
import java.util.Locale
import org.nodescope.android.core.network.LiveFeedState

@Composable
fun ObserverDetailScreen(model: ObserversViewModel, host: String, observerId: String, feed: LiveFeedState? = null, favorite: Boolean = false,
    onFavorite: (MeshObserver) -> Unit = {}, onViewed: (MeshObserver) -> Unit = {}) {
    val listState by model.observers.state.collectAsStateWithLifecycle()
    val analyticsState by model.analytics.state.collectAsStateWithLifecycle()
    val key = host to observerId
    val now = rememberNow()
    val beacon = LocalBeaconSource.current
    val detailState by model.beaconDetail.state.collectAsStateWithLifecycle()
    if (beacon) LaunchedEffect(key) { model.beaconDetail.load(key) }
    else LaunchedEffect(host) { model.observers.load(host) }
    LaunchedEffect(key, beacon) { model.analytics.load(key) }
    val observer = if (beacon) {
        // The server record, with the list's online state and the newest live status (iOS `displayedObserver`).
        val listed = feed?.observers?.firstOrNull { it.id.equals(observerId, ignoreCase = true) }
        val detail = detailState.value?.takeIf { detailState.key == key }?.copy(online = listed?.online) ?: listed
        detail?.let { feed?.beacon?.observers?.get(it.id.lowercase())?.applying(it) ?: it }
    } else listState.value.takeIf { listState.key == host }?.firstOrNull { it.id == observerId }
    val analytics = analyticsState.value.takeIf { analyticsState.key == key }
    // Beacon activity counts observations, not packets; labels say which.
    val observations = analytics?.context?.countUnit == AnalyticsCountUnit.OBSERVATIONS
    val noun = if (observations) stringResource(R.string.observers_observations) else stringResource(R.string.observers_packets_2)
    LaunchedEffect(observer?.id) { observer?.let(onViewed) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            when {
                observer != null -> ObserverIdentityCard(observer, now, favorite) { onFavorite(observer) }
                (if (beacon) detailState.loading || detailState.key != key else listState.loading) -> LinearProgressIndicator(Modifier.fillMaxWidth())
                else -> Text(stringResource(R.string.observers_this_observer_is_not_in_the_current_results, host), style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (beacon && detailState.key == key) item {
            LoadStatus(detailState, detailState.value != null, now) { model.beaconDetail.load(key, force = true) }
        }
        item { LoadStatus(analyticsState, analytics != null, now) { model.analytics.load(key, force = true) } }
        if (analytics != null) {
            analytics.context?.explanation?.let { text -> item { AnalyticsNotice(text) } }
            if (analytics.timeline.isEmpty() && analytics.packetTypes.isEmpty() && analytics.nodesTimeline.isEmpty() &&
                analytics.snrDistribution.isEmpty() && analytics.recentPackets.isEmpty() && analytics.telemetry.isNullOrEmpty()) item {
                EmptyState(Icons.Outlined.MonitorHeart, stringResource(R.string.observers_no_activity_in_this_range), stringResource(R.string.observers_no_observer_activity_or_telemetry_was_recorded_during))
            }
            if (analytics.timeline.isNotEmpty()) item {
                AnalyticsCard(stringResource(R.string.observers_over_time, noun), Icons.Outlined.BarChart) {
                    BarChart(stringResource(if (observations) R.string.observers_observations_received_over_time else R.string.observers_packets_received_over_time), analytics.timeline.map { ChartPoint(it.label, it.count) }, MaterialTheme.colorScheme.primary)
                }
            }
            if (analytics.packetTypes.isNotEmpty()) item {
                val types = if (observations) stringResource(R.string.observers_observation_types) else stringResource(R.string.observers_packet_types)
                AnalyticsCard(types, Icons.Outlined.DonutLarge) {
                    val slices = analytics.packetTypes.entries.sortedByDescending { it.value }
                        .map { ChartPoint(payloadTypeLabel(it.key.toIntOrNull()), it.value) }
                    val total = slices.sumOf { it.value }
                    DonutChart("$types: " + slices.take(3).joinToString(", ") { "${it.label} ${percentOf(it.value, total)}" }, slices,
                        centerLabel = noun.lowercase())
                }
            }
            if (analytics.nodesTimeline.isNotEmpty()) item {
                AnalyticsCard(stringResource(R.string.observers_unique_nodes_heard), Icons.Outlined.Hub) {
                    LineChart(stringResource(R.string.observers_unique_nodes_heard_2), analytics.nodesTimeline.map { ChartPoint(it.label, it.count) }, HealthyGreen)
                }
            }
            if (analytics.snrDistribution.isNotEmpty()) item {
                AnalyticsCard(stringResource(R.string.observers_snr_distribution_db), Icons.Outlined.GraphicEq, GlossaryTerm.SNR) {
                    BarChart("SNR distribution", analytics.snrDistribution.map { ChartPoint(it.range, it.count) }, ActivityAmber)
                }
            }
            if (analytics.recentPackets.isNotEmpty()) item {
                AnalyticsCard(stringResource(R.string.observers_recent_packets), Icons.Outlined.Inventory2) {
                    analytics.recentPackets.take(25).forEach { RecentPacketRow(it, now) }
                }
            }
            // Beacon-only telemetry follows the shared sections.
            analytics.telemetry?.takeIf { it.isNotEmpty() }?.let { points ->
                item { TelemetrySummaryCard(points, now) }
                points.filter { it.batteryMillivolts != null }.takeIf { it.isNotEmpty() }?.let { battery -> item {
                    TelemetryHistoryCard(stringResource(R.string.observers_battery_history), Icons.Outlined.BatteryFull, stringResource(R.string.observers_observer_battery_voltage_over_time), HealthyGreen,
                        battery.map { it.timestamp to it.batteryMillivolts!! / 1_000.0 }) { String.format(Locale.US, "%.2f V", it) }
                } }
                points.filter { it.noiseFloorDecibels != null }.takeIf { it.isNotEmpty() }?.let { noise -> item {
                    TelemetryHistoryCard(stringResource(R.string.observers_noise_floor_history), Icons.Outlined.GraphicEq, stringResource(R.string.observers_observer_noise_floor_over_time), ActivityAmber,
                        noise.map { it.timestamp to it.noiseFloorDecibels!! }) { String.format(Locale.US, "%.0f dB", it) }
                } }
            }
        }
    }
}

@Composable
internal fun ObserverIdentityCard(observer: MeshObserver, now: Long, favorite: Boolean, onFavorite: () -> Unit) {
    val resources = LocalResources.current
    var technicalExpanded by rememberSaveable(observer.id) { mutableStateOf(false) }
    val copy = rememberCopyAction()
    val context = androidx.compose.ui.platform.LocalContext.current
    Card(colors = meshCardColors()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val tone = statusAccent(observer.online ?: observer.isActive(now))
                Box(Modifier.size(48.dp).background(tone.copy(alpha = 0.13f), CircleShape), contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.Sensors, null, tint = tone)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(observer.displayName, style = MaterialTheme.typography.titleLarge)
                    Text(listOfNotNull(observer.iata,
                        parseInstant(observer.lastSeen)?.let { stringResource(R.string.observers_seen, relativeTime(it, now)) } ?: stringResource(R.string.observers_last_seen_unknown),
                        observer.online?.let { if (it) stringResource(R.string.observers_online) else stringResource(R.string.observers_offline) }).joinToString(" · "),
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = onFavorite) {
                    Icon(if (favorite) Icons.Outlined.Star else Icons.Outlined.StarBorder,
                        if (favorite) stringResource(R.string.observers_remove_observer_from_favorites) else stringResource(R.string.observers_add_observer_to_favorites),
                        tint = if (favorite) androidx.compose.ui.graphics.Color(0xFFE0A100) else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                var menu by remember { mutableStateOf(false) }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, stringResource(R.string.observers_observer_actions)) }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.observers_copy_observer_id)) }, leadingIcon = { Icon(Icons.Outlined.ContentCopy, null) },
                            onClick = { menu = false; copy(resources.getString(R.string.observers_observer_id_2), observer.id, false) })
                        DropdownMenuItem(text = { Text(stringResource(R.string.observers_share_observer_link)) }, leadingIcon = { Icon(Icons.Outlined.Share, null) }, onClick = {
                            menu = false
                            // Same link format as iOS (`nodescope://observer/<id>`).
                            context.startActivity(android.content.Intent.createChooser(android.content.Intent(android.content.Intent.ACTION_SEND)
                                .setType("text/plain").putExtra(android.content.Intent.EXTRA_TEXT, NodeScopeLink.observer(observer.id).url), resources.getString(R.string.observers_share_observer_link_2)))
                        })
                    }
                }
            }
            MetricGrid(listOf(
                stringResource(R.string.observers_packets_hr_2) to (observer.packetsLastHour?.let(::compactCount) ?: stringResource(R.string.observers_unknown_2)),
                stringResource(R.string.observers_all_packets) to (observer.packetCount?.let(::compactCount) ?: stringResource(R.string.observers_unknown_2)),
            ))
            TextButton(onClick = { technicalExpanded = !technicalExpanded }) {
                Text(if (technicalExpanded) stringResource(R.string.observers_hide_technical_details) else stringResource(R.string.observers_technical_details))
                Icon(if (technicalExpanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null)
            }
            if (technicalExpanded) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                MetricGrid(listOf(
                    stringResource(R.string.observers_model) to (observer.model ?: stringResource(R.string.observers_unknown_2)),
                    stringResource(R.string.observers_firmware) to (observer.firmware ?: stringResource(R.string.observers_unknown_2)),
                    stringResource(R.string.observers_battery) to (observer.batteryMv?.let { "$it mV" } ?: stringResource(R.string.observers_unknown_2)),
                    stringResource(R.string.observers_noise_floor) to (observer.noiseFloor?.let { "%.0f dB".format(it) } ?: stringResource(R.string.observers_unknown_2)),
                    stringResource(R.string.observers_uptime) to (observer.uptimeSecs?.let(::formatDuration) ?: stringResource(R.string.observers_unknown_2)),
                    stringResource(R.string.observers_first_seen) to shortDateTime(parseInstant(observer.firstSeen)),
                ))
                Text(stringResource(R.string.observers_observer_id), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                SelectionContainer {
                    Text(observer.id, fontFamily = MonaspaceNeon, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun AnalyticsNotice(message: String) {
    Card(colors = meshCardColors()) {
        Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Outlined.Info, null, Modifier.size(18.dp), tint = SignalBlue)
            Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Latest Beacon telemetry; a field the sample omits reads "Unknown", never zero. */
@Composable
private fun TelemetrySummaryCard(points: List<ObserverTelemetryPoint>, now: Long) {
    val latest = points.maxBy { it.timestamp }
    fun duration(seconds: Long?): String? = seconds?.let {
        val days = it / 86_400
        val hours = it % 86_400 / 3_600
        if (days > 0) "$days d $hours h" else "$hours h ${it % 3_600 / 60} min"
    }
    AnalyticsCard(stringResource(R.string.observers_observer_telemetry), Icons.Outlined.Sensors) {
        MetricGrid(listOf(
            stringResource(R.string.observers_telemetry_battery) to (latest.batteryMillivolts?.let { String.format(Locale.US, "%.2f V", it / 1_000.0) } ?: stringResource(R.string.observers_telemetry_unknown)),
            stringResource(R.string.observers_telemetry_noise_floor) to (latest.noiseFloorDecibels?.let { String.format(Locale.US, "%.0f dB", it) } ?: stringResource(R.string.observers_telemetry_unknown)),
            "TX airtime" to (duration(latest.transmitAirtimeSeconds) ?: stringResource(R.string.observers_unknown_2)),
            "RX airtime" to (duration(latest.receiveAirtimeSeconds) ?: stringResource(R.string.observers_unknown_2)),
            stringResource(R.string.observers_uptime) to (duration(latest.uptimeSeconds) ?: stringResource(R.string.observers_unknown_2)),
            stringResource(R.string.observers_receive_errors) to (latest.receiveErrors?.toString() ?: stringResource(R.string.observers_unknown_2)),
        ))
        Text(stringResource(R.string.observers_latest_telemetry_sample, relativeTime(java.time.Instant.ofEpochMilli(latest.timestamp), now)),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun TelemetryHistoryCard(title: String, icon: ImageVector, description: String, color: androidx.compose.ui.graphics.Color,
    points: List<Pair<Long, Double>>, format: (Double) -> String) {
    val sorted = points.sortedBy { it.first }
    val from = sorted.first().first
    val to = sorted.last().first.let { if (it > from) it else from + 3_600_000 }
    AnalyticsCard(title, icon) {
        TimeChart(description, listOf(TimeSeries(color, sorted)), from, to, area = false, zeroBased = false, formatValue = format,
            formatTime = { org.nodescope.android.feature.nodes.analyticsTimeLabel(it, to - from) })
    }
}

@Composable
private fun MetricGrid(values: List<Pair<String, String>>) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        values.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                row.forEach { (label, value) ->
                    Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}) {
                        Text(value, style = MaterialTheme.typography.titleMedium)
                        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun AnalyticsCard(title: String, icon: ImageVector, learn: GlossaryTerm? = null, content: @Composable ColumnScope.() -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(icon, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                Text(title, style = MaterialTheme.typography.titleMedium)
                learn?.let { LearnButton(it, Modifier.offset(x = (-6).dp)) }
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
            Text(packet.hash.take(16), fontFamily = MonaspaceNeon, style = MaterialTheme.typography.labelMedium)
            Text(listOfNotNull(packet.snr?.let { "SNR %.1f dB".format(it) }, packet.rssi?.let { "RSSI %.0f dBm".format(it) })
                .joinToString(" · ").ifEmpty { stringResource(R.string.observers_no_signal_data) }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(relativeTime(parseInstant(packet.timestamp), now), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
