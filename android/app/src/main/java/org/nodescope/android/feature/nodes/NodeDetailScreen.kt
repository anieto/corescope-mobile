package org.nodescope.android.feature.nodes

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.CallMade
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import org.nodescope.android.core.design.*
import org.nodescope.android.core.model.*
import org.nodescope.android.core.network.Loaded

/** Role colors shared with the map markers. */
fun roleColor(role: String?): Color = when (role?.lowercase()) {
    "repeater" -> Color(0xFFFFAA44)
    "room" -> Color(0xFF299EFF)
    "companion" -> Color(0xFF45C99D)
    "sensor" -> Color(0xFFB18AFF)
    else -> Color(0xFF65DDB4)
}
fun roleIcon(role: String?): ImageVector = when (role?.lowercase()) {
    "repeater" -> Icons.Outlined.CellTower
    "room" -> Icons.Outlined.Forum
    "companion" -> Icons.Outlined.Smartphone
    "sensor" -> Icons.Outlined.Thermostat
    else -> Icons.Outlined.Router
}

/**
 * Node identity plus the iOS detail sections: mesh reach, health, observers that heard it,
 * links and known paths. [initial] comes from loaded map data or saved snapshots; the health
 * response supplies the node when it is not in the current region's results.
 */
@Composable
fun NodeDetailScreen(
    model: NodeDetailViewModel?, host: String, publicKey: String, initial: MeshNode?,
    favorite: Boolean, onFavorite: (MeshNode) -> Unit, onMap: (MeshNode) -> Unit,
    onObserver: (id: String, name: String) -> Unit, onNode: (String) -> Unit, onAnalytics: (() -> Unit)? = null,
    onViewed: (MeshNode) -> Unit = {},
) {
    // Without a network layer (previews/tests) only the identity card is shown.
    val health = model?.health?.state?.collectAsStateWithLifecycle()?.value ?: Loaded()
    val reach = model?.reach?.state?.collectAsStateWithLifecycle()?.value ?: Loaded()
    val paths = model?.paths?.state?.collectAsStateWithLifecycle()?.value ?: Loaded()
    val key = host to publicKey
    LaunchedEffect(model, key) { model?.load(host, publicKey) }
    val now = rememberNow()
    // Never show another node's (or analyzer's) sections while this one loads.
    val sections = listOf(health, reach, paths).map { if (it.key == key) it else Loaded(loading = model != null) }
    val healthValue = health.value?.takeIf { health.key == key }
    val reachValue = reach.value?.takeIf { reach.key == key }
    val pathsValue = paths.value?.takeIf { paths.key == key }
    val node = healthValue?.node ?: initial
    // Nodes opened from links may only be known once health loads; record them for Recently Viewed.
    LaunchedEffect(node?.publicKey) { node?.let(onViewed) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (model != null) item {
            LoadStatus(combinedStatus(sections),
                hasContent = healthValue != null || reachValue != null || pathsValue != null, now) { model.load(host, publicKey, force = true) }
        }
        item {
            if (node != null) IdentityCard(node, publicKey, favorite, now, onFavorite, onMap)
            else if (sections[0].loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            else Text("This node is not in the loaded results for $host.", style = MaterialTheme.typography.bodyMedium)
        }
        reachValue?.let { value -> item { ReachCard(value) } }
        healthValue?.stats?.let { stats -> item { HealthCard(stats) } }
        onAnalytics?.let { open -> item { AnalyticsLinkCard(open) } }
        healthValue?.observers?.takeIf { it.isNotEmpty() }?.let { observers ->
            item { ObserversCard(observers.sortedByDescending { it.packetCount }, onObserver) }
        }
        reachValue?.links?.takeIf { it.isNotEmpty() }?.let { links -> item { LinksCard(links, onNode) } }
        pathsValue?.takeIf { it.paths.isNotEmpty() }?.let { value -> item { PathsCard(value) } }
    }
}

@Composable
private fun IdentityCard(node: MeshNode, publicKey: String, favorite: Boolean, now: Long, onFavorite: (MeshNode) -> Unit, onMap: (MeshNode) -> Unit) {
    val copy = rememberCopyAction()
    val context = LocalContext.current
    var menuOpen by remember { mutableStateOf(false) }
    var identityExpanded by rememberSaveable(publicKey) { mutableStateOf(false) }
    DetailCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(44.dp).background(roleColor(node.role), CircleShape), contentAlignment = Alignment.Center) {
                Icon(roleIcon(node.role), null, tint = Color(0xFF14243A))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(node.name?.takeIf(String::isNotBlank) ?: "Unnamed node", style = MaterialTheme.typography.titleLarge)
                Text(node.role.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Seen ${relativeTime(parseInstant(node.lastSeen), now)}", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconToggleButton(checked = favorite, onCheckedChange = { onFavorite(node) }) {
                Icon(if (favorite) Icons.Outlined.Star else Icons.Outlined.StarBorder,
                    if (favorite) "Remove favorite" else "Save node")
            }
            Box {
                IconButton(onClick = { menuOpen = true }) { Icon(Icons.Outlined.MoreVert, "Node actions") }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(text = { Text("Copy key") }, leadingIcon = { Icon(Icons.Outlined.ContentCopy, null) },
                        onClick = { menuOpen = false; copy("Public key", publicKey, false) })
                    DropdownMenuItem(text = { Text("Share") }, leadingIcon = { Icon(Icons.Outlined.Share, null) }, onClick = {
                        menuOpen = false
                        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                            .putExtra(Intent.EXTRA_TEXT, NodeScopeLink.node(publicKey).url), "Share node link"))
                    })
                }
            }
        }
        if (node.coordinate != null) Button(onClick = { onMap(node) }) {
            Icon(Icons.Outlined.Map, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Show on map")
        }
        TextButton(onClick = { identityExpanded = !identityExpanded }) {
            Text(if (identityExpanded) "Hide technical identity" else "Technical identity")
            Spacer(Modifier.width(4.dp))
            Icon(if (identityExpanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null)
        }
        if (identityExpanded) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text("Public key", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SelectionContainer { Text(publicKey.uppercase(), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                DateMetric("First seen", node.firstSeen, Modifier.weight(1f))
                DateMetric("Last seen", node.lastSeen, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun AnalyticsLinkCard(onClick: () -> Unit) {
    Card(onClick = onClick, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.size(48.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.13f), CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.QueryStats, null, tint = MaterialTheme.colorScheme.primary)
            }
            Column(Modifier.weight(1f)) {
                Text("Node analytics", style = MaterialTheme.typography.titleMedium)
                Text("Activity, signal, coverage, and peer trends", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Outlined.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun DateMetric(label: String, value: String?, modifier: Modifier) {
    Column(modifier.semantics(mergeDescendants = true) {}) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(parseInstant(value)?.let { DateTimeFormatter.ofPattern("MMM d, yyyy").withZone(ZoneId.systemDefault()).format(it) } ?: "Unknown",
            style = MaterialTheme.typography.titleSmall)
    }
}

@Composable
private fun ReachCard(reach: NodeReach) {
    DetailCard("Mesh reach", Icons.Outlined.Hub) {
        reach.importance?.let { importance ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MetricTile(importance.neighborDegree.toString(), "Neighbors", Modifier.weight(1f))
                MetricTile(importance.bidirectionalLinks.toString(), "Two-way links", Modifier.weight(1f))
                MetricTile(importance.directObservers.toString(), "Observers", Modifier.weight(1f))
            }
        }
        reach.window?.let { Text("Measured across a ${it.days}-day network window", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun HealthCard(stats: NodeHealthStats) {
    DetailCard("Health", Icons.Outlined.MonitorHeart) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MetricTile(stats.totalTransmissions?.let(::compactCount) ?: "—", "Transmissions", Modifier.weight(1f))
            MetricTile(stats.packetsToday?.let(::compactCount) ?: "—", "Today", Modifier.weight(1f))
            MetricTile(stats.avgSnr?.let { "%.1f".format(it) } ?: "—", "Avg SNR", Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            stats.totalObservations?.let { MetricChip("${compactCount(it)} observations", Icons.Outlined.Hearing) }
            stats.avgHops?.let { MetricChip("%.1f avg hops".format(it), Icons.Outlined.Route) }
        }
    }
}

@Composable
private fun ObserversCard(observers: List<NodeObserverStat>, onObserver: (String, String) -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    DetailCard("Heard by", Icons.Outlined.Sensors) {
        Column {
            observers.take(if (expanded) observers.size else 3).forEachIndexed { index, observer ->
                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                val name = observer.observerName?.takeIf(String::isNotBlank) ?: observer.observerId.take(12)
                Row(Modifier.fillMaxWidth().clickable { onObserver(observer.observerId, name) }.padding(vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.size(32.dp).background(HealthyGreen.copy(alpha = 0.14f), CircleShape), contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.Sensors, null, Modifier.size(16.dp), tint = HealthyGreen)
                    }
                    Column(Modifier.weight(1f)) {
                        Text(name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(listOfNotNull(observer.iata, observer.avgSnr?.let { "%.1f dB".format(it) }, observer.avgRssi?.let { "%.0f dBm".format(it) })
                            .joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text("${compactCount(observer.packetCount)} pkts", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    Icon(Icons.Outlined.ChevronRight, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (observers.size > 3) ExpandButton(expanded, observers.size, "observers") { expanded = !expanded }
    }
}

@Composable
private fun LinksCard(links: List<ReachLink>, onNode: (String) -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    DetailCard("Links", Icons.Outlined.Link) {
        Column {
            links.take(if (expanded) links.size else 3).forEachIndexed { index, link ->
                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                Row(Modifier.fillMaxWidth().clickable { onNode(link.pubkey) }.padding(vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(if (link.bidir) Icons.Outlined.SyncAlt else Icons.AutoMirrored.Outlined.CallMade,
                        if (link.bidir) "Two-way" else "One way", tint = if (link.bidir) HealthyGreen else ActivityAmber)
                    Column(Modifier.weight(1f)) {
                        Text(link.label, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(if (link.bidir) "Bidirectional" else if (link.theyHear > 0) "Heard by this node's neighbor" else "Heard by this node",
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    link.distanceKm?.let { Text("%.1f km".format(it), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    Icon(Icons.Outlined.ChevronRight, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (links.size > 3) ExpandButton(expanded, links.size, "links") { expanded = !expanded }
    }
}

@Composable
private fun PathsCard(value: NodePaths) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    DetailCard("Known paths", Icons.Outlined.Route) {
        Text("${value.totalPaths} resolved route${if (value.totalPaths == 1) "" else "s"}", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Column {
            value.paths.take(if (expanded) value.paths.size else 3).forEachIndexed { index, path ->
                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                Column(Modifier.padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(path.hops.joinToString(" → ") { it.label }, style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MetricChip("${path.hops.size} hops", Icons.Outlined.Route, MaterialTheme.colorScheme.primary)
                        MetricChip("Seen ${path.count}×", Icons.Outlined.Visibility, MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
        if (value.paths.size > 3) ExpandButton(expanded, value.paths.size, "paths") { expanded = !expanded }
    }
}

@Composable
private fun ExpandButton(expanded: Boolean, total: Int, noun: String, onClick: () -> Unit) {
    FilledTonalButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Text(if (expanded) "Show less" else "Show all $total $noun", Modifier.weight(1f))
        Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null)
    }
}

@Composable
private fun MetricTile(value: String, label: String, modifier: Modifier) {
    Surface(modifier.semantics(mergeDescendants = true) {}, color = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f), shape = MaterialTheme.shapes.small) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(value, style = MaterialTheme.typography.titleMedium)
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun DetailCard(title: String? = null, icon: ImageVector? = null, content: @Composable ColumnScope.() -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (title != null) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                icon?.let { Icon(it, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary) }
                Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            }
            content()
        }
    }
}
