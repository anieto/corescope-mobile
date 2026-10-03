package org.nodescope.android.feature.map

import android.graphics.RectF
import android.util.Log
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdate
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression.*
import org.maplibre.android.style.layers.*
import org.maplibre.android.style.layers.PropertyFactory.*
import org.maplibre.android.style.sources.GeoJsonOptions
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.*
import org.nodescope.android.core.model.Coordinate
import kotlin.coroutines.resume

private const val NODES = "nodescope-nodes"
private const val POINTS = "nodescope-points"
private const val CLUSTERS = "nodescope-clusters"
private const val NODE_LABELS = "nodescope-node-labels"
/** A route-only replay's nodes: never grouped, so no count badge is left once the replay ends. */
private const val REPLAY_NODES = "nodescope-replay-nodes"
private const val REPLAY_POINTS = "nodescope-replay-points"
private const val REPLAY_LABELS = "nodescope-replay-labels"
private const val ROUTE_NODES = "nodescope-route-nodes"
private const val ROUTE_NODE_POINTS = "nodescope-route-node-points"
private const val ROUTE = "nodescope-route"
private const val PACKET = "nodescope-packet"
private const val USER_LOCATION = "nodescope-user-location"
private val emptyFeatures get() = FeatureCollection.fromFeatures(emptyList<Feature>())

/** CARTO basemap styles, in the order of the layers menu (Standard, Light, Dark). */
private val cartoStyles = listOf("voyager", "positron", "dark-matter")

/** The MapLibre engine: a MapView drawing CARTO's vector basemaps. */
@Stable
internal class MapLibreEngine(val view: MapView) : MapEngineState {
    var map by mutableStateOf<MapLibreMap?>(null)
        private set
    internal var style by mutableStateOf<Style?>(null)
    override var failed by mutableStateOf(false)
        internal set
    override var failure by mutableStateOf<MapLoadError?>(null)
        internal set
    /** MapLibre's logo, relative to the map, so the node count can sit above it. */
    override var logoFrame by mutableStateOf<android.graphics.Rect?>(null)
        internal set
    /** Equal for the same map, so effects keyed on it restart only when the map changes. */
    override val camera: MapCamera? get() = map?.let { MapLibreCamera(it, view) }
    /** Equal for the same loaded style; a new style (or a retry) gets fresh overlays. */
    override val overlays: MapOverlays? get() = style?.let(::MapLibreOverlays)
    override val loading get() = !failed && style == null
    internal fun attach(ready: MapLibreMap) { map = ready }
}

@Composable
internal fun rememberMapLibreEngine(): MapLibreEngine {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    // A MapView is destroyed with the lifecycle it was attached to, so a new lifecycle (e.g. the
    // navigation entry being replaced) gets a new MapView; a destroyed one is never reused,
    // which left the map blank and crashed MapLibre ("called after the MapView was destroyed").
    return remember(context, lifecycle) { MapLibreEngine(MapView(context).apply { onCreate(null) }) }
}

/**
 * Hosts [engine]'s MapView: its lifecycle, CARTO style ([styleMode] indexes the layers menu),
 * taps and camera reports. [bottomInset] lifts the MapLibre logo above the replay controls.
 */
// Map load failures are logged with android.util.Log: Timber only arrives through MapLibre, with no
// tree planted, so it would log nothing in release builds.
@android.annotation.SuppressLint("LogNotTimber")
@Composable
internal fun MapLibreEngineHost(engine: MapLibreEngine, styleMode: Int, retry: Int, bottomInset: Int, savedCamera: SavedCamera,
    onCameraIdle: (SavedCamera) -> Unit, onTap: (MapTap) -> Boolean, modifier: Modifier = Modifier) {
    val view = engine.view
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val currentSavedCamera by rememberUpdatedState(savedCamera)
    val currentOnCameraIdle by rememberUpdatedState(onCameraIdle)
    val currentOnTap by rememberUpdatedState(onTap)
    // A style that fails to load (often a brief CARTO or network hiccup) is tried again after
    // MAP_RETRY_DELAYS_MS before the error is shown; a manual retry or another style starts over.
    val scope = rememberCoroutineScope()
    var reload by remember(engine) { mutableIntStateOf(0) }
    val autoRetries = remember(engine) { intArrayOf(0) }
    LaunchedEffect(styleMode, retry) { autoRetries[0] = 0 }
    // MapLibre draws its logo as an ImageView inside the MapView; track where it lands.
    DisposableEffect(view) {
        val listener = android.view.View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> engine.logoFrame = findLogoFrame(view) }
        view.addOnLayoutChangeListener(listener)
        onDispose { view.removeOnLayoutChangeListener(listener) }
    }
    // The logo rides up with the replay controls (the node count follows it via logoFrame).
    val map = engine.map
    val logoBase = remember(map) { map?.uiSettings?.let { intArrayOf(it.logoMarginLeft, it.logoMarginTop, it.logoMarginRight, it.logoMarginBottom) } }
    LaunchedEffect(map, bottomInset) {
        val base = logoBase ?: return@LaunchedEffect
        map?.uiSettings?.setLogoMargins(base[0], base[1], base[2], base[3] + bottomInset)
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
        val failure = MapView.OnDidFailLoadingMapListener { message ->
            // Only the style itself: once it has loaded, a missing tile isn't a failed map.
            if (!alive || engine.style != null) return@OnDidFailLoadingMapListener
            Log.w("NodeScope", "Map style failed to load (attempt ${autoRetries[0] + 1}): $message")
            val attempt = autoRetries[0]
            if (attempt < MAP_RETRY_DELAYS_MS.size) {
                autoRetries[0] = attempt + 1
                scope.launch { delay(MAP_RETRY_DELAYS_MS[attempt]); reload++ }
            } else {
                engine.failure = mapLoadError(message)
                engine.failed = true
            }
        }
        view.addOnDidFailLoadingMapListener(failure)
        view.getMapAsync { ready ->
            if (alive) {
                val saved = currentSavedCamera
                ready.cameraPosition = CameraPosition.Builder()
                    .target(LatLng(saved[0], saved[1])).zoom(saved[2])
                    .bearing(saved[3]).tilt(saved[4]).build()
                ready.addOnCameraIdleListener {
                    ready.cameraPosition.let { c -> c.target?.let { currentOnCameraIdle(listOf(it.latitude, it.longitude, c.zoom, c.bearing, c.tilt)) } }
                }
                ready.addOnMapClickListener { coordinate ->
                    val point = ready.projection.toScreenLocation(coordinate)
                    val radius = 24 * context.resources.displayMetrics.density
                    val area = RectF(point.x-radius, point.y-radius, point.x+radius, point.y+radius)
                    val hits = ready.queryRenderedFeatures(area, ROUTE_NODE_POINTS, REPLAY_POINTS, POINTS, CLUSTERS)
                    // Spread co-located nodes sit close together: pick the marker nearest the tap.
                    fun distance(feature: Feature) = (feature.geometry() as? Point)?.let {
                        val screen = ready.projection.toScreenLocation(LatLng(it.latitude(), it.longitude()))
                        kotlin.math.hypot((screen.x - point.x).toDouble(), (screen.y - point.y).toDouble())
                    } ?: Double.MAX_VALUE
                    val node = hits.filter { it.hasProperty("publicKey") }.minByOrNull(::distance)
                    val cluster = hits.filter { it.hasProperty("cluster_id") }.minByOrNull(::distance)
                    currentOnTap(MapTap(
                        nodeKey = node?.getStringProperty("publicKey"),
                        nodeUnderFinger = node != null && distance(node) <= radius / 2,
                        expandGroup = cluster?.let { group -> {
                            val center = group.geometry() as? Point
                            val source = ready.style?.getSourceAs<GeoJsonSource>(NODES)
                            if (center != null && source != null) ready.animateCamera(CameraUpdateFactory.newLatLngZoom(
                                LatLng(center.latitude(), center.longitude()), source.getClusterExpansionZoom(group).toDouble()))
                        } },
                        routeKeys = {
                            ready.queryRenderedFeatures(area, "live-route-halo")
                                .mapNotNull { line -> if (line.hasProperty("route")) line.getStringProperty("route") else null }
                        }))
                }
                engine.attach(ready)
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
    LaunchedEffect(map, styleMode, retry, reload) {
        val ready = map ?: return@LaunchedEffect
        engine.failed = false
        engine.failure = null
        engine.style = null
        val name = cartoStyles[styleMode]
        ready.setStyle("https://basemaps.cartocdn.com/gl/$name-gl-style/style.json") { loaded ->
            if (!view.isDestroyed) { addOverlays(loaded, dark = name == "dark-matter"); autoRetries[0] = 0; engine.style = loaded }
        }
    }
    // Keyed so a replacement MapView is attached (the factory only runs once per key).
    key(view) { AndroidView(factory = { view }, modifier = modifier) }
}

private data class MapLibreCamera(val map: MapLibreMap, val view: MapView) : MapCamera {
    override val zoom get() = map.cameraPosition.zoom
    override val width get() = view.width
    override val height get() = view.height
    override fun visibleBounds() = map.projection.visibleRegion.latLngBounds.let { CameraTarget.Bounds(it.latitudeNorth, it.longitudeEast, it.latitudeSouth, it.longitudeWest) }
    override fun toScreen(point: Coordinate) = map.projection.toScreenLocation(point.latLng).let { it.x to it.y }
    override fun fittedZoom(bounds: CameraTarget.Bounds, insets: FramingInsets) =
        map.getCameraForLatLngBounds(bounds.latLngBounds, intArrayOf(insets.left, insets.top, insets.right, insets.bottom))?.zoom
    override fun jump(move: CameraMove) = map.moveCamera(move.update)
    override fun animate(move: CameraMove, durationMs: Int?) =
        if (durationMs == null) map.animateCamera(move.update) else map.animateCamera(move.update, durationMs)
    override suspend fun animateAndWait(move: CameraMove, durationMs: Int) = map.animateCameraAndWait(move.update, durationMs)
    override suspend fun awaitIdle() = view.awaitIdle()
}

private val Coordinate.latLng get() = LatLng(latitude, longitude)
private val CameraTarget.Bounds.latLngBounds get() = LatLngBounds.from(north, east, south, west)
private val CameraMove.update: CameraUpdate get() = when (this) {
    is CameraMove.Center -> CameraUpdateFactory.newLatLngZoom(coordinate.latLng, zoom)
    is CameraMove.Fit -> CameraUpdateFactory.newLatLngBounds(bounds.latLngBounds, insets.left, insets.top, insets.right, insets.bottom)
    CameraMove.ZoomIn -> CameraUpdateFactory.zoomIn()
    CameraMove.ZoomOut -> CameraUpdateFactory.zoomOut()
}

private data class MapLibreOverlays(val style: Style) : MapOverlays {
    // Looked up once per style: route frames are pushed on every display frame.
    private val lineSource by lazy { style.getSourceAs<GeoJsonSource>("live-lines") }
    private val dotSource by lazy { style.getSourceAs<GeoJsonSource>("live-dots") }
    private val ringSource by lazy { style.getSourceAs<GeoJsonSource>("live-rings") }
    private fun source(id: String) = style.getSourceAs<GeoJsonSource>(id)

    override fun setNodes(nodes: List<MapMarker>, grouped: Boolean) {
        // The grouped source would bundle a route's nearby hops into a numbered badge when zoomed
        // out, which stayed behind after the replay's own markers faded; route nodes go to an
        // ungrouped source instead.
        val features = markerFeatures(nodes)
        source(NODES)?.setGeoJson(if (grouped) features else emptyFeatures)
        source(REPLAY_NODES)?.setGeoJson(if (grouped) emptyFeatures else features)
    }
    override fun setNodeLabelsVisible(visible: Boolean) {
        val shown = visibility(if (visible) Property.VISIBLE else Property.NONE)
        listOf(NODE_LABELS, REPLAY_LABELS).forEach { style.getLayer(it)?.setProperties(shown) }
    }
    override fun setRouteAnchors(markers: List<MapMarker>) {
        source(ROUTE_NODES)?.setGeoJson(FeatureCollection.fromFeatures(markers.map { marker ->
            marker.feature().apply { addStringProperty("anchorId", marker.anchorId) }
        }))
    }
    override fun setRouteFrame(frame: RouteFrame) {
        lineSource?.setGeoJson(FeatureCollection.fromFeatures(frame.lines.map { line ->
            Feature.fromGeometry(LineString.fromLngLats(line.points.map { it.point })).apply {
                addStringProperty("color", line.color)
                addNumberProperty("opacity", line.opacity)
                addStringProperty("route", line.route)
            }
        }))
        dotSource?.setGeoJson(FeatureCollection.fromFeatures(frame.heads.map { (point, color) ->
            Feature.fromGeometry(point.point).apply { addStringProperty("color", color) }
        }))
        ringSource?.setGeoJson(FeatureCollection.fromFeatures(frame.rings.map { ring ->
            Feature.fromGeometry(ring.center.point).apply {
                addStringProperty("color", ring.color)
                addNumberProperty("radius", ring.radius)
                addNumberProperty("width", ring.width)
                addNumberProperty("opacity", ring.opacity)
            }
        }))
    }
    override fun setUserLocation(point: Coordinate?) {
        source(USER_LOCATION)?.setGeoJson(point?.let { FeatureCollection.fromFeatures(listOf(Feature.fromGeometry(it.point))) } ?: emptyFeatures)
    }
    override fun setSampleRoute(route: List<Coordinate>) {
        source(ROUTE)?.setGeoJson(if (route.size > 1)
            FeatureCollection.fromFeatures(listOf(Feature.fromGeometry(LineString.fromLngLats(route.map { it.point }))))
            else emptyFeatures)
    }
    override fun setSamplePacket(point: Coordinate?) {
        source(PACKET)?.setGeoJson(if (point == null) emptyFeatures else FeatureCollection.fromFeatures(listOf(Feature.fromGeometry(point.point))))
    }
}

private val Coordinate.point get() = Point.fromLngLat(longitude, latitude)

private fun MapMarker.feature() = Feature.fromGeometry(coordinate.point).apply {
    publicKey?.let { addStringProperty("publicKey", it) }
    name?.let { addStringProperty("name", it) }
    addStringProperty("role", role)
}

private fun markerFeatures(markers: List<MapMarker>): FeatureCollection = FeatureCollection.fromFeatures(markers.map { it.feature() })

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
    fun labels(id: String, source: String) = SymbolLayer(id, source).withFilter(not(has("point_count"))).withProperties(
        textField(get("name")), textFont(arrayOf("Montserrat Medium", "Open Sans Bold", "Noto Sans Regular", "HanWangHeiLight Regular", "NanumBarunGothic Regular")),
        textSize(11f), textAnchor(Property.TEXT_ANCHOR_TOP), textOffset(arrayOf(0f, 0.8f)), textMaxWidth(10f),
        textColor(if (dark) "#FFFFFF" else "#14243A"), textHaloColor(if (dark) "rgba(0,0,0,0.72)" else "rgba(255,255,255,0.85)"),
        textHaloWidth(1.6f), visibility(Property.NONE))
    style.addLayer(labels(NODE_LABELS, NODES))
    style.addSource(GeoJsonSource(REPLAY_NODES, emptyFeatures))
    style.addLayer(CircleLayer(REPLAY_POINTS, REPLAY_NODES).withProperties(
        circleRadius(5f), circleColor(match(get("role"), literal("#299EFF"), stop("repeater", "#FFAA44"), stop("room", "#299EFF"), stop("companion", "#45C99D"), stop("sensor", "#B18AFF"))), circleStrokeWidth(1f), circleStrokeColor("#C5E4FA")))
    style.addLayer(labels(REPLAY_LABELS, REPLAY_NODES))
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

private suspend fun MapLibreMap.animateCameraAndWait(update: CameraUpdate, durationMs: Int) =
    suspendCancellableCoroutine<Unit> { continuation ->
        animateCamera(update, durationMs, object : MapLibreMap.CancelableCallback {
            override fun onCancel() { if (continuation.isActive) continuation.resume(Unit) }
            override fun onFinish() { if (continuation.isActive) continuation.resume(Unit) }
        })
    }

/** Suspends until the map has finished loading and drawing what is on screen. */
private suspend fun MapView.awaitIdle() = suspendCancellableCoroutine<Unit> { continuation ->
    val listener = object : MapView.OnDidBecomeIdleListener {
        override fun onDidBecomeIdle() {
            removeOnDidBecomeIdleListener(this)
            if (continuation.isActive) continuation.resume(Unit)
        }
    }
    addOnDidBecomeIdleListener(listener)
    continuation.invokeOnCancellation { removeOnDidBecomeIdleListener(listener) }
}
