package org.nodescope.android.feature.map

import android.content.Context
import androidx.core.graphics.createBitmap
import androidx.core.graphics.toColorInt
import androidx.core.graphics.withTranslation
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.Path
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.View
import android.widget.FrameLayout
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.maps.CameraUpdate
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.MapView
import com.google.android.gms.maps.model.*
import com.google.maps.android.clustering.Cluster
import com.google.maps.android.clustering.ClusterItem
import com.google.maps.android.clustering.ClusterManager
import com.google.maps.android.clustering.algo.NonHierarchicalDistanceBasedAlgorithm
import com.google.maps.android.clustering.algo.StaticCluster
import com.google.maps.android.clustering.view.DefaultClusterRenderer
import com.google.maps.android.collections.MarkerManager
import kotlinx.coroutines.suspendCancellableCoroutine
import org.nodescope.android.core.model.Coordinate
import kotlin.coroutines.resume
import kotlin.math.hypot

/*
 * Google Maps (beta), the native Maps SDK: ordinary markers and no map ID, so map loads stay in
 * the free Maps SDK SKU. A map ID, cloud styling or advanced markers would bill as Dynamic Maps.
 */

/** Google's zoom is one level above MapLibre's for the same view (256- vs 512-pixel tiles). */
private const val GOOGLE_ZOOM_OFFSET = 1.0
/** Nodes group only up to MapLibre zoom 8 (clusterMaxZoom 8), i.e. below Google zoom 10. */
private const val GOOGLE_UNGROUPED_ZOOM = 10f

enum class GoogleMapType(val mapType: Int) {
    MAP(GoogleMap.MAP_TYPE_NORMAL), SATELLITE(GoogleMap.MAP_TYPE_SATELLITE), TERRAIN(GoogleMap.MAP_TYPE_TERRAIN), HYBRID(GoogleMap.MAP_TYPE_HYBRID)
}

@Stable
internal class GoogleMapEngine(val view: MapView, val routeLayer: RouteLayer) : MapEngineState {
    /** The map with the route layer over it, attached as one view. */
    val container = FrameLayout(view.context).apply { addView(view); addView(routeLayer) }
    private var ready by mutableStateOf<Pair<GoogleCamera, GoogleOverlays>?>(null)
    override val camera: MapCamera? get() = ready?.first
    override val overlays: MapOverlays? get() = ready?.second
    override var failed by mutableStateOf(false)
        internal set
    override val loading get() = !failed && ready == null
    /** Google draws its logo itself (kept above the replay controls with map padding). */
    override val logoFrame: android.graphics.Rect? get() = null
    internal val map get() = ready?.first?.map
    internal fun attach(camera: GoogleCamera, overlays: GoogleOverlays) { ready = camera to overlays }
}

@Composable
internal fun rememberGoogleMapEngine(): GoogleMapEngine {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    // As with MapLibre, a MapView is destroyed with its lifecycle and never reused after that.
    return remember(context, lifecycle) { GoogleMapEngine(MapView(context).apply { onCreate(null) }, RouteLayer(context)) }
}

/**
 * Hosts [engine]'s MapView: lifecycle, map type, taps and camera reports. [bottomInset] becomes
 * map padding so Google's logo rides above the replay controls, without moving what is shown.
 */
@Composable
internal fun GoogleMapEngineHost(engine: GoogleMapEngine, mapType: GoogleMapType, dark: Boolean, bottomInset: Int, savedCamera: SavedCamera,
    onCameraIdle: (SavedCamera) -> Unit, onTap: (MapTap) -> Boolean, modifier: Modifier = Modifier) {
    val view = engine.view
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val currentSavedCamera by rememberUpdatedState(savedCamera)
    val currentOnCameraIdle by rememberUpdatedState(onCameraIdle)
    val currentOnTap by rememberUpdatedState(onTap)
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
        // Without Google Play services the map never loads; say so instead of spinning.
        if (GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) != ConnectionResult.SUCCESS) engine.failed = true
        view.getMapAsync { map ->
            if (!alive) return@getMapAsync
            map.uiSettings.apply {
                isZoomControlsEnabled = false; isMapToolbarEnabled = false; isMyLocationButtonEnabled = false; isIndoorLevelPickerEnabled = false
            }
            val camera = GoogleCamera(map, view)
            val saved = currentSavedCamera
            map.moveCamera(CameraUpdateFactory.newCameraPosition(CameraPosition(LatLng(saved[0], saved[1]),
                (saved[2] + GOOGLE_ZOOM_OFFSET).toFloat(), saved[4].toFloat(), saved[3].toFloat())))
            val overlays = GoogleOverlays(context, map, camera, engine.routeLayer) { currentOnTap(it) }
            engine.routeLayer.map = map
            // The route layer follows every camera change, including gestures between route frames.
            map.setOnCameraMoveListener { engine.routeLayer.invalidate() }
            map.setOnCameraIdleListener {
                engine.routeLayer.invalidate()
                overlays.onCameraIdle()
                camera.saved()?.let { currentOnCameraIdle(it) }
            }
            engine.attach(camera, overlays)
        }
        onDispose {
            alive = false
            lifecycle.removeObserver(observer)
            if (resumed) view.onPause()
            if (started) view.onStop()
            view.onDestroy()
        }
    }
    val map = engine.map
    // Satellite and hybrid imagery is dark whatever the theme, so their labels are light too.
    LaunchedEffect(mapType, dark) { engine.routeLayer.lightLabels = dark || mapType == GoogleMapType.SATELLITE || mapType == GoogleMapType.HYBRID }
    LaunchedEffect(map, mapType, dark) {
        map ?: return@LaunchedEffect
        map.mapType = mapType.mapType
        map.setMapColorScheme(if (dark) MapColorScheme.DARK else MapColorScheme.LIGHT)
    }
    LaunchedEffect(engine.camera, bottomInset) { (engine.camera as? GoogleCamera)?.setBottomInset(bottomInset) }
    key(view) { AndroidView(factory = { engine.container }, modifier = modifier) }
}

internal class GoogleCamera(val map: GoogleMap, private val view: MapView) : MapCamera {
    private val density = view.resources.displayMetrics.density
    /** Map padding at the bottom (the replay controls); the camera target is the padded area's centre. */
    private var padding = 0
    private var pendingPadding: Int? = null
    private var animating = 0

    override val zoom get() = map.cameraPosition.zoom - GOOGLE_ZOOM_OFFSET
    override val width get() = view.width
    override val height get() = view.height
    override fun visibleBounds() = map.projection.visibleRegion.latLngBounds.let {
        CameraTarget.Bounds(it.northeast.latitude, it.northeast.longitude, it.southwest.latitude, it.southwest.longitude)
    }
    override fun toScreen(point: Coordinate) = map.projection.toScreenLocation(LatLng(point.latitude, point.longitude)).let { it.x.toFloat() to it.y.toFloat() }
    override fun fittedZoom(bounds: CameraTarget.Bounds, insets: FramingInsets) = fitCamera(bounds, width, height, insets, density).zoom
    override fun jump(move: CameraMove) = map.moveCamera(update(move))
    override fun animate(move: CameraMove, durationMs: Int?) {
        animating++
        val done = callback { finished() }
        if (durationMs == null) map.animateCamera(update(move), done) else map.animateCamera(update(move), durationMs, done)
    }
    override suspend fun animateAndWait(move: CameraMove, durationMs: Int) = suspendCancellableCoroutine { continuation ->
        animating++
        map.animateCamera(update(move), durationMs, callback { finished(); if (continuation.isActive) continuation.resume(Unit) })
    }
    override suspend fun awaitIdle() = suspendCancellableCoroutine { continuation ->
        // Called once the map has finished drawing (immediately when it already has).
        map.setOnMapLoadedCallback { if (continuation.isActive) continuation.resume(Unit) }
        continuation.invokeOnCancellation { map.setOnMapLoadedCallback(null) }
    }

    /** The camera as the screen saves it: the whole map's centre, MapLibre zoom. */
    fun saved(): SavedCamera? {
        val position = map.cameraPosition
        val zoom = position.zoom - GOOGLE_ZOOM_OFFSET
        val center = Mercator.offset(Coordinate(position.target.latitude, position.target.longitude), zoom, 0.0, padding / 2.0, density)
        return listOf(center.latitude, center.longitude, zoom, position.bearing.toDouble(), position.tilt.toDouble())
    }

    /**
     * Pads the bottom of the map so Google's logo stays above the replay controls. Padding moves
     * the camera target up by half, so the target is moved to keep the view still; that would
     * interrupt a camera animation, so it waits for one to finish.
     */
    fun setBottomInset(inset: Int) {
        if (inset == padding) { pendingPadding = null; return }
        if (animating > 0) { pendingPadding = inset; return }
        val keep = map.projection.fromScreenLocation(android.graphics.Point(view.width / 2, (view.height - inset) / 2))
        padding = inset
        map.setPadding(0, 0, 0, inset)
        if (view.width > 0 && view.height > 0) map.moveCamera(CameraUpdateFactory.newLatLng(keep))
    }

    private fun finished() {
        animating = (animating - 1).coerceAtLeast(0)
        if (animating == 0) pendingPadding?.let { pendingPadding = null; setBottomInset(it) }
    }

    private fun update(move: CameraMove): CameraUpdate = when (move) {
        is CameraMove.Center -> centered(move)
        is CameraMove.Fit -> centered(fitCamera(move.bounds, width, height, move.insets, density))
        CameraMove.ZoomIn -> CameraUpdateFactory.zoomIn()
        CameraMove.ZoomOut -> CameraUpdateFactory.zoomOut()
    }

    /** Puts [move]'s coordinate at the centre of the whole map, as MapLibre does, despite the padding. */
    private fun centered(move: CameraMove.Center): CameraUpdate {
        val target = Mercator.offset(move.coordinate, move.zoom, 0.0, -padding / 2.0, density)
        return CameraUpdateFactory.newLatLngZoom(LatLng(target.latitude, target.longitude), (move.zoom + GOOGLE_ZOOM_OFFSET).toFloat())
    }

    private fun callback(done: () -> Unit) = object : GoogleMap.CancelableCallback {
        override fun onFinish() = done()
        override fun onCancel() = done()
    }
}

private data class NodeItem(val marker: MapMarker) : ClusterItem {
    private val latLng = LatLng(marker.coordinate.latitude, marker.coordinate.longitude)
    override fun getPosition() = latLng
    override fun getTitle(): String? = null
    override fun getSnippet(): String? = null
    override fun getZIndex(): Float? = null
}

/** Groups nearby nodes only while zoomed out, like the CARTO map; closer in every node is its own marker. */
private class NodeGrouping : NonHierarchicalDistanceBasedAlgorithm<NodeItem>() {
    override fun getClusters(zoom: Float): MutableSet<out Cluster<NodeItem>> =
        if (zoom >= GOOGLE_UNGROUPED_ZOOM) items.mapTo(mutableSetOf()) { item -> StaticCluster<NodeItem>(item.position).apply { add(item) } }
        else super.getClusters(zoom)
}

private val roleColors = mapOf("repeater" to 0xFFFFAA44.toInt(), "room" to 0xFF299EFF.toInt(), "companion" to 0xFF45C99D.toInt(), "sensor" to 0xFFB18AFF.toInt())

internal class GoogleOverlays(private val context: Context, private val map: GoogleMap, private val camera: GoogleCamera,
    private val routeLayer: RouteLayer, private val onTap: (MapTap) -> Boolean) : MapOverlays {
    private val screenDensity = context.resources.displayMetrics.density
    private val icons = mutableMapOf<String, BitmapDescriptor>()
    private val markers = MarkerManager(map)
    private val nodes = ClusterManager<NodeItem>(context, map, markers)
    private val renderer = NodeRenderer()
    private val replayNodes = markers.newCollection()
    private val decorations = markers.newCollection()
    private var items: List<NodeItem> = emptyList()
    private var userMarker: Marker? = null
    private var sampleRoute: Polyline? = null
    private var samplePacket: Marker? = null

    init {
        nodes.algorithm = NodeGrouping().apply { maxDistanceBetweenClusteredItems = 24 }
        nodes.renderer = renderer
        nodes.setOnClusterItemClickListener { item -> tapNode(item.marker.publicKey); true }
        nodes.setOnClusterClickListener { group ->
            onTap(MapTap(null, false, { expand(group.items.map { it.marker.coordinate }) }, { emptyList() }))
            true
        }
        replayNodes.setOnMarkerClickListener { marker -> tapNode(marker.tag as? String); true }
        decorations.setOnMarkerClickListener { true }
        map.setOnMapClickListener { point -> tapNear(point) }
    }

    fun onCameraIdle() = nodes.onCameraIdle()

    private fun tapNode(key: String?) { if (key != null) onTap(MapTap(key, true, null, { emptyList() })) }

    /**
     * Small markers are hard to hit exactly: a tap near one (24 dp) picks the nearest, as on CARTO,
     * including a route's node markers; otherwise a route under the finger opens its details.
     */
    private fun tapNear(point: LatLng) {
        val tap = map.projection.toScreenLocation(point)
        val shown = items.mapNotNull { item -> renderer.getMarker(item)?.let { item.marker.publicKey to it.position } } +
            replayNodes.markers.map { (it.tag as? String) to it.position } +
            routeLayer.anchors.map { it.publicKey to LatLng(it.coordinate.latitude, it.coordinate.longitude) }
        val nearest = shown.mapNotNull { (key, position) ->
            key ?: return@mapNotNull null
            val screen = map.projection.toScreenLocation(position)
            key to hypot((screen.x - tap.x).toDouble(), (screen.y - tap.y).toDouble())
        }.minByOrNull { it.second }?.takeIf { it.second <= 24 * screenDensity }
        onTap(MapTap(nearest?.first, nearest != null && nearest.second <= 12 * screenDensity, null,
            { routeLayer.routesNear(tap.x.toFloat(), tap.y.toFloat(), 24 * screenDensity) }))
    }

    /** Zooms into a group far enough for it to come apart (groups end at MapLibre zoom 9). */
    private fun expand(points: List<Coordinate>) {
        val margin = (48 * screenDensity).toInt()
        val fit = fitCamera(boundsOf(points), camera.width, camera.height, FramingInsets(margin, margin, margin, margin), screenDensity)
        camera.animate(CameraMove.Center(fit.coordinate, maxOf(minOf(fit.zoom, 12.0), camera.zoom + 1, 9.0)))
    }

    override fun setNodes(nodes: List<MapMarker>, grouped: Boolean) {
        items = if (grouped) nodes.map(::NodeItem) else emptyList()
        this.nodes.clearItems()
        this.nodes.addItems(items)
        this.nodes.cluster()
        // A route-only replay's nodes are never grouped.
        routeLayer.labelNodes = nodes
        replayNodes.clear()
        if (!grouped) nodes.forEach { node ->
            replayNodes.addMarker(markerOptions(node.coordinate, nodeIcon(node.role))).apply { tag = node.publicKey }
        }
    }

    /** Google markers have no text of their own, so names are drawn on the route layer. */
    override fun setNodeLabelsVisible(visible: Boolean) { routeLayer.labelsVisible = visible }

    override fun setRouteAnchors(markers: List<MapMarker>) { routeLayer.anchors = markers }

    override fun setRouteFrame(frame: RouteFrame) { routeLayer.frame = frame }

    override fun setUserLocation(point: Coordinate?) {
        userMarker?.let { decorations.remove(it) }
        userMarker = point?.let { decorations.addMarker(markerOptions(it, userIcon()).zIndex(3f)) }
    }

    override fun setSampleRoute(route: List<Coordinate>) {
        sampleRoute?.remove()
        sampleRoute = if (route.size > 1) map.addPolyline(PolylineOptions().addAll(route.map { LatLng(it.latitude, it.longitude) })
            .width(4 * screenDensity).color(0xFF299EFF.toInt())) else null
    }

    override fun setSamplePacket(point: Coordinate?) {
        if (point == null) { samplePacket?.let { decorations.remove(it) }; samplePacket = null; return }
        val position = LatLng(point.latitude, point.longitude)
        samplePacket?.let { it.position = position } ?: run {
            samplePacket = decorations.addMarker(markerOptions(point, dot("packet", 9f, 0xFF00BCD4.toInt(), 1f, 0xFFC5E4FA.toInt())))
        }
    }

    private fun markerOptions(point: Coordinate, icon: BitmapDescriptor) = MarkerOptions()
        .position(LatLng(point.latitude, point.longitude)).icon(icon).anchor(0.5f, 0.5f)

    private fun nodeIcon(role: String) = dot("node:$role", 5f, roleColors[role.lowercase()] ?: 0xFF299EFF.toInt(), 1f, 0xFFC5E4FA.toInt())

    /** A filled circle with a ring, radius and ring width in dp, as the CARTO layers draw them. */
    private fun dot(key: String, radiusDp: Float, fill: Int, strokeDp: Float, stroke: Int) = icons.getOrPut(key) {
        val radius = radiusDp * screenDensity; val ring = strokeDp * screenDensity
        val size = kotlin.math.ceil((radius + ring) * 2).toInt()
        val bitmap = createBitmap(size, size)
        Canvas(bitmap).apply {
            drawCircle(size / 2f, size / 2f, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fill })
            if (ring > 0f) drawCircle(size / 2f, size / 2f, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; strokeWidth = ring; color = stroke
            })
        }
        BitmapDescriptorFactory.fromBitmap(bitmap)
    }

    /** Your position: a soft halo under a blue dot with a white ring. */
    private fun userIcon() = icons.getOrPut("user") {
        val size = (32 * screenDensity).toInt()
        val bitmap = createBitmap(size, size)
        val c = size / 2f
        Canvas(bitmap).apply {
            drawCircle(c, c, 16 * screenDensity, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = withAlpha(0xFF1A73E8.toInt(), 0.18f) })
            drawCircle(c, c, 7 * screenDensity, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1A73E8.toInt() })
            drawCircle(c, c, 7 * screenDensity, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.5f * screenDensity; color = 0xFFFFFFFF.toInt() })
        }
        BitmapDescriptorFactory.fromBitmap(bitmap)
    }

    /** A group: a dark circle with its node count, as on the CARTO map. */
    private fun groupIcon(count: Int): BitmapDescriptor {
        val text = if (count < 1000) "$count" else "%.1fk".format(count / 1000.0).replace(".0k", "k")
        return icons.getOrPut("group:$text") {
            val radius = 12 * screenDensity
            val size = kotlin.math.ceil((radius + screenDensity) * 2).toInt()
            val bitmap = createBitmap(size, size)
            val c = size / 2f
            Canvas(bitmap).apply {
                drawCircle(c, c, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF21465E.toInt() })
                drawCircle(c, c, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = screenDensity; color = 0xFF6994AF.toInt() })
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = 0xFFFFFFFF.toInt(); textSize = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, 11f, context.resources.displayMetrics)
                    textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD
                }
                drawText(text, c, c - (paint.descent() + paint.ascent()) / 2, paint)
            }
            BitmapDescriptorFactory.fromBitmap(bitmap)
        }
    }

    private inner class NodeRenderer : DefaultClusterRenderer<NodeItem>(context, map, nodes) {
        init { setAnimation(false); minClusterSize = 3 }
        override fun shouldRenderAsCluster(cluster: Cluster<NodeItem>) = cluster.size >= 3
        override fun onBeforeClusterItemRendered(item: NodeItem, markerOptions: MarkerOptions) {
            markerOptions.icon(nodeIcon(item.marker.role)).anchor(0.5f, 0.5f)
        }
        override fun onClusterItemUpdated(item: NodeItem, marker: Marker) {
            marker.setIcon(nodeIcon(item.marker.role)); marker.setAnchor(0.5f, 0.5f)
        }
        override fun onBeforeClusterRendered(cluster: Cluster<NodeItem>, markerOptions: MarkerOptions) {
            markerOptions.icon(groupIcon(cluster.size)).anchor(0.5f, 0.5f)
        }
        override fun onClusterUpdated(cluster: Cluster<NodeItem>, marker: Marker) {
            marker.setIcon(groupIcon(cluster.size)); marker.setAnchor(0.5f, 0.5f)
        }
    }
}

/**
 * Live and replayed routes, drawn over the Google map instead of as Google map objects. Google
 * re-processes a polyline on every change, which cost ~20 ms of main thread per frame when
 * profiled; drawing the same lines here takes well under a millisecond. Points are placed with
 * the map's projection on each draw, so the layer follows pans and zooms. Matches the CARTO
 * layers: halo and core lines, arrival rings, travelling heads, then route node markers on top.
 * It never takes touches; taps reach the map, which asks [routesNear].
 */
internal class RouteLayer(context: Context) : View(context) {
    var map: GoogleMap? = null
    var frame = RouteFrame(emptyList(), emptyList(), emptyList())
        set(value) { if (value.isEmpty && field.isEmpty) return; field = value; invalidate() }
    var anchors: List<MapMarker> = emptyList()
        set(value) { field = value; invalidate() }
    /** Node names, shown under their dots while [labelsVisible] (close in, few nodes), as on CARTO. */
    var labelNodes: List<MapMarker> = emptyList()
        set(value) { field = value; if (labelsVisible) invalidate() }
    var labelsVisible = false
        set(value) { if (field != value) { field = value; invalidate() } }
    /** White names with a dark halo (dark, satellite and hybrid maps), else dark with a light halo. */
    var lightLabels = false
        set(value) { if (field != value) { field = value; invalidate() } }
    private val density = resources.displayMetrics.density
    private val path = Path()
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    // CARTO's label layers: 11 dp medium text, top-anchored 0.8 em under the point, wrapped at
    // 10 em, with a 1.6 dp halo; colliding labels are left out.
    private val labelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 11 * density; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        strokeJoin = Paint.Join.ROUND; strokeWidth = 3.2f * density
    }
    private val labelLayouts = HashMap<String, StaticLayout>()
    private val placedLabels = ArrayList<RectF>()

    init { isClickable = false; isFocusable = false; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    private val RouteFrame.isEmpty get() = lines.isEmpty() && heads.isEmpty() && rings.isEmpty()

    // Google's projection takes a LatLng and returns a new Point; there is no allocation-free form.
    @android.annotation.SuppressLint("DrawAllocation")
    override fun onDraw(canvas: Canvas) {
        val map = map ?: return
        val labels = labelsVisible && labelNodes.isNotEmpty()
        if (frame.isEmpty && anchors.isEmpty() && !labels) return
        val projection = map.projection
        fun screen(point: Coordinate) = projection.toScreenLocation(LatLng(point.latitude, point.longitude))
        // Names first, under the routes, as the CARTO layers stack.
        if (labels) drawLabels(canvas, labelNodes, ::screen)
        for (line in frame.lines) {
            path.reset()
            line.points.forEachIndexed { index, point ->
                val p = screen(point)
                if (index == 0) path.moveTo(p.x.toFloat(), p.y.toFloat()) else path.lineTo(p.x.toFloat(), p.y.toFloat())
            }
            val color = parseColor(line.color)
            stroke.strokeWidth = 6 * density; stroke.color = withAlpha(color, line.opacity * 0.18f); canvas.drawPath(path, stroke)
            stroke.strokeWidth = 2 * density; stroke.color = withAlpha(color, line.opacity * 0.85f); canvas.drawPath(path, stroke)
        }
        for (ring in frame.rings) {
            val p = screen(ring.center)
            stroke.strokeWidth = ring.width * density; stroke.color = withAlpha(parseColor(ring.color), ring.opacity)
            canvas.drawCircle(p.x.toFloat(), p.y.toFloat(), ring.radius * density, stroke)
        }
        for ((point, color) in frame.heads) dot(canvas, screen(point), 3.5f, 0xFFFFFFFF.toInt(), 1.5f, parseColor(color))
        for (anchor in anchors) dot(canvas, screen(anchor.coordinate), 6f, roleColors[anchor.role.lowercase()] ?: 0xFF65DDB4.toInt(), 2f, 0xFFFFFFFF.toInt())
        // A replayed route's observers are always labelled, as on CARTO.
        val receivers = anchors.filter { it.role == "observer" && it.name != null }
        if (receivers.isNotEmpty()) drawLabels(canvas, receivers, ::screen, fresh = !labels)
    }

    /** [fresh] starts collision checks over; otherwise these labels also avoid ones already drawn. */
    private fun drawLabels(canvas: Canvas, markers: List<MapMarker>, screen: (Coordinate) -> android.graphics.Point, fresh: Boolean = true) {
        if (fresh) placedLabels.clear()
        val textColor = if (lightLabels) 0xFFFFFFFF.toInt() else 0xFF14243A.toInt()
        val haloColor = if (lightLabels) 0xB8000000.toInt() else 0xD9FFFFFF.toInt()
        for (node in markers) {
            val name = node.name?.takeIf(String::isNotBlank) ?: continue
            val at = screen(node.coordinate)
            if (at.x < 0 || at.y < 0 || at.x > width || at.y > height) continue
            val layout = labelLayouts.getOrPut(name) {
                StaticLayout.Builder.obtain(name, 0, name.length, labelPaint, (10 * labelPaint.textSize).toInt())
                    .setAlignment(Layout.Alignment.ALIGN_CENTER).setIncludePad(false).build()
            }
            val textWidth = (0 until layout.lineCount).maxOf { layout.getLineWidth(it) }
            val left = at.x - layout.width / 2f
            val top = at.y + 0.8f * labelPaint.textSize
            val bounds = RectF(at.x - textWidth / 2, top, at.x + textWidth / 2, top + layout.height)
            if (placedLabels.any { RectF.intersects(it, bounds) }) continue
            placedLabels += bounds
            canvas.withTranslation(left, top) {
                labelPaint.style = Paint.Style.STROKE; labelPaint.color = haloColor; layout.draw(this)
                labelPaint.style = Paint.Style.FILL; labelPaint.color = textColor; layout.draw(this)
            }
        }
        // Layouts for names no longer on the map aren't kept.
        if (labelLayouts.size > 400) labelLayouts.clear()
    }

    private fun dot(canvas: Canvas, at: android.graphics.Point, radiusDp: Float, color: Int, ringDp: Float, ringColor: Int) {
        fill.color = color
        canvas.drawCircle(at.x.toFloat(), at.y.toFloat(), radiusDp * density, fill)
        stroke.strokeWidth = ringDp * density; stroke.color = ringColor
        canvas.drawCircle(at.x.toFloat(), at.y.toFloat(), radiusDp * density, stroke)
    }

    /** Keys of the routes drawn within [radius] pixels of a screen point, nearest first. */
    fun routesNear(x: Float, y: Float, radius: Float): List<String> {
        val projection = map?.projection ?: return emptyList()
        return frame.lines.mapNotNull { line ->
            val points = line.points.map { projection.toScreenLocation(LatLng(it.latitude, it.longitude)) }
            val distance = points.zipWithNext { a, b -> segmentDistance(x, y, a, b) }.minOrNull() ?: return@mapNotNull null
            if (distance <= radius) line.route to distance else null
        }.sortedBy { it.second }.map { it.first }.distinct()
    }
}

/** Distance in pixels from a point to the segment [a]–[b]. */
private fun segmentDistance(x: Float, y: Float, a: android.graphics.Point, b: android.graphics.Point): Float {
    val dx = (b.x - a.x).toFloat(); val dy = (b.y - a.y).toFloat()
    val length = dx * dx + dy * dy
    val t = if (length == 0f) 0f else (((x - a.x) * dx + (y - a.y) * dy) / length).coerceIn(0f, 1f)
    return hypot(x - (a.x + t * dx), y - (a.y + t * dy))
}

private val parsedColors = mutableMapOf<String, Int>()
private fun parseColor(hex: String) = parsedColors.getOrPut(hex) { hex.toColorInt() }
private fun withAlpha(color: Int, opacity: Float) = (color and 0x00FFFFFF) or ((opacity.coerceIn(0f, 1f) * 255).toInt() shl 24)
