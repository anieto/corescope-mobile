package org.nodescope.android.feature.map

import kotlinx.coroutines.launch
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import android.animation.ValueAnimator
import android.graphics.RectF
import android.view.Choreographer
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
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
import androidx.core.view.isVisible
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression.*
import org.maplibre.android.style.layers.*
import org.maplibre.android.style.layers.PropertyFactory.*
import org.maplibre.android.style.sources.GeoJsonOptions
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.*
import org.nodescope.android.BuildConfig
import org.nodescope.android.R
import org.nodescope.android.core.model.*

private const val NODES = "nodescope-nodes"
private const val POINTS = "nodescope-points"
private const val CLUSTERS = "nodescope-clusters"
private const val NODE_LABELS = "nodescope-node-labels"
private const val ROUTE_NODES = "nodescope-route-nodes"
private const val ROUTE_NODE_POINTS = "nodescope-route-node-points"
private const val ROUTE = "nodescope-route"
private const val PACKET = "nodescope-packet"
private const val USER_LOCATION = "nodescope-user-location"
private val emptyFeatures get() = FeatureCollection.fromFeatures(emptyList<Feature>())

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(snapshot: AnalyzerSnapshot?, focusedKey: String? = null, onNode: (String) -> Unit, sampleRoute: List<Coordinate> = emptyList(), feed: LiveFeedState = LiveFeedState(), onRefresh: () -> Unit = {}, regionControl: @Composable () -> Unit = {}, focusedCoordinate: Coordinate? = null, selectedRegion: String? = null, sourceViewport: RegionCoordinate? = null, routeReplay: RouteReplay? = null, onExitReplay: () -> Unit = {}, host: String? = null, showActiveNodes: Boolean = false, onActiveNodesShown: () -> Unit = {}) {
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
    val shownRoutes = remember { java.util.concurrent.atomic.AtomicReference<List<LiveRoute>>(emptyList()) }
    val currentAllNodes by rememberUpdatedState(snapshot?.nodes.orEmpty())
    // Replay mode (iOS): live traffic pauses; bottom controls replay the route, pick another
    // route, show only its nodes, or return to live.
    var replayActive by remember(routeReplay?.id) { mutableStateOf(routeReplay != null) }
    var replayIndex by remember(routeReplay?.id) { mutableIntStateOf(routeReplay?.selected ?: 0) }
    var routeOnly by remember(routeReplay?.id) { mutableStateOf(true) }
    var replayStart by remember(routeReplay?.id) { mutableLongStateOf(System.currentTimeMillis() + 700) }
    val replayOption = routeReplay?.routes?.getOrNull(replayIndex)?.takeIf { replayActive }
    val mapNodes = if (replayOption != null && routeOnly) replayOption.nodes else displayedNodes
    var cameraValues by rememberSaveable { mutableStateOf(listOf(0.0, 0.0, 1.0, 0.0, 0.0)) }
    var initialized by rememberSaveable { mutableStateOf(false) }
    var lastRegion by rememberSaveable { mutableStateOf(selectedRegion) }
    // The analyzer the camera was last framed for; a different one frames afresh.
    var framedHost by rememberSaveable { mutableStateOf(host) }
    var lastFocus by rememberSaveable { mutableStateOf<String?>(null) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val view = remember(context) { MapView(context).apply { onCreate(null) } }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var style by remember { mutableStateOf<Style?>(null) }
    var failed by remember { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }
    val currentOnNode by rememberUpdatedState(onNode)
    val currentDisplayedNodes by rememberUpdatedState(mapNodes)
    val density = androidx.compose.ui.platform.LocalDensity.current.density
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
                    map?.animateCamera(CameraUpdateFactory.newLatLngBounds(LatLngBounds.from(box.north, box.east, box.south, box.west),
                        (24 * density).toInt(), (72 * density).toInt(), (24 * density).toInt(), (104 * density).toInt()), 600)
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
    val nodes = remember(mapNodes, spreadZoom) { nodeFeatures(mapNodes, spreadCoincidentNodes(mapNodes, spreadZoom)) }
    val progress = remember { Animatable(0f) }
    var replay by remember { mutableIntStateOf(0) }
    val uriHandler = LocalUriHandler.current

    // MapLibre draws its logo as an ImageView inside the MapView; track where it lands.
    var logoFrame by remember { mutableStateOf<android.graphics.Rect?>(null) }
    DisposableEffect(view) {
        val listener = android.view.View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> logoFrame = findLogoFrame(view) }
        view.addOnLayoutChangeListener(listener)
        onDispose { view.removeOnLayoutChangeListener(listener) }
    }
    DisposableEffect(view, lifecycle) {
        var alive = true
        var started = false
        var resumed = false
        fun sync() {
            val active = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            val foreground = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            if (active && !started) { view.onStart(); started = true }
            if (foreground && !resumed) { view.onResume(); resumed = true }
            if (!foreground && resumed) { view.onPause(); resumed = false }
            if (!active && started) { view.onStop(); started = false }
        }
        val observer = LifecycleEventObserver { _, _ -> sync() }
        lifecycle.addObserver(observer)
        sync()
        val failure = MapView.OnDidFailLoadingMapListener { if (alive) failed = true }
        view.addOnDidFailLoadingMapListener(failure)
        view.getMapAsync { ready ->
            if (alive) {
                ready.cameraPosition = CameraPosition.Builder()
                    .target(LatLng(cameraValues[0], cameraValues[1])).zoom(cameraValues[2])
                    .bearing(cameraValues[3]).tilt(cameraValues[4]).build()
                ready.addOnCameraIdleListener {
                    ready.cameraPosition.let { c -> c.target?.let { cameraValues = listOf(it.latitude, it.longitude, c.zoom, c.bearing, c.tilt) } }
                    updateNodeLabels(ready, currentDisplayedNodes)
                }
                ready.addOnMapClickListener { coordinate ->
                    val point = ready.projection.toScreenLocation(coordinate)
                    val radius = 24 * context.resources.displayMetrics.density
                    val hits = ready.queryRenderedFeatures(RectF(point.x-radius, point.y-radius, point.x+radius, point.y+radius), ROUTE_NODE_POINTS, POINTS, CLUSTERS)
                    // Spread co-located nodes sit close together: pick the marker nearest the tap.
                    fun distance(feature: Feature) = (feature.geometry() as? Point)?.let {
                        val screen = ready.projection.toScreenLocation(LatLng(it.latitude(), it.longitude()))
                        kotlin.math.hypot((screen.x - point.x).toDouble(), (screen.y - point.y).toDouble())
                    } ?: Double.MAX_VALUE
                    val node = hits.filter { it.hasProperty("publicKey") }.minByOrNull(::distance)
                    val cluster = hits.filter { it.hasProperty("cluster_id") }.minByOrNull(::distance)
                    // A route under the finger opens its details (iOS), unless a node marker is right there.
                    val route = if (node != null && distance(node) <= radius / 2) null else ready.queryRenderedFeatures(
                        RectF(point.x-radius, point.y-radius, point.x+radius, point.y+radius), "live-route-halo")
                        .firstNotNullOfOrNull { line ->
                            val key = if (line.hasProperty("route")) line.getStringProperty("route") else null
                            shownRoutes.get().firstOrNull { it.key == key && it.packet != null }
                        }
                    when {
                        node != null && distance(node) <= radius / 2 -> { currentOnNode(node.getStringProperty("publicKey")); true }
                        cluster != null && node == null -> {
                            val center = cluster.geometry() as? Point
                            val source = ready.style?.getSourceAs<GeoJsonSource>(NODES)
                            if (center != null && source != null) ready.animateCamera(CameraUpdateFactory.newLatLngZoom(
                                LatLng(center.latitude(), center.longitude()), source.getClusterExpansionZoom(cluster).toDouble()))
                            true
                        }
                        route != null -> { routeDetail = routeDetails(route.packet!!, currentAllNodes, route.receivedAt); true }
                        node != null -> { currentOnNode(node.getStringProperty("publicKey")); true }
                        else -> false
                    }
                }
                map = ready
            }
        }
        onDispose {
            alive = false
            lifecycle.removeObserver(observer)
            view.removeOnDidFailLoadingMapListener(failure)
            if (resumed) view.onPause()
            if (started) view.onStop()
            view.onDestroy()
        }
    }
    LaunchedEffect(map, mode, retry) {
        val ready = map ?: return@LaunchedEffect
        failed = false
        style = null
        val name = listOf("voyager", "positron", "dark-matter")[mode]
        ready.setStyle("https://basemaps.cartocdn.com/gl/$name-gl-style/style.json") { loaded ->
            if (!view.isDestroyed) { addOverlays(loaded, dark = mode == 2); style = loaded; updateNodeLabels(ready, currentDisplayedNodes) }
        }
    }
    LaunchedEffect(style, nodes) {
        style?.getSourceAs<GeoJsonSource>(NODES)?.setGeoJson(nodes)
        map?.let { updateNodeLabels(it, mapNodes) }
    }
    // Region centers and names stay valid for the analyzer while a new region's nodes load,
    // so a region change can frame immediately instead of waiting (never with stale nodes).
    var framingSnapshot by remember(host) { mutableStateOf<AnalyzerSnapshot?>(null) }
    LaunchedEffect(snapshot) { snapshot?.let { framingSnapshot = it } }
    LaunchedEffect(map, snapshot, focusedKey, focusedCoordinate, selectedRegion, sourceViewport, host) {
        val ready = map ?: return@LaunchedEffect
        val data = snapshot ?: framingSnapshot?.takeIf { lastRegion != selectedRegion }?.copy(nodes = emptyList()) ?: return@LaunchedEffect
        val selected = data.nodes.firstOrNull { it.publicKey == focusedKey }?.coordinate ?: focusedCoordinate
        val regionChanged = lastRegion != selectedRegion
        val fresh = !initialized || framedHost != host
        if (fresh || regionChanged || (selected != null && focusedKey != lastFocus)) {
            val update = if (selected != null && !regionChanged) CameraUpdateFactory.newLatLngZoom(LatLng(selected.latitude, selected.longitude), 13.0)
            else when (val target = cameraTarget(data, selectedRegion, sourceViewport)) {
                null -> null
                is CameraTarget.Center -> CameraUpdateFactory.newLatLngZoom(LatLng(target.coordinate.latitude, target.coordinate.longitude), target.zoom)
                is CameraTarget.Bounds -> {
                    // Keep the framed area clear of the top controls and bottom count/attribution.
                    CameraUpdateFactory.newLatLngBounds(LatLngBounds.from(target.north, target.east, target.south, target.west),
                        (24 * density).toInt(), (72 * density).toInt(), (24 * density).toInt(), (104 * density).toInt())
                }
            }
            if (update != null) {
                // Animate moves within an analyzer; the first framing of each analyzer is instant.
                if (fresh) ready.moveCamera(update) else ready.animateCamera(update, 600)
                initialized = true
                framedHost = host
                lastRegion = selectedRegion
            }
        }
        lastFocus = focusedKey
    }
    LaunchedEffect(style, userLocation) {
        style?.getSourceAs<GeoJsonSource>(USER_LOCATION)?.setGeoJson(userLocation?.let {
            FeatureCollection.fromFeatures(listOf(Feature.fromGeometry(Point.fromLngLat(it.longitude, it.latitude))))
        } ?: emptyFeatures)
    }
    LaunchedEffect(style, sampleRoute) {
        style?.getSourceAs<GeoJsonSource>(ROUTE)?.setGeoJson(if (sampleRoute.size > 1)
            FeatureCollection.fromFeatures(listOf(Feature.fromGeometry(LineString.fromLngLats(sampleRoute.map { Point.fromLngLat(it.longitude, it.latitude) })) ))
            else emptyFeatures)
    }
    LaunchedEffect(style, sampleRoute, progress.value) {
        val point = routePosition(sampleRoute, progress.value)
        style?.getSourceAs<GeoJsonSource>(PACKET)?.setGeoJson(if (point == null) emptyFeatures
            else FeatureCollection.fromFeatures(listOf(Feature.fromGeometry(Point.fromLngLat(point.longitude, point.latitude)))))
    }
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
                routeAnchorFeatures(packet, snapshot?.nodes.orEmpty(), feed.observers),
                packetRoute(packet, snapshot?.nodes.orEmpty(), feed.observers), historical = !packet.isLive, packet = packet)
        }
    }
    val replayRoute = remember(replayOption, replayStart) {
        replayOption?.let { option ->
            val anchors = nodeFeatures(option.nodes).features().orEmpty().onEach { it.addStringProperty("anchorId", "node:" + it.getStringProperty("publicKey")) }
            LiveRoute("replay-${routeReplay?.id}-$replayIndex-$replayStart", replayStart, "#FFA833", anchors, option.subchains, replay = true)
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
    // Frame each new replay; when switching routes, move only if the new one is off screen.
    var framedReplay by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(map, routeReplay?.id, replayIndex) {
        val ready = map ?: return@LaunchedEffect
        val points = replayOption?.subchains?.flatten().orEmpty().map { LatLng(it.latitude, it.longitude) }
        val visible = ready.projection.visibleRegion.latLngBounds
        if (points.isEmpty() || framedReplay == routeReplay?.id && points.all(visible::contains)) return@LaunchedEffect
        framedReplay = routeReplay?.id
        if (points.size > 1) ready.animateCamera(CameraUpdateFactory.newLatLngBounds(LatLngBounds.Builder().includes(points).build(),
            (32 * density).toInt(), (96 * density).toInt(), (32 * density).toInt(), (220 * density).toInt()), 500)
        else ready.animateCamera(CameraUpdateFactory.newLatLngZoom(points[0], 12.0), 500)
    }
    LaunchedEffect(style) {
        val currentStyle = style ?: return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            // Anchor wall-clock receipt timestamps once; clock corrections cannot jump particles.
            val epoch = System.currentTimeMillis()
            val start = android.os.SystemClock.elapsedRealtime()
            var previousRoutes: List<LiveRoute>? = null
            val lineSource = currentStyle.getSourceAs<GeoJsonSource>("live-lines")
            val dotSource = currentStyle.getSourceAs<GeoJsonSource>("live-dots")
            val ringSource = currentStyle.getSourceAs<GeoJsonSource>("live-rings")
            while (isActive) {
                awaitMapFrame()
                val now = epoch + android.os.SystemClock.elapsedRealtime() - start
                val animate = ValueAnimator.areAnimatorsEnabled()
                val active = latestPaths.filter { it.isActive(now) }
                // Temporary route markers only change when routes enter or leave.
                if (active != previousRoutes) {
                    currentStyle.getSourceAs<GeoJsonSource>(ROUTE_NODES)?.setGeoJson(FeatureCollection.fromFeatures(
                        active.flatMap { it.anchors }.distinctBy { it.getStringProperty("anchorId") }))
                    previousRoutes = active
                }
                val frame = routeFrame(active, now, animate)
                lineSource?.setGeoJson(FeatureCollection.fromFeatures(frame.lineFeatures()))
                dotSource?.setGeoJson(FeatureCollection.fromFeatures(frame.headFeatures()))
                ringSource?.setGeoJson(FeatureCollection.fromFeatures(frame.ringFeatures()))
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
    if (filtersOpen) MapFiltersSheet(filters, observerOptions, onChange = { filters = it }, onDismiss = { filtersOpen = false })
    routeDetail?.let { details -> RouteDetailsSheet(details, onNode = { routeDetail = null; currentOnNode(it) }, onDismiss = { routeDetail = null }) }
    if (search) ModalBottomSheet(onDismissRequest = { search = false }) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.8f)) {
            Text("Search nodes", Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.titleLarge)
            NodeBrowser(snapshot?.nodes.orEmpty(), snapshot?.total ?: 0) { search = false; currentOnNode(it) }
        }
    }
    Column(Modifier.fillMaxSize()) {
        // The app shell's Scaffold already applies the status-bar inset; don't add it twice.
        TopAppBar(windowInsets = WindowInsets(0, 0, 0, 0), title = {
            Column {
                Text("Live Map", style = MaterialTheme.typography.titleLarge)
                ConnectionBadge(feed.connection)
            }
        }, actions = {
            IconButton(onClick = onRefresh) { Icon(Icons.Outlined.Refresh, "Refresh map data") }
            IconButton(onClick = { search = true }) { Icon(Icons.Outlined.Search, "Search nodes") }
            Box {
                IconButton(onClick = { layersOpen = true }) { Icon(Icons.Outlined.Layers, "Map layers") }
                DropdownMenu(layersOpen, { layersOpen = false }) {
                    listOf(R.string.standard, R.string.light, R.string.dark).forEachIndexed { index, label ->
                        DropdownMenuItem(text = { Text(stringResource(label)) }, onClick = { mode = index; customStyle = true; layersOpen = false }, trailingIcon = { if (mode == index) Icon(Icons.Outlined.Check, null) })
                    }
                }
            }
        })
    Box(Modifier.weight(1f).fillMaxWidth()) {
        AndroidView(factory = { view }, modifier = Modifier.fillMaxSize())
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
            if (failed) Surface(shape = MaterialTheme.shapes.medium, tonalElevation = 4.dp) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.map_load_failed), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { retry++ }) { Text(stringResource(R.string.retry)) }
                }
            }
            if (sampleRoute.isNotEmpty()) Surface(shape = MaterialTheme.shapes.medium) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                    Text(stringResource(R.string.sample_route), style = MaterialTheme.typography.labelMedium)
                    TextButton(onClick = { replay++ }) { Text(stringResource(R.string.replay_sample)) }
                }
            }
        }
        if (!failed && style == null) CircularProgressIndicator(Modifier.align(Alignment.Center))
        // Location sits above zoom, which sits just above the attribution line at the right edge.
        Column(Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = 56.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Surface(shape = MaterialTheme.shapes.medium, shadowElevation = 2.dp) {
                IconButton(onClick = { if (hasLocationPermission(context)) centerOnUser() else locationPermission.launch(LOCATION_PERMISSIONS) }, enabled = !locating) {
                    if (locating) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Outlined.MyLocation, "Center on my location")
                }
            }
            Surface(shape = MaterialTheme.shapes.medium, shadowElevation = 2.dp) {
                Column {
                    IconButton(onClick = { map?.animateCamera(CameraUpdateFactory.zoomIn()) }) { Icon(Icons.Outlined.Add, stringResource(R.string.zoom_in)) }
                    HorizontalDivider(Modifier.width(32.dp).align(Alignment.CenterHorizontally))
                    IconButton(onClick = { map?.animateCamera(CameraUpdateFactory.zoomOut()) }) { Icon(Icons.Outlined.Remove, stringResource(R.string.zoom_out)) }
                }
            }
        }
        // Required CARTO/OpenStreetMap attribution: one line, bottom right (MapLibre's own logo is bottom left).
        Surface(Modifier.align(Alignment.BottomEnd).padding(end = 8.dp, bottom = 6.dp), shape = MaterialTheme.shapes.small,
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
        val logo = logoFrame
        Surface((if (logo != null && countSize != IntSize.Zero) Modifier.align(Alignment.TopStart).offset {
                IntOffset((logo.centerX() - countSize.width / 2).coerceAtLeast(gap), logo.top - gap - countSize.height)
            } else Modifier.align(Alignment.BottomStart).padding(start = 8.dp, bottom = 34.dp)).onSizeChanged { countSize = it },
            shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f)) {
            Text("${mapNodes.size} nodes", Modifier.padding(horizontal = 14.dp, vertical = 6.dp), style = MaterialTheme.typography.labelMedium)
        }
        // Replay controls centered near the bottom, above the count and attribution; the equal
        // side insets keep them centered on screen and clear of the zoom column.
        if (replayActive && routeReplay != null) ReplayControls(
            playing = replayPlaying, routes = routeReplay.routes, selected = replayIndex, routeOnly = routeOnly,
            onPlay = { replayStart = System.currentTimeMillis() + 150 },
            onLive = { replayActive = false; onExitReplay() },
            onRoute = { replayIndex = it; replayStart = System.currentTimeMillis() + 150 },
            onRouteOnly = { routeOnly = !routeOnly },
            modifier = Modifier.align(Alignment.BottomCenter).padding(start = 72.dp, end = 72.dp, bottom = 76.dp))
    }
}

}

internal fun nodeFeatures(nodes: List<MeshNode>, positions: Map<String, Coordinate> = emptyMap()): FeatureCollection = FeatureCollection.fromFeatures(nodes.mapNotNull { node ->
    (positions[node.publicKey] ?: node.coordinate)?.let { point -> Feature.fromGeometry(Point.fromLngLat(point.longitude, point.latitude)).apply {
        addStringProperty("publicKey", node.publicKey)
        addStringProperty("name", node.displayName)
        addStringProperty("role", node.role)
    } }
})

/**
 * The MapLibre logo has no public view reference: it is the one visible, wide ImageView in the
 * MapView (the attribution button is square). Returns its bounds relative to the MapView.
 */
private fun findLogoFrame(root: android.view.ViewGroup): android.graphics.Rect? {
    val rootLocation = IntArray(2).also(root::getLocationInWindow)
    fun search(group: android.view.ViewGroup): android.widget.ImageView? {
        for (index in 0 until group.childCount) {
            when (val child = group.getChildAt(index)) {
                is android.widget.ImageView -> if (child.isVisible && child.height > 0 && child.width > child.height * 2) return child
                is android.view.ViewGroup -> search(child)?.let { return it }
            }
        }
        return null
    }
    val logo = search(root) ?: return null
    val location = IntArray(2).also(logo::getLocationInWindow)
    val left = location[0] - rootLocation[0]
    val top = location[1] - rootLocation[1]
    return android.graphics.Rect(left, top, left + logo.width, top + logo.height)
}

/** iOS replay controls: Replay / Play again with Live, then route choice and Route only. */
@Composable
private fun ReplayControls(playing: Boolean, routes: List<ReplayRoute>, selected: Int, routeOnly: Boolean,
    onPlay: () -> Unit, onLive: () -> Unit, onRoute: (Int) -> Unit, onRouteOnly: () -> Unit, modifier: Modifier = Modifier) {
    val pill = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f)
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Surface(shape = MaterialTheme.shapes.medium, color = pill, shadowElevation = 2.dp) {
            Row(Modifier.padding(4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Button(onClick = onPlay, contentPadding = PaddingValues(horizontal = 14.dp)) {
                    Icon(if (playing) Icons.Outlined.PlayArrow else Icons.Outlined.Replay, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (playing) "Replay" else "Play again")
                }
                TextButton(onClick = onLive) {
                    Icon(Icons.Outlined.Sensors, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Live")
                }
            }
        }
        Surface(shape = MaterialTheme.shapes.medium, color = pill, shadowElevation = 2.dp) {
            Row(Modifier.padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (routes.size > 1) {
                    var open by remember { mutableStateOf(false) }
                    Box {
                        TextButton(onClick = { open = true }) {
                            Icon(Icons.Outlined.Route, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Route ${selected + 1} of ${routes.size}")
                            Icon(Icons.Outlined.ArrowDropDown, null)
                        }
                        DropdownMenu(open, { open = false }) {
                            routes.forEachIndexed { index, route ->
                                DropdownMenuItem(text = { Text("Route ${index + 1} · ${route.hops} hops") }, onClick = { onRoute(index); open = false },
                                    trailingIcon = { if (index == selected) Icon(Icons.Outlined.Check, null) })
                            }
                        }
                    }
                    VerticalDivider(Modifier.height(20.dp))
                }
                TextButton(onClick = onRouteOnly, modifier = Modifier.semantics { stateDescription = if (routeOnly) "On" else "Off" }) {
                    Icon(if (routeOnly) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Route only")
                }
            }
        }
    }
}

/** iOS parity: names appear only when the view is close (≤ 0.08° tall) and shows at most 60 nodes. */
internal fun showsNodeLabels(latitudeSpan: Double, visibleNodes: Int) = latitudeSpan <= 0.08 && visibleNodes <= 60

private fun updateNodeLabels(map: MapLibreMap, nodes: List<MeshNode>) {
    val layer = map.style?.getLayer(NODE_LABELS) ?: return
    val bounds = map.projection.visibleRegion.latLngBounds
    val visible = if (bounds.latitudeSpan > 0.08) Int.MAX_VALUE
        else nodes.count { node -> node.coordinate?.let { bounds.contains(LatLng(it.latitude, it.longitude)) } == true }
    layer.setProperties(visibility(if (showsNodeLabels(bounds.latitudeSpan, visible)) Property.VISIBLE else Property.NONE))
}

private fun addOverlays(style: Style, dark: Boolean) {
    // Group only 3+ nodes that nearly touch, and show every node individually from
    // regional zoom (zoom 9, e.g. a whole metro area) inward.
    style.addSource(GeoJsonSource(NODES, emptyFeatures, GeoJsonOptions().withCluster(true)
        .withClusterMaxZoom(8).withClusterRadius(24).withClusterMinPoints(3)))
    style.addLayer(CircleLayer(POINTS, NODES).withFilter(not(has("point_count"))).withProperties(
        circleRadius(5f), circleColor(match(get("role"), literal("#299EFF"), stop("repeater", "#FFAA44"), stop("room", "#299EFF"), stop("companion", "#45C99D"), stop("sensor", "#B18AFF"))), circleStrokeWidth(1f), circleStrokeColor("#C5E4FA")))
    style.addLayer(CircleLayer(CLUSTERS, NODES).withFilter(has("point_count")).withProperties(
        circleRadius(12f), circleColor("#21465E"), circleStrokeWidth(1f), circleStrokeColor("#6994AF")))
    style.addLayer(SymbolLayer("nodescope-counts", NODES).withFilter(has("point_count")).withProperties(
        textField(toString(get("point_count_abbreviated"))), textSize(11f), textColor("#FFFFFF"), textAllowOverlap(true)))
    // Font stack served by CARTO's glyph endpoint for these styles; labels that collide are hidden.
    style.addLayer(SymbolLayer(NODE_LABELS, NODES).withFilter(not(has("point_count"))).withProperties(
        textField(get("name")), textFont(arrayOf("Montserrat Medium", "Open Sans Bold", "Noto Sans Regular", "HanWangHeiLight Regular", "NanumBarunGothic Regular")),
        textSize(11f), textAnchor(Property.TEXT_ANCHOR_TOP), textOffset(arrayOf(0f, 0.8f)), textMaxWidth(10f),
        textColor(if (dark) "#FFFFFF" else "#14243A"), textHaloColor(if (dark) "rgba(0,0,0,0.72)" else "rgba(255,255,255,0.85)"),
        textHaloWidth(1.6f), visibility(Property.NONE)))
    // Live hops as on iOS: a soft halo under a thin core, a small white head, and arrival rings.
    style.addSource(GeoJsonSource("live-lines", emptyFeatures).apply { setOverrideSynchronousUpdate(true) })
    style.addLayer(LineLayer("live-route-halo", "live-lines").withProperties(lineColor(get("color")), lineWidth(6f),
        lineOpacity(product(get("opacity"), literal(0.18f))), lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND)))
    style.addLayer(LineLayer("live-route-lines", "live-lines").withProperties(lineColor(get("color")), lineWidth(2f),
        lineOpacity(product(get("opacity"), literal(0.85f))), lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND)))
    style.addSource(GeoJsonSource("live-rings", emptyFeatures).apply { setOverrideSynchronousUpdate(true) })
    style.addLayer(CircleLayer("live-route-rings", "live-rings").withProperties(circleOpacity(0f), circleRadius(get("radius")),
        circleStrokeColor(get("color")), circleStrokeWidth(get("width")), circleStrokeOpacity(get("opacity"))))
    style.addSource(GeoJsonSource("live-dots", emptyFeatures).apply { setOverrideSynchronousUpdate(true) })
    style.addLayer(CircleLayer("live-route-dots", "live-dots").withProperties(circleColor("#FFFFFF"), circleRadius(3.5f), circleStrokeColor(get("color")), circleStrokeWidth(1.5f)))
    style.addSource(GeoJsonSource(ROUTE_NODES, emptyFeatures))
    style.addLayer(CircleLayer(ROUTE_NODE_POINTS, ROUTE_NODES).withProperties(
        circleRadius(6f), circleColor(match(get("role"), literal("#65DDB4"), stop("repeater", "#FFAA44"), stop("room", "#299EFF"), stop("companion", "#45C99D"), stop("sensor", "#B18AFF"))),
        circleStrokeWidth(2f), circleStrokeColor("#FFFFFF")))
    style.addSource(GeoJsonSource(ROUTE, emptyFeatures))
    style.addLayer(LineLayer("nodescope-route-line", ROUTE).withProperties(lineColor("#299EFF"), lineWidth(4f)))
    style.addSource(GeoJsonSource(PACKET, emptyFeatures))
    style.addLayer(CircleLayer("nodescope-packet-dot", PACKET).withProperties(
        circleRadius(9f), circleColor("#00BCD4"), circleStrokeWidth(1f), circleStrokeColor("#C5E4FA")))
    // Your position: a soft halo under a blue dot with a white ring (the platform convention).
    style.addSource(GeoJsonSource(USER_LOCATION, emptyFeatures))
    style.addLayer(CircleLayer("nodescope-user-halo", USER_LOCATION).withProperties(circleRadius(16f), circleColor("#1A73E8"), circleOpacity(0.18f)))
    style.addLayer(CircleLayer("nodescope-user-dot", USER_LOCATION).withProperties(
        circleRadius(7f), circleColor("#1A73E8"), circleStrokeWidth(2.5f), circleStrokeColor("#FFFFFF")))
}

/** Explicit debug-only synthetic fixture, never mixed into analyzer data. */
fun mapLabSnapshot(): AnalyzerSnapshot {
    val points = listOf(Coordinate(30.2672, -97.7431), Coordinate(30.30, -97.72), Coordinate(30.33, -97.68))
    return AnalyzerSnapshot(points.mapIndexed { index, point ->
        MeshNode("sample-$index", "Sample node ${index + 1}", "repeater", point.latitude, point.longitude, "2026-01-01T00:00:00Z")
    }, 3, emptyMap(), MapDefaults(listOf(30.30, -97.72), 11.0))
}


/** Native map updates use the display clock without keeping Compose's UI clock busy. */
private suspend fun awaitMapFrame() = suspendCancellableCoroutine<Unit> { continuation ->
    val choreographer = Choreographer.getInstance()
    val callback = Choreographer.FrameCallback { if (continuation.isActive) continuation.resume(Unit) }
    choreographer.postFrameCallback(callback)
    continuation.invokeOnCancellation { choreographer.removeFrameCallback(callback) }
}

/** Endpoints stay visible while the clustered source continues its normal layout. */
internal fun routeAnchorFeatures(packet: LivePacket, nodes: List<MeshNode>, observers: List<MeshObserver>): List<Feature> {
    val resolved = resolvedRouteNodes(packet, nodes).filterNotNull().distinctBy { it.publicKey }
    val anchors = nodeFeatures(resolved).features().orEmpty().onEach { it.addStringProperty("anchorId", "node:" + it.getStringProperty("publicKey")) }
    val nodeCoordinates = resolved.mapNotNull { it.coordinate }.toSet()
    val endpoints = packetRoute(packet, nodes, observers).flatten().distinct().filterNot { it in nodeCoordinates }.map { point ->
        Feature.fromGeometry(Point.fromLngLat(point.longitude, point.latitude)).apply {
            addStringProperty("anchorId", "observer:${point.latitude},${point.longitude}")
            addStringProperty("role", "observer")
        }
    }
    return anchors + endpoints
}

internal fun regionSpan(region: RegionCoordinate): Pair<Double, Double> {
    val radius = region.radiusKm?.takeIf { it.isFinite() && it > 0 }?.coerceAtMost(2000.0) ?: 45.0
    val latitude = ((radius * 2.4) / 111).coerceAtLeast(0.15)
    return latitude to (latitude / kotlin.math.cos(Math.toRadians(region.lat)).coerceAtLeast(0.2))
}
