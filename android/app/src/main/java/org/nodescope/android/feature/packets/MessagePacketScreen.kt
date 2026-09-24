package org.nodescope.android.feature.packets

import android.content.Intent
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import org.nodescope.android.core.design.*
import org.nodescope.android.core.model.*
import org.nodescope.android.core.network.BrowseRepository
import org.nodescope.android.core.network.KeyedLoader
import org.nodescope.android.core.network.ResponseCache

class PacketDetailViewModel(repository: BrowseRepository) : ViewModel() {
    val detail = KeyedLoader(viewModelScope, 60_000, restore = { key: Pair<String, String> -> repository.saved(ResponseCache.DETAIL_LIFETIME) { packetDetail(key.first, key.second) } }) { key -> repository.packetDetail(key.first, key.second) }
}

/**
 * iOS `PacketDetailScreen` for a channel message: the message, packet identity, route with
 * map replay, every observer and the technical data. The sender and text come from the
 * conversation, since a message decrypted on this device is still encrypted on the analyzer.
 */
@Composable
fun MessagePacketScreen(model: PacketDetailViewModel, host: String, hash: String, sender: String, text: String,
    nodes: List<MeshNode>, onReplay: (routes: List<List<String>>, selected: Int) -> Unit) {
    val state by model.detail.state.collectAsStateWithLifecycle()
    val key = host to hash
    LaunchedEffect(key) { model.detail.load(key) }
    val now = rememberNow()
    val detail = state.value.takeIf { state.key == key }
    val routes = remember(detail) { distinctRoutes(detail?.observations.orEmpty().map { it.resolvedPath.orEmpty() }) }
    var selectedRoute by rememberSaveable { mutableIntStateOf(0) }
    val copy = rememberCopyAction()
    val context = LocalContext.current

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            // Shared links carry no analyzer, so a missing packet usually belongs to another one.
            val shown = (state.takeIf { it.key == key } ?: org.nodescope.android.core.network.Loaded<Pair<String, String>, PacketDetail>(loading = true))
                .let { if (it.error?.contains("HTTP 404") == true) it.copy(error = "This packet isn't on $host. It may have come from another analyzer or aged out.") else it }
            LoadStatus(shown, detail != null, now) { model.detail.load(key, force = true) }
        }
        if (text.isNotBlank()) item {
            PacketCard("Message", Icons.Outlined.ChatBubbleOutline) {
                Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(sender, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                        SelectionContainer { Text(text, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center) }
                    }
                }
            }
        }
        item {
            val type = detail?.packet?.payloadType?.let(::payloadTypeName)
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(Modifier.size(48.dp).background(SignalBlue, MaterialTheme.shapes.medium), contentAlignment = Alignment.Center) {
                            Icon(Icons.Outlined.MonitorHeart, null, tint = MaterialTheme.colorScheme.surface)
                        }
                        Column(Modifier.weight(1f)) {
                            Text(type ?: "Loading packet", style = MaterialTheme.typography.titleLarge)
                            SelectionContainer { Text(hash.uppercase(), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        }
                        PacketActions(onCopy = { copy("Packet hash", hash, false) }, onShare = {
                            // Same link format as iOS (`nodescope://packet/<hash>`).
                            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                                .putExtra(Intent.EXTRA_TEXT, NodeScopeLink.packet(hash).url), "Share packet link"))
                        })
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MetricTile((detail?.observationCount ?: detail?.observations?.size)?.toString() ?: "—", "Observations", Icons.Outlined.Hearing, Modifier.weight(1f))
                        MetricTile(detail?.path?.size?.toString() ?: "—", "Hops", Icons.Outlined.Route, Modifier.weight(1f))
                        MetricTile(detail?.packet?.snr?.let { "%.1f".format(it) } ?: "—", "SNR dB", Icons.Outlined.GraphicEq, Modifier.weight(1f))
                    }
                }
            }
        }
        if (detail != null) {
            item {
                PacketCard("Route", Icons.Outlined.Route) {
                    if (routes.isEmpty()) Text("No mappable route was recorded for this packet.", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else {
                        val index = selectedRoute.coerceIn(routes.indices)
                        if (routes.size > 1) {
                            var open by remember { mutableStateOf(false) }
                            Box {
                                OutlinedButton(onClick = { open = true }) { Text("Route ${index + 1} · ${routes[index].size} hops"); Icon(Icons.Outlined.ArrowDropDown, null) }
                                DropdownMenu(open, { open = false }) {
                                    routes.forEachIndexed { i, route ->
                                        DropdownMenuItem(text = { Text("Route ${i + 1} · ${route.size} hops") }, onClick = { selectedRoute = i; open = false })
                                    }
                                }
                            }
                        } else Row {
                            Text("Resolved path", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            Text("${routes[0].size} hops", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        val lookup = remember(nodes) { nodes.associateBy { it.publicKey.lowercase() } }
                        Text(routes[index].joinToString(" → ") { lookup[it.lowercase()]?.displayName ?: it.take(8).uppercase() },
                            style = MaterialTheme.typography.bodyMedium)
                        val playable = routeSubchains(routes[index], nodes).any { it.size > 1 }
                        if (!playable) Text("This route's nodes aren't on the current map region, so it can't be replayed.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Button(onClick = { onReplay(routes, index) }, Modifier.fillMaxWidth(), enabled = playable,
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary)) {
                            Icon(Icons.Outlined.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text("Replay on map")
                        }
                    }
                }
            }
            if (detail.observations.isNotEmpty()) item { ObserversCard(detail.observations) }
            item { TechnicalCard(detail.packet) }
        }
    }
}

@Composable
private fun PacketActions(onCopy: () -> Unit, onShare: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Outlined.MoreVert, "Packet actions") }
        DropdownMenu(open, { open = false }) {
            DropdownMenuItem(text = { Text("Copy packet hash") }, leadingIcon = { Icon(Icons.Outlined.ContentCopy, null) }, onClick = { open = false; onCopy() })
            DropdownMenuItem(text = { Text("Share packet link") }, leadingIcon = { Icon(Icons.Outlined.Share, null) }, onClick = { open = false; onShare() })
        }
    }
}

@Composable
private fun ObserversCard(observations: List<PacketObservation>) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    PacketCard("Observers", Icons.Outlined.Sensors) {
        Column {
            observations.take(if (expanded) observations.size else 3).forEachIndexed { index, observation ->
                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp).semantics(mergeDescendants = true) {},
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.size(32.dp).background(HealthyGreen.copy(alpha = 0.13f), CircleShape), contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.Sensors, null, Modifier.size(16.dp), tint = HealthyGreen)
                    }
                    Column(Modifier.weight(1f)) {
                        Text(observation.observerName ?: "Unknown observer", style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(listOfNotNull(observation.observerIata ?: "Unknown region", observation.rssi?.let { "%.0f RSSI".format(it) }).joinToString(" · "),
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    observation.snr?.let { Text("%.1f dB".format(it), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary) }
                }
            }
        }
        if (observations.size > 3) FilledTonalButton(onClick = { expanded = !expanded }, Modifier.fillMaxWidth()) {
            Text(if (expanded) "Show less" else "Show all ${observations.size} observations", Modifier.weight(1f))
            Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null)
        }
    }
}

@Composable
private fun TechnicalCard(packet: PacketRecord) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    PacketCard("Technical data", Icons.Outlined.Code) {
        TextButton(onClick = { expanded = !expanded }) {
            Text(if (expanded) "Hide raw packet" else "Show raw packet", Modifier.weight(1f))
            Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null)
        }
        if (expanded) {
            packet.decodedJson?.let { SelectionContainer { Text(it, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelMedium) } }
            packet.rawHex?.let { SelectionContainer { Text(it.uppercase(), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant) } }
        }
    }
}

@Composable
private fun MetricTile(value: String, label: String, icon: ImageVector, modifier: Modifier) {
    Surface(modifier.semantics(mergeDescendants = true) { contentDescription = "$label: $value" }, color = SignalBlue.copy(alpha = 0.08f),
        shape = MaterialTheme.shapes.small) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(icon, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
            Text(value, style = MaterialTheme.typography.titleMedium)
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PacketCard(title: String, icon: ImageVector, content: @Composable ColumnScope.() -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(icon, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            }
            content()
        }
    }
}
