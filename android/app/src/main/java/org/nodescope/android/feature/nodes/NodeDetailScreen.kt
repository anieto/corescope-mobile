package org.nodescope.android.feature.nodes

import org.nodescope.android.core.design.nodeRoleLabel
import kotlinx.coroutines.launch
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import org.nodescope.android.R
import org.nodescope.android.core.design.withDescription
import org.nodescope.android.feature.learn.*
import org.nodescope.android.core.design.meshCardColors
import org.nodescope.android.core.design.MeshModalBottomSheet
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
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
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import org.nodescope.android.core.design.*
import org.nodescope.android.core.model.*
import org.nodescope.android.core.network.Loaded
import org.nodescope.android.core.network.AccountException
import org.nodescope.android.core.network.CoreScopeAccountClient
import kotlinx.coroutines.CancellationException
import org.nodescope.android.core.network.endpoint

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
    onViewed: (MeshNode) -> Unit = {}, onNodeCheck: (() -> Unit)? = null,
    refreshOnOpen: Boolean = false, onWatchNode: ((MeshNode?) -> Unit)? = null,
) {
    // Without a network layer (previews/tests) only the identity card is shown.
    val health = model?.health?.state?.collectAsStateWithLifecycle()?.value ?: Loaded()
    val reach = model?.reach?.state?.collectAsStateWithLifecycle()?.value ?: Loaded()
    val paths = model?.paths?.state?.collectAsStateWithLifecycle()?.value ?: Loaded()
    val neighbors = model?.neighbors?.state?.collectAsStateWithLifecycle()?.value ?: Loaded()
    val beaconHealth = model?.beaconHealth?.state?.collectAsStateWithLifecycle()?.value ?: Loaded()
    val nodeCheck = model?.nodeCheck?.state?.collectAsStateWithLifecycle()?.value ?: Loaded()
    val key = host to publicKey
    // Beacon has no health, reach or path statistics; it reports neighbors instead.
    val beacon = LocalBeaconSource.current
    LaunchedEffect(model, key, beacon, refreshOnOpen) {
        model?.load(host, publicKey, force = refreshOnOpen, beacon = beacon)
    }
    val metadata = remember(model, key, beacon) { mutableStateOf<RepeaterMetadata?>(null) }
    LaunchedEffect(model, key, beacon) {
        if (!beacon) metadata.value = model?.repeaterMetadata(host, publicKey)
    }
    val now = rememberNow()
    // Never show another node's (or analyzer's) sections while this one loads.
    val sections = (if (beacon) listOf(neighbors, beaconHealth) else listOf(health, reach, paths))
        .map { if (it.key == key) it else Loaded(loading = model != null) }
    val healthValue = health.value?.takeIf { health.key == key && !beacon }
    val reachValue = reach.value?.takeIf { reach.key == key && !beacon }
    val pathsValue = paths.value?.takeIf { paths.key == key && !beacon }
    val neighborsValue = neighbors.value?.takeIf { neighbors.key == key && beacon }
    val beaconHealthValue = beaconHealth.value?.takeIf { beaconHealth.key == key && beacon }
    val node = healthValue?.node ?: initial
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var addingWatch by remember(key) { mutableStateOf(false) }
    var confirmsWatch by remember(key) { mutableStateOf(false) }
    var watchFeedback by remember(key) { mutableStateOf<UiText?>(null) }
    watchFeedback?.let { feedback ->
        AlertDialog(
            onDismissRequest = { watchFeedback = null },
            title = { Text(stringResource(R.string.account_watch_node)) },
            text = { Text(feedback.asString()) },
            confirmButton = {
                TextButton(onClick = { watchFeedback = null }) { Text(stringResource(android.R.string.ok)) }
            },
        )
    }
    if (confirmsWatch && node != null && onWatchNode != null) {
        val watch = onWatchNode
        val name = node.name?.takeIf { it.isNotBlank() } ?: stringResource(R.string.account_watch_this_node)
        AlertDialog(
            onDismissRequest = { confirmsWatch = false },
            title = { Text(stringResource(R.string.account_watch_confirm_title)) },
            text = { Text(stringResource(R.string.account_watch_confirm_message, name)) },
            dismissButton = {
                TextButton(onClick = { confirmsWatch = false }) { Text(stringResource(android.R.string.cancel)) }
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmsWatch = false
                    if (!addingWatch) {
                        addingWatch = true
                        scope.launch {
                            try {
                                val client = CoreScopeAccountClient.forSource(context, host)
                                if (!client.hasSession) {
                                    watch(node)
                                } else if (client.accountCapabilities()?.notifications != true) {
                                    watchFeedback = UiText.Resource(R.string.account_alerts_unavailable)
                                } else {
                                    val user = client.me()
                                    client.setWatch(publicKey, true, user.csrfToken)
                                    watchFeedback = UiText.Resource(
                                        R.string.account_watch_confirmation, node.name?.takeIf { it.isNotBlank() }
                                            ?: context.getString(R.string.account_watch_this_node),
                                    )
                                }
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (error: AccountException) {
                                if (error.kind == AccountException.Kind.SIGNED_OUT) watch(node)
                                else watchFeedback = error.displayText
                            } catch (_: Exception) {
                                watchFeedback = UiText.Resource(R.string.account_watch_failed)
                            } finally {
                                addingWatch = false
                            }
                        }
                    }
                }) { Text(stringResource(R.string.account_watch_node)) }
            },
        )
    }
    val coreScopeUrl = remember(host, publicKey, node?.role, beacon) {
        if (!beacon && node?.role.equals("repeater", ignoreCase = true) &&
            publicKey.matches(Regex("[0-9a-fA-F]{64}"))) {
            runCatching {
                endpoint(host).newBuilder()
                    .fragment("/nodes/${publicKey.lowercase()}?section=node-repeater-info")
                    .build().toString()
            }.getOrNull()
        } else null
    }
    var canClaim by remember(coreScopeUrl) { mutableStateOf(false) }
    var confirmClaim by remember(coreScopeUrl) { mutableStateOf(false) }
    val uri = LocalUriHandler.current
    LifecycleResumeEffect(coreScopeUrl, model) {
        val lookup = scope.launch {
            val wasUnclaimed = canClaim
            canClaim = false
            if (coreScopeUrl != null && model != null) {
                canClaim = model.repeaterIsUnclaimed(host, publicKey)
                if (wasUnclaimed && !canClaim) {
                    metadata.value = model.repeaterMetadata(host, publicKey, force = true)
                }
            }
        }
        onPauseOrDispose { lookup.cancel() }
    }
    if (confirmClaim && canClaim && coreScopeUrl != null) {
        AlertDialog(
            onDismissRequest = { confirmClaim = false },
            title = { Text(stringResource(R.string.repeater_claim_confirmation)) },
            text = { Text(stringResource(R.string.repeater_claim_guidance)) },
            confirmButton = {
                TextButton(onClick = { confirmClaim = false; uri.openUri(coreScopeUrl) }) {
                    Text(stringResource(R.string.repeater_continue_to_corescope))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmClaim = false }) { Text(stringResource(android.R.string.cancel)) }
            },
        )
    }
    // Nodes opened from links may only be known once health loads; record them for Recently Viewed.
    LaunchedEffect(node?.publicKey) { node?.let(onViewed) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (model != null) item {
            LoadStatus(combinedStatus(sections),
                hasContent = healthValue != null || reachValue != null || pathsValue != null || neighborsValue != null ||
                    beaconHealthValue != null, now) {
                model.load(host, publicKey, force = true, beacon = beacon)
                if (!beacon) scope.launch { metadata.value = model.repeaterMetadata(host, publicKey, force = true) }
            }
        }
        item {
            if (node != null) IdentityCard(
                node, publicKey, favorite, now, onFavorite, onMap,
                coreScopeUrl = coreScopeUrl,
                addingWatch = addingWatch,
                onWatch = onWatchNode?.let { { confirmsWatch = true } },
            )
            else if (sections[0].loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            else Text(stringResource(R.string.node_detail_this_node_is_not_in_the_loaded_results, host), style = MaterialTheme.typography.bodyMedium)
        }
        metadata.value?.let { item(key = "repeater-metadata:$host:$publicKey") { RepeaterMetadataCard(it, coreScopeUrl) } }
        if (canClaim && coreScopeUrl != null) item {
            Button(
                onClick = { confirmClaim = true },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = ActionBlue, contentColor = Color.White),
            ) {
                Icon(Icons.Outlined.PersonAdd, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.repeater_claim))
            }
        }
        if (!beacon) onNodeCheck?.let { open ->
            item {
                org.nodescope.android.feature.nodecheck.NodeCheckLinkCard(
                    nodeCheck.value?.takeIf { nodeCheck.key == key }, loading = nodeCheck.key != key || nodeCheck.loading, onClick = open)
            }
        }
        reachValue?.let { value -> item { ReachCard(value) } }
            ?: neighborsValue?.let { value -> item { BeaconReachCard(value.neighbors.size) } }
        healthValue?.stats?.let { stats -> item { HealthCard(stats) } }
        beaconHealthValue?.takeIf { it.hasActivity }?.let { value -> item { BeaconHealthCard(value, now) } }
        onAnalytics?.let { open -> item { AnalyticsLinkCard(open) } }
        healthValue?.observers?.takeIf { it.isNotEmpty() }?.let { observers ->
            item { ObserversCard(observers.sortedByDescending { it.packetCount }, onObserver) }
        }
        beaconHealthValue?.heardIn?.takeIf { it.isNotEmpty() }?.let { regions -> item { HeardInCard(regions, now) } }
        reachValue?.links?.takeIf { it.isNotEmpty() }?.let { links -> item { LinksCard(links, onNode) } }
        neighborsValue?.neighbors?.let { list -> item { if (list.isEmpty()) NeighborsEmptyCard() else NeighborsCard(list, onNode) } }
        pathsValue?.takeIf { it.paths.isNotEmpty() }?.let { value -> item { PathsCard(value) } }
    }
}

@Composable
private fun IdentityCard(
    node: MeshNode, publicKey: String, favorite: Boolean, now: Long,
    onFavorite: (MeshNode) -> Unit, onMap: (MeshNode) -> Unit, coreScopeUrl: String?, addingWatch: Boolean, onWatch: (() -> Unit)?,
) {
    val uri = LocalUriHandler.current
    val resources = LocalResources.current
    val copy = rememberCopyAction()
    val context = LocalContext.current
    var menuOpen by remember { mutableStateOf(false) }
    var meshCoreContact by remember { mutableStateOf<MeshCoreContactLink?>(null) }
    var identityExpanded by rememberSaveable(publicKey) { mutableStateOf(false) }
    DetailCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(44.dp).background(roleColor(node.role), CircleShape), contentAlignment = Alignment.Center) {
                Icon(roleIcon(node.role), null, tint = Color(0xFF14243A))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(node.name?.takeIf(String::isNotBlank) ?: stringResource(R.string.node_detail_unnamed_node), style = MaterialTheme.typography.titleLarge)
                val roleTerm = GlossaryTerm.forRole(node.role)
                if (roleTerm != null) LearnLabel(nodeRoleLabel(node.role), roleTerm, style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                else Text(nodeRoleLabel(node.role), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                // Beacon may not know when a node was last seen; never show "Seen Unknown".
                parseInstant(node.lastSeen)?.let { seen ->
                    LearnLabel(stringResource(R.string.node_detail_seen, relativeTime(seen, now)), GlossaryTerm.LAST_HEARD, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            onWatch?.let {
                val label = stringResource(if (addingWatch) R.string.account_watch_adding else R.string.account_watch_node)
                IconButton(onClick = it, enabled = !addingWatch, modifier = Modifier.withDescription(label) { description ->
                    contentDescription = description
                }) {
                    if (addingWatch) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Outlined.NotificationsNone, null)
                }
            }
            IconToggleButton(checked = favorite, onCheckedChange = { onFavorite(node) }) {
                Icon(if (favorite) Icons.Outlined.Star else Icons.Outlined.StarBorder,
                    if (favorite) stringResource(R.string.node_detail_remove_favorite) else stringResource(R.string.node_detail_save_node))
            }
            Box {
                IconButton(onClick = { menuOpen = true }) { Icon(Icons.Outlined.MoreVert, stringResource(R.string.node_detail_node_actions)) }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.node_detail_copy_key)) }, leadingIcon = { Icon(Icons.Outlined.ContentCopy, null) },
                        onClick = { menuOpen = false; copy(resources.getString(R.string.node_detail_public_key_2), publicKey, false) })
                    MeshCoreContactLink.from(node.name, publicKey, node.role)?.let { contact ->
                        DropdownMenuItem(text = { Text(stringResource(R.string.node_detail_add_to_meshcore)) }, leadingIcon = { Icon(Icons.Outlined.QrCode2, null) },
                            onClick = { menuOpen = false; meshCoreContact = contact })
                    }
                    coreScopeUrl?.let { url ->
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.repeater_open_in_corescope)) },
                            leadingIcon = { Icon(Icons.AutoMirrored.Outlined.OpenInNew, null) },
                            onClick = { menuOpen = false; uri.openUri(url) },
                        )
                    }
                    DropdownMenuItem(text = { Text(stringResource(R.string.common_share)) }, leadingIcon = { Icon(Icons.Outlined.Share, null) }, onClick = {
                        menuOpen = false
                        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                            .putExtra(Intent.EXTRA_TEXT, NodeScopeLink.node(publicKey).url), resources.getString(R.string.node_detail_share_node_link)))
                    })
                }
            }
        }
        meshCoreContact?.let { MeshCoreContactSheet(it, onDismiss = { meshCoreContact = null }) }
        if (node.coordinate != null) Button(onClick = { onMap(node) }) {
            Icon(Icons.Outlined.Map, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.node_detail_show_on_map))
        }
        TextButton(onClick = { identityExpanded = !identityExpanded }) {
            Text(if (identityExpanded) stringResource(R.string.node_detail_hide_technical_identity) else stringResource(R.string.node_detail_technical_identity))
            Spacer(Modifier.width(4.dp))
            Icon(if (identityExpanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null)
        }
        if (identityExpanded) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text(stringResource(R.string.node_detail_public_key), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SelectionContainer { Text(publicKey.uppercase(), fontFamily = MonaspaceNeon, style = MaterialTheme.typography.bodySmall) }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                DateMetric(stringResource(R.string.node_detail_first_seen), node.firstSeen, Modifier.weight(1f))
                DateMetric(stringResource(R.string.node_detail_last_seen), node.lastSeen, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun AnalyticsLinkCard(onClick: () -> Unit) {
    Card(onClick = onClick, colors = meshCardColors()) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.size(48.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.13f), CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.QueryStats, null, tint = MaterialTheme.colorScheme.primary)
            }
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.node_detail_node_analytics), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.node_detail_activity_signal_coverage_and_peer_trends), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Outlined.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun DateMetric(label: String, value: String?, modifier: Modifier) {
    Column(modifier.semantics(mergeDescendants = true) {}) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(parseInstant(value)?.let { DateTimeFormatter.ofPattern("MMM d, yyyy").withZone(ZoneId.systemDefault()).format(it) } ?: stringResource(R.string.node_detail_unknown),
            style = MaterialTheme.typography.titleSmall)
    }
}

@Composable
private fun ReachCard(reach: NodeReach) {
    DetailCard(stringResource(R.string.node_detail_mesh_reach), Icons.Outlined.Hub) {
        reach.importance?.let { importance ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MetricTile(importance.neighborDegree.toString(), stringResource(R.string.node_detail_neighbors), Modifier.weight(1f))
                MetricTile(importance.bidirectionalLinks.toString(), stringResource(R.string.node_detail_two_way_links), Modifier.weight(1f))
                MetricTile(importance.directObservers.toString(), stringResource(R.string.node_detail_observers), Modifier.weight(1f))
            }
        }
        reach.window?.let { Text(stringResource(R.string.node_detail_measured_across_a_day_network_window, it.days), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

/** Beacon fills the Mesh reach card with what it reports: a neighbor count, not direction or observers. */
@Composable
private fun BeaconReachCard(neighborCount: Int) {
    DetailCard(stringResource(R.string.node_detail_mesh_reach), Icons.Outlined.Hub) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MetricTile(neighborCount.toString(), stringResource(R.string.node_detail_neighbors), Modifier.weight(1f))
            MetricTile("—", stringResource(R.string.node_detail_two_way_links), Modifier.weight(1f))
            MetricTile("—", stringResource(R.string.node_detail_observers), Modifier.weight(1f))
        }
        Text(stringResource(R.string.node_detail_beacon_reports_nearby_nodes_but_not_link_direction), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun HealthCard(stats: NodeHealthStats) {
    DetailCard(stringResource(R.string.node_detail_health), Icons.Outlined.MonitorHeart) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MetricTile(stats.totalTransmissions?.let(::compactCount) ?: "—", stringResource(R.string.node_detail_transmissions), Modifier.weight(1f))
            MetricTile(stats.packetsToday?.let(::compactCount) ?: "—", stringResource(R.string.node_detail_today), Modifier.weight(1f))
            MetricTile(stats.avgSnr?.let { "%.1f".format(it) } ?: "—", stringResource(R.string.node_detail_avg_snr), Modifier.weight(1f), GlossaryTerm.SNR)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            stats.totalObservations?.let { MetricChip(stringResource(R.string.node_detail_observations, compactCount(it)), Icons.Outlined.Hearing) }
            stats.avgHops?.let { MetricChip(stringResource(R.string.node_detail_1_1f_avg_hops).format(it), Icons.Outlined.Route) }
        }
    }
}

/** Beacon's counterpart to the health card: observations from Beacon's hourly totals; averages cover the seven days. */
@Composable
private fun BeaconHealthCard(health: BeaconNodeHealth, now: Long) {
    DetailCard(stringResource(R.string.node_detail_health), Icons.Outlined.MonitorHeart, GlossaryTerm.OBSERVATION) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MetricTile(health.observationsDay?.let { compactCount(it.toLong()) } ?: "—", stringResource(R.string.node_detail_24_h), Modifier.weight(1f))
            MetricTile(health.observationsWeek?.let { compactCount(it.toLong()) } ?: "—", stringResource(R.string.node_detail_7_days), Modifier.weight(1f))
            MetricTile(health.avgSnr?.let { "%.1f".format(it) } ?: "—", stringResource(R.string.node_detail_avg_snr), Modifier.weight(1f), GlossaryTerm.SNR)
        }
        if (health.avgRssi != null || health.avgHops != null) Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            health.avgRssi?.let { MetricChip(stringResource(R.string.node_detail_1_0f_dbm_avg_rssi).format(it), Icons.Outlined.Sensors) }
            health.avgHops?.let { MetricChip(stringResource(R.string.node_detail_1_1f_avg_hops).format(it), Icons.Outlined.Route) }
        }
        health.lastHeard?.let {
            Text(stringResource(R.string.node_detail_last_heard, relativeTime(java.time.Instant.ofEpochMilli(it), now)), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Where a Beacon node was heard. Beacon reports regions, not observers. */
@Composable
private fun HeardInCard(regions: List<BeaconNodeHealth.HeardIn>, now: Long) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    DetailCard(stringResource(R.string.node_detail_heard_in), Icons.Outlined.Map) {
        Column {
            regions.take(if (expanded) regions.size else 3).forEachIndexed { index, region ->
                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                Row(Modifier.fillMaxWidth().padding(vertical = 9.dp).semantics(mergeDescendants = true) {},
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(region.iata, Modifier.widthIn(min = 40.dp), style = MaterialTheme.typography.titleSmall, fontFamily = MonaspaceNeon)
                    // The name fills the middle, so the time stays right-aligned with or without it.
                    Text(region.name?.takeIf { !it.equals(region.iata, ignoreCase = true) }.orEmpty(), Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    region.lastHeard?.let {
                        Text(relativeTime(java.time.Instant.ofEpochMilli(it), now), style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        if (regions.size > 3) ExpandButton(expanded, regions.size, "regions") { expanded = !expanded }
    }
}

@Composable
private fun ObserversCard(observers: List<NodeObserverStat>, onObserver: (String, String) -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    DetailCard(stringResource(R.string.node_detail_heard_by), Icons.Outlined.Sensors, GlossaryTerm.OBSERVER) {
        Column {
            observers.take(if (expanded) observers.size else 3).forEachIndexed { index, observer ->
                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                val name = observer.observerName?.takeIf(String::isNotBlank) ?: observer.observerId.take(12)
                Row(Modifier.fillMaxWidth().clickable { onObserver(observer.observerId, name) }.padding(vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.size(32.dp).background(statusAccent(true).copy(alpha = 0.14f), CircleShape), contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.Sensors, null, Modifier.size(16.dp), tint = statusAccent(true))
                    }
                    Column(Modifier.weight(1f)) {
                        Text(name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(listOfNotNull(observer.iata, observer.avgSnr?.let { "%.1f dB".format(it) }, observer.avgRssi?.let { "%.0f dBm".format(it) })
                            .joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(stringResource(R.string.node_detail_pkts, compactCount(observer.packetCount)), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
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
    DetailCard(stringResource(R.string.node_detail_links), Icons.Outlined.Link) {
        Column {
            links.take(if (expanded) links.size else 3).forEachIndexed { index, link ->
                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                Row(Modifier.fillMaxWidth().clickable { onNode(link.pubkey) }.padding(vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(if (link.bidir) Icons.Outlined.SyncAlt else Icons.AutoMirrored.Outlined.CallMade,
                        if (link.bidir) stringResource(R.string.node_detail_two_way) else stringResource(R.string.node_detail_one_way), tint = statusAccent(link.bidir))
                    Column(Modifier.weight(1f)) {
                        Text(link.label, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(if (link.bidir) stringResource(R.string.node_detail_bidirectional) else if (link.theyHear > 0) stringResource(R.string.node_detail_heard_by_this_node_s_neighbor) else stringResource(R.string.node_detail_heard_by_this_node),
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    link.distanceKm?.let { Text(org.nodescope.android.core.design.formatDistance(it, org.nodescope.android.core.design.LocalDistanceUnit.current), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    Icon(Icons.Outlined.ChevronRight, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (links.size > 3) ExpandButton(expanded, links.size, "links") { expanded = !expanded }
    }
}

/** Beacon neighbors in the Links card's place; only a full public key opens a node. */
@Composable
private fun NeighborsCard(neighbors: List<NeighborInsight>, onNode: (String) -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    DetailCard(stringResource(R.string.node_detail_links), Icons.Outlined.Link) {
        Column {
            neighbors.take(if (expanded) neighbors.size else 3).forEachIndexed { index, neighbor ->
                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                val key = neighbor.identity.linkablePublicKey
                Row(Modifier.fillMaxWidth().let { if (key != null) it.clickable { onNode(key) } else it }.padding(vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.AutoMirrored.Outlined.CallMade, null, tint = statusAccent(true))
                    Column(Modifier.weight(1f)) {
                        Text(neighbor.name?.takeIf(String::isNotBlank) ?: neighbor.identity.displayPrefix ?: stringResource(R.string.node_detail_unknown_node),
                            style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(neighbor.signal.snr?.let { "SNR %.1f dB".format(it) } ?: stringResource(R.string.node_detail_signal_unavailable),
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (key != null) Icon(Icons.Outlined.ChevronRight, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (neighbors.size > 3) ExpandButton(expanded, neighbors.size, "links") { expanded = !expanded }
    }
}

@Composable
private fun NeighborsEmptyCard() {
    DetailCard(stringResource(R.string.node_detail_links), Icons.Outlined.Link) {
        EmptyState(Icons.Outlined.Hub, stringResource(R.string.node_detail_no_neighbors_reported), stringResource(R.string.node_detail_beacon_has_not_recorded_any_neighboring_nodes_for))
    }
}

@Composable
private fun PathsCard(value: NodePaths) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    DetailCard(stringResource(R.string.node_detail_known_paths), Icons.Outlined.Route) {
        Text(pluralStringResource(R.plurals.node_detail_resolved_route, value.totalPaths, value.totalPaths), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Column {
            value.paths.take(if (expanded) value.paths.size else 3).forEachIndexed { index, path ->
                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                Column(Modifier.padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(path.hops.joinToString(" → ") { it.label }, style = MaterialTheme.typography.bodyMedium, fontFamily = MonaspaceNeon)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MetricChip(pluralStringResource(R.plurals.node_detail_hops, path.hops.size, path.hops.size), Icons.Outlined.Route, MaterialTheme.colorScheme.primary)
                        MetricChip(stringResource(R.string.node_detail_seen_2, path.count), Icons.Outlined.Visibility, MaterialTheme.colorScheme.primary)
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
        Text(if (expanded) stringResource(R.string.node_detail_show_less) else stringResource(R.string.node_detail_show_all_2, total, noun), Modifier.weight(1f))
        Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null)
    }
}

@Composable
private fun MetricTile(value: String, label: String, modifier: Modifier, learn: GlossaryTerm? = null) {
    // Merged for TalkBack, except when it carries an ⓘ, which has to stay focusable on its own.
    Surface(if (learn == null) modifier.semantics(mergeDescendants = true) {} else modifier,
        color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.small) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(value, style = MaterialTheme.typography.titleMedium)
            if (learn != null) LearnLabel(label, learn, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            else Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun DetailCard(title: String? = null, icon: ImageVector? = null, learn: GlossaryTerm? = null, content: @Composable ColumnScope.() -> Unit) {
    Card(colors = meshCardColors()) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (title != null) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                icon?.let { Icon(it, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary) }
                Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                learn?.let { LearnButton(it, Modifier.offset(x = (-6).dp)) }
            }
            content()
        }
    }
}

/**
 * A QR code plus open/copy/share actions for a node's MeshCore contact link, matching iOS.
 * The QR is the path that works everywhere: apps that handle `meshcore://` open the link
 * directly (Android offers a chooser when several do), and other apps can scan or paste it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MeshCoreContactSheet(contact: MeshCoreContactLink, onDismiss: () -> Unit) {
    val resources = LocalResources.current
    val copy = rememberCopyAction()
    val context = LocalContext.current
    val qr = remember(contact.url) { meshCoreQrBitmap(contact.url) }
    var openFailed by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    MeshModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.node_detail_add_to_meshcore), style = MaterialTheme.typography.titleLarge)
            Image(qr.asImageBitmap(), null, filterQuality = FilterQuality.None,
                modifier = Modifier.widthIn(max = 280.dp).fillMaxWidth().aspectRatio(1f)
                    .background(Color.White, RoundedCornerShape(16.dp)).padding(14.dp)
                    .withDescription(stringResource(R.string.node_detail_qr_code_for_adding_as_a_meshcore_contact, contact.name)) { description -> contentDescription = description })
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(contact.name, style = MaterialTheme.typography.titleMedium)
                Text(contact.typeLabel.asString(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Button(onClick = {
                // A new task keeps MeshCore in its own recents entry; without it, MeshCore stacks
                // inside NodeScope's task and Back/relaunch can't return to NodeScope.
                openFailed = try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(contact.url))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); false
                } catch (_: ActivityNotFoundException) { true }
            }, Modifier.fillMaxWidth()) {
                Icon(Icons.AutoMirrored.Outlined.OpenInNew, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.node_detail_open_in_meshcore))
            }
            OutlinedButton(onClick = { copy(resources.getString(R.string.node_detail_meshcore_contact), contact.url, false); copied = true }, Modifier.fillMaxWidth()) {
                Icon(if (copied) Icons.Outlined.Check else Icons.Outlined.ContentCopy, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp)); Text(if (copied) stringResource(R.string.node_detail_copied) else stringResource(R.string.node_detail_copy_contact_link))
            }
            OutlinedButton(onClick = {
                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, contact.url), resources.getString(R.string.node_detail_share_meshcore_contact)))
            }, Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.Share, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.node_detail_share_contact_link))
            }
            if (openFailed) Text(stringResource(R.string.node_detail_no_installed_app_opens_meshcore_links_scan_the),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
            Text(stringResource(R.string.node_detail_scan_this_code_from_your_meshcore_appu2019s_add),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
    }
}

/** One pixel per module; the Image scales it up without filtering so edges stay sharp. */
private fun meshCoreQrBitmap(text: String): Bitmap {
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0,
        mapOf(EncodeHintType.MARGIN to 0, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M))
    val pixels = IntArray(matrix.width * matrix.height) { i ->
        if (matrix[i % matrix.width, i / matrix.width]) android.graphics.Color.BLACK else android.graphics.Color.WHITE
    }
    return Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
}


@Composable
private fun RepeaterMetadataCard(metadata: RepeaterMetadata, coreScopeUrl: String?) {
    val uri = LocalUriHandler.current
    var showsInfo by rememberSaveable { mutableStateOf(false) }
    if (showsInfo) {
        AlertDialog(
            onDismissRequest = { showsInfo = false },
            title = { Text(stringResource(R.string.repeater_about_details)) },
            text = { Text(stringResource(R.string.repeater_about_details_body)) },
            confirmButton = {
                if (coreScopeUrl != null) TextButton(onClick = { showsInfo = false; uri.openUri(coreScopeUrl) }) {
                    Text(stringResource(R.string.repeater_open_corescope))
                }
            },
            dismissButton = {
                TextButton(onClick = { showsInfo = false }) { Text(stringResource(android.R.string.ok)) }
            },
        )
    }
    var expanded by rememberSaveable { mutableStateOf(false) }
    val status = metadata.status?.let(::reportedStatusLabel)
    val disclosureState = stringResource(
        if (expanded) R.string.coverage_disclosure_expanded else R.string.coverage_disclosure_collapsed,
    )
    DetailCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                modifier = Modifier.weight(1f)
                    .clickable(role = Role.Button) { expanded = !expanded }
                    .semantics { stateDescription = disclosureState }
                    .heightIn(min = 48.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.repeater_details), style = MaterialTheme.typography.titleMedium)
                    status?.let {
                        Text(stringResource(it), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null)
            }

            IconButton(onClick = { showsInfo = true }) {
                Icon(Icons.Outlined.Info, stringResource(R.string.repeater_about_details),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (!expanded) return@DetailCard

        val time = DateTimeFormatter.ofLocalizedDateTime(java.time.format.FormatStyle.MEDIUM)
            .withZone(ZoneId.systemDefault())
        val numbers = java.text.NumberFormat.getNumberInstance()
        val power = when (metadata.power) {
            "mains" -> R.string.repeater_mains
            "battery" -> R.string.repeater_battery
            "solar" -> R.string.repeater_solar
            else -> null
        }
        power?.let { Text(stringResource(R.string.repeater_power, stringResource(it))) }
        metadata.heightMeters?.let {
            Text(stringResource(R.string.repeater_height))
            val unit = LocalDistanceUnit.current
            val height = org.nodescope.android.feature.linkprofile.profileDisplayHeight(it, unit)
            Text(stringResource(if (unit.usesFeet) R.string.repeater_feet else R.string.repeater_meters, numbers.format(height)),
                fontFamily = MonaspaceNeon)
        }
        metadata.gainDbi?.let {
            Text(stringResource(R.string.repeater_gain))
            if (it == 0.0) Text(stringResource(R.string.repeater_default_gain))
            else Text(stringResource(R.string.repeater_dbi, numbers.format(it)), fontFamily = MonaspaceNeon)
        }
        metadata.claimers.forEach { claim ->
            Text(stringResource(R.string.repeater_claimed_by, claim.displayName))
            claim.claimedAt?.let { Text(time.format(it), style = MaterialTheme.typography.bodySmall) }
        }
        metadata.updatedBy?.let { Text(stringResource(R.string.repeater_edited_by, it)) }
        metadata.updatedAt?.let { Text(stringResource(R.string.repeater_updated, time.format(it))) }
        metadata.notes?.let {
            Text(stringResource(R.string.repeater_notes), style = MaterialTheme.typography.titleSmall)
            SelectionContainer { Text(it) }
        }
    }
}
