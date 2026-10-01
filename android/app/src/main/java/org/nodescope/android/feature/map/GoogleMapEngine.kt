package org.nodescope.android.feature.map

import android.content.Context
import androidx.core.graphics.createBitmap
import androidx.core.graphics.toColorInt
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
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
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.roundToInt

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
internal class GoogleMapEngine(val view: MapView) : MapEngineState {
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
    return remember(context, lifecycle) { GoogleMapEngine(MapView(context).apply { onCreate(null) }) }
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
            val overlays = GoogleOverlays(context, map, camera) { currentOnTap(it) }
            map.setOnCameraIdleListener {
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
    LaunchedEffect(map, mapType, dark) {
        map ?: return@LaunchedEffect
        map.mapType = mapType.mapType
        map.setMapColorScheme(if (dark) MapColorScheme.DARK else MapColorScheme.LIGHT)
    }
    LaunchedEffect(engine.camera, bottomInset) { (engine.camera as? GoogleCamera)?.setBottomInset(bottomInset) }
    key(view) { AndroidView(factory = { view }, modifier = modifier) }
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

/** A hop's soft halo (tappable) and thin core, with what was last sent to Google for them. */
private class HopLines(val halo: Polyline, val core: Polyline) {
    var points: List<Coordinate>? = null
    var haloColor = 0
    var coreColor = 0
    var route: String? = null
    var visible = true
}

/** Route frames sent to Google at most ~30 times a second. */
private const val FRAME_INTERVAL_MS = 33L
/** Fades change a line's colour in 1/32 steps rather than on every frame. */
private const val OPACITY_STEPS = 32f

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
    private val onTap: (MapTap) -> Boolean) : MapOverlays {
    private val screenDensity = context.resources.displayMetrics.density
    private val icons = mutableMapOf<String, BitmapDescriptor>()
    private val markers = MarkerManager(map)
    private val nodes = ClusterManager<NodeItem>(context, map, markers)
    private val renderer = NodeRenderer()
    private val replayNodes = markers.newCollection()
    private val anchors = markers.newCollection()
    private val decorations = markers.newCollection()
    private var items: List<NodeItem> = emptyList()
    private val anchorMarkers = mutableMapOf<String, Marker>()
    /** Drawn route hops by route and hop, and hidden ones kept for reuse. */
    private val hopLines = mutableMapOf<String, HopLines>()
    private val spareLines = ArrayDeque<HopLines>()
    private val heads = mutableListOf<Marker>()
    private val rings = mutableListOf<Circle>()
    private var headsShown = 0
    private var ringsShown = 0
    private var lastFrameAt = 0L
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
        anchors.setOnMarkerClickListener { marker -> tapNode(marker.tag as? String); true }
        decorations.setOnMarkerClickListener { true }
        map.setOnPolylineClickListener { line -> (line.tag as? String)?.let { key -> onTap(MapTap(null, false, null, { listOf(key) })) } }
        map.setOnMapClickListener { point -> tapNear(point) }
    }

    fun onCameraIdle() = nodes.onCameraIdle()

    private fun tapNode(key: String?) { if (key != null) onTap(MapTap(key, true, null, { emptyList() })) }

    /** Small markers are hard to hit exactly: a tap near one (24 dp) picks the nearest, as on CARTO. */
    private fun tapNear(point: LatLng) {
        val tap = map.projection.toScreenLocation(point)
        val shown = items.mapNotNull { item -> renderer.getMarker(item)?.let { item.marker.publicKey to it.position } } +
            replayNodes.markers.map { (it.tag as? String) to it.position }
        val nearest = shown.mapNotNull { (key, position) ->
            key ?: return@mapNotNull null
            val screen = map.projection.toScreenLocation(position)
            key to hypot((screen.x - tap.x).toDouble(), (screen.y - tap.y).toDouble())
        }.minByOrNull { it.second }?.takeIf { it.second <= 24 * screenDensity }
        onTap(MapTap(nearest?.first, nearest != null && nearest.second <= 12 * screenDensity, null, { emptyList() }))
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
        replayNodes.clear()
        if (!grouped) nodes.forEach { node ->
            replayNodes.addMarker(markerOptions(node.coordinate, nodeIcon(node.role))).apply { tag = node.publicKey }
        }
    }

    /** Node names come in a later step (Google markers have no text labels of their own). */
    override fun setNodeLabelsVisible(visible: Boolean) = Unit

    override fun setRouteAnchors(markers: List<MapMarker>) {
        val wanted = markers.associateBy { it.anchorId }
        anchorMarkers.keys.filter { it !in wanted }.forEach { anchors.remove(anchorMarkers.remove(it)) }
        wanted.forEach { (id, marker) ->
            if (id !in anchorMarkers) anchorMarkers[id] = anchors.addMarker(markerOptions(marker.coordinate,
                dot("anchor:${marker.role}", 6f, roleColors[marker.role.lowercase()] ?: 0xFF65DDB4.toInt(), 2f, 0xFFFFFFFF.toInt())).zIndex(1f)).apply { tag = marker.publicKey }
        }
    }

    /**
     * Google keeps every line, marker and circle as its own object and queues each change, so a
     * frame only sends what changed: lines keep their identity (route and hop), fades move in
     * small steps, and frames are capped at ~30 a second. Sending every object on every display
     * frame grew Google's queue until the app ran out of memory. The final, empty frame always
     * goes through so nothing is left on the map.
     */
    override fun setRouteFrame(frame: RouteFrame) {
        val now = android.os.SystemClock.uptimeMillis()
        val empty = frame.lines.isEmpty() && frame.heads.isEmpty() && frame.rings.isEmpty()
        if (!empty && now - lastFrameAt < FRAME_INTERVAL_MS) return
        lastFrameAt = now
        val drawn = HashSet<String>(frame.lines.size)
        for (line in frame.lines) {
            val key = "${line.route}#${line.hop}"
            if (!drawn.add(key)) continue
            val hop = hopLines.getOrPut(key) { spareLines.removeFirstOrNull() ?: HopLines(map.addPolyline(lineOptions(6f).clickable(true)), map.addPolyline(lineOptions(2f))) }
            if (hop.points != line.points) {
                val points = line.points.map { LatLng(it.latitude, it.longitude) }
                hop.halo.points = points; hop.core.points = points; hop.points = line.points
            }
            val color = parseColor(line.color)
            val opacity = (line.opacity * OPACITY_STEPS).roundToInt() / OPACITY_STEPS
            val halo = withAlpha(color, opacity * 0.18f)
            val core = withAlpha(color, opacity * 0.85f)
            if (hop.haloColor != halo) { hop.halo.color = halo; hop.haloColor = halo }
            if (hop.coreColor != core) { hop.core.color = core; hop.coreColor = core }
            if (hop.route != line.route) { hop.halo.tag = line.route; hop.route = line.route }
            if (!hop.visible) { hop.halo.isVisible = true; hop.core.isVisible = true; hop.visible = true }
        }
        hopLines.keys.filter { it !in drawn }.forEach { key ->
            val hop = hopLines.remove(key)!!
            hop.halo.isVisible = false; hop.core.isVisible = false; hop.visible = false
            spareLines.addLast(hop)
        }
        frame.heads.forEachIndexed { index, (point, color) ->
            val icon = "head:$color"
            heads.pooled(index) { decorations.addMarker(markerOptions(point, headIcon(color)).zIndex(2f)).apply { tag = icon } }.apply {
                position = LatLng(point.latitude, point.longitude)
                if (tag != icon) { setIcon(headIcon(color)); tag = icon }
                if (index >= headsShown) isVisible = true
            }
        }
        (frame.heads.size until headsShown).forEach { heads[it].isVisible = false }
        headsShown = frame.heads.size
        // Rings are sized on screen; circles are sized on the ground, so convert at the current zoom.
        val zoom = if (frame.rings.isEmpty()) 0f else map.cameraPosition.zoom
        frame.rings.forEachIndexed { index, ring ->
            val metresPerDp = 40_075_016.686 * cos(Math.toRadians(ring.center.latitude)) / (256 * 2.0.pow(zoom.toDouble()))
            rings.pooled(index) { map.addCircle(CircleOptions().center(LatLng(0.0, 0.0)).radius(1.0).fillColor(0).zIndex(1f)) }.apply {
                center = LatLng(ring.center.latitude, ring.center.longitude)
                radius = ring.radius * metresPerDp
                strokeWidth = ring.width * screenDensity
                strokeColor = withAlpha(parseColor(ring.color), ring.opacity)
                if (index >= ringsShown) isVisible = true
            }
        }
        (frame.rings.size until ringsShown).forEach { rings[it].isVisible = false }
        ringsShown = frame.rings.size
    }

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

    private fun <T> MutableList<T>.pooled(index: Int, create: () -> T): T = getOrNull(index) ?: create().also { add(it) }

    private fun lineOptions(widthDp: Float) = PolylineOptions().width(widthDp * screenDensity)
        .startCap(RoundCap()).endCap(RoundCap()).jointType(JointType.ROUND).zIndex(1f)

    private fun markerOptions(point: Coordinate, icon: BitmapDescriptor) = MarkerOptions()
        .position(LatLng(point.latitude, point.longitude)).icon(icon).anchor(0.5f, 0.5f)

    private fun nodeIcon(role: String) = dot("node:$role", 5f, roleColors[role.lowercase()] ?: 0xFF299EFF.toInt(), 1f, 0xFFC5E4FA.toInt())
    private fun headIcon(color: String) = dot("head:$color", 3.5f, 0xFFFFFFFF.toInt(), 1.5f, parseColor(color))

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

private val parsedColors = mutableMapOf<String, Int>()
private fun parseColor(hex: String) = parsedColors.getOrPut(hex) { hex.toColorInt() }
private fun withAlpha(color: Int, opacity: Float) = (color and 0x00FFFFFF) or ((opacity.coerceIn(0f, 1f) * 255).toInt() shl 24)
