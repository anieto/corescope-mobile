package org.nodescope.android.feature.packets

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import org.nodescope.android.core.design.*
import org.nodescope.android.core.model.MeshNode
import org.nodescope.android.core.network.LiveFeedState

/**
 * iOS `LivePacketDetailScreen`: packet facts, payload, every observation, the route hop by hop
 * (with node names where known) and a map replay of a resolved route, then the raw bytes.
 */
@Composable
fun PacketDetailScreen(feed: LiveFeedState, groupId: String, nodes: List<MeshNode>,
    /** The packet's routes from its full observation list; "Recent" rows carry only one. */
    loadRoutes: suspend (hash: String) -> List<RouteOption> = { emptyList() },
    onReplay: (routes: List<RouteOption>, selected: Int) -> Unit) {
    // Keep the last known version if the transmission ages out of the bounded feed while open.
    var group by remember { mutableStateOf<TransmissionGroup?>(null) }
    val current = remember(feed.visiblePackets, feed.observers) { groupTransmissions(feed.visiblePackets, null, feed.observers).firstOrNull { it.id == groupId } }
    if (current != null) group = current
    val shown = group ?: return Box(Modifier.fillMaxSize().padding(24.dp)) {
        Text("This packet is no longer in the recent feed.", style = MaterialTheme.typography.bodyMedium)
    }
    val latest = shown.latest
    var fullRoutes by remember { mutableStateOf<List<RouteOption>>(emptyList()) }
    LaunchedEffect(latest.hash) { if (latest.hash.isNotBlank()) fullRoutes = loadRoutes(latest.hash) }
    // The feed's routes come first, so a route already chosen stays put as more load.
    val routes = remember(shown, fullRoutes) { mergeRouteOptions(shown.routeOptions(), fullRoutes) }
    var selectedRoute by rememberSaveable { mutableIntStateOf(0) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    // Packet, payload (when there is one), observations, then the route.
    val routeItem = if (latest.payloadText != null || latest.payloadName != null) 3 else 2
    val nodesByKey = remember(nodes) { nodes.associateBy { it.publicKey.lowercase() } }
    val tone = packetTone(shown.typeName)

    LazyColumn(Modifier.fillMaxSize(), listState, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            DetailSection("Packet") {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(packetIcon(shown.typeName), null, tint = tone)
                    Text(shown.typeName, style = MaterialTheme.typography.titleMedium, color = tone)
                }
                DetailRow("Hash", latest.hash.ifEmpty { "Unavailable" }, monospace = true)
                latest.payloadVersion?.let { DetailRow("Payload version", it.toString()) }
                DetailRow("Observations", shown.observationCount.toString())
                DetailRow("Received", DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(shown.latestAt)) +
                    if (shown.isLive) "" else " (recent history)")
                shown.region?.let { DetailRow("Region", it) }
            }
        }
        (latest.payloadText ?: latest.payloadName)?.let { payload ->
            item { DetailSection("Payload") { SelectionContainer { Text(payload, style = MaterialTheme.typography.bodyLarge) } } }
        }
        item {
            DetailSection("Observations") {
                shown.observations.forEachIndexed { index, observation ->
                    if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    // An observer whose path is one of the routes shows it below when tapped.
                    val routeIndex = routeIndexFor(observation.resolvedPath, routes)
                    val onRoute = routeIndex != null && routeIndex == selectedRoute.coerceIn(routes.indices.takeIf { !it.isEmpty() } ?: 0..0)
                    Row(Modifier.fillMaxWidth().then(if (routeIndex == null) Modifier else Modifier.clickable(onClickLabel = "Show this observer's route") {
                        selectedRoute = routeIndex; scope.launch { listState.animateScrollToItem(routeItem) }
                    }).semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).padding(vertical = 2.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(observation.title, style = MaterialTheme.typography.titleSmall)
                            Text(listOfNotNull(observation.snr?.let { "%.1f dB".format(it) }, observation.rssi?.let { "%.0f dBm".format(it) },
                                observation.hops.size.takeIf { it > 0 }?.let { "$it hops" }).joinToString(" · ").ifEmpty { "No signal data" },
                                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (routeIndex != null) RouteTag(onRoute)
                    }
                }
            }
        }
        if (shown.longestPath.isNotEmpty() || routes.isNotEmpty()) item {
            DetailSection("Route") {
                if (routes.size > 1) RoutePicker(routes, selectedRoute, { selectedRoute = it })
                // Resolved routes carry full keys; otherwise show the path's hash prefixes.
                val hops: List<Pair<String, MeshNode?>> = routes.getOrNull(selectedRoute)?.keys?.map { it to nodesByKey[it.lowercase()] }
                    ?: shown.longestPath.map { (it ?: "?") to null }
                hops.forEachIndexed { index, (value, node) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Hop ${index + 1}", Modifier.width(56.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(node?.displayName ?: "Unknown node", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(value.take(8).uppercase(), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                routes.getOrNull(selectedRoute.coerceIn(routes.indices.takeIf { !it.isEmpty() } ?: 0..0))?.let { RouteHearers(it) }
                val playable = routes.getOrNull(selectedRoute.coerceIn(routes.indices.takeIf { !it.isEmpty() } ?: 0..0))
                    ?.let { route -> routeSubchains(route.keys, nodes).any { it.size > 1 } } == true
                if (routes.isNotEmpty() && !playable) Text("This route's nodes aren't on the current map region, so it can't be replayed.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (routes.isNotEmpty()) Button(onClick = { onReplay(routes, selectedRoute.coerceIn(routes.indices)) }, Modifier.fillMaxWidth(), enabled = playable,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary)) {
                    Icon(Icons.Outlined.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text("Replay on map")
                }
            }
        }
        latest.rawHex?.let { raw ->
            item { DetailSection("Raw packet") { SelectionContainer { Text(raw, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelMedium) } } }
        }
    }
}

@Composable
private fun DetailSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            content()
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String, monospace: Boolean = false) {
    Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SelectionContainer {
            Text(value, style = MaterialTheme.typography.bodyMedium, fontFamily = if (monospace) FontFamily.Monospace else null, textAlign = TextAlign.Start)
        }
    }
}

/** On an observer row: "Shown below" when its route is the one shown, otherwise "Show route". */
@Composable
private fun RouteTag(shown: Boolean) {
    Surface(shape = MaterialTheme.shapes.small, color = if (shown) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
        border = if (shown) null else androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(if (shown) Icons.Outlined.Check else Icons.Outlined.Route, null, Modifier.size(14.dp),
                tint = if (shown) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
            Text(if (shown) "Shown below" else "Show route", style = MaterialTheme.typography.labelSmall,
                color = if (shown) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
