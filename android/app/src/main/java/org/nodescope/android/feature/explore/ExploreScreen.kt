package org.nodescope.android.feature.explore

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

/** Where Explore can take the person; wired by the app shell. */
class ExploreActions(
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
    val scope = rememberCoroutineScope()
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
    var adding by remember { mutableStateOf(false) }
    var customizing by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var preferences by remember(host) { mutableStateOf(loadGlancePreferences(context, host)) }
    val favorites = library.state.favorites
    val recent = library.state.recent.take(10)
    val listState = rememberLazyListState()
    val metrics = preferences.visible.map { it to glanceValue(it, nodes, observers, packets, now) }
    val activeFavorites = activeFavoriteCount(favorites, nodes, observers, now)
    val nodesError = nodesState?.takeIf { it.key == host && it.error != null }
    // Header, dashboard, optional error line, then the empty state or spacer precede the favorites.
    val favoritesIndex = 3 + (if (nodesError != null) 1 else 0)

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
    if (adding) ModalBottomSheet(onDismissRequest = { adding = false }) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.85f)) {
            Text("Add favorite node", Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.titleLarge)
            NodeBrowser(nodes, nodes.size) { key ->
                nodes.firstOrNull { it.publicKey == key }?.let { if (!library.isFavorite(SavedKind.NODE, key)) library.toggle(it) }
                adding = false
            }
        }
    }
    if (customizing) CustomizeSheet(preferences, { preferences = it; saveGlancePreferences(context, host, it) }) { customizing = false }

    PullToRefreshBox(isRefreshing = loading && nodes.isNotEmpty(), onRefresh = { model?.load(host, force = true) }, modifier = Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Explore", style = MaterialTheme.typography.headlineLarge)
                        Text("${favorites.size} saved item${if (favorites.size == 1) "" else "s"} on this analyzer", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { searching = true }) { Icon(Icons.Outlined.Search, "Search the network") }
                    FilledIconButton(onClick = { adding = true }) { Icon(Icons.Outlined.Add, "Add favorite node") }
                }
            }
            item {
                GlanceCard(metrics, loading, activeFavorites, favorites, actions,
                    onCustomize = { customizing = true },
                    onExportCsv = { pendingExport = glanceCsv(metrics, activeFavorites, favoriteBreakdown(favorites)); csvLauncher.launch("network-summary.csv") },
                    onExportJson = { pendingExport = glanceJson(host, System.currentTimeMillis(), metrics, activeFavorites, favorites.size); jsonLauncher.launch("network-summary.json") },
                    onFavorites = { scope.launch { listState.animateScrollToItem(favoritesIndex) } })
            }
            nodesError?.let { state -> item { LoadStatus(state, nodes.isNotEmpty(), now) { model?.load(host, force = true) } } }
            if (favorites.isEmpty() && recent.isEmpty()) item {
                Column(Modifier.fillMaxWidth().padding(vertical = 20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Outlined.Explore, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
                    Text("Explore Your Mesh", style = MaterialTheme.typography.titleLarge)
                    Text("Search for nodes, observers, and channels, then save the ones you want to follow.", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    Button(onClick = { searching = true }) { Text("Search the Network") }
                    TextButton(onClick = actions.onMap) { Text("Open Map") }
                    TextButton(onClick = actions.onChannels) { Text("Browse Channels") }
                    TextButton(onClick = { actions.onObservers(false) }) { Text("Browse Observers") }
                }
            } else item { Spacer(Modifier.height(0.dp)) } // keeps the favorites scroll target at a stable index
            listOf(SavedKind.CHANNEL to "Favorite Channels", SavedKind.NODE to "Favorite Nodes", SavedKind.OBSERVER to "Favorite Observers").forEach { (kind, title) ->
                val items = favorites.filter { it.kind == kind }
                if (items.isNotEmpty()) {
                    item(key = "header-$kind") {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            SectionLabel(title, Modifier.weight(1f))
                            if (items.size > 1) TextButton(onClick = { editing = !editing }) { Text(if (editing) "Done" else "Reorder") }
                        }
                    }
                    items(items, key = { "favorite-${it.key}" }) { item ->
                        SavedRow(item, now = null, current = currentNode(item, nodes), onOpen = { open(item, actions) },
                            trailing = {
                                if (editing) {
                                    val index = items.indexOf(item)
                                    IconButton(onClick = { library.moveFavorite(item, -1) }, enabled = index > 0) { Icon(Icons.Outlined.KeyboardArrowUp, "Move ${item.title} up") }
                                    IconButton(onClick = { library.moveFavorite(item, 1) }, enabled = index < items.lastIndex) { Icon(Icons.Outlined.KeyboardArrowDown, "Move ${item.title} down") }
                                } else IconButton(onClick = { library.removeFavorite(item) }) {
                                    Icon(Icons.Outlined.Star, "Remove favorite ${item.title}", tint = MaterialTheme.colorScheme.primary)
                                }
                            })
                    }
                }
            }
            if (recent.isNotEmpty()) {
                item(key = "recent-header") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Recently Viewed", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        TextButton(onClick = library::clearRecent) { Text("Clear") }
                    }
                }
                items(recent, key = { "recent-${it.key}" }) { item ->
                    SavedRow(item, now = now, current = currentNode(item, nodes), onOpen = { open(item, actions) }) {
                        IconButton(onClick = { library.removeRecent(item) }) { Icon(Icons.Outlined.Close, "Remove ${item.title} from recent") }
                    }
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
private fun GlanceCard(metrics: List<Pair<GlanceMetric, GlanceValue>>, loading: Boolean, activeFavorites: Int, favorites: List<SavedItem>,
    actions: ExploreActions, onCustomize: () -> Unit, onExportCsv: () -> Unit, onExportJson: () -> Unit, onFavorites: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Network at a Glance", style = MaterialTheme.typography.titleMedium)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Icon(Icons.Outlined.Public, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Entire network", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (loading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                var open by remember { mutableStateOf(false) }
                Box {
                    IconButton(onClick = { open = true }) { Icon(Icons.Outlined.MoreVert, "Network summary actions") }
                    DropdownMenu(open, { open = false }) {
                        DropdownMenuItem(text = { Text("Customize dashboard") }, leadingIcon = { Icon(Icons.Outlined.Tune, null) }, onClick = { open = false; onCustomize() })
                        DropdownMenuItem(text = { Text("Export CSV") }, leadingIcon = { Icon(Icons.Outlined.TableChart, null) }, onClick = { open = false; onExportCsv() })
                        DropdownMenuItem(text = { Text("Export JSON") }, leadingIcon = { Icon(Icons.Outlined.DataObject, null) }, onClick = { open = false; onExportJson() })
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
                        MetricTile(metric.title, value, metric.tone(), Modifier.weight(1f), action)
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable(onClickLabel = "Scroll to your saved items", onClick = onFavorites)
                .padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Outlined.Star, null, tint = Color(0xFFE0A100))
                Column(Modifier.weight(1f)) {
                    Text(if (favorites.isEmpty()) "No favorites saved yet" else "$activeFavorites active favorite${if (activeFavorites == 1) "" else "s"}",
                        style = MaterialTheme.typography.titleSmall)
                    Text(favoriteBreakdown(favorites), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(Icons.Outlined.ExpandMore, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun MetricTile(label: String, value: GlanceValue, tone: GlanceTone, modifier: Modifier, onClick: (() -> Unit)?) {
    val color = if (MaterialTheme.colorScheme.surface.luminance() < 0.3f) tone.dark else tone.light
    val shape = MaterialTheme.shapes.medium
    Column(modifier.clip(shape).background(if (onClick != null) color.copy(alpha = 0.08f) else Color.Transparent, shape)
        .let { if (onClick != null) it.clickable(onClickLabel = "Open $label", onClick = onClick) else it }
        .padding(12.dp).semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(value.value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = color, maxLines = 1)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
            if (onClick != null) Icon(Icons.Outlined.ChevronRight, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(value.detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Icon treatment shared by saved rows and search results (iOS FavoriteRow/RecentRow). */
@Composable
private fun KindIcon(kind: SavedKind, node: MeshNode?) {
    val (icon, tint, fill) = when {
        node != null -> Triple(roleIcon(node.role), Color.White, roleColor(node.role))
        kind == SavedKind.OBSERVER -> Triple(Icons.Outlined.Sensors, HealthyGreen, HealthyGreen.copy(alpha = 0.14f))
        kind == SavedKind.CHANNEL -> Triple(Icons.Outlined.Tag, SignalBlue, SignalBlue.copy(alpha = 0.14f))
        kind == SavedKind.PACKET -> Triple(Icons.Outlined.MonitorHeart, ActivityAmber, ActivityAmber.copy(alpha = 0.14f))
        else -> Triple(Icons.Outlined.Hub, SignalBlue, SignalBlue.copy(alpha = 0.14f))
    }
    Box(Modifier.size(40.dp).background(fill, CircleShape), contentAlignment = Alignment.Center) { Icon(icon, null, Modifier.size(20.dp), tint = tint) }
}

@Composable
private fun SavedRow(item: SavedItem, now: Long?, current: MeshNode?, onOpen: () -> Unit, trailing: @Composable RowScope.() -> Unit) {
    Surface(onClick = onOpen, shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            KindIcon(item.kind, current ?: item.node)
            Column(Modifier.weight(1f)) {
                Text(current?.displayName?.takeIf { current.name != null } ?: item.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(listOfNotNull(item.subtitle, now?.let { relativeTime(java.time.Instant.ofEpochMilli(item.at), it) }).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            trailing()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CustomizeSheet(preferences: GlancePreferences, onChange: (GlancePreferences) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Customize dashboard", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = { onChange(GlancePreferences()) }) { Text("Reset") }
            }
            SectionLabel("Dashboard metrics")
            val order = preferences.normalized().order
            val visibleCount = order.count { it !in preferences.hidden }
            order.forEachIndexed { index, metric ->
                val shown = metric !in preferences.hidden
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(metric.title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    IconButton(onClick = { onChange(preferences.copy(order = order.toMutableList().apply { add(index - 1, removeAt(index)) })) }, enabled = index > 0) {
                        Icon(Icons.Outlined.KeyboardArrowUp, "Move ${metric.title} up")
                    }
                    IconButton(onClick = { onChange(preferences.copy(order = order.toMutableList().apply { add(index + 1, removeAt(index)) })) }, enabled = index < order.lastIndex) {
                        Icon(Icons.Outlined.KeyboardArrowDown, "Move ${metric.title} down")
                    }
                    // At least one metric always stays visible, as on iOS.
                    Switch(checked = shown, enabled = !shown || visibleCount > 1, onCheckedChange = { on ->
                        onChange(preferences.copy(hidden = if (on) preferences.hidden - metric else preferences.hidden + metric))
                    })
                }
            }
            Text("Reorder metrics and hide the ones you don't need.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.92f).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            BrowseSearchFieldWithSubmit(query, { query = it }) { library.recordSearch(query) }
            val trimmed = query.trim()
            LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                when {
                    trimmed.isEmpty() && library.state.searches.isEmpty() -> item {
                        EmptyState(Icons.Outlined.Search, "Search the Network", "Find nodes, observers, channels, public keys, and packet hashes.")
                    }
                    trimmed.isEmpty() -> {
                        item {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                SectionLabel("Recent searches", Modifier.weight(1f))
                                TextButton(onClick = library::clearSearches) { Text("Clear") }
                            }
                        }
                        items(library.state.searches, key = { "search-$it" }) { past ->
                            Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable { query = past }.padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Icon(Icons.Outlined.History, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(past, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                                IconButton(onClick = { library.removeSearch(past) }) { Icon(Icons.Outlined.Close, "Remove $past") }
                            }
                        }
                    }
                    results.isEmpty && loading -> item { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Indexing network…", Modifier.padding(top = 8.dp)) }
                    results.isEmpty -> item { EmptyState(Icons.Outlined.SearchOff, "No results for \"$trimmed\"", "Check the spelling or try a different search.") }
                    else -> {
                        if (results.channels.isNotEmpty()) {
                            item { SectionLabel("Channels") }
                            items(results.channels, key = { "c-${it.hash}" }) { channel ->
                                ResultRow(SavedKind.CHANNEL, null, channel.name, channel.lastSender ?: channel.lastMessage,
                                    library.isFavorite(SavedKind.CHANNEL, channel.hash), { library.toggle(channel) }) { choose { actions.onChannel(channel.hash, channel.name) } }
                            }
                        }
                        if (results.nodes.isNotEmpty()) {
                            item { SectionLabel("Nodes") }
                            items(results.nodes, key = { "n-${it.publicKey}" }) { node ->
                                ResultRow(SavedKind.NODE, node, node.name ?: "Unnamed node", "${node.role.replaceFirstChar { it.uppercase() }} · ${node.publicKey.take(12).uppercase()}",
                                    library.isFavorite(SavedKind.NODE, node.publicKey), { library.toggle(node) }) { choose { actions.onNode(node.publicKey) } }
                            }
                        }
                        if (results.observers.isNotEmpty()) {
                            item { SectionLabel("Observers") }
                            items(results.observers, key = { "o-${it.id}" }) { observer ->
                                ResultRow(SavedKind.OBSERVER, null, observer.name ?: "Unnamed observer", listOfNotNull(observer.iata, observer.model, observer.id.take(12)).joinToString(" · "),
                                    library.isFavorite(SavedKind.OBSERVER, observer.id), { library.toggle(observer) }) { choose { actions.onObserver(observer.id, observer.displayName) } }
                            }
                        }
                        if (results.packets.isNotEmpty()) {
                            item { SectionLabel("Packets") }
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
    TextField(query, onQuery, singleLine = true, shape = MaterialTheme.shapes.large, placeholder = { Text("Search the network") },
        leadingIcon = { Icon(Icons.Outlined.Search, null) },
        trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { onQuery("") }) { Icon(Icons.Outlined.Close, "Clear search") } },
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
            Icon(if (favorite) Icons.Outlined.Star else Icons.Outlined.StarBorder, if (favorite) "Remove $title from favorites" else "Add $title to favorites",
                tint = if (favorite) Color(0xFFE0A100) else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
