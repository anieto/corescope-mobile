package org.nodescope.android.feature.map

import org.nodescope.android.core.model.Coordinate
import org.nodescope.android.core.model.LivePacket
import org.nodescope.android.core.model.MeshNode
import org.nodescope.android.core.model.MeshObserver
import org.nodescope.android.core.model.packetEpoch
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/** Live route timing shared with iOS 0.7.2 (`ActivePing` / `MapTrafficCanvas`). */
internal object RouteTiming {
    const val HOP_TRAVEL = 660L
    const val HOLD = 450L
    const val FADE = 550L
    const val ARRIVAL_PULSE = 800L
    const val SOLO_PULSE = 3_000L
    /** History packets are shown already arrived and fade steadily over this window. */
    const val HISTORY_FADE = 12_000L
    /** iOS replay: slower hops, one at a time, then the whole route holds and fades together. */
    const val REPLAY_HOP_TRAVEL = 850L
    const val REPLAY_HOLD = 750L
    const val REPLAY_FADE = 650L
    /** iOS keeps at most 40 anchored hops and 20 moving/pulsing ones during live traffic. */
    const val MAX_TRANSIENT_ROUTES = 20

    /**
     * Live routes redraw at most this often (~30 per second). Each redraw rebuilds the route
     * sources on the main thread; at the display's full 60–120 Hz a busy network kept the CPU
     * working continuously. Replays keep the full rate.
     */
    const val LIVE_FRAME_INTERVAL = 33L
}

/**
 * One hop. [curvature] bows it gently (iOS 0.07), so a packet that doubles back between nearby
 * nodes shows two arcs rather than one line drawn twice. A [dashed] hop bridges hops the analyzer
 * couldn't resolve: drawn faint and dashed, with no arrival ring, since no relay is claimed.
 */
internal class RouteHop(val start: Coordinate, val end: Coordinate, val startsAt: Long, val fadeStartsAt: Long, val fadeDuration: Long,
    val travel: Long = RouteTiming.HOP_TRAVEL, val curvature: Double = 0.0, val dashed: Boolean = false) {
    private val track = if (curvature == 0.0) RouteTrack(listOf(start, end)) else null
    fun position(fraction: Float): Coordinate = track?.position(fraction) ?: curvePoint(start, end, curvature, fraction.toDouble())
    /** The hop from its start to [fraction] of the way: two points straight, enough to look smooth curved. */
    fun points(fraction: Float = 1f): List<Coordinate> {
        if (track != null) return listOf(start, position(fraction))
        val samples = maxOf(2, kotlin.math.ceil(16 * fraction).toInt())
        return (0..samples).map { curvePoint(start, end, curvature, fraction.toDouble() * it / samples) }
    }
}

/** A quadratic curve in Web Mercator space, so it looks the same at every zoom (iOS `ActivePing`). */
internal fun curvePoint(start: Coordinate, end: Coordinate, curvature: Double, t: Double): Coordinate {
    val ax = Mercator.x(start.longitude); val ay = Mercator.y(start.latitude)
    var bx = Mercator.x(end.longitude); val by = Mercator.y(end.latitude)
    // The short way round across the antimeridian.
    if (bx - ax > 0.5) bx -= 1.0 else if (ax - bx > 0.5) bx += 1.0
    val dx = bx - ax; val dy = by - ay
    val cx = (ax + bx) / 2 + dy * curvature; val cy = (ay + by) / 2 - dx * curvature
    val u = 1 - t
    val x = u * u * ax + 2 * u * t * cx + t * t * bx
    val y = u * u * ay + 2 * u * t * cy + t * t * by
    return Coordinate(Mercator.latitude(y), Mercator.longitude(((x % 1.0) + 1.0) % 1.0))
}

/** Bow of live and replayed hops (iOS). */
internal const val HOP_CURVATURE = 0.07

/**
 * How long a hop takes (iOS `hopTravelTime`), in ms. A hop too short to see at [zoom] (under
 * 6 dp) passes straight through: given its usual time, the head sat wiggling in one spot and a
 * route that steps out to a neighbour and back read as ping-pong. Otherwise a steady
 * [kilometresPerSecond] when given, else gentle (logarithmic) growth reaching [longest] at 40 km.
 */
internal fun hopTravelMs(start: Coordinate, end: Coordinate, shortest: Long, longest: Long, zoom: Double?,
    kilometresPerSecond: Double? = null): Long {
    if (zoom != null) {
        val world = 512.0 * Math.pow(2.0, zoom)
        val dx = (Mercator.x(end.longitude) - Mercator.x(start.longitude)) * world
        val dy = (Mercator.y(end.latitude) - Mercator.y(start.latitude)) * world
        if (kotlin.math.hypot(dx, dy) < 6) return 50
    }
    val kilometres = distanceKm(start, end)
    if (kilometresPerSecond != null) return (kilometres / kilometresPerSecond * 1000).toLong().coerceIn(shortest, longest)
    val growth = (longest - shortest) / kotlin.math.log2(41.0)
    return minOf(shortest + (growth * kotlin.math.log2(1 + kilometres)).toLong(), longest)
}

internal fun distanceKm(a: Coordinate, b: Coordinate): Double {
    val dLat = Math.toRadians(b.latitude - a.latitude); val dLon = Math.toRadians(b.longitude - a.longitude)
    val h = sin(dLat / 2).let { it * it } + cos(Math.toRadians(a.latitude)) * cos(Math.toRadians(b.latitude)) * sin(dLon / 2).let { it * it }
    return 2 * 6371.0 * kotlin.math.asin(kotlin.math.sqrt(h.coerceIn(0.0, 1.0)))
}

/**
 * One observed packet. Hops within a resolved subchain travel one after another; separate
 * subchains (split by unresolved hops) start together, as on iOS. Isolated known positions pulse.
 *
 * A [historical] packet from recent history has already arrived at [receivedAt] (its packet
 * timestamp): every hop is shown complete and fades together over [RouteTiming.HISTORY_FADE],
 * without replaying travel or pulsing isolated points.
 */
internal class LiveRoute(val key: String, val receivedAt: Long, val color: String, val anchors: List<MapMarker>,
    subchains: List<List<Coordinate>>, val historical: Boolean = false, val replay: Boolean = false,
    /** The observed packet, for route details when the route is tapped (none for a replay). */
    val packet: org.nodescope.android.core.model.LivePacket? = null,
    /** When the packet was observed, for route details; [receivedAt] is when the animation starts. */
    val observedAt: Long = receivedAt,
    /** The map's zoom when the route was built, so hops too short to see pass straight through. */
    zoom: Double? = null) {
    val hops: List<RouteHop> = if (replay) replayHops(subchains, receivedAt) else liveHops(subchains, receivedAt, historical, zoom)
    val pulses: List<Coordinate> = if (historical || replay) emptyList() else subchains.filter { it.size == 1 }.map { it.single() }
    private val startsAt: Long = hops.minOfOrNull { it.startsAt } ?: receivedAt
    val endsAt: Long = max(
        hops.maxOfOrNull { it.fadeStartsAt + it.fadeDuration } ?: receivedAt,
        if (pulses.isEmpty()) receivedAt else receivedAt + RouteTiming.SOLO_PULSE,
    )
    fun isActive(now: Long) = now >= startsAt && now < endsAt
}

/** One resolved route of a packet, placed on the map: coordinate chains and the nodes along it. */
data class ReplayRoute(val subchains: List<List<Coordinate>>, val nodes: List<MeshNode>, val hops: Int,
    /** The route and who heard it, for naming it in the replay controls. */
    val option: org.nodescope.android.feature.packets.RouteOption? = null,
    /** The observers it ends at (name and position), drawn as the route's labelled endpoints. */
    val receivers: List<Pair<String, Coordinate>> = emptyList())

/**
 * "Replay on map" from packet details (iOS `PacketReplayStore`): every route, and the one chosen.
 * [packetId] is the replayed packet's group id (its hash), so the live packets panel can
 * highlight the same packet the map is showing.
 */
data class RouteReplay(val id: Long, val routes: List<ReplayRoute>, val selected: Int, val packetId: String? = null,
    /** The routes and their observers, so more can be merged in while the replay runs. */
    val options: List<org.nodescope.android.feature.packets.RouteOption> = emptyList())

/** One hop's line; [route] and [hop] identify it from frame to frame. */
internal data class RouteLine(val points: List<Coordinate>, val color: String, val opacity: Float, val route: String = "", val hop: Int = 0,
    /** A bridge over unresolved hops: drawn dashed and fainter. */
    val dashed: Boolean = false)
internal data class RouteRing(val center: Coordinate, val color: String, val radius: Float, val width: Float, val opacity: Float)
internal data class RouteFrame(val lines: List<RouteLine>, val heads: List<Pair<Coordinate, String>>, val rings: List<RouteRing>)

/**
 * Live hops follow one another across the whole path (iOS): each takes 0.3–1.0 s by its real
 * length, and the pieces of a path split by unresolved hops are joined by a quick dashed bridge.
 * A [historical] route has already arrived: every hop shows complete and fades over
 * [RouteTiming.HISTORY_FADE].
 */
private fun liveHops(subchains: List<List<Coordinate>>, startsAt: Long, historical: Boolean, zoom: Double?): List<RouteHop> {
    val hops = mutableListOf<RouteHop>()
    var elapsed = 0L
    var previousEnd: Coordinate? = null
    for (chain in subchains) {
        val from = previousEnd; val to = chain.firstOrNull()
        if (from != null && to != null && from != to) {
            hops += if (historical) RouteHop(from, to, startsAt - 200, startsAt - 200, RouteTiming.HISTORY_FADE, 200, HOP_CURVATURE, dashed = true)
            else RouteHop(from, to, startsAt + elapsed, startsAt + elapsed + 200 + RouteTiming.HOLD, RouteTiming.FADE, 200, HOP_CURVATURE, dashed = true)
            elapsed += 200
        }
        previousEnd = chain.lastOrNull() ?: previousEnd
        for ((a, b) in chain.zipWithNext()) {
            val travel = hopTravelMs(a, b, 300, 1_000, zoom)
            hops += if (historical) {
                val arrived = startsAt - travel
                RouteHop(a, b, arrived, arrived, RouteTiming.HISTORY_FADE, travel, HOP_CURVATURE)
            } else RouteHop(a, b, startsAt + elapsed, startsAt + elapsed + travel + RouteTiming.HOLD, RouteTiming.FADE, travel, HOP_CURVATURE)
            elapsed += travel
        }
    }
    return hops
}

/** Replay hops travel strictly one after another across every subchain, then fade as one route. */
private fun replayHops(subchains: List<List<Coordinate>>, startsAt: Long): List<RouteHop> {
    val pairs = subchains.filter { it.size > 1 }.flatMap { it.zipWithNext() }
    val fadeStart = startsAt + pairs.size * RouteTiming.REPLAY_HOP_TRAVEL + RouteTiming.REPLAY_HOLD
    return pairs.mapIndexed { index, (a, b) ->
        RouteHop(a, b, startsAt + index * RouteTiming.REPLAY_HOP_TRAVEL, fadeStart, RouteTiming.REPLAY_FADE, RouteTiming.REPLAY_HOP_TRAVEL)
    }
}

/**
 * A hop draws itself progressively with a small head, sends an arrival ring from its end,
 * holds briefly and fades. With system animations off, hops appear complete and nothing moves.
 */
internal fun routeFrame(routes: List<LiveRoute>, now: Long, animate: Boolean): RouteFrame {
    val lines = mutableListOf<RouteLine>()
    val heads = mutableListOf<Pair<Coordinate, String>>()
    val rings = mutableListOf<RouteRing>()
    val transient = routes.sortedByDescending { it.receivedAt }.take(RouteTiming.MAX_TRANSIENT_ROUTES).toSet()
    for (route in routes) {
        for ((index, hop) in route.hops.withIndex()) {
            val elapsed = now - hop.startsAt
            if (elapsed < 0) continue
            val strength = if (hop.dashed) 0.6f else 1f
            if (animate && elapsed < hop.travel) {
                if (route !in transient) continue
                val fraction = elapsed.toFloat() / hop.travel
                lines += RouteLine(hop.points(fraction), route.color, strength, route.key, index, hop.dashed)
                heads += hop.position(fraction) to route.color
                continue
            }
            val fade = ((now - hop.fadeStartsAt).toFloat() / hop.fadeDuration).coerceIn(0f, 1f)
            if (fade < 1f) lines += RouteLine(hop.points(), route.color, (1f - fade) * strength, route.key, index, hop.dashed)
            val arrival = elapsed - hop.travel
            if (animate && route in transient && !hop.dashed && arrival < RouteTiming.ARRIVAL_PULSE) {
                val p = arrival.toFloat() / RouteTiming.ARRIVAL_PULSE
                rings += RouteRing(hop.end, route.color, (8 + p * 42) / 2, max(0.5f, 2.5f - p * 1.5f), (1 - p) * 0.9f)
            }
        }
        val pulseElapsed = now - route.receivedAt
        if (animate && route in transient && pulseElapsed in 0 until RouteTiming.SOLO_PULSE) {
            val p = pulseElapsed.toFloat() / RouteTiming.SOLO_PULSE
            route.pulses.forEach { point ->
                rings += RouteRing(point, route.color, (14 + p * 42) / 2, 2.5f, (1 - p) * 0.8f)
                rings += RouteRing(point, route.color, (10 + p * 20) / 2, 1.5f, (1 - p) * 0.45f)
            }
        }
    }
    return RouteFrame(lines, heads, rings)
}

/**
 * iOS parity for co-located nodes: nodes that share an exact position fan out around it on a
 * circle of constant screen size, so each stays individually tappable. The data position is
 * unchanged; only the marker moves. Recomputed when the zoom settles.
 */
internal fun spreadCoincidentNodes(nodes: List<MeshNode>, zoom: Double, radiusDp: Double = 18.0): Map<String, Coordinate> {
    val degreesPerDp = 360.0 / (512.0 * Math.pow(2.0, zoom))
    val spread = mutableMapOf<String, Coordinate>()
    nodes.filter { it.coordinate != null }.groupBy { it.coordinate!! }.forEach { (origin, group) ->
        if (group.size < 2) return@forEach
        val radius = radiusDp * max(1.0, group.size / 6.0) * degreesPerDp
        group.sortedBy { it.publicKey }.forEachIndexed { index, node ->
            val angle = index.toDouble() / group.size * 2 * Math.PI - Math.PI / 2
            // Web Mercator: a screen dp covers cos(latitude) as many degrees north-south as east-west.
            val latitude = (origin.latitude - sin(angle) * radius * cos(Math.toRadians(origin.latitude))).coerceIn(-85.0, 85.0)
            val longitude = ((origin.longitude + cos(angle) * radius + 540) % 360) - 180
            spread[node.publicKey] = Coordinate(latitude, longitude)
        }
    }
    return spread
}

/**
 * Live routes for the newest packets. Each packet's route is worked out once and reused, with
 * one node lookup shared by all packets, instead of rebuilding every route on each new packet.
 */
internal class LiveRouteBuilder {
    private var nodes: List<MeshNode>? = null
    private var observers: List<MeshObserver>? = null
    private var lookup: Map<String, MeshNode> = emptyMap()
    private val routes = HashMap<LivePacket, LiveRoute>()
    /**
     * A live route starts animating when it is first ready to draw, not when its packet arrived:
     * the time spent getting it to the map would otherwise be skipped, a jump at the start. Kept
     * per packet, so an update to the packet (more observations) never restarts its route.
     */
    private val starts = HashMap<String, Long>()

    fun build(packets: List<LivePacket>, nodes: List<MeshNode>, observers: List<MeshObserver>, filters: MapFilters,
        zoom: Double? = null): List<LiveRoute> = traced("NodeScope:routes") {
        if (nodes !== this.nodes) { this.nodes = nodes; lookup = nodeLookup(nodes); routes.clear() }
        if (observers !== this.observers) { this.observers = observers; routes.clear() }
        // Recent history appears as already-arrived routes while it is under 12 s old, as on iOS.
        val historyCutoff = System.currentTimeMillis() - RouteTiming.HISTORY_FADE
        val candidates = packets.filter { (it.isLive || packetEpoch(it) > historyCutoff) && filters.showsRoute(it) }
        // Each observer's first copy of a packet only (iOS): in a flood an observer also hears
        // repeaters echoing the packet back, and drawing those echoes read as ping-pong.
        val reached = HashSet<String>()
        val firstCopies = candidates.sortedBy { if (it.isLive) it.receivedAt else packetEpoch(it) }
            .filterTo(HashSet()) { reached.add("${it.hash}|${(it.observerId ?: it.observerName)?.lowercase()}") }
        val shown = candidates.filter { it in firstCopies }.take(40)
        val result = shown.map { packet ->
            routes.getOrPut(packet) {
                val color = listOf("#66B9FF", "#FFC27A", "#65DDB4", "#BEA1FF")[(packet.hash.hashCode() and Int.MAX_VALUE) % 4]
                val observed = if (packet.isLive) packet.receivedAt else packetEpoch(packet)
                // Only for packets that just arrived: older ones (back after a filter change) keep
                // their own timing rather than replaying.
                val start = if (packet.isLive) starts.getOrPut(packet.key) {
                    val now = System.currentTimeMillis()
                    if (now - observed < 2_000) maxOf(observed, now) else observed
                } else observed
                LiveRoute(packet.key, start, color, routeAnchors(packet, nodes, observers, lookup),
                    packetRoute(packet, nodes, observers, lookup), historical = !packet.isLive, packet = packet, observedAt = observed, zoom = zoom)
            }
        }
        routes.keys.retainAll(shown.toSet())
        starts.keys.retainAll(shown.mapTo(HashSet()) { it.key })
        result
    }
}

/**
 * Whether this display frame should rebuild the live route overlays. Routes entering or leaving
 * always draw at once, and replays draw every frame; otherwise moving routes redraw at most every
 * [RouteTiming.LIVE_FRAME_INTERVAL] ms.
 */
internal fun shouldDrawRouteFrame(now: Long, lastDrawnAt: Long?, routesChanged: Boolean, replaying: Boolean): Boolean =
    routesChanged || replaying || lastDrawnAt == null || now - lastDrawnAt >= RouteTiming.LIVE_FRAME_INTERVAL


/** Below this zoom, ungrouped markers that would draw on top of one another are thinned out. */
internal const val THIN_MARKERS_BELOW_ZOOM = 9.0

/**
 * With Group Nearby Nodes off and the map zoomed out (iOS `withoutCoveredMarkers`), nodes within
 * about 2.5 dp of each other draw as one dot anyway. Keeps one per cell (a repeater over a room
 * over a companion over a sensor, then by key so the pick is stable) and returns, by kept node's
 * key, the nodes it covers, so a tap there can zoom in on the stack.
 */
internal fun thinCoveredNodes(nodes: List<MeshNode>, zoom: Double): Pair<List<MeshNode>, Map<String, List<MeshNode>>> {
    if (zoom >= THIN_MARKERS_BELOW_ZOOM) return nodes to emptyMap()
    val degreesPerDp = 360.0 / (512.0 * Math.pow(2.0, zoom))
    val cell = 2.5 * degreesPerDp
    fun rank(node: MeshNode) = MAP_ROLES.indexOf(node.role.lowercase()).let { if (it < 0) MAP_ROLES.size else it }
    val best = HashMap<Pair<Long, Long>, MeshNode>()
    val covered = HashMap<Pair<Long, Long>, MutableList<MeshNode>>()
    for (node in nodes) {
        val at = node.coordinate ?: continue
        // Web Mercator: a dp spans fewer degrees of latitude than longitude away from the equator.
        val key = kotlin.math.floor(at.longitude / cell).toLong() to
            kotlin.math.floor(Mercator.y(at.latitude) * 512.0 * Math.pow(2.0, zoom) / 2.5).toLong()
        val current = best[key]
        if (current == null) { best[key] = node; continue }
        val replaces = compareValuesBy(node, current, ::rank, MeshNode::publicKey) < 0
        covered.getOrPut(key) { mutableListOf() } += if (replaces) current else node
        if (replaces) best[key] = node
    }
    val kept = best.values.sortedBy { it.publicKey }
    return kept to best.entries.mapNotNull { (key, node) -> covered[key]?.let { node.publicKey to it.toList() } }.toMap()
}
