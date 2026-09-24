package org.nodescope.android.app

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import org.nodescope.android.core.storage.NodeLibrary
import org.nodescope.android.core.storage.SavedKind
import org.nodescope.android.core.model.MeshChannel
import org.nodescope.android.core.model.NodeScopeLink
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.*
import androidx.navigation.toRoute
import kotlinx.serialization.Serializable
import kotlinx.coroutines.flow.first
import org.nodescope.android.R
import org.nodescope.android.core.network.SessionState
import org.nodescope.android.core.network.LiveFeedState
import org.nodescope.android.feature.packets.PacketScreen
import org.nodescope.android.feature.packets.PacketDetailScreen
import org.nodescope.android.feature.packets.MessagePacketScreen
import org.nodescope.android.feature.packets.PacketDetailViewModel
import org.nodescope.android.feature.packets.routeSubchains
import androidx.compose.ui.text.style.TextOverflow
import org.nodescope.android.core.storage.AppPreferences
import org.nodescope.android.feature.explore.*
import org.nodescope.android.feature.map.*
import org.nodescope.android.feature.onboarding.SourceScreen
import org.nodescope.android.feature.settings.*
import androidx.lifecycle.viewmodel.compose.viewModel
import org.nodescope.android.core.model.AnalyzerSelection
import org.nodescope.android.core.network.BrowseRepository
import org.nodescope.android.core.storage.MonitoredChannelStore
import org.nodescope.android.feature.channels.*
import org.nodescope.android.feature.observers.*
import org.nodescope.android.feature.nodes.*

@Serializable sealed interface TopLevelRoute
@Serializable data object MapRoute : TopLevelRoute
@Serializable data object ExploreRoute : TopLevelRoute
@Serializable data object ChannelsRoute : TopLevelRoute
@Serializable data object ObserversRoute : TopLevelRoute
@Serializable data object SettingsRoute : TopLevelRoute

enum class Destination(val title: Int, val icon: ImageVector, val route: TopLevelRoute) {
    MAP(R.string.map, Icons.Outlined.Map, MapRoute),
    EXPLORE(R.string.explore, Icons.Outlined.Explore, ExploreRoute),
    CHANNELS(R.string.channels, Icons.Outlined.Forum, ChannelsRoute),
    OBSERVERS(R.string.observers, Icons.Outlined.Sensors, ObserversRoute),
    SETTINGS(R.string.settings, Icons.Outlined.Settings, SettingsRoute),
}
@Serializable data class NodeRoute(val publicKey: String)
@Serializable data class NodeAnalyticsRoute(val publicKey: String)
@Serializable data object SourceRoute
@Serializable data object PacketRoute
@Serializable data class PacketDetailRoute(val groupId: String)
@Serializable data object DiagnosticsRoute
@Serializable data object StorageRoute
@Serializable data object AboutRoute
@Serializable data class MessagePacketRoute(val hash: String, val sender: String = "", val text: String = "")
@Serializable data object MapLabRoute
@Serializable data class ChannelRoute(val id: String, val name: String)
@Serializable data object AddChannelRoute
@Serializable data class ObserverRoute(val id: String, val name: String)

@Composable
fun NodeScopeApp(model: AppViewModel, preferences: AppPreferences) {
    val sources by model.sources.collectAsStateWithLifecycle()
    val saving by model.saving.collectAsStateWithLifecycle()
    val error by model.writeError.collectAsStateWithLifecycle()
    if (!preferences.onboarded) {
        Surface(Modifier.fillMaxSize()) {
            Box(Modifier.safeDrawingPadding()) {
                SourceScreen(sources, preferences.host, true, saving, error, { model.selectSource(it) }, model.sourceIcons)
            }
        }
    } else {
        key(preferences.host) {
            val rawState by model.state.collectAsStateWithLifecycle()
            // Preferences and session emit separately: never expose a previous selection's data.
            val state = rawState.takeIf { it.selection?.host == preferences.host && it.selection.region == preferences.region }
                ?: SessionState(loading = true)
            val rawLive by model.live.collectAsStateWithLifecycle()
            val live = rawLive.takeIf { it.selection?.host == preferences.host && it.selection.region == preferences.region } ?: LiveFeedState()
            val sourceChangedAt by model.sourceChangedAt.collectAsStateWithLifecycle()
            val pendingLink by model.pendingLink.collectAsStateWithLifecycle()
            AppShell(preferences, state, model::setRegion, { model.refresh(); model.reconnectLive() }, model::setAppearance, model::selectDestination,
                sourceScreen = { onDone -> SourceScreen(sources, preferences.host, false, saving, error, { host -> model.selectSource(host, onDone) }, model.sourceIcons) },
                feed = live, onReconnect = model::reconnectLive, browse = model.browse, monitoredChannels = { model.monitoredChannels },
                diagnostics = model.diagnostics, cacheStorage = model.cacheStorage, sourceChangedAt = sourceChangedAt,
                pendingLink = pendingLink, onLinkHandled = model::linkHandled, onDistanceUnit = model::setDistanceUnit,
                sourceViewport = sources.firstOrNull { source ->
                    runCatching { org.nodescope.android.core.network.normalizeHost(source.host) }.getOrNull().equals(preferences.host, true)
                }?.viewport)

        }
    }
}

/** Settings → Support development opens this page in the browser. */
const val SUPPORT_URL = "https://buymeacoffee.com/anieto"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AppShell(
    preferences: AppPreferences, state: SessionState,
    onRegion: (String?) -> Unit, onRefresh: () -> Unit,
    onAppearance: (org.nodescope.android.core.storage.Appearance) -> Unit,
    onDestination: (String) -> Unit,
    sourceScreen: @Composable (() -> Unit) -> Unit,
    feed: LiveFeedState = LiveFeedState(), onReconnect: () -> Unit = {}, nodeLibrary: NodeLibrary? = null,
    browse: BrowseRepository? = null, monitoredChannels: () -> MonitoredChannelStore? = { null },
    sourceViewport: org.nodescope.android.core.model.RegionCoordinate? = null,
    diagnostics: org.nodescope.android.core.network.AnalyzerDiagnostics? = null,
    cacheStorage: org.nodescope.android.core.storage.CacheStorage? = null, sourceChangedAt: Long? = null,
    pendingLink: PendingLink? = null, onLinkHandled: (Long) -> Unit = {},
    onDistanceUnit: (org.nodescope.android.core.storage.DistanceUnit) -> Unit = {},
) {
    val context = LocalContext.current
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
    val library = nodeLibrary ?: remember(preferences.host) { NodeLibrary.forAnalyzer(context, preferences.host) }
    val nav = rememberNavController()
    fun openNode(key: String) {
        (state.snapshot?.nodes?.firstOrNull { it.publicKey == key } ?: library.node(key))?.let(library::viewed)
        nav.navigate(NodeRoute(key))
    }
    val initialTab = remember { Destination.entries.firstOrNull { it.name == preferences.destination } ?: Destination.MAP }
    val entry by nav.currentBackStackEntryAsState()
    var currentTab by rememberSaveable { mutableStateOf(initialTab.name) }
    var focusedNode by rememberSaveable { mutableStateOf<String?>(null) }
    var focusedPosition by rememberSaveable { mutableStateOf<List<Double>?>(null) }
    val isRoot = entry == null || Destination.entries.any { entry!!.destination.hasRoute(it.route::class) }
    LaunchedEffect(entry) {
        Destination.entries.firstOrNull { entry?.destination?.hasRoute(it.route::class) == true }?.let {
            currentTab = it.name
        }
    }
    fun selectTab(destination: Destination) {
        if (currentTab == destination.name && !isRoot && nav.popBackStack(destination.route, false)) return
        currentTab = destination.name
        onDestination(destination.name)
        nav.navigate(destination.route) {
            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }
    val selection = AnalyzerSelection(preferences.host, preferences.region)
    var replay by remember { mutableStateOf<RouteReplay?>(null) }
    // Explore's "Observers online" opens Observers filtered to recently active ones.
    var observerActiveRequest by remember { mutableStateOf<Long?>(null) }
    /** Explore's "Active nodes" opens the map filtered to the last 15 minutes (iOS). */
    var showActiveNodes by remember { mutableStateOf(false) }
    fun startReplay(routes: List<List<String>>, selected: Int) {
        val nodes = state.snapshot?.nodes.orEmpty()
        val lookup = nodes.associateBy { it.publicKey.lowercase() }
        replay = RouteReplay(System.nanoTime(), routes.map { route ->
            ReplayRoute(routeSubchains(route, nodes), route.mapNotNull { lookup[it.lowercase()] }, route.size - 1)
        }, selected)
        selectTab(Destination.MAP)
    }
    // Open a `nodescope://` link on the same tab iOS uses. Detail screens load their own data by
    // identifier and explain when it isn't on the selected analyzer (links carry no source).
    LaunchedEffect(pendingLink?.id) {
        val pending = pendingLink ?: return@LaunchedEffect
        // On a cold start, wait until navigation has its first destination.
        nav.currentBackStackEntryFlow.first()
        val id = pending.link.identifier
        when (pending.link.kind) {
            NodeScopeLink.Kind.NODE -> { selectTab(Destination.EXPLORE); openNode(id) }
            NodeScopeLink.Kind.OBSERVER -> {
                selectTab(Destination.OBSERVERS)
                nav.navigate(ObserverRoute(id, feed.observers.firstOrNull { it.id.equals(id, true) }?.displayName ?: id.take(12)))
            }
            NodeScopeLink.Kind.CHANNEL -> { selectTab(Destination.CHANNELS); nav.navigate(ChannelRoute(id, id.removePrefix("user:"))) }
            NodeScopeLink.Kind.PACKET -> { selectTab(Destination.EXPLORE); nav.navigate(MessagePacketRoute(id)) }
        }
        onLinkHandled(pending.id)
    }
    // A replayed route belongs to the region it was chosen in.
    LaunchedEffect(preferences.region) { replay = null }
    val regionControl: @Composable () -> Unit = { RegionMenu(preferences, state) { focusedNode = null; focusedPosition = null; onRegion(it) } }
    val headerlessTabs = listOf(Destination.MAP.name, Destination.EXPLORE.name, Destination.CHANNELS.name, Destination.OBSERVERS.name, Destination.SETTINGS.name)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val expanded = maxWidth >= 600.dp
        Scaffold(
            topBar = {
                if (!isRoot || currentTab !in headerlessTabs) TopAppBar(title = {
                    Column {
                        val destination = entry?.destination
                        Text(when {
                            isRoot && currentTab == Destination.MAP.name -> "Live Map"
                            isRoot -> stringResource(Destination.valueOf(currentTab).title)
                            destination?.hasRoute<ChannelRoute>() == true -> entry!!.toRoute<ChannelRoute>().name
                            destination?.hasRoute<ObserverRoute>() == true -> entry!!.toRoute<ObserverRoute>().name
                            else -> stringResource(when {
                                destination?.hasRoute<SourceRoute>() == true -> R.string.analyzer
                                destination?.hasRoute<MapLabRoute>() == true -> R.string.map_lab
                                destination?.hasRoute<PacketRoute>() == true -> R.string.live_packets
                                destination?.hasRoute<PacketDetailRoute>() == true -> R.string.packet_details
                                destination?.hasRoute<MessagePacketRoute>() == true -> R.string.packet
                                destination?.hasRoute<DiagnosticsRoute>() == true -> R.string.analyzer_diagnostics
                                destination?.hasRoute<StorageRoute>() == true -> R.string.storage
                                destination?.hasRoute<AboutRoute>() == true -> R.string.about_title
                                destination?.hasRoute<AddChannelRoute>() == true -> R.string.add_channel
                                destination?.hasRoute<NodeAnalyticsRoute>() == true -> R.string.node_analytics
                                else -> R.string.node_details
                            })
                        }, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (isRoot) Text(preferences.host, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }, navigationIcon = {
                    if (!isRoot) IconButton(onClick = { nav.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.back))
                    }
                }, actions = {
                    if (isRoot && currentTab in listOf(Destination.MAP.name, Destination.EXPLORE.name)) {
                        RegionMenu(preferences, state) { focusedNode = null; focusedPosition = null; onRegion(it) }
                        IconButton(onClick = onRefresh, enabled = !state.loading) {
                            Icon(Icons.Outlined.Refresh, stringResource(R.string.refresh))
                        }
                    }
                })
            },
            bottomBar = {
                if (!expanded) NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
                    Destination.entries.forEach { destination ->
                        NavigationBarItem(selected = currentTab == destination.name, onClick = { selectTab(destination) },
                            icon = { Icon(destination.icon, null) }, label = { Text(stringResource(destination.title)) })
                    }
                }
            },
        ) { padding ->
            Row(Modifier.padding(padding).fillMaxSize()) {
                if (expanded) NavigationRail {
                    Destination.entries.forEach { destination ->
                        NavigationRailItem(selected = currentTab == destination.name, onClick = { selectTab(destination) },
                            icon = { Icon(destination.icon, null) }, label = { Text(stringResource(destination.title)) })
                    }
                }
                Column(Modifier.weight(1f)) {
                    if (isRoot && currentTab in listOf(Destination.MAP.name, Destination.EXPLORE.name)) {
                        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                        state.error?.let {
                            Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
                            if (state.snapshot != null) Text(stringResource(R.string.retained_data), modifier = Modifier.padding(horizontal = 16.dp))
                        }
                        if (state.snapshot?.configurationIncomplete == true) {
                            Text(stringResource(R.string.partial_configuration), modifier = Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    NavHost(navController = nav, startDestination = initialTab.route, modifier = Modifier.weight(1f)) {
                        composable<MapRoute> {
                            // One map for all regions: it re-frames itself and keeps the chosen map style.
                            MapScreen(state.snapshot?.takeIf { state.selection?.host == preferences.host }, focusedNode, ::openNode, feed = feed, onRefresh = onRefresh, regionControl = { RegionMenu(preferences, state) { focusedNode = null; focusedPosition = null; onRegion(it) } }, focusedCoordinate = focusedPosition?.let { org.nodescope.android.core.model.Coordinate.gps(it[0], it[1]) }, selectedRegion = preferences.region, sourceViewport = sourceViewport, routeReplay = replay, onExitReplay = { replay = null },
                                host = preferences.host, showActiveNodes = showActiveNodes, onActiveNodesShown = { showActiveNodes = false })
                        }
                        composable<ExploreRoute> { backStack ->
                            val monitoredList = monitoredChannels()?.channels?.collectAsStateWithLifecycle()?.value.orEmpty()
                            ExploreScreen(browse?.let { repository -> viewModel(backStack) { ExploreViewModel(repository) } }, preferences.host,
                                state.snapshot, feed, library, monitoredList, ExploreActions(
                                    onNode = ::openNode,
                                    onObserver = { id, name -> nav.navigate(ObserverRoute(id, name)) },
                                    onChannel = { id, name -> nav.navigate(ChannelRoute(id, name)) },
                                    onPacket = { hash, sender, text -> nav.navigate(MessagePacketRoute(hash, sender, text)) },
                                    onPackets = { nav.navigate(PacketRoute) },
                                    onMap = { selectTab(Destination.MAP) },
                                    onActiveNodes = { showActiveNodes = true; selectTab(Destination.MAP) },
                                    onChannels = { selectTab(Destination.CHANNELS) },
                                    onObservers = { activeOnly -> if (activeOnly) observerActiveRequest = System.nanoTime(); selectTab(Destination.OBSERVERS) },
                                ))
                        }
                        composable<PacketRoute> {
                            PacketScreen(feed, onReconnect, regionControl, { onRegion(null) }, onGroup = { nav.navigate(PacketDetailRoute(it)) })
                        }
                        composable<PacketDetailRoute> { backStack ->
                            val nodes = state.snapshot?.nodes.orEmpty()
                            PacketDetailScreen(feed, backStack.toRoute<PacketDetailRoute>().groupId, nodes, ::startReplay)
                        }
                        composable<MessagePacketRoute> { backStack ->
                            val route = backStack.toRoute<MessagePacketRoute>()
                            browse?.let { repository ->
                                LaunchedEffect(route.hash) { library.viewedMessage(route.hash, route.sender, route.text) }
                                MessagePacketScreen(viewModel(backStack) { PacketDetailViewModel(repository) }, preferences.host, route.hash,
                                    route.sender, route.text, state.snapshot?.nodes.orEmpty(), ::startReplay)
                            }
                        }
                        composable<ChannelsRoute> {
                            channelsModel(browse, monitoredChannels, it)?.let { channels ->
                                ChannelsScreen(channels, selection, feed, regionControl, { onRegion(null) },
                                    onChannel = { row -> nav.navigate(ChannelRoute(row.id, row.name)) }, onAdd = { nav.navigate(AddChannelRoute) })
                            } ?: UnavailableScreen(R.string.channels, R.string.channels_unavailable)
                        }
                        composable<ChannelRoute> { backStack ->
                            val route = backStack.toRoute<ChannelRoute>()
                            channelsModel(browse, monitoredChannels, parentEntry<ChannelsRoute>(nav, backStack))?.let { channels ->
                                val known = channels.channels.state.collectAsStateWithLifecycle().value.value?.firstOrNull { it.hash == route.id }
                                    ?: MeshChannel(route.id, route.name)
                                LaunchedEffect(route.id) { library.viewed(known) }
                                ChannelDetailScreen(channels, route.id, route.name, selection, feed, onRemoved = { nav.popBackStack() },
                                    onPacket = { hash, sender, text -> nav.navigate(MessagePacketRoute(hash, sender, text)) },
                                    favorite = library.isFavorite(SavedKind.CHANNEL, route.id), onFavorite = { library.toggle(known) })
                            }
                        }
                        composable<AddChannelRoute> {
                            monitoredChannels()?.let { store -> AddChannelScreen(store) { nav.popBackStack() } }
                        }
                        composable<ObserversRoute> {
                            observersModel(browse, it)?.let { observers ->
                                ObserversScreen(observers, selection, feed, regionControl, { onRegion(null) },
                                    onObserver = { observer -> nav.navigate(ObserverRoute(observer.id, observer.displayName)) },
                                    activeOnlyRequest = observerActiveRequest)
                            } ?: UnavailableScreen(R.string.observers, R.string.observers_unavailable)
                        }
                        composable<ObserverRoute> { backStack ->
                            observersModel(browse, parentEntry<ObserversRoute>(nav, backStack))?.let { observers ->
                                val id = backStack.toRoute<ObserverRoute>().id
                                ObserverDetailScreen(observers, preferences.host, id, favorite = library.isFavorite(SavedKind.OBSERVER, id),
                                    onFavorite = library::toggle, onViewed = library::viewed)
                            }
                        }
                        composable<SettingsRoute> {
                            SettingsScreen(preferences, feed.connection, state.error, sourceChangedAt, onAppearance,
                                onSource = { nav.navigate(SourceRoute) },
                                onDiagnostics = { if (diagnostics != null) nav.navigate(DiagnosticsRoute) },
                                onStorage = { if (cacheStorage != null) nav.navigate(StorageRoute) },
                                onAbout = { nav.navigate(AboutRoute) }, onMapLab = { nav.navigate(MapLabRoute) },
                                onSupport = { runCatching { uriHandler.openUri(SUPPORT_URL) } }, onDistanceUnit = onDistanceUnit)
                        }
                        composable<DiagnosticsRoute> { diagnostics?.let { DiagnosticsScreen(it, preferences.host, feed.connection) } }
                        composable<StorageRoute> { cacheStorage?.let { StorageScreen(it) { onRefresh() } } }
                        composable<AboutRoute> { AboutScreen() }
                        composable<NodeRoute> { backStack ->
                            val publicKey = backStack.toRoute<NodeRoute>().publicKey
                            val node = state.snapshot?.nodes?.firstOrNull { it.publicKey == publicKey } ?: library.node(publicKey)
                            NodeDetailScreen(browse?.let { repository -> viewModel(backStack) { NodeDetailViewModel(repository) } },
                                preferences.host, publicKey, node,
                                favorite = library.isFavorite(SavedKind.NODE, publicKey), onFavorite = library::toggle, onViewed = library::viewed,
                                onMap = { shown ->
                                    focusedNode = shown.publicKey
                                    focusedPosition = shown.coordinate?.let { point -> listOf(point.latitude, point.longitude) }
                                    selectTab(Destination.MAP)
                                },
                                onObserver = { id, name -> nav.navigate(ObserverRoute(id, name)) }, onNode = ::openNode,
                                onAnalytics = browse?.let { { nav.navigate(NodeAnalyticsRoute(publicKey)) } })
                        }
                        composable<NodeAnalyticsRoute> { backStack ->
                            val publicKey = backStack.toRoute<NodeAnalyticsRoute>().publicKey
                            browse?.let { repository ->
                                NodeAnalyticsScreen(viewModel(backStack) { NodeAnalyticsViewModel(repository) }, preferences.host, publicKey,
                                    state.snapshot?.nodes?.firstOrNull { it.publicKey == publicKey } ?: library.node(publicKey),
                                    onObserver = { id, name -> nav.navigate(ObserverRoute(id, name)) })
                            }
                        }
                        composable<SourceRoute> { sourceScreen { nav.popBackStack() } }
                        composable<MapLabRoute> {
                            val sample = remember { mapLabSnapshot() }
                            MapScreen(sample, onNode = {}, sampleRoute = sample.nodes.mapNotNull { it.coordinate })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RegionMenu(preferences: AppPreferences, state: SessionState, onRegion: (String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val regions = state.snapshot?.regions.orEmpty()
    Box {
        TextButton(onClick = { open = true }, enabled = regions.isNotEmpty() || preferences.region != null) {
            Text(preferences.region ?: stringResource(R.string.all_regions))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.all_regions)) }, onClick = { onRegion(null); open = false })
            regions.toSortedMap().forEach { (code, label) ->
                DropdownMenuItem(text = { Text("$label ($code)") }, onClick = { onRegion(code); open = false })
            }
        }
    }
}

/** List and detail share the list destination's model; a detail opened without its list falls back to its own entry. */
private inline fun <reified T : Any> parentEntry(nav: androidx.navigation.NavHostController, entry: androidx.navigation.NavBackStackEntry) =
    runCatching { nav.getBackStackEntry<T>() }.getOrDefault(entry)

@Composable
private fun channelsModel(browse: BrowseRepository?, store: () -> MonitoredChannelStore?, owner: androidx.lifecycle.ViewModelStoreOwner): ChannelsViewModel? {
    browse ?: return null
    val monitor = store() ?: return null
    return viewModel(owner) { ChannelsViewModel(browse, monitor) }
}

@Composable
private fun observersModel(browse: BrowseRepository?, owner: androidx.lifecycle.ViewModelStoreOwner): ObserversViewModel? {
    browse ?: return null
    return viewModel(owner) { ObserversViewModel(browse) }
}

@Composable
private fun UnavailableScreen(title: Int, description: Int) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.Start) {
        Text(stringResource(title), style = MaterialTheme.typography.headlineMedium)
        Text(stringResource(description))
    }
}
