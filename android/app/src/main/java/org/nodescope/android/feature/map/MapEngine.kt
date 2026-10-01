package org.nodescope.android.feature.map

import android.view.Choreographer
import kotlinx.coroutines.suspendCancellableCoroutine
import org.nodescope.android.core.model.Coordinate
import org.nodescope.android.core.model.LivePacket
import org.nodescope.android.core.model.MeshNode
import org.nodescope.android.core.model.MeshObserver
import kotlin.coroutines.resume
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.sinh
import kotlin.math.tan

/*
 * The map screen's view of whichever engine draws the map (MapLibre with CARTO today; the
 * Google Maps SDK is planned as an option). Everything here is engine-neutral: the screen keeps
 * the data, filters, selection, replay state and timing, and hands engines only what to draw.
 */

/** A marker to draw: a node (tappable, with a label) or an observer endpoint of a live route. */
internal data class MapMarker(val coordinate: Coordinate, val role: String, val publicKey: String? = null, val name: String? = null) {
    /** Identifies a temporary route marker, so a node on several routes is drawn once. */
    val anchorId: String get() = publicKey?.let { "node:$it" } ?: "observer:${coordinate.latitude},${coordinate.longitude}"
}

/** Node markers, at their [positions] when spread apart (co-located nodes), else their own. */
internal fun nodeMarkers(nodes: List<MeshNode>, positions: Map<String, Coordinate> = emptyMap()): List<MapMarker> = nodes.mapNotNull { node ->
    (positions[node.publicKey] ?: node.coordinate)?.let { MapMarker(it, node.role, node.publicKey, node.displayName) }
}

/** Endpoints stay visible while the grouped node markers continue their normal layout. */
internal fun routeAnchors(packet: LivePacket, nodes: List<MeshNode>, observers: List<MeshObserver>,
    lookup: Map<String, MeshNode> = nodeLookup(nodes)): List<MapMarker> {
    val resolved = resolvedRouteNodes(packet, nodes, lookup).filterNotNull().distinctBy { it.publicKey }
    val nodeCoordinates = resolved.mapNotNull { it.coordinate }.toSet()
    val endpoints = packetRoute(packet, nodes, observers, lookup).flatten().distinct().filterNot { it in nodeCoordinates }
        .map { MapMarker(it, "observer") }
    return nodeMarkers(resolved) + endpoints
}

/** A camera move, described without any engine's types. */
internal sealed interface CameraMove {
    data class Center(val coordinate: Coordinate, val zoom: Double) : CameraMove
    /** Fit [bounds] inside the map, kept clear of [insets] (pixels). */
    data class Fit(val bounds: CameraTarget.Bounds, val insets: FramingInsets) : CameraMove
    data object ZoomIn : CameraMove
    data object ZoomOut : CameraMove
}

/** Camera position as saved across configuration changes: latitude, longitude, zoom, bearing, tilt. */
internal typealias SavedCamera = List<Double>

/** Everything the map screen needs from an engine, whichever one draws the map. */
internal interface MapEngineState {
    /** Camera control once the map is ready (null before). */
    val camera: MapCamera?
    /** Drawing once the base map has loaded (null while it loads). */
    val overlays: MapOverlays?
    val failed: Boolean
    val loading: Boolean
    /** The engine's logo, relative to the map, when it can be found (the node count sits above it). */
    val logoFrame: android.graphics.Rect?
}

/**
 * Camera control, available once an engine's map is ready. Screen positions are in pixels.
 * Zoom levels are always MapLibre's (512-pixel tiles); engines with another scale convert.
 */
internal interface MapCamera {
    val zoom: Double
    val width: Int
    val height: Int
    fun visibleBounds(): CameraTarget.Bounds
    fun toScreen(point: Coordinate): Pair<Float, Float>
    /** The zoom that [bounds] would be fitted at inside [insets], if it can be fitted. */
    fun fittedZoom(bounds: CameraTarget.Bounds, insets: FramingInsets): Double?
    fun jump(move: CameraMove)
    /** Animates over [durationMs], or the engine's default duration when null. */
    fun animate(move: CameraMove, durationMs: Int? = null)
    /** Animates and suspends until the move finishes or is interrupted. */
    suspend fun animateAndWait(move: CameraMove, durationMs: Int)
    /** Suspends until the map has finished loading and drawing what is on screen. */
    suspend fun awaitIdle()
}

/** Drawing on top of the base map, available once the base map has loaded. */
internal interface MapOverlays {
    /** The node markers; [grouped] ones may cluster, others (a route-only replay) never do. */
    fun setNodes(nodes: List<MapMarker>, grouped: Boolean)
    fun setNodeLabelsVisible(visible: Boolean)
    /** Temporary markers for the nodes and observers of the routes on screen. */
    fun setRouteAnchors(markers: List<MapMarker>)
    /** One animation frame of live or replayed routes. */
    fun setRouteFrame(frame: RouteFrame)
    fun setUserLocation(point: Coordinate?)
    /** The debug map lab's sample route and its moving packet. */
    fun setSampleRoute(route: List<Coordinate>)
    fun setSamplePacket(point: Coordinate?)
}

/**
 * What a tap on the map touched, nearest first. The screen decides what the tap does: a node
 * right under the finger wins over a route under it, and a group expands only when no node is near.
 */
internal class MapTap(
    val nodeKey: String?,
    /** Whether that node is right under the finger rather than just near it. */
    val nodeUnderFinger: Boolean,
    val expandGroup: (() -> Unit)?,
    /** Keys of the drawn routes under the finger (looked up only when needed). */
    val routeKeys: () -> List<String>,
)

/** iOS parity: names appear only when the view is close (≤ 0.08° tall) and shows at most 60 nodes. */
internal fun showsNodeLabels(latitudeSpan: Double, visibleNodes: Int) = latitudeSpan <= 0.08 && visibleNodes <= 60

internal fun nodeLabelsVisible(bounds: CameraTarget.Bounds, nodes: List<MeshNode>): Boolean {
    val span = bounds.north - bounds.south
    val visible = if (span > 0.08) Int.MAX_VALUE else nodes.count { node -> node.coordinate?.let(bounds::contains) == true }
    return showsNodeLabels(span, visible)
}

/** The smallest bounds holding every point (which must not be empty). */
internal fun boundsOf(points: List<Coordinate>) = CameraTarget.Bounds(points.maxOf { it.latitude }, points.maxOf { it.longitude },
    points.minOf { it.latitude }, points.minOf { it.longitude })

internal val CameraTarget.Bounds.center get() = Coordinate((north + south) / 2, (east + west) / 2)

/**
 * Calls [frame] from the display's own frame callbacks, with each frame's vsync time in
 * nanoseconds, until it returns false. Drawing inside the callback, timed by vsync, moves things
 * by even steps; resuming a coroutine per frame instead added a variable delay and sometimes
 * slipped an update into the next frame, which made moving routes twitch. It also leaves
 * Compose's UI clock idle.
 */
internal suspend fun runMapFrames(frame: (vsyncNanos: Long) -> Boolean) = suspendCancellableCoroutine<Unit> { continuation ->
    val choreographer = Choreographer.getInstance()
    val callback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!continuation.isActive) return
            val more = try { frame(frameTimeNanos) } catch (error: Throwable) { continuation.resumeWith(Result.failure(error)); return }
            if (more) choreographer.postFrameCallback(this) else continuation.resume(Unit)
        }
    }
    choreographer.postFrameCallback(callback)
    continuation.invokeOnCancellation { choreographer.removeFrameCallback(callback) }
}

/** Web Mercator in MapLibre's zoom scale: the world is 512 × 2^zoom dp wide. x and y run 0…1. */
internal object Mercator {
    private const val MAX_LATITUDE = 85.05112878
    fun x(longitude: Double) = (longitude + 180) / 360
    fun y(latitude: Double): Double {
        val radians = Math.toRadians(latitude.coerceIn(-MAX_LATITUDE, MAX_LATITUDE))
        return (1 - ln(tan(PI / 4 + radians / 2)) / PI) / 2
    }
    fun longitude(x: Double) = x * 360 - 180
    fun latitude(y: Double) = Math.toDegrees(atan(sinh(PI * (1 - 2 * y))))
    fun worldPixels(zoom: Double, density: Float) = 512.0 * 2.0.pow(zoom) * density
    /** [point] moved by a screen offset in pixels (+y is south) at [zoom]. */
    fun offset(point: Coordinate, zoom: Double, dx: Double, dy: Double, density: Float): Coordinate {
        val world = worldPixels(zoom, density)
        return Coordinate(latitude(y(point.latitude) + dy / world), longitude(x(point.longitude) + dx / world))
    }
}

/**
 * The camera that fits [bounds] inside a [width] × [height] pixel map, clear of [insets]: the
 * centre of the whole map and the zoom (MapLibre scale), as MapLibre's own bounds fitting does.
 */
internal fun fitCamera(bounds: CameraTarget.Bounds, width: Int, height: Int, insets: FramingInsets, density: Float, maxZoom: Double = 21.0): CameraMove.Center {
    val west = Mercator.x(bounds.west); val east = Mercator.x(bounds.east)
    val north = Mercator.y(bounds.north); val south = Mercator.y(bounds.south)
    val clearWidth = (width - insets.left - insets.right).coerceAtLeast(1)
    val clearHeight = (height - insets.top - insets.bottom).coerceAtLeast(1)
    val base = Mercator.worldPixels(0.0, density)
    val zoomX = if (east > west) log2(clearWidth / ((east - west) * base)) else maxZoom
    val zoomY = if (south > north) log2(clearHeight / ((south - north) * base)) else maxZoom
    val zoom = minOf(zoomX, zoomY, maxZoom).coerceAtLeast(0.0)
    // The bounds' centre sits in the middle of the clear area, off the map's centre by half the inset difference.
    val world = Mercator.worldPixels(zoom, density)
    val x = (west + east) / 2 - (insets.left - insets.right) / 2.0 / world
    val y = (north + south) / 2 - (insets.top - insets.bottom) / 2.0 / world
    return CameraMove.Center(Coordinate(Mercator.latitude(y), Mercator.longitude(x)), zoom)
}

/** A named section in system traces (Perfetto), costing nothing when no trace is recording. */
internal inline fun <T> traced(name: String, block: () -> T): T {
    android.os.Trace.beginSection(name)
    try { return block() } finally { android.os.Trace.endSection() }
}
