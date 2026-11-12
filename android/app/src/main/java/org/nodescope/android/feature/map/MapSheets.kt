package org.nodescope.android.feature.map

import org.nodescope.android.core.design.MonaspaceNeon
import org.nodescope.android.feature.learn.*
import org.nodescope.android.core.design.MeshModalBottomSheet
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.util.Locale
import org.nodescope.android.core.design.BrowseSearchField
import org.nodescope.android.core.design.SectionLabel
import org.nodescope.android.core.design.relativeTime
import org.nodescope.android.core.design.rememberNow
import org.nodescope.android.core.model.MeshObserver

/** Marker colors, so each role row matches what it shows on the map. */
private val roleColors = mapOf("repeater" to Color(0xFFFFAA44), "room" to Color(0xFF299EFF),
    "companion" to Color(0xFF45C99D), "sensor" to Color(0xFFB18AFF))

/** A role's map marker color (shared by the filter sheet and node search chips). */
fun roleColor(role: String): Color = roleColors[role.lowercase()] ?: Color(0xFF299EFF)

/**
 * iOS `MapNodeFilterSheet`, grouped by what each part changes: which nodes are drawn (last heard,
 * roles), then which routes (heard by). What the map's symbols mean is on the Map Key, opened from
 * here or from the map's ⓘ.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MapFiltersSheet(filters: MapFilters, observers: List<MeshObserver>, onChange: (MapFilters) -> Unit, onDismiss: () -> Unit) {
    var page by remember { mutableStateOf(FilterPage.FILTERS) }
    MeshModalBottomSheet(onDismissRequest = onDismiss) {
        when (page) {
            FilterPage.OBSERVERS -> ObserverPicker(filters.observerId, observers, onBack = { page = FilterPage.FILTERS }) {
                onChange(filters.copy(observerId = it)); page = FilterPage.FILTERS
            }
            FilterPage.KEY -> MapKeyContent(onBack = { page = FilterPage.FILTERS })
            FilterPage.FILTERS -> Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
                Text("Map filters", style = MaterialTheme.typography.titleLarge)

                SectionLabel("Nodes shown")
                LastHeardRow(filters.activity) { onChange(filters.copy(activity = it)) }
                FilterFootnote("Hides nodes that haven't been heard from in this time. Live traffic and replays aren't affected.")

                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionLabel("Node roles", Modifier.weight(1f))
                    if (filters.roles.isNotEmpty()) TextButton(onClick = { onChange(filters.copy(roles = emptySet())) }) { Text("Show all") }
                }
                MAP_ROLES.forEach { role ->
                    val shown = filters.roles.isEmpty() || role in filters.roles
                    RoleRow(role.replaceFirstChar { it.uppercase() }, roleColors.getValue(role), shown) {
                        onChange(filters.copy(roles = toggledRoles(filters.roles, role)))
                    }
                }
                FilterFootnote("Colors match the dots on the map.")

                SectionLabel("Routes")
                val selected = observers.firstOrNull { it.id.equals(filters.observerId, true) }
                ListItem(modifier = Modifier.clickable { page = FilterPage.OBSERVERS },
                    leadingContent = { Icon(Icons.Outlined.SettingsInputAntenna, null) },
                    headlineContent = { Text("Heard by") },
                    supportingContent = { Text(when {
                        filters.observerId == null -> "All observers"
                        selected != null -> selected.name?.takeIf(String::isNotBlank) ?: selected.id
                        else -> "Selected observer"
                    }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    trailingContent = { Icon(Icons.Outlined.ChevronRight, null) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent))
                FilterFootnote("Shows only packets received by the chosen observer. Node markers follow the filters above.")

                if (filters.isFiltering) {
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(onClick = { onChange(MapFilters()) }, Modifier.fillMaxWidth()) {
                        Icon(Icons.Outlined.Restore, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Reset all filters")
                    }
                }

                Spacer(Modifier.height(12.dp))
                ListItem(modifier = Modifier.clickable { page = FilterPage.KEY },
                    leadingContent = { Icon(Icons.Outlined.Info, null) },
                    headlineContent = { Text("Map key") },
                    supportingContent = { Text("What the markers, lines and colors on the map mean.") },
                    trailingContent = { Icon(Icons.Outlined.ChevronRight, null) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent))
            }
        }
    }
}

private enum class FilterPage { FILTERS, OBSERVERS, KEY }

/** Every role shown is stored as no selection; the last shown role can't be turned off (iOS). */
private fun toggledRoles(selected: Set<String>, role: String): Set<String> {
    val shown = selected.ifEmpty { MAP_ROLES.toSet() }.toMutableSet()
    if (role in shown) { if (shown.size == 1) return selected; shown -= role } else shown += role
    return if (shown == MAP_ROLES.toSet()) emptySet() else shown
}

@Composable
private fun FilterFootnote(text: String) {
    Text(text, Modifier.padding(top = 4.dp, bottom = 4.dp), style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** "Last heard" with the chosen window, opening a menu of windows. */
@Composable
private fun LastHeardRow(selected: ActivityFilter, onSelect: (ActivityFilter) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        ListItem(modifier = Modifier.clickable { open = true },
            leadingContent = { Icon(Icons.Outlined.Schedule, null) },
            headlineContent = { Text("Last heard") },
            trailingContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(selected.lastHeardTitle, color = MaterialTheme.colorScheme.primary)
                    Icon(Icons.Outlined.ArrowDropDown, null, tint = MaterialTheme.colorScheme.primary)
                }
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent))
        DropdownMenu(open, { open = false }, Modifier.align(Alignment.TopEnd)) {
            ActivityFilter.entries.forEach { option ->
                DropdownMenuItem(text = { Text(option.lastHeardTitle) }, onClick = { onSelect(option); open = false },
                    trailingIcon = { if (option == selected) Icon(Icons.Outlined.Check, "Selected") })
            }
        }
    }
}

/** A role with its marker colour and whether it's shown (iOS checkmark rows). */
@Composable
private fun RoleRow(title: String, color: Color, shown: Boolean, onToggle: () -> Unit) {
    Row(Modifier.fillMaxWidth().toggleable(shown, role = Role.Checkbox) { onToggle() }.padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).background(color, CircleShape))
        Spacer(Modifier.width(14.dp))
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Icon(if (shown) Icons.Filled.CheckCircle else Icons.Outlined.Circle, null,
            tint = if (shown) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)
    }
}

/** The Map Key on its own, opened from the map's ⓘ. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MapKeySheet(onDismiss: () -> Unit) {
    MeshModalBottomSheet(onDismissRequest = onDismiss) { MapKeyContent(onBack = null) }
}

/**
 * iOS `MapKeyScreen`: what each marker, line and colour on the map means, with an ⓘ for the
 * glossary terms. [onBack] returns to the filters when opened from there.
 */
@Composable
internal fun MapKeyContent(onBack: (() -> Unit)?) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        Row(Modifier.padding(horizontal = if (onBack != null) 8.dp else 20.dp), verticalAlignment = Alignment.CenterVertically) {
            onBack?.let { IconButton(onClick = it) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back to filters") } }
            Text("Map key", style = MaterialTheme.typography.titleLarge)
        }
        Column(Modifier.padding(horizontal = 20.dp)) {
            SectionLabel("Node roles")
            listOf(
                Triple("repeater", "Repeater", "Relays packets so they travel further.") to GlossaryTerm.REPEATER,
                Triple("room", "Room", "Hosts a shared message board.") to GlossaryTerm.ROOM_SERVER,
                Triple("companion", "Companion", "Someone's radio, paired with an app.") to GlossaryTerm.COMPANION,
                Triple("sensor", "Sensor", "Reports readings over the mesh.") to GlossaryTerm.SENSOR,
            ).forEach { (item, term) ->
                val (role, title, summary) = item
                MapKeyRow(title, summary, term) { MapKeyDot(roleColors.getValue(role)) }
            }
            SectionLabel("On the map")
            val density = androidx.compose.ui.platform.LocalDensity.current.density
            val observer = remember(density) {
                observerMarkerBitmap(MARKER_ROLE_COLORS["repeater"], density).asImageBitmap()
            }
            MapKeyRow("Observer", "Heard the packet in a replay. A ring shows the node it's on.", GlossaryTerm.OBSERVER) {
                androidx.compose.foundation.Image(observer, null)
            }
            MapKeyRow("Grouped nodes", "Nearby nodes when zoomed out. Tap to zoom in.") {
                Box(Modifier.size(24.dp).background(Color(0xFF21465E), CircleShape)
                    .border(1.dp, Color(0xFF6994AF), CircleShape), contentAlignment = Alignment.Center) {
                    Text("12", color = Color.White, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                }
            }
            MapKeyRow("Spread-out nodes", "Nodes at the same spot, shown apart so each can be tapped.") {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    MapKeyDot(roleColors.getValue("repeater")); MapKeyDot(roleColors.getValue("room"))
                }
            }
            MapKeyRow("Live packets", "Each packet has its own color, on every path it took.") {
                MapKeyLines(listOf(Color(0xFF66B9FF), Color(0xFFFFC27A)), curved = false)
            }
            MapKeyRow("Replayed route", "A packet's path, played hop by hop.", GlossaryTerm.ROUTE) {
                MapKeyLines(listOf(Color(0xFFFFA833)), curved = true)
            }
            MapKeyRow("You", "Your location, when you share it.") {
                Box(Modifier.size(26.dp).background(Color(0x2E1A73E8), CircleShape), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(14.dp).background(Color.White, CircleShape), contentAlignment = Alignment.Center) {
                        Box(Modifier.size(10.dp).background(Color(0xFF1A73E8), CircleShape))
                    }
                }
            }
        }
    }
}

@Composable
private fun MapKeyRow(title: String, summary: String, term: GlossaryTerm? = null, symbol: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(width = 34.dp, height = 30.dp), contentAlignment = Alignment.Center) { symbol() }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        term?.let { LearnButton(it) }
    }
}

@Composable
private fun MapKeyDot(color: Color) {
    Box(Modifier.size(12.dp).background(color, CircleShape).border(2.dp, Color.White.copy(alpha = 0.9f), CircleShape))
}

/** Short packet paths with their travelling head, as drawn on the map. */
@Composable
private fun MapKeyLines(colors: List<Color>, curved: Boolean) {
    androidx.compose.foundation.Canvas(Modifier.size(width = 34.dp, height = 30.dp)) {
        colors.forEachIndexed { index, color ->
            val y = if (colors.size == 1) size.height * 0.6f else size.height * (0.35f + 0.35f * index)
            val path = androidx.compose.ui.graphics.Path().apply {
                moveTo(size.width * 0.08f, y)
                if (curved) quadraticTo(size.width * 0.5f, y - size.height * 0.55f, size.width * 0.92f, y)
                else lineTo(size.width * 0.92f, y - size.height * 0.12f)
            }
            drawPath(path, color.copy(alpha = 0.18f), style = androidx.compose.ui.graphics.drawscope.Stroke(6.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round))
            drawPath(path, color.copy(alpha = 0.85f), style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round))
            drawCircle(Color.White, 3.5.dp.toPx(), androidx.compose.ui.geometry.Offset(size.width * 0.92f, if (curved) y else y - size.height * 0.12f))
            drawCircle(color, 3.5.dp.toPx(), androidx.compose.ui.geometry.Offset(size.width * 0.92f, if (curved) y else y - size.height * 0.12f),
                style = androidx.compose.ui.graphics.drawscope.Stroke(1.5.dp.toPx()))
        }
    }
}

/** iOS `MapObserverPickerSheet`: search by name, ID or region; choosing returns to the filters. */
@Composable
private fun ObserverPicker(selectedId: String?, observers: List<MeshObserver>, onBack: () -> Unit, onSelect: (String?) -> Unit) {
    var query by remember { mutableStateOf("") }
    val matches = remember(query, observers) {
        val q = query.trim()
        if (q.isEmpty()) observers else observers.filter { o ->
            listOf(o.name, o.id, o.iata).any { it?.contains(q, ignoreCase = true) == true }
        }
    }
    Column(Modifier.fillMaxWidth().fillMaxHeight(0.85f)) {
        Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back to filters") }
            Text("Route observer", style = MaterialTheme.typography.titleLarge)
        }
        Box(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) { BrowseSearchField(query, { query = it }, "Name, ID or region") }
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                PickerRow("All observers", "Show routes received by any observer", selectedId == null) { onSelect(null) }
            }
            items(matches, key = { it.id }) { observer ->
                PickerRow(observer.name?.takeIf(String::isNotBlank) ?: observer.id, observer.iata, observer.id.equals(selectedId, true)) { onSelect(observer.id) }
            }
        }
    }
}

@Composable
private fun PickerRow(title: String, subtitle: String?, selected: Boolean, onClick: () -> Unit) {
    ListItem(modifier = Modifier.selectable(selected, role = Role.RadioButton, onClick = onClick),
        headlineContent = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = subtitle?.takeIf(String::isNotBlank)?.let { { Text(it) } },
        trailingContent = { if (selected) Icon(Icons.Outlined.Check, "Selected") })
}

/** iOS `MapRouteDetailsSheet`: summary, message and each stop; known nodes open their details. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RouteDetailsSheet(details: RouteDetails, onNode: (String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val now = rememberNow()
    val packet = details.packet
    val age = details.observedAt?.let { relativeTime(Instant.ofEpochMilli(it), now) }
    MeshModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp)) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Route details", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    IconButton(onClick = {
                        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                            .putExtra(Intent.EXTRA_SUBJECT, "NodeScope Route").putExtra(Intent.EXTRA_TEXT, details.shareText(age)), "Share route"))
                    }) { Icon(Icons.Outlined.Share, "Share route") }
                }
                SectionLabel("Summary")
                SummaryRow("Hops", details.hopCount.toString(), GlossaryTerm.HOP)
                age?.let { SummaryRow("Age", it) }
                packet.observerName?.takeIf(String::isNotBlank)?.let { SummaryRow("Observer", it, GlossaryTerm.OBSERVER) }
                packet.snr?.let { SummaryRow("SNR", String.format(Locale.US, "%.1f dB", it), GlossaryTerm.SNR) }
                packet.rssi?.let { SummaryRow("RSSI", String.format(Locale.US, "%.0f dBm", it), GlossaryTerm.RSSI) }
                packet.hash.takeIf(String::isNotBlank)?.let { SummaryRow("Packet", it.take(12).uppercase()) }
                packet.payloadText?.trim()?.takeIf(String::isNotEmpty)?.let { message ->
                    SectionLabel("Message")
                    var expanded by remember { mutableStateOf(false) }
                    packet.payloadChannel?.takeIf(String::isNotBlank)?.let {
                        Text(it, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                    }
                    details.sender?.takeIf(String::isNotBlank)?.let {
                        Text(it, style = MaterialTheme.typography.titleSmall, fontFamily = MonaspaceNeon)
                    }
                    Text(message, maxLines = if (expanded) Int.MAX_VALUE else 4, overflow = TextOverflow.Ellipsis)
                    if (message.length > 180) TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(0.dp)) {
                        Text(if (expanded) "Show less" else "Show more")
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionLabel("Route")
                    LearnButton(GlossaryTerm.ROUTE, Modifier.padding(top = 8.dp))
                }
            }
            items(details.stops, key = { it.position }) { stop ->
                val key = stop.publicKey
                ListItem(modifier = if (key != null) Modifier.clickable { onNode(key) } else Modifier,
                    leadingContent = {
                        Box(Modifier.size(28.dp).background(if (stop.receiver) MaterialTheme.colorScheme.secondaryContainer
                            else MaterialTheme.colorScheme.primaryContainer, CircleShape), contentAlignment = Alignment.Center) {
                            if (stop.receiver) Icon(Icons.Outlined.SettingsInputAntenna, "Receiving observer", Modifier.size(16.dp))
                            else Text("${stop.position}", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                        }
                    },
                    headlineContent = { Text(stop.title) },
                    supportingContent = when {
                        stop.receiver -> ({ Text("Received by") })
                        key != null -> ({ Text(key, fontFamily = MonaspaceNeon, maxLines = 1, overflow = TextOverflow.Ellipsis) })
                        else -> null
                    },
                    trailingContent = if (key != null) ({ Icon(Icons.Outlined.ChevronRight, null) }) else null,
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent))
            }
        }
    }
}

@Composable
private fun SummaryRow(label: String, value: String, learn: GlossaryTerm? = null) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (learn != null) LearnLabel(label, learn, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        else Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

/** A quick map selection keeps the user in context until they choose full details. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MapNodeSelectionSheet(node: org.nodescope.android.core.model.MeshNode, onDetails: () -> Unit, onDismiss: () -> Unit) {
    val now = rememberNow()
    MeshModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
                    Icon(org.nodescope.android.feature.nodes.roleIcon(node.role), null, Modifier.padding(12.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer)
                }
                Column(Modifier.weight(1f)) {
                    Text(node.displayName, style = MaterialTheme.typography.titleLarge)
                    Text(node.role.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, "Dismiss node selection") }
            }
            Text("Last heard ${relativeTime(org.nodescope.android.core.model.parseInstant(node.lastSeen), now)}",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = onDetails, modifier = Modifier.fillMaxWidth()) {
                Text("View node details"); Spacer(Modifier.width(8.dp)); Icon(Icons.Outlined.ChevronRight, null)
            }
        }
    }
}
