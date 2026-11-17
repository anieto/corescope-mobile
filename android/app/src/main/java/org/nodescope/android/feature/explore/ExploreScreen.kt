package org.nodescope.android.feature.explore

import org.nodescope.android.core.design.nodeRoleLabel
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import org.nodescope.android.R
import org.nodescope.android.core.design.meshCardColors
import org.nodescope.android.core.design.meshCardColor
import org.nodescope.android.core.design.MeshModalBottomSheet
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.nodescope.android.core.design.*
import org.nodescope.android.core.model.*
import org.nodescope.android.core.network.LiveFeedState
import org.nodescope.android.core.network.Loaded
import org.nodescope.android.core.storage.NodeLibrary
import org.nodescope.android.core.storage.SavedItem
import org.nodescope.android.core.storage.SavedKind
import org.nodescope.android.feature.nodes.roleColor
import org.nodescope.android.feature.nodes.roleIcon
import org.nodescope.android.feature.settings.NavigationRow

/** Where Explore can take the person; wired by the app shell. */
class ExploreActions(
    val onNodePlanner: () -> Unit = {},
    val onNodeCheck: () -> Unit = {},
    val onFavorites: () -> Unit = {},
    val onActivity: () -> Unit = {},
    val onAccount: () -> Unit = {},
    val onNode: (String) -> Unit = {},
    val onObserver: (id: String, name: String) -> Unit = { _, _ -> },
    val onChannel: (id: String, name: String) -> Unit = { _, _ -> },
    val onPacket: (hash: String, sender: String, text: String) -> Unit = { _, _, _ -> },
    val onPackets: () -> Unit = {},
    val onMap: () -> Unit = {},
    val onActiveNodes: () -> Unit = {},
    val onChannels: () -> Unit = {},
    val onObservers: (activeOnly: Boolean) -> Unit = {},
)

private enum class GlanceTone(val light: Color, val dark: Color) {
    // Light-theme tones are darkened so large numbers keep readable contrast on white.
    NODES(Color(0xFF1E8A4C), HealthyGreen),
    OBSERVERS(Color(0xFF0063A6), SignalBlue),
    PACKETS(Color(0xFFB45F00), ActivityAmber),
    SNR(Color(0xFF7A4FD0), Color(0xFFB9A2FF)),
}

private fun GlanceMetric.tone() = when (this) {
    GlanceMetric.ACTIVE_NODES -> GlanceTone.NODES; GlanceMetric.OBSERVERS_ONLINE -> GlanceTone.OBSERVERS
    GlanceMetric.PACKETS -> GlanceTone.PACKETS; GlanceMetric.AVERAGE_SNR -> GlanceTone.SNR
}

private fun <T> Loaded<String, T>.valueFor(host: String): T? = value.takeIf { key == host }

/**
 * iOS `ExploreScreen`: a customizable, exportable network summary for the entire network,
 * favorites of every kind (reorderable), recently viewed items, and global search.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExploreScreen(model: ExploreViewModel?, host: String, snapshot: AnalyzerSnapshot?, feed: LiveFeedState, library: NodeLibrary,
    monitored: List<MonitoredChannel>, actions: ExploreActions) {
    val context = LocalContext.current
    val now = rememberNow()
    val nodesState = model?.nodes?.state?.collectAsStateWithLifecycle()?.value
    val observersState = model?.observers?.state?.collectAsStateWithLifecycle()?.value
    val channelsState = model?.channels?.state?.collectAsStateWithLifecycle()?.value
    val packetsState = model?.packets?.state?.collectAsStateWithLifecycle()?.value
    // Without a network layer (tests/previews), fall back to what the app already has loaded.
    val nodes = nodesState?.valueFor(host) ?: snapshot?.nodes.orEmpty()
    val observers = observersState?.valueFor(host) ?: feed.observers
    val channels = channelsState?.valueFor(host).orEmpty()
    val packets = packetsState?.valueFor(host) ?: feed.packets
    val loading = listOfNotNull(nodesState, observersState, channelsState, packetsState).any { it.loading }
    LaunchedEffect(model, host) {
        model ?: return@LaunchedEffect
        model.load(host)
        // Keep saved node snapshots current while Explore is open (iOS refreshes every minute).
        while (true) { delay(60_000); model.nodes.load(host, force = true) }
    }
    LaunchedEffect(nodes) { if (nodes.isNotEmpty()) library.refreshNodes(nodes) }

    var searching by remember { mutableStateOf(false) }
    var customizing by remember { mutableStateOf(false) }
    // Recently Viewed shows a few items until expanded; it starts collapsed each launch.
    var showsAllRecent by remember { mutableStateOf(false) }
    var preferences by remember(host) { mutableStateOf(loadGlancePreferences(context, host)) }
    val favorites = library.favorites
    val recent = library.state.recent.take(10)
    val listState = rememberLazyListState()
    val metrics = preferences.visible.map { it to glanceValue(it, nodes, observers, packets, now) }
    val activeFavorites = activeFavoriteCount(favorites, nodes, observers, now)
    val networkStatus = nodesState?.takeIf { it.key == host }

    // Exports use the system file picker, so the person chooses where the file goes.
    var pendingExport by remember { mutableStateOf<String?>(null) }
    fun write(uri: Uri?) {
        val text = pendingExport ?: return
        pendingExport = null
        uri ?: return
        runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) } }
    }
    val csvLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv"), ::write)
    val jsonLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json"), ::write)

    if (searching) GlobalSearchSheet(nodes, observers, channels, monitored, packets, loading, library, actions) { searching = false }
    if (customizing) CustomizeSheet(preferences, { preferences = it; saveGlancePreferences(context, host, it) }) { customizing = false }

    PullToRefreshBox(isRefreshing = loading && nodes.isNotEmpty(), onRefresh = { model?.load(host, force = true) }, modifier = Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.explore_explore), style = MaterialTheme.typography.headlineLarge)
                        Text(pluralStringResource(R.plurals.explore_saved_item_on_this_analyzer, favorites.size, favorites.size), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { searching = true }) { Icon(Icons.Outlined.Search, stringResource(R.string.explore_search_the_network_3)) }
                }
            }
            item {
                GlanceCard(metrics, loading, actions,
                    onCustomize = { customizing = true },
                    onExportCsv = { pendingExport = glanceCsv(metrics, activeFavorites, favoriteBreakdown(favorites)); csvLauncher.launch("network-summary.csv") },
                    onExportJson = { pendingExport = glanceJson(host, System.currentTimeMillis(), metrics, activeFavorites, favorites.size); jsonLauncher.launch("network-summary.json") })
            }
            item {
                Card(Modifier.fillMaxWidth(), colors = meshCardColors()) {
                    Column(Modifier.padding(16.dp)) {
                        NavigationRow(stringResource(R.string.explore_node_planner), stringResource(R.string.explore_evaluate_proposed_sites_and_point_to_point_terrain), Icons.Outlined.GraphicEq, actions.onNodePlanner)
                        NavigationRow(stringResource(R.string.explore_node_check), stringResource(R.string.explore_is_your_node_getting_out_check_any_node), Icons.Outlined.Verified, actions.onNodeCheck)
                    }
                }
            }
            networkStatus?.let { state ->
                item { LoadStatus(state, nodes.isNotEmpty(), now) { model?.load(host, force = true) } }
            }
            item {
                Card(onClick = actions.onFavorites, modifier = Modifier.fillMaxWidth(),
                    colors = meshCardColors()) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(Icons.Outlined.Star, contentDescription = null, modifier = Modifier.size(24.dp),
                            tint = Color(0xFFE0A100))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text(stringResource(R.string.explore_favorites), style = MaterialTheme.typography.titleMedium)
                            Text(pluralStringResource(R.plurals.explore_saved_items_view_activity, favorites.size, favorites.size), style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Icon(Icons.Outlined.ChevronRight, contentDescription = null, modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (favorites.isEmpty() && recent.isEmpty()) item {
                Column(Modifier.fillMaxWidth().padding(vertical = 20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Outlined.Explore, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
                    Text(stringResource(R.string.explore_explore_your_mesh), style = MaterialTheme.typography.titleLarge)
                    Text(stringResource(R.string.explore_search_for_nodes_observers_and_channels_then_save), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    Button(onClick = { searching = true }) { Text(stringResource(R.string.explore_search_the_network)) }
                    TextButton(onClick = actions.onMap) { Text(stringResource(R.string.explore_open_map)) }
                    TextButton(onClick = actions.onChannels) { Text(stringResource(R.string.explore_browse_channels)) }
                    TextButton(onClick = { actions.onObservers(false) }) { Text(stringResource(R.string.explore_browse_observers)) }
                }
            }
            if (recent.isNotEmpty()) {
                item(key = "recent-header") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.explore_recently_viewed), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        TextButton(onClick = library::clearRecent) { Text(stringResource(R.string.explore_clear)) }
                    }
                }
                items(if (showsAllRecent) recent else recent.take(COLLAPSED_RECENT_COUNT), key = { "recent-${it.key}" }) { item ->
                    SavedRow(item, now = now, current = currentNode(item, nodes), onOpen = { open(item, actions) }) {
                        IconButton(onClick = { library.removeRecent(item) }) { Icon(Icons.Outlined.Close, stringResource(R.string.explore_remove_from_recent, item.title)) }
                    }
                }
                if (recent.size > COLLAPSED_RECENT_COUNT) item(key = "recent-toggle") {
                    TextButton(onClick = { showsAllRecent = !showsAllRecent }) {
                        Text(if (showsAllRecent) stringResource(R.string.explore_show_less) else stringResource(R.string.explore_show_all, recent.size))
                        Icon(if (showsAllRecent) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null, Modifier.padding(start = 4.dp))
                    }
                }
            }
        }
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FavoritesScreen(model: ExploreViewModel?, host: String, snapshot: AnalyzerSnapshot?, library: NodeLibrary,
                    actions: ExploreActions, sourceName: String? = null) {
    val isBeacon = org.nodescope.android.core.design.LocalBeaconSource.current
    val state = model?.nodes?.state?.collectAsStateWithLifecycle()?.value
    val nodes = state?.valueFor(host) ?: snapshot?.nodes.orEmpty()
    val favorites = library.favorites
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    LaunchedEffect(model, host) { model?.nodes?.load(host) }
    LaunchedEffect(nodes) { if (nodes.isNotEmpty()) library.refreshNodes(nodes) }
    if (adding) MeshModalBottomSheet(onDismissRequest = { adding = false }) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.85f)) {
            Text(stringResource(R.string.explore_add_favorite_node), Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.titleLarge)
            NodeBrowser(nodes, nodes.size) { key ->
                nodes.firstOrNull { it.publicKey == key }?.let { if (!library.isFavorite(SavedKind.NODE, key)) library.toggle(it) }
                adding = false
            }
        }
    }

    val favoriteSections = listOf(SavedKind.CHANNEL to stringResource(R.string.explore_favorite_channels), SavedKind.NODE to stringResource(R.string.explore_favorite_nodes), SavedKind.OBSERVER to stringResource(R.string.explore_favorite_observers_2))
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { FavoriteSourceIdentity(host, sourceName) }
                FilledIconButton(onClick = { adding = true }) { Icon(Icons.Outlined.Add, stringResource(R.string.explore_add_favorite_node_2)) }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Card(Modifier.fillMaxWidth(), colors = meshCardColors()) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                        NavigationRow(stringResource(R.string.explore_activity), stringResource(R.string.explore_last_heard_and_freshness_for_saved_nodes_and), Icons.Outlined.GraphicEq, actions.onActivity)
                        if (!isBeacon) NavigationRow(stringResource(R.string.explore_corescope_account), stringResource(R.string.explore_sync_node_favorites_with_your_account_on_this), Icons.Outlined.AccountCircle, actions.onAccount)
                    }
                }
                Text(stringResource(R.string.explore_device_favorites_and_account_nodes_on_this_source),
                    Modifier.padding(horizontal = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (favorites.isEmpty()) item {
            EmptyState(Icons.Outlined.StarBorder, stringResource(R.string.explore_no_favorites_yet), stringResource(R.string.explore_add_a_node_here_or_save_observers_and))
        }
        favoriteSections.forEach { (kind, title) ->
            val items = favorites.filter { it.kind == kind }
            if (items.isNotEmpty()) {
                item(key = "header-$kind") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SectionLabel(title, Modifier.weight(1f))
                        if (items.count { library.isDeviceFavorite(it) } > 1) TextButton(onClick = { editing = !editing }) { Text(if (editing) stringResource(R.string.common_done_2) else stringResource(R.string.explore_reorder)) }
                    }
                }
                items(items, key = { "favorite-${it.key}" }) { item ->
                    val device = library.isDeviceFavorite(item)
                    val account = library.isAccountFavorite(item)
                    SavedRow(item, now = null, current = currentNode(item, nodes), onOpen = { open(item, actions) },
                        detail = if (item.kind == SavedKind.NODE) library.membershipLabel(item).takeIf { it.isNotEmpty() } else null,
                        trailing = {
                            if (editing && device) {
                                val deviceItems = items.filter { library.isDeviceFavorite(it) }
                                val index = deviceItems.indexOf(item)
                                IconButton(onClick = { library.moveFavorite(item, -1) }, enabled = index > 0) { Icon(Icons.Outlined.KeyboardArrowUp, stringResource(R.string.explore_move_up, item.title)) }
                                IconButton(onClick = { library.moveFavorite(item, 1) }, enabled = index < deviceItems.lastIndex) { Icon(Icons.Outlined.KeyboardArrowDown, stringResource(R.string.explore_move_down, item.title)) }
                            } else if (device || account) {
                                // One membership removes directly, like the star elsewhere; both ask which to remove.
                                var choosing by remember { mutableStateOf(false) }
                                Box {
                                    IconButton(onClick = {
                                        when {
                                            device && account -> choosing = true
                                            device -> library.removeFavorite(item)
                                            else -> library.removeAccountFavorite(item)
                                        }
                                    }) {
                                        Icon(Icons.Outlined.Star, when {
                                            device && account -> stringResource(R.string.explore_remove_favorite_from_device_or_account, item.title)
                                            device -> stringResource(R.string.explore_remove_favorite, item.title)
                                            else -> stringResource(R.string.explore_remove_account_favorite_2, item.title)
                                        }, tint = Color(0xFFE0A100))
                                    }
                                    DropdownMenu(expanded = choosing, onDismissRequest = { choosing = false }) {
                                        DropdownMenuItem(text = { Text(stringResource(R.string.explore_remove_from_device)) }, onClick = { choosing = false; library.removeFavorite(item) })
                                        DropdownMenuItem(text = { Text(stringResource(R.string.explore_remove_from_account)) }, onClick = { choosing = false; library.removeAccountFavorite(item) })
                                    }
                                }
                            }
                        })
                }
            }
        }
    }
}

private fun currentNode(item: SavedItem, nodes: List<MeshNode>) = item.node?.let { saved -> nodes.firstOrNull { it.publicKey == saved.publicKey } ?: saved }

private fun open(item: SavedItem, actions: ExploreActions) = when (item.kind) {
    SavedKind.NODE -> actions.onNode(item.entityId)
    SavedKind.OBSERVER -> actions.onObserver(item.entityId, item.title)
    SavedKind.CHANNEL -> actions.onChannel(item.entityId, item.title)
    SavedKind.PACKET -> item.message?.let { actions.onPacket(it.hash, it.sender, it.text) } ?: Unit
}

@Composable
private fun GlanceCard(metrics: List<Pair<GlanceMetric, GlanceValue>>, loading: Boolean,
    actions: ExploreActions, onCustomize: () -> Unit, onExportCsv: () -> Unit, onExportJson: () -> Unit) {
    Card(colors = meshCardColors()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.explore_network_at_a_glance), style = MaterialTheme.typography.titleMedium)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Icon(Icons.Outlined.Public, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(stringResource(R.string.explore_entire_network), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (loading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                var open by remember { mutableStateOf(false) }
                Box {
                    IconButton(onClick = { open = true }) { Icon(Icons.Outlined.MoreVert, stringResource(R.string.explore_network_summary_actions)) }
                    DropdownMenu(open, { open = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.explore_customize_dashboard)) }, leadingIcon = { Icon(Icons.Outlined.Tune, null) }, onClick = { open = false; onCustomize() })
                        DropdownMenuItem(text = { Text(stringResource(R.string.explore_export_csv)) }, leadingIcon = { Icon(Icons.Outlined.TableChart, null) }, onClick = { open = false; onExportCsv() })
                        DropdownMenuItem(text = { Text(stringResource(R.string.explore_export_json)) }, leadingIcon = { Icon(Icons.Outlined.DataObject, null) }, onClick = { open = false; onExportJson() })
                    }
                }
            }
            metrics.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { (metric, value) ->
                        val action: (() -> Unit)? = when (metric) {
                            GlanceMetric.ACTIVE_NODES -> actions.onActiveNodes
                            GlanceMetric.OBSERVERS_ONLINE -> ({ actions.onObservers(true) })
                            GlanceMetric.PACKETS -> actions.onPackets
                            GlanceMetric.AVERAGE_SNR -> null
                        }
                        MetricTile(stringResource(metric.titleRes), value, metric.tone(), Modifier.weight(1f), action)
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }

        }
    }
}

@Composable
private fun MetricTile(label: String, value: GlanceValue, tone: GlanceTone, modifier: Modifier, onClick: (() -> Unit)?) {
    val color = if (MaterialTheme.colorScheme.surface.luminance() < 0.3f) tone.dark else tone.light
    val shape = MaterialTheme.shapes.medium
    Column(modifier.clip(shape)
        .let { if (onClick != null) it.clickable(onClickLabel = stringResource(R.string.explore_open, label), onClick = onClick) else it }
        .padding(horizontal = 8.dp, vertical = 6.dp).semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(value.displayValue(), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = color, maxLines = 1)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
            if (onClick != null) Icon(Icons.Outlined.ChevronRight, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(value.displayDetail.asString(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Icon treatment shared by saved rows and search results (iOS FavoriteRow/RecentRow). */
@Composable
internal fun KindIcon(kind: SavedKind, node: MeshNode?) {
    val (icon, tint, fill) = when {
        node != null -> Triple(roleIcon(node.role), Color(0xFF14243A), roleColor(node.role))
        kind == SavedKind.OBSERVER -> Triple(Icons.Outlined.Sensors, statusAccent(true), statusAccent(true).copy(alpha = 0.14f))
        kind == SavedKind.CHANNEL -> Triple(Icons.Outlined.Tag, MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.primaryContainer)
        kind == SavedKind.PACKET -> Triple(Icons.Outlined.MonitorHeart, statusAccent(false), statusAccent(false).copy(alpha = 0.14f))
        else -> Triple(Icons.Outlined.Hub, SignalBlue, SignalBlue.copy(alpha = 0.14f))
    }
    Box(Modifier.size(40.dp).background(fill, CircleShape), contentAlignment = Alignment.Center) { Icon(icon, null, Modifier.size(20.dp), tint = tint) }
}

@Composable
private fun SavedRow(item: SavedItem, now: Long?, current: MeshNode?, onOpen: () -> Unit, detail: String? = null,
    trailing: @Composable RowScope.() -> Unit) {
    Surface(onClick = onOpen, shape = MaterialTheme.shapes.medium, color = meshCardColor(), contentColor = MaterialTheme.colorScheme.onSurface) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            KindIcon(item.kind, current ?: item.node)
            Column(Modifier.weight(1f)) {
                Text(current?.displayName?.takeIf { current.name != null } ?: item.title, style = MaterialTheme.typography.titleSmall)
                // Without a time this is the favorites list, whose subtitle is a key, observer code or sender: data, in Neon.
                Text(listOfNotNull(item.subtitleText?.asString(), now?.let { relativeTime(java.time.Instant.ofEpochMilli(item.at), it) }).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = if (now == null) MonaspaceNeon else null)
                detail?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            trailing()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CustomizeSheet(preferences: GlancePreferences, onChange: (GlancePreferences) -> Unit, onDismiss: () -> Unit) {
    MeshModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.explore_customize_dashboard), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = { onChange(GlancePreferences()) }) { Text(stringResource(R.string.explore_reset)) }
            }
            SectionLabel(stringResource(R.string.explore_dashboard_metrics))
            val order = preferences.normalized().order
            val visibleCount = order.count { it !in preferences.hidden }
            order.forEachIndexed { index, metric ->
                val shown = metric !in preferences.hidden
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(metric.titleRes), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    IconButton(onClick = { onChange(preferences.copy(order = order.toMutableList().apply { add(index - 1, removeAt(index)) })) }, enabled = index > 0) {
                        Icon(Icons.Outlined.KeyboardArrowUp, stringResource(R.string.explore_move_up, stringResource(metric.titleRes)))
                    }
                    IconButton(onClick = { onChange(preferences.copy(order = order.toMutableList().apply { add(index + 1, removeAt(index)) })) }, enabled = index < order.lastIndex) {
                        Icon(Icons.Outlined.KeyboardArrowDown, stringResource(R.string.explore_move_down, stringResource(metric.titleRes)))
                    }
                    // At least one metric always stays visible, as on iOS.
                    Switch(checked = shown, enabled = !shown || visibleCount > 1, onCheckedChange = { on ->
                        onChange(preferences.copy(hidden = if (on) preferences.hidden - metric else preferences.hidden + metric))
                    })
                }
            }
            Text(stringResource(R.string.explore_reorder_metrics_and_hide_the_ones_you_don), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** iOS `GlobalSearchSheet`: nodes, observers, channels and packet hashes, with recent searches. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GlobalSearchSheet(nodes: List<MeshNode>, observers: List<MeshObserver>, channels: List<MeshChannel>, monitored: List<MonitoredChannel>,
    packets: List<LivePacket>, loading: Boolean, library: NodeLibrary, actions: ExploreActions, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val results = remember(query, nodes, observers, channels, monitored, packets) { searchNetwork(query, nodes, observers, channels, monitored, packets) }
    fun choose(open: () -> Unit) { library.recordSearch(query); onDismiss(); open() }
    MeshModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.92f).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            BrowseSearchFieldWithSubmit(query, { query = it }) { library.recordSearch(query) }
            val trimmed = query.trim()
            LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                when {
                    trimmed.isEmpty() && library.state.searches.isEmpty() -> item {
                        EmptyState(Icons.Outlined.Search, stringResource(R.string.explore_search_the_network_4), stringResource(R.string.explore_find_nodes_observers_channels_public_keys_and_packet))
                    }
                    trimmed.isEmpty() -> {
                        item {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                SectionLabel(stringResource(R.string.explore_recent_searches), Modifier.weight(1f))
                                TextButton(onClick = library::clearSearches) { Text(stringResource(R.string.explore_clear)) }
                            }
                        }
                        items(library.state.searches, key = { "search-$it" }) { past ->
                            Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable { query = past }.padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Icon(Icons.Outlined.History, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(past, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                                IconButton(onClick = { library.removeSearch(past) }) { Icon(Icons.Outlined.Close, stringResource(R.string.explore_remove_2, past)) }
                            }
                        }
                    }
                    results.isEmpty && loading -> item { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(stringResource(R.string.explore_indexing_network), Modifier.padding(top = 8.dp)) }
                    results.isEmpty -> item { EmptyState(Icons.Outlined.SearchOff, stringResource(R.string.explore_no_results_for, trimmed), stringResource(R.string.explore_check_the_spelling_or_try_a_different_search)) }
                    else -> {
                        if (results.channels.isNotEmpty()) {
                            item { SectionLabel(stringResource(R.string.explore_channels)) }
                            items(results.channels, key = { "c-${it.hash}" }) { channel ->
                                ResultRow(SavedKind.CHANNEL, null, channel.name, channel.lastSender ?: channel.lastMessage,
                                    library.isFavorite(SavedKind.CHANNEL, channel.id), { library.toggle(channel) }) { choose { actions.onChannel(channel.id, channel.name) } }
                            }
                        }
                        if (results.nodes.isNotEmpty()) {
                            item { SectionLabel(stringResource(R.string.explore_nodes)) }
                            items(results.nodes, key = { "n-${it.publicKey}" }) { node ->
                                ResultRow(SavedKind.NODE, node, node.name ?: stringResource(R.string.explore_unnamed_node), "${nodeRoleLabel(node.role)} · ${node.publicKey.take(12).uppercase()}",
                                    library.isFavorite(SavedKind.NODE, node.publicKey), { library.toggle(node) }) { choose { actions.onNode(node.publicKey) } }
                            }
                        }
                        if (results.observers.isNotEmpty()) {
                            item { SectionLabel(stringResource(R.string.explore_observers)) }
                            items(results.observers, key = { "o-${it.id}" }) { observer ->
                                ResultRow(SavedKind.OBSERVER, null, observer.name ?: stringResource(R.string.explore_unnamed_observer), listOfNotNull(observer.iata, observer.model, observer.id.take(12)).joinToString(" · "),
                                    library.isFavorite(SavedKind.OBSERVER, observer.id), { library.toggle(observer) }) { choose { actions.onObserver(observer.id, observer.displayName) } }
                            }
                        }
                        if (results.packets.isNotEmpty()) {
                            item { SectionLabel(stringResource(R.string.explore_packets)) }
                            items(results.packets, key = { "p-${it.hash}-${it.id}" }) { packet ->
                                ResultRow(SavedKind.PACKET, null, packet.typeName, packet.hash, null, null) {
                                    choose { actions.onPacket(packet.hash, packet.payloadName ?: "", packet.payloadText ?: "") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BrowseSearchFieldWithSubmit(query: String, onQuery: (String) -> Unit, onSubmit: () -> Unit) {
    TextField(query, onQuery, singleLine = true, shape = MaterialTheme.shapes.large, placeholder = { Text(stringResource(R.string.explore_search_the_network_2)) },
        leadingIcon = { Icon(Icons.Outlined.Search, null) },
        trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { onQuery("") }) { Icon(Icons.Outlined.Close, stringResource(R.string.explore_clear_search)) } },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
        colors = TextFieldDefaults.colors(focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer, unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth())
}

@Composable
private fun ResultRow(kind: SavedKind, node: MeshNode?, title: String, subtitle: String?, favorite: Boolean?, onFavorite: (() -> Unit)?, onOpen: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable(onClick = onOpen).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        KindIcon(kind, node)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        if (favorite != null && onFavorite != null) IconButton(onClick = onFavorite) {
            Icon(if (favorite) Icons.Outlined.Star else Icons.Outlined.StarBorder, if (favorite) stringResource(R.string.explore_remove_from_favorites, title) else stringResource(R.string.explore_add_to_favorites, title),
                tint = if (favorite) Color(0xFFE0A100) else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private const val COLLAPSED_RECENT_COUNT = 3
