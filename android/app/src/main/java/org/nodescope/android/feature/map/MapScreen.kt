package org.nodescope.android.feature.map

import kotlinx.coroutines.launch
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import android.animation.ValueAnimator
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import kotlinx.coroutines.isActive
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.style.TextOverflow
import org.nodescope.android.core.network.LiveFeedState
import org.nodescope.android.feature.packets.ConnectionBadge
import org.nodescope.android.feature.explore.NodeBrowser
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.first
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import org.nodescope.android.BuildConfig
import org.nodescope.android.R
import org.nodescope.android.core.model.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(snapshot: AnalyzerSnapshot?, focusedKey: String? = null, onNode: (String) -> Unit, sampleRoute: List<Coordinate> = emptyList(), feed: LiveFeedState = LiveFeedState(), onRefresh: () -> Unit = {}, regionControl: @Composable () -> Unit = {}, focusedCoordinate: Coordinate? = null, selectedRegion: String? = null, sourceViewport: RegionCoordinate? = null, routeReplay: RouteReplay? = null, onExitReplay: () -> Unit = {}, host: String? = null, showActiveNodes: Boolean = false, onActiveNodesShown: () -> Unit = {}, onShowPackets: (() -> Unit)? = null) {
    if (!BuildConfig.MAPS_CONFIGURED) {
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.map_unavailable), style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(R.string.map_unavailable_description))
        }
        return
    }
    val darkTheme = MaterialTheme.colorScheme.background.luminance() < 0.3f
    var mode by rememberSaveable { mutableIntStateOf(if (darkTheme) 2 else 0) }
    var customStyle by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(darkTheme) { if (!customStyle) mode = if (darkTheme) 2 else 0 }
    var search by remember { mutableStateOf(false) }
    var layersOpen by remember { mutableStateOf(false) }
    val context = LocalContext.current
    // CARTO, or Google Maps (beta) when this build has a key; remembered across launches.
    var providerChoice by remember { mutableStateOf(loadMapProvider(context)) }
    fun choose(choice: MapProviderChoice) { providerChoice = choice; saveMapProvider(context, choice) }
    var filtersOpen by remember { mutableStateOf(false) }
    // Saved per analyzer, as on iOS.
    var filters by remember(host) { mutableStateOf(host?.let { loadMapFilters(context, it) } ?: MapFilters()) }
    LaunchedEffect(host, filters) { host?.let { saveMapFilters(context, it, filters) } }
    LaunchedEffect(showActiveNodes) { if (showActiveNodes) { filters = MapFilters.ACTIVE_NODES; onActiveNodesShown() } }
    // An observer belongs to one region, so a region change clears the observer choice (iOS).
    var filterRegion by rememberSaveable { mutableStateOf(selectedRegion) }
    LaunchedEffect(selectedRegion) {
        if (filterRegion != selectedRegion) { filterRegion = selectedRegion; filters = filters.copy(observerId = null) }
    }
    val observerOptions = remember(feed.observers, selectedRegion) { mapObserverOptions(feed.observers, selectedRegion) }
    LaunchedEffect(feed.observersLoaded, observerOptions) {
        val chosen = filters.observerId ?: return@LaunchedEffect
        if (feed.observersLoaded && observerOptions.none { it.id.equals(chosen, true) }) filters = filters.copy(observerId = null)
    }
    // Activity windows move with time; re-check every minute while one is set.
    var clock by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(filters.activity) {
        clock = System.currentTimeMillis()
        while (filters.activity != ActivityFilter.ALL) { delay(60_000); clock = System.currentTimeMillis() }
    }
    val displayedNodes = remember(snapshot?.nodes, filters.roles, filters.activity, clock) {
        snapshot?.nodes.orEmpty().filter { it.coordinate != null && filters.shows(it, clock) }
    }
    var routeDetail by remember { mutableStateOf<RouteDetails?>(null) }
    var selectedNode by remember(host, selectedRegion) { mutableStateOf<MeshNode?>(null) }
    LaunchedEffect(routeReplay?.id) { selectedNode = null }
    val shownRoutes = remember { java.util.concurrent.atomic.AtomicReference<List<LiveRoute>>(emptyList()) }
    val currentAllNodes by rememberUpdatedState(snapshot?.nodes.orEmpty())
    // Replay mode (iOS): live traffic pauses; bottom controls replay the route, pick another
    // route, show only its nodes, or return to live.
    var replayActive by remember(routeReplay?.id) { mutableStateOf(routeReplay != null) }
    var replayIndex by remember(routeReplay?.id) { mutableIntStateOf(routeReplay?.selected ?: 0) }
    var routeOnly by remember(routeReplay?.id) { mutableStateOf(true) }
    var replayStart by remember(routeReplay?.id) { mutableLongStateOf(System.currentTimeMillis() + 700) }
    // A replay waits until the map has framed it and finished drawing (set by the framing
    // effect below), so it never starts while the camera is still moving or tiles are loading.
    var replayReady by remember(routeReplay?.id) { mutableStateOf(false) }
    val replayOption = routeReplay?.routes?.getOrNull(replayIndex)?.takeIf { replayActive }
    // The replay controls float over the bottom of the map rather than shrinking it: resizing
    // the MapView re-centres it mid-flight, which made starting a replay jitter. Bottom controls
    // and the framed route are kept clear of them instead.
    val showsReplayControls = replayActive && routeReplay != null
    var replayControlsHeight by remember { mutableIntStateOf(0) }
    val controlsInset by animateIntAsState(if (showsReplayControls) replayControlsHeight else 0, tween(250), label = "replay-inset")
    val mapNodes = if (replayOption != null && routeOnly) replayOption.nodes else displayedNodes
    var cameraValues by rememberSaveable { mutableStateOf(listOf(0.0, 0.0, 1.0, 0.0, 0.0)) }
    var initialized by rememberSaveable { mutableStateOf(false) }
    var lastRegion by rememberSaveable { mutableStateOf(selectedRegion) }
    // The analyzer the camera was last framed for; a different one frames afresh.
    var framedHost by rememberSaveable { mutableStateOf(host) }
    var lastFocus by rememberSaveable { mutableStateOf<String?>(null) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    // Switching provider replaces the engine; the new map opens where the old one was looking.
    val engine: MapEngineState = if (providerChoice.provider == MapProvider.GOOGLE) rememberGoogleMapEngine() else rememberMapLibreEngine()
    // Camera control once the map is ready, and overlay drawing once its base style has loaded.
    val camera = engine.camera
    val overlays = engine.overlays
    var retry by remember { mutableIntStateOf(0) }
    val currentOnNode by rememberUpdatedState(onNode)
    val currentDisplayedNodes by rememberUpdatedState(mapNodes)
    val selectNode by rememberUpdatedState<(String) -> Unit>({ key ->
        val node = (currentAllNodes + currentDisplayedNodes).firstOrNull { it.publicKey == key }
        if (node != null) { routeDetail = null; selectedNode = node } else currentOnNode(key)
    })
    val density = androidx.compose.ui.platform.LocalDensity.current.density
    // Region and location framing keep clear of the top controls and bottom count/attribution.
    val framingInsets = FramingInsets((24 * density).toInt(), (72 * density).toInt(), (24 * density).toInt(), (104 * density).toInt())
    fun refreshLabels(nodes: List<MeshNode>) {
        val current = engine.camera ?: return
        engine.overlays?.setNodeLabelsVisible(nodeLabelsVisible(current.visibleBounds(), nodes))
    }
    // "Center on my location" (iOS): permission is asked on the first tap, one fix is taken,
    // and the position is only kept in memory to draw the dot.
    val locationScope = rememberCoroutineScope()
    var userLocation by remember { mutableStateOf<Coordinate?>(null) }
    var locating by remember { mutableStateOf(false) }
    var locationMessage by remember { mutableStateOf<String?>(null) }
    fun centerOnUser() {
        if (locating) return
        locating = true
        locationScope.launch {
            val result = currentLocation(context)
            locating = false
            when (result) {
                is LocationResult.Found -> {
                    userLocation = result.coordinate
                    val box = regionBounds(RegionCoordinate(result.coordinate.latitude, result.coordinate.longitude, 10.0))
                    engine.camera?.animate(CameraMove.Fit(box, framingInsets), 600)
                }
                LocationResult.ServicesOff -> locationMessage = "Turn on location services to center the map on yourself."
                LocationResult.Unavailable -> locationMessage = "Couldn't find your location. Try again in a moment."
            }
        }
    }
    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (grants.values.any { it }) centerOnUser()
        else locationMessage = "Location permission is off. You can allow it in the app's settings."
    }
    LaunchedEffect(locationMessage) { if (locationMessage != null) { delay(5_000); locationMessage = null } }
    // Co-located markers fan out by a fixed screen distance; recompute when zoom settles.
    val spreadZoom = kotlin.math.round(cameraValues[2] * 4) / 4
    val nodes = remember(mapNodes, spreadZoom) { nodeMarkers(mapNodes, spreadCoincidentNodes(mapNodes, spreadZoom)) }
    val progress = remember { Animatable(0f) }
    var replay by remember { mutableIntStateOf(0) }
    val uriHandler = LocalUriHandler.current

    fun onTap(tap: MapTap): Boolean {
        // A route under the finger opens its details (iOS), unless a node marker is right there.
        val route = if (tap.nodeUnderFinger) null else tap.routeKeys().firstNotNullOfOrNull { key ->
            shownRoutes.get().firstOrNull { it.key == key && it.packet != null }
        }
        return when {
            tap.nodeUnderFinger -> { selectNode(tap.nodeKey!!); true }
            tap.expandGroup != null && tap.nodeKey == null -> { tap.expandGroup.invoke(); true }
            route != null -> { selectedNode = null; routeDetail = routeDetails(route.packet!!, currentAllNodes, route.receivedAt); true }
            tap.nodeKey != null -> { selectNode(tap.nodeKey); true }
            else -> false
        }
    }
    val routeOnlyReplay = replayOption != null && routeOnly
    LaunchedEffect(overlays, nodes, routeOnlyReplay) {
        // A route-only replay's nodes are never grouped, so no count badge outlives the replay.
        overlays?.setNodes(nodes, grouped = !routeOnlyReplay)
        refreshLabels(mapNodes)
    }
    // Region centers and names stay valid for the analyzer while a new region's nodes load,
    // so a region change can frame immediately instead of waiting (never with stale nodes).
    var framingSnapshot by remember(host) { mutableStateOf<AnalyzerSnapshot?>(null) }
    LaunchedEffect(snapshot) { snapshot?.let { framingSnapshot = it } }
    LaunchedEffect(camera, snapshot, focusedKey, focusedCoordinate, selectedRegion, sourceViewport, host) {
        val ready = camera ?: return@LaunchedEffect
        val data = snapshot ?: framingSnapshot?.takeIf { lastRegion != selectedRegion }?.copy(nodes = emptyList()) ?: return@LaunchedEffect
        val selected = data.nodes.firstOrNull { it.publicKey == focusedKey }?.coordinate ?: focusedCoordinate
        val regionChanged = lastRegion != selectedRegion
        val fresh = !initialized || framedHost != host
        if (fresh || regionChanged || (selected != null && focusedKey != lastFocus)) {
            val update = if (selected != null && !regionChanged) CameraMove.Center(selected, 13.0)
            else when (val target = cameraTarget(data, selectedRegion, sourceViewport)) {
                null -> null
                is CameraTarget.Center -> CameraMove.Center(target.coordinate, target.zoom)
                is CameraTarget.Bounds -> CameraMove.Fit(target, framingInsets)
            }
            if (update != null) {
                // Animate moves within an analyzer; the first framing of each analyzer is instant.
                if (fresh) ready.jump(update) else ready.animate(update, 600)
                initialized = true
                framedHost = host
                lastRegion = selectedRegion
            }
        }
        lastFocus = focusedKey
    }
    LaunchedEffect(overlays, userLocation) { overlays?.setUserLocation(userLocation) }
    LaunchedEffect(overlays, sampleRoute) { overlays?.setSampleRoute(sampleRoute) }
    LaunchedEffect(overlays, sampleRoute, progress.value) { overlays?.setSamplePacket(routePosition(sampleRoute, progress.value)) }
    LaunchedEffect(replay, sampleRoute) {
        if (replay == 0 || sampleRoute.isEmpty()) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            progress.snapTo(0f)
            if (ValueAnimator.areAnimatorsEnabled()) progress.animateTo(1f, tween(4000, easing = LinearEasing))
            else progress.snapTo(1f)
        }
    }
    val livePackets = feed.visiblePackets
    val paths = remember(livePackets, snapshot?.nodes, feed.observers, filters.observerId) {
        // Recent history appears as already-arrived routes while it is under 12 s old, as on iOS.
        val historyCutoff = System.currentTimeMillis() - RouteTiming.HISTORY_FADE
        livePackets.filter { (it.isLive || packetEpoch(it) > historyCutoff) && filters.showsRoute(it) }.take(40).map { packet ->
            val color = listOf("#66B9FF", "#FFC27A", "#65DDB4", "#BEA1FF")[(packet.hash.hashCode() and Int.MAX_VALUE) % 4]
            LiveRoute(packet.key, if (packet.isLive) packet.receivedAt else packetEpoch(packet), color,
                routeAnchors(packet, snapshot?.nodes.orEmpty(), feed.observers),
                packetRoute(packet, snapshot?.nodes.orEmpty(), feed.observers), historical = !packet.isLive, packet = packet)
        }
    }
    val replayRoute = remember(replayOption, replayStart, replayReady) {
        replayOption?.takeIf { replayReady }?.let { option ->
            LiveRoute("replay-${routeReplay?.id}-$replayIndex-$replayStart", replayStart, "#FFA833", nodeMarkers(option.nodes), option.subchains, replay = true)
        }
    }
    // While replaying, only the replayed route animates (live traffic resumes on "Live").
    val latestPaths by rememberUpdatedState(if (replayActive && routeReplay != null) listOfNotNull(replayRoute) else paths)
    SideEffect { shownRoutes.set(latestPaths) }
    var replayPlaying by remember { mutableStateOf(false) }
    LaunchedEffect(replayRoute) {
        val route = replayRoute ?: return@LaunchedEffect run { replayPlaying = false }
        replayPlaying = true
        // The button reflects travel along the route, not the fade that follows (as on iOS).
        delay((route.hops.maxOfOrNull { it.startsAt + it.travel } ?: 0L) - System.currentTimeMillis())
        replayPlaying = false
    }
    // Move the camera only when the replayed route isn't already in the clear part of the map,
    // so picking packets or routes that are already in view doesn't shake the map.
    LaunchedEffect(camera, routeReplay?.id, replayIndex) {
        val ready = camera ?: return@LaunchedEffect
        if (routeReplay == null) return@LaunchedEffect
        fun play() { replayStart = System.currentTimeMillis() + 150; replayReady = true }
        val points = replayOption?.subchains?.flatten().orEmpty()
        if (points.isEmpty()) return@LaunchedEffect play()
        // A map that was just created (e.g. arriving from Channels) needs its style first.
        withTimeoutOrNull(3_000) { snapshotFlow { engine.overlays }.first { it != null } }
        // Judge and frame against the controls' real height (measured on their first layout),
        // so the camera makes one move instead of correcting itself as they appear.
        withTimeoutOrNull(300) { snapshotFlow { replayControlsHeight }.first { it > 0 } }
        val insets = replayFramingInsets(ready.width, ready.height, replayControlsHeight, density)
        val screen = points.map(ready::toScreen)
        if (!replayNeedsFraming(screen, ready.width, ready.height, insets, ready.zoom)) return@LaunchedEffect play()
        val update = if (points.size > 1) {
            val bounds = boundsOf(points)
            val fitted = ready.fittedZoom(bounds, insets)
            // Nodes a few hundred metres apart would fit at building level; stop at street level.
            if (fitted != null && fitted > REPLAY_MAX_ZOOM) CameraMove.Center(bounds.center, REPLAY_MAX_ZOOM)
            else CameraMove.Fit(bounds, insets)
        }
        // A single point is centred: the controls cover at most the bottom 35%.
        else CameraMove.Center(points[0], maxOf(ready.zoom, REPLAY_POINT_ZOOM))
        withTimeoutOrNull(1_500) { ready.animateAndWait(update, 500) }
        // Then let the new area's tiles load (the map reports idle once everything is drawn).
        withTimeoutOrNull(1_500) { ready.awaitIdle() }
        play()
    }
    LaunchedEffect(overlays) {
        val drawing = overlays ?: return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            // Anchor wall-clock receipt timestamps once; clock corrections cannot jump particles.
            val epoch = System.currentTimeMillis()
            val start = android.os.SystemClock.elapsedRealtime()
            var previousRoutes: List<LiveRoute>? = null
            while (isActive) {
                awaitMapFrame()
                val now = epoch + android.os.SystemClock.elapsedRealtime() - start
                val animate = ValueAnimator.areAnimatorsEnabled()
                val active = latestPaths.filter { it.isActive(now) }
                // Temporary route markers only change when routes enter or leave.
                if (active != previousRoutes) {
                    drawing.setRouteAnchors(active.flatMap { it.anchors }.distinctBy { it.anchorId })
                    previousRoutes = active
                }
                drawing.setRouteFrame(routeFrame(active, now, animate))
                val observedPaths = latestPaths
                // A replay is scheduled slightly ahead so the camera can frame it first.
                val nextStart = observedPaths.filter { it.receivedAt > now }.minOfOrNull { it.receivedAt }
                if (active.isEmpty() && nextStart != null) {
                    withTimeoutOrNull(nextStart - now) { snapshotFlow { latestPaths }.first { it !== observedPaths } }
                } else if (active.isEmpty()) {
                    // No display callbacks or source updates while the map is idle.
                    snapshotFlow { latestPaths }.first { it !== observedPaths }
                } else if (!animate) delay(100)
            }
        }
    }
    selectedNode?.let { node ->
        MapNodeSelectionSheet(node, onDetails = { selectedNode = null; currentOnNode(node.publicKey) },
            onDismiss = { selectedNode = null })
    }
    if (filtersOpen) MapFiltersSheet(filters, observerOptions, onChange = { filters = it }, onDismiss = { filtersOpen = false })
    routeDetail?.let { details -> RouteDetailsSheet(details, onNode = { routeDetail = null; currentOnNode(it) }, onDismiss = { routeDetail = null }) }
    if (search) ModalBottomSheet(onDismissRequest = { search = false }) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.8f)) {
            Text("Search nodes", Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.titleLarge)
            NodeBrowser(snapshot?.nodes.orEmpty(), snapshot?.total ?: 0) { key ->
                search = false
                snapshot?.nodes?.firstOrNull { it.publicKey == key }?.coordinate?.let {
                    engine.camera?.animate(CameraMove.Center(it, 13.0))
                }
                selectNode(key)
            }
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
    val replayPanelMaxHeight = maxHeight * 0.35f
    Column(Modifier.fillMaxSize()) {
        // The app shell's Scaffold already applies the status-bar inset; don't add it twice.
        TopAppBar(windowInsets = WindowInsets(0, 0, 0, 0), title = {
            Column {
                Text("Live Map", style = MaterialTheme.typography.titleLarge)
                ConnectionBadge(feed.connection)
            }
        }, actions = {
            // Wide screens with the live packets panel hidden get it back from here.
            onShowPackets?.let { IconButton(onClick = it) { Icon(Icons.Outlined.Sensors, "Show live packets") } }
            IconButton(onClick = onRefresh) { Icon(Icons.Outlined.Refresh, "Refresh map data") }
            IconButton(onClick = { search = true }) { Icon(Icons.Outlined.Search, "Search nodes") }
            Box {
                IconButton(onClick = { layersOpen = true }) { Icon(Icons.Outlined.Layers, "Map layers") }
                DropdownMenu(layersOpen, { layersOpen = false }) {
                    val google = BuildConfig.GOOGLE_MAPS_CONFIGURED
                    // Grouped by provider when there is more than one.
                    if (google) MenuSectionHeader(stringResource(R.string.map_provider_carto))
                    listOf(R.string.standard, R.string.light, R.string.dark).forEachIndexed { index, label ->
                        val chosen = providerChoice.provider == MapProvider.CARTO && mode == index
                        DropdownMenuItem(text = { Text(stringResource(label)) }, onClick = {
                            mode = index; customStyle = true; choose(providerChoice.copy(provider = MapProvider.CARTO)); layersOpen = false
                        }, trailingIcon = { if (chosen) Icon(Icons.Outlined.Check, null) })
                    }
                    if (google) {
                        HorizontalDivider()
                        MenuSectionHeader(stringResource(R.string.map_provider_google))
                        listOf(GoogleMapType.MAP to R.string.google_map, GoogleMapType.SATELLITE to R.string.google_satellite,
                            GoogleMapType.TERRAIN to R.string.google_terrain, GoogleMapType.HYBRID to R.string.google_hybrid).forEach { (type, label) ->
                            val chosen = providerChoice.provider == MapProvider.GOOGLE && providerChoice.googleType == type
                            DropdownMenuItem(text = { Text(stringResource(label)) }, onClick = {
                                choose(MapProviderChoice(MapProvider.GOOGLE, type)); layersOpen = false
                            }, trailingIcon = { if (chosen) Icon(Icons.Outlined.Check, null) })
                        }
                    }
                }
            }
        })
    Box(Modifier.weight(1f).fillMaxWidth()) {
        val onCameraIdle: (SavedCamera) -> Unit = { cameraValues = it; refreshLabels(currentDisplayedNodes) }
        when (engine) {
            is MapLibreEngine -> MapLibreEngineHost(engine, styleMode = mode, retry = retry, bottomInset = controlsInset, savedCamera = cameraValues,
                onCameraIdle = onCameraIdle, onTap = ::onTap, modifier = Modifier.fillMaxSize())
            is GoogleMapEngine -> GoogleMapEngineHost(engine, mapType = providerChoice.googleType, dark = darkTheme, bottomInset = controlsInset,
                savedCamera = cameraValues, onCameraIdle = onCameraIdle, onTap = ::onTap, modifier = Modifier.fillMaxSize())
        }
        Column(Modifier.align(Alignment.TopStart).fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = MaterialTheme.shapes.medium, shadowElevation = 1.dp) { regionControl() }
                Surface(shape = MaterialTheme.shapes.medium, shadowElevation = 1.dp) {
                    TextButton(onClick = { filtersOpen = true }, modifier = Modifier.semantics {
                        stateDescription = if (filters.isFiltering) "${filters.activeCount} active" else "None"
                    }, colors = if (filters.isFiltering) ButtonDefaults.textButtonColors() else ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface)) {
                        Icon(Icons.Outlined.FilterList, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (filters.isFiltering) "${filters.activeCount} active" else "Filters")
                    }
                }
            }
            locationMessage?.let { message ->
                Surface(shape = MaterialTheme.shapes.medium, tonalElevation = 4.dp) {
                    Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(message, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        IconButton(onClick = { locationMessage = null }) { Icon(Icons.Outlined.Close, "Dismiss") }
                    }
                }
            }
            if (engine.failed) Surface(shape = MaterialTheme.shapes.medium, tonalElevation = 4.dp) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    val google = engine is GoogleMapEngine
                    Text(stringResource(if (google) R.string.google_maps_unavailable else R.string.map_load_failed), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    if (!google) TextButton(onClick = { retry++ }) { Text(stringResource(R.string.retry)) }
                }
            }
            if (sampleRoute.isNotEmpty()) Surface(shape = MaterialTheme.shapes.medium) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                    Text(stringResource(R.string.sample_route), style = MaterialTheme.typography.labelMedium)
                    TextButton(onClick = { replay++ }) { Text(stringResource(R.string.replay_sample)) }
                }
            }
        }
        if (engine.loading) CircularProgressIndicator(Modifier.align(Alignment.Center))
        // Location sits above zoom, which sits just above the attribution line at the right edge.
        Column(Modifier.align(Alignment.BottomEnd).offset { IntOffset(0, -controlsInset) }.padding(end = 12.dp, bottom = 56.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Surface(shape = MaterialTheme.shapes.medium, shadowElevation = 2.dp) {
                IconButton(onClick = { if (hasLocationPermission(context)) centerOnUser() else locationPermission.launch(LOCATION_PERMISSIONS) }, enabled = !locating) {
                    if (locating) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Outlined.MyLocation, "Center on my location")
                }
            }
            Surface(shape = MaterialTheme.shapes.medium, shadowElevation = 2.dp) {
                Column {
                    IconButton(onClick = { engine.camera?.animate(CameraMove.ZoomIn) }) { Icon(Icons.Outlined.Add, stringResource(R.string.zoom_in)) }
                    HorizontalDivider(Modifier.width(32.dp).align(Alignment.CenterHorizontally))
                    IconButton(onClick = { engine.camera?.animate(CameraMove.ZoomOut) }) { Icon(Icons.Outlined.Remove, stringResource(R.string.zoom_out)) }
                }
            }
        }
        // Required CARTO/OpenStreetMap attribution: one line, bottom right (MapLibre's own logo is bottom left).
        // Google Maps draws its own logo and attribution.
        if (engine is MapLibreEngine) Surface(Modifier.align(Alignment.BottomEnd).offset { IntOffset(0, -controlsInset) }.padding(end = 8.dp, bottom = 6.dp), shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f)) {
            Row {
                TextButton(onClick = { uriHandler.openUri("https://www.openstreetmap.org/copyright") }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Text("© OpenStreetMap", style = MaterialTheme.typography.labelSmall)
                }
                TextButton(onClick = { uriHandler.openUri("https://carto.com/attributions") }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Text("© CARTO", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        // Node count centered just above the MapLibre logo (measured; falls back to bottom left).
        var countSize by remember { mutableStateOf(IntSize.Zero) }
        val gap = with(LocalDensity.current) { 4.dp.roundToPx() }
        val logo = engine.logoFrame
        Surface((if (logo != null && countSize != IntSize.Zero) Modifier.align(Alignment.TopStart).offset {
                IntOffset((logo.centerX() - countSize.width / 2).coerceAtLeast(gap), logo.top - gap - countSize.height)
            } else Modifier.align(Alignment.BottomStart).offset { IntOffset(0, -controlsInset) }.padding(start = 8.dp, bottom = 34.dp)).onSizeChanged { countSize = it },
            shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f)) {
            Text("${mapNodes.size} nodes", Modifier.padding(horizontal = 14.dp, vertical = 6.dp), style = MaterialTheme.typography.labelMedium)
        }
        // Kept while sliding away, after the replay itself has been cleared.
        var lastReplay by remember { mutableStateOf(routeReplay) }
        if (routeReplay != null) lastReplay = routeReplay
        SlideUpVisibility(showsReplayControls, Modifier.align(Alignment.BottomCenter)) {
            lastReplay?.let { shown ->
                ReplayControls(
                    playing = replayPlaying || !replayReady, routes = shown.routes, selected = replayIndex, routeOnly = routeOnly,
                    onPlay = { replayStart = System.currentTimeMillis() + 150 },
                    onLive = { replayActive = false; onExitReplay() },
                    // Another route waits for its own framing; the same one just restarts.
                    onRoute = { if (it == replayIndex) replayStart = System.currentTimeMillis() + 150 else { replayReady = false; replayIndex = it } },
                    onRouteOnly = { routeOnly = !routeOnly },
                    modifier = Modifier.fillMaxWidth().heightIn(max = replayPanelMaxHeight).onSizeChanged { replayControlsHeight = it.height })
            }
        }
    }
    }
}

}

/**
 * The part of the map a replayed route must stay inside: clear of the region and filter
 * buttons (top), the zoom and location buttons (right), and the node count, logo and
 * attribution sitting on top of the replay controls (bottom). Shrunk proportionally when the
 * map is too small for all of it (landscape phones), so the camera always has room to frame.
 */
internal data class FramingInsets(val left: Int, val top: Int, val right: Int, val bottom: Int)

internal fun replayFramingInsets(width: Int, height: Int, controlsHeight: Int, density: Float): FramingInsets {
    fun dp(value: Int) = (value * density).toInt()
    var left = maxOf(dp(24), (width * 0.06f).toInt())
    var right = dp(76)
    var top = dp(72)
    var bottom = controlsHeight + dp(72)
    if (left + right > width * 0.6f) { val scale = width * 0.6f / (left + right); left = (left * scale).toInt(); right = (right * scale).toInt() }
    if (top + bottom > height * 0.8f) { val scale = height * 0.8f / (top + bottom); top = (top * scale).toInt(); bottom = (bottom * scale).toInt() }
    return FramingInsets(left, top, right, bottom)
}

internal const val REPLAY_MAX_ZOOM = 14.0
internal const val REPLAY_POINT_ZOOM = 12.0
/** A route spanning less than half the clear area, both ways, is zoomed in to. */
private const val REPLAY_SMALL_SHARE = 0.5f

/**
 * Whether a replayed route (its points on screen, in pixels) needs the camera to move: when any
 * point is outside the clear area, or the route is so small there that it's hard to follow
 * (under half the area both ways, or a lone point while zoomed far out). Routes
 * already framed at street level are left alone.
 */
internal fun replayNeedsFraming(points: List<Pair<Float, Float>>, width: Int, height: Int, insets: FramingInsets, zoom: Double): Boolean {
    if (points.isEmpty()) return false
    val left = insets.left.toFloat(); val top = insets.top.toFloat()
    val right = (width - insets.right).toFloat(); val bottom = (height - insets.bottom).toFloat()
    if (points.any { (x, y) -> x < left || x > right || y < top || y > bottom }) return true
    if (points.size == 1) return zoom < REPLAY_POINT_ZOOM - 2
    if (zoom >= REPLAY_MAX_ZOOM) return false
    val spanX = points.maxOf { it.first } - points.minOf { it.first }
    val spanY = points.maxOf { it.second } - points.minOf { it.second }
    return spanX < (right - left) * REPLAY_SMALL_SHARE && spanY < (bottom - top) * REPLAY_SMALL_SHARE
}

/** A non-interactive group title inside a dropdown menu. */
@Composable
private fun MenuSectionHeader(title: String) {
    Text(title, Modifier.padding(horizontal = 16.dp, vertical = 8.dp).semantics { heading() },
        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Slides in from the bottom edge (outside any Row/Column scope, which have their own overloads). */
@Composable
private fun SlideUpVisibility(visible: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    AnimatedVisibility(visible, modifier, enter = slideInVertically(tween(250)) { it }, exit = slideOutVertically(tween(200)) { it }) { content() }
}

/** iOS replay controls: Replay / Play again with Live, then route choice and Route only. */
@Composable
internal fun ReplayControls(playing: Boolean, routes: List<ReplayRoute>, selected: Int, routeOnly: Boolean,
    onPlay: () -> Unit, onLive: () -> Unit, onRoute: (Int) -> Unit, onRouteOnly: () -> Unit, modifier: Modifier = Modifier) {
    Surface(modifier, color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Route replay", style = MaterialTheme.typography.titleSmall)
                    Text(if (playing) "Playing · live routes paused" else "Finished · live routes paused",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = onLive) { Icon(Icons.Outlined.Close, "Exit replay and return to live") }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp),
                itemVerticalAlignment = Alignment.CenterVertically) {
                FilledTonalButton(onClick = onPlay) {
                    Icon(Icons.Outlined.Replay, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp)); Text(if (playing) "Restart" else "Play again")
                }
                if (routes.size > 1) {
                    var open by remember { mutableStateOf(false) }
                    Box {
                        OutlinedButton(onClick = { open = true }) {
                            Text("Route ${selected + 1} of ${routes.size}")
                            Icon(Icons.Outlined.ArrowDropDown, null)
                        }
                        DropdownMenu(open, { open = false }) {
                            routes.forEachIndexed { index, route ->
                                DropdownMenuItem(text = { Text("Route ${index + 1} · ${route.hops} hops") },
                                    onClick = { onRoute(index); open = false },
                                    trailingIcon = { if (index == selected) Icon(Icons.Outlined.Check, "Selected", Modifier.size(18.dp)) })
                            }
                        }
                    }
                } else routes.getOrNull(selected)?.let { Text("${it.hops} hops", style = MaterialTheme.typography.bodySmall) }
                FilterChip(selected = routeOnly, onClick = onRouteOnly, label = { Text("Route nodes only") },
                    leadingIcon = { Icon(if (routeOnly) Icons.Outlined.Check else Icons.Outlined.Hub, null, Modifier.size(18.dp)) })
                TextButton(onClick = onLive) { Text("Return to live") }
            }
        }
    }
}

/** Explicit debug-only synthetic fixture, never mixed into analyzer data. */
fun mapLabSnapshot(): AnalyzerSnapshot {
    val points = listOf(Coordinate(30.2672, -97.7431), Coordinate(30.30, -97.72), Coordinate(30.33, -97.68))
    return AnalyzerSnapshot(points.mapIndexed { index, point ->
        MeshNode("sample-$index", "Sample node ${index + 1}", "repeater", point.latitude, point.longitude, "2026-01-01T00:00:00Z")
    }, 3, emptyMap(), MapDefaults(listOf(30.30, -97.72), 11.0))
}

internal fun regionSpan(region: RegionCoordinate): Pair<Double, Double> {
    val radius = region.radiusKm?.takeIf { it.isFinite() && it > 0 }?.coerceAtMost(2000.0) ?: 45.0
    val latitude = ((radius * 2.4) / 111).coerceAtLeast(0.15)
    return latitude to (latitude / kotlin.math.cos(Math.toRadians(region.lat)).coerceAtLeast(0.2))
}
