package org.nodescope.android.feature.map

import android.view.Choreographer
import kotlinx.coroutines.suspendCancellableCoroutine
import org.nodescope.android.core.model.Coordinate
import org.nodescope.android.core.model.LivePacket
import org.nodescope.android.core.model.MeshNode
import org.nodescope.android.core.model.MeshObserver
import kotlin.coroutines.resume

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
internal fun routeAnchors(packet: LivePacket, nodes: List<MeshNode>, observers: List<MeshObserver>): List<MapMarker> {
    val resolved = resolvedRouteNodes(packet, nodes).filterNotNull().distinctBy { it.publicKey }
    val nodeCoordinates = resolved.mapNotNull { it.coordinate }.toSet()
    val endpoints = packetRoute(packet, nodes, observers).flatten().distinct().filterNot { it in nodeCoordinates }
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

/** Camera control, available once an engine's map is ready. Screen positions are in pixels. */
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

/** Native map updates use the display clock without keeping Compose's UI clock busy. */
internal suspend fun awaitMapFrame() = suspendCancellableCoroutine<Unit> { continuation ->
    val choreographer = Choreographer.getInstance()
    val callback = Choreographer.FrameCallback { if (continuation.isActive) continuation.resume(Unit) }
    choreographer.postFrameCallback(callback)
    continuation.invokeOnCancellation { choreographer.removeFrameCallback(callback) }
}
