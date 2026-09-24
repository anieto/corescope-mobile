package org.nodescope.android.feature.map

import android.content.Intent
import androidx.compose.foundation.background
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

/** iOS `MapNodeFilterSheet`: activity, roles and the route observer, with the observer picker inside. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MapFiltersSheet(filters: MapFilters, observers: List<MeshObserver>, onChange: (MapFilters) -> Unit, onDismiss: () -> Unit) {
    var picking by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        if (picking) ObserverPicker(filters.observerId, observers, onBack = { picking = false }) {
            onChange(filters.copy(observerId = it)); picking = false
        } else Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Map filters", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                if (filters.isFiltering) TextButton(onClick = { onChange(MapFilters()) }) { Text("Reset all") }
            }
            SectionLabel("Activity")
            ActivityFilter.entries.forEach { option ->
                Row(Modifier.fillMaxWidth().selectable(filters.activity == option, role = Role.RadioButton) { onChange(filters.copy(activity = option)) }
                    .padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = filters.activity == option, onClick = null)
                    Spacer(Modifier.width(12.dp))
                    Text(option.title, style = MaterialTheme.typography.bodyLarge)
                }
            }
            SectionLabel("Node roles")
            RoleRow("All roles", null, filters.roles.isEmpty()) { onChange(filters.copy(roles = emptySet())) }
            MAP_ROLES.forEach { role ->
                RoleRow(role.replaceFirstChar { it.uppercase() }, roleColors[role], role in filters.roles) {
                    onChange(filters.copy(roles = if (role in filters.roles) filters.roles - role else filters.roles + role))
                }
            }
            SectionLabel("Route observer")
            val selected = observers.firstOrNull { it.id.equals(filters.observerId, true) }
            ListItem(modifier = Modifier.clickable { picking = true },
                leadingContent = { Icon(Icons.Outlined.SettingsInputAntenna, null) },
                headlineContent = { Text("Choose observer") },
                supportingContent = { Text(when {
                    filters.observerId == null -> "All observers"
                    selected != null -> selected.name?.takeIf(String::isNotBlank) ?: selected.id
                    else -> "Selected observer"
                }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                trailingContent = { Icon(Icons.Outlined.ChevronRight, null) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent))
            Text("Limits live and recent routes to packets received by this observer. Node markers still follow the region, activity and role filters.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun RoleRow(title: String, color: Color?, checked: Boolean, onToggle: () -> Unit) {
    Row(Modifier.fillMaxWidth().toggleable(checked, role = Role.Checkbox) { onToggle() }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = null)
        Spacer(Modifier.width(12.dp))
        if (color != null) {
            Box(Modifier.size(10.dp).background(color, CircleShape))
            Spacer(Modifier.width(8.dp))
        }
        Text(title, style = MaterialTheme.typography.bodyLarge)
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
    val age = relativeTime(Instant.ofEpochMilli(details.observedAt), now)
    ModalBottomSheet(onDismissRequest = onDismiss) {
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
                SummaryRow("Hops", details.hopCount.toString())
                SummaryRow("Age", age)
                packet.observerName?.takeIf(String::isNotBlank)?.let { SummaryRow("Observer", it) }
                packet.snr?.let { SummaryRow("SNR", String.format(Locale.US, "%.1f dB", it)) }
                packet.rssi?.let { SummaryRow("RSSI", String.format(Locale.US, "%.0f dBm", it)) }
                packet.hash.takeIf(String::isNotBlank)?.let { SummaryRow("Packet", it.take(12).uppercase()) }
                packet.payloadText?.trim()?.takeIf(String::isNotEmpty)?.let { message ->
                    SectionLabel("Message")
                    var expanded by remember { mutableStateOf(false) }
                    packet.payloadChannel?.takeIf(String::isNotBlank)?.let {
                        Text(it, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                    }
                    Text(message, maxLines = if (expanded) Int.MAX_VALUE else 4, overflow = TextOverflow.Ellipsis)
                    if (message.length > 180) TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(0.dp)) {
                        Text(if (expanded) "Show less" else "Show more")
                    }
                }
                SectionLabel("Route")
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
                    headlineContent = { Text(stop.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = when {
                        stop.receiver -> ({ Text("Received by") })
                        key != null -> ({ Text(key, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis) })
                        else -> null
                    },
                    trailingContent = if (key != null) ({ Icon(Icons.Outlined.ChevronRight, null) }) else null,
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent))
            }
        }
    }
}

@Composable
private fun SummaryRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(label, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontWeight = FontWeight.Medium)
    }
}
