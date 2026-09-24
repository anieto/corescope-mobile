package org.nodescope.android.feature.nodes

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.util.Locale
import org.nodescope.android.core.design.*
import org.nodescope.android.core.model.*

private val seriesColors = listOf(Color(0xFF299EFF), Color(0xFF3DC77A), Color(0xFFFFA833), Color(0xFFB18AFF), Color(0xFFEF6F8A), Color(0xFF4FC3D9))
private val dayNames = listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NodeAnalyticsScreen(model: NodeAnalyticsViewModel, host: String, publicKey: String, initial: MeshNode?, onObserver: (String, String) -> Unit) {
    var range by rememberSaveable { mutableStateOf(AnalyticsRange.WEEK) }
    val key = AnalyticsKey(host, publicKey, range.days)
    val state by model.analytics.state.collectAsStateWithLifecycle()
    LaunchedEffect(key) { model.analytics.load(key) }
    val observerState by model.observers.state.collectAsStateWithLifecycle()
    LaunchedEffect(host) { model.observers.load(host) }
    val observerList = observerState.value.takeIf { observerState.key == host }.orEmpty()
    val now = rememberNow()
    val result = state.value.takeIf { state.key == key }
    val analytics = (result as? AnalyticsResult.Available)?.analytics
    val node = analytics?.node ?: initial

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Time range", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    AnalyticsRange.entries.forEachIndexed { index, option ->
                        SegmentedButton(selected = range == option, onClick = { range = option },
                            shape = SegmentedButtonDefaults.itemShape(index, AnalyticsRange.entries.size)) { Text(option.title) }
                    }
                }
            }
        }
        item {
            AnalyticsCard {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(44.dp).background(roleColor(node?.role), CircleShape), contentAlignment = Alignment.Center) {
                        Icon(roleIcon(node?.role), null, tint = Color.White)
                    }
                    Column(Modifier.weight(1f)) {
                        Text(node?.name?.takeIf(String::isNotBlank) ?: "Unnamed node", style = MaterialTheme.typography.titleMedium)
                        Text("${node?.role?.replaceFirstChar { it.uppercase() } ?: "Unknown role"} • ${publicKey.take(8).uppercase()}…${publicKey.takeLast(4).uppercase()}",
                            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                }
            }
        }
        if (result != AnalyticsResult.Unsupported) item {
            LoadStatus(state.takeIf { it.key == key } ?: org.nodescope.android.core.network.Loaded<AnalyticsKey, AnalyticsResult>(loading = true), analytics != null, now) {
                model.analytics.load(key, force = true)
            }
        }
        if (result == AnalyticsResult.Unsupported) item {
            EmptyState(Icons.Outlined.QueryStats, "Analytics unavailable", "This analyzer does not currently provide node analytics.")
        }
        if (analytics != null) {
            analytics.computedStats?.let { stats -> item { SummaryCard(stats) } }
            val activity = analytics.activityTimeline.mapNotNull { point -> parseInstant(point.bucket)?.let { it.toEpochMilli() to point.count.toDouble() } }.sortedBy { it.first }
            dataDomain(activity.map { it.first })?.let { (from, to) -> item {
                ChartCard("Activity", "${compactCount(analytics.activityTimeline.sumOf { it.count })} packets", Icons.Outlined.MonitorHeart) {
                    TimeChart("Packet activity over the last ${range.title}", listOf(TimeSeries(seriesColors[0], activity)), from, to,
                        area = true, zeroBased = true, formatValue = { compactCount(it.toLong()) }, formatTime = { analyticsTimeLabel(it, to - from) })
                }
            } }
            if (analytics.uptimeHeatmap.isNotEmpty()) item { HeatmapCard(heatmapGrid(analytics.uptimeHeatmap)) }
            if (analytics.snrTrend.isNotEmpty()) item { SignalCard(analytics.snrTrend, observerList, now) }
            if (analytics.packetTypeBreakdown.isNotEmpty()) item {
                ChartCard("Packet types", "Traffic reported by payload", Icons.Outlined.Inventory2) {
                    RankedBars(analytics.packetTypeBreakdown.sortedByDescending { it.count }.map { ChartPoint(payloadTypeLabel(it.payloadType), it.count) },
                        MaterialTheme.colorScheme.primary)
                }
            }
            if (analytics.hopDistribution.isNotEmpty()) item {
                ChartCard("Hop count", "How far packets traveled", Icons.Outlined.Route) {
                    BarChart("Hop count distribution", analytics.hopDistribution.map { ChartPoint("${it.hops} hops", it.count) }, MaterialTheme.colorScheme.primary)
                }
            }
            if (analytics.observerCoverage.isNotEmpty()) item {
                ListCard("Observer coverage", Icons.Outlined.Visibility, analytics.observerCoverage.sortedByDescending { it.packetCount }) { observer ->
                    val name = observer.observerName?.takeIf(String::isNotBlank) ?: observer.observerId.take(12)
                    ListRow(Icons.Outlined.Sensors, name, "Last heard ${relativeTime(parseInstant(observer.lastSeen), now)}",
                        compactCount(observer.packetCount), observer.avgSnr?.let { "%.1f dB".format(it) } ?: "No SNR") { onObserver(observer.observerId, name) }
                }
            }
            if (analytics.peerInteractions.isNotEmpty()) item {
                ListCard("Peer interactions", Icons.Outlined.People, analytics.peerInteractions.sortedByDescending { it.messageCount }) { peer ->
                    ListRow(Icons.Outlined.SettingsInputAntenna, peer.peerName?.takeIf(String::isNotBlank) ?: peer.peerKey.take(12),
                        "Last contact ${relativeTime(parseInstant(peer.lastContact), now)}", "${compactCount(peer.messageCount)} messages", null, null)
                }
            }
        }
    }
}

@Composable
private fun SummaryCard(stats: ComputedStats) {
    fun percent(value: Double?) = value?.let { String.format(Locale.US, "%.0f%%", it) } ?: "—"
    val metrics = listOf(
        Triple(percent(stats.availabilityPct), "Availability", HealthyGreen),
        Triple(stats.signalGrade ?: "—", "Signal grade", SignalBlue),
        Triple(stats.avgPacketsPerDay?.let { String.format(Locale.US, "%.0f", it) } ?: "—", "Packets / day", SignalBlue),
        Triple(percent(stats.relayPct), "Relayed", ActivityAmber),
        Triple(stats.uniqueObservers?.toString() ?: "—", "Observers", Color(0xFF5C8DFF)),
        Triple(stats.longestSilenceMs?.let(::formatSilence) ?: "—", "Longest silence", MaterialTheme.colorScheme.onSurfaceVariant),
    )
    AnalyticsCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Outlined.Speed, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
            Text("At a glance", style = MaterialTheme.typography.titleMedium)
        }
        metrics.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { (value, label, tint) ->
                    Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(value, style = MaterialTheme.typography.headlineSmall)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            Box(Modifier.size(6.dp).background(tint, CircleShape))
                            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HeatmapCard(grid: Array<LongArray>) {
    val max = grid.maxOf { row -> row.max() }.coerceAtLeast(1)
    val busiest = grid.withIndex().flatMap { (day, row) -> row.withIndex().map { (hour, count) -> Triple(day, hour, count) } }.maxByOrNull { it.third }
    val empty = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.08f)
    ChartCard("Activity by time", "Packet activity by weekday and hour, shown in UTC", Icons.Outlined.CalendarMonth) {
        Column(Modifier.semantics(mergeDescendants = true) {
            contentDescription = busiest?.takeIf { it.third > 0 }?.let { "Busiest: ${dayNames[it.first]} at ${"%02d".format(it.second)}:00 UTC, ${it.third} packets" }
                ?: "No activity recorded"
        }, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row {
                Spacer(Modifier.width(32.dp))
                (0 until 24 step 6).forEach { hour ->
                    Text("%02d".format(hour), Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            grid.forEachIndexed { day, row ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(dayNames[day], Modifier.width(32.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Canvas(Modifier.weight(1f).height(14.dp)) {
                        val gap = 2.dp.toPx()
                        val cell = (size.width - gap * 23) / 24
                        row.forEachIndexed { hour, count ->
                            val color = if (count == 0L) empty else HealthyGreen.copy(alpha = (0.22f + 0.78f * count / max).coerceAtMost(1f))
                            drawRoundRect(color, Offset(hour * (cell + gap), 0f), Size(cell, size.height), CornerRadius(3.dp.toPx()))
                        }
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
                Text("Less", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                (1..4).forEach { level -> Box(Modifier.size(12.dp).background(HealthyGreen.copy(alpha = level * 0.25f), MaterialTheme.shapes.extraSmall)) }
                Text("More", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private fun qualityColor(quality: SignalQuality?): Color = when (quality) {
    SignalQuality.STRONG -> HealthyGreen
    SignalQuality.GOOD -> Color(0xFF7CC576)
    SignalQuality.WEAK -> ActivityAmber
    SignalQuality.NEAR_LIMIT -> Color(0xFFE5484D)
    null -> Color(0xFF8A99A8)
}
private fun db(value: Double) = String.format(Locale.US, "%.1f dB", value)

/**
 * Plain-language link quality first, with the measured numbers always beside it and full
 * statistics plus a per-observer trend on tap. Each observer keeps its own listener fixed,
 * so its trend is meaningful; a trend mixed across observers is not shown.
 */
@Composable
private fun SignalCard(points: List<SignalPoint>, observers: List<MeshObserver>, now: Long) {
    val signals = remember(points, observers) { observerSignals(points, observers) }
    var expandedList by rememberSaveable { mutableStateOf(false) }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    if (signals.isEmpty()) return
    val counts = signals.mapNotNull { it.quality }.groupingBy { it }.eachCount()
    val floors = signals.mapNotNull { it.spreadingFactor }.distinct()
    val best = signals.first()
    ChartCard("Signal", "How well observers hear this node (SNR)", Icons.Outlined.GraphicEq) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Heard by ${signals.size} observer${if (signals.size == 1) "" else "s"}" +
                SignalQuality.entries.mapNotNull { q -> counts[q]?.let { " · $it ${q.label.lowercase()}" } }.joinToString(""),
                style = MaterialTheme.typography.titleSmall)
            Text("Best: ${best.observer}, ${db(best.median)}" + (best.margin?.let { " (${db(it)} above the decode limit)" } ?: ""),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        // Shared scale across rows, always including the decode limit so the margin is visible.
        val floor = signals.mapNotNull { it.floor }.maxOrNull()
        val scaleLow = minOf(signals.minOf { it.low }, floor ?: Double.MAX_VALUE) - 2
        val scaleHigh = maxOf(signals.maxOf { it.high }, (floor ?: 0.0) + 16) + 2
        signals.take(if (expandedList) signals.size else 5).forEach { signal ->
            val key = signal.observerId ?: signal.observer
            ObserverSignalRow(signal, scaleLow, scaleHigh, expanded = selected == key, now = now,
                readings = remember(points, key) { observerReadings(points, signal) }) {
                selected = if (selected == key) null else key
            }
        }
        if (signals.size > 5) TextButton(onClick = { expandedList = !expandedList }) { Text(if (expandedList) "Show less" else "Show all ${signals.size} observers") }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SignalQuality.entries.forEach { quality ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box(Modifier.size(8.dp).background(qualityColor(quality), CircleShape))
                    Text(quality.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Text(when {
            floors.size == 1 -> "Quality is the margin above the SF${floors.single()} decode limit (${db(snrFloor(floors.single()))}): near limit < 5 dB, weak 5–10, good 10–15, strong 15+. Bars show each observer's typical range; the tick is its median."
            floors.isEmpty() -> "Radio settings are unavailable, so only measured values are shown. Bars show each observer's typical range; the tick is its median."
            else -> "Quality is the margin above each observer's own decode limit (it depends on its spreading factor). Bars show typical range; the tick is the median."
        }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ObserverSignalRow(signal: ObserverSignal, scaleLow: Double, scaleHigh: Double, expanded: Boolean, now: Long,
    readings: List<Pair<Long, Double>>, onToggle: () -> Unit) {
    val track = MaterialTheme.colorScheme.surfaceContainerHigh
    val tick = MaterialTheme.colorScheme.onSurface
    val tone = qualityColor(signal.quality)
    Column(Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(Modifier.semantics(mergeDescendants = true) {
            contentDescription = "${signal.observer}: ${signal.quality?.label ?: "quality unknown"}, median ${db(signal.median)}, " +
                "typically ${db(signal.low)} to ${db(signal.high)}, ${signal.count} readings. Double tap for details."
        }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                Text(signal.observer, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("typical ${String.format(Locale.US, "%.1f to %.1f dB", signal.low, signal.high)} · ${signal.count} reading${if (signal.count == 1) "" else "s"}" +
                    if (signal.fewReadings) " · few readings" else "", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            signal.quality?.let { quality ->
                Surface(color = tone.copy(alpha = 0.16f), shape = CircleShape) {
                    Text(quality.label, Modifier.padding(horizontal = 8.dp, vertical = 3.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface)
                }
            }
            Text(db(signal.median), style = MaterialTheme.typography.labelLarge)
            Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Canvas(Modifier.fillMaxWidth().height(8.dp)) {
            fun x(v: Double) = size.width * ((v - scaleLow) / (scaleHigh - scaleLow)).toFloat().coerceIn(0f, 1f)
            val radius = CornerRadius(size.height / 2)
            drawRoundRect(track, cornerRadius = radius)
            signal.floor?.let { floor -> drawRect(Color(0xFFE5484D).copy(alpha = 0.35f), Offset(x(floor) - 1.dp.toPx(), 0f), Size(2.dp.toPx(), size.height)) }
            drawRoundRect(tone.copy(alpha = 0.7f), Offset(x(signal.low), 0f), Size((x(signal.high) - x(signal.low)).coerceAtLeast(size.height), size.height), radius)
            drawRect(tick, Offset(x(signal.median) - 1.dp.toPx(), 0f), Size(2.dp.toPx(), size.height))
        }
        if (expanded) {
            val stats = listOfNotNull(
                "Median SNR" to db(signal.median),
                "Typical range" to String.format(Locale.US, "%.1f to %.1f dB", signal.low, signal.high),
                "Min / max" to String.format(Locale.US, "%.1f / %.1f dB", signal.min, signal.max),
                signal.margin?.let { "Above decode limit" to "${db(it)} (SF${signal.spreadingFactor} limit ${db(signal.floor!!)})" },
                signal.medianRssi?.let { "Median RSSI" to String.format(Locale.US, "%.0f dBm", it) },
                "Readings" to signal.count.toString(),
                signal.lastHeard?.let { "Last heard" to relativeTime(java.time.Instant.ofEpochMilli(it), now) },
            )
            Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                stats.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        row.forEach { (label, value) ->
                            Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}) {
                                Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(value, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
                if (readings.isNotEmpty()) {
                    ReadingsChart(signal, readings, scaleLow, scaleHigh)
                    Text("Each dot is one reading, on the same scale as the bars. Shading marks the quality zones; the red line is the decode limit.",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/**
 * Every reading as a dot on the card's shared scale, over quality zones and the decode limit,
 * so a steady link reads as steady and its distance from failure stays visible.
 */
@Composable
private fun ReadingsChart(signal: ObserverSignal, readings: List<Pair<Long, Double>>, scaleLow: Double, scaleHigh: Double) {
    val (from, to) = dataDomain(readings.map { it.first }) ?: return
    val floor = signal.floor
    val zoneAlpha = 0.10f
    Column(Modifier.semantics(mergeDescendants = true) {
        contentDescription = "${readings.size} readings from ${db(signal.min)} to ${db(signal.max)}" +
            (floor?.let { ", decode limit ${db(it)}" } ?: "")
    }) {
        Row(Modifier.fillMaxWidth().height(130.dp)) {
            Column(Modifier.fillMaxHeight().padding(end = 6.dp), verticalArrangement = Arrangement.SpaceBetween, horizontalAlignment = Alignment.End) {
                Text(String.format(Locale.US, "%.0f dB", scaleHigh), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(String.format(Locale.US, "%.0f dB", scaleLow), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Canvas(Modifier.weight(1f).fillMaxHeight()) {
                fun y(v: Double) = size.height - size.height * ((v - scaleLow) / (scaleHigh - scaleLow)).toFloat().coerceIn(0f, 1f)
                fun x(t: Long) = if (to > from) size.width * ((t - from).toFloat() / (to - from)) else size.width / 2
                if (floor != null) {
                    // Quality zones by margin above the limit: near limit, weak, good, strong.
                    listOf(Triple(scaleLow, floor + 5, SignalQuality.NEAR_LIMIT), Triple(floor + 5, floor + 10, SignalQuality.WEAK),
                        Triple(floor + 10, floor + 15, SignalQuality.GOOD), Triple(floor + 15, scaleHigh, SignalQuality.STRONG)).forEach { (lo, hi, quality) ->
                        if (hi > lo) drawRect(qualityColor(quality).copy(alpha = zoneAlpha), Offset(0f, y(hi)), Size(size.width, y(lo) - y(hi)))
                    }
                    drawLine(Color(0xFFE5484D), Offset(0f, y(floor)), Offset(size.width, y(floor)), 1.5.dp.toPx(),
                        pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx())))
                }
                val radius = if (readings.size > 150) 2.dp.toPx() else 3.dp.toPx()
                readings.forEach { (time, snr) ->
                    val color = floor?.let { qualityColor(signalQuality(snr - it)) } ?: SignalBlue
                    drawCircle(color, radius, Offset(x(time), y(snr)))
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = 36.dp, top = 4.dp)) {
            Text(analyticsTimeLabel(readings.first().first, to - from), Modifier.weight(1f), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(analyticsTimeLabel(readings.last().first, to - from), Modifier.weight(1f), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.End)
        }
    }
}

@Composable
private fun <T> ListCard(title: String, icon: ImageVector, items: List<T>, row: @Composable (T) -> Unit) {
    var expanded by rememberSaveable(title) { mutableStateOf(false) }
    AnalyticsCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(icon, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
            Text(title, style = MaterialTheme.typography.titleMedium)
        }
        Column {
            items.take(if (expanded) items.size else 5).forEachIndexed { index, item ->
                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                row(item)
            }
        }
        if (items.size > 5) TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Show less" else "Show all ${items.size}") }
    }
}

@Composable
private fun ListRow(icon: ImageVector, title: String, subtitle: String, value: String, detail: String?, onClick: (() -> Unit)?) {
    Row(Modifier.fillMaxWidth().let { if (onClick != null) it.clickable(onClick = onClick) else it }.padding(vertical = 9.dp)
        .semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(value, style = MaterialTheme.typography.labelLarge)
            detail?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
private fun ChartCard(title: String, subtitle: String, icon: ImageVector, content: @Composable ColumnScope.() -> Unit) {
    AnalyticsCard {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        content()
    }
}

@Composable
private fun AnalyticsCard(content: @Composable ColumnScope.() -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp), content = content)
    }
}
